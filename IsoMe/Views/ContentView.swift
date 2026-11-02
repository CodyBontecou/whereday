import SwiftUI
import SwiftData
import CoreLocation

struct ContentView: View {
    @Environment(\.modelContext) private var modelContext
    @State private var viewModel: LocationViewModel?
    @State private var locationManager: LocationManager?
    @AppStorage("hasCompletedOnboarding") private var hasCompletedOnboarding = false
    @AppStorage("appAppearance") private var appAppearance = AppAppearance.system.rawValue
    @State private var crashLog: String?
    @State private var pendingTrackingStart = false
    @ObservedObject private var storeManager = StoreManager.shared
    @State private var showingTrackingPaywall = false

    private var showsInitialPaywall: Bool {
        guard storeManager.hasResolvedEntitlements else { return false }
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("--review-hard-paywall") && !storeManager.isPurchased {
            return true
        }
        #endif
        return hasCompletedOnboarding && storeManager.requiresInitialPaywall
    }

    private var bypassOnboardingForWatchReplay: Bool {
        #if DEBUG && targetEnvironment(simulator)
        ProcessInfo.processInfo.arguments.contains("--watch-sync-replay")
        #else
        false
        #endif
    }

    var body: some View {
        Group {
            if let viewModel = viewModel {
                PurchaseLaunchGate(
                    hasResolvedEntitlements: storeManager.hasResolvedEntitlements,
                    requiresInitialPaywall: showsInitialPaywall
                ) {
                    PaywallView(storeManager: storeManager, context: .onboarding, isRequired: true)
                } content: {
                    if hasCompletedOnboarding || bypassOnboardingForWatchReplay {
                        Group {
                            #if DEBUG
                            if ProcessInfo.processInfo.arguments.contains("--review-upgrade-paywall") {
                                PaywallView(storeManager: .shared, context: .settings)
                            } else if ProcessInfo.processInfo.arguments.contains("--review-paywall") {
                                PaywallView(storeManager: .shared, context: .export)
                            } else if ProcessInfo.processInfo.arguments.contains("--demo-webhook-settings") {
                                NavigationStack {
                                    WebhookSettingsView()
                                }
                            } else {
                                MainTabView(viewModel: viewModel)
                            }
                            #else
                            MainTabView(viewModel: viewModel)
                            #endif
                        }
                            .onAppear {
                                startPendingTrackingIfAllowed(viewModel: viewModel)
                            }
                    } else {
                        OnboardingView(viewModel: viewModel) { shouldStartTracking in
                            pendingTrackingStart = shouldStartTracking
                            hasCompletedOnboarding = true
                        }
                    }
                }
            } else {
                ProgressView("Loading...")
            }
        }
        .onChange(of: storeManager.canTrack) { _, canTrack in
            if canTrack, hasCompletedOnboarding, let viewModel {
                startPendingTrackingIfAllowed(viewModel: viewModel)
            }
        }
        .preferredColorScheme((AppAppearance(rawValue: appAppearance) ?? .system).colorScheme)
        .background {
            if !hasCompletedOnboarding || showsInitialPaywall {
                InkPaperPalette.paper.ignoresSafeArea()
            }
        }
        .tint(!hasCompletedOnboarding || showsInitialPaywall ? InkPaperPalette.ink : TE.accent)
        .task {
            if viewModel == nil {
                let manager = LocationManager()
                ManualVisitAutomation.foregroundContext = modelContext
                locationManager = manager
                viewModel = LocationViewModel(
                    modelContext: modelContext,
                    locationManager: manager
                )

                // Wire up services that need the LocationManager
                WebhookManager.shared.attach(
                    modelContainer: modelContext.container,
                    locationManager: manager
                )

                #if DEBUG
                if ScreenshotFeatureDemoSeeder.isEnabled {
                    ScreenshotFeatureDemoSeeder.seed(in: modelContext)
                    hasCompletedOnboarding = true
                    viewModel?.loadData()
                    return
                }
                #endif

                // Migrate existing users: if they already granted permissions or turned
                // tracking on, skip onboarding automatically the first time this version runs.
                if UserDefaults.standard.object(forKey: "hasCompletedOnboarding") == nil {
                    let hasExistingSetup = manager.authorizationStatus != .notDetermined ||
                        UserDefaults.standard.bool(forKey: "isTrackingEnabled")
                    hasCompletedOnboarding = hasExistingSetup
                }
            }
        }
        .sheet(isPresented: $showingTrackingPaywall) {
            PaywallView(storeManager: storeManager, context: .onboarding)
        }
        .onReceive(NotificationCenter.default.publisher(for: .trackingAccessRequired)) { _ in
            if !storeManager.requiresInitialPaywall { showingTrackingPaywall = true }
        }
        .onReceive(NotificationCenter.default.publisher(for: .appDidBecomeActive)) { _ in
            Task { await storeManager.checkEntitlement() }
            viewModel?.loadData()
            if let viewModel {
                Task {
                    await viewModel.syncPhotosAutomaticallyIfAuthorized()
                }
            }
        }
        .onReceive(NotificationCenter.default.publisher(for: .watchLocationDataImported)) { _ in
            viewModel?.loadData()
            if let viewModel {
                Task {
                    await viewModel.syncPhotosAutomaticallyIfAuthorized(ignoresCooldown: true)
                }
            }
        }
        .onAppear {

            if let log = UserDefaults.standard.string(forKey: "lastCrashLog") {
                crashLog = log
                UserDefaults.standard.removeObject(forKey: "lastCrashLog")
            }
        }
        .alert("Previous Crash Detected", isPresented: Binding(
            get: { crashLog != nil },
            set: { if !$0 { crashLog = nil } }
        )) {
            Button("Copy to Clipboard") {
                UIPasteboard.general.string = crashLog
                crashLog = nil
            }
            Button("Dismiss", role: .cancel) { crashLog = nil }
        } message: {
            Text(crashLog ?? "")
        }
    }

    private func startPendingTrackingIfAllowed(viewModel: LocationViewModel) {
        guard pendingTrackingStart, storeManager.canTrack else { return }
        pendingTrackingStart = false
        viewModel.startTracking()
    }
}

private struct MainTabView: View {
    @Environment(ManualVisitRouter.self) private var manualVisitRouter
    let viewModel: LocationViewModel
    @State private var selectedTab: Int = MainTabView.initialTabFromLaunchArguments()
    @ObservedObject private var exportToastCenter = ExportToastCenter.shared

    var body: some View {
        TabView(selection: $selectedTab) {
            journalWorkspace(mapIsPrimary: true)
                .tabItem {
                    Label("Map", systemImage: "map.fill")
                }
                .tag(0)

            journalWorkspace(mapIsPrimary: false)
            .tabItem {
                Label("Timeline", systemImage: "calendar")
            }
            .tag(3)

            ExportView(viewModel: viewModel)
                .modifier(JournalFlowPresentation())
                .tabItem {
                    Label("Export", systemImage: "square.and.arrow.up")
                }
                .tag(1)

            SettingsView(viewModel: viewModel)
                .modifier(JournalFlowPresentation())
                .tabItem {
                    Label("Settings", systemImage: "gear")
                }
                .tag(2)
        }
        .onChange(of: manualVisitRouter.request?.id, initial: true) { _, id in
            if id != nil { selectedTab = 0 }
        }
        .onReceive(NotificationCenter.default.publisher(for: .manualVisitsChanged)) { _ in
            viewModel.loadData()
        }
        .overlay(alignment: .top) {
            if let toast = exportToastCenter.toast {
                ExportToastBanner(toast: toast)
                    .padding(.horizontal, 16)
                    .padding(.top, 12)
                    .transition(.move(edge: .top).combined(with: .opacity))
                    .zIndex(1)
            }
        }
        .task {
            await viewModel.syncPhotosAutomaticallyIfAuthorized(ignoresCooldown: true)
        }
        .animation(.spring(response: 0.28, dampingFraction: 0.86), value: exportToastCenter.toast?.id)
        .isoMeReleaseNotesSheet()
    }

