import CoreLocation
import Foundation

// Watch ↔ iPhone recording hand-off and overlap merge (#173).
//
// Hand-off saves Watch battery: while a reachable iPhone holds a fresh
// recording lease, the Watch stands by (low-power fixes kept in a short
// rolling buffer, nothing queued). When the lease lapses or the phone goes
// out of range, the Watch records again and first promotes the buffered fixes
// taken after the phone's last attested coverage, so a phone that dies
// mid-recording leaves no gap.
//
// Merge is the safety net for overlap that slips through (connectivity flaps,
// stale leases): on import the iPhone folds Watch points that fall inside its
// own recorded coverage into what it already stored, and Watch visits into
// the matching iPhone visit. Every folded item is counted and logged, never
// dropped silently. These types depend only on Foundation/CoreLocation so the
// Watch target and unit tests can use them without SwiftData.

/// Which device collects fixes for a recording, as shown in both apps.
enum RecordingDeviceRole: String, Codable, Equatable {
    /// This Apple Watch collects and queues its own fixes.
    case recording
    /// The iPhone records; the Watch keeps only a low-power standby buffer.
    case standingBy
    /// Not recording.
    case idle
}

/// iPhone → Watch: "I am recording, and my coverage runs until `coveredUntil`."
/// The Watch judges freshness by its own receipt time, so no clock sync is needed.
struct PhoneRecordingLease: Codable, Equatable {
    var isRecording: Bool
    var sessionID: UUID?
    /// Phone time up to which the phone attests it recorded.
    var coveredUntil: Date
}

/// Watch → iPhone lease request; carries the Watch's role for the phone's UI.
struct WatchHandoffReport: Codable, Equatable {
    var deviceID: UUID
    var role: RecordingDeviceRole
    var reportedAt: Date
}

enum RecordingHandoffTransport {
    static let leaseRequestKey = "com.bontecou.isome.recordingLeaseRequest.v1"
    static let leaseReplyKey = "com.bontecou.isome.recordingLease.v1"
}

struct RecordingHandoffPolicy {
    /// A lease older than this (by Watch receipt time) no longer lets the Watch stand by.
    var leaseLifetime: TimeInterval = 150
    /// Ask for a new lease this often while recording.
    var refreshInterval: TimeInterval = 60
    /// Promote standby fixes from slightly before the phone's coverage ended;
    /// the iPhone's merge folds the small overlap.
    var promotionOverlap: TimeInterval = 30

    func role(isRecording: Bool, isPhoneReachable: Bool, lease: PhoneRecordingLease?,
              leaseReceivedAt: Date?, now: Date) -> RecordingDeviceRole {
        guard isRecording else { return .idle }
        guard isPhoneReachable, let lease, lease.isRecording, let leaseReceivedAt else { return .recording }
        let age = now.timeIntervalSince(leaseReceivedAt)
        // A receipt time in the future means the Watch clock moved back; don't trust it.
        guard age >= -5, age <= leaseLifetime else { return .recording }
        return .standingBy
    }

    func needsLeaseRefresh(leaseReceivedAt: Date?, now: Date) -> Bool {
        guard let leaseReceivedAt else { return true }
        let age = now.timeIntervalSince(leaseReceivedAt)
        return age < 0 || age >= refreshInterval
    }

    /// Standby fixes the Watch must keep when it takes over: everything after
    /// the phone's last attested coverage (minus a small overlap).
    func promotableStandbyPoints(_ buffer: [WatchLocationSyncPoint],
                                 phoneCoveredUntil: Date?) -> [WatchLocationSyncPoint] {
        let sorted = buffer.sorted { $0.timestamp < $1.timestamp }
        guard let phoneCoveredUntil else { return sorted }
        let cutoff = phoneCoveredUntil.addingTimeInterval(-promotionOverlap)
        return sorted.filter { $0.timestamp > cutoff }
    }
}

/// Rolling buffer of low-power fixes the Watch takes while standing by.
struct WatchStandbyBuffer: Codable, Equatable {
    static let window: TimeInterval = 30 * 60
    static let maxCount = 600

    private(set) var points: [WatchLocationSyncPoint] = []

    mutating func append(_ point: WatchLocationSyncPoint) {
        points.append(point)
        let cutoff = point.timestamp.addingTimeInterval(-Self.window)
        points.removeAll { $0.timestamp < cutoff }
        if points.count > Self.maxCount { points.removeFirst(points.count - Self.maxCount) }
    }

