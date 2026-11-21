#if DEBUG
import ScrawlKit
#endif
import SwiftUI
import SwiftData
import StoreKit

@main
struct IsoMeApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @StateObject private var watchSyncReceiver: WatchLocationSyncReceiver
    @State private var showingMyselfClaim = false
    @State private var manualVisitRouter = ManualVisitRouter()

    init() {
        #if DEBUG
        OnboardingLaunchConfiguration.consumeOverride()
        AppReviewPromptCoordinator.applyDebugSeedIfRequested()
        #endif
        RetiredAnalyticsCleanup.run()
        #if DEBUG && os(iOS)
        if !ProcessInfo.processInfo.arguments.contains("--disable-debug-gestures") {
            DebugSelfie.install()
        }
        DebugScrollDiagnostics.installIfRequested()
        #endif
        let receiver = WatchLocationSyncReceiver()
        if AppModelStore.openFailure == nil {
            receiver.configure(modelContainer: sharedModelContainer)
        }
        _watchSyncReceiver = StateObject(wrappedValue: receiver)
    }

    /// Shared with in-app intents; see `AppModelStore`.
    let sharedModelContainer = AppModelStore.shared

    @Environment(\.scenePhase) private var scenePhase

    var body: some Scene {
        WindowGroup {
            ContentView()
                .environment(manualVisitRouter)
                .alert("Visit Link", isPresented: Binding(
                    get: { manualVisitRouter.errorMessage != nil },
                    set: { if !$0 { manualVisitRouter.errorMessage = nil } }
                )) { Button("OK", role: .cancel) {} } message: {
                    Text(manualVisitRouter.errorMessage ?? "")
                }
                #if DEBUG
                .scrawl(isEnabled: !ProcessInfo.processInfo.arguments.contains("--disable-debug-gestures"))
                #endif
                .sheet(isPresented: $showingMyselfClaim) {
                    MyselfClaimView(app: "iso.me")
                }
                .onOpenURL { url in
                    #if DEBUG
                    if url.host == "scrawl" || url.host == "tether" { return }
                    #endif
                    handleDeepLink(url)
                }
                .task {
                    // Never sync into the empty stand-in: the watch would drop data it thinks was saved.
                    guard AppModelStore.openFailure == nil else { return }
                    DailyExportScheduler.shared.attach(modelContainer: sharedModelContainer)
                    DailyExportScheduler.shared.scheduleNextBackgroundRun()
                    await DailyExportScheduler.shared.runIfDue()
                }
        }
        .modelContainer(sharedModelContainer)
        .onChange(of: scenePhase) { oldPhase, newPhase in
            switch newPhase {
            case .active:
                // Counts a day of use only; review requests come from success moments.
                AppReviewPromptCoordinator.shared.recordAppUse()
                NotificationCenter.default.post(name: .appDidBecomeActive, object: nil)
                Task { await DailyExportScheduler.shared.runIfDue() }
            case .inactive:
                break
            case .background:
                // Reaching the background on its own means this launch didn't die.
                LaunchGuard.confirmLaunch()
                NotificationCenter.default.post(name: .appDidEnterBackground, object: nil)
                DailyExportScheduler.shared.scheduleNextBackgroundRun()
            @unknown default:
                break
            }
        }
    }
    
    private func handleDeepLink(_ url: URL) {
        guard let scheme = url.scheme, ["isome", "whereday"].contains(scheme) else { return }

        if url.host == "myself", url.path == "/claim", url.user == nil,
           url.password == nil, url.port == nil, url.query == nil, url.fragment == nil {
            guard MyselfClaimFeature.isEnabled else { return }
            showingMyselfClaim = true
            return
        }

        switch url.host {
        case "check-in", "log-visit", "visit":
            manualVisitRouter.open(url)
        case "stop":
            NotificationCenter.default.post(name: .stopTracking, object: nil)
        default:
            break
        }
    }
}

#if DEBUG
/// Treat the QA launch preference as an initial value, not a session-long lock.
/// Otherwise NSArgumentDomain masks writes made by Get Started and Settings.
enum OnboardingLaunchConfiguration {
    static func consumeOverride(defaults: UserDefaults = .standard) {
        let key = "hasCompletedOnboarding"
        var arguments = defaults.volatileDomain(forName: UserDefaults.argumentDomain)
        guard arguments[key] != nil else { return }
        let initialValue = defaults.bool(forKey: key)
        arguments.removeValue(forKey: key)
        defaults.setVolatileDomain(arguments, forName: UserDefaults.argumentDomain)
        defaults.set(initialValue, forKey: key)
    }
}
#endif