    @ViewBuilder
    private func journalWorkspace(mapIsPrimary: Bool) -> some View {
        // The Duo SDK ships with Swift 6.4; older SDKs cannot resolve ArrangementView.
        #if compiler(>=6.4)
        if #available(iOS 27.1, *) {
            DuoJournalWorkspace(viewModel: viewModel, mapIsPrimary: mapIsPrimary) {
                selectedTab = 0
            }
        } else {
            singlePaneJournalWorkspace(mapIsPrimary: mapIsPrimary)
        }
        #else
        singlePaneJournalWorkspace(mapIsPrimary: mapIsPrimary)
        #endif
    }

    @ViewBuilder
    private func singlePaneJournalWorkspace(mapIsPrimary: Bool) -> some View {
        if mapIsPrimary {
            LocationMapView(viewModel: viewModel)
        } else {
            OutingsView(viewModel: viewModel) { selectedTab = 0 }
        }
    }

    private static func initialTabFromLaunchArguments() -> Int {
        #if DEBUG
        let prefix = "--default-tab="
        if let arg = ProcessInfo.processInfo.arguments.first(where: { $0.hasPrefix(prefix) }),
           let index = Int(arg.dropFirst(prefix.count)),
           (0...3).contains(index) {
            return index
        }
        #endif
        return 0
    }
}


#if compiler(>=6.4)
/// System arrangements retain the primary screen while the inner display gains a companion.
/// They also keep each page clear of the active hinge without device-name or orientation checks.
@available(iOS 27.1, *)
private struct DuoJournalWorkspace: View {
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let viewModel: LocationViewModel
    let mapIsPrimary: Bool
    let onShowOnMap: () -> Void
    @State private var focusedVisit: Visit?
    @State private var inspectedVisit: Visit?
    @State private var hasUsedCompanion = false
    @State private var coverInspector: Visit?

    private var hasCompanion: Bool { horizontalSizeClass == .regular }

    var body: some View {
        ArrangementView {
            if mapIsPrimary {
                LocationMapView(
                    viewModel: viewModel,
                    focusedVisit: focusedVisit,
                    onSelectVisit: hasCompanion || hasUsedCompanion ? { visit in
                        focusedVisit = visit
                        inspectedVisit = visit
                        if !hasCompanion { coverInspector = visit }
                    } : nil
                )
            } else {
                timeline
            }
        } secondary: {
            if mapIsPrimary {
                ZStack {
                    timeline
                        .opacity(inspectedVisit == nil ? 1 : 0)
                        .allowsHitTesting(inspectedVisit == nil)
                        .accessibilityHidden(inspectedVisit != nil)
                    if let inspectedVisit {
                        NavigationStack {
                            VisitDetailView(visit: inspectedVisit, viewModel: viewModel, showsMap: false)
                                .toolbar {
                                    ToolbarItem(placement: .topBarLeading) {
                                        Button("Timeline", systemImage: "chevron.left") {
                                            self.inspectedVisit = nil
                                        }
                                    }
                                }
                        }
                    }
                }
                .overlay(alignment: .leading) { Rectangle().fill(TE.border).frame(width: 1).allowsHitTesting(false) }
            } else {
                LocationMapView(viewModel: viewModel, focusedVisit: focusedVisit)
            }
        }
        .arrangementViewStyle(.split.axes(hasCompanion ? Axis.Set.horizontal.union(.vertical) : Axis.Set()))
        .onChange(of: hasCompanion, initial: true) { _, expanded in
            // Keep a single presentation owner while crossing to the cover display.
            if expanded {
                hasUsedCompanion = true
                coverInspector = nil
            } else {
                coverInspector = inspectedVisit
            }
        }
        .onChange(of: inspectedVisit?.id) { _, _ in
            if !hasCompanion { coverInspector = inspectedVisit }
        }
        .sheet(item: $coverInspector) { visit in
            NavigationStack {
                VisitDetailView(visit: visit, viewModel: viewModel)
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            Button("Done") {
                                inspectedVisit = nil
                                coverInspector = nil
                            }
                        }
                    }
            }
        }
    }

    private var timeline: some View {
        OutingsView(
            viewModel: viewModel,
            usesCompanionMap: hasCompanion,
            onVisitOpened: { visit in focusedVisit = visit },
            onShowOnMap: {
                if !hasCompanion { onShowOnMap() }
            }
        )
    }

}

#endif

