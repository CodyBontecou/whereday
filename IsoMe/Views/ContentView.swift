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
    @State private var showingTrackingPausedNotice = false

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
            if AppModelStore.openFailure != nil {
                StoreUnavailableView()
            } else if let viewModel = viewModel {
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
        .background {
            AppAppearanceWindowOverride(appearance: AppAppearance(rawValue: appAppearance) ?? .system)
                .accessibilityHidden(true)
        }
        .background {
            if !hasCompletedOnboarding || showsInitialPaywall {
                InkPaperPalette.paper.ignoresSafeArea()
            }
        }
        .tint(!hasCompletedOnboarding || showsInitialPaywall ? InkPaperPalette.ink : TE.accent)
        .task {
            if viewModel == nil {
                if let failure = AppModelStore.openFailure {
                    LogManager.shared.error("Saved data couldn't be opened · \(failure)", category: .general)
                    return
                }

                // Must run before LocationManager, which resumes tracking from saved state.
                let launch = LaunchGuard.beginLaunch(
                    isBackgroundLaunch: UIApplication.shared.applicationState == .background
                )
                if let stage = launch.previousStage {
                    LogManager.shared.warning("Previous launch didn't finish · last step: \(stage)", category: .general)
                }
                if launch.pausedTracking {
                    LogManager.shared.warning(
                        "Tracking paused after \(launch.previousUnconfirmedLaunches) launches that didn't finish",
                        category: .location
                    )
                    Task { await LiveActivityManager.shared.endAllActivities() }
                }

                LaunchGuard.mark(.locationManager)
                let manager = LocationManager()
                ManualVisitAutomation.foregroundContext = modelContext
                locationManager = manager
                LaunchGuard.mark(.viewModel)
                // With tracking paused above, this also closes the interrupted recording.
                viewModel = LocationViewModel(
                    modelContext: modelContext,
                    locationManager: manager
                )
                LaunchGuard.mark(.interface)
                LaunchGuard.scheduleConfirmation()
                showingTrackingPausedNotice = LaunchGuard.consumePausedTrackingNotice()

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
        .alert("Recording Paused", isPresented: $showingTrackingPausedNotice) {
            Button("OK", role: .cancel) {}
        } message: {
            Text("Whereday didn't open properly the last few times, so it stopped recording to get you back in. Your history is safe. Start recording again whenever you're ready.")
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
            // How Whereday listens shows when the Apple Watch last handed its recording over.
            UserDefaults.standard.set(Date().timeIntervalSince1970, forKey: WatchSyncStatus.lastImportDateKey)
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

extension EnvironmentValues {
    /// True when a page is shown as its own navigation-rail tab (Settings on the open rail) rather than
    /// pushed from another page, so it can hide a back button that would lead nowhere.
    @Entry var isPresentedAsRailTab = false
}

private struct MainTabView: View {
    @Environment(ManualVisitRouter.self) private var manualVisitRouter
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let viewModel: LocationViewModel
    var startsOnArchive = false
    // The map is home (0). Timeline (3) opens over it; Settings (2) lives behind Archive, or on the open rail.
    @State private var selectedTab: Int = MainTabLaunch.current.tab.rawValue
    @State private var showsTimeline = MainTabLaunch.current.opensTimeline
    @State private var hidesTabBar = false
    @ObservedObject private var exportToastCenter = ExportToastCenter.shared

    /// Open on a foldable (and on other regular-width displays) a navigation rail replaces the tab bar.
    private var usesRail: Bool { horizontalSizeClass == .regular }

    var body: some View {
        // One TabView identity across folding and unfolding, so every tab keeps its state.
        HStack(spacing: 0) {
            if usesRail {
                WheredayNavigationRail(selection: $selectedTab)
                    .padding(.leading, 14)
                    .padding(.vertical, 20)
            }
            tabs
        }
        .background(TE.surface.ignoresSafeArea())
        .onAppear {
            if startsOnArchive { selectedTab = MainTab.archive.rawValue }
        }
        .onChange(of: usesRail) { _, railed in
            // Settings has no slot in the closed tab bar; it lives behind Archive there.
            if !railed && selectedTab == MainTab.settings.rawValue {
                selectedTab = MainTab.archive.rawValue
            }
        }
        .sheet(isPresented: $showsTimeline) {
            timelineWorkspace
                .presentationDragIndicator(.visible)
        }
        .onChange(of: manualVisitRouter.request?.id, initial: true) { _, id in
            // Links and Shortcuts open on the map, which presents the visit or the check-in form.
            if id != nil {
                showsTimeline = false
                selectedTab = MainTab.map.rawValue
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

    private func tabChrome(overlaysBar: Bool = false) -> TabChrome {
        TabChrome(showsBar: !usesRail && !(overlaysBar && hidesTabBar), overlaysBar: overlaysBar, selection: $selectedTab)
    }

    /// The system tab bar stays hidden; the floating bar or the rail drives the selection.
    private var tabs: some View {
        TabView(selection: $selectedTab) {
            mapWorkspace
                .onPreferenceChange(HidesFloatingTabBarKey.self) { hides in
                    hidesTabBar = hides
                }
                .modifier(tabChrome(overlaysBar: true))
                .tabItem { Label(MainTab.map.title, systemImage: MainTab.map.systemImage) }
                .tag(MainTab.map.rawValue)

            AtlasView(viewModel: viewModel)
                .modifier(tabChrome())
                .tabItem { Label(MainTab.atlas.title, systemImage: MainTab.atlas.systemImage) }
                .tag(MainTab.atlas.rawValue)

            NavigationStack {
                SavedLocationsView(viewModel: viewModel)
            }
            .modifier(tabChrome())
            .tabItem { Label(MainTab.places.title, systemImage: MainTab.places.systemImage) }
            .tag(MainTab.places.rawValue)

            archiveTab
                .modifier(tabChrome())
                .tabItem { Label(MainTab.archive.title, systemImage: MainTab.archive.systemImage) }
                .tag(MainTab.archive.rawValue)

            if usesRail {
                SettingsView(viewModel: viewModel)
                    // A rail destination of its own: there is no Archive page to go back to.
                    .environment(\.isPresentedAsRailTab, true)
                    .modifier(tabChrome())
                    .tabItem { Label(MainTab.settings.title, systemImage: MainTab.settings.systemImage) }
                    .tag(MainTab.settings.rawValue)
            }
        }
        .tint(TE.inkRust)
    }

    /// ExportView lays out its own composer and live preview when open, so it takes no decorative
    /// second pane. Wrapping it in an ArrangementView would also drop the floating tab bar's inset
    /// and hide the Export footer behind the bar.
    private var archiveTab: some View {
        ExportView(viewModel: viewModel)
    }

    @ViewBuilder
    private var mapWorkspace: some View {
        // The Duo SDK ships with Swift 6.4; older SDKs cannot resolve ArrangementView.
        #if compiler(>=6.4)
        if #available(iOS 27.1, *) {
            DuoMapWorkspace(
                viewModel: viewModel,
                expandsDaySheet: MainTabLaunch.current.expandsDaySheet,
                onOpenTimeline: { showsTimeline = true }
            )
        } else {
            singlePaneMap
        }
        #else
        singlePaneMap
        #endif
    }

    private var singlePaneMap: some View {
        LocationMapView(
            viewModel: viewModel,
            presentation: .home,
            expandsDaySheet: MainTabLaunch.current.expandsDaySheet,
            onOpenTimeline: { showsTimeline = true }
        )
    }

    /// Picking a day: the timeline, with the map beside it when the display is open.
    @ViewBuilder
    private var timelineWorkspace: some View {
        #if compiler(>=6.4)
        if #available(iOS 27.1, *) {
            DuoJournalWorkspace(viewModel: viewModel, onShowOnMap: showMap)
        } else {
            OutingsView(viewModel: viewModel, onShowOnMap: showMap)
        }
        #else
        OutingsView(viewModel: viewModel, onShowOnMap: showMap)
        #endif
    }

    private func showMap() {
        showsTimeline = false
        selectedTab = MainTab.map.rawValue
    }
}

/// Hides the system tab bar and, when closed, places the floating bar below each tab so content ends above it.
/// Applied per tab (outside any NavigationStack) so pushed pages keep the bar and the inset too.
/// The map runs full-bleed under the bar instead, and is told how much of its bottom edge the bar covers.
private struct TabChrome: ViewModifier {
    let showsBar: Bool
    var overlaysBar = false
    @Binding var selection: Int
    @State private var barHeight: CGFloat = 0

    func body(content: Content) -> some View {
        Group {
            if overlaysBar {
                ZStack(alignment: .bottom) {
                    content
                        .environment(\.floatingTabBarInset, showsBar ? barHeight : 0)
                        .frame(maxHeight: .infinity)
                    if showsBar {
                        WheredayTabBar(selection: $selection)
                            .onGeometryChange(for: CGFloat.self) { $0.size.height } action: { barHeight = $0 }
                            .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                }
            } else {
                // Stacked rather than a safeAreaInset: an inset added outside a NavigationStack doesn't reach
                // the stack's content, so scroll views and pinned footers ended up behind the bar.
                VStack(spacing: 0) {
                    content
                        .frame(maxHeight: .infinity)
                    if showsBar {
                        WheredayTabBar(selection: $selection)
                    }
                }
            }
        }
        .background(TE.surface.ignoresSafeArea())
        .toolbar(.hidden, for: .tabBar)
    }
}

/// The floating capsule tab bar: Map, Atlas, Places, Archive.
/// At accessibility text sizes it drops the labels for larger icons; long-press shows the large content viewer.
private struct WheredayTabBar: View {
    @Binding var selection: Int
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var iconOnly: Bool { dynamicTypeSize.isAccessibilitySize }

    var body: some View {
        let shape = RoundedRectangle(cornerRadius: iconOnly ? 28 : 32, style: .continuous)
        HStack(spacing: 0) {
            ForEach(MainTab.primary) { tab in
                item(tab)
            }
        }
        .padding(.horizontal, 4)
        .background(shape.fill(TE.card).shadow(color: TE.textPrimary.opacity(0.08), radius: 12, y: 8))
        .overlay(shape.strokeBorder(TE.border, lineWidth: 1))
        .padding(.horizontal, iconOnly ? 12 : 16)
        .padding(.top, 6)
        .padding(.bottom, 2)
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isTabBar)
        .accessibilityLabel("Main")
    }

    private func item(_ tab: MainTab) -> some View {
        let isSelected = selection == tab.rawValue
        return Button {
            selection = tab.rawValue
        } label: {
            VStack(spacing: 3) {
                Image(systemName: tab.systemImage)
                    .font(.system(size: iconOnly ? 28 : 20, weight: .regular))
                    .frame(height: iconOnly ? 32 : 24)
                if !iconOnly {
                    Text(tab.title)
                        .font(.system(size: 10, weight: isSelected ? .semibold : .medium))
                        .lineLimit(1)
                }
            }
            .foregroundStyle(isSelected ? TE.inkRust : TE.textMuted)
            .frame(maxWidth: .infinity, minHeight: iconOnly ? 84 : 64)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(tab.title))
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityShowsLargeContentViewer {
            Label(tab.title, systemImage: tab.systemImage)
        }
    }
}

/// Open on a foldable, a slim rail on the leading edge keeps every destination one tap away.
private struct WheredayNavigationRail: View {
    @Binding var selection: Int

    var body: some View {
        VStack(spacing: 6) {
            Spacer(minLength: 8)
            ForEach(MainTab.primary) { tab in
                item(tab)
            }
            Spacer(minLength: 8)
            Rectangle()
                .fill(TE.border)
                .frame(width: 24, height: 1)
                .padding(.vertical, 6)
                .accessibilityHidden(true)
            item(.settings)
                .padding(.bottom, 8)
        }
        .frame(width: 60)
        .frame(maxHeight: .infinity)
        .background(Capsule().fill(TE.card))
        .overlay(Capsule().strokeBorder(TE.border, lineWidth: 1))
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isTabBar)
        .accessibilityLabel("Main")
    }

    private func item(_ tab: MainTab) -> some View {
        let isSelected = selection == tab.rawValue
        return Button {
            selection = tab.rawValue
        } label: {
            VStack(spacing: 2) {
                Image(systemName: tab.systemImage)
                    .font(.system(size: 20, weight: .regular))
                    .frame(height: 24)
                Text(tab.title)
                    .font(.system(size: 9, weight: isSelected ? .semibold : .medium))
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
            }
            .foregroundStyle(isSelected ? TE.inkRust : TE.textMuted)
            .frame(width: 52, height: 52)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(tab.title))
        .accessibilityAddTraits(isSelected ? .isSelected : [])
        .accessibilityShowsLargeContentViewer {
            Label(tab.title, systemImage: tab.systemImage)
        }
    }
}

#if compiler(>=6.4)
/// The timeline, picked from the map: open, the map stays beside it as a companion and focuses each stay.
/// System arrangements retain the primary screen while the inner display gains a companion,
/// and keep each page clear of the active hinge without device-name or orientation checks.
@available(iOS 27.1, *)
private struct DuoJournalWorkspace: View {
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let viewModel: LocationViewModel
    let onShowOnMap: () -> Void
    @State private var focusedVisit: Visit?

    private var hasCompanion: Bool { horizontalSizeClass == .regular }

    var body: some View {
        ArrangementView {
            OutingsView(
                viewModel: viewModel,
                usesCompanionMap: hasCompanion,
                onVisitOpened: { visit in focusedVisit = visit },
                onShowOnMap: {
                    if !hasCompanion { onShowOnMap() }
                }
            )
        } secondary: {
            LocationMapView(
                viewModel: viewModel,
                focusedVisit: focusedVisit,
                presentation: .companion,
                onCloseFocusedVisit: { focusedVisit = nil }
            )
        }
        .arrangementViewStyle(.split.axes(hasCompanion ? Axis.Set.horizontal.union(.vertical) : Axis.Set()))
    }
}

/// The map home on a foldable. Closed, the cover display shows the map with its day sheet, as on any phone.
/// Open, the day (Today's dial and list) moves to the companion pane beside the map: a tapped stay is focused
/// on the map and inspected there, and a tapped marker opens the stay's details in the companion.
/// Half-open (tabletop), replay keeps the map above the fold and moves its controls below it.
@available(iOS 27.1, *)
private struct DuoMapWorkspace: View {
    /// Remembered so a relaunch while closed gets the cover layout before any hinge update arrives.
    static let isFoldableKey = "duo.isFoldableDevice"

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    let viewModel: LocationViewModel
    var expandsDaySheet = false
    let onOpenTimeline: () -> Void
    @State private var focusedVisit: Visit?
    @State private var inspectedVisit: Visit?
    @State private var coverInspector: Visit?
    @AppStorage(DuoMapWorkspace.isFoldableKey) private var hasHinge = false
    @State private var isHalfOpen = false
    @State private var showsReplay = false
    @State private var replayBridge = TraceReplayBridge()

    private var hasCompanion: Bool { horizontalSizeClass == .regular }
    private var usesTabletopReplay: Bool { hasCompanion && isHalfOpen && showsReplay }

    var body: some View {
        ArrangementView {
            LocationMapView(
                viewModel: viewModel,
                focusedVisit: hasCompanion ? focusedVisit : nil,
                onSelectVisit: hasCompanion ? { visit in
                    focusedVisit = visit
                    inspectedVisit = visit
                } : nil,
                presentation: .home,
                replayBridge: usesTabletopReplay ? replayBridge : nil,
                onCloseFocusedVisit: { focusedVisit = nil },
                companionReplay: $showsReplay,
                showsDaySheet: !hasCompanion,
                expandsDaySheet: expandsDaySheet,
                onOpenTimeline: onOpenTimeline
            )
        } secondary: {
            if usesTabletopReplay {
                TraceTabletopReplayPane(bridge: replayBridge)
                    .overlay(alignment: .top) { Rectangle().fill(TE.border).frame(height: 1).allowsHitTesting(false) }
            } else {
                companionDay
                    .overlay(alignment: .leading) { Rectangle().fill(TE.border).frame(width: 1).allowsHitTesting(false) }
            }
        }
        .arrangementViewStyle(.split.axes(hasCompanion ? Axis.Set.horizontal.union(.vertical) : Axis.Set()))
        .background {
            // onHingeChange only reports changes; the probe also reports the posture at launch.
            HingePresenceProbe { status in
                if status != nil, !hasHinge { hasHinge = true }
                isHalfOpen = status == .partiallyOpen
            }
            .frame(width: 0, height: 0)
            .accessibilityHidden(true)
        }
        .onHingeChange { oldContext, newContext in
            if (oldContext.hinge != nil || newContext.hinge != nil), !hasHinge { hasHinge = true }
            isHalfOpen = newContext.hinge?.status == .partiallyOpen
        }
        .onChange(of: hasCompanion, initial: true) { _, expanded in
            // Keep a single presentation owner while crossing to the cover display.
            if expanded {
                coverInspector = nil
            } else {
                coverInspector = inspectedVisit
            }
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

    /// Today's dial and list beside the map; a stay opens its details here and focuses it on the map.
    private var companionDay: some View {
        ZStack {
            TodayView(
                viewModel: viewModel,
                onReplay: { showsReplay = true },
                onPickDay: onOpenTimeline,
                onSelectVisit: { visit in
                    focusedVisit = visit
                    inspectedVisit = visit
                },
                selectedVisitID: focusedVisit?.id
            )
            .opacity(inspectedVisit == nil ? 1 : 0)
            .allowsHitTesting(inspectedVisit == nil)
            .accessibilityHidden(inspectedVisit != nil)

            if let inspectedVisit {
                NavigationStack {
                    VisitDetailView(visit: inspectedVisit, viewModel: viewModel, showsMap: false)
                        .toolbar {
                            ToolbarItem(placement: .topBarLeading) {
                                Button("Today", systemImage: "chevron.left") {
                                    self.inspectedVisit = nil
                                }
                            }
                        }
                }
            }
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
                    .font(WheredayFont.serif(size: 40, relativeTo: .largeTitle))
                    .accessibilityAddTraits(.isHeader)
                Text("Whereday quietly writes down where you were, so you can look back at any day, place or trip.")
                    .foregroundStyle(TE.textMuted)
            }
        }
    }

    private var privacyPage: some View {
        VStack(alignment: .leading, spacing: 26) {
            Text("It stays\non this phone.")
                .font(WheredayFont.serif(size: 40, relativeTo: .largeTitle))
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
                .font(WheredayFont.serif(size: 40, relativeTo: .largeTitle))
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
                    .font(.custom(WheredayFont.serifFamily, fixedSize: 39))
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

            // Today's day ends at capture time, so the dial never shows stops in the future.
            let now = Date()
            func ago(_ hours: Double) -> Date { now.addingTimeInterval(-hours * 3_600) }
            let morningStart = ago(6.2)
            let morningEnd = ago(5.35)
            let lunchStart = ago(2.9)
            let lunchEnd = ago(2.3)
            let eveningStart = ago(0.7)
            let eveningEnd = now

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
                    arrivedAt: ago(3.4),
                    departedAt: ago(3.0),
                    customName: nil,
                    locationName: "Valencia Street Stop",
                    address: "375 Valencia St, San Francisco, CA",
                    notes: "Tap a nearby business suggestion to rename this visit.",
                    geocodingCompleted: true
                ),
                Visit(
                    latitude: 37.7877,
                    longitude: -122.4077,
                    arrivedAt: ago(2.3),
                    departedAt: ago(1.55),
                    customName: "Client Lunch — The Grove",
                    locationName: "Market Street Stop",
                    address: "690 Mission St, San Francisco, CA",
                    notes: "Custom visit names are preserved separately from the detected place.",
                    geocodingCompleted: true
                ),
                Visit(
                    latitude: 37.7951,
                    longitude: -122.3938,
                    arrivedAt: ago(0.3),
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
            pointSets.flatMap { $0 }.forEach { $0.countryCode = "US" }
            visits.forEach { $0.countryCode = "US" }
            seedPlaces(in: context, today: today)
            seedCountryStays(in: context, today: today)
            try context.save()
        } catch {
            assertionFailure("Failed to seed screenshot demo data: \(error)")
        }
    }

    /// Saved places plus eight weeks of earlier visits, so Places shows real visit counts.
    private static func seedPlaces(in context: ModelContext, today: Date) {
        let places: [(name: String, latitude: Double, longitude: Double, kind: SavedPlaceKind, every: Int)] = [
            ("Home", 37.7599, -122.4148, .home, 1),
            ("Studio on Mission", 37.7877, -122.4077, .work, 2),
            ("Valencia Coffee", 37.7765, -122.4258, .cafe, 3),
            ("Dolores Park", 37.7617, -122.4350, .other, 6),
            ("Ferry Building", 37.7951, -122.3938, .other, 9),
            ("Mission Cliffs Gym", 37.7646, -122.4120, .gym, 4)
        ]
        for (offset, place) in places.enumerated() {
            context.insert(SavedPlace(name: place.name, latitude: place.latitude, longitude: place.longitude,
                                      address: nil, radiusMeters: 150, kind: place.kind))
            for daysAgo in stride(from: 3 + offset, through: 56, by: place.every) {
                let day = Calendar.current.date(byAdding: .day, value: -daysAgo, to: today) ?? today
                let arrival = date(on: day, hour: 8 + (offset * 2) % 11, minute: (daysAgo * 7) % 60)
                let visit = Visit(latitude: place.latitude, longitude: place.longitude, arrivedAt: arrival,
                                  departedAt: arrival.addingTimeInterval(Double(35 + (daysAgo * 13) % 80) * 60),
                                  locationName: place.name, geocodingCompleted: true)
                visit.countryCode = "US"
                context.insert(visit)
            }
        }
    }

    /// Trips abroad between stretches at home, so Atlas shows a colored year.
    private static func seedCountryStays(in context: ModelContext, today: Date) {
        let trips: [(country: String, from: Int, to: Int)] = [
            ("MX", 300, 291), ("CA", 262, 257), ("JP", 214, 199), ("PT", 142, 128), ("FR", 97, 90), ("GB", 47, 41)
        ]
        let timeZone = TimeZone.current
        func day(_ daysAgo: Int) -> String {
            CountryDayCalculator.dayIdentifier(
                Calendar.current.date(byAdding: .day, value: -daysAgo, to: today) ?? today, timeZone: timeZone)
        }
        var homeFrom = 310
        for trip in trips {
            context.insert(CountryStay(countryCode: "US", firstDay: day(homeFrom), lastDay: day(trip.from + 1)))
            context.insert(CountryStay(countryCode: trip.country, firstDay: day(trip.from), lastDay: day(trip.to)))
            homeFrom = trip.to - 1
        }
        context.insert(CountryStay(countryCode: "US", firstDay: day(homeFrom), lastDay: day(0)))
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
        for place in try context.fetch(FetchDescriptor<SavedPlace>()) {
            context.delete(place)
        }
        for stay in try context.fetch(FetchDescriptor<CountryStay>()) {
            context.delete(stay)
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

/// Shown instead of the app when the saved data can't be opened. The data stays on
/// disk untouched, so reopening the app (or an update) can still recover it; nothing
/// here offers to delete it.
struct StoreUnavailableView: View {
    var body: some View {
        ContentUnavailableView {
            Label("Whereday Couldn't Open Your Data", systemImage: "externaldrive.badge.exclamationmark")
        } description: {
            Text("Your history hasn't been deleted. Close Whereday and open it again. If this keeps happening, contact support and don't reinstall the app, because that would delete your history.")
        }
    }
}
