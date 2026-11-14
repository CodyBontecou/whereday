import UIKit
import CoreLocation
import UserNotifications
import ExportAutomationKit
import SwiftData

/// One tracking service graph per process, available before any scene/view task.
/// A failed persistent store never starts sensors against the empty recovery container.
@MainActor
final class AppTrackingServices {
    static let shared = AppTrackingServices(modelContainer: {
        guard AppModelStore.openFailure == nil else { return nil }
        return AppModelStore.shared
    }, makeManager: { allowsSmartStartRuntime in
        LocationManager(startsSmartStartRuntime: allowsSmartStartRuntime)
    }, attachServices: { container, manager in
        WebhookManager.shared.attach(modelContainer: container, locationManager: manager)
        DailyExportScheduler.shared.attach(modelContainer: container)
    })

    private let defaults: UserDefaults
    private let modelContainer: () -> ModelContainer?
    private let makeManager: (Bool) -> LocationManager
    private let attachServices: (ModelContainer, LocationManager) -> Void
    private var manager: LocationManager?

    init(defaults: UserDefaults = .standard, modelContainer: @escaping () -> ModelContainer?,
         makeManager: @escaping (Bool) -> LocationManager,
         attachServices: @escaping (ModelContainer, LocationManager) -> Void) {
        self.defaults = defaults
        self.modelContainer = modelContainer
        self.makeManager = makeManager
        self.attachServices = attachServices
    }

    @discardableResult
    func boot(isBackgroundLaunch: Bool) -> LocationManager? {
        if let manager { return manager }
        guard let container = modelContainer() else { return nil }
        let launch = LaunchGuard.beginLaunch(defaults: defaults, isBackgroundLaunch: isBackgroundLaunch)
        if let stage = launch.previousStage {
            LogManager.shared.warning("Previous launch didn't finish · last step: \(stage)", category: .general)
        }
        if launch.pausedTracking {
            LogManager.shared.warning("Tracking paused after \(launch.previousUnconfirmedLaunches) launches that didn't finish", category: .location)
            Task { await LiveActivityManager.shared.endAllActivities() }
        }
        LaunchGuard.mark(.locationManager, defaults: defaults)
        let created = makeManager(!launch.pausedTracking)
        created.setModelContext(container.mainContext)
        manager = created
        attachServices(container, created)
        return created
    }
}

class AppDelegate: NSObject, UIApplicationDelegate, UNUserNotificationCenterDelegate {
    // Shared location manager instance for background launches
    static var sharedLocationManager: LocationManager?

    func application(
        _ application: UIApplication,
        didFinishLaunchingWithOptions launchOptions: [UIApplication.LaunchOptionsKey: Any]? = nil
    ) -> Bool {
        // Install crash handler to capture crash info for debugging
        NSSetUncaughtExceptionHandler { exception in
            let info = """
            CRASH: \(exception.name.rawValue)
            REASON: \(exception.reason ?? "unknown")
            STACK: \(exception.callStackSymbols.joined(separator: "\n"))
            """
            UserDefaults.standard.set(info, forKey: "lastCrashLog")
            UserDefaults.standard.synchronize()
        }

        UNUserNotificationCenter.current().delegate = self

        // Register the daily-export background task before applicationDidFinishLaunching returns.
        DailyExportScheduler.registerBackgroundTask()

        // Location and silent-push launches may never create ContentView. Attach the
        // real store, exports and webhook services before pending callbacks arrive.
        let isLocationLaunch = launchOptions?[.location] as? Bool == true
        Self.sharedLocationManager = AppTrackingServices.shared.boot(
            isBackgroundLaunch: isLocationLaunch || application.applicationState == .background
        )

        return true
    }

    func applicationDidEnterBackground(_ application: UIApplication) {
        // Ensure location tracking continues in background
        print("App entered background - location tracking continues")
    }

    // MARK: - Remote notifications (server-side scheduled exports)

    func application(_ application: UIApplication,
                     didRegisterForRemoteNotificationsWithDeviceToken deviceToken: Data) {
        PushRegistrationManager.shared.submitDeviceToken(deviceToken)
    }

    func application(_ application: UIApplication,
                     didFailToRegisterForRemoteNotificationsWithError error: Error) {
        print("APNs registration failed: \(error.localizedDescription)")
    }

    func application(_ application: UIApplication,
                     didReceiveRemoteNotification userInfo: [AnyHashable: Any],
                     fetchCompletionHandler completionHandler: @escaping (UIBackgroundFetchResult) -> Void) {
        guard userInfo["type"] as? String == RemoteScheduleWorkerContract.scheduledExportPushType else {
            completionHandler(.noData)
            return
        }

        let fireDate = scheduledExportFireDate(from: userInfo)
        Task { @MainActor in
            let outcome = await DailyExportScheduler.shared.runFromServerNotification(fireDate: fireDate)
            completionHandler(outcome.completedExport ? .newData : .noData)
        }
    }

    private func scheduledExportFireDate(from userInfo: [AnyHashable: Any]) -> Date? {
        let stringKeys = ["fireAt", "fire_at", "scheduledFireDate", "scheduled_fire_date"]
        let formatter = ISO8601DateFormatter()
        let fractionalFormatter = ISO8601DateFormatter()
        fractionalFormatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        for key in stringKeys {
            guard let value = userInfo[key] as? String else { continue }
            if let date = fractionalFormatter.date(from: value) ?? formatter.date(from: value) {
                return date
            }
        }

        for key in stringKeys {
            if let value = userInfo[key] as? TimeInterval {
                return Date(timeIntervalSince1970: value)
            }
            if let value = userInfo[key] as? NSNumber {
                return Date(timeIntervalSince1970: value.doubleValue)
            }
        }

        return nil
    }

    // MARK: - Local notification taps

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                didReceive response: UNNotificationResponse,
                                withCompletionHandler completionHandler: @escaping () -> Void) {
        let request = response.notification.request
        if DailyExportNotificationPayload.isDailyExportNotification(
            identifier: request.identifier,
            userInfo: request.content.userInfo
        ) {
            Task { @MainActor in
                await DailyExportScheduler.shared.runFromNotificationTap(userInfo: request.content.userInfo)
            }
        }
        completionHandler()
    }

    func userNotificationCenter(_ center: UNUserNotificationCenter,
                                willPresent notification: UNNotification,
                                withCompletionHandler completionHandler: @escaping (UNNotificationPresentationOptions) -> Void) {
        completionHandler([.banner, .sound])
    }

    func applicationWillTerminate(_ application: UIApplication) {
        // Save any pending data before termination
        print("App will terminate - saving state")
    }
}