private struct OnboardingView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let viewModel: LocationViewModel
    let onComplete: (Bool) -> Void

    @ObservedObject private var locationManager: LocationManager

    @State private var selectedPage: Int
    @State private var startTrackingWhenDone = false
    @State private var isConfiguringAutomaticPhotoSync = false
    @AppStorage(LocationViewModel.automaticPhotoSyncEnabledKey) private var automaticPhotoSyncEnabled = false

    private static let pageCount = 7
    private var pageCount: Int { Self.pageCount }
    private let permissionsPageIndex = 5

    init(viewModel: LocationViewModel, onComplete: @escaping (Bool) -> Void) {
        self.viewModel = viewModel
        self.onComplete = onComplete
        _locationManager = ObservedObject(initialValue: viewModel.locationManager)
        _selectedPage = State(initialValue: Self.initialPageFromLaunchArguments())
    }

    private static func initialPageFromLaunchArguments() -> Int {
        #if DEBUG
        let prefix = "--onboarding-page="
        if let argument = ProcessInfo.processInfo.arguments.first(where: { $0.hasPrefix(prefix) }),
           let page = Int(argument.dropFirst(prefix.count)),
           (0..<Self.pageCount).contains(page) {
            return page
        }
        #endif
        return 0
    }

    var body: some View {
        ZStack {
            OnboardingBackdrop()

            VStack(spacing: 0) {
                TabView(selection: $selectedPage) {
                    welcomePage
                        .tag(0)

                    timelinePage.tag(1)
                    routePage.tag(2)
                    exportsPage.tag(3)
                    photosPage.tag(4)
                    permissionsPage.tag(5)
                    finishPage.tag(6)
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                .animation(reduceMotion ? nil : .spring(response: 0.42, dampingFraction: 0.84), value: selectedPage)

                VStack(spacing: 8) {
                    pageIndicators
                    controls
                }
                .padding(.horizontal, 24)
                .padding(.top, 8)
                .padding(.bottom, 16)
                .background {
                    LinearGradient(colors: [InkPaperPalette.paper.opacity(0), InkPaperPalette.paper, InkPaperPalette.paper], startPoint: .top, endPoint: .bottom)
                        .ignoresSafeArea(edges: .bottom)
                }
            }
        }
        .modifier(JournalFlowPresentation())
        .tint(OnboardingPalette.accent)
        .onChange(of: locationManager.authorizationStatus) { _, newStatus in
            guard selectedPage == permissionsPageIndex else { return }
            if newStatus == .authorizedAlways {
                withAnimation(reduceMotion ? nil : .spring(response: 0.4, dampingFraction: 0.85)) {
                    selectedPage = permissionsPageIndex + 1
                }
            }
        }
    }

    private var welcomePage: some View {
        LocationOnboardingPageView(
            icon: "mappin.and.ellipse",
            eyebrow: "Welcome",
            title: "Every day\nhas a story.",
            description: "A calm, private timeline of where your day takes you — always on-device and in your control."
        ) {
            OnboardingMapPreview(style: .places)
            Label("Keep all location data on-device", systemImage: "lock.shield.fill")
                .font(.onboardingCaption)
                .foregroundStyle(OnboardingPalette.textSecondary)
        }
    }

    private var timelinePage: some View {
        LocationOnboardingPageView(
            icon: "house.and.flag.fill",
            eyebrow: "Visit timeline",
            title: "The shape\nof your day.",
            description: "Arrivals, durations, and changes throughout your day."
        ) {
            OnboardingMapPreview(style: .timeline)
        }
    }

    private var routePage: some View {
        LocationOnboardingPageView(
            icon: "map.fill",
            eyebrow: "Path visualization",
            title: "Path visualization",
            description: "Routes with start and end markers plus optional detail points."
        ) {
            OnboardingMapPreview(style: .route)
        }
    }

    private var exportsPage: some View {
        LocationOnboardingPageView(
            icon: "square.and.arrow.up",
            eyebrow: "Flexible exports",
            title: "Flexible exports",
            description: "JSON, CSV, or Markdown — whenever you need it."
        ) {
            OnboardingExportPreview()
        }
    }

    private var permissionsPage: some View {
        ScrollView(showsIndicators: false) {
            VStack(spacing: 14) {
                VStack(alignment: .leading, spacing: 14) {
                    Text("PERMISSION SETUP")
                        .font(.onboardingMicro)
                        .tracking(2.2)
                        .foregroundStyle(OnboardingPalette.textMuted)

                    Text("Your places,\nautomatically.")
                        .font(.onboardingDisplay)
                        .fixedSize(horizontal: false, vertical: true)
                        .foregroundStyle(OnboardingPalette.textPrimary)
                        .multilineTextAlignment(.leading)

                    Text("Record your days around the clock, even when Whereday is closed. Allow Always location access for automatic background visit detection.")
                        .font(.onboardingBody)
                        .foregroundStyle(OnboardingPalette.textSecondary)
                        .multilineTextAlignment(.leading)
                        .lineSpacing(3)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 40)
                .padding(.bottom, 12)

                VStack(spacing: 10) {
                    OnboardingStatusRow(
                        title: "Location access",
                        subtitle: locationManager.authorizationStatus.onboardingLabel,
                        icon: locationManager.authorizationStatus.onboardingIcon,
                        color: locationManager.authorizationStatus.onboardingColor
                    )

                    OnboardingStatusRow(
                        title: "Background tracking",
                        subtitle: locationManager.hasAlwaysPermission
                            ? String(localized: "Ready for always-on visit detection")
                            : String(localized: "Needs extended location access"),
                        icon: locationManager.hasAlwaysPermission ? "checkmark.shield.fill" : "exclamationmark.shield.fill",
                        color: locationManager.hasAlwaysPermission ? OnboardingPalette.success : OnboardingPalette.warning
                    )
                }

                permissionActionCard
            }
            .padding(.horizontal, 24)
            .padding(.bottom, 14)
            .frame(maxWidth: 560).frame(maxWidth: .infinity)
        }
        .scrollBounceBehavior(.basedOnSize)
    }

    @ViewBuilder
    private var permissionActionCard: some View {
        switch locationManager.authorizationStatus {
        case .notDetermined:
            VStack(alignment: .leading, spacing: 12) {
                Text("STEP 1 OF 2")
                    .font(.onboardingMicro)
                    .tracking(1.8)
                    .foregroundStyle(OnboardingPalette.textMuted)

                Text("Whereday uses your location to detect places you visit and trace your daily path.")
                    .font(.onboardingCaption)
                    .foregroundStyle(OnboardingPalette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)

                Button {
                    locationManager.requestWhenInUseAuthorization()
                } label: {
                    Label("Continue", systemImage: "location.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(OnboardingPrimaryButtonStyle())
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .onboardingCard()

        case .authorizedWhenInUse:
            VStack(alignment: .leading, spacing: 12) {
                Text("STEP 2 OF 2")
                    .font(.onboardingMicro)
                    .tracking(1.8)
                    .foregroundStyle(OnboardingPalette.textMuted)

                Text("Background visit detection requires extended location access so Whereday keeps recording when the app is closed.")
                    .font(.onboardingCaption)
                    .foregroundStyle(OnboardingPalette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)

                Button {
                    locationManager.requestAlwaysAuthorization()
                } label: {
                    Label("Continue", systemImage: "arrow.up.forward.app.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(OnboardingPrimaryButtonStyle())
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .onboardingCard()

        case .denied, .restricted:
            VStack(alignment: .leading, spacing: 12) {
                Text("LOCATION ACCESS IS OFF")
                    .font(.onboardingMicro)
                    .tracking(1.8)
                    .foregroundStyle(OnboardingPalette.textMuted)

                Text("Grant location access in iPhone Settings → Whereday → Location.")
                    .font(.onboardingCaption)
                    .foregroundStyle(OnboardingPalette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)

                Button {
                    openSettings()
                } label: {
                    Label("Open iPhone Settings", systemImage: "gearshape.fill")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(OnboardingSecondaryButtonStyle())
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .onboardingCard()

        case .authorizedAlways:
            HStack(spacing: 10) {
                Image(systemName: "checkmark.circle.fill")
                    .foregroundStyle(OnboardingPalette.success)

                Text("Perfect — permissions are fully configured.")
                    .font(.onboardingCaption)
                    .foregroundStyle(OnboardingPalette.textPrimary)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .onboardingCard()

        @unknown default:
            EmptyView()
        }
    }

    private var photosPage: some View {
        LocationOnboardingPageView(
            icon: "photo.on.rectangle.angled",
            eyebrow: "Photo moments",
            title: "Your places.\nYour photographs.",
            description: "Let Whereday automatically show iPhone photos on your map and outings. Photos stay in your Photos library."
        ) {
            OnboardingMapPreview(style: .photos)

            VStack(spacing: 10) {
                HStack(spacing: 8) {
                    Image(systemName: viewModel.photoLibraryAccessState.onboardingIcon)
                        .foregroundStyle(viewModel.photoLibraryAccessState.onboardingColor)
                    Text("Photos access")
                    Spacer()
                    Text(viewModel.photoLibraryAccessState.onboardingLabel)
                        .foregroundStyle(OnboardingPalette.textSecondary)
                }
                .font(.onboardingCaption)
                .onboardingCard(padding: 12)

                VStack(alignment: .leading, spacing: 12) {
                    Toggle(isOn: Binding(
                        get: { automaticPhotoSyncEnabled },
                        set: { handleAutomaticPhotoSyncToggle($0) }
                    )) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Auto-sync photo moments")
                                .font(.onboardingCaption)
                                .fontWeight(.medium)
                                .foregroundStyle(OnboardingPalette.textPrimary)

                            Text("Match photos to your visits and routes.")
                                .font(.onboardingCaption)
                                .foregroundStyle(OnboardingPalette.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)
                        }
                    }
                    .tint(OnboardingPalette.accent)
                    .disabled(isConfiguringAutomaticPhotoSync)

                    if isConfiguringAutomaticPhotoSync {
                        HStack(spacing: 8) {
                            ProgressView()
                                .controlSize(.mini)
                                .tint(OnboardingPalette.accent)

                            Text("Syncing photo metadata…")
                                .font(.onboardingCaption)
                                .foregroundStyle(OnboardingPalette.textSecondary)
                        }
                    } else if automaticPhotoSyncEnabled && viewModel.photoLibraryAccessState.canRead {
                        Text("Photo moments are enabled.")
                            .font(.onboardingCaption)
                            .foregroundStyle(OnboardingPalette.success)
                            .fixedSize(horizontal: false, vertical: true)
                    } else if viewModel.photoLibraryAccessState == .denied || viewModel.photoLibraryAccessState == .restricted {
                        VStack(alignment: .leading, spacing: 8) {
                            Text("Photos access is off. You can enable it later in iPhone Settings.")
                                .font(.onboardingCaption)
                                .foregroundStyle(OnboardingPalette.textSecondary)
                                .fixedSize(horizontal: false, vertical: true)

                            Button {
                                openSettings()
                            } label: {
                                Label("Open iPhone Settings", systemImage: "gearshape.fill")
                                    .frame(maxWidth: .infinity)
                            }
                            .buttonStyle(OnboardingSecondaryButtonStyle())
                        }
                    } else {
                        Text("Optional")
                            .font(.onboardingCaption)
                            .foregroundStyle(OnboardingPalette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .onboardingCard(padding: 12, fill: OnboardingPalette.cardSoft)
            }
        }
    }

    private var finishPage: some View {
        LocationOnboardingPageView(
            icon: "checkmark.seal",
            eyebrow: "Ready",
            title: "Ready for your\nnext chapter.",
            description: "Start now, or fine-tune tracking behavior later in Settings."
        ) {
            VStack(spacing: 12) {
                OnboardingSummaryRow(
                    icon: "location.fill",
                    title: "Location permission",
                    value: locationManager.authorizationStatus.onboardingLabel
                )

                OnboardingSummaryRow(
                    icon: "photo.on.rectangle",
                    title: "Photos",
                    value: automaticPhotoSyncEnabled && viewModel.photoLibraryAccessState.canRead
                        ? String(localized: "Auto-sync enabled")
                        : String(localized: "Off")
                )

                OnboardingSummaryRow(
                    icon: "lock.fill",
                    title: "Privacy",
                    value: String(localized: "Stored on-device")
                )
            }
            .onboardingCard(padding: 14, fill: OnboardingPalette.card)

            if locationManager.hasLocationPermission {
                Toggle(isOn: $startTrackingWhenDone) {
                    Text("Start tracking immediately")
                        .font(.onboardingBody)
                        .fontWeight(.medium)
                        .foregroundStyle(OnboardingPalette.textPrimary)
                }
                .tint(OnboardingPalette.accent)
                .onboardingCard(padding: 14, fill: OnboardingPalette.cardSoft)
            } else {
                Text("You can grant permission later from the Settings tab.")
                    .font(.onboardingCaption)
                    .foregroundStyle(OnboardingPalette.textSecondary)
                    .multilineTextAlignment(.center)
                    .lineSpacing(3)
                    .fixedSize(horizontal: false, vertical: true)
                    .onboardingCard(padding: 14, fill: OnboardingPalette.cardSoft)
            }
        }
    }

    private var pageIndicators: some View {
        HStack {
            Spacer(minLength: 0)
            HStack(spacing: 0) {
                ForEach(0..<pageCount, id: \.self) { index in
                    Button {
                        withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.25)) {
                            selectedPage = index
                        }
                    } label: {
                        Circle()
                            .fill(index == selectedPage ? OnboardingPalette.accent : .clear)
                            .frame(width: 5, height: 5)
                            .overlay(Circle().stroke(OnboardingPalette.accent.opacity(0.5), lineWidth: 1))
                            .frame(width: 28, height: 44)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(Text("Step \(index + 1) of \(pageCount)"))
                    .accessibilityAddTraits(index == selectedPage ? .isSelected : [])
                    .accessibilityIdentifier("onboarding-step-\(index)")
                }
            }

            Spacer(minLength: 0)
        }
    }

    private var controls: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(spacing: 8))
            : AnyLayout(HStackLayout(spacing: 12))
        return layout {
            if selectedPage > 0 {
                Button {
                    withAnimation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.85)) {
                        selectedPage -= 1
                    }
                } label: {
                    HStack(spacing: 6) {
                        Image(systemName: "chevron.left")
                            .font(.caption.weight(.semibold))
                        Text("Back")
                    }
                }
                .buttonStyle(OnboardingTextButtonStyle())
                .accessibilityIdentifier("onboarding-back")
            }

            if !isAwaitingPermissionRequest {
                Button {
                    handlePrimaryAction()
                } label: {
                    HStack(spacing: 6) {
                        Text(primaryButtonTitle)

                        Image(systemName: selectedPage == pageCount - 1 ? "checkmark" : "chevron.right")
                            .font(.caption.weight(.semibold))
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(OnboardingPrimaryButtonStyle())
                .accessibilityIdentifier("onboarding-next")
            }
        }
        .frame(maxWidth: 512)
        .frame(maxWidth: .infinity)
    }

    private var isAwaitingPermissionRequest: Bool {
        selectedPage == permissionsPageIndex && locationManager.authorizationStatus == .notDetermined
    }

    private var primaryButtonTitle: String {
        if selectedPage == pageCount - 1 {
            return String(localized: "Get Started")
        }

        return String(localized: "Next")
    }

    private func handlePrimaryAction() {
        guard selectedPage < pageCount - 1 else {
            completeOnboarding()
            return
        }

        withAnimation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.85)) {
            selectedPage += 1
        }
    }

    private func completeOnboarding() {
        let shouldStartTracking = startTrackingWhenDone && locationManager.hasLocationPermission
        onComplete(shouldStartTracking)
    }

    private func handleAutomaticPhotoSyncToggle(_ isEnabled: Bool) {
        automaticPhotoSyncEnabled = isEnabled

        guard isEnabled else {
            viewModel.setAutomaticPhotoSyncEnabled(false)
            return
        }

        Task { @MainActor in
            isConfiguringAutomaticPhotoSync = true
            await viewModel.requestPhotoLibraryAccessAndStartAutomaticSync()
            automaticPhotoSyncEnabled = LocationViewModel.isAutomaticPhotoSyncEnabled && viewModel.photoLibraryAccessState.canRead
            isConfiguringAutomaticPhotoSync = false
        }
    }

    private func openSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        UIApplication.shared.open(url)
    }
}

private struct LocationOnboardingPageView<AccentContent: View>: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let icon: String
    let eyebrow: LocalizedStringKey
    let title: LocalizedStringKey
    let description: LocalizedStringKey
    @ViewBuilder let accentContent: () -> AccentContent

    var body: some View {
        ScrollView(showsIndicators: false) {
            VStack(spacing: 24) {
                VStack(alignment: .leading, spacing: 14) {
                    Label { Text(eyebrow).textCase(.uppercase) } icon: {
                        Image(systemName: icon)
                    }
                    .font(.onboardingMicro)
                    .tracking(2)
                    .foregroundStyle(OnboardingPalette.accent)

                    Text(title)
                        .font(.onboardingDisplay)
                        .tracking(dynamicTypeSize.isAccessibilitySize ? -1 : 0)
                        .foregroundStyle(OnboardingPalette.textPrimary)
                        .multilineTextAlignment(.leading)
                        .fixedSize(horizontal: false, vertical: true)

                    Text(description)
                        .font(.onboardingBody)
                        .foregroundStyle(OnboardingPalette.textSecondary)
                        .multilineTextAlignment(.leading)
                        .lineSpacing(3)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                accentContent()
                    .frame(maxWidth: .infinity)
            }
            .padding(.horizontal, 24)
            .padding(.top, 40)
            .padding(.bottom, 32)
            .frame(maxWidth: 560).frame(maxWidth: .infinity)
        }
        .scrollBounceBehavior(.basedOnSize)
    }
}

/// An illustrated, fictional day, bundled for an immediate offline preview.
/// Coordinates refer to the atlas artwork, so every page retains the same geography.
private struct OnboardingMapPreview: View {
    enum Style { case places, timeline, route, photos }
    let style: Style

    private let ink = Color(red: 0.16, green: 0.29, blue: 0.56)
    private let paper = Color(red: 0.995, green: 0.986, blue: 0.964)
    private let stops: [CGPoint] = [
        CGPoint(x: 0.22, y: 0.78), CGPoint(x: 0.465, y: 0.53), CGPoint(x: 0.685, y: 0.285)
    ]

    var body: some View {
        VStack(spacing: 0) {
            atlas
                .frame(height: mapHeight)
                .clipped()
            if style == .timeline {
                timeline
            } else if style == .route {
                routeSummary
            }
        }
        .background(paper)
        .clipShape(RoundedRectangle(cornerRadius: 22, style: .continuous))
        .overlay {
            RoundedRectangle(cornerRadius: 22, style: .continuous)
                .strokeBorder(ink.opacity(0.12), lineWidth: 0.75)
        }
        .shadow(color: ink.opacity(0.09), radius: 16, x: 0, y: 9)
        // These are illustrations, not real user records or interactive map controls.
        .dynamicTypeSize(.large)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("An example day"))
        .accessibilityValue(Text(accessibilitySummary))
    }

    private var accessibilitySummary: LocalizedStringKey {
        switch style {
        case .places: "Auto-detect places you visit"
        case .timeline: "Arrivals, durations, and changes throughout your day."
        case .route: "Routes with start and end markers plus optional detail points."
        case .photos: "Match photos to your visits and routes."
        }
    }

    private var mapHeight: CGFloat {
        switch style {
        case .places: 310
        case .timeline: 190
        case .route: 270
        case .photos: 180
        }
    }

    private var atlas: some View {
        GeometryReader { geometry in
            ZStack {
                Image("OnboardingAtlas")
                    .resizable()
                    .scaledToFill()
                    .frame(width: geometry.size.width, height: geometry.size.width)
                    .offset(y: cropOffset(in: geometry.size))
                    .frame(width: geometry.size.width, height: geometry.size.height)
                    .clipped()
                    .saturation(0.72)
                    .overlay(paper.opacity(0.08))

                route(in: geometry.size)
                    .stroke(.white.opacity(0.95), style: StrokeStyle(lineWidth: 7, lineCap: .round, lineJoin: .round))
                route(in: geometry.size)
                    .stroke(ink.opacity(style == .places ? 0.65 : 1),
                            style: StrokeStyle(lineWidth: 2.8, lineCap: .round, lineJoin: .round,
                                               dash: style == .places ? [2, 6] : []))

                if style == .photos {
                    photoPin(asset: "OnboardingCafe", title: "Coffee", rotation: -7)
                        .position(x: geometry.size.width * 0.30, y: geometry.size.height * 0.61)
                    photoPin(asset: "OnboardingGarden", title: "Park", rotation: 6)
                        .position(x: geometry.size.width * 0.72, y: geometry.size.height * 0.36)
                } else {
                    ForEach(stops.indices, id: \.self) { index in
                        // The timeline's closer crop deliberately focuses on the last two visits.
                        if style != .timeline || index > 0 {
                            placePin(index: index)
                                .position(point(stops[index], in: geometry.size))
                        }
                    }
                }

                VStack {
                    HStack {
                        if style == .places {
                            Label("Today", systemImage: "sun.max")
                                .font(.system(size: 11, weight: .semibold))
                                .padding(.horizontal, 12)
                                .padding(.vertical, 8)
                                .background(paper.opacity(0.97), in: Capsule())
                                .overlay(Capsule().strokeBorder(ink.opacity(0.10), lineWidth: 0.5))
                        }
                        Spacer()
                        compass
                    }
                    Spacer()
                    if style == .places {
                        HStack(spacing: 6) {
                            Image(systemName: "mappin.and.ellipse")
                            Text(3, format: .number)
                            Text("Visits")
                            Spacer()
                            Image(systemName: "point.topleft.down.to.point.bottomright.curvepath")
                            Text(Measurement(value: 2.4, unit: UnitLength.kilometers),
                                 format: .measurement(width: .abbreviated, usage: .road))
                        }
                        .font(.system(size: 11, weight: .medium))
                        .padding(.horizontal, 13)
                        .padding(.vertical, 11)
                        .background(paper.opacity(0.97), in: Capsule())
                        .overlay(Capsule().strokeBorder(ink.opacity(0.10), lineWidth: 0.5))
                    }
                }
                .padding(16)
            }
            .foregroundStyle(ink)
        }
    }

    private var compass: some View {
        Image(systemName: "location.north.line.fill")
            .font(.system(size: 13, weight: .regular))
            .frame(width: 30, height: 30)
            .background(paper.opacity(0.95), in: Circle())
            .overlay(Circle().strokeBorder(ink.opacity(0.12), lineWidth: 0.5))
    }

    private func placePin(index: Int) -> some View {
        let symbols = ["house.fill", "cup.and.saucer.fill", "tree.fill"]
        return VStack(spacing: 0) {
            Image(systemName: symbols[index])
                .font(.system(size: 13, weight: .semibold))
                .foregroundStyle(index == 1 ? paper : ink)
                .frame(width: 34, height: 34)
                .background(index == 1 ? ink : paper, in: Circle())
                .overlay(Circle().strokeBorder(paper, lineWidth: 2.5))
                .shadow(color: ink.opacity(0.20), radius: 5, x: 0, y: 3)
            Rectangle().fill(ink.opacity(0.6)).frame(width: 1, height: 9)
            Circle().fill(ink).frame(width: 6, height: 6)
                .overlay(Circle().stroke(paper, lineWidth: 1.5))
        }
        .offset(y: -20)
    }

    private func cropOffset(in size: CGSize) -> CGFloat {
        style == .timeline ? size.width * 0.1 : 0
    }

    private func point(_ point: CGPoint, in size: CGSize) -> CGPoint {
        CGPoint(x: point.x * size.width,
                y: point.y * size.width - (size.width - size.height) / 2 + cropOffset(in: size))
    }

    private func route(in size: CGSize) -> Path {
        let points: [CGPoint] = [
            stops[0], CGPoint(x: 0.245, y: 0.73), CGPoint(x: 0.26, y: 0.67),
            CGPoint(x: 0.295, y: 0.585), CGPoint(x: 0.345, y: 0.57),
            CGPoint(x: 0.40, y: 0.555), stops[1], CGPoint(x: 0.49, y: 0.51),
            CGPoint(x: 0.495, y: 0.46), CGPoint(x: 0.492, y: 0.40),
            CGPoint(x: 0.48, y: 0.33), CGPoint(x: 0.478, y: 0.285),
            CGPoint(x: 0.49, y: 0.23), CGPoint(x: 0.513, y: 0.18),
            CGPoint(x: 0.53, y: 0.145), CGPoint(x: 0.565, y: 0.16),
            CGPoint(x: 0.60, y: 0.18), CGPoint(x: 0.65, y: 0.22),
            CGPoint(x: 0.68, y: 0.255), stops[2]
        ].map { point($0, in: size) }
        // Quadratic joins follow the boulevard without sharp schematic elbows.
        return Path { path in
            guard let first = points.first, let last = points.last else { return }
            path.move(to: first)
            for index in 1..<points.count - 1 {
                let midpoint = CGPoint(x: (points[index].x + points[index + 1].x) / 2,
                                       y: (points[index].y + points[index + 1].y) / 2)
                path.addQuadCurve(to: midpoint, control: points[index])
            }
            path.addLine(to: last)
        }
    }

    private var timeline: some View {
        VStack(spacing: 0) {
            visit(icon: "house.fill", title: "Home", time: "8:00", duration: "Morning", last: false)
            visit(icon: "cup.and.saucer.fill", title: "Coffee", time: "9:15", duration: "45 min", last: false)
            visit(icon: "tree.fill", title: "Park", time: "10:30", duration: "1 hr", last: true)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 12)
        .foregroundStyle(ink)
    }

    private func visit(icon: String, title: LocalizedStringKey, time: String,
                       duration: LocalizedStringKey, last: Bool) -> some View {
        HStack(spacing: 12) {
            Text(verbatim: time)
                .font(.system(size: 10, weight: .medium, design: .monospaced))
                .foregroundStyle(ink.opacity(0.55))
                .frame(width: 36, alignment: .leading)
            Image(systemName: icon)
                .font(.system(size: 11, weight: .medium))
                .frame(width: 26, height: 26)
                .background(ink.opacity(0.07), in: Circle())
                .overlay(alignment: .bottom) {
                    if !last {
                        Rectangle().fill(ink.opacity(0.18)).frame(width: 1, height: 12).offset(y: 12)
                    }
                }
            Text(title).font(.system(size: 12, weight: .medium))
            Spacer(minLength: 4)
            Text(duration).font(.system(size: 10)).foregroundStyle(ink.opacity(0.55))
        }
        .frame(height: 38)
    }

    private var routeSummary: some View {
        HStack(alignment: .firstTextBaseline, spacing: 24) {
            VStack(alignment: .leading, spacing: 4) {
                Text("Distance").font(.system(size: 9, weight: .medium)).foregroundStyle(ink.opacity(0.55))
                Text(Measurement(value: 2.4, unit: UnitLength.kilometers),
                     format: .measurement(width: .abbreviated, usage: .road))
                    .font(.system(size: 24, weight: .regular, design: .serif))
            }
            Rectangle().fill(ink.opacity(0.12)).frame(width: 1, height: 30)
            VStack(alignment: .leading, spacing: 4) {
                Text("Visits").font(.system(size: 9, weight: .medium)).foregroundStyle(ink.opacity(0.55))
                Text(3, format: .number).font(.system(size: 24, weight: .regular, design: .serif))
            }
            Spacer(minLength: 0)
            Image(systemName: "point.topleft.down.to.point.bottomright.curvepath")
                .font(.system(size: 26, weight: .ultraLight))
        }
        .foregroundStyle(ink)
        .padding(.horizontal, 20)
        .padding(.vertical, 16)
    }

    private func photoPin(asset: String, title: LocalizedStringKey, rotation: Double) -> some View {
        VStack(spacing: 6) {
            Image(asset)
                .resizable()
                .scaledToFill()
                .frame(width: 88, height: 82)
                .clipped()
            Text(title)
                .font(.system(size: 10, weight: .regular, design: .serif))
                .foregroundStyle(ink)
        }
        .padding(5)
        .padding(.bottom, 3)
        .background(paper)
        .shadow(color: ink.opacity(0.22), radius: 8, x: 0, y: 5)
        .rotationEffect(.degrees(rotation))
    }
}

/// The same fictional visits in each format, kept separate from the user's records.
private struct OnboardingExportPreview: View {
    private enum Format: String, CaseIterable {
        case json = "JSON", csv = "CSV", markdown = "Markdown"

        var filename: String {
            "visits." + (self == .markdown ? "md" : rawValue.lowercased())
        }

        // Short excerpts keep the structure readable on a phone.
        var excerpt: String {
            switch self {
            case .json:
                """
                [
                  {
                    "locationName": "Home",
                    "durationMinutes": 75
                  },
                  {
                    "locationName": "Coffee",
                    "durationMinutes": 45
                  },
                  {
                    "locationName": "Park",
                    "durationMinutes": 60
                  }
                ]
                """
            case .csv:
                """
                location_name,duration_minutes
                Home,75
                Coffee,45
                Park,60
                """
            case .markdown:
                """
                # Visits

                | Location | Duration |
                | --- | --- |
                | Home | 1h 15m |
                | Coffee | 45m |
                | Park | 1h |
                """
            }
        }
    }

    @State private var selectedFormat: Format = .json

    var body: some View {
        VStack(alignment: .leading, spacing: 16) {
            Picker("Export format", selection: $selectedFormat) {
                ForEach(Format.allCases, id: \.self) { format in
                    Text(verbatim: format.rawValue).tag(format)
                        .accessibilityIdentifier("onboarding-export-\(format.rawValue.lowercased())")
                }
            }
            .pickerStyle(.segmented)
            .accessibilityIdentifier("onboarding-export-format")

            VStack(alignment: .leading, spacing: 12) {
                Label {
                    Text(verbatim: selectedFormat.filename)
                } icon: {
                    Image(systemName: "doc.text")
                }
                .font(.onboardingCaption)
                .foregroundStyle(OnboardingPalette.accent)

                Divider()

                ScrollView(.horizontal) {
                    Text(verbatim: selectedFormat.excerpt)
                        .font(.system(.caption, design: .monospaced))
                        .foregroundStyle(OnboardingPalette.textPrimary)
                        .fixedSize(horizontal: true, vertical: true)
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .accessibilityIdentifier("onboarding-export-content")
                }
                .id(selectedFormat)
                .scrollBounceBehavior(.basedOnSize)
            }
            .onboardingCard(padding: 18)
        }
    }
}

private struct OnboardingStatusRow: View {
    let title: LocalizedStringKey
    let subtitle: String
    let icon: String
    let color: Color

    var body: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.callout.weight(.semibold))
                .foregroundStyle(color)
                .frame(width: 22)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.onboardingHeadline)
                    .foregroundStyle(OnboardingPalette.textPrimary)

                Text(subtitle)
                    .font(.onboardingCaption)
                    .foregroundStyle(OnboardingPalette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer(minLength: 0)
        }
        .onboardingCard(padding: 12, fill: OnboardingPalette.cardSoft)
    }
}

