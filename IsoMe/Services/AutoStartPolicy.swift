import Foundation

/// Pure decision layer: callers supply normalized evidence and perform any effects.
/// Location wake events alone are not proof of sustained movement in this first slice.
struct AutoStartPolicy {
    enum Activity: Equatable { case driving, walkingRunning, cycling, stationary, unknown }
    enum Confidence: Equatable { case low, medium, high }
    struct Signal {
        var activity: Activity
        var confidence: Confidence
        /// Continuous duration established by the signal producer, not time since app launch.
        var duration: TimeInterval
    }
    struct Context {
        var settings: SmartStartSettings
        var hasAlwaysLocation: Bool
        var hasMotionPermission: Bool
        var canTrack: Bool
        var isTracking: Bool
        var source: TrackingStartSource?
        var lastManualStopAt: Date?
        var lastAutoStartAt: Date?
        var now: Date
    }
    enum Reason: Equatable {
        case disabled, missingLocationPermission, missingMotionPermission, accessRequired
        case alreadyTracking, lowConfidence, insufficientMovement, activityDisabled
        case manualStopCooldown, restartCooldown, invalidSignal, manualSessionProtected
        case stationaryAutoStopDisabled, insufficientStationaryTime, noMovement
    }
    enum Decision: Equatable {
        case start(TrackingStartSource)
        case stop
        case suppress(Reason)
        case noop(Reason)
    }

    func evaluate(_ signal: Signal, context: Context) -> Decision {
        let settings = context.settings
        guard settings.isEnabled else { return .suppress(.disabled) }
        // Never end a manually/shortcut-started session based on inferred activity.
        if context.isTracking {
            guard context.source?.isAutomatic == true else { return .noop(.manualSessionProtected) }
            guard signal.activity == .stationary else { return .noop(.alreadyTracking) }
            guard settings.stationaryAutoStopEnabled else { return .noop(.stationaryAutoStopDisabled) }
            guard signal.confidence == .high else { return .suppress(.lowConfidence) }
            guard signal.duration.isFinite, signal.duration >= 0,
                  settings.stationaryDuration.isFinite, settings.stationaryDuration > 0 else {
                return .suppress(.invalidSignal)
            }
            return signal.duration >= settings.stationaryDuration ? .stop : .noop(.insufficientStationaryTime)
        }
        guard context.canTrack else { return .suppress(.accessRequired) }
        guard context.hasAlwaysLocation else { return .suppress(.missingLocationPermission) }
        guard context.hasMotionPermission else { return .suppress(.missingMotionPermission) }
        guard signal.confidence == .high else { return .suppress(.lowConfidence) }
        guard signal.duration.isFinite, signal.duration >= 0,
              settings.minimumMovementDuration.isFinite, settings.minimumMovementDuration > 0,
              settings.manualStopCooldown.isFinite, settings.manualStopCooldown >= 0,
              settings.restartCooldown.isFinite, settings.restartCooldown >= 0 else {
            return .suppress(.invalidSignal)
        }
        // A future timestamp (clock moved backwards) conservatively retains the cooldown.
        if let last = context.lastManualStopAt,
           context.now.timeIntervalSince(last) < settings.manualStopCooldown {
            return .suppress(.manualStopCooldown)
        }
        if let last = context.lastAutoStartAt,
           context.now.timeIntervalSince(last) < settings.restartCooldown {
            return .suppress(.restartCooldown)
        }
        let enabled: Bool
        switch signal.activity {
        case .driving: enabled = settings.drivingEnabled
        case .walkingRunning: enabled = settings.walkingRunningEnabled
        case .cycling: enabled = settings.cyclingEnabled
        case .stationary, .unknown: return .noop(.noMovement)
        }
        guard enabled else { return .suppress(.activityDisabled) }
        guard signal.duration >= settings.minimumMovementDuration else { return .suppress(.insufficientMovement) }
        return .start(.smartMotion)
    }
}
