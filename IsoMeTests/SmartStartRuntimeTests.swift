import XCTest
import CoreLocation
import SwiftData
@testable import IsoMe

@MainActor
private final class TestMotionDetector: ActivityDetecting {
    var status: MotionDetectionStatus = .authorized
    var callbacks: [(ActivityEvidence?) -> Void] = []
    var starts = 0, stops = 0, queries = 0
    var onQuery: (() -> Void)?
    func start(_ receive: @escaping (ActivityEvidence?) -> Void) { starts += 1; callbacks.append(receive) }
    func queryRecentActivity() { queries += 1; onQuery?() }
    func stop() { stops += 1 }
    func emit(_ evidence: ActivityEvidence?) { callbacks.last?(evidence) }
}

@MainActor
private final class TestTrackingTarget: SmartStartTracking {
    var hasAlwaysPermission = true
    var isTrackingEnabled = false
    var trackingStartSource: TrackingStartSource?
    var ambient = false
    var starts = 0, stops = 0
    let state: SmartStartStateStore
    var now: Date
    init(state: SmartStartStateStore, now: Date) { self.state = state; self.now = now }
    func startAmbientMonitoring() { ambient = true }
    func stopAmbientMonitoring() { ambient = false }
    func startTracking(source: TrackingStartSource, allowsPermissionPrompt: Bool) {
        XCTAssertFalse(allowsPermissionPrompt)
        guard !isTrackingEnabled else { return }
        starts += 1; isTrackingEnabled = true; trackingStartSource = source
        state.beginSession(source: source, at: now)
    }
    func stopTrackingAutomatically() {
        stops += 1; isTrackingEnabled = false; trackingStartSource = nil
        state.endSession(manually: false, at: now)
    }
}

@MainActor
final class AutoStartCoordinatorTests: XCTestCase {
    private var defaults: UserDefaults!
    private var motion: TestMotionDetector!
    private var target: TestTrackingTarget!
    private var coordinator: AutoStartCoordinator!
    private var date = Date(timeIntervalSince1970: 1_800_000_000)
    private var access = true
    private var decisions: [AutoStartPolicy.Decision] = []
    private var state: SmartStartStateStore { .init(defaults: defaults) }

    override func setUp() {
        super.setUp()
        defaults = makeIsolatedDefaults(); motion = TestMotionDetector()
        target = TestTrackingTarget(state: state, now: date)
        coordinator = AutoStartCoordinator(tracking: target, motion: motion, state: state,
            canTrack: { [unowned self] in access }, now: { [unowned self] in date },
            observeLifecycle: false, log: { [unowned self] in decisions.append($0) })
    }
    override func tearDown() {
        coordinator.shutdown(); coordinator = nil; target = nil; motion = nil; defaults = nil
        super.tearDown()
    }
    private func enable() {
        var settings = state.settings; settings.isEnabled = true
        coordinator.updateSettings(settings)
    }
    private func evidence(_ kind: AutoStartPolicy.Activity = .driving,
                          duration: TimeInterval = 120,
                          confidence: AutoStartPolicy.Confidence = .high) -> ActivityEvidence {
        .init(intervals: [.init(start: date.addingTimeInterval(-duration), end: date,
                               activity: kind, confidence: confidence)], queriedAt: date)
    }