private struct OnboardingSummaryRow: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let icon: String
    let title: LocalizedStringKey
    let value: String

    var body: some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 6) {
                Label { Text(title) } icon: {
                    Image(systemName: icon).foregroundStyle(OnboardingPalette.accent)
                }
                .font(.onboardingCaption)
                .foregroundStyle(OnboardingPalette.textPrimary)
                Text(value)
                    .font(.onboardingMicro)
                    .foregroundStyle(OnboardingPalette.textSecondary)
            }
            .fixedSize(horizontal: false, vertical: true)
            .frame(maxWidth: .infinity, alignment: .leading)
        } else {
            summaryLine
        }
    }

    private var summaryLine: some View {
        HStack(spacing: 12) {
            Image(systemName: icon)
                .font(.caption.weight(.semibold))
                .foregroundStyle(OnboardingPalette.accent)
                .frame(width: 20)

            Text(title)
                .font(.onboardingCaption)
                .foregroundStyle(OnboardingPalette.textPrimary)

            Spacer()

            Text(value)
                .font(.onboardingMicro)
                .tracking(0.7)
                .foregroundStyle(OnboardingPalette.textSecondary)
        }
    }
}

private struct OnboardingChecklistRow: View {
    let icon: String
    let text: LocalizedStringKey

    var body: some View {
        HStack(spacing: 10) {
            Image(systemName: icon)
                .font(.caption.weight(.semibold))
                .foregroundStyle(OnboardingPalette.accent)
                .frame(width: 18)

            Text(text)
                .font(.onboardingCaption)
                .foregroundStyle(OnboardingPalette.textPrimary)
                .fixedSize(horizontal: false, vertical: true)

            Spacer(minLength: 0)
        }
    }
}

