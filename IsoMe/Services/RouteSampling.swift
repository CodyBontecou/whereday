import Foundation
import CoreLocation

/// How route points are chosen while recording. Visits are detected separately by iOS
/// (`startMonitoringVisits`) and are not affected by any of this.
enum RouteSamplingMode: String, CaseIterable, Identifiable, Sendable {
    /// A point each time you move the distance filter. Core Location does the filtering.
    case distance
    /// A point every interval, moving or not.
    case time
    /// A point every interval, but only once you've moved the distance filter since the last one.
    case distanceAndTime

    var id: Self { self }

    var usesTimeInterval: Bool { self != .distance }
    var usesDistanceFilter: Bool { self != .time }

    var title: String {
        switch self {
        case .distance: String(localized: "By distance")
        case .time: String(localized: "By time")
        case .distanceAndTime: String(localized: "Distance + time")
        }
    }
}

/// How hard Core Location should work between route samples.
enum RoutePowerLevel: Equatable, Sendable {
    /// GPS on: the next sample is due, or the interval is too short to be worth powering down.
    case precise
    /// Wi-Fi and cell positioning only. Keeps location updates (and so background recording)
    /// alive while letting iOS power the GPS down between samples.
    case low

    var desiredAccuracy: CLLocationAccuracy {
        switch self {
        case .precise: kCLLocationAccuracyBest
        case .low: kCLLocationAccuracyHundredMeters
        }
    }
}

struct RouteSamplingPolicy: Equatable, Sendable {
    var mode: RouteSamplingMode
    var distanceFilter: CLLocationDistance
    var interval: TimeInterval

    static let intervalOptions: [TimeInterval] = [10, 30, 60, 120, 300]
    static let defaultInterval: TimeInterval = 120
    static let defaultDistanceFilter: CLLocationDistance = 5

    /// Shorter intervals keep the GPS on: re-acquiring a fix takes several seconds,
    /// so dropping to low power between 10 s or 30 s samples saves little and costs accuracy.
    static let lowPowerMinimumInterval: TimeInterval = 60
    /// How long before a sample is due to bring the GPS back, so the fix has settled.
    static let warmUpLead: TimeInterval = 15
    /// A due fix at least this precise is kept straight away.
    static let preciseAccuracy: CLLocationAccuracy = 25
    /// How long past due to wait for a precise fix before accepting any usable one.
    static let precisionGrace: TimeInterval = 20
    /// Fixes arriving this little before the interval still count, so 1 Hz delivery
    /// doesn't push every sample a full second late.
    static let timingTolerance: TimeInterval = 0.5
    /// Matches the app's existing cut-off for saving a point at all.
    static let maximumUsableAccuracy: CLLocationAccuracy = 100

    init(mode: RouteSamplingMode, distanceFilter: CLLocationDistance, interval: TimeInterval) {
        self.mode = mode
        self.distanceFilter = max(0, distanceFilter)
        self.interval = max(1, interval)
    }

    /// The distance filter handed to Core Location for this mode.
    var coreLocationDistanceFilter: CLLocationDistance {
        mode == .time || distanceFilter <= 0 ? kCLDistanceFilterNone : distanceFilter
    }

    var usesLowPowerBetweenSamples: Bool {
        mode.usesTimeInterval && interval >= Self.lowPowerMinimumInterval
    }

    static func intervalLabel(_ interval: TimeInterval) -> String {
        let seconds = Int(interval.rounded())
        if seconds < 60 {
            return String(localized: "\(seconds) s")
        }
        if seconds % 60 == 0 {
            return String(localized: "\(seconds / 60) min")
        }
        return String(localized: "\(seconds / 60) min \(seconds % 60) s")
    }

    /// e.g. "Every 5 m", "Every 2 min", "Every 2 min once 25 m away".
    var summary: String {
        let distance = Measurement(value: distanceFilter, unit: UnitLength.meters)
            .formatted(.measurement(width: .abbreviated, usage: .asProvided))
        switch mode {
        case .distance:
            return String(localized: "Every \(distance)")
        case .time:
            return String(localized: "Every \(Self.intervalLabel(interval))")
        case .distanceAndTime:
            return String(localized: "Every \(Self.intervalLabel(interval)) once \(distance) away")
        }
    }
}

/// Decides which delivered fixes become route points, and how much power Core Location
/// should use until the next one. Pure value type so the timing rules can be unit tested.
struct RouteSampler {
    var policy: RouteSamplingPolicy {
        didSet {
            guard policy != oldValue else { return }
            // Shortening the interval brings the next sample forward; lengthening pushes it back.
            if let lastKept {
                nextSampleDue = lastKept.timestamp.addingTimeInterval(policy.interval)
            }
            if !policy.usesLowPowerBetweenSamples { powerLevel = .precise }
        }
    }

