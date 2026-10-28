import Foundation
import UserNotifications

/// Local fallback notifications for daily exports.
///
/// The server-side APNs worker sends a silent push at the selected export time.
/// This scheduler adds a user-visible local fallback shortly after that time;
/// if the silent push/background task already completed, the fallback is
/// cancelled. If it is still visible and the user taps it, the app retries the
/// exact scheduled occurrence and skips the retry when `lastRun` already covers
/// it.
enum DailyExportNotificationPayload {
    static let typeValue = "daily-export"
    static let typeKey = "isome.notification.type"
    static let scheduledFireDateKey = "isome.dailyExport.scheduledFireDate"
    static let reasonKey = "isome.dailyExport.reason"
    static let identifierPrefix = "com.bontecou.isome.dailyexport."
    static let fallbackIdentifierPrefix = identifierPrefix + "fallback."

    private static let isoFormatter: ISO8601DateFormatter = {
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return formatter
    }()

    static func identifier(for fireDate: Date) -> String {
        fallbackIdentifierPrefix + String(Int(fireDate.timeIntervalSince1970))
    }

    static func userInfo(fireDate: Date, reason: String? = nil) -> [String: String] {
        var info = [
            typeKey: typeValue,
            scheduledFireDateKey: isoFormatter.string(from: fireDate)
        ]
        if let reason, !reason.isEmpty {
            info[reasonKey] = reason
        }
        return info
    }

    static func fireDate(from userInfo: [AnyHashable: Any]) -> Date? {
        guard userInfo[typeKey] as? String == typeValue,
              let value = userInfo[scheduledFireDateKey] as? String else {
            return nil
        }
        return isoFormatter.date(from: value)
    }

    static func isDailyExportNotification(identifier: String, userInfo: [AnyHashable: Any]) -> Bool {
        if fireDate(from: userInfo) != nil { return true }
        return identifier.hasPrefix(fallbackIdentifierPrefix)
    }
}

protocol DailyExportNotificationScheduling {
    func scheduleFallbackNotification(fireDate: Date) async throws
    func sendImmediateRetryNotification(fireDate: Date, reason: String?) async throws
    func cancelFallbackNotification(fireDate: Date)
    func cancelNotification(identifier: String)
}

struct UserNotificationDailyExportScheduler: DailyExportNotificationScheduling {
    private let center: UNUserNotificationCenter
    private let fallbackDelay: TimeInterval
    private let now: () -> Date

    init(
        center: UNUserNotificationCenter = .current(),
        fallbackDelay: TimeInterval = 60,
        now: @escaping () -> Date = Date.init
    ) {
        self.center = center
        self.fallbackDelay = fallbackDelay
        self.now = now
    }

    func scheduleFallbackNotification(fireDate: Date) async throws {
        let identifier = DailyExportNotificationPayload.identifier(for: fireDate)
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
        center.removeDeliveredNotifications(withIdentifiers: [identifier])

        let content = makeContent(
            title: String(localized: "Daily Export Ready"),
            body: String(localized: "Tap to run your scheduled Whereday export if it hasn't completed."),
            fireDate: fireDate,
            reason: nil
        )
        let triggerDate = fireDate.addingTimeInterval(fallbackDelay)
        let interval = max(1, triggerDate.timeIntervalSince(now()))
        let request = UNNotificationRequest(
            identifier: identifier,
            content: content,
            trigger: UNTimeIntervalNotificationTrigger(timeInterval: interval, repeats: false)
        )
        try await center.add(request)
    }

    func sendImmediateRetryNotification(fireDate: Date, reason: String?) async throws {
        let identifier = DailyExportNotificationPayload.identifier(for: fireDate)
        center.removePendingNotificationRequests(withIdentifiers: [identifier])

        let body: String
        if let reason, !reason.isEmpty {
            body = String(localized: "Tap to retry your scheduled Whereday export. \(reason)")
        } else {
            body = String(localized: "Tap to retry your scheduled Whereday export.")
        }

        let request = UNNotificationRequest(
            identifier: identifier,
            content: makeContent(
                title: String(localized: "Daily Export Needs Attention"),
                body: body,
                fireDate: fireDate,
                reason: reason
            ),
            trigger: nil
        )
        try await center.add(request)
    }

    func cancelFallbackNotification(fireDate: Date) {
        cancelNotification(identifier: DailyExportNotificationPayload.identifier(for: fireDate))
    }

    func cancelNotification(identifier: String) {
        center.removePendingNotificationRequests(withIdentifiers: [identifier])
        center.removeDeliveredNotifications(withIdentifiers: [identifier])
    }

    private func makeContent(
        title: String,
        body: String,
        fireDate: Date,
        reason: String?
    ) -> UNMutableNotificationContent {
        let content = UNMutableNotificationContent()
        content.title = title
        content.body = body
        content.sound = .default
        content.threadIdentifier = "com.bontecou.isome.dailyexport"
        content.categoryIdentifier = "com.bontecou.isome.dailyexport.retry"
        content.userInfo = DailyExportNotificationPayload.userInfo(fireDate: fireDate, reason: reason)
        return content
    }
}

final class InspectableDailyExportNotificationScheduler: DailyExportNotificationScheduling {
    private(set) var scheduledFireDates: [Date] = []
    private(set) var immediateRetryFireDates: [(fireDate: Date, reason: String?)] = []
    private(set) var canceledIdentifiers: [String] = []

    func scheduleFallbackNotification(fireDate: Date) async throws {
        scheduledFireDates.append(fireDate)
    }

    func sendImmediateRetryNotification(fireDate: Date, reason: String?) async throws {
        immediateRetryFireDates.append((fireDate, reason))
    }

    func cancelFallbackNotification(fireDate: Date) {
        canceledIdentifiers.append(DailyExportNotificationPayload.identifier(for: fireDate))
    }

    func cancelNotification(identifier: String) {
        canceledIdentifiers.append(identifier)
    }
}