private struct OnboardingSignalColumn: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let delay: Double
    @State private var isAnimating = false

    var body: some View {
        VStack(spacing: 4) {
            ForEach(0..<5, id: \.self) { index in
                Capsule()
                    .fill(OnboardingPalette.accent.opacity(isAnimating ? (1.0 - Double(index) * 0.17) : 0.25))
                    .frame(width: 5, height: CGFloat(22 - index * 3))
            }
        }
        .onAppear {
            updateSignalAnimation(reduceMotion: reduceMotion)
        }
        .onChange(of: reduceMotion) { _, shouldReduceMotion in
            updateSignalAnimation(reduceMotion: shouldReduceMotion)
        }
    }

    private func updateSignalAnimation(reduceMotion: Bool) {
        guard !reduceMotion else {
            isAnimating = true
            return
        }

        isAnimating = false
        withAnimation(
            .easeInOut(duration: 1.0)
            .repeatForever(autoreverses: true)
            .delay(delay)
        ) {
            isAnimating = true
        }
    }
}

private struct OnboardingBackdrop: View {
    var body: some View {
        InkJournalBackground()
            .allowsHitTesting(false)
            .accessibilityHidden(true)
    }
}

private struct OnboardingCardModifier: ViewModifier {
    var padding: CGFloat = 16
    var fill: Color = OnboardingPalette.card

