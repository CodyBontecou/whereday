import XCTest
import CoreLocation
import SwiftData
@testable import IsoMe

/// Tests that the LocationManager tracking lifecycle is correct:
/// starting, stopping, and restarting should leave no stale state.
@MainActor
final class LocationManagerTrackingTests: XCTestCase {

    private var manager: LocationManager!
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        // Clear any persisted state from prior runs before instantiating
        // Each test gets its own defaults suite; nothing is read from or written to the host app's.
        defaults = makeIsolatedDefaults()
        manager = makeIsolatedLocationManager(defaults: defaults)
    }

    override func tearDown() {
        manager = nil
        defaults = nil
        super.tearDown()
    }

    // MARK: - stopTracking must fully clean up

    /// stopTracking() must reset isTrackingEnabled and clear the start time
    /// so the next start cycle doesn't see stale state.
    func testStopTrackingClearsState() {
        // Simulate that tracking was previously active
        manager.isTrackingEnabled = true
        manager.trackingStartTime = Date()

        manager.stopTracking()

        XCTAssertFalse(manager.isTrackingEnabled)
        XCTAssertNil(manager.trackingStartTime,
                     "stopTracking must nil out trackingStartTime")
    }

    /// After stopTracking(), the Live Activity manager should not
    /// report an active session.
    func testStopTrackingEndsLiveActivity() async {
        manager.stopTracking()

        // Give any async Task a moment to complete
        try? await Task.sleep(nanoseconds: 200_000_000)

        let liveActivityActive = LiveActivityManager.shared.isActivityActive
        XCTAssertFalse(liveActivityActive,
                       "stopTracking must end the Live Activity")
    }

    // MARK: - Persisted defaults

    /// stopAfterHours defaults to 0 (Never) so a fresh user won't see
    /// surprise auto-stops.
    func testStopAfterHoursDefaultsToNever() {
        XCTAssertEqual(manager.stopAfterHours, 0.0)
    }

    /// Distance filter defaults to 5m (the most aggressive available)
    /// to maximize captured points by default.
    func testDistanceFilterDefaultsToFiveMeters() {
        XCTAssertEqual(manager.distanceFilter, 5.0)
    }

    /// setStopAfterHours persists across reinstantiation.
    func testStopAfterHoursIsPersisted() {
        manager.setStopAfterHours(2.0)
        let fresh = makeIsolatedLocationManager(defaults: defaults)
        XCTAssertEqual(fresh.stopAfterHours, 2.0)
    }

    /// Live Activity monitor defaults to on for existing behavior.
    func testLiveActivityEnabledDefaultsToOn() {
        XCTAssertTrue(manager.isLiveActivityEnabled)
    }

    /// Live Activity monitor preference persists across reinstantiation.
    func testLiveActivityEnabledIsPersisted() {
        manager.setLiveActivityEnabled(false)
        let fresh = makeIsolatedLocationManager(defaults: defaults)
        XCTAssertFalse(fresh.isLiveActivityEnabled)
    }

    /// Core Location may deliver multiple chronological points in one delegate callback.
    /// Tracking must persist every valid point, not just `locations.last`, otherwise
    /// background batches render as long straight-line jumps on the map.
    func testDidUpdateLocationsPersistsEveryValidLocationInBatch() async throws {
        defaults.set(false, forKey: "allowNetworkGeocoding")
        let container = try makeInMemoryContainer()
        manager.setModelContext(container.mainContext)
        manager.isTrackingEnabled = true
        manager.isLiveActivityEnabled = false

        let start = Date()
        let locations = [
            makeLocation(latitude: 18.3270, longitude: -67.2200, timestamp: start),
            makeLocation(latitude: 18.3271, longitude: -67.2201, timestamp: start.addingTimeInterval(1)),
            makeLocation(latitude: 18.3272, longitude: -67.2202, timestamp: start.addingTimeInterval(2))
        ]

        manager.locationManager(CLLocationManager(), didUpdateLocations: locations)
        try await waitForSavedPointCount(3)

        var descriptor = FetchDescriptor<LocationPoint>()
        descriptor.sortBy = [SortDescriptor(\.timestamp, order: .forward)]
        let savedPoints = try container.mainContext.fetch(descriptor)

        XCTAssertEqual(savedPoints.count, 3)
        XCTAssertEqual(savedPoints.map(\.timestamp), locations.map(\.timestamp))
        XCTAssertEqual(manager.locationPointsSavedCount, 3)
        XCTAssertEqual(manager.lastSavedLocationPoints.count, 3)
        XCTAssertEqual(manager.lastSavedLocationPoint?.timestamp, locations.last?.timestamp)
        XCTAssertEqual(manager.currentLocation?.timestamp, locations.last?.timestamp)
    }

    /// Fast travel is real movement: a flight's fixes, each step far over 40 m/s and none coming back, stay
    /// unflagged and their distance counts.
    func testFastTravelIsNotFlaggedAndItsDistanceCounts() async throws {
        defaults.set(false, forKey: "allowNetworkGeocoding")
        let container = try makeInMemoryContainer()
        manager.setModelContext(container.mainContext)
        manager.isTrackingEnabled = true
        manager.isLiveActivityEnabled = false

        // Heading east at cruise: about 0.135° of longitude (~15 km) a minute, about 250 m/s.
        let start = Date()
        let locations = (0..<4).map { step in
            makeLocation(latitude: 40.0, longitude: -100.0 + 0.135 * Double(step),
                         timestamp: start.addingTimeInterval(60 * Double(step)))
        }

        manager.locationManager(CLLocationManager(), didUpdateLocations: locations)
        try await waitForSavedPointCount(4)

        let savedPoints = try savedPointsInTimeOrder(container)
        XCTAssertEqual(savedPoints.count, 4)
        for (previous, next) in zip(savedPoints, savedPoints.dropFirst()) {
            let speed = DayDistance.leg(DistanceFix(previous), DistanceFix(next))
                / next.timestamp.timeIntervalSince(previous.timestamp)
            XCTAssertGreaterThan(speed, 40, "Each step is faster than the old teleport limit")
        }
        XCTAssertEqual(savedPoints.map(\.isOutlier), [false, false, false, false])

        let expected = zip(savedPoints, savedPoints.dropFirst())
            .map { DayDistance.leg(DistanceFix($0), DistanceFix($1)) }
            .reduce(0, +)
        XCTAssertGreaterThan(expected, 30_000)
        XCTAssertEqual(DayDistance.distances(savedPoints).totalMeters, expected, accuracy: 0.01)
    }

    /// The out-and-back spike rule still catches a GPS glitch: a fix that jumps far out and lands back within
    /// 30 m of the fix before it is flagged, and only that fix.
    func testOutAndBackSpikeIsStillFlagged() async throws {
        defaults.set(false, forKey: "allowNetworkGeocoding")
        let container = try makeInMemoryContainer()
        manager.setModelContext(container.mainContext)
        manager.isTrackingEnabled = true
        manager.isLiveActivityEnabled = false

        let start = Date()
        let locations = [
            makeLocation(latitude: 37.7599, longitude: -122.4148, timestamp: start),
            // About 5 km north, then straight back to within a few metres of the first fix.
            makeLocation(latitude: 37.8049, longitude: -122.4148, timestamp: start.addingTimeInterval(30)),
            makeLocation(latitude: 37.7600, longitude: -122.4148, timestamp: start.addingTimeInterval(60))
        ]

        manager.locationManager(CLLocationManager(), didUpdateLocations: locations)
        try await waitForSavedPointCount(3)

        let savedPoints = try savedPointsInTimeOrder(container)
        XCTAssertEqual(savedPoints.map(\.isOutlier), [false, true, false])
        XCTAssertEqual(DayDistance.distances(savedPoints).totalMeters,
                       DayDistance.leg(DistanceFix(savedPoints[0]), DistanceFix(savedPoints[2])), accuracy: 0.01)
    }

    func testReconcilingOpenVisitsLeavesOnlyLatestVisitCurrent() throws {
        let container = try makeInMemoryContainer()
        let context = container.mainContext
        let base = Date(timeIntervalSince1970: 1_800_000_000)

        let staleOpenVisit = Visit(
            latitude: 18.3270,
            longitude: -67.2200,
            arrivedAt: base.addingTimeInterval(-4 * 3600)
        )
        let completedVisitAfterStaleOpenVisit = Visit(
            latitude: 18.3280,
            longitude: -67.2210,
            arrivedAt: base.addingTimeInterval(-3 * 3600),
            departedAt: base.addingTimeInterval(-2 * 3600)
        )
        let latestOpenVisit = Visit(
            latitude: 18.3290,
            longitude: -67.2220,
            arrivedAt: base.addingTimeInterval(-3600)
        )

        context.insert(staleOpenVisit)
        context.insert(completedVisitAfterStaleOpenVisit)
        context.insert(latestOpenVisit)
        try context.save()

        manager.setModelContext(context)

        let openPredicate = #Predicate<Visit> { visit in
            visit.departedAt == nil
        }
        let openVisits = try context.fetch(FetchDescriptor<Visit>(predicate: openPredicate))

        XCTAssertEqual(openVisits.map(\.id), [latestOpenVisit.id])
        XCTAssertEqual(staleOpenVisit.departedAt, completedVisitAfterStaleOpenVisit.arrivedAt)
        XCTAssertNil(latestOpenVisit.departedAt)
    }

    func testReconcilingDuplicateVisitsMergesNearbyStackedPins() throws {
        let container = try makeInMemoryContainer()
        let context = container.mainContext
        let base = Date(timeIntervalSince1970: 1_800_000_000)

        let firstVisit = Visit(
            latitude: 18.3270,
            longitude: -67.2200,
            arrivedAt: base,
            departedAt: base.addingTimeInterval(10 * 60),
            locationName: "Puerto Rico"
        )
        let duplicateVisit = Visit(
            latitude: 18.3272,
            longitude: -67.2201,
            arrivedAt: base.addingTimeInterval(2 * 60),
            departedAt: base.addingTimeInterval(15 * 60),
            address: "Puerto Rico"
        )

        context.insert(firstVisit)
        context.insert(duplicateVisit)
        try context.save()

        manager.setModelContext(context)

        let visits = try context.fetch(FetchDescriptor<Visit>())

        XCTAssertEqual(visits.count, 1)
        XCTAssertEqual(visits.first?.arrivedAt, firstVisit.arrivedAt)
        XCTAssertEqual(visits.first?.departedAt, duplicateVisit.departedAt)
        XCTAssertEqual(visits.first?.locationName, "Puerto Rico")
    }

    func testReconcilingDuplicateOpenAndCompletedVisitKeepsOneCurrentVisit() throws {
        let container = try makeInMemoryContainer()
        let context = container.mainContext
        let base = Date(timeIntervalSince1970: 1_800_000_000)

        let completedVisit = Visit(
            latitude: 18.3270,
            longitude: -67.2200,
            arrivedAt: base,
            departedAt: base.addingTimeInterval(10 * 60)
        )
        let openDuplicateVisit = Visit(
            latitude: 18.3271,
            longitude: -67.2201,
            arrivedAt: base.addingTimeInterval(2 * 60)
        )

        context.insert(completedVisit)
        context.insert(openDuplicateVisit)
        try context.save()

        manager.setModelContext(context)

        let visits = try context.fetch(FetchDescriptor<Visit>())

        XCTAssertEqual(visits.count, 1)
        XCTAssertNil(visits.first?.departedAt)
    }

    func testReconcilingVisitsDoesNotMergeSeparateReturnsToSamePlace() throws {
        let container = try makeInMemoryContainer()
        let context = container.mainContext
        let base = Date(timeIntervalSince1970: 1_800_000_000)

        let morningVisit = Visit(
            latitude: 18.3270,
            longitude: -67.2200,
            arrivedAt: base,
            departedAt: base.addingTimeInterval(10 * 60)
        )
        let afternoonVisit = Visit(
            latitude: 18.3271,
            longitude: -67.2201,
            arrivedAt: base.addingTimeInterval(2 * 3600),
            departedAt: base.addingTimeInterval(2 * 3600 + 10 * 60)
        )

        context.insert(morningVisit)
        context.insert(afternoonVisit)
        try context.save()

        manager.setModelContext(context)

        let visits = try context.fetch(FetchDescriptor<Visit>())

        XCTAssertEqual(visits.count, 2)
    }

    func testReconcilingTrackingStateCreatesAndClosesRecordingSession() throws {
        let container = try makeInMemoryContainer()
        let context = container.mainContext
        let base = Date(timeIntervalSince1970: 1_800_000_000)

        manager.isTrackingEnabled = true
        manager.trackingStartTime = base
        manager.setModelContext(context)

        var descriptor = FetchDescriptor<RecordingSession>()
        descriptor.sortBy = [SortDescriptor(\.startedAt, order: .forward)]
        var sessions = try context.fetch(descriptor)

        XCTAssertEqual(sessions.count, 1)
        XCTAssertEqual(sessions.first?.startedAt, base)
        XCTAssertNil(sessions.first?.endedAt)

        let end = base.addingTimeInterval(2 * 3600)
        manager.isTrackingEnabled = false
        XCTAssertEqual(manager.reconcileRecordingSessions(referenceDate: end), 1)

        sessions = try context.fetch(descriptor)
        XCTAssertEqual(sessions.count, 1)
        XCTAssertEqual(sessions.first?.startedAt, base)
        XCTAssertEqual(sessions.first?.endedAt, end)
    }

    private func savedPointsInTimeOrder(_ container: ModelContainer) throws -> [LocationPoint] {
        var descriptor = FetchDescriptor<LocationPoint>()
        descriptor.sortBy = [SortDescriptor(\.timestamp, order: .forward)]
        return try container.mainContext.fetch(descriptor)
    }

    private func makeInMemoryContainer() throws -> ModelContainer {
        let schema = Schema([Visit.self, LocationPoint.self, RecordingSession.self, PhotoMoment.self, SavedPlace.self, CountryStay.self])
        let configuration = ModelConfiguration(
            schema: schema,
            isStoredInMemoryOnly: true,
            allowsSave: true
        )
        return try ModelContainer(for: schema, configurations: [configuration])
    }

    private func makeLocation(latitude: Double, longitude: Double, timestamp: Date) -> CLLocation {
        CLLocation(
            coordinate: CLLocationCoordinate2D(latitude: latitude, longitude: longitude),
            altitude: 0,
            horizontalAccuracy: 5,
            verticalAccuracy: 5,
            course: -1,
            speed: 10,
            timestamp: timestamp
        )
    }

    private func waitForSavedPointCount(_ count: Int, timeout: TimeInterval = 1) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while manager.locationPointsSavedCount < count {
            if Date() >= deadline {
                XCTFail("Timed out waiting for saved location points")
                return
            }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
    }
}
