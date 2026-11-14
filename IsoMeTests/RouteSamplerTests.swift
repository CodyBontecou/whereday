import XCTest
import CoreLocation
@testable import IsoMe

/// Route sampling: which delivered fixes become route points in distance, time and
/// distance + time modes, and when the GPS may rest between samples.
final class RouteSamplerTests: XCTestCase {

    private let start = Date(timeIntervalSince1970: 1_800_000_000)
    private let origin = CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194)

    /// A fix `north` metres north of the origin, `t` seconds after `start`.
    private func fix(t: TimeInterval, north: Double = 0, accuracy: CLLocationAccuracy = 5) -> CLLocation {
        let metresPerDegree = 111_320.0
        return CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: origin.latitude + north / metresPerDegree, longitude: origin.longitude),
            altitude: 0,
            horizontalAccuracy: accuracy,
            verticalAccuracy: 5,
            timestamp: start.addingTimeInterval(t)
        )
    }

    private func sampler(_ mode: RouteSamplingMode, interval: TimeInterval = 60, distance: Double = 25) -> RouteSampler {
        RouteSampler(policy: RouteSamplingPolicy(mode: mode, distanceFilter: distance, interval: interval))
    }

    private func seconds(_ locations: [CLLocation]) -> [Int] {
        locations.map { Int($0.timestamp.timeIntervalSince(start).rounded()) }
    }

    // MARK: - Distance mode is unchanged

    func testDistanceModePassesEveryFixThroughAtFullPower() {
        var sampler = sampler(.distance)
        let batch = [fix(t: 0), fix(t: 1, north: 5, accuracy: 150), fix(t: 2, north: 10)]

        let kept = sampler.process(batch)

        XCTAssertEqual(kept, batch, "Distance mode leaves filtering to Core Location and saveLocationPoints, as before")
        XCTAssertEqual(sampler.powerLevel, .precise)
        XCTAssertNil(sampler.nextPowerUpDate)
    }

    // MARK: - Time mode

    func testTimeModeKeepsOnePointPerIntervalFromOneHertzFixes() {
        var sampler = sampler(.time, interval: 60)
        let fixes = (0..<300).map { fix(t: TimeInterval($0), north: Double($0) * 10) }

        let kept = fixes.flatMap { sampler.process([$0]) }

        XCTAssertEqual(seconds(kept), [0, 60, 120, 180, 240])
    }

    func testTimeModeHandlesBatchedDelivery() {
        var sampler = sampler(.time, interval: 30)
        let batch = (0..<95).map { fix(t: TimeInterval($0), north: Double($0)) }

        XCTAssertEqual(seconds(sampler.process(batch)), [0, 30, 60, 90])
    }

    func testTimeModeKeepsSamplesWhileStationary() {
        var sampler = sampler(.time, interval: 120)
        let fixes = stride(from: 0, through: 600, by: 5).map { fix(t: TimeInterval($0)) }

        let kept = fixes.flatMap { sampler.process([$0]) }

        XCTAssertEqual(seconds(kept), [0, 120, 240, 360, 480, 600])
    }

    func testFixJustBeforeTheIntervalStillCounts() {
        var sampler = sampler(.time, interval: 60)
        _ = sampler.process([fix(t: 0)])

        let early = fix(t: 59.6)
        XCTAssertEqual(sampler.process([early]), [early],
                       "A fix within the timing tolerance shouldn't wait a whole extra second")
        XCTAssertTrue(sampler.process([fix(t: 61)]).isEmpty)
    }

    func testUnusableFixesAreNeverKeptInTimeMode() {
        var sampler = sampler(.time, interval: 10)

        XCTAssertTrue(sampler.process([fix(t: 0, accuracy: -1)]).isEmpty)
        XCTAssertTrue(sampler.process([fix(t: 1, accuracy: 150)]).isEmpty)
        XCTAssertNil(sampler.lastKept)
        XCTAssertEqual(seconds(sampler.process([fix(t: 2)])), [2])
    }

    // MARK: - Precision when a sample is due

    func testRoughDueFixWaitsForAPreciseOne() {
        var sampler = sampler(.time, interval: 120)
        _ = sampler.process([fix(t: 0)])

        XCTAssertTrue(sampler.process([fix(t: 120, accuracy: 65)]).isEmpty,
                      "A Wi-Fi fix shouldn't become the point while the GPS is coming back")
        let precise = fix(t: 124, accuracy: 8)
        XCTAssertEqual(sampler.process([precise]), [precise])
        XCTAssertEqual(sampler.nextSampleDue, precise.timestamp.addingTimeInterval(120))
    }

    func testRoughFixIsAcceptedAfterTheGracePeriod() {
        var sampler = sampler(.time, interval: 120)
        _ = sampler.process([fix(t: 0)])

        XCTAssertTrue(sampler.process([fix(t: 120, accuracy: 65)]).isEmpty)
        XCTAssertTrue(sampler.process([fix(t: 130, accuracy: 60)]).isEmpty)
        let fallback = fix(t: 140, accuracy: 70)
        XCTAssertEqual(sampler.process([fallback]), [fallback],
                       "Indoors or underground, a rough point beats a gap")
    }

    // MARK: - Distance + time

    func testDistanceAndTimeSkipsPointsWhileParked() {
        var sampler = sampler(.distanceAndTime, interval: 60, distance: 25)
        let parked = stride(from: 0, through: 600, by: 10).map { fix(t: TimeInterval($0), north: Double($0 % 20) / 10) }

        let kept = parked.flatMap { sampler.process([$0]) }

        XCTAssertEqual(seconds(kept), [0], "Parked for 10 minutes: only the first point")
    }

    func testDistanceAndTimeNeedsBothTheIntervalAndTheDistance() {
        var sampler = sampler(.distanceAndTime, interval: 60, distance: 25)
        _ = sampler.process([fix(t: 0)])

        XCTAssertTrue(sampler.process([fix(t: 30, north: 200)]).isEmpty, "Far enough, too soon")
        XCTAssertTrue(sampler.process([fix(t: 70, north: 10)]).isEmpty, "Late enough, not far enough")
        let moved = fix(t: 75, north: 40)
        XCTAssertEqual(sampler.process([moved]), [moved])
    }

    func testDistanceAndTimeMeasuresFromTheLastKeptPoint() {
        var sampler = sampler(.distanceAndTime, interval: 10, distance: 25)
        _ = sampler.process([fix(t: 0)])

        // Creeping 10 m per fix: Core Location delivers each, but only every 25 m from the
        // last kept point should be saved.
        let creeping = (1...6).map { fix(t: TimeInterval($0 * 10), north: Double($0 * 10)) }
        let kept = creeping.flatMap { sampler.process([$0]) }

        XCTAssertEqual(seconds(kept), [30, 60])
    }

    // MARK: - Power between samples

    func testLongIntervalsRestTheGPSBetweenSamples() {
        var sampler = sampler(.time, interval: 120)
        XCTAssertEqual(sampler.powerLevel, .precise, "Needs a precise first fix")

        _ = sampler.process([fix(t: 0)])
        XCTAssertEqual(sampler.powerLevel, .low)
        XCTAssertEqual(sampler.nextPowerUpDate, start.addingTimeInterval(120 - RouteSamplingPolicy.warmUpLead))

        _ = sampler.process([fix(t: 100, accuracy: 65)])
        XCTAssertEqual(sampler.powerLevel, .low)

        _ = sampler.process([fix(t: 106, accuracy: 65)])
        XCTAssertEqual(sampler.powerLevel, .precise, "GPS comes back before the sample is due")
        XCTAssertNil(sampler.nextPowerUpDate)

        _ = sampler.process([fix(t: 120)])
        XCTAssertEqual(sampler.powerLevel, .low, "And rests again once the sample is taken")
    }

    func testWakeTimerCanRaisePowerWithoutANewFix() {
        var sampler = sampler(.time, interval: 300)
        _ = sampler.process([fix(t: 0)])
        XCTAssertEqual(sampler.powerLevel, .low)

        sampler.refreshPowerLevel(at: start.addingTimeInterval(290))
        XCTAssertEqual(sampler.powerLevel, .precise)
    }

    func testShortIntervalsKeepTheGPSOn() {
        for interval in [10.0, 30.0] {
            var sampler = sampler(.time, interval: interval)
            _ = sampler.process([fix(t: 0)])
            XCTAssertEqual(sampler.powerLevel, .precise, "\(interval) s is too short to be worth re-acquiring GPS")
            XCTAssertNil(sampler.nextPowerUpDate)
        }
    }

    func testDistanceAndTimeStaysLowWhileParkedAndWakesOnMovement() {
        var sampler = sampler(.distanceAndTime, interval: 120, distance: 25)
        _ = sampler.process([fix(t: 0)])

        _ = sampler.process([fix(t: 300, north: 10, accuracy: 65)])
        XCTAssertEqual(sampler.powerLevel, .low, "Due, but a rough fix this close is just Wi-Fi jitter")
        XCTAssertNil(sampler.nextPowerUpDate, "Waiting on movement, not time")

        _ = sampler.process([fix(t: 310, north: 150, accuracy: 65)])
        XCTAssertEqual(sampler.powerLevel, .precise, "Clearly moved: bring the GPS back for the point")
    }

    // MARK: - Policy and settings changes

    func testCoreLocationDistanceFilterPerMode() {
        XCTAssertEqual(RouteSamplingPolicy(mode: .distance, distanceFilter: 25, interval: 60).coreLocationDistanceFilter, 25)
        XCTAssertEqual(RouteSamplingPolicy(mode: .time, distanceFilter: 25, interval: 60).coreLocationDistanceFilter, kCLDistanceFilterNone)
        XCTAssertEqual(RouteSamplingPolicy(mode: .distanceAndTime, distanceFilter: 25, interval: 60).coreLocationDistanceFilter, 25)
    }

    func testChangingTheIntervalReschedulesTheNextSample() {
        var sampler = sampler(.time, interval: 300)
        _ = sampler.process([fix(t: 0)])

        sampler.policy = RouteSamplingPolicy(mode: .time, distanceFilter: 25, interval: 60)

        XCTAssertEqual(sampler.nextSampleDue, start.addingTimeInterval(60))
        XCTAssertEqual(seconds(sampler.process([fix(t: 61)])), [61])
    }

    func testResetStartsFresh() {
        var sampler = sampler(.time, interval: 120)
        _ = sampler.process([fix(t: 0)])
        sampler.reset()

        XCTAssertNil(sampler.lastKept)
        XCTAssertNil(sampler.nextSampleDue)
        XCTAssertEqual(sampler.powerLevel, .precise)
        XCTAssertEqual(seconds(sampler.process([fix(t: 5)])), [5])
    }

    func testIntervalLabels() {
        XCTAssertEqual(RouteSamplingPolicy.intervalOptions, [10, 30, 60, 120, 300])
        XCTAssertEqual(RouteSamplingPolicy.intervalLabel(10), "10 s")
        XCTAssertEqual(RouteSamplingPolicy.intervalLabel(120), "2 min")
    }
}