    func body(content: Content) -> some View {
        content
            .padding(padding)
            .background(fill, in: RoundedRectangle(cornerRadius: 12, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 12, style: .continuous)
                    .stroke(OnboardingPalette.border, lineWidth: 0.75)
            }
    }
}

private extension View {
    func onboardingCard(padding: CGFloat = 16, fill: Color = OnboardingPalette.card) -> some View {
        modifier(OnboardingCardModifier(padding: padding, fill: fill))
    }
}

private struct OnboardingPrimaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.onboardingButton)
            .foregroundStyle(InkPaperPalette.paper)
            .padding(.horizontal, 20)
            .padding(.vertical, 17)
            .frame(minHeight: 52)
            .background(OnboardingPalette.accent, in: RoundedRectangle(cornerRadius: 12))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

private struct OnboardingSecondaryButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.onboardingButton)
            .foregroundStyle(OnboardingPalette.accent)
            .padding(.horizontal, 18)
            .padding(.vertical, 17)
            .frame(minHeight: 52)
            .background(OnboardingPalette.card, in: RoundedRectangle(cornerRadius: 12))
            .overlay(RoundedRectangle(cornerRadius: 12).stroke(OnboardingPalette.border, lineWidth: 1))
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

private struct OnboardingTextButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.subheadline.weight(.medium))
            .foregroundStyle(OnboardingPalette.accent)
            .padding(.horizontal, 8)
            .frame(minWidth: 44, minHeight: 52)
            .contentShape(Rectangle())
            .opacity(configuration.isPressed ? 0.7 : 1)
    }
}