    mutating func removeAll() { points = [] }
}

/// Spans of time this iPhone itself recorded. Extended by its own fixes and by
/// lease attestations; closed when recording stops or the process restarts, so
/// a phone that died never claims the time it was off.
struct RecordingCoverage: Codable, Equatable {
    struct Interval: Codable, Equatable {
        var start: Date
        var end: Date
    }

    /// Two attestations further apart than this are not bridged.
    static let maxBridgedGap: TimeInterval = 30 * 60
    static let retention: TimeInterval = 60 * 24 * 60 * 60

    private(set) var intervals: [Interval] = []
    private(set) var isOpen = false

    mutating func extend(to date: Date) {
        if isOpen, let last = intervals.last, date >= last.start,
           date.timeIntervalSince(last.end) <= Self.maxBridgedGap {
            intervals[intervals.count - 1].end = max(last.end, date)
            return
        }
        // A late, out-of-order fix inside a span already known adds nothing.
        if covers(date) { return }
        let interval = Interval(start: date, end: date)
        intervals.append(interval)
        intervals.sort { $0.start < $1.start }
        // Only the newest span can keep growing.
        isOpen = intervals.last == interval
    }

    /// The next attestation starts a new interval.
    mutating func close() { isOpen = false }

    mutating func prune(now: Date) {
        let cutoff = now.addingTimeInterval(-Self.retention)
        intervals.removeAll { $0.end < cutoff }
    }

    func covers(_ date: Date) -> Bool {
        intervals.contains { $0.start <= date && date <= $0.end }
    }
}

/// A stored visit as the merge sees it.
struct RecordingMergeVisit: Equatable {
    var id: UUID
    var latitude: Double
    var longitude: Double
    var arrivedAt: Date
    var departedAt: Date?
    var isAutomatic: Bool
}

enum RecordingOverlapMerge {
    /// Same place, per the iPhone's own duplicate-visit rule.
    static let sameVisitDistance: CLLocationDistance = 150
    /// Look this far past a Watch visit for an iPhone visit that started late.
    static let arrivalTolerance: TimeInterval = 15 * 60

    /// Watch points whose moment the iPhone already recorded itself.
    /// Points outside the phone's coverage fill gaps and are kept.
    static func redundantPointIDs(_ points: [WatchLocationSyncPoint],
                                  coverage: RecordingCoverage) -> Set<UUID> {
        Set(points.filter { coverage.covers($0.timestamp) }.map(\.id))
    }

    /// The stored automatic visit a Watch visit duplicates: the closest one at
    /// the same place whose time overlaps it.
    static func duplicateVisitID(for visit: WatchLocationSyncVisit,
                                 among candidates: [RecordingMergeVisit], now: Date) -> UUID? {
        let location = CLLocation(latitude: visit.latitude, longitude: visit.longitude)
        let visitEnd = visit.departedAt ?? now
        return candidates
            .filter { $0.isAutomatic && $0.id != visit.id }
            .compactMap { candidate -> (UUID, CLLocationDistance)? in
                let distance = CLLocation(latitude: candidate.latitude, longitude: candidate.longitude)
                    .distance(from: location)
                guard distance <= sameVisitDistance else { return nil }
                let candidateEnd = candidate.departedAt ?? now
                // Strict overlap: back-to-back stays that only touch are two visits.
                let overlaps = candidate.arrivedAt < visitEnd && visit.arrivedAt < candidateEnd
                return overlaps ? (candidate.id, distance) : nil
            }
            .min { $0.1 < $1.1 }?
            .0
    }

    /// How a Watch visit may adjust the iPhone visit it duplicates: only at
    /// moments the iPhone did not record itself, so the phone's data wins
    /// where it has any and the Watch's fills what the phone missed.
    static func mergedTimes(target: RecordingMergeVisit, watch: WatchLocationSyncVisit,
                            coverage: RecordingCoverage) -> (arrivedAt: Date, departedAt: Date?) {
        var arrivedAt = target.arrivedAt
        if watch.arrivedAt < arrivedAt, !coverage.covers(watch.arrivedAt) {
            arrivedAt = watch.arrivedAt
        }
        var departedAt = target.departedAt
        if let watchDeparture = watch.departedAt, !coverage.covers(watchDeparture) {
            if let current = departedAt {
                departedAt = max(current, watchDeparture)
            } else if watchDeparture >= arrivedAt {
                departedAt = watchDeparture
            }
        }
        return (arrivedAt, departedAt)
    }
}
