import XCTest
import CoreLocation
import SwiftData
@testable import IsoMe

@MainActor
private final class QueuedStopAfterScheduler: StopAfterScheduling {
    var expiries: [@MainActor () -> Void] = []
    func schedule(after seconds: TimeInterval, expiry: @escaping @MainActor () -> Void) -> () -> Void {
        expiries.append(expiry)
        // These closures represent Timer work already enqueued on MainActor.
        // Invalidating the underlying Timer cannot retract them.
        return {}
    }
}

private final class SessionVisitFixture: CLVisit {
    private let arrival: Date
    private let departure: Date
    init(arrival: Date, departure: Date = .distantFuture) {
        self.arrival = arrival; self.departure = departure
        super.init()
    }
    required init?(coder: NSCoder) { fatalError("Synthetic fixture is not decoded") }
    override var coordinate: CLLocationCoordinate2D { .init(latitude: 10, longitude: 20) }
    override var arrivalDate: Date { arrival }
    override var departureDate: Date { departure }
    override var horizontalAccuracy: CLLocationAccuracy { 5 }
}

@MainActor
final class SmartStartCorrectionTests: XCTestCase {
    private var defaults: UserDefaults!
    private var manager: LocationManager!
    private var scheduler: QueuedStopAfterScheduler!
    private var container: ModelContainer!
    private var distanceFixes: [CLLocation] = []
    private var state: SmartStartStateStore { .init(defaults: defaults) }