private enum OnboardingPalette {
    static let card = InkPaperPalette.paper.opacity(0.94)
    static let cardSoft = InkPaperPalette.paper.opacity(0.94)
    static let border = InkPaperPalette.border

    static let accent = InkPaperPalette.ink

    static let textPrimary = Color.primary
    static let textSecondary = InkPaperPalette.pencil
    static let textMuted = InkPaperPalette.pencil

    static let success = TE.success
    static let warning = TE.warning
    static let danger = TE.danger
}

private extension Font {
    static let onboardingDisplay = Font.system(.largeTitle, design: .serif, weight: .medium)
    static let onboardingHeadline = Font.system(.headline, weight: .medium)
    static let onboardingBody = Font.system(.subheadline)
    static let onboardingCaption = Font.system(.subheadline)
    static let onboardingMicro = Font.system(.caption, weight: .medium)
    static let onboardingButton = Font.system(.headline)
}

private extension CLAuthorizationStatus {
    var onboardingLabel: String {
        switch self {
        case .notDetermined:
            return String(localized: "Not requested")
        case .restricted:
            return String(localized: "Restricted")
        case .denied:
            return String(localized: "Denied")
        case .authorizedWhenInUse:
            return String(localized: "While using")
        case .authorizedAlways:
            return String(localized: "Always allowed")
        @unknown default:
            return String(localized: "Unknown")
        }
    }

    var onboardingIcon: String {
        switch self {
        case .authorizedAlways:
            return "checkmark.circle.fill"
        case .authorizedWhenInUse:
            return "clock.badge.checkmark.fill"
        case .denied, .restricted:
            return "xmark.octagon.fill"
        case .notDetermined:
            return "questionmark.circle.fill"
        @unknown default:
            return "questionmark.circle.fill"
        }
    }

    var onboardingColor: Color {
        switch self {
        case .authorizedAlways:
            return OnboardingPalette.success
        case .authorizedWhenInUse:
            return OnboardingPalette.warning
        case .denied, .restricted:
            return OnboardingPalette.danger
        case .notDetermined:
            return OnboardingPalette.textMuted
        @unknown default:
            return OnboardingPalette.textMuted
        }
    }
}

private extension PhotoLibraryAccessState {
    var onboardingLabel: String {
        switch self {
        case .notDetermined:
            return String(localized: "Not requested")
        case .authorized:
            return String(localized: "Allowed")
        case .limited:
            return String(localized: "Limited")
        case .denied:
            return String(localized: "Denied")
        case .restricted:
            return String(localized: "Restricted")
        case .unavailable:
            return String(localized: "Unavailable")
        }
    }

    var onboardingIcon: String {
        switch self {
        case .authorized, .limited:
            return "checkmark.circle.fill"
        case .denied, .restricted:
            return "xmark.octagon.fill"
        case .notDetermined:
            return "questionmark.circle.fill"
        case .unavailable:
            return "exclamationmark.triangle.fill"
        }
    }

    var onboardingColor: Color {
        switch self {
        case .authorized, .limited:
            return OnboardingPalette.success
        case .denied, .restricted:
            return OnboardingPalette.danger
        case .notDetermined, .unavailable:
            return OnboardingPalette.textMuted
        }
    }
}

#if DEBUG
@MainActor
private enum ScreenshotFeatureDemoSeeder {
    static var isEnabled: Bool {
        ProcessInfo.processInfo.arguments.contains("--seed-screenshot-data")
    }

