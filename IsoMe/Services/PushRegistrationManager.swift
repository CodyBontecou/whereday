import Foundation
import OSLog
import Security
import UserNotifications
import ExportAutomationKit

#if os(iOS)
import UIKit
#endif

/// Bridges Whereday's daily export schedule to the scheduled-notifications worker.
///
/// The worker only stores routing/timing metadata (stable install id, APNs token,
/// bundle id, timezone, hour/minute, and next fire time). It never receives
/// location records, export files, destination folders, or filename templates.
final class PushRegistrationManager: @unchecked Sendable {
    static let shared = PushRegistrationManager()

    private let logger = Logger(subsystem: "com.bontecou.isome", category: "PushRegistration")
    private let remoteClient: any RemoteScheduleClient
    private let session: URLSession
    private let baseURL: URL
    private let upsertRetryPolicy = RemoteScheduleRetryPolicy()

    private static let keychainService = "com.bontecou.isome"
    private static let userIdKeychainAccount = "pushRegistrationUserId"

    init(
        session: URLSession = .shared,
        baseURL: URL = URL(string: "https://isome-scheduled-notifications.costream.workers.dev")!,
        remoteClient: (any RemoteScheduleClient)? = nil
    ) {
        self.baseURL = baseURL
        self.session = session
        self.remoteClient = remoteClient ?? URLSessionRemoteScheduleClient(baseURL: baseURL, session: session)
    }

    // MARK: - Stable install identity

    var userId: String {
        if let existing = readKeychainString(account: Self.userIdKeychainAccount) {
            return existing
        }
        let fresh = UUID().uuidString
        writeKeychainString(account: Self.userIdKeychainAccount, value: fresh)
        return fresh
    }

    private var platformString: String { "ios" }

    private var bundleId: String {
        Bundle.main.bundleIdentifier ?? "com.bontecou.isome"
    }

    // MARK: - Registration

    /// Register for APNs so the worker can deliver silent schedule nudges near
    /// the selected minute. The visible notification permission prompt is only
    /// requested when `requestAuthorizationIfNeeded` is true, which should be the
    /// explicit scheduled-export setup path.
    @MainActor
    func registerForRemoteNotificationsIfNeeded(requestAuthorizationIfNeeded: Bool = false) async {
        let center = UNUserNotificationCenter.current()
        let settings = await center.notificationSettings()
        switch settings.authorizationStatus {
        case .notDetermined:
            if requestAuthorizationIfNeeded {
                do {
                    _ = try await center.requestAuthorization(options: [.alert, .badge, .sound])
                } catch {
                    logger.error("Notification authorization request failed: \(error.localizedDescription)")
                }
            } else {
                logger.info("Notification authorization deferred until scheduled-export setup")
            }
        case .denied:
            logger.info("Notifications denied — registering APNs for silent schedule nudges only")
        case .authorized, .provisional, .ephemeral:
            break
        @unknown default:
            break
        }

        UIApplication.shared.registerForRemoteNotifications()
    }

    func submitDeviceToken(_ token: Data) {
        let hex = token.map { String(format: "%02x", $0) }.joined()
        logger.info("APNs token captured: \(hex.prefix(8), privacy: .public)…")
        Task { await self.postRegisterDevice(apnsToken: hex) }
    }

    private func postRegisterDevice(apnsToken: String) async {
        let body = RemoteScheduleDeviceRegistrationPayload(
            userId: userId,
            platform: platformString,
            apnsToken: apnsToken,
            bundleId: bundleId
        )
        await post(label: "register", path: RemoteScheduleWorkerContract.deviceRegistrationPath) {
            try await remoteClient.registerDevice(body)
        }
    }

    // MARK: - Schedule sync

    func syncSchedule(_ schedule: AutomationSchedule, intervalHours: Int = 0) {
        let timezone = TimeZone.current.identifier
        Task { await self.postUpsertSchedule(schedule, timezone: timezone, intervalHours: intervalHours) }
    }

