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
    // Keep dictionary and interactive messages small. Larger route batches use
    // WCSession's durable file transport without changing their wire format.
    static let inlinePayloadByteLimit = 48 * 1024
    static let payloadFileMetadataKey = "com.bontecou.isome.watchLocationFile"
    static let payloadFileNameKey = "com.bontecou.isome.watchLocationFileName"
    static func inlinePayload(_ data: Data) -> Data? {
        data.count <= inlinePayloadByteLimit ? data : nil
    }

    static func writePayloadFile(_ data: Data, directory: URL) throws -> URL {
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        let url = directory.appendingPathComponent("route-\(UUID().uuidString).json")
        try data.write(to: url, options: .atomic)
        return url
    }

    /// Read before the WCSession delegate returns: its received file URL is temporary.
    static func readPayloadFile(_ url: URL, metadata: [String: Any]?) throws -> Data? {
        guard metadata?[payloadFileMetadataKey] as? Bool == true else { return nil }
        return try Data(contentsOf: url)
    }

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