    override func setUpWithError() throws {
        defaults = makeIsolatedDefaults()
        defaults.set(false, forKey: "allowNetworkGeocoding")
        scheduler = QueuedStopAfterScheduler()
        let schema = Schema([Visit.self, LocationPoint.self, RecordingSession.self, PhotoMoment.self, SavedPlace.self, CountryStay.self])
        container = try ModelContainer(for: schema, configurations: [ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)])
        manager = LocationManager(defaults: defaults, registersAsShared: false, monitoring: TestLocationMonitor(),
            trackingAccess: { true }, startsSmartStartRuntime: false, stopAfterScheduler: scheduler,
            recordDistanceLocation: { [weak self] in self?.distanceFixes.append($0) })
        manager.authorizationStatus = .authorizedAlways
        manager.setLiveActivityEnabled(false)
        manager.setModelContext(container.mainContext)
    }
    override func tearDown() {
        manager.stopTracking(); manager = nil
        container = nil; scheduler = nil; defaults = nil
        super.tearDown()
    }
    private func fix(at date: Date) -> CLLocation {
        CLLocation(coordinate: .init(latitude: 10, longitude: 20), altitude: 0,
                   horizontalAccuracy: 5, verticalAccuracy: 5, timestamp: date)
    }
    private func sessions() throws -> [RecordingSession] {
        try container.mainContext.fetch(FetchDescriptor<RecordingSession>())
    }

    func testPeerSuccessorRefreshesPersistedSessionAndInvalidatesPriorExpiry() throws {
        manager.setStopAfterHours(1)
        manager.startTracking(source: .shortcut)
        let oldExpiry = try XCTUnwrap(scheduler.expiries.last)
        let expiryCount = scheduler.expiries.count
        let start = Date().addingTimeInterval(-30)
        let intent = SharedRecordingIntent(revision: 1, origin: UUID(), sessionID: UUID(),
                                           startedAt: start, changedAt: Date(), isRecording: true)

        XCTAssertTrue(manager.applySharedRecordingIntent(intent))
        XCTAssertEqual(manager.trackingStartTime, start)
        XCTAssertEqual(state.trackingStartTime, start)
        XCTAssertEqual(manager.trackingStartSource, .manual)
        XCTAssertEqual(state.trackingStartSource, .manual)
        XCTAssertEqual(scheduler.expiries.count, expiryCount + 1)
        oldExpiry()
        XCTAssertTrue(manager.isTrackingEnabled, "Predecessor's queued expiry must not stop its peer successor")
        let open = try sessions().filter { $0.endedAt == nil }
        XCTAssertEqual(open.map(\.id), [intent.sessionID])
        XCTAssertEqual(open.first?.startedAt, start)
    }

    func testPeerStartCannotBypassPermissionOrRecordingAccess() throws {
        for (canTrack, status) in [(false, CLAuthorizationStatus.authorizedAlways), (true, .denied)] {
            let isolated = LocationManager(defaults: makeIsolatedDefaults(), registersAsShared: false,
                                           monitoring: TestLocationMonitor(), trackingAccess: { canTrack },
                                           startsSmartStartRuntime: false)
            isolated.authorizationStatus = status
            isolated.setLiveActivityEnabled(false)
            isolated.setModelContext(container.mainContext)
            let intent = SharedRecordingIntent(revision: 1, origin: UUID(), sessionID: UUID(),
                                               startedAt: Date(), changedAt: Date(), isRecording: true)
            XCTAssertFalse(isolated.applySharedRecordingIntent(intent))
            XCTAssertFalse(isolated.isTrackingEnabled)
            XCTAssertTrue(try sessions().isEmpty)
        }
    }

    func testExplicitPeerStartCannotRetainAutomaticStopOwnership() throws {
        var settings = SmartStartSettings()
        settings.isEnabled = true
        try manager.updateSmartStartSettings(settings)
        manager.startTracking(source: .smartMotion)
        XCTAssertEqual(manager.trackingStartSource, .smartMotion)
        let intent = SharedRecordingIntent(revision: 2, origin: UUID(), sessionID: UUID(),
                                           startedAt: Date(), changedAt: Date(), isRecording: true)
        XCTAssertTrue(manager.applySharedRecordingIntent(intent))
        XCTAssertEqual(manager.trackingStartSource, .manual)
        XCTAssertEqual(state.trackingStartSource, .manual)
        manager.stopTrackingAutomatically()
        XCTAssertTrue(manager.isTrackingEnabled, "Stationary policy must not stop an explicit peer recording")
    }

    func testCachedPreSessionFixDeliveredAfterStartCannotReachAnyRecordingConsumer() async throws {
        manager.setRouteSamplingMode(.time)
        manager.setRouteSampleInterval(60)
        manager.startTracking()
        let start = try XCTUnwrap(manager.trackingStartTime)
        // Start has already happened before this old/cached callback is processed.
        await manager.handleLocationUpdates([fix(at: start.addingTimeInterval(-1))])
        XCTAssertTrue(distanceFixes.isEmpty)
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<LocationPoint>()), 0)
        XCTAssertEqual(try sessions().first?.receivedLocationCount ?? 0, 0)
        await manager.handleLocationUpdates([fix(at: start.addingTimeInterval(1))])
        XCTAssertEqual(distanceFixes.count, 1)
        XCTAssertEqual(try container.mainContext.fetch(FetchDescriptor<LocationPoint>()).map(\.timestamp),
                       [start.addingTimeInterval(1)])
        XCTAssertEqual(try sessions().first?.receivedLocationCount, 1)
    }

    func testQueuedExpiryFromAIsHarmlessAfterExplicitSuccessorBStarts() throws {
        manager.setStopAfterHours(1)
        manager.startTracking()
        let queuedA = try XCTUnwrap(scheduler.expiries.first)
        manager.stopTracking()
        manager.startTracking(source: .shortcut)
        let startB = try XCTUnwrap(manager.trackingStartTime)
        queuedA()
        XCTAssertTrue(manager.isTrackingEnabled)
        XCTAssertEqual(manager.trackingStartSource, .shortcut)
        XCTAssertEqual(state.trackingStartSource, .shortcut)
        XCTAssertEqual(state.trackingStartTime, startB)
        XCTAssertEqual(try sessions().filter { $0.endedAt == nil }.count, 1)
    }

    func testUnsupportedMovementThresholdIsExplicitlyRejectedWithoutPersistence() throws {
        var settings = SmartStartSettings(); settings.isEnabled = true
        settings.minimumMovementDuration = 1200
        let end = Date(timeIntervalSince1970: 1_800_000_000)
        XCTAssertEqual(end.timeIntervalSince(ActivityDetectionManager.historyStart(endingAt: end)), 900)
        XCTAssertThrowsError(try state.saveSettings(settings))
        XCTAssertFalse(state.settings.isEnabled)
    }

    func testUnsupportedStationaryThresholdIsExplicitlyRejectedWithoutPersistence() throws {
        var settings = SmartStartSettings(); settings.isEnabled = true
        settings.stationaryAutoStopEnabled = true; settings.stationaryDuration = 1200
        XCTAssertThrowsError(try state.saveSettings(settings))
        XCTAssertFalse(state.settings.isEnabled)
    }

    func testCurrentExpiryStillStopsItsOwnExplicitSession() throws {
        manager.setStopAfterHours(1); manager.startTracking(source: .shortcut)
        try XCTUnwrap(scheduler.expiries.last)()
        XCTAssertFalse(manager.isTrackingEnabled)
        XCTAssertNil(state.trackingStartTime); XCTAssertNil(state.trackingStartSource)
        XCTAssertNil(state.lastManualStopAt)
        XCTAssertEqual(try sessions().filter { $0.endedAt == nil }.count, 0)
    }

    func testReschedulingSameSessionInvalidatesAlreadyQueuedExpiry() throws {
        manager.setStopAfterHours(1); manager.startTracking()
        let oldExpiry = try XCTUnwrap(scheduler.expiries.last)
        let start = try XCTUnwrap(state.trackingStartTime)
        manager.setStopAfterHours(2)
        oldExpiry()
        XCTAssertTrue(manager.isTrackingEnabled); XCTAssertEqual(state.trackingStartTime, start)
        try XCTUnwrap(scheduler.expiries.last)()
        XCTAssertFalse(manager.isTrackingEnabled)
    }

    func testRestoredRealStartAdmitsHistoricalInSessionFixButRejectsEarlierFix() async throws {
        let start = Date().addingTimeInterval(-120)
        state.beginSession(source: .manual, at: start)
        defaults.set(true, forKey: "isTrackingEnabled")
        let restored = LocationManager(defaults: defaults, registersAsShared: false, monitoring: TestLocationMonitor(),
            trackingAccess: { true }, startsSmartStartRuntime: false, stopAfterScheduler: scheduler,
            recordDistanceLocation: { [weak self] in self?.distanceFixes.append($0) })
        restored.setModelContext(container.mainContext)
        XCTAssertEqual(restored.trackingStartTime, start)
        await restored.handleLocationUpdates([fix(at: start.addingTimeInterval(-1)),
                                              fix(at: start.addingTimeInterval(1))])
        XCTAssertEqual(distanceFixes.map(\.timestamp), [start.addingTimeInterval(1)])
        XCTAssertEqual(try container.mainContext.fetch(FetchDescriptor<LocationPoint>()).map(\.timestamp),
                       [start.addingTimeInterval(1)])
        XCTAssertEqual(try sessions().first?.receivedLocationCount, 1)
        XCTAssertEqual(state.trackingStartTime, start)
        restored.stopTracking()
    }

    func testActiveFlagWithoutSessionMetadataCannotAdmitFixes() async throws {
        manager.isTrackingEnabled = true
        await manager.handleLocationUpdates([fix(at: Date())])
        XCTAssertTrue(distanceFixes.isEmpty)
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<LocationPoint>()), 0)
    }

    func testVisitDepartureCanClosePreviouslyRecordedVisitOnlyInsideCurrentSession() throws {
        manager.startTracking()
        let start = try XCTUnwrap(manager.trackingStartTime)
        let arrival = start.addingTimeInterval(-120)
        let visit = Visit(latitude: 10, longitude: 20, arrivedAt: arrival)
        container.mainContext.insert(visit); try container.mainContext.save()
        manager.handleVisit(SessionVisitFixture(arrival: arrival, departure: start.addingTimeInterval(-1)))
        XCTAssertNil(visit.departedAt)
        manager.handleVisit(SessionVisitFixture(arrival: arrival, departure: start.addingTimeInterval(1)))
        XCTAssertEqual(visit.departedAt, start.addingTimeInterval(1))
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<Visit>()), 1)
    }

    func testFreshVisitArrivalStillPersistsNormally() throws {
        let saved = SavedPlace(name: "Synthetic stop", latitude: 10, longitude: 20)
        container.mainContext.insert(saved); try container.mainContext.save()
        manager.startTracking()
        let start = try XCTUnwrap(manager.trackingStartTime)
        manager.handleVisit(SessionVisitFixture(arrival: start.addingTimeInterval(1)))
        let visits = try container.mainContext.fetch(FetchDescriptor<Visit>())
        XCTAssertEqual(visits.count, 1)
        XCTAssertEqual(visits.first?.arrivedAt, start.addingTimeInterval(1))
        XCTAssertTrue(visits.first?.geocodingCompleted == true)
    }

    func testEverySupportedThresholdFitsTheActualHistoryWindowForStartAndStop() throws {
        let end = Date(timeIntervalSince1970: 1_800_000_000)
        let start = ActivityDetectionManager.historyStart(endingAt: end)
        let evidence = ActivityEvidence(intervals: [.init(start: start, end: end,
            activity: .driving, confidence: .high)], queriedAt: end)
        let movement = try XCTUnwrap(evidence.signal(at: end))
        XCTAssertEqual(movement.duration, 900)
        for threshold in [0.001, 1, 120, 600, 899.999, 900] {
            var settings = SmartStartSettings(); settings.isEnabled = true
            settings.minimumMovementDuration = threshold; settings.stationaryDuration = threshold
            settings.stationaryAutoStopEnabled = true
            try state.saveSettings(settings)
            XCTAssertEqual(state.settings, settings)
            var context = AutoStartPolicy.Context(settings: settings, hasAlwaysLocation: true,
                hasMotionPermission: true, canTrack: true, isTracking: false, source: nil,
                lastManualStopAt: nil, lastAutoStartAt: nil, now: end)
            XCTAssertEqual(AutoStartPolicy().evaluate(movement, context: context), .start(.smartMotion))
            context.isTracking = true; context.source = .smartMotion
            XCTAssertEqual(AutoStartPolicy().evaluate(.init(activity: .stationary, confidence: .high,
                duration: movement.duration), context: context), .stop)
        }
    }

    func testInvalidThresholdBoundariesAndLegacyPayloadFailClosed() throws {
        for duration in [0.0, -1, .nan, .infinity, 900.001] {
            var settings = SmartStartSettings(); settings.isEnabled = true
            settings.minimumMovementDuration = duration
            XCTAssertThrowsError(try state.saveSettings(settings)) { error in
                XCTAssertEqual(error as? SmartStartSettings.ValidationError, .unsupportedMovementDuration)
            }
            settings = SmartStartSettings(); settings.stationaryDuration = duration
            XCTAssertThrowsError(try state.saveSettings(settings)) { error in
                XCTAssertEqual(error as? SmartStartSettings.ValidationError, .unsupportedStationaryDuration)
            }
        }
        var legacy = SmartStartSettings(); legacy.isEnabled = true; legacy.minimumMovementDuration = 1200
        defaults.set(try JSONEncoder().encode(legacy), forKey: "smartStartSettings")
        XCTAssertFalse(state.settings.isEnabled)
        let context = AutoStartPolicy.Context(settings: legacy, hasAlwaysLocation: true,
            hasMotionPermission: true, canTrack: true, isTracking: false, source: nil,
            lastManualStopAt: nil, lastAutoStartAt: nil, now: Date())
        XCTAssertEqual(AutoStartPolicy().evaluate(.init(activity: .driving, confidence: .high,
            duration: 1200), context: context), .suppress(.invalidSignal))
    }

    func testManagerRejectsUnsupportedSettingsWithoutChangingPersistedOptIn() {
        var settings = manager.smartStartSettings; settings.isEnabled = true
        settings.minimumMovementDuration = 1200
        XCTAssertThrowsError(try manager.updateSmartStartSettings(settings))
        XCTAssertFalse(manager.smartStartSettings.isEnabled)
        XCTAssertFalse(manager.ambientMonitoringActive)
    }

    func testNewVisitWhoseArrivalPredatesSessionIsNotSynthesizedFromLateDelivery() throws {
        manager.startTracking()
        let start = try XCTUnwrap(manager.trackingStartTime)
        manager.handleVisit(SessionVisitFixture(arrival: start.addingTimeInterval(-60),
                                               departure: start.addingTimeInterval(1)))
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<Visit>()), 0)
    }
}

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
        XCTAssertNoThrow(try coordinator.updateSettings(settings))
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
    func testUnsupportedSettingsReportErrorBeforeSensorOrPersistenceChanges() {
        var settings = state.settings; settings.isEnabled = true; settings.minimumMovementDuration = 1200
        XCTAssertThrowsError(try coordinator.updateSettings(settings))
        XCTAssertFalse(state.settings.isEnabled); XCTAssertFalse(target.ambient)
        XCTAssertEqual(motion.starts, 0)
        enable()
        let previous = state.settings
        XCTAssertThrowsError(try coordinator.updateSettings(settings))
        XCTAssertEqual(state.settings, previous)
        XCTAssertEqual(motion.starts, 1); XCTAssertEqual(motion.stops, 0)
    }
    func testWakeWithoutMotionEvidenceCannotStart() {
        enable(); coordinator.locationWake(); motion.emit(nil)
        XCTAssertEqual(target.starts, 0); XCTAssertEqual(decisions.last, .suppress(.invalidSignal))
    }
    func testDisableInvalidatesCallbacksEvenAfterReenable() {
        enable(); let oldCallback = motion.callbacks[0]
        var settings = state.settings; settings.isEnabled = false
        XCTAssertNoThrow(try coordinator.updateSettings(settings))
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
        XCTAssertNoThrow(try coordinator.updateSettings(settings)); motion.emit(evidence())
        date.addTimeInterval(600); motion.emit(evidence(.stationary, duration: 600))
        XCTAssertEqual(target.stops, 1); XCTAssertNil(state.lastManualStopAt)
        XCTAssertTrue(target.ambient)
    }
    func testExplicitSessionsProtectedFromStationaryStop() {
        var settings = state.settings; settings.isEnabled = true; settings.stationaryAutoStopEnabled = true
        XCTAssertNoThrow(try coordinator.updateSettings(settings))
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
        XCTAssertNoThrow(try manager.updateSmartStartSettings(settings))
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
        XCTAssertNoThrow(try manager.updateSmartStartSettings(settings))
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
        XCTAssertNoThrow(try manager.updateSmartStartSettings(settings))
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
        XCTAssertNoThrow(try runtimeManager.updateSmartStartSettings(settings))
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