    static func seed(in context: ModelContext) {
        let defaults = UserDefaults.standard
        defaults.set(true, forKey: "hasCompletedOnboarding")
        defaults.set(false, forKey: "isTrackingEnabled")
        defaults.set(true, forKey: "usesMetricDistanceUnits")
        defaults.set(false, forKey: "showOutliers")
        defaults.set(true, forKey: "snapTravelPathToRoads")
        defaults.set(true, forKey: "showStraightLinePathSegments")
        defaults.set(true, forKey: "discordPromoDismissed")
        defaults.set(true, forKey: RecordingSessionInferenceConfiguration.includesInferredSessionsKey)
        defaults.set(RecordingSessionGapPreset.thirtyMinutes.rawValue, forKey: RecordingSessionInferenceConfiguration.gapPresetKey)
        defaults.set(RecordingSessionMinimumDurationPreset.fiveMinutes.rawValue, forKey: RecordingSessionInferenceConfiguration.minimumDurationPresetKey)
        defaults.set(RecordingSessionMinimumPointCountPreset.two.rawValue, forKey: RecordingSessionInferenceConfiguration.minimumPointCountKey)
        defaults.set(true, forKey: "webhook.enabled")
        defaults.set("https://example.com/api/location", forKey: "webhook.url")
        defaults.set("json", forKey: "webhook.format")
        defaults.set("none", forKey: "webhook.authType")
        defaults.set("realtime", forKey: "webhook.sendMode")
        defaults.set(true, forKey: "webhook.privacyWarningDismissed")

        let webhook = WebhookManager.shared
        webhook.isEnabled = true
        webhook.urlString = "https://example.com/api/location"
        webhook.format = .json
        webhook.authType = .none
        webhook.sendMode = .realtime

        do {
            try clearExistingData(in: context)

            let today = Calendar.current.startOfDay(for: Date())
            let yesterday = Calendar.current.date(byAdding: .day, value: -1, to: today) ?? today.addingTimeInterval(-86_400)
            let twoDaysAgo = Calendar.current.date(byAdding: .day, value: -2, to: today) ?? today.addingTimeInterval(-172_800)

            let morningStart = date(on: today, hour: 8, minute: 5)
            let morningEnd = date(on: today, hour: 8, minute: 56)
            let lunchStart = date(on: today, hour: 12, minute: 10)
            let lunchEnd = date(on: today, hour: 12, minute: 46)
            let eveningStart = date(on: today, hour: 17, minute: 20)
            let eveningEnd = Date()

            let morningSession = RecordingSession(
                startedAt: morningStart,
                endedAt: morningEnd,
                customName: "Morning Commute",
                notes: "Road-matched demo route from the Mission to the Ferry Building."
            )
            let lunchSession = RecordingSession(
                startedAt: lunchStart,
                endedAt: lunchEnd,
                customName: "Client Lunch Walk",
                notes: "Use this outing to show replay, visit names, and nearby business suggestions."
            )
            let liveSession = RecordingSession(
                startedAt: eveningStart,
                endedAt: nil,
                customName: "Evening Errands",
                notes: "Open session used to show the LIVE outing state."
            )

            [morningSession, lunchSession, liveSession].forEach { context.insert($0) }

            let pointSets = [
                routePoints(
                    coordinates: [
                        (37.7599, -122.4148),
                        (37.7653, -122.4194),
                        (37.7722, -122.4214),
                        (37.7793, -122.4182),
                        (37.7892, -122.4010),
                        (37.7955, -122.3937)
                    ],
                    start: morningStart,
                    end: morningEnd,
                    speed: 8.4
                ),
                routePoints(
                    coordinates: [
                        (37.7764, -122.4262),
                        (37.7788, -122.4240),
                        (37.7819, -122.4206),
                        (37.7851, -122.4176),
                        (37.7877, -122.4077)
                    ],
                    start: lunchStart,
                    end: lunchEnd,
                    speed: 1.6
                ),
                routePoints(
                    coordinates: [
                        (37.7858, -122.4064),
                        (37.7891, -122.4017),
                        (37.7920, -122.3974),
                        (37.7951, -122.3938)
                    ],
                    start: eveningStart,
                    end: eveningEnd,
                    speed: 2.2
                ),
                routePoints(
                    coordinates: [
                        (37.8024, -122.4058),
                        (37.8002, -122.4101),
                        (37.7980, -122.4144),
                        (37.7950, -122.4185)
                    ],
                    start: date(on: yesterday, hour: 9, minute: 20),
                    end: date(on: yesterday, hour: 10, minute: 5),
                    speed: 1.9
                ),
                routePoints(
                    coordinates: [
                        (37.7716, -122.4238),
                        (37.7685, -122.4291),
                        (37.7656, -122.4343),
                        (37.7617, -122.4350)
                    ],
                    start: date(on: yesterday, hour: 14, minute: 35),
                    end: date(on: yesterday, hour: 15, minute: 18),
                    speed: 2.1
                ),
                routePoints(
                    coordinates: [
                        (37.7693, -122.4862),
                        (37.7718, -122.4824),
                        (37.7749, -122.4780),
                        (37.7782, -122.4715)
                    ],
                    start: date(on: twoDaysAgo, hour: 16, minute: 15),
                    end: date(on: twoDaysAgo, hour: 17, minute: 0),
                    speed: 2.4
                )
            ]

            pointSets.flatMap { $0 }.forEach { context.insert($0) }

            let visits = [
                Visit(
                    latitude: 37.7765,
                    longitude: -122.4258,
                    arrivedAt: date(on: today, hour: 11, minute: 54),
                    departedAt: date(on: today, hour: 12, minute: 18),
                    customName: nil,
                    locationName: "Valencia Street Stop",
                    address: "375 Valencia St, San Francisco, CA",
                    notes: "Tap a nearby business suggestion to rename this visit.",
                    geocodingCompleted: true
                ),
                Visit(
                    latitude: 37.7877,
                    longitude: -122.4077,
                    arrivedAt: date(on: today, hour: 12, minute: 28),
                    departedAt: date(on: today, hour: 13, minute: 12),
                    customName: "Client Lunch — The Grove",
                    locationName: "Market Street Stop",
                    address: "690 Mission St, San Francisco, CA",
                    notes: "Custom visit names are preserved separately from the detected place.",
                    geocodingCompleted: true
                ),
                Visit(
                    latitude: 37.7951,
                    longitude: -122.3938,
                    arrivedAt: date(on: today, hour: 17, minute: 42),
                    departedAt: nil,
                    customName: "Ferry Building Errand",
                    locationName: "Ferry Building",
                    address: "1 Ferry Building, San Francisco, CA",
                    notes: "Only one current visit marker should be visible on the map.",
                    geocodingCompleted: true
                ),
                Visit(
                    latitude: 37.8024,
                    longitude: -122.4058,
                    arrivedAt: date(on: yesterday, hour: 9, minute: 35),
                    departedAt: date(on: yesterday, hour: 10, minute: 20),
                    locationName: "North Beach",
                    address: "Columbus Ave, San Francisco, CA",
                    geocodingCompleted: true
                ),
                Visit(
                    latitude: 37.7617,
                    longitude: -122.4350,
                    arrivedAt: date(on: yesterday, hour: 15, minute: 5),
                    departedAt: date(on: yesterday, hour: 15, minute: 40),
                    locationName: "Dolores Park",
                    address: "Dolores St, San Francisco, CA",
                    geocodingCompleted: true
                )
            ]

            visits.forEach { context.insert($0) }
            try context.save()
        } catch {
            assertionFailure("Failed to seed screenshot demo data: \(error)")
        }
    }

    private static func clearExistingData(in context: ModelContext) throws {
        for visit in try context.fetch(FetchDescriptor<Visit>()) {
            context.delete(visit)
        }
        for point in try context.fetch(FetchDescriptor<LocationPoint>()) {
            context.delete(point)
        }
        for session in try context.fetch(FetchDescriptor<RecordingSession>()) {
            context.delete(session)
        }
        try context.save()
    }

    private static func date(on day: Date, hour: Int, minute: Int) -> Date {
        Calendar.current.date(bySettingHour: hour, minute: minute, second: 0, of: day) ?? day
    }

    private static func routePoints(
        coordinates: [(Double, Double)],
        start: Date,
        end: Date,
        speed: Double
    ) -> [LocationPoint] {
        guard coordinates.count >= 2 else { return [] }
        let segmentCount = coordinates.count - 1
        let totalSteps = max(segmentCount * 5, coordinates.count)
        let duration = max(end.timeIntervalSince(start), Double(totalSteps))

        return (0...totalSteps).map { step in
            let progress = Double(step) / Double(totalSteps)
            let scaled = progress * Double(segmentCount)
            let index = min(segmentCount - 1, Int(scaled.rounded(.down)))
            let localProgress = scaled - Double(index)
            let from = coordinates[index]
            let to = coordinates[index + 1]
            let latitude = from.0 + ((to.0 - from.0) * localProgress)
            let longitude = from.1 + ((to.1 - from.1) * localProgress)
            let timestamp = start.addingTimeInterval(duration * progress)

            return LocationPoint(
                latitude: latitude,
                longitude: longitude,
                timestamp: timestamp,
                altitude: 12,
                speed: speed,
                horizontalAccuracy: step % 7 == 0 ? 12 : 6,
                isOutlier: false
            )
        }
    }
}
#endif

#Preview {
    ContentView()
        .modelContainer(for: [Visit.self, LocationPoint.self, RecordingSession.self, PhotoMoment.self, SavedPlace.self, CountryStay.self], inMemory: true)
    .environment(ManualVisitRouter())
}

/// Purchase verification runs in the background so a delayed StoreKit response
/// cannot lock users out of their journal. Tracking still checks `canTrack`.
struct PurchaseLaunchGate<Paywall: View, Content: View>: View {
    let hasResolvedEntitlements: Bool
    let requiresInitialPaywall: Bool
    @ViewBuilder var paywall: () -> Paywall
    @ViewBuilder var content: () -> Content

    var body: some View {
        if hasResolvedEntitlements && requiresInitialPaywall {
            paywall()
        } else {
            content()
        }
    }
}
