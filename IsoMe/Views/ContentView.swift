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
    @State private var startsOnArchive = false
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
                            MainTabView(viewModel: viewModel, startsOnArchive: startsOnArchive)
                        }
                        #else
                        MainTabView(viewModel: viewModel, startsOnArchive: startsOnArchive)
                        #endif
                    }
                        .onAppear {
                            startPendingTrackingIfAllowed(viewModel: viewModel)
                        }
                } else {
                    OnboardingView(viewModel: viewModel) { shouldStartTracking, opensImport in
                        pendingTrackingStart = shouldStartTracking
                        startsOnArchive = opensImport
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
    var startsOnArchive = false
    // Map (0) and Timeline (3) open over Today; Settings (2) lives behind Archive.
    @State private var selectedTab: Int = MainTabView.tabTag(forLaunchTab: MainTabView.initialTabFromLaunchArguments())
    @State private var showsTrace = MainTabView.initialTabFromLaunchArguments() == 0
    @State private var showsTimeline = MainTabView.initialTabFromLaunchArguments() == 3
    @ObservedObject private var exportToastCenter = ExportToastCenter.shared

    var body: some View {
        TabView(selection: $selectedTab) {
            todayWorkspace
            .tabItem {
                Label("Today", systemImage: "clock")
            }
            .tag(4)

            AtlasView(viewModel: viewModel)
                .tabItem {
                    Label("Atlas", systemImage: "square.grid.2x2")
                }
                .tag(5)

            NavigationStack {
                SavedLocationsView(viewModel: viewModel)
            }
            .tabItem {
                Label("Places", systemImage: "mappin.and.ellipse")
            }
            .tag(6)

            ExportView(viewModel: viewModel)
                .modifier(JournalFlowPresentation())
                .tabItem {
                    Label("Archive", systemImage: "archivebox")
                }
                .tag(1)
        }
        .tint(TE.inkRust)
        .onAppear {
            if startsOnArchive { selectedTab = 1 }
        }
        .fullScreenCover(isPresented: $showsTrace) {
            journalWorkspace(mapIsPrimary: true)
                .overlay(alignment: .topLeading) {
                    Button {
                        showsTrace = false
                    } label: {
                        Label("Today", systemImage: "chevron.left")
                            .font(TE.mono(.caption, weight: .semibold))
                            .padding(.horizontal, 14)
                            .frame(minHeight: 44)
                            .background(Capsule().fill(TE.card))
                            .overlay(Capsule().strokeBorder(TE.border, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .foregroundStyle(TE.textPrimary)
                    .padding(.leading, 16)
                    .padding(.top, 4)
                }
        }
        .sheet(isPresented: $showsTimeline) {
            journalWorkspace(mapIsPrimary: false)
                .presentationDragIndicator(.visible)
        }
        .onChange(of: manualVisitRouter.request?.id, initial: true) { _, id in
            if id != nil {
                showsTimeline = false
                showsTrace = true
            }
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
    private var todayWorkspace: some View {
        #if compiler(>=6.4)
        if #available(iOS 27.1, *) {
            DuoTodayWorkspace(
                viewModel: viewModel,
                onReplay: { showsTrace = true },
                onPickDay: { showsTimeline = true },
                onOpenPlaces: { selectedTab = 6 },
                onOpenArchive: { selectedTab = 1 }
            )
        } else {
            singlePaneToday
        }
        #else
        singlePaneToday
        #endif
    }

    private var singlePaneToday: some View {
        TodayView(
            viewModel: viewModel,
            onReplay: { showsTrace = true },
            onPickDay: { showsTimeline = true },
            onOpenPlaces: { selectedTab = 6 },
            onOpenArchive: { selectedTab = 1 }
        )
    }

    @ViewBuilder
    private func journalWorkspace(mapIsPrimary: Bool) -> some View {
        // The Duo SDK ships with Swift 6.4; older SDKs cannot resolve ArrangementView.
        #if compiler(>=6.4)
        if #available(iOS 27.1, *) {
            DuoJournalWorkspace(viewModel: viewModel, mapIsPrimary: mapIsPrimary) {
                showsTimeline = false
                showsTrace = true
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
            OutingsView(viewModel: viewModel) {
                showsTimeline = false
                showsTrace = true
            }
        }
    }

    private static func tabTag(forLaunchTab tab: Int) -> Int {
        switch tab {
        case 0, 3: 4
        case 2: 1
        default: tab
        }
    }

    private static func initialTabFromLaunchArguments() -> Int {
        #if DEBUG
        let prefix = "--default-tab="
        if let arg = ProcessInfo.processInfo.arguments.first(where: { $0.hasPrefix(prefix) }),
           let index = Int(arg.dropFirst(prefix.count)),
           (0...6).contains(index) {
            return index
        }
        #endif
        return 4
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
                LocationMapView(viewModel: viewModel, focusedVisit: focusedVisit, presentation: .companion)
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

/// Open, Today sits beside the map; a tapped stay is focused there with a small inspector.
/// Closed, the cover display keeps Today alone and hints that opening it shows the map.
@available(iOS 27.1, *)
private struct DuoTodayWorkspace: View {
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let viewModel: LocationViewModel
    let onReplay: () -> Void
    let onPickDay: () -> Void
    let onOpenPlaces: () -> Void
    let onOpenArchive: () -> Void
    @State private var focusedVisit: Visit?
    @State private var hasUsedCompanion = false

    private var hasCompanion: Bool { horizontalSizeClass == .regular }

    var body: some View {
        ArrangementView {
            TodayView(
                viewModel: viewModel,
                onReplay: onReplay,
                onPickDay: onPickDay,
                onOpenPlaces: onOpenPlaces,
                onOpenArchive: onOpenArchive,
                onSelectVisit: hasCompanion ? { focusedVisit = $0 } : nil,
                showsCoverHint: !hasCompanion && hasUsedCompanion
            )
        } secondary: {
            LocationMapView(viewModel: viewModel, focusedVisit: focusedVisit, presentation: .companion)
                .overlay(alignment: .leading) { Rectangle().fill(TE.border).frame(width: 1).allowsHitTesting(false) }
        }
        .arrangementViewStyle(.split.axes(hasCompanion ? Axis.Set.horizontal.union(.vertical) : Axis.Set()))
        .onChange(of: hasCompanion, initial: true) { _, expanded in
            if expanded { hasUsedCompanion = true }
        }
    }
}

#endif

private struct OnboardingView: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let viewModel: LocationViewModel
    let onComplete: (_ startTracking: Bool, _ opensImport: Bool) -> Void

    @ObservedObject private var locationManager: LocationManager

    @State private var selectedPage: Int
    @State private var recordingDetail: RecordingDetail = .routes
    @State private var isConfiguringAutomaticPhotoSync = false
    @AppStorage(LocationViewModel.automaticPhotoSyncEnabledKey) private var automaticPhotoSyncEnabled = false

    private static let pageCount = 3
    private var pageCount: Int { Self.pageCount }
    private let permissionsPageIndex = 2

    init(viewModel: LocationViewModel, onComplete: @escaping (_ startTracking: Bool, _ opensImport: Bool) -> Void) {
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
        VStack(alignment: .leading, spacing: 0) {
            HStack {
                Text("Whereday")
                    .font(TE.editorial(.title3).italic())
                Spacer()
                stepIndicators
            }
            .padding(.top, 12)

            ScrollView {
                Group {
                    switch selectedPage {
                    case 0: welcomePage
                    case 1: privacyPage
                    default: rememberPage
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(.vertical, 24)
            }
            .scrollBounceBehavior(.basedOnSize)
            .defaultScrollAnchor(.center)
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: selectedPage)

            controls
        }
        .padding(.horizontal, 24)
        .padding(.bottom, 16)
        .frame(maxWidth: 560)
        .frame(maxWidth: .infinity)
        .foregroundStyle(TE.textPrimary)
        .background(TE.surface.ignoresSafeArea())
        .modifier(JournalFlowPresentation())
        .tint(TE.accent)
        .onChange(of: locationManager.authorizationStatus) { _, newStatus in
            if selectedPage == permissionsPageIndex, newStatus == .authorizedAlways {
                completeOnboarding()
            }
        }
    }

    // MARK: Pages

    private var welcomePage: some View {
        VStack(alignment: .leading, spacing: 28) {
            OnboardingDial()
                .frame(width: 260, height: 260)
                .frame(maxWidth: .infinity)
            VStack(alignment: .leading, spacing: 10) {
                Text("Every day\nis a page.")
                    .font(.system(size: 40, design: .serif))
                    .accessibilityAddTraits(.isHeader)
                Text("Whereday quietly writes down where you were, so you can look back at any day, place or trip.")
                    .foregroundStyle(TE.textMuted)
            }
        }
    }

    private var privacyPage: some View {
        VStack(alignment: .leading, spacing: 26) {
            Text("It stays\non this phone.")
                .font(.system(size: 40, design: .serif))
                .accessibilityAddTraits(.isHeader)
            VStack(spacing: 0) {
                Divider().overlay(TE.border)
                privacyRow("01", "No account. Nothing to sign up for.")
                privacyRow("02", "No server keeps a copy of where you've been.")
                privacyRow("03", "Export it anytime, in open formats. The code is open too.")
            }
        }
    }

    private func privacyRow(_ number: String, _ text: LocalizedStringKey) -> some View {
        VStack(spacing: 0) {
            HStack(alignment: .firstTextBaseline, spacing: 0) {
                Text(number)
                    .font(TE.mono(.caption))
                    .foregroundStyle(TE.inkRust)
                    .frame(width: 34, alignment: .leading)
                    .accessibilityHidden(true)
                Text(text)
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .padding(.vertical, 16)
            Divider().overlay(TE.border)
        }
    }

    private var rememberPage: some View {
        VStack(alignment: .leading, spacing: 20) {
            Text("How much\nshould it remember?")
                .font(.system(size: 40, design: .serif))
                .accessibilityAddTraits(.isHeader)
            VStack(spacing: 8) {
                ForEach(RecordingDetail.allCases) { detail in
                    Button {
                        recordingDetail = detail
                    } label: {
                        choiceCard(isSelected: recordingDetail == detail) {
                            Text(detail.title).font(.body.weight(.semibold))
                            Text(detail.summary).font(.footnote).foregroundStyle(TE.textMuted)
                        }
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(recordingDetail == detail ? .isSelected : [])
                }
                Toggle(isOn: Binding(
                    get: { automaticPhotoSyncEnabled },
                    set: { handleAutomaticPhotoSyncToggle($0) }
                )) {
                    VStack(alignment: .leading, spacing: 3) {
                        Text("Match photos to places").font(.body.weight(.semibold))
                        Text("Shows your iPhone photos on the days and places they were taken. Photos stay in your library.")
                            .font(.footnote)
                            .foregroundStyle(TE.textMuted)
                    }
                }
                .toggleStyle(TEToggleStyle())
                .disabled(isConfiguringAutomaticPhotoSync)
                .padding(.horizontal, 16)
                .padding(.vertical, 14)
                .background(RoundedRectangle(cornerRadius: 16).fill(TE.card))
                .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(TE.border, lineWidth: 1))
            }
            Text(permissionNote)
                .font(TE.mono(.caption2))
                .foregroundStyle(TE.textMuted)
        }
    }

    private func choiceCard<Content: View>(isSelected: Bool, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 3, content: content)
            .multilineTextAlignment(.leading)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(RoundedRectangle(cornerRadius: 16).fill(TE.card))
            .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(isSelected ? TE.textPrimary : TE.border, lineWidth: isSelected ? 2 : 1))
            .contentShape(RoundedRectangle(cornerRadius: 16))
    }

    private var permissionNote: LocalizedStringKey {
        switch locationManager.authorizationStatus {
        case .authorizedWhenInUse: "Allow Always so Whereday records when it's closed. You can change this anytime."
        case .denied, .restricted: "Location access is off. Turn it on in iPhone Settings → Whereday → Location."
        default: "You can change this anytime."
        }
    }

    // MARK: Controls

    private var stepIndicators: some View {
        HStack(spacing: 6) {
            ForEach(0..<pageCount, id: \.self) { index in
                Capsule()
                    .fill(index == selectedPage ? TE.placeInks[1] : TE.border)
                    .frame(width: index == selectedPage ? 20 : 6, height: 6)
                    .accessibilityIdentifier("onboarding-step-\(index)")
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Step \(selectedPage + 1) of \(pageCount)"))
    }

    private var controls: some View {
        VStack(spacing: 8) {
            Button(action: handlePrimaryAction) {
                Text(primaryButtonTitle)
            }
            .buttonStyle(TEActionButtonStyle(emphasis: .primary))
            .accessibilityIdentifier("onboarding-next")

            Button(action: handleSecondaryAction) {
                Text(secondaryButtonTitle)
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(TE.textMuted)
                    .frame(maxWidth: .infinity, minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityIdentifier(selectedPage == 0 ? "onboarding-import" : "onboarding-back")
        }
    }

    private var primaryButtonTitle: LocalizedStringKey {
        guard selectedPage == permissionsPageIndex else { return "Continue" }
        switch locationManager.authorizationStatus {
        case .notDetermined: return "Allow location"
        case .authorizedWhenInUse: return "Allow Always"
        case .denied, .restricted: return "Open iPhone Settings"
        default: return "Start recording"
        }
    }

    private var secondaryButtonTitle: LocalizedStringKey {
        switch selectedPage {
        case 0: "I already have data — import"
        case permissionsPageIndex where locationManager.authorizationStatus != .notDetermined: "Finish without Always"
        default: "Back"
        }
    }

    private func handlePrimaryAction() {
        guard selectedPage == permissionsPageIndex else {
            withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.25)) { selectedPage += 1 }
            return
        }
        recordingDetail.apply(to: locationManager)
        switch locationManager.authorizationStatus {
        case .notDetermined: locationManager.requestWhenInUseAuthorization()
        case .authorizedWhenInUse: locationManager.requestAlwaysAuthorization()
        case .denied, .restricted: openSettings()
        default: completeOnboarding()
        }
    }

    private func handleSecondaryAction() {
        switch selectedPage {
        case 0:
            onComplete(false, true)
        case permissionsPageIndex where locationManager.authorizationStatus != .notDetermined:
            recordingDetail.apply(to: locationManager)
            completeOnboarding()
        default:
            withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.25)) { selectedPage -= 1 }
        }
    }

    private func completeOnboarding() {
        onComplete(locationManager.hasLocationPermission, false)
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

/// The first-run illustration: a day dial with its first places filling in.
private struct OnboardingDial: View {
    var body: some View {
        ZStack {
            ring(from: 0, to: 1, ink: TE.dialTrack)
            ring(from: 0, to: 0.342, ink: TE.placeInks[0])
            ring(from: 0.361, to: 0.392, ink: TE.placeInks[1])
            ring(from: 0.41, to: 0.49, ink: TE.placeInks[2].opacity(0.5))
            VStack(spacing: 2) {
                Text(Date.now.formatted(.dateTime.weekday(.abbreviated)))
                    .font(.system(size: 39, design: .serif))
                Text(Date.now.formatted(.dateTime.month(.abbreviated).day()).uppercased())
                    .font(TE.mono(.caption2))
                    .foregroundStyle(TE.textMuted)
            }
        }
        .accessibilityElement()
        .accessibilityLabel("An empty day dial with the first place starting to fill in")
    }

    private func ring(from start: Double, to end: Double, ink: Color) -> some View {
        Circle()
            .inset(by: 26)
            .trim(from: start, to: end)
            .stroke(ink, lineWidth: 23)
            .rotationEffect(.degrees(-90))
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
