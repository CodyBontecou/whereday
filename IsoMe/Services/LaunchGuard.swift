import Foundation

/// Breaks launch loops after a tracking session.
///
/// A launch that relaunches tracking resumes Core Location and the Live Activity
/// before the first screen settles. If something in that path kills the app,
/// each later launch repeats it, so the app never opens and only a reinstall
/// (which also deletes every recording) helps.
///
/// Every foreground launch counts as unconfirmed until the app has stayed open
/// for `settleDelay` or reached the background on its own. After
/// `failureThreshold` unconfirmed launches in a row, the next one does not
/// resume tracking. It keeps all data, ends the stale recording, and tells
/// the person. It also records the last launch step reached, which shows up in
/// Diagnostics.
enum LaunchGuard {
    enum Stage: String {
        case started = "started"
        case locationManager = "resuming tracking"
        case viewModel = "loading saved data"
        case interface = "showing the app"
    }

    struct Decision: Equatable {
        /// True when tracking was switched off to break a launch loop.
        let pausedTracking: Bool
        /// Unconfirmed launches in a row before this one.
        let previousUnconfirmedLaunches: Int
        /// The last step the previous launch reached, when it didn't finish.
        let previousStage: String?
    }

    static let unconfirmedLaunchesKey = "launchGuard.unconfirmedLaunches"
    static let lastStageKey = "launchGuard.lastStage"
    static let pausedTrackingNoticeKey = "launchGuard.pausedTrackingNotice"
    static let trackingEnabledKey = "isTrackingEnabled"

    static let failureThreshold = 2
    static let settleDelay: Duration = .seconds(10)

    /// Call at the start of a foreground launch, before tracking can resume.
    /// Launches into the background (location or intent relaunches) are not counted.
    @discardableResult
    static func beginLaunch(defaults: UserDefaults = .standard, isBackgroundLaunch: Bool) -> Decision {
        let previous = defaults.integer(forKey: unconfirmedLaunchesKey)
        let previousStage = previous > 0 ? defaults.string(forKey: lastStageKey) : nil
        guard !isBackgroundLaunch else {
            return Decision(pausedTracking: false, previousUnconfirmedLaunches: previous, previousStage: previousStage)
        }

        let shouldPause = previous >= failureThreshold && defaults.bool(forKey: trackingEnabledKey)
        if shouldPause {
            defaults.set(false, forKey: trackingEnabledKey)
            defaults.set(true, forKey: pausedTrackingNoticeKey)
        }

        // Once tracking is paused, count from zero so the next unconfirmed launch
        // doesn't immediately trip the guard again.
        defaults.set(shouldPause ? 1 : previous + 1, forKey: unconfirmedLaunchesKey)
        defaults.set(Stage.started.rawValue, forKey: lastStageKey)
        // Persist before the risky work below: a crash can come within milliseconds.
        defaults.synchronize()

        return Decision(pausedTracking: shouldPause, previousUnconfirmedLaunches: previous, previousStage: previousStage)
    }

    static func mark(_ stage: Stage, defaults: UserDefaults = .standard) {
        guard defaults.integer(forKey: unconfirmedLaunchesKey) > 0 else { return }
        defaults.set(stage.rawValue, forKey: lastStageKey)
        defaults.synchronize()
    }

    /// The app stayed up: clear the count so ordinary use never trips the guard.
    static func confirmLaunch(defaults: UserDefaults = .standard) {
        defaults.removeObject(forKey: unconfirmedLaunchesKey)
        defaults.removeObject(forKey: lastStageKey)
    }

    @MainActor private static var confirmationTask: Task<Void, Never>?

    /// Confirms the launch once the app has stayed active for `settleDelay`.
    @MainActor static func scheduleConfirmation() {
        guard confirmationTask == nil,
              UserDefaults.standard.integer(forKey: unconfirmedLaunchesKey) > 0 else { return }
        confirmationTask = Task { @MainActor in
            try? await Task.sleep(for: settleDelay)
            confirmationTask = nil
            guard !Task.isCancelled else { return }
            confirmLaunch()
        }
    }

    /// Returns true once after the guard paused tracking, so the UI can explain why.
    static func consumePausedTrackingNotice(defaults: UserDefaults = .standard) -> Bool {
        guard defaults.bool(forKey: pausedTrackingNoticeKey) else { return false }
        defaults.removeObject(forKey: pausedTrackingNoticeKey)
        return true
    }
}