    func testDefaultOffDoesNotStartMotionOrArmAmbientOrQueryWake() {
        coordinator.refresh(); coordinator.locationWake()
        XCTAssertFalse(target.ambient); XCTAssertEqual(motion.starts, 0)
        XCTAssertEqual(motion.queries, 0); XCTAssertEqual(target.starts, 0)
    }
    func testMissingAlwaysDoesNotAskMotionOrArm() {
        target.hasAlwaysPermission = false; enable(); coordinator.locationWake()
        XCTAssertFalse(target.ambient); XCTAssertEqual(motion.starts, 0); XCTAssertEqual(motion.queries, 0)
    }
    func testMissingAccessDoesNotStartObservers() {
        access = false; enable()
        XCTAssertFalse(target.ambient); XCTAssertEqual(motion.starts, 0)
    }
    func testUnavailableOrDeniedMotionDoesNotStartStream() {
        for status in [MotionDetectionStatus.unavailable, .denied] {
            motion.status = status; enable(); coordinator.locationWake()
            XCTAssertEqual(motion.starts, 0); XCTAssertEqual(target.starts, 0)
            XCTAssertEqual(decisions.last, .suppress(.missingMotionPermission))
        }
    }
    func testPermissionMayOnlyBeRequestedAfterOptInAndAlways() {
        motion.status = .notDetermined; coordinator.refresh()
        XCTAssertEqual(motion.starts, 0)
        enable(); XCTAssertEqual(motion.starts, 1)
        motion.status = .authorized; motion.emit(evidence())
        XCTAssertEqual(target.starts, 1)
    }
    func testRepeatedRefreshWakeAndEvidenceUseOneStreamAndOneSession() {
        enable(); coordinator.refresh(); coordinator.locationWake()
        XCTAssertEqual(motion.starts, 1); XCTAssertEqual(motion.queries, 1)
        motion.emit(evidence()); motion.emit(evidence())
        date.addTimeInterval(30); motion.emit(evidence())
        XCTAssertEqual(target.starts, 1)
        XCTAssertEqual(target.trackingStartSource, .smartMotion)
        XCTAssertEqual(decisions.last, .noop(.alreadyTracking))
    }
    func testWakeWithoutMotionEvidenceCannotStart() {
        enable(); coordinator.locationWake(); motion.emit(nil)
        XCTAssertEqual(target.starts, 0); XCTAssertEqual(decisions.last, .suppress(.invalidSignal))
    }
    func testDisableInvalidatesCallbacksEvenAfterReenable() {
        enable(); let oldCallback = motion.callbacks[0]
        var settings = state.settings; settings.isEnabled = false
        coordinator.updateSettings(settings)
        XCTAssertFalse(target.ambient); XCTAssertEqual(motion.stops, 1)
        oldCallback(evidence()); XCTAssertEqual(target.starts, 0)
        enable(); oldCallback(evidence()); XCTAssertEqual(target.starts, 0)
        motion.emit(evidence()); XCTAssertEqual(target.starts, 1)
    }
    func testPermissionDowngradeCancelsAndRejectsUndeliveredCallback() {
        enable(); let oldCallback = motion.callbacks[0]
        target.hasAlwaysPermission = false; coordinator.refresh()
        oldCallback(evidence())
        XCTAssertFalse(target.ambient); XCTAssertEqual(motion.stops, 1); XCTAssertEqual(target.starts, 0)
    }
    func testCurrentPermissionAndAccessRecheckedWithoutNotification() {
        enable(); target.hasAlwaysPermission = false; motion.emit(evidence())
        XCTAssertEqual(target.starts, 0); XCTAssertFalse(target.ambient)
        target.hasAlwaysPermission = true; enable(); access = false; motion.emit(evidence())
        XCTAssertEqual(target.starts, 0); XCTAssertFalse(target.ambient)
    }
    func testMotionRevocationStopsStreamWithoutStarting() {
        enable(); motion.status = .denied; motion.emit(evidence())
        XCTAssertEqual(target.starts, 0); XCTAssertEqual(motion.stops, 1)
    }
    func testManualStopCooldownSurvivesNewEvidenceAndExpires() {
        state.endSession(manually: true, at: date)
        enable(); motion.emit(evidence())
        XCTAssertEqual(decisions.last, .suppress(.manualStopCooldown)); XCTAssertEqual(target.starts, 0)
        date.addTimeInterval(1800); motion.emit(evidence())
        XCTAssertEqual(target.starts, 1)
    }
    func testLowConfidenceShortUnknownAndStationaryDoNotStart() {
        enable()
        motion.emit(evidence(confidence: .low)); XCTAssertEqual(decisions.last, .suppress(.lowConfidence))
        date.addTimeInterval(30); motion.emit(evidence(duration: 119))
        XCTAssertEqual(decisions.last, .suppress(.insufficientMovement))
        date.addTimeInterval(30); motion.emit(evidence(.unknown))
        XCTAssertEqual(decisions.last, .noop(.noMovement))
        date.addTimeInterval(30); motion.emit(evidence(.stationary, duration: 600))
        XCTAssertEqual(target.starts, 0)
    }
    func testStationaryAutomaticStopDoesNotCreateManualCooldown() {
        var settings = state.settings; settings.isEnabled = true; settings.stationaryAutoStopEnabled = true
        coordinator.updateSettings(settings); motion.emit(evidence())
        date.addTimeInterval(600); motion.emit(evidence(.stationary, duration: 600))
        XCTAssertEqual(target.stops, 1); XCTAssertNil(state.lastManualStopAt)
        XCTAssertTrue(target.ambient)
    }
    func testExplicitSessionsProtectedFromStationaryStop() {
        var settings = state.settings; settings.isEnabled = true; settings.stationaryAutoStopEnabled = true
        coordinator.updateSettings(settings)
        for source in [TrackingStartSource.manual, .shortcut, .carPlayShortcut, .workoutShortcut] {
            date.addTimeInterval(30); target.isTrackingEnabled = true; target.trackingStartSource = source
            motion.emit(evidence(.stationary, duration: 600))
        }
        XCTAssertEqual(target.stops, 0)
    }
    func testStaleOutOfOrderAndFutureEvidenceRejected() {
        enable()
        var stale = evidence(); stale.queriedAt = date.addingTimeInterval(-31)
        motion.emit(stale); XCTAssertEqual(target.starts, 0)
        motion.emit(evidence(duration: 1))
        date.addTimeInterval(-1); motion.emit(evidence())
        XCTAssertEqual(target.starts, 0); XCTAssertEqual(decisions.last, .suppress(.invalidSignal))
    }
    func testShutdownCannotBeRestartedByForegroundOrLateCallbacks() {
        enable(); let callback = motion.callbacks[0]
        coordinator.shutdown(); coordinator.refresh(); coordinator.locationWake()
        callback(evidence())
        XCTAssertEqual(motion.starts, 1); XCTAssertEqual(motion.stops, 1)
        XCTAssertEqual(motion.queries, 0); XCTAssertEqual(target.starts, 0)
        XCTAssertFalse(target.ambient)
    }
    func testTargetAndCoordinatorHaveNoRetainCycle() {
        weak var weakTarget = target
        weak var weakCoordinator = coordinator
        coordinator.shutdown(); coordinator = nil; target = nil
        XCTAssertNil(weakTarget); XCTAssertNil(weakCoordinator)
        // Keep teardown safe and prove shutdown removed callbacks' effective ownership.
        target = TestTrackingTarget(state: state, now: date)
        coordinator = AutoStartCoordinator(tracking: target, motion: motion, state: state,
                                            canTrack: { true }, observeLifecycle: false, log: { _ in })
    }
}