/// Route sampling settings on LocationManager: defaults, persistence and recorded sessions.
@MainActor
final class LocationManagerRouteSamplingTests: XCTestCase {

    func testDefaultsKeepExistingDistanceBehaviour() {
        let manager = makeIsolatedLocationManager()
        XCTAssertEqual(manager.routeSamplingMode, .distance)
        XCTAssertEqual(manager.routeSampleInterval, 120)
        XCTAssertEqual(manager.routeSamplingPolicy.coreLocationDistanceFilter, 5)
    }

    func testSamplingSettingsPersist() {
        let defaults = makeIsolatedDefaults()
        let manager = makeIsolatedLocationManager(defaults: defaults)
        manager.setRouteSamplingMode(.distanceAndTime)
        manager.setRouteSampleInterval(300)

        let restored = makeIsolatedLocationManager(defaults: defaults)
        XCTAssertEqual(restored.routeSamplingMode, .distanceAndTime)
        XCTAssertEqual(restored.routeSampleInterval, 300)
    }

    func testPresetSelectionRequiresItsActualDistanceAndAutoStopSettings() {
        let manager = makeIsolatedLocationManager()
        manager.setDistanceFilter(5)
        manager.setStopAfterHours(0)
        XCTAssertFalse(RecordingDetail.everyStep.matches(manager), "Five metres with no timer must not promise a two-hour auto-stop")
        RecordingDetail.everyStep.apply(to: manager)
        XCTAssertTrue(RecordingDetail.everyStep.matches(manager))
        manager.setRouteSamplingMode(.time)
        XCTAssertFalse(RecordingDetail.everyStep.matches(manager))
        RecordingDetail.places.apply(to: manager)
        XCTAssertTrue(RecordingDetail.places.matches(manager))
        manager.setDistanceFilter(200)
        XCTAssertFalse(RecordingDetail.places.matches(manager), "A custom filter must not select the 100 m preset")
    }

    func testChoosingAPresetReturnsToDistanceMode() {
        let manager = makeIsolatedLocationManager()
        manager.setRouteSamplingMode(.time)

        RecordingDetail.routes.apply(to: manager)

        XCTAssertEqual(manager.routeSamplingMode, .distance)
        XCTAssertEqual(manager.distanceFilter, 25)
    }

    func testRecordingSessionRemembersItsSamplingPolicy() {
        let legacy = RecordingSession(startedAt: Date(), distanceFilterMeters: 10)
        XCTAssertEqual(legacy.routeSamplingPolicy?.mode, .distance, "Sessions from before this setting were distance-based")

        let timed = RecordingSession(
            startedAt: Date(),
            distanceFilterMeters: 25,
            routeSamplingModeRawValue: RouteSamplingMode.time.rawValue,
            routeSampleIntervalSeconds: 120
        )
        XCTAssertEqual(timed.routeSamplingPolicy, RouteSamplingPolicy(mode: .time, distanceFilter: 25, interval: 120))
    }
}
