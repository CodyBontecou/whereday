import XCTest
@testable import IsoMe

final class AutoStartPolicyTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    private var context: AutoStartPolicy.Context {
        var settings = SmartStartSettings()
        settings.isEnabled = true
        return .init(settings: settings, hasAlwaysLocation: true, hasMotionPermission: true,
                     canTrack: true, isTracking: false, source: nil,
                     lastManualStopAt: nil, lastAutoStartAt: nil, now: now)
    }
    private let moving = AutoStartPolicy.Signal(activity: .driving, confidence: .high, duration: 120)
    private let policy = AutoStartPolicy()

    func testDisabledDefaultsCannotStart() {
        var c = context
        c.settings = SmartStartSettings()
        XCTAssertEqual(policy.evaluate(moving, context: c), .suppress(.disabled))
    }

    func testStartRequiresAlwaysMotionAndAccess() {
        var c = context
        c.hasAlwaysLocation = false
        XCTAssertEqual(policy.evaluate(moving, context: c), .suppress(.missingLocationPermission))
        c = context; c.hasMotionPermission = false
        XCTAssertEqual(policy.evaluate(moving, context: c), .suppress(.missingMotionPermission))
        c = context; c.canTrack = false
        XCTAssertEqual(policy.evaluate(moving, context: c), .suppress(.accessRequired))
    }

    func testConfidenceAndMovementThreshold() {
        for confidence in [AutoStartPolicy.Confidence.low, .medium] {
            var signal = moving; signal.confidence = confidence
            XCTAssertEqual(policy.evaluate(signal, context: context), .suppress(.lowConfidence))
        }
        var signal = moving; signal.duration = 119
        XCTAssertEqual(policy.evaluate(signal, context: context), .suppress(.insufficientMovement))
        XCTAssertEqual(policy.evaluate(moving, context: context), .start(.smartMotion))
    }

    func testActivityModesAreExplicit() {
        for activity in [AutoStartPolicy.Activity.walkingRunning, .cycling] {
            let signal = AutoStartPolicy.Signal(activity: activity, confidence: .high, duration: 120)
            XCTAssertEqual(policy.evaluate(signal, context: context), .suppress(.activityDisabled))
            var c = context
            c.settings.walkingRunningEnabled = true; c.settings.cyclingEnabled = true
            XCTAssertEqual(policy.evaluate(signal, context: c), .start(.smartMotion))
        }
        var c = context; c.settings.drivingEnabled = false
        XCTAssertEqual(policy.evaluate(moving, context: c), .suppress(.activityDisabled))
    }

    func testStationaryAndUnknownNeverStart() {
        for activity in [AutoStartPolicy.Activity.stationary, .unknown] {
            XCTAssertEqual(policy.evaluate(.init(activity: activity, confidence: .high, duration: 999),
                                           context: context), .noop(.noMovement))
        }
    }

    func testManualStopCooldownBoundaryAndFutureClock() {
        var c = context
        for age in [-60.0, 0, 1799] {
            c.lastManualStopAt = now.addingTimeInterval(-age)
            XCTAssertEqual(policy.evaluate(moving, context: c), .suppress(.manualStopCooldown))
        }
        c.lastManualStopAt = now.addingTimeInterval(-1800)
        XCTAssertEqual(policy.evaluate(moving, context: c), .start(.smartMotion))
    }

    func testRestartCooldownBoundary() {
        var c = context; c.lastAutoStartAt = now.addingTimeInterval(-299)
        XCTAssertEqual(policy.evaluate(moving, context: c), .suppress(.restartCooldown))
        c.lastAutoStartAt = now.addingTimeInterval(-300)
        XCTAssertEqual(policy.evaluate(moving, context: c), .start(.smartMotion))
    }

    func testAlreadyTrackingDoesNotStartAgain() {
        var c = context; c.isTracking = true; c.source = .smartMotion
        XCTAssertEqual(policy.evaluate(moving, context: c), .noop(.alreadyTracking))
    }

    func testManualAndShortcutSessionsNeverAutoStop() {
        var c = context; c.isTracking = true; c.settings.stationaryAutoStopEnabled = true
        let signal = AutoStartPolicy.Signal(activity: .stationary, confidence: .high, duration: 600)
        for source: TrackingStartSource? in [nil, .manual, .shortcut, .carPlayShortcut, .workoutShortcut] {
            c.source = source
            XCTAssertEqual(policy.evaluate(signal, context: c), .noop(.manualSessionProtected))
        }
    }

    func testStationaryStopRequiresOptInHighConfidenceAndDuration() {
        var c = context; c.isTracking = true; c.source = .smartMotion
        var signal = AutoStartPolicy.Signal(activity: .stationary, confidence: .high, duration: 600)
        XCTAssertEqual(policy.evaluate(signal, context: c), .noop(.stationaryAutoStopDisabled))
        c.settings.stationaryAutoStopEnabled = true
        signal.duration = 599
        XCTAssertEqual(policy.evaluate(signal, context: c), .noop(.insufficientStationaryTime))
        signal.duration = 600; signal.confidence = .low
        XCTAssertEqual(policy.evaluate(signal, context: c), .suppress(.lowConfidence))
        signal.confidence = .high
        XCTAssertEqual(policy.evaluate(signal, context: c), .stop)
    }

    func testAutomaticStopAlsoRequiresCurrentPermissionsAndAccess() {
        var c = context
        c.isTracking = true; c.source = .smartMotion; c.settings.stationaryAutoStopEnabled = true
        let signal = AutoStartPolicy.Signal(activity: .stationary, confidence: .high, duration: 600)
        c.canTrack = false
        XCTAssertEqual(policy.evaluate(signal, context: c), .suppress(.accessRequired))
        c.canTrack = true; c.hasAlwaysLocation = false
        XCTAssertEqual(policy.evaluate(signal, context: c), .suppress(.missingLocationPermission))
        c.hasAlwaysLocation = true; c.hasMotionPermission = false
        XCTAssertEqual(policy.evaluate(signal, context: c), .suppress(.missingMotionPermission))
    }

    func testInvalidDurationsFailClosed() {
        for duration in [-1.0, .nan, .infinity] {
            var signal = moving; signal.duration = duration
            XCTAssertEqual(policy.evaluate(signal, context: context), .suppress(.invalidSignal))
            var c = context; c.settings.minimumMovementDuration = duration
            XCTAssertEqual(policy.evaluate(moving, context: c), .suppress(.invalidSignal))
        }
    }
}

