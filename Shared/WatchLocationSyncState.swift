import CoreLocation
import Foundation

struct WatchLocationSyncState: Codable {
    var deviceID: UUID
    var nextSequence: Int = 1
    var sessions: [WatchLocationSyncSession] = []
    var points: [WatchLocationSyncPoint] = []
    var visits: [WatchLocationSyncVisit] = []
    var activeSessionID: UUID?
    var pendingTransfers: [WatchLocationPendingTransfer] = []
    var lastTransferDate: Date?
    var lastAckedSequence: Int?
    private var visitRecordingVersion = 1

    /// Keep the open visit after acknowledging arrival so departure can follow.
    @discardableResult
    mutating func recordPoint(_ point: WatchLocationSyncPoint) -> Bool {
        points.append(point)
        if let index = visits.lastIndex(where: { $0.departedAt == nil }) {
            let visit = visits[index]
            guard point.timestamp >= visit.arrivedAt else { return false }
            let origin = CLLocation(latitude: visit.latitude, longitude: visit.longitude)
            let location = CLLocation(latitude: point.latitude, longitude: point.longitude)
            guard location.distance(from: origin) >= 100 else { return false }
            visits[index].departedAt = point.timestamp
        }
        visits.append(WatchLocationSyncVisit(
            id: UUID(), latitude: point.latitude, longitude: point.longitude,
            arrivedAt: point.timestamp, departedAt: nil,
            horizontalAccuracy: point.horizontalAccuracy
        ))
        return true
    }

    mutating func endVisit(at date: Date) {
        guard let index = visits.lastIndex(where: { $0.departedAt == nil }) else { return }
        visits[index].departedAt = max(visits[index].arrivedAt, date)
    }

    mutating func acknowledge(_ transfer: WatchLocationPendingTransfer) {
        points.removeAll { transfer.pointIDs.contains($0.id) }
        // An arrival acknowledgement must not discard a later departure.
        visits.removeAll { visit in
            visit.departedAt != nil && (transfer.visits ?? []).contains(visit)
        }
        let remainingSessionIDs = Set(points.map(\.sessionID))
        sessions.removeAll {
            transfer.sessionIDs.contains($0.id) && $0.endedAt != nil &&
            !remainingSessionIDs.contains($0.id) &&
            (transfer.endedSessionIDs ?? []).contains($0.id)
        }
        pendingTransfers.removeAll { $0.sequence == transfer.sequence }
        lastAckedSequence = max(lastAckedSequence ?? transfer.sequence, transfer.sequence)
    }

    init(deviceID: UUID) {
        self.deviceID = deviceID
    }

    private enum CodingKeys: String, CodingKey {
        case deviceID
        case nextSequence
        case sessions
        case points
        case visits
        case activeSessionID
        case pendingTransfers
        case lastTransferDate
        case lastAckedSequence
        case visitRecordingVersion
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        deviceID = try container.decode(UUID.self, forKey: .deviceID)
        nextSequence = try container.decodeIfPresent(Int.self, forKey: .nextSequence) ?? 1
        sessions = try container.decodeIfPresent([WatchLocationSyncSession].self, forKey: .sessions) ?? []
        points = try container.decodeIfPresent([WatchLocationSyncPoint].self, forKey: .points) ?? []
        visits = try container.decodeIfPresent([WatchLocationSyncVisit].self, forKey: .visits) ?? []
        activeSessionID = try container.decodeIfPresent(UUID.self, forKey: .activeSessionID)
        pendingTransfers = try container.decodeIfPresent([WatchLocationPendingTransfer].self, forKey: .pendingTransfers) ?? []
        lastTransferDate = try container.decodeIfPresent(Date.self, forKey: .lastTransferDate)
        lastAckedSequence = try container.decodeIfPresent(Int.self, forKey: .lastAckedSequence)
        let previousVersion = try container.decodeIfPresent(Int.self, forKey: .visitRecordingVersion) ?? 0
        if previousVersion == 0, visits.isEmpty, !points.isEmpty {
            // Older releases retained route points but never created visits.
            // Recover only still-queued points; already delivered history is
            // no longer available on the Watch.
            let recordedPoints = points.sorted { $0.timestamp < $1.timestamp }
            points = []
            for point in recordedPoints {
                recordPoint(point)
            }
            if activeSessionID == nil, let lastPoint = recordedPoints.last {
                endVisit(at: lastPoint.timestamp)
            }
        }
    }
}

struct WatchLocationPendingTransfer: Codable {
    var sequence: Int
    var sessionIDs: Set<UUID>
    var pointIDs: Set<UUID>
    var visitIDs: Set<UUID>
    var createdAt: Date
    // Missing in queues persisted by versions before visit syncing.
    var visits: [WatchLocationSyncVisit]?
    var endedSessionIDs: Set<UUID>?
}

extension WatchLocationPendingTransfer {
    private enum CodingKeys: String, CodingKey {
        case sequence, sessionIDs, pointIDs, visitIDs, createdAt, visits, endedSessionIDs
    }

    init(from decoder: Decoder) throws {
        let container = try decoder.container(keyedBy: CodingKeys.self)
        sequence = try container.decode(Int.self, forKey: .sequence)
        sessionIDs = try container.decodeIfPresent(Set<UUID>.self, forKey: .sessionIDs) ?? []
        pointIDs = try container.decodeIfPresent(Set<UUID>.self, forKey: .pointIDs) ?? []
        visitIDs = try container.decodeIfPresent(Set<UUID>.self, forKey: .visitIDs) ?? []
        createdAt = try container.decode(Date.self, forKey: .createdAt)
        visits = try container.decodeIfPresent([WatchLocationSyncVisit].self, forKey: .visits)
        endedSessionIDs = try container.decodeIfPresent(Set<UUID>.self, forKey: .endedSessionIDs)
    }
}