final class ActivityEvidenceTests: XCTestCase {
    private let now = Date(timeIntervalSince1970: 1_800_000_000)
    private func interval(_ start: Double, _ end: Double,
                          _ activity: AutoStartPolicy.Activity = .driving,
                          _ confidence: AutoStartPolicy.Confidence = .high) -> ActivityEvidence.Interval {
        .init(start: now.addingTimeInterval(start), end: now.addingTimeInterval(end),
              activity: activity, confidence: confidence)
    }
    func testContiguousHighConfidenceEvidenceUsesRealIntervals() {
        let evidence = ActivityEvidence(intervals: [interval(-120, -60), interval(-60, 0)], queriedAt: now)
        XCTAssertEqual(evidence.signal(at: now)?.duration, 120)
    }
    func testGapOrChangedActivityOrConfidenceDoesNotCountAsSustained() {
        for intervals in [[interval(-600, -60), interval(-30, 0)],
                          [interval(-600, -30, .stationary), interval(-30, 0)],
                          [interval(-600, -30, .driving, .low), interval(-30, 0)]] {
            XCTAssertEqual(ActivityEvidence(intervals: intervals, queriedAt: now).signal(at: now)?.duration, 30)
        }
    }
    func testUnknownAndLowConfidenceStayNormalized() {
        let evidence = ActivityEvidence(intervals: [interval(-120, 0, .unknown, .low)], queriedAt: now)
        XCTAssertEqual(evidence.signal(at: now)?.activity, .unknown)
        XCTAssertEqual(evidence.signal(at: now)?.confidence, .low)
    }
    func testReversedOverlappingUnsortedAndMissingCoverageRejected() {
        for intervals in [[interval(1, 0)], [interval(-120, -30), interval(-60, 0)],
                          [interval(-30, 0), interval(-120, -30)], [interval(-120, -1)]] {
            XCTAssertNil(ActivityEvidence(intervals: intervals, queriedAt: now).signal(at: now))
        }
    }
    func testStaleFutureAndEmptyEvidenceRejected() {
        let evidence = ActivityEvidence(intervals: [interval(-120, 0)], queriedAt: now)
        XCTAssertNil(evidence.signal(at: now.addingTimeInterval(31)))
        XCTAssertNil(evidence.signal(at: now.addingTimeInterval(-1)))
        XCTAssertNil(ActivityEvidence(intervals: [], queriedAt: now).signal(at: now))
    }
    func testNoUptimeOrUncoveredPastIsAdded() {
        let evidence = ActivityEvidence(intervals: [interval(-5, 0)], queriedAt: now)
        XCTAssertEqual(evidence.signal(at: now.addingTimeInterval(20))?.duration, 5)
    }
}