@MainActor
final class SmartStartStateTests: XCTestCase {
    private var defaults: UserDefaults!
    private var store: SmartStartStateStore { .init(defaults: defaults) }
    override func setUp() { defaults = makeIsolatedDefaults() }
    override func tearDown() { defaults = nil }

    func testDefaultsAreOptInAndEmpty() {
        XCTAssertFalse(store.settings.isEnabled)
        XCTAssertFalse(store.settings.stationaryAutoStopEnabled)
        XCTAssertNil(store.trackingStartTime)
        XCTAssertNil(store.trackingStartSource)
        XCTAssertNil(store.lastManualStopAt)
        XCTAssertNil(store.lastAutoStartAt)
    }

    func testSettingsRoundTripAndCorruptFallback() {
        var settings = SmartStartSettings(); settings.isEnabled = true; settings.cyclingEnabled = true
        store.settings = settings
        XCTAssertEqual(SmartStartStateStore(defaults: defaults).settings, settings)
        defaults.set(Data("invalid".utf8), forKey: "smartStartSettings")
        XCTAssertEqual(store.settings, SmartStartSettings())
    }

    func testAllSourcesRoundTripWithOnlyInferredSourcesRecordingAutoStart() {
        let date = Date(timeIntervalSince1970: 1_800_000_000)
        for source in [TrackingStartSource.manual, .shortcut, .carPlayShortcut, .workoutShortcut,
                       .smartMotion, .visitDeparture, .significantLocationChange] {
            defaults.removeObject(forKey: "lastAutoStartAt")
            store.beginSession(source: source, at: date)
            let restored = SmartStartStateStore(defaults: defaults)
            XCTAssertEqual(restored.trackingStartSource, source)
            XCTAssertEqual(restored.trackingStartTime, date)
            XCTAssertEqual(restored.lastAutoStartAt, source.isAutomatic ? date : nil)
        }
    }

    func testManualStopClearsSessionButRetainsCooldownAcrossNewSession() {
        let date = Date(timeIntervalSince1970: 1_800_000_000)
        store.beginSession(source: .smartMotion, at: date)
        store.endSession(manually: true, at: date.addingTimeInterval(10))
        XCTAssertNil(store.trackingStartTime); XCTAssertNil(store.trackingStartSource)
        XCTAssertEqual(store.lastManualStopAt, date.addingTimeInterval(10))
        store.beginSession(source: .manual, at: date.addingTimeInterval(20))
        store.endSession(manually: false, at: date.addingTimeInterval(30))
        XCTAssertEqual(store.lastManualStopAt, date.addingTimeInterval(10))
        XCTAssertEqual(store.lastAutoStartAt, date)
    }

    func testMetadataReconciliationDoesNotResetAutoStartCooldown() {
        let date = Date(timeIntervalSince1970: 1_800_000_000)
        store.beginSession(source: .smartMotion, at: date)
        store.saveSessionMetadata(source: .smartMotion, at: date.addingTimeInterval(-600))
        XCTAssertEqual(store.trackingStartTime, date.addingTimeInterval(-600))
        XCTAssertEqual(store.lastAutoStartAt, date)
    }

    func testUnknownLegacySourceIsOptional() {
        defaults.set("future-source", forKey: "trackingStartSource")
        XCTAssertNil(store.trackingStartSource)
    }
}
