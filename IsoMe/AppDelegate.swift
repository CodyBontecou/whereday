import UIKit
import CoreLocation
import UserNotifications
import ExportAutomationKit

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

        // Evening recaps: migrate the old one-shot and rebuild the next request, also on background launches.
        RecapReminderScheduler.shared.start(container: AppModelStore.openFailure == nil ? AppModelStore.shared : nil)

        // Check if app was launched due to a location event
        if let locationKey = launchOptions?[.location] as? Bool, locationKey {
            // App was launched in background due to location event
            // The LocationManager will be initialized and will receive the pending events
            print("App launched from location event")
        }

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
        if request.identifier == RecapReminderScheduler.requestIdentifier {
            let kind = (request.content.userInfo[RecapReminderScheduler.userInfoKindKey] as? String)
                .flatMap(RecapPeriodKind.init(rawValue:)) ?? .day
            Task { @MainActor in
                RecapOpenRequest.shared.open(kind)
                RecapReminderScheduler.shared.setNeedsRefresh(after: 1)
            }
        }
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
        if notification.request.identifier == RecapReminderScheduler.requestIdentifier {
            // Whereday is open, so the day is already on screen; schedule the following recap instead.
            Task { @MainActor in RecapReminderScheduler.shared.setNeedsRefresh(after: 1) }
            completionHandler([])
            return
        }
        completionHandler([.banner, .sound])
    }

    func applicationWillTerminate(_ application: UIApplication) {
        // Save any pending data before termination
        print("App will terminate - saving state")
    }
}