    private func postUpsertSchedule(_ schedule: AutomationSchedule, timezone: String, intervalHours: Int) async {
        var remoteSchedule = schedule
        remoteSchedule.timeZoneIdentifier = timezone

        // Interval schedules need a wire field the ExportKit payload type cannot
        // express yet, so the body is mirrored app-side (identical camelCase
        // contract plus `intervalMinutes`) and posted with the same retry policy.
        if intervalHours > 0 {
            let body = IntervalScheduleUpsertBody(
                userId: userId,
                timezone: timezone,
                schedule: .init(
                    isEnabled: remoteSchedule.isEnabled,
                    hour: remoteSchedule.preferredHour,
                    minute: remoteSchedule.preferredMinute,
                    intervalMinutes: intervalHours * 60
                ),
                platform: platformString,
                bundleId: bundleId
            )
            await post(label: "schedule", path: RemoteScheduleWorkerContract.scheduleUpsertPath) {
                try await self.postIntervalScheduleUpsert(body)
            }
            return
        }

        let body = RemoteScheduleUpsertPayload(
            userId: userId,
            timezone: timezone,
            schedule: RemoteSchedulePayload(schedule: remoteSchedule),
            platform: platformString,
            bundleId: bundleId
        )
        await post(label: "schedule", path: RemoteScheduleWorkerContract.scheduleUpsertPath) {
            try await remoteClient.upsertSchedule(body)
        }
    }

    /// Wire-compatible mirror of ExportKit's `RemoteScheduleUpsertPayload` that
    /// can express interval schedules (`frequency: "interval"`).
    private struct IntervalScheduleUpsertBody: Encodable {
        struct IntervalSchedule: Encodable {
            let isEnabled: Bool
            let frequency = "interval"
            let hour: Int
            let minute: Int
            let weekday: Int? = nil
            let intervalMinutes: Int
        }

        let userId: String
        let timezone: String
        let schedule: IntervalSchedule
        let platform: String?
        let bundleId: String?
    }

    private func postIntervalScheduleUpsert(_ body: IntervalScheduleUpsertBody) async throws {
        var request = URLRequest(url: baseURL.appendingPathComponent(RemoteScheduleWorkerContract.scheduleUpsertPath))
        request.httpMethod = "POST"
        request.setValue("application/json", forHTTPHeaderField: "Content-Type")
        request.httpBody = try JSONEncoder().encode(body)

        for attempt in 1...max(1, upsertRetryPolicy.maxAttempts) {
            do {
                let (_, response) = try await session.data(for: request)
                guard let http = response as? HTTPURLResponse else {
                    throw RemoteScheduleClientError.invalidHTTPResponse
                }
                guard (200..<300).contains(http.statusCode) else {
                    throw RemoteScheduleClientError.unsuccessfulStatusCode(http.statusCode)
                }
                return
            } catch {
                guard attempt < upsertRetryPolicy.maxAttempts, upsertRetryPolicy.shouldRetry(error) else {
                    throw error
                }
                try await Task.sleep(nanoseconds: upsertRetryPolicy.retryDelayNanoseconds)
            }
        }
    }

    private func post(label: String, path: String, operation: () async throws -> Void) async {
        do {
            try await operation()
            logger.info("POST \(path, privacy: .public) ok")
        } catch RemoteScheduleClientError.unsuccessfulStatusCode(let statusCode) {
            logger.error("POST \(path, privacy: .public) failed: HTTP \(statusCode)")
        } catch {
            logger.error("POST \(path, privacy: .public) \(label, privacy: .public) error: \(error.localizedDescription)")
        }
    }

    // MARK: - Keychain storage

    private func readKeychainString(account: String) -> String? {
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.keychainService,
            kSecAttrAccount as String: account,
            kSecReturnData as String: true,
            kSecMatchLimit as String: kSecMatchLimitOne,
        ]
        var result: AnyObject?
        guard SecItemCopyMatching(query as CFDictionary, &result) == errSecSuccess,
              let data = result as? Data,
              let str = String(data: data, encoding: .utf8) else {
            return nil
        }
        return str
    }

    private func writeKeychainString(account: String, value: String) {
        guard let data = value.data(using: .utf8) else { return }
        let query: [String: Any] = [
            kSecClass as String: kSecClassGenericPassword,
            kSecAttrService as String: Self.keychainService,
            kSecAttrAccount as String: account,
        ]
        let attrs: [String: Any] = [kSecValueData as String: data]
        if SecItemUpdate(query as CFDictionary, attrs as CFDictionary) == errSecItemNotFound {
            var addQuery = query
            addQuery[kSecValueData as String] = data
            addQuery[kSecAttrAccessible as String] = kSecAttrAccessibleAfterFirstUnlock
            SecItemAdd(addQuery as CFDictionary, nil)
        }
    }
}