    private(set) var lastKept: CLLocation?
    /// When the next time-based sample may be kept. nil until the first fix arrives.
    private(set) var nextSampleDue: Date?
    private(set) var powerLevel: RoutePowerLevel = .precise
    /// Latest usable fix seen, kept or not, used to notice movement while in low power.
    private var latestUsable: CLLocation?
    /// First fix that was due (and far enough, in distance + time) but not precise enough.
    /// The precision grace period runs from here, so a rough Wi-Fi fix at the moment you
    /// start moving waits briefly for the GPS instead of becoming the point.
    private var eligibleSince: Date?

    init(policy: RouteSamplingPolicy) {
        self.policy = policy
    }

    mutating func reset() {
        lastKept = nil
        nextSampleDue = nil
        latestUsable = nil
        eligibleSince = nil
        powerLevel = .precise
    }

    /// Returns the fixes to save as route points, in delivery order, and updates `powerLevel`.
    mutating func process(_ locations: [CLLocation]) -> [CLLocation] {
        guard policy.mode.usesTimeInterval else {
            // Distance mode: Core Location's distance filter already chose these. Pass them all
            // through, unfiltered, exactly as before time sampling existed.
            if let last = locations.last { lastKept = last }
            powerLevel = .precise
            return locations
        }

        var kept: [CLLocation] = []
        for location in locations where shouldKeep(location) {
            kept.append(location)
        }
        if let latest = locations.last {
            powerLevel = recommendedPowerLevel(at: latest.timestamp)
        }
        return kept
    }

    /// Re-evaluates power without a new fix, e.g. when a wake-up timer fires or settings change.
    mutating func refreshPowerLevel(at date: Date) {
        powerLevel = recommendedPowerLevel(at: date)
    }

    /// The power level that should apply at `date`.
    func recommendedPowerLevel(at date: Date) -> RoutePowerLevel {
        guard policy.usesLowPowerBetweenSamples,
              lastKept != nil,
              let due = nextSampleDue else { return .precise }
        guard date >= due.addingTimeInterval(-RouteSamplingPolicy.warmUpLead) else { return .low }
        // Due (or nearly). In distance + time, stay low while parked: only spend GPS once
        // even a rough fix suggests we've moved far enough to need a point.
        if policy.mode == .distanceAndTime, !hasPlausiblyMoved {
            return .low
        }
        return .precise
    }

    /// When the GPS should come back up, for a wake-up timer. nil while it's already up
    /// or while it is waiting on movement rather than time.
    var nextPowerUpDate: Date? {
        guard powerLevel == .low, let due = nextSampleDue else { return nil }
        let wake = due.addingTimeInterval(-RouteSamplingPolicy.warmUpLead)
        if policy.mode == .distanceAndTime, let latestUsable, wake <= latestUsable.timestamp {
            return nil
        }
        return wake
    }

    private var hasPlausiblyMoved: Bool {
        guard let lastKept, let latestUsable else { return true }
        let distance = latestUsable.distance(from: lastKept)
        // A rough fix is only "moved" once it's clear of its own uncertainty.
        let slack = max(0, latestUsable.horizontalAccuracy - lastKept.horizontalAccuracy)
        return distance >= policy.distanceFilter + slack * 0.5
    }

    private mutating func shouldKeep(_ location: CLLocation) -> Bool {
        let accuracy = location.horizontalAccuracy
        guard accuracy >= 0, accuracy <= RouteSamplingPolicy.maximumUsableAccuracy else { return false }
        latestUsable = location

        let due = nextSampleDue ?? location.timestamp
        if nextSampleDue == nil { nextSampleDue = due }

        guard location.timestamp.timeIntervalSince(due) >= -RouteSamplingPolicy.timingTolerance else { return false }

        if policy.mode == .distanceAndTime, let lastKept,
           location.distance(from: lastKept) < policy.distanceFilter {
            return false
        }

        let waitingSince = eligibleSince ?? location.timestamp
        if eligibleSince == nil { eligibleSince = waitingSince }
        guard accuracy <= RouteSamplingPolicy.preciseAccuracy
                || location.timestamp.timeIntervalSince(waitingSince) >= RouteSamplingPolicy.precisionGrace else { return false }

        lastKept = location
        eligibleSince = nil
        nextSampleDue = location.timestamp.addingTimeInterval(policy.interval)
        return true
    }
}

extension RecordingSession {
    /// The sampling settings this session was recorded with, when known. Kept out of the model file so
    /// RecordingSession.swift stays self-contained for the Android reference-fixture harnesses.
    var routeSamplingPolicy: RouteSamplingPolicy? {
        guard let distanceFilterMeters else { return nil }
        let mode = routeSamplingModeRawValue.flatMap(RouteSamplingMode.init(rawValue:)) ?? .distance
        return RouteSamplingPolicy(
            mode: mode,
            distanceFilter: distanceFilterMeters,
            interval: routeSampleIntervalSeconds ?? RouteSamplingPolicy.defaultInterval
        )
    }
}