// MARK: - App Store Review Prompt

/// A finished task that may lead to a review request. Only work the person just chose to do and saw succeed
/// qualifies; automatic, background and safety-net work never does.
enum AppReviewPromptSuccess: String, CaseIterable {
    case countryDaysExport
    case folderExport
    case obsidianExport
    case shareExport
    case outingExport
    case historyImport
    case purchase
    /// The backup Settings makes right before Clear All Data: a destructive confirmation follows.
    case backupBeforeClear
    /// Scheduled exports, including the manual Run Now of a schedule.
    case scheduledExport

    var qualifies: Bool {
        switch self {
        case .countryDaysExport, .folderExport, .obsidianExport, .shareExport, .outingExport, .historyImport, .purchase:
            return true
        case .backupBeforeClear, .scheduledExport:
            return false
        }
    }
}

/// Asks for an App Store review right after a success, never on launch or resume.
///
/// Rules: use on at least two distinct days, a qualifying success, at most once per app version and
/// 120 days apart. The request waits about a second for the success toast and is skipped unless
/// the screen is clear (onboarding done, no paywall, sheet, alert or system prompt, app active).
/// A skipped request isn't retried; the next success can try again.
@MainActor
final class AppReviewPromptCoordinator {
    static let shared = AppReviewPromptCoordinator()
    static let cooldown: TimeInterval = 120 * 24 * 60 * 60
    static let maxStoredUseDays = 30
    static let requiredUseDays = 2

    enum DefaultsKey {
        static let usedDayIDs = "reviewPrompt.usedDayIDs"
        static let lastRequestedVersion = "reviewPrompt.lastRequestedVersion"
        static let lastRequestDate = "reviewPrompt.lastRequestDate"
        /// Pre-cooldown keys: a single request ever, after one export.
        static let legacyCompletedFileExport = "reviewPrompt.completedFileExport"
        static let legacyRequested = "reviewPrompt.requestedSecondDayExportReview"
    }

    private let defaults: UserDefaults
    private let calendar: Calendar
    private let appVersion: String
    private let now: () -> Date
    private let reviewRequestDelay: TimeInterval
    private let canPresent: @MainActor () -> Bool
    private let requestReview: @MainActor () -> Bool
    private var isReviewRequestScheduled = false

    init(
        defaults: UserDefaults = .standard,
        calendar: Calendar = .current,
        appVersion: String = AppInfo.version,
        now: @escaping () -> Date = Date.init,
        reviewRequestDelay: TimeInterval = 1,
        canPresent: @escaping @MainActor () -> Bool = AppReviewPromptCoordinator.screenIsClear,
        requestReview: @escaping @MainActor () -> Bool = AppReviewPromptCoordinator.requestStoreKitReview
    ) {
        self.defaults = defaults
        self.calendar = calendar
        self.appVersion = appVersion
        self.now = now
        self.reviewRequestDelay = reviewRequestDelay
        self.canPresent = canPresent
        self.requestReview = requestReview
        migrateLegacyState()
    }

    /// Counts a day of use. Never requests a review.
    func recordAppUse(on date: Date? = nil) {
        recordUseDay(on: date ?? now())
    }

    /// Call at the moment a success is shown (after a file is saved, a share completes, a sheet that
    /// showed the result has closed). Non-qualifying sources are ignored entirely.
    func recordSuccess(_ source: AppReviewPromptSuccess, on date: Date? = nil) {
        guard source.qualifies else { return }
        recordUseDay(on: date ?? now())
        scheduleReviewRequest()
    }

    var recordedUseDayCount: Int { usedDayIDs.count }

    var lastRequestedVersion: String? { defaults.string(forKey: DefaultsKey.lastRequestedVersion) }

    var lastRequestDate: Date? { defaults.object(forKey: DefaultsKey.lastRequestDate) as? Date }

    func isEligible(at date: Date) -> Bool {
        guard recordedUseDayCount >= Self.requiredUseDays, lastRequestedVersion != appVersion else { return false }
        guard let last = lastRequestDate else { return true }
        return date.timeIntervalSince(last) >= Self.cooldown
    }

