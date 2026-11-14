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
                if !storeManager.hasResolvedEntitlements {
                    ProgressView("Checking purchases…")
                } else if showsInitialPaywall {
                    PaywallView(storeManager: storeManager, context: .onboarding, isRequired: true)
                } else if hasCompletedOnboarding || bypassOnboardingForWatchReplay {
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
            OnboardingAnalyticsClient.shared.flush()
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
            OnboardingAnalyticsClient.shared.flush()

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
        if #available(iOS 27.1, *) {
            DuoJournalWorkspace(viewModel: viewModel, mapIsPrimary: mapIsPrimary) {
                selectedTab = 0
            }
        } else if mapIsPrimary {
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

private struct OnboardingView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let viewModel: LocationViewModel
    let onComplete: (Bool) -> Void
    private let analytics = OnboardingAnalyticsClient.shared

    @ObservedObject private var locationManager: LocationManager

    @State private var selectedPage: Int
    @State private var startTrackingWhenDone = false
    @State private var isConfiguringAutomaticPhotoSync = false
    @State private var didTrackOnboardingStarted = false
    @State private var trackedStepViews: Set<OnboardingAnalyticsStep> = []
    @AppStorage(LocationViewModel.automaticPhotoSyncEnabledKey) private var automaticPhotoSyncEnabled = false

    private let pageCount = 5

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
           (0..<5).contains(page) {
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

                    featuresPage
                        .tag(1)

                    permissionsPage
                        .tag(2)

                    photosPage
                        .tag(3)

                    finishPage
                        .tag(4)
                }
                .tabViewStyle(.page(indexDisplayMode: .never))
                .animation(reduceMotion ? nil : .spring(response: 0.42, dampingFraction: 0.84), value: selectedPage)

                VStack(spacing: 16) {
                    pageIndicators
                    controls
                }
                .padding(.horizontal, 24)
                .padding(.top, 16)
                .padding(.bottom, 16)
                .background {
                    LinearGradient(colors: [InkPaperPalette.paper.opacity(0), InkPaperPalette.paper, InkPaperPalette.paper], startPoint: .top, endPoint: .bottom)
                        .ignoresSafeArea(edges: .bottom)
                }
            }
        }
        .modifier(JournalFlowPresentation())
        .tint(OnboardingPalette.accent)
        .onAppear {
            trackOnboardingStartedIfNeeded()
            trackOnboardingStepViewed(for: selectedPage)
        }
        .onChange(of: selectedPage) { _, newPage in
            trackOnboardingStepViewed(for: newPage)
        }
        .onChange(of: startTrackingWhenDone) { _, _ in
            analytics.trackTrackingIntentChanged(
                intent: trackingAnalyticsIntent,
                authorizationStatus: currentAuthorizationAnalyticsStatus
            )
        }
        .onChange(of: locationManager.authorizationStatus) { _, newStatus in
            if selectedPage == 2 {
                analytics.trackLocationAuthorizationCompleted(status: analyticsStatus(for: newStatus))
            }

            guard selectedPage == 2 else { return }
            if newStatus == .authorizedAlways {
                withAnimation(reduceMotion ? nil : .spring(response: 0.4, dampingFraction: 0.85)) {
                    selectedPage = 3
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
            VStack(spacing: 10) {
                OnboardingChecklistRow(icon: "mappin.and.ellipse", text: "Auto-detect places you visit")
                OnboardingChecklistRow(icon: "point.topleft.down.to.point.bottomright.curvepath", text: "Capture detailed routes when needed")
                OnboardingChecklistRow(icon: "lock.shield.fill", text: "Keep all location data on-device")
            }
        }
    }

    private var featuresPage: some View {
        LocationOnboardingPageView(
            icon: "square.grid.2x2.fill",
            eyebrow: "Core Features",
            title: "The shape\nof your day.",
            description: "Glanceable, battery-aware, and deeply contextual when you need it."
        ) {
            VStack(spacing: 12) {
                OnboardingFeatureCard(
                    icon: "house.and.flag.fill",
                    title: "Visit timeline",
                    description: "Arrivals, durations, and changes throughout your day."
                )

                OnboardingFeatureCard(
                    icon: "map.fill",
                    title: "Path visualization",
                    description: "Routes with start and end markers plus optional detail points."
                )

                OnboardingFeatureCard(
                    icon: "square.and.arrow.up",
                    title: "Flexible exports",
                    description: "JSON, CSV, or Markdown — whenever you need it."
                )
            }
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

                    Text("Background visit detection lets Whereday keep recording when the app is closed.")
                        .font(.onboardingBody)
                        .foregroundStyle(OnboardingPalette.textSecondary)
                        .multilineTextAlignment(.leading)
                        .lineSpacing(3)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.top, 64)
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
                    analytics.trackLocationAuthorizationRequested(
                        requestKind: .whenInUse,
                        status: currentAuthorizationAnalyticsStatus
                    )
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
                    analytics.trackLocationAuthorizationRequested(
                        requestKind: .always,
                        status: currentAuthorizationAnalyticsStatus
                    )
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
                    analytics.trackLocationAuthorizationRequested(
                        requestKind: .settings,
                        status: currentAuthorizationAnalyticsStatus
                    )
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
            eyebrow: "Optional",
            title: "Your places.\nYour photographs.",
            description: "Let Whereday automatically show iPhone photos on your map and outings. Photos stay in your Photos library."
        ) {
            VStack(spacing: 12) {
                OnboardingStatusRow(
                    title: "Photos access",
                    subtitle: viewModel.photoLibraryAccessState.onboardingLabel,
                    icon: viewModel.photoLibraryAccessState.onboardingIcon,
                    color: viewModel.photoLibraryAccessState.onboardingColor
                )

                VStack(alignment: .leading, spacing: 12) {
                    Toggle(isOn: Binding(
                        get: { automaticPhotoSyncEnabled },
                        set: { handleAutomaticPhotoSyncToggle($0) }
                    )) {
                        VStack(alignment: .leading, spacing: 4) {
                            Text("Auto-sync photo moments")
                                .font(.onboardingBody)
                                .fontWeight(.medium)
                                .foregroundStyle(OnboardingPalette.textPrimary)

                            Text("Uses photo GPS when available, otherwise matches timestamps to nearby route points or visits.")
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
                        Text("Enabled. Photo cards will appear automatically when Whereday can place them on your map.")
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
                        Text("This is optional — skip it if you only want location visits and routes.")
                            .font(.onboardingCaption)
                            .foregroundStyle(OnboardingPalette.textSecondary)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                }
                .onboardingCard(padding: 14, fill: OnboardingPalette.cardSoft)
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
            HStack(spacing: 8) {
                ForEach(0..<pageCount, id: \.self) { index in
                    Circle()
                        .fill(index <= selectedPage ? OnboardingPalette.accent : .clear)
                        .frame(width: 7, height: 7)
                        .overlay(Circle().stroke(OnboardingPalette.accent.opacity(0.5), lineWidth: 1))
                }
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text("Step \(selectedPage + 1) of \(pageCount)"))
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
        selectedPage == 2 && locationManager.authorizationStatus == .notDetermined
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
        analytics.trackOnboardingCompleted(
            authorizationStatus: currentAuthorizationAnalyticsStatus,
            trackingIntent: trackingAnalyticsIntent
        )
        onComplete(shouldStartTracking)
    }

    private func trackOnboardingStartedIfNeeded() {
        guard !didTrackOnboardingStarted else { return }
        didTrackOnboardingStarted = true
        analytics.trackOnboardingStarted(
            step: .welcome,
            authorizationStatus: currentAuthorizationAnalyticsStatus
        )
    }

    private func trackOnboardingStepViewed(for pageIndex: Int) {
        guard let step = onboardingStep(for: pageIndex),
              !trackedStepViews.contains(step) else { return }

        trackedStepViews.insert(step)
        analytics.trackOnboardingStepViewed(
            step,
            authorizationStatus: currentAuthorizationAnalyticsStatus,
            trackingIntent: step == .ready ? trackingAnalyticsIntent : nil
        )
    }

    private func onboardingStep(for pageIndex: Int) -> OnboardingAnalyticsStep? {
        switch pageIndex {
        case 0:
            return .welcome
        case 1:
            return .features
        case 2:
            return .permissions
        case 3:
            return .photos
        case 4:
            return .ready
        default:
            return nil
        }
    }

    private var currentAuthorizationAnalyticsStatus: OnboardingAnalyticsAuthorizationStatus {
        analyticsStatus(for: locationManager.authorizationStatus)
    }

    private var trackingAnalyticsIntent: OnboardingAnalyticsTrackingIntent {
        guard locationManager.hasLocationPermission else { return .unavailable }
        return startTrackingWhenDone ? .startImmediately : .later
    }

    private func analyticsStatus(for status: CLAuthorizationStatus) -> OnboardingAnalyticsAuthorizationStatus {
        switch status {
        case .notDetermined:
            return .notDetermined
        case .authorizedWhenInUse:
            return .whenInUse
        case .authorizedAlways:
            return .always
        case .denied:
            return .denied
        case .restricted:
            return .restricted
        @unknown default:
            return .unknown
        }
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
            .padding(.top, 64)
            .padding(.bottom, 32)
            .frame(maxWidth: 560).frame(maxWidth: .infinity)
        }
        .scrollBounceBehavior(.basedOnSize)
    }
}

private struct OnboardingFeatureCard: View {
    let icon: String
    let title: LocalizedStringKey
    let description: LocalizedStringKey

    var body: some View {
        HStack(alignment: .top, spacing: 12) {
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 6) {
                    Image(systemName: icon)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(OnboardingPalette.accent)

                    Text(title)
                        .font(.onboardingHeadline)
                        .foregroundStyle(OnboardingPalette.textPrimary)
                }

                Text(description)
                    .font(.onboardingCaption)
                    .foregroundStyle(OnboardingPalette.textSecondary)
                    .fixedSize(horizontal: false, vertical: true)
            }

            Spacer(minLength: 0)
        }
        .onboardingCard(padding: 14, fill: OnboardingPalette.card)
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