@MainActor
private final class TestLocationMonitor: TrackingLocationMonitoring {
    var activeStarts = 0, activeStops = 0, ambientStarts = 0, ambientStops = 0
    func startActiveUpdates() { activeStarts += 1 }
    func stopActiveUpdates() { activeStops += 1 }
    func startLowPowerMonitoring() { ambientStarts += 1 }
    func stopLowPowerMonitoring() { ambientStops += 1 }
}

@MainActor
final class SmartStartMonitoringTests: XCTestCase {
    private var defaults: UserDefaults!
    private var monitor: TestLocationMonitor!
    private var manager: LocationManager!
    private var retainedContainer: ModelContainer?
    override func setUp() {
        super.setUp()
        defaults = makeIsolatedDefaults(); monitor = TestLocationMonitor()
        manager = LocationManager(defaults: defaults, registersAsShared: false, monitoring: monitor,
                                  trackingAccess: { true }, startsSmartStartRuntime: false)
        manager.authorizationStatus = .authorizedAlways
        manager.isLiveActivityEnabled = false
    }
    override func tearDown() {
        manager.stopTracking(); manager = nil
        retainedContainer = nil
        monitor = nil; defaults = nil
        super.tearDown()
    }
    private func makeContainer() throws -> ModelContainer {
        let schema = Schema([Visit.self, LocationPoint.self, RecordingSession.self, PhotoMoment.self, SavedPlace.self, CountryStay.self])
        let container = try ModelContainer(for: schema, configurations: [ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)])
        // A ModelContext does not own its ModelContainer. Keep it through Stop
        // in tearDown; otherwise session reconciliation dereferences a dead store.
        retainedContainer = container
        return container
    }
    private func enable() {
        var settings = manager.smartStartSettings; settings.isEnabled = true
        manager.updateSmartStartSettings(settings)
    }
    func testDefaultOffArmsNothing() {
        manager.startAmbientMonitoring()
        XCTAssertFalse(manager.ambientMonitoringActive)
        XCTAssertEqual(monitor.ambientStarts, 0); XCTAssertEqual(monitor.activeStarts, 0)
    }
    func testAlwaysRequiredAndAmbientStartIdempotent() {
        manager.authorizationStatus = .authorizedWhenInUse; enable()
        XCTAssertFalse(manager.ambientMonitoringActive)
        manager.authorizationStatus = .authorizedAlways
        manager.startAmbientMonitoring(); manager.startAmbientMonitoring()
        XCTAssertTrue(manager.ambientMonitoringActive); XCTAssertEqual(monitor.ambientStarts, 1)
        XCTAssertEqual(monitor.activeStarts, 0)
    }
    func testStopActiveRetainsAmbientOnlyWhileOptedIn() {
        enable(); manager.startTracking()
        XCTAssertEqual(monitor.activeStarts, 1)
        manager.stopTracking()
        XCTAssertTrue(manager.ambientMonitoringActive); XCTAssertEqual(monitor.ambientStops, 0)
        XCTAssertEqual(monitor.activeStops, 1)
        var settings = manager.smartStartSettings; settings.isEnabled = false
        manager.updateSmartStartSettings(settings)
        XCTAssertFalse(manager.ambientMonitoringActive); XCTAssertEqual(monitor.ambientStops, 1)
    }
    func testDefaultManualStartStopKeepsLegacyMonitorLifecycle() {
        manager.startTracking(); XCTAssertEqual(monitor.ambientStarts, 1)
        manager.stopTracking(); XCTAssertEqual(monitor.ambientStops, 1)
        XCTAssertFalse(manager.ambientMonitoringActive)
    }
    func testOptOutDoesNotEndManualSession() {
        enable(); manager.startTracking()
        var settings = manager.smartStartSettings; settings.isEnabled = false
        manager.updateSmartStartSettings(settings)
        XCTAssertTrue(manager.isTrackingEnabled); XCTAssertFalse(manager.ambientMonitoringActive)
        XCTAssertEqual(monitor.ambientStops, 0, "active route keeps legacy visit monitoring")
    }
    func testAutomaticStopLeavesAmbientAndDoesNotSetManualCooldown() {
        enable(); manager.startTracking(source: .smartMotion)
        manager.stopTrackingAutomatically()
        XCTAssertFalse(manager.isTrackingEnabled); XCTAssertTrue(manager.ambientMonitoringActive)
        XCTAssertNil(SmartStartStateStore(defaults: defaults).lastManualStopAt)
    }
    func testAutomaticStopCannotEndExplicitSession() {
        manager.startTracking(source: .shortcut); manager.stopTrackingAutomatically()
        XCTAssertTrue(manager.isTrackingEnabled)
    }
    func testPermissionDowngradeDisarmsIdleAmbientMonitoring() {
        enable()
        manager.handleAuthorizationChange(status: .authorizedWhenInUse, accuracy: .fullAccuracy)
        XCTAssertFalse(manager.ambientMonitoringActive)
        XCTAssertEqual(monitor.ambientStops, 1)
        XCTAssertEqual(monitor.activeStarts, 0)
    }
    func testVisitWakeWhileAmbientDoesNotCreateVisitOrSession() throws {
        let container = try makeContainer()
        manager.setModelContext(container.mainContext); enable()
        manager.handleVisit(CLVisit())
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<Visit>()), 0)
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<RecordingSession>()), 0)
    }
    func testWakeThatStartsRouteStillCannotSaveAmbientFix() async throws {
        let detector = TestMotionDetector()
        let runtimeManager = LocationManager(defaults: defaults, registersAsShared: false, monitoring: monitor,
                                             trackingAccess: { true }, motionDetector: detector)
        runtimeManager.authorizationStatus = .authorizedAlways
        runtimeManager.isLiveActivityEnabled = false
        var settings = runtimeManager.smartStartSettings; settings.isEnabled = true
        runtimeManager.updateSmartStartSettings(settings)
        let container = try makeContainer()
        runtimeManager.setModelContext(container.mainContext)
        detector.onQuery = { [weak detector] in
            let date = Date().addingTimeInterval(-0.1)
            detector?.emit(.init(intervals: [.init(start: date.addingTimeInterval(-120), end: date,
                                                  activity: .driving, confidence: .high)], queriedAt: date))
        }
        await runtimeManager.handleLocationUpdates([CLLocation(latitude: 10, longitude: 20)])
        XCTAssertTrue(runtimeManager.isTrackingEnabled)
        XCTAssertEqual(runtimeManager.trackingStartSource, .smartMotion)
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<LocationPoint>()), 0)
        XCTAssertEqual(runtimeManager.locationPointsSavedCount, 0)
        runtimeManager.stopTracking()
    }
    func testAmbientOnlyLocationCallbackDoesNotPersistRoutePoints() async throws {
        let container = try makeContainer()
        manager.setModelContext(container.mainContext); enable()
        await manager.handleLocationUpdates([CLLocation(latitude: 10, longitude: 20)])
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<LocationPoint>()), 0)
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<RecordingSession>()), 0)
        XCTAssertEqual(manager.locationPointsSavedCount, 0)
        XCTAssertFalse(manager.isTrackingEnabled)
    }
}
