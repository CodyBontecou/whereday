import Foundation
import CoreMotion

/// Timestamped evidence, not elapsed app uptime. Intervals may only be joined when
/// contiguous, high confidence, and of the same unambiguous activity.
struct ActivityEvidence {
    struct Interval {
        var start: Date
        var end: Date
        var activity: AutoStartPolicy.Activity
        var confidence: AutoStartPolicy.Confidence
    }
    var intervals: [Interval]
    var queriedAt: Date

    func signal(at now: Date) -> AutoStartPolicy.Signal? {
        guard queriedAt <= now, now.timeIntervalSince(queriedAt) <= 30,
              let last = intervals.last, last.end == queriedAt else { return nil }
        var previousEnd: Date?
        for interval in intervals {
            guard interval.start <= interval.end, interval.end <= queriedAt,
                  previousEnd.map({ interval.start >= $0 }) ?? true else { return nil }
            previousEnd = interval.end
        }
        var start = last.start
        if last.confidence == .high {
            for interval in intervals.dropLast().reversed() {
                guard interval.end == start, interval.activity == last.activity,
                      interval.confidence == .high else { break }
                start = interval.start
            }
        }
        return .init(activity: last.activity, confidence: last.confidence,
                     duration: last.end.timeIntervalSince(start))
    }
}

enum MotionDetectionStatus: Equatable {
    case unavailable, notDetermined, denied, authorized
}

@MainActor
protocol ActivityDetecting: AnyObject {
    var status: MotionDetectionStatus { get }
    func start(_ receive: @escaping (ActivityEvidence?) -> Void)
    func queryRecentActivity()
    func stop()
}

/// CoreMotion only: no tracking decisions. Constructing this adapter does not ask
/// for permission. `start` is called only after persisted explicit opt-in + Always.
@MainActor
final class ActivityDetectionManager: ActivityDetecting {
    private let manager = CMMotionActivityManager()
    private var receive: ((ActivityEvidence?) -> Void)?
    private var timer: Timer?
    private var generation = UUID()
    private var queryID = UUID()
    private var queryPending = false
    private var lastQueryAt: Date?
    private let now: () -> Date

    init(now: @escaping () -> Date = Date.init) { self.now = now }

    deinit {
        timer?.invalidate()
        manager.stopActivityUpdates()
    }

    var status: MotionDetectionStatus {
        guard CMMotionActivityManager.isActivityAvailable() else { return .unavailable }
        switch CMMotionActivityManager.authorizationStatus() {
        case .authorized: return .authorized
        case .notDetermined: return .notDetermined
        case .denied, .restricted: return .denied
        @unknown default: return .denied
        }
    }

    func start(_ receive: @escaping (ActivityEvidence?) -> Void) {
        guard self.receive == nil else { return }
        guard status == .authorized || status == .notDetermined else { return }
        self.receive = receive
        let token = generation
        manager.startActivityUpdates(to: .main) { [weak self] _ in
            Task { @MainActor [weak self] in
                guard let self, self.generation == token else { return }
                self.queryRecentActivity()
            }
        }
        // A stream emits transitions, not heartbeats. Query covered history to
        // establish sustained motion; a timer alone is never movement evidence.
        timer = Timer.scheduledTimer(withTimeInterval: 30, repeats: true) { [weak self] _ in
            Task { @MainActor [weak self] in self?.queryRecentActivity() }
        }
        queryRecentActivity()
    }

    func stop() {
        generation = UUID()
        queryID = UUID()
        receive = nil
        queryPending = false
        lastQueryAt = nil
        timer?.invalidate()
        timer = nil
        manager.stopActivityUpdates()
        // CoreMotion has no cancel-query method; generation rejects completions.
    }

    func queryRecentActivity() {
        guard receive != nil else { return }
        guard status == .authorized || status == .notDetermined else {
            receive?(nil)
            return
        }
        let end = now()
        if let lastQueryAt, end < lastQueryAt {
            // Invalidate in-flight history after clock reversal, do not reuse it.
            queryID = UUID(); queryPending = false; self.lastQueryAt = nil
            receive?(nil)
            return
        }
        guard !queryPending else { return }
        if let lastQueryAt, end.timeIntervalSince(lastQueryAt) < 15 { return }
        lastQueryAt = end
        queryPending = true
        queryID = UUID()
        let id = queryID, token = generation
        let start = end.addingTimeInterval(-15 * 60)
        manager.queryActivityStarting(from: start, to: end, to: .main) { [weak self] activities, error in
            // Normalize Apple objects on their delivery queue, crossing actors with values only.
            let evidence = error == nil ? Self.evidence(activities ?? [], from: start, to: end) : nil
            Task { @MainActor [weak self] in
                guard let self, self.generation == token, self.queryID == id else { return }
                self.queryPending = false
                self.receive?(evidence)
            }
        }
    }

    nonisolated private static func evidence(_ activities: [CMMotionActivity], from start: Date,
                                            to end: Date) -> ActivityEvidence? {
        let sorted = activities.sorted { $0.startDate < $1.startDate }
        guard !sorted.isEmpty else { return nil }
        var intervals: [ActivityEvidence.Interval] = []
        for (index, activity) in sorted.enumerated() {
            let next = index + 1 < sorted.count ? sorted[index + 1].startDate : end
            let lower = max(start, activity.startDate)
            guard lower < next, next <= end else { return nil }
            let flags = [activity.automotive, activity.walking || activity.running,
                         activity.cycling, activity.stationary]
            let kind: AutoStartPolicy.Activity
            if flags.filter({ $0 }).count != 1 { kind = .unknown }
            else if activity.automotive { kind = .driving }
            else if activity.walking || activity.running { kind = .walkingRunning }
            else if activity.cycling { kind = .cycling }
            else { kind = .stationary }
            let confidence: AutoStartPolicy.Confidence
            switch activity.confidence {
            case .high: confidence = .high
            case .medium: confidence = .medium
            default: confidence = .low
            }
            intervals.append(.init(start: lower, end: next, activity: kind, confidence: confidence))
        }
        return ActivityEvidence(intervals: intervals, queriedAt: end)
    }
}
