import Foundation

/// Codable payloads used to move Apple Watch-recorded route data back to the
/// iPhone app over WatchConnectivity. These DTOs intentionally depend only on
/// Foundation so the watch target can record offline without linking SwiftData.
struct WatchLocationSyncPoint: Codable, Hashable, Identifiable {
    var id: UUID
    var sessionID: UUID
    var latitude: Double
    var longitude: Double
    var timestamp: Date
    var altitude: Double?
    var speed: Double?
    var horizontalAccuracy: Double
}

struct WatchLocationSyncSession: Codable, Hashable, Identifiable {
    var id: UUID
    var startedAt: Date
    var endedAt: Date?
}

struct WatchLocationSyncVisit: Codable, Hashable, Identifiable {
    var id: UUID
    var latitude: Double
    var longitude: Double
    var arrivedAt: Date
    var departedAt: Date?
    var horizontalAccuracy: Double
}

struct WatchLocationSyncPayload: Codable {
    static let currentVersion = 1

    var version: Int
    var deviceID: UUID
    var sequence: Int
    var generatedAt: Date
    var isTrackingEnabled: Bool
    var activeSessionID: UUID?
    var sessions: [WatchLocationSyncSession]
    var points: [WatchLocationSyncPoint]
    var visits: [WatchLocationSyncVisit]

    init(
        version: Int = WatchLocationSyncPayload.currentVersion,
        deviceID: UUID,
        sequence: Int,
        generatedAt: Date = Date(),
        isTrackingEnabled: Bool,
        activeSessionID: UUID?,
        sessions: [WatchLocationSyncSession],
        points: [WatchLocationSyncPoint],
        visits: [WatchLocationSyncVisit] = []
    ) {
        self.version = version
        self.deviceID = deviceID
        self.sequence = sequence
        self.generatedAt = generatedAt
        self.isTrackingEnabled = isTrackingEnabled
        self.activeSessionID = activeSessionID
        self.sessions = sessions
        self.points = points
        self.visits = visits
    }

    private enum CodingKeys: String, CodingKey {
        case version
        case deviceID
        case sequence
        case generatedAt
        case isTrackingEnabled
        case activeSessionID
        case sessions
        case points
        case visits
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        version = try container.decode(Int.self, forKey: .version)
        deviceID = try container.decode(UUID.self, forKey: .deviceID)
        sequence = try container.decode(Int.self, forKey: .sequence)
        generatedAt = try container.decode(Date.self, forKey: .generatedAt)
        isTrackingEnabled = try container.decode(Bool.self, forKey: .isTrackingEnabled)
        activeSessionID = try container.decodeIfPresent(UUID.self, forKey: .activeSessionID)
        sessions = try container.decode([WatchLocationSyncSession].self, forKey: .sessions)
        points = try container.decode([WatchLocationSyncPoint].self, forKey: .points)
        visits = try container.decodeIfPresent([WatchLocationSyncVisit].self, forKey: .visits) ?? []
    }
}

struct WatchLocationSyncAck: Codable {
    var deviceID: UUID
    var sequence: Int
    var importedSessionCount: Int
    var importedPointCount: Int
    var importedVisitCount: Int
    var importedAt: Date
    var succeeded: Bool
    var errorMessage: String?

    init(
        deviceID: UUID,
        sequence: Int,
        importedSessionCount: Int = 0,
        importedPointCount: Int = 0,
        importedVisitCount: Int = 0,
        importedAt: Date = Date(),
        succeeded: Bool = true,
        errorMessage: String? = nil
    ) {
        self.deviceID = deviceID
        self.sequence = sequence
        self.importedSessionCount = importedSessionCount
        self.importedPointCount = importedPointCount
        self.importedVisitCount = importedVisitCount
        self.importedAt = importedAt
        self.succeeded = succeeded
        self.errorMessage = errorMessage
    }

    private enum CodingKeys: String, CodingKey {
        case deviceID
        case sequence
        case importedSessionCount
        case importedPointCount
        case importedVisitCount
        case importedAt
        case succeeded
        case errorMessage
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        deviceID = try container.decode(UUID.self, forKey: .deviceID)
        sequence = try container.decode(Int.self, forKey: .sequence)
        importedSessionCount = try container.decodeIfPresent(Int.self, forKey: .importedSessionCount) ?? 0
        importedPointCount = try container.decodeIfPresent(Int.self, forKey: .importedPointCount) ?? 0
        importedVisitCount = try container.decodeIfPresent(Int.self, forKey: .importedVisitCount) ?? 0
        importedAt = try container.decode(Date.self, forKey: .importedAt)
        succeeded = try container.decode(Bool.self, forKey: .succeeded)
        errorMessage = try container.decodeIfPresent(String.self, forKey: .errorMessage)
    }
}

enum WatchLocationSyncTransport {
    static let payloadUserInfoKey = "com.bontecou.isome.watchLocationPayload"
    static let ackUserInfoKey = "com.bontecou.isome.watchLocationAck"
    static let markRequestUserInfoKey = "com.bontecou.isome.watchMarkPlace"
    static let markAckUserInfoKey = "com.bontecou.isome.watchMarkPlaceAck"
    /// iPhone → watch message asking it to send waiting points now; the reply carries the waiting count.
    static let syncRequestMessageKey = "com.bontecou.isome.watchSyncRequest"
    static let pendingPointCountReplyKey = "com.bontecou.isome.watchPendingPointCount"
}

/// "Mark" on Apple Watch: asks the iPhone to check in at the watch's current place.
/// Sent with `transferUserInfo`, so it stays queued until the iPhone receives it.
struct WatchMarkPlaceRequest: Codable, Hashable, Identifiable {
    var id: UUID
    var requestedAt: Date
    /// The watch's latest accurate fix, when it has one; otherwise the iPhone uses its own.
    var latitude: Double?
    var longitude: Double?
    var horizontalAccuracy: Double?
}