    private var usedDayIDs: [String] {
        get { defaults.stringArray(forKey: DefaultsKey.usedDayIDs) ?? [] }
        set { defaults.set(Array(newValue.sorted().suffix(Self.maxStoredUseDays)), forKey: DefaultsKey.usedDayIDs) }
    }

    private func migrateLegacyState() {
        if defaults.bool(forKey: DefaultsKey.legacyRequested) {
            // Already asked once under the old rule: the cooldown starts now; any version may ask after it.
            if lastRequestDate == nil {
                defaults.set(now(), forKey: DefaultsKey.lastRequestDate)
            }
        }
        defaults.removeObject(forKey: DefaultsKey.legacyRequested)
        defaults.removeObject(forKey: DefaultsKey.legacyCompletedFileExport)
        let ids = usedDayIDs
        if ids.count > Self.maxStoredUseDays {
            usedDayIDs = ids
        }
    }

    private func recordUseDay(on date: Date) {
        var ids = Set(usedDayIDs)
        if ids.insert(dayIdentifier(for: date)).inserted {
            usedDayIDs = Array(ids)
        }
    }

    private func dayIdentifier(for date: Date) -> String {
        let components = calendar.dateComponents([.year, .month, .day], from: date)
        return String(
            format: "%04d-%02d-%02d",
            components.year ?? 0,
            components.month ?? 0,
            components.day ?? 0
        )
    }

    private func scheduleReviewRequest() {
        guard isEligible(at: now()), !isReviewRequestScheduled else { return }

        isReviewRequestScheduled = true
        if reviewRequestDelay <= 0 {
            performScheduledReviewRequest()
        } else {
            let delay = UInt64(reviewRequestDelay * 1_000_000_000)
            Task { @MainActor [weak self] in
                try? await Task.sleep(nanoseconds: delay)
                self?.performScheduledReviewRequest()
            }
        }
    }

    private func performScheduledReviewRequest() {
        isReviewRequestScheduled = false
        let date = now()
        guard isEligible(at: date), canPresent() else { return }

        if requestReview() {
            defaults.set(appVersion, forKey: DefaultsKey.lastRequestedVersion)
            defaults.set(date, forKey: DefaultsKey.lastRequestDate)
        }
    }

    /// Nothing else on screen: onboarding and the hard paywall are done, no sheet, alert,
    /// confirmation or share sheet is presented, and no system prompt has made the app inactive.
    static func screenIsClear() -> Bool {
        guard UIApplication.shared.applicationState == .active,
              UserDefaults.standard.bool(forKey: "hasCompletedOnboarding"),
              StoreManager.shared.hasResolvedEntitlements,
              !StoreManager.shared.requiresInitialPaywall,
              let scene = foregroundActiveScene else { return false }
        return scene.windows.allSatisfy { window in
            window.isHidden || window.rootViewController?.presentedViewController == nil
        }
    }

    private static var foregroundActiveScene: UIWindowScene? {
        UIApplication.shared.connectedScenes
            .first(where: { $0.activationState == .foregroundActive }) as? UIWindowScene
    }

    private static func requestStoreKitReview() -> Bool {
        guard let scene = foregroundActiveScene else { return false }
        AppStore.requestReview(in: scene)
        return true
    }

    #if DEBUG
    /// QA only: `-reviewPromptSeedUseDays N` records N earlier days of use and clears any past request,
    /// so one success in a fresh install can reach the system review sheet.
    static func applyDebugSeedIfRequested(defaults: UserDefaults = .standard, calendar: Calendar = .current) {
        let count = defaults.integer(forKey: "reviewPromptSeedUseDays")
        guard count > 0 else { return }
        defaults.removeObject(forKey: DefaultsKey.lastRequestedVersion)
        defaults.removeObject(forKey: DefaultsKey.lastRequestDate)
        defaults.removeObject(forKey: DefaultsKey.legacyRequested)
        let coordinator = AppReviewPromptCoordinator(defaults: defaults, calendar: calendar, canPresent: { false }, requestReview: { false })
        for offset in 1...min(count, maxStoredUseDays) {
            if let day = calendar.date(byAdding: .day, value: -offset, to: Date()) {
                coordinator.recordAppUse(on: day)
            }
        }
    }
    #endif
}

// MARK: - Notification Names

extension Notification.Name {
    static let appDidBecomeActive = Notification.Name("appDidBecomeActive")
    static let appDidEnterBackground = Notification.Name("appDidEnterBackground")
    static let stopTracking = Notification.Name("stopTracking")
}
