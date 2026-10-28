#if DEBUG
import ScrawlKit
#endif
import SwiftUI
import SwiftData
import StoreKit

@main
struct IsoMeApp: App {
    @UIApplicationDelegateAdaptor(AppDelegate.self) var appDelegate
    @StateObject private var watchSyncReceiver = WatchLocationSyncReceiver()
    @State private var showingMyselfClaim = false
    @State private var manualVisitRouter = ManualVisitRouter()

    init() {
        #if DEBUG && os(iOS)
        DebugSelfie.install()
        #endif
    }

    var sharedModelContainer: ModelContainer = {
        let schema = Schema([
            Visit.self,
            LocationPoint.self,
            RecordingSession.self,
            PhotoMoment.self,
            SavedPlace.self,
            CountryStay.self
        ])
        let modelConfiguration: ModelConfiguration
        #if DEBUG && targetEnvironment(simulator)
        if ProcessInfo.processInfo.arguments.contains("--watch-sync-replay") {
            let directory = URL.documentsDirectory.appendingPathComponent("WatchSyncReplay")
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            modelConfiguration = ModelConfiguration(schema: schema, url: directory.appendingPathComponent("phone.store"))
        } else {
            modelConfiguration = ModelConfiguration(schema: schema, isStoredInMemoryOnly: false, allowsSave: true)
        }
        #else
        modelConfiguration = ModelConfiguration(schema: schema, isStoredInMemoryOnly: false, allowsSave: true)
        #endif

        do {
            let container = try ModelContainer(for: schema, configurations: [modelConfiguration])
            return container
        } catch {
            fatalError("Could not create ModelContainer: \(error)")
        }
    }()

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
                .scrawl()
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
                    watchSyncReceiver.configure(modelContainer: sharedModelContainer)
                    DailyExportScheduler.shared.attach(modelContainer: sharedModelContainer)
                    DailyExportScheduler.shared.scheduleNextBackgroundRun()
                    await DailyExportScheduler.shared.runIfDue()
                }
        }
        .modelContainer(sharedModelContainer)
        .onChange(of: scenePhase) { oldPhase, newPhase in
            switch newPhase {
            case .active:
                AppReviewPromptCoordinator.shared.recordAppUse()
                NotificationCenter.default.post(name: .appDidBecomeActive, object: nil)
                Task { await DailyExportScheduler.shared.runIfDue() }
            case .inactive:
                break
            case .background:
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

// MARK: - App Store Review Prompt

@MainActor
final class AppReviewPromptCoordinator {
    static let shared = AppReviewPromptCoordinator()

    private enum DefaultsKey {
        static let usedDayIDs = "reviewPrompt.usedDayIDs"
        static let completedFileExport = "reviewPrompt.completedFileExport"
        static let requestedSecondDayExportReview = "reviewPrompt.requestedSecondDayExportReview"
    }

    private let defaults: UserDefaults
    private let calendar: Calendar
    private let reviewRequestDelay: TimeInterval
    private let requestReview: @MainActor () -> Bool
    private var isReviewRequestScheduled = false

    init(
        defaults: UserDefaults = .standard,
        calendar: Calendar = .current,
        reviewRequestDelay: TimeInterval = 1,
        requestReview: @escaping @MainActor () -> Bool = AppReviewPromptCoordinator.requestStoreKitReview
    ) {
        self.defaults = defaults
        self.calendar = calendar
        self.reviewRequestDelay = reviewRequestDelay
        self.requestReview = requestReview
    }

    func recordAppUse(on date: Date = Date()) {
        recordUseDay(on: date)
        requestReviewIfEligible()
    }

    func recordSuccessfulFileExport(on date: Date = Date()) {
        defaults.set(true, forKey: DefaultsKey.completedFileExport)
        recordUseDay(on: date)
        requestReviewIfEligible()
    }

    var recordedUseDayCount: Int {
        usedDayIDs.count
    }

    var hasCompletedFileExport: Bool {
        defaults.bool(forKey: DefaultsKey.completedFileExport)
    }

    var hasRequestedMilestoneReview: Bool {
        defaults.bool(forKey: DefaultsKey.requestedSecondDayExportReview)
    }

    private var isEligibleForReviewRequest: Bool {
        !hasRequestedMilestoneReview && hasCompletedFileExport && recordedUseDayCount >= 2
    }

    private var usedDayIDs: [String] {
        get { defaults.stringArray(forKey: DefaultsKey.usedDayIDs) ?? [] }
        set { defaults.set(newValue.sorted(), forKey: DefaultsKey.usedDayIDs) }
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

    private func requestReviewIfEligible() {
        guard isEligibleForReviewRequest, !isReviewRequestScheduled else { return }

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
        guard isEligibleForReviewRequest else { return }

        if requestReview() {
            defaults.set(true, forKey: DefaultsKey.requestedSecondDayExportReview)
        }
    }

    private static func requestStoreKitReview() -> Bool {
        guard let scene = UIApplication.shared.connectedScenes
            .first(where: { $0.activationState == .foregroundActive }) as? UIWindowScene else {
            return false
        }

        AppStore.requestReview(in: scene)
        return true
    }
}

// MARK: - Notification Names

extension Notification.Name {
    static let appDidBecomeActive = Notification.Name("appDidBecomeActive")
    static let appDidEnterBackground = Notification.Name("appDidEnterBackground")
    static let stopTracking = Notification.Name("stopTracking")
}