struct WatchMarkPlaceAck: Codable, Hashable {
    var requestID: UUID
    var succeeded: Bool
    var placeName: String?
    var errorMessage: String?
}

/// A Lamport revision avoids depending on synchronized phone/watch wall clocks.
/// Concurrent stops beat starts; otherwise origin breaks ties deterministically.
struct SharedRecordingIntent: Codable, Equatable {
    var revision: Int64
    var origin: UUID
    var sessionID: UUID
    var startedAt: Date
    var changedAt: Date
    var isRecording: Bool

    func supersedes(_ other: Self) -> Bool {
        if revision != other.revision { return revision > other.revision }
        if isRecording != other.isRecording { return !isRecording }
        return origin.uuidString > other.origin.uuidString
    }
}

#if canImport(WatchConnectivity)
import WatchConnectivity
import Combine

/// Persisted control-plane state, separate from the existing durable route queue.
/// The WCSession delegates forward these dictionaries; this never owns the session.
@MainActor
final class SharedRecordingControl: ObservableObject {
    static let shared = SharedRecordingControl(defaults: .standard)
    nonisolated static let messageKey = "sharedRecordingIntent.v1"
    nonisolated static let ackKey = "sharedRecordingAck.v1"
    private static let storageKey = "sharedRecordingControl.v1"

    private struct State: Codable {
        var origin = UUID()
        var intent: SharedRecordingIntent?
        var applied: SharedRecordingIntent?
        var acknowledged: SharedRecordingIntent?
    }

    private let defaults: UserDefaults
    private let enablesTransport: Bool
    @Published private var state: State
    /// Return false when permission/access/store readiness prevents applying a command.
    var apply: ((SharedRecordingIntent) -> Bool)?
    private(set) var isApplying = false
    var intent: SharedRecordingIntent? { state.intent }
    var isWaitingForPeer: Bool { state.intent != nil && state.acknowledged != state.intent }
    var statusDescription: String {
        guard let intent = state.intent else { return String(localized: "No shared recording yet") }
        if state.applied != intent {
            return String(localized: "Open this app and check location permission and recording access")
        }
        if isWaitingForPeer { return String(localized: "Waiting for the other device") }
        return String(localized: "Recording controls synced")
    }

    init(defaults: UserDefaults, enablesTransport: Bool = true) {
        self.defaults = defaults
        self.enablesTransport = enablesTransport
        state = defaults.data(forKey: Self.storageKey)
            .flatMap { try? JSONDecoder().decode(State.self, from: $0) } ?? State()
    }

    private func save() {
        if let data = try? JSONEncoder().encode(state) {
            defaults.set(data, forKey: Self.storageKey)
        }
    }

    func publish(isRecording: Bool, sessionID: UUID, startedAt: Date, now: Date = Date()) {
        guard !isApplying else { return }
        let next = SharedRecordingIntent(revision: (state.intent?.revision ?? 0) + 1,
            origin: state.origin, sessionID: sessionID, startedAt: startedAt,
            changedAt: now, isRecording: isRecording)
        state.intent = next
        state.applied = next
        state.acknowledged = nil
        save()
        flush()
    }

    /// Called on activation/reachability and after local prerequisites change.
    func flush() {
        retryApplication()
        guard enablesTransport, WCSession.isSupported(), WCSession.default.activationState == .activated,
              let intent = state.intent, state.acknowledged != intent,
              let data = try? JSONEncoder().encode(intent) else { return }
        let session = WCSession.default
        #if os(iOS)
        guard session.isPaired, session.isWatchAppInstalled else { return }
        #endif
        let message: [String: Any] = [Self.messageKey: data]
        // Avoid enqueuing identical commands on every foreground/reachability event.
        if !session.outstandingUserInfoTransfers.contains(where: {
            ($0.userInfo[Self.messageKey] as? Data) == data
        }) {
            session.transferUserInfo(message)
        }
        if session.isReachable {
            session.sendMessage(message, replyHandler: { reply in
                Task { @MainActor in self.receive(reply) }
            }, errorHandler: { _ in /* Durable transfer remains queued. */ })
        }
    }

    @discardableResult
    func receive(_ message: [String: Any]) -> [String: Any] {
        if let data = message[Self.ackKey] as? Data,
           let ack = try? JSONDecoder().decode(SharedRecordingIntent.self, from: data),
           ack == state.intent {
            state.acknowledged = ack
            save()
        }
        guard let data = message[Self.messageKey] as? Data,
              let incoming = try? JSONDecoder().decode(SharedRecordingIntent.self, from: data),
              incoming.revision > 0, incoming.revision < Int64.max else { return [:] }
        if state.intent == nil || incoming.supersedes(state.intent!) {
            state.intent = incoming
            state.acknowledged = nil
            save()
        }
        retryApplication()
        // Only acknowledge exactly the command actually applied, not mere receipt.
        if state.applied == incoming {
            let ack: [String: Any] = [Self.ackKey: data]
            if enablesTransport, WCSession.isSupported(), WCSession.default.activationState == .activated {
                WCSession.default.transferUserInfo(ack)
            }
            return ack
        }
        // A stale sender must learn the winning intent (including an offline stop).
        if state.intent != incoming { flush() }
        return [:]
    }

    func retryApplication() {
        guard !isApplying, let intent = state.intent, state.applied != intent,
              let apply else { return }
        isApplying = true
        let succeeded = apply(intent)
        isApplying = false
        if succeeded {
            state.applied = intent
            save()
        }
    }
}
#endif
