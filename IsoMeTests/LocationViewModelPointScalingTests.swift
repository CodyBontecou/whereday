import XCTest
import CoreLocation
import MapKit
import SwiftData
@testable import IsoMe

@MainActor
final class LocationViewModelPointScalingTests: XCTestCase {
    private let mapPointDisplayCap = 2_500

    override func setUp() {
        super.setUp()
        resetUserDefaults()
    }

    override func tearDown() {
        resetUserDefaults()
        super.tearDown()
    }

    func testMapRangeDownsamplesLargePointHistoryAndLeavesExportCacheLazy() throws {
        let container = try makeInMemoryContainer()
        let start = fixtureDate(dayOffset: 0)
        let pointCount = 12_500
        let lastTimestamp = try insertPointHistory(
            count: pointCount,
            startingAt: start,
            interval: 1,
            into: container.mainContext
        )

        let viewModel = makeViewModel(container: container)
        let range = start...lastTimestamp
        viewModel.mapDateRange = range
        viewModel.loadMapLocationPoints(in: range)

        XCTAssertEqual(viewModel.totalLocationPointCount, pointCount)
        XCTAssertEqual(viewModel.mapLocationPointCount, pointCount)
        XCTAssertLessThanOrEqual(viewModel.mapLocationPoints.count, mapPointDisplayCap)
        XCTAssertGreaterThan(viewModel.mapLocationPoints.count, 0)
        XCTAssertEqual(
            viewModel.locationPoints.count,
            0,
            "Opening the map must not hydrate the full point history; export loads it on demand."
        )

        assertSortedByTimestamp(viewModel.mapLocationPoints)
        XCTAssertEqual(
            try XCTUnwrap(viewModel.mapLocationPoints.first?.timestamp.timeIntervalSince1970),
            start.timeIntervalSince1970,
            accuracy: 0.001
        )
        XCTAssertEqual(
            try XCTUnwrap(viewModel.mapLocationPoints.last?.timestamp.timeIntervalSince1970),
            lastTimestamp.timeIntervalSince1970,
            accuracy: 0.001
        )
    }

    func testEnsureAllLocationPointsLoadedPreservesFullResolutionForExport() throws {
        let container = try makeInMemoryContainer()
        let start = fixtureDate(dayOffset: 1)
        let pointCount = 750
        let lastTimestamp = try insertPointHistory(
            count: pointCount,
            startingAt: start,
            interval: 5,
            into: container.mainContext
        )

        let viewModel = makeViewModel(container: container)
        XCTAssertEqual(viewModel.locationPoints.count, 0, "Full-resolution points should start lazy.")

        viewModel.ensureAllLocationPointsLoaded()

        XCTAssertEqual(viewModel.locationPoints.count, pointCount)
        XCTAssertEqual(viewModel.totalLocationPointCount, pointCount)
        assertSortedByTimestamp(viewModel.locationPoints)
        XCTAssertEqual(
            try XCTUnwrap(viewModel.locationPoints.first?.timestamp.timeIntervalSince1970),
            start.timeIntervalSince1970,
            accuracy: 0.001
        )
        XCTAssertEqual(
            try XCTUnwrap(viewModel.locationPoints.last?.timestamp.timeIntervalSince1970),
            lastTimestamp.timeIntervalSince1970,
            accuracy: 0.001
        )
    }

    func testMapReloadCountsOnlySelectedDateRange() throws {
        let container = try makeInMemoryContainer()
        let firstDay = fixtureDate(dayOffset: 2)
        let secondDay = fixtureDate(dayOffset: 3)

        _ = try insertPointHistory(count: 5, startingAt: firstDay, interval: 60, into: container.mainContext)
        let secondDayLast = try insertPointHistory(count: 7, startingAt: secondDay, interval: 60, into: container.mainContext)

        let viewModel = makeViewModel(container: container)
        let range = secondDay...secondDayLast
        viewModel.mapDateRange = range
        viewModel.loadMapLocationPoints(in: range)

        XCTAssertEqual(viewModel.totalLocationPointCount, 12)
        XCTAssertEqual(viewModel.mapLocationPointCount, 7)
        XCTAssertEqual(viewModel.mapLocationPoints.count, 7)
        XCTAssertTrue(viewModel.mapLocationPoints.allSatisfy { range.contains($0.timestamp) })
        assertSortedByTimestamp(viewModel.mapLocationPoints)
    }

    func testManualVisitCreationAndTimeEditing() throws {
        let container = try makeInMemoryContainer()
        let viewModel = makeViewModel(container: container)
        let arrivedAt = fixtureDate(dayOffset: 19)
        let departedAt = arrivedAt.addingTimeInterval(45 * 60)
        let coordinate = CLLocationCoordinate2D(latitude: 37.7, longitude: -122.4)

        let visit = try XCTUnwrap(viewModel.createManualVisit(
            name: "Manual Cafe",
            address: "1 Manual Way",
            coordinate: coordinate,
            arrivedAt: arrivedAt,
            departedAt: departedAt
        ))

        XCTAssertEqual(visit.source, .manual)
        XCTAssertEqual(visit.confirmationStatus, .confirmed)
        XCTAssertEqual(visit.placeSource, .userEntered)
        XCTAssertEqual(visit.locationName, "Manual Cafe")
        XCTAssertEqual(visit.address, "1 Manual Way")
        assertCoordinate(visit.coordinate, equals: coordinate)
        XCTAssertEqual(viewModel.allVisits.count, 1)

        let invalidResult = viewModel.updateVisitTimes(
            visit,
            arrivedAt: departedAt,
            departedAt: arrivedAt
        )
        XCTAssertFalse(invalidResult)
        XCTAssertEqual(visit.arrivedAt, arrivedAt)
        XCTAssertEqual(visit.departedAt, departedAt)

        let newArrival = arrivedAt.addingTimeInterval(-30 * 60)
        XCTAssertTrue(viewModel.updateVisitTimes(visit, arrivedAt: newArrival, departedAt: nil))
        XCTAssertEqual(visit.arrivedAt, newArrival)
        XCTAssertNil(visit.departedAt)
    }

    func testRemoteManualVisitPersistsAppleMapsSelection() throws {
        let container = try makeInMemoryContainer()
        let viewModel = makeViewModel(container: container)
        let arrivedAt = fixtureDate(dayOffset: 21)
        let departedAt = arrivedAt.addingTimeInterval(90 * 60)
        let coordinate = CLLocationCoordinate2D(latitude: 48.8584, longitude: 2.2945)

        let visit = try XCTUnwrap(viewModel.createManualVisit(
            name: "Eiffel Tower",
            address: "5 Avenue Anatole France, Paris",
            coordinate: coordinate,
            arrivedAt: arrivedAt,
            departedAt: departedAt,
            placeSource: .appleMaps
        ))

        XCTAssertEqual(visit.source, .manual)
        XCTAssertEqual(visit.confirmationStatus, .confirmed)
        XCTAssertEqual(visit.placeSource, .appleMaps)
        assertCoordinate(visit.coordinate, equals: coordinate)
    }

    func testManualVisitRejectsInvalidRemoteCoordinate() throws {
        let container = try makeInMemoryContainer()
        let viewModel = makeViewModel(container: container)
        let arrivedAt = fixtureDate(dayOffset: 22)

        let visit = viewModel.createManualVisit(
            name: "Invalid Place",
            address: nil,
            coordinate: CLLocationCoordinate2D(latitude: 200, longitude: -122.4),
            arrivedAt: arrivedAt,
            departedAt: arrivedAt.addingTimeInterval(60)
        )

        XCTAssertNil(visit)
        XCTAssertTrue(viewModel.allVisits.isEmpty)
    }

    func testConfirmingVisitRemembersPlaceForFutureAutomaticVisits() throws {
        let container = try makeInMemoryContainer()
        let coordinate = CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194)
        let visit = Visit(
            latitude: coordinate.latitude,
            longitude: coordinate.longitude,
            arrivedAt: fixtureDate(dayOffset: 20),
            locationName: "Detected Cafe",
            address: "1 Market St",
            geocodingCompleted: true
        )
        container.mainContext.insert(visit)
        try container.mainContext.save()

        let viewModel = makeViewModel(container: container)
        viewModel.confirmVisit(visit)

        let savedPlace = try XCTUnwrap(viewModel.savedPlaces.first)
        XCTAssertEqual(savedPlace.name, "Detected Cafe")
        XCTAssertEqual(savedPlace.address, "1 Market St")
        assertCoordinate(savedPlace.coordinate, equals: coordinate)
        XCTAssertTrue(savedPlace.contains(coordinate))
    }

    func testCorrectingVisitRemembersSelectedPlace() throws {
        let container = try makeInMemoryContainer()
        let originalCoordinate = CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194)
        let correctedCoordinate = CLLocationCoordinate2D(latitude: 37.7755, longitude: -122.4188)
        let visit = Visit(
            latitude: originalCoordinate.latitude,
            longitude: originalCoordinate.longitude,
            arrivedAt: fixtureDate(dayOffset: 20),
            locationName: "Detected Block",
            address: "Market St",
            geocodingCompleted: true
        )
        container.mainContext.insert(visit)
        try container.mainContext.save()

        let viewModel = makeViewModel(container: container)
        viewModel.correctVisit(
            visit,
            name: "Correct Cafe",
            address: "1 Correct Way",
            coordinate: correctedCoordinate,
            placeSource: .appleMaps,
            distanceMeters: 42
        )

        let savedPlace = try XCTUnwrap(viewModel.savedPlaces.first)
        XCTAssertEqual(savedPlace.name, "Correct Cafe")
        XCTAssertEqual(savedPlace.address, "1 Correct Way")
        assertCoordinate(savedPlace.coordinate, equals: correctedCoordinate)
        XCTAssertTrue(savedPlace.contains(correctedCoordinate))
    }

    func testVisitConfirmCorrectAndUndoPreservesOriginalMetadata() throws {
        let container = try makeInMemoryContainer()
        let originalCoordinate = CLLocationCoordinate2D(latitude: 37.7749, longitude: -122.4194)
        let correctedCoordinate = CLLocationCoordinate2D(latitude: 37.7755, longitude: -122.4188)
        let visit = Visit(
            latitude: originalCoordinate.latitude,
            longitude: originalCoordinate.longitude,
            arrivedAt: fixtureDate(dayOffset: 20),
            locationName: "Detected Block",
            address: "Market St",
            geocodingCompleted: true
        )
        container.mainContext.insert(visit)
        try container.mainContext.save()

        let viewModel = makeViewModel(container: container)
        XCTAssertEqual(visit.confirmationStatus, .unconfirmed)
        XCTAssertEqual(visit.source, .automatic)

        viewModel.confirmVisit(visit)
        XCTAssertEqual(visit.confirmationStatus, .confirmed)
        XCTAssertNotNil(visit.confirmedAt)

        viewModel.correctVisit(
            visit,
            name: "Correct Cafe",
            address: "1 Correct Way",
            coordinate: correctedCoordinate,
            placeSource: .appleMaps,
            distanceMeters: 42
        )

        XCTAssertEqual(visit.confirmationStatus, .corrected)
        XCTAssertEqual(visit.displayName, "Correct Cafe")
        XCTAssertEqual(visit.address, "1 Correct Way")
        XCTAssertEqual(visit.placeSource, .appleMaps)
        XCTAssertEqual(visit.placeDistanceMeters, 42)
        assertCoordinate(visit.originalCoordinate, equals: originalCoordinate)
        XCTAssertEqual(visit.originalLocationName, "Detected Block")
        XCTAssertEqual(visit.originalAddress, "Market St")
        XCTAssertEqual(visit.latitude, correctedCoordinate.latitude, accuracy: 0.000001)
        XCTAssertEqual(visit.longitude, correctedCoordinate.longitude, accuracy: 0.000001)

        viewModel.undoVisitCorrection(visit)

        XCTAssertEqual(visit.confirmationStatus, .unconfirmed)
        XCTAssertNil(visit.confirmedAt)
        XCTAssertEqual(visit.locationName, "Detected Block")
        XCTAssertEqual(visit.address, "Market St")
        assertCoordinate(visit.coordinate, equals: originalCoordinate)
    }

    func testLiveBatchAppendAddsEveryPointToMapAndSessionCaches() async throws {
        UserDefaults.standard.set(false, forKey: "allowNetworkGeocoding")
        let container = try makeInMemoryContainer()
        let manager = LocationManager()
        let start = Date()
        manager.isTrackingEnabled = true
        manager.isLiveActivityEnabled = false
        manager.trackingStartTime = start.addingTimeInterval(-1)

        let viewModel = LocationViewModel(modelContext: container.mainContext, locationManager: manager)
        let range = start.addingTimeInterval(-1)...start.addingTimeInterval(10)
        viewModel.mapDateRange = range
        viewModel.loadMapLocationPoints(in: range)

        let locations = [
            makeLocation(latitude: 18.3270, longitude: -67.2200, timestamp: start),
            makeLocation(latitude: 18.3271, longitude: -67.2201, timestamp: start.addingTimeInterval(1)),
            makeLocation(latitude: 18.3272, longitude: -67.2202, timestamp: start.addingTimeInterval(2))
        ]

        manager.locationManager(CLLocationManager(), didUpdateLocations: locations)
        try await waitUntil { viewModel.mapLocationPoints.count == 3 }

        XCTAssertEqual(viewModel.totalLocationPointCount, 3)
        XCTAssertEqual(viewModel.mapLocationPointCount, 3)
        XCTAssertEqual(viewModel.mapLocationPoints.count, 3)
        XCTAssertEqual(viewModel.todayLocationPoints.count, 3)
        XCTAssertEqual(viewModel.sessionLocationPoints.count, 3)
        XCTAssertEqual(viewModel.mapLocationPoints.map(\.timestamp), locations.map(\.timestamp))
    }

    func testRecordingSessionBuilderSplitsLegacyPointHistoryByQuietGaps() throws {
        let start = fixtureDate(dayOffset: 4)
        let points = [
            LocationPoint(latitude: 37.0000, longitude: -122.0000, timestamp: start, horizontalAccuracy: 5),
            LocationPoint(latitude: 37.0010, longitude: -122.0000, timestamp: start.addingTimeInterval(60), horizontalAccuracy: 5),
            LocationPoint(latitude: 37.0100, longitude: -122.0100, timestamp: start.addingTimeInterval(45 * 60), horizontalAccuracy: 5),
            LocationPoint(latitude: 37.0110, longitude: -122.0100, timestamp: start.addingTimeInterval(46 * 60), horizontalAccuracy: 5)
        ]

        let sessions = RecordingSessionBuilder.summaries(
            storedSessions: [],
            points: points,
            activeTrackingStart: nil,
            gapThreshold: 30 * 60,
            now: start.addingTimeInterval(47 * 60)
        )

        XCTAssertEqual(sessions.count, 2)
        XCTAssertTrue(sessions.allSatisfy(\.isInferred))
        XCTAssertEqual(sessions[0].sequenceNumber, 1)
        XCTAssertEqual(sessions[0].points.map(\.timestamp), [points[0].timestamp, points[1].timestamp])
        XCTAssertEqual(sessions[1].sequenceNumber, 2)
        XCTAssertEqual(sessions[1].points.map(\.timestamp), [points[2].timestamp, points[3].timestamp])
        XCTAssertEqual(RecordingSessionSort.newest.sorted(sessions).map(\.sequenceNumber), [2, 1])
    }

    func testRecordingSessionBuilderPrefersStoredSessionRanges() throws {
        let start = fixtureDate(dayOffset: 5)
        let storedSession = RecordingSession(
            id: UUID(uuidString: "00000000-0000-0000-0000-000000000123")!,
            startedAt: start,
            endedAt: start.addingTimeInterval(90 * 60),
            customName: "Airport Run"
        )
        let points = [
            LocationPoint(latitude: 37.0000, longitude: -122.0000, timestamp: start.addingTimeInterval(60), horizontalAccuracy: 5),
            LocationPoint(latitude: 37.0010, longitude: -122.0000, timestamp: start.addingTimeInterval(45 * 60), horizontalAccuracy: 5),
            LocationPoint(latitude: 37.0020, longitude: -122.0000, timestamp: start.addingTimeInterval(89 * 60), horizontalAccuracy: 5)
        ]

        let sessions = RecordingSessionBuilder.summaries(
            storedSessions: [storedSession],
            points: points,
            activeTrackingStart: nil,
            gapThreshold: 30 * 60,
            now: start.addingTimeInterval(2 * 3600)
        )

        XCTAssertEqual(sessions.count, 1)
        let session = try XCTUnwrap(sessions.first)
        XCTAssertFalse(session.isInferred)
        XCTAssertEqual(session.title, "Airport Run")
        XCTAssertEqual(session.startedAt, storedSession.startedAt)
        XCTAssertEqual(session.effectiveEndDate, try XCTUnwrap(storedSession.endedAt))
        XCTAssertEqual(session.points.count, 3)
    }

    func testDeletingStoredOutingRemovesSessionPointsAndKeepsVisits() throws {
        let container = try makeInMemoryContainer()
        let context = container.mainContext
        let start = fixtureDate(dayOffset: 6)
        let end = start.addingTimeInterval(30 * 60)
        let storedSession = RecordingSession(startedAt: start, endedAt: end, customName: "Errand Run")
        let beforePoint = LocationPoint(
            latitude: 37.0,
            longitude: -122.0,
            timestamp: start.addingTimeInterval(-60 * 60),
            horizontalAccuracy: 5
        )
        let outingPoint = LocationPoint(
            latitude: 37.1,
            longitude: -122.1,
            timestamp: start.addingTimeInterval(10 * 60),
            horizontalAccuracy: 5
        )
        let afterPoint = LocationPoint(
            latitude: 37.2,
            longitude: -122.2,
            timestamp: end.addingTimeInterval(60 * 60),
            horizontalAccuracy: 5
        )
        let visit = Visit(
            latitude: 37.1,
            longitude: -122.1,
            arrivedAt: start.addingTimeInterval(5 * 60),
            departedAt: start.addingTimeInterval(15 * 60)
        )

        context.insert(storedSession)
        context.insert(beforePoint)
        context.insert(outingPoint)
        context.insert(afterPoint)
        context.insert(visit)
        try context.save()

        let viewModel = makeViewModel(container: container)
        viewModel.ensureAllLocationPointsLoaded()
        let summary = try XCTUnwrap(
            viewModel.recordingSessionSummaries().first { $0.storedSession?.id == storedSession.id }
        )

        viewModel.deleteRecordingSession(summary)

        XCTAssertEqual(try context.fetchCount(FetchDescriptor<RecordingSession>()), 0)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<LocationPoint>()), 2)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Visit>()), 1)
        XCTAssertEqual(Set(viewModel.locationPoints.map(\.id)), Set([beforePoint.id, afterPoint.id]))
        XCTAssertEqual(viewModel.totalLocationPointCount, 2)
        XCTAssertEqual(viewModel.allVisits.map(\.id), [visit.id])
    }

    func testDeletingActiveOutingStopsTrackingAndIncludesNewPoints() throws {
        let container = try makeInMemoryContainer()
        let context = container.mainContext
        let manager = LocationManager()
        let start = Date().addingTimeInterval(-5 * 60)
        manager.isTrackingEnabled = true
        manager.isLiveActivityEnabled = false
        manager.trackingStartTime = start

        let viewModel = LocationViewModel(modelContext: context, locationManager: manager)
        context.insert(LocationPoint(
            latitude: 37.0,
            longitude: -122.0,
            timestamp: start.addingTimeInterval(30),
            horizontalAccuracy: 5
        ))
        try context.save()
        viewModel.loadLocationPoints()

        let summary = try XCTUnwrap(
            viewModel.recordingSessionSummaries().first { !$0.isInferred && $0.isActive }
        )

        // Simulate a fix saved while the detail screen still holds its original summary.
        context.insert(LocationPoint(
            latitude: 37.001,
            longitude: -122.001,
            timestamp: Date().addingTimeInterval(-1),
            horizontalAccuracy: 5
        ))
        try context.save()

        viewModel.deleteRecordingSession(summary)

        XCTAssertFalse(manager.isTrackingEnabled)
        XCTAssertNil(manager.trackingStartTime)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<RecordingSession>()), 0)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<LocationPoint>()), 0)
        XCTAssertTrue(viewModel.recordingSessionSummaries().isEmpty)
    }

    func testDeletingInferredOutingRemovesItsPointsSoItDoesNotReappear() throws {
        let container = try makeInMemoryContainer()
        let context = container.mainContext
        let start = fixtureDate(dayOffset: 7)
        context.insert(LocationPoint(
            latitude: 37.0,
            longitude: -122.0,
            timestamp: start,
            horizontalAccuracy: 5
        ))
        context.insert(LocationPoint(
            latitude: 37.001,
            longitude: -122.001,
            timestamp: start.addingTimeInterval(60),
            horizontalAccuracy: 5
        ))
        try context.save()

        let viewModel = makeViewModel(container: container)
        viewModel.ensureAllLocationPointsLoaded()
        let summary = try XCTUnwrap(viewModel.recordingSessionSummaries().first)
        XCTAssertTrue(summary.isInferred)

        viewModel.deleteRecordingSession(summary)

        XCTAssertEqual(try context.fetchCount(FetchDescriptor<LocationPoint>()), 0)
        XCTAssertEqual(viewModel.totalLocationPointCount, 0)
        XCTAssertTrue(viewModel.locationPoints.isEmpty)
        XCTAssertTrue(viewModel.recordingSessionSummaries().isEmpty)
    }

    func testRecordingSessionBuilderCanDisableInferredOutings() throws {
        let start = fixtureDate(dayOffset: 6)
        let points = [
            LocationPoint(latitude: 37.0000, longitude: -122.0000, timestamp: start, horizontalAccuracy: 5),
            LocationPoint(latitude: 37.0010, longitude: -122.0000, timestamp: start.addingTimeInterval(60), horizontalAccuracy: 5)
        ]

        let sessions = RecordingSessionBuilder.summaries(
            storedSessions: [],
            points: points,
            activeTrackingStart: nil,
            inferenceConfiguration: RecordingSessionInferenceConfiguration(includesInferredSessions: false),
            now: start.addingTimeInterval(10 * 60)
        )

        XCTAssertTrue(sessions.isEmpty)
    }

    func testRecordingSessionBuilderFiltersInferredOutingsByUserConfiguration() throws {
        let start = fixtureDate(dayOffset: 7)
        let points = [
            LocationPoint(latitude: 37.0000, longitude: -122.0000, timestamp: start, horizontalAccuracy: 5),
            LocationPoint(latitude: 37.0010, longitude: -122.0000, timestamp: start.addingTimeInterval(60), horizontalAccuracy: 5),
            LocationPoint(latitude: 37.0100, longitude: -122.0100, timestamp: start.addingTimeInterval(45 * 60), horizontalAccuracy: 5),
            LocationPoint(latitude: 37.0110, longitude: -122.0100, timestamp: start.addingTimeInterval(55 * 60), horizontalAccuracy: 5),
            LocationPoint(latitude: 37.0120, longitude: -122.0100, timestamp: start.addingTimeInterval(65 * 60), horizontalAccuracy: 5)
        ]

        let sessions = RecordingSessionBuilder.summaries(
            storedSessions: [],
            points: points,
            activeTrackingStart: nil,
            inferenceConfiguration: RecordingSessionInferenceConfiguration(
                gapThreshold: 30 * 60,
                minimumDuration: 10 * 60,
                minimumPointCount: 3
            ),
            now: start.addingTimeInterval(70 * 60)
        )

        XCTAssertEqual(sessions.count, 1)
        let session = try XCTUnwrap(sessions.first)
        XCTAssertTrue(session.isInferred)
        XCTAssertEqual(session.sequenceNumber, 1)
        XCTAssertEqual(session.points.map(\.timestamp), [points[2].timestamp, points[3].timestamp, points[4].timestamp])
    }

    func testRecordingSessionBuilderKeepsStoredSessionsWhenInferredOutingsAreDisabled() throws {
        let start = fixtureDate(dayOffset: 8)
        let storedSession = RecordingSession(
            id: UUID(uuidString: "00000000-0000-0000-0000-000000000456")!,
            startedAt: start,
            endedAt: start.addingTimeInterval(30 * 60),
            customName: "School Run"
        )
        let points = [
            LocationPoint(latitude: 37.0000, longitude: -122.0000, timestamp: start.addingTimeInterval(60), horizontalAccuracy: 5),
            LocationPoint(latitude: 38.0000, longitude: -123.0000, timestamp: start.addingTimeInterval(2 * 3600), horizontalAccuracy: 5)
        ]

        let sessions = RecordingSessionBuilder.summaries(
            storedSessions: [storedSession],
            points: points,
            activeTrackingStart: nil,
            inferenceConfiguration: RecordingSessionInferenceConfiguration(includesInferredSessions: false),
            now: start.addingTimeInterval(3 * 3600)
        )

        XCTAssertEqual(sessions.count, 1)
        let session = try XCTUnwrap(sessions.first)
        XCTAssertFalse(session.isInferred)
        XCTAssertEqual(session.title, "School Run")
        XCTAssertEqual(session.points.map(\.timestamp), [points[0].timestamp])
    }

    func testRouteReplaySnapshotMapsProgressToVisiblePathAndMetrics() throws {
        let start = fixtureDate(dayOffset: 9)
        let points = [
            LocationPoint(latitude: 37.0000, longitude: -122.0000, timestamp: start, horizontalAccuracy: 5),
            LocationPoint(latitude: 37.0010, longitude: -122.0000, timestamp: start.addingTimeInterval(60), horizontalAccuracy: 5),
            LocationPoint(latitude: 37.0010, longitude: -122.0010, timestamp: start.addingTimeInterval(180), horizontalAccuracy: 5)
        ]

        let snapshot = try XCTUnwrap(RouteReplayCalculator.snapshot(points: points, progress: 0.5))

        XCTAssertEqual(snapshot.index, 1)
        XCTAssertEqual(snapshot.progress, 0.5, accuracy: 0.001)
        XCTAssertEqual(snapshot.totalPointCount, 3)
        XCTAssertEqual(snapshot.visiblePoints.count, 2)
        XCTAssertEqual(snapshot.currentPoint.timestamp, points[1].timestamp)
        XCTAssertEqual(snapshot.elapsedDuration, 60, accuracy: 0.001)
        XCTAssertEqual(snapshot.totalDuration, 180, accuracy: 0.001)
        XCTAssertEqual(snapshot.distanceMeters, points[0].distance(to: points[1]), accuracy: 0.01)
        XCTAssertEqual(
            snapshot.totalDistanceMeters,
            points[0].distance(to: points[1]) + points[1].distance(to: points[2]),
            accuracy: 0.01
        )
    }

    func testRouteReplayCalculatorClampsProgressAndHandlesShortRoutes() throws {
        let start = fixtureDate(dayOffset: 7)
        let point = LocationPoint(latitude: 37.0, longitude: -122.0, timestamp: start, horizontalAccuracy: 5)

        XCTAssertNil(RouteReplayCalculator.snapshot(points: [], progress: 0.5))
        XCTAssertNil(RouteReplayCalculator.snapshot(points: [point], progress: 0.5))
        XCTAssertEqual(RouteReplayCalculator.clampedProgress(-0.25), 0)
        XCTAssertEqual(RouteReplayCalculator.clampedProgress(1.25), 1)
        XCTAssertEqual(RouteReplayCalculator.index(for: 1.25, pointCount: 4), 3)
        XCTAssertEqual(RouteReplayCalculator.progress(forIndex: 10, pointCount: 4), 1)
        XCTAssertEqual(RouteReplayCalculator.playbackStepSize(pointCount: 2), 1)
        XCTAssertGreaterThanOrEqual(RouteReplayCalculator.playbackStepSize(pointCount: 2_500), 2)
    }

    func testDisabledRoadSnappingDoesNotEvaluatePointQuery() {
        var queryCount = 0
        func query() -> [LocationPoint] {
            queryCount += 1
            return []
        }
        _ = RoadSnappedRouteBuilder.taskFingerprint(for: query(), isEnabled: false)
        XCTAssertEqual(queryCount, 0)
        _ = RoadSnappedRouteBuilder.taskFingerprint(for: query(), isEnabled: true)
        XCTAssertEqual(queryCount, 1)
    }

    func testDisabledRoadSnappingTaskDoesNotRestartWhenPointDataChanges() {
        let point = LocationPoint(latitude: 37, longitude: -122, timestamp: Date(timeIntervalSince1970: 1_790_000_000), horizontalAccuracy: 5)
        let disabled = RoadSnappedRouteBuilder.taskFingerprint(for: [point], isEnabled: false)
        let enabled = RoadSnappedRouteBuilder.taskFingerprint(for: [point], isEnabled: true)
        point.latitude += 0.001
        point.isOutlier = true
        XCTAssertEqual(disabled, RoadSnappedRouteBuilder.taskFingerprint(for: [point], isEnabled: false))
        XCTAssertEqual(disabled, RoadSnappedRouteBuilder.taskFingerprint(for: [], isEnabled: false))
        XCTAssertNotEqual(enabled, RoadSnappedRouteBuilder.taskFingerprint(for: [point], isEnabled: true))
        XCTAssertNotEqual(disabled, enabled)
    }

    func testRoadSnappedRouteBuilderCoalescesShortSegmentsWithoutDirections() async throws {
        let start = fixtureDate(dayOffset: 8)
        let points = [
            LocationPoint(latitude: 37.00000, longitude: -122.00000, timestamp: start, horizontalAccuracy: 5),
            LocationPoint(latitude: 37.00005, longitude: -122.00005, timestamp: start.addingTimeInterval(10), horizontalAccuracy: 5),
            LocationPoint(latitude: 37.00010, longitude: -122.00010, timestamp: start.addingTimeInterval(20), horizontalAccuracy: 5)
        ]

        let route = await RoadSnappedRouteBuilder.buildRoute(
            for: points.map { RoadSnappingPoint(point: $0) },
            sourceFingerprint: 42
        )

        XCTAssertEqual(route.sourceFingerprint, 42)
        XCTAssertEqual(route.sourcePointCount, points.count)
        XCTAssertFalse(route.hasSnappedSegments)
        XCTAssertEqual(route.segments.count, 1)

        let segment = try XCTUnwrap(route.segments.first)
        XCTAssertEqual(segment.startIndex, 0)
        XCTAssertEqual(segment.endIndex, 2)
        XCTAssertEqual(segment.coordinates.count, 3)
        assertCoordinate(segment.coordinates[0], equals: points[0].coordinate)
        assertCoordinate(segment.coordinates[2], equals: points[2].coordinate)
    }

    func testRoadSnappedRouteClipsCoalescedRawSegmentForReplayProgress() throws {
        let coordinates = [
            CLLocationCoordinate2D(latitude: 37.0000, longitude: -122.0000),
            CLLocationCoordinate2D(latitude: 37.0001, longitude: -122.0001),
            CLLocationCoordinate2D(latitude: 37.0002, longitude: -122.0002),
            CLLocationCoordinate2D(latitude: 37.0003, longitude: -122.0003)
        ]
        let route = RoadSnappedRoute(
            sourceFingerprint: 7,
            sourcePointCount: coordinates.count,
            segments: [
                RoadSnappedRouteSegment(
                    startIndex: 0,
                    endIndex: 3,
                    coordinates: coordinates,
                    isSnapped: false
                )
            ]
        )

        let replaySegments = route.segments(upTo: 2)

        XCTAssertEqual(replaySegments.count, 1)
        let segment = try XCTUnwrap(replaySegments.first)
        XCTAssertEqual(segment.startIndex, 0)
        XCTAssertEqual(segment.endIndex, 2)
        XCTAssertEqual(segment.coordinates.count, 3)
        assertCoordinate(segment.coordinates.last, equals: coordinates[2])
    }

    private func resetUserDefaults() {
        UserDefaults.standard.removeObject(forKey: "isTrackingEnabled")
        UserDefaults.standard.removeObject(forKey: "stopAfterHours")
        UserDefaults.standard.removeObject(forKey: "distanceFilter")
        UserDefaults.standard.removeObject(forKey: "isLiveActivityEnabled")
        UserDefaults.standard.removeObject(forKey: "allowNetworkGeocoding")
        UserDefaults.standard.removeObject(forKey: "activeRecordingSessionID")
        UserDefaults.standard.removeObject(forKey: RecordingSessionInferenceConfiguration.includesInferredSessionsKey)
        UserDefaults.standard.removeObject(forKey: RecordingSessionInferenceConfiguration.gapPresetKey)
        UserDefaults.standard.removeObject(forKey: RecordingSessionInferenceConfiguration.minimumDurationPresetKey)
        UserDefaults.standard.removeObject(forKey: RecordingSessionInferenceConfiguration.minimumPointCountKey)
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

    private func makeViewModel(container: ModelContainer) -> LocationViewModel {
        let manager = LocationManager()
        manager.isTrackingEnabled = false
        manager.trackingStartTime = nil
        return LocationViewModel(
            modelContext: container.mainContext,
            locationManager: manager
        )
    }

    @discardableResult
    private func insertPointHistory(
        count: Int,
        startingAt start: Date,
        interval: TimeInterval,
        into context: ModelContext
    ) throws -> Date {
        precondition(count > 0)

        var timestamp = start
        for index in 0..<count {
            timestamp = start.addingTimeInterval(Double(index) * interval)
            context.insert(LocationPoint(
                latitude: 37.0 + Double(index) * 0.00001,
                longitude: -122.0 - Double(index) * 0.00001,
                timestamp: timestamp,
                altitude: nil,
                speed: nil,
                horizontalAccuracy: 5,
                isOutlier: false
            ))
        }

        try context.save()
        return timestamp
    }

    private func fixtureDate(dayOffset: Int) -> Date {
        var components = DateComponents()
        components.calendar = Calendar(identifier: .gregorian)
        components.timeZone = TimeZone(secondsFromGMT: 0)
        components.year = 2026
        components.month = 1
        components.day = 1 + dayOffset
        components.hour = 12
        components.minute = 0
        components.second = 0
        return components.date!
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

    private func waitUntil(
        _ condition: @escaping @MainActor () -> Bool,
        timeout: TimeInterval = 1
    ) async throws {
        let deadline = Date().addingTimeInterval(timeout)
        while !condition() {
            if Date() >= deadline {
                XCTFail("Timed out waiting for condition")
                return
            }
            try await Task.sleep(nanoseconds: 10_000_000)
        }
    }

    private func assertSortedByTimestamp(
        _ points: [LocationPoint],
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        for pair in zip(points, points.dropFirst()) {
            XCTAssertLessThanOrEqual(pair.0.timestamp, pair.1.timestamp, file: file, line: line)
        }
    }

    private func assertCoordinate(
        _ actual: CLLocationCoordinate2D?,
        equals expected: CLLocationCoordinate2D,
        file: StaticString = #filePath,
        line: UInt = #line
    ) {
        guard let actual else {
            XCTFail("Expected coordinate", file: file, line: line)
            return
        }

        XCTAssertEqual(actual.latitude, expected.latitude, accuracy: 0.000001, file: file, line: line)
        XCTAssertEqual(actual.longitude, expected.longitude, accuracy: 0.000001, file: file, line: line)
    }
}


@MainActor
final class MapPointMarkerSamplerTests: XCTestCase {
    private let viewport = CGSize(width: 400, height: 800)
    private var origin: MKMapPoint { MKMapPoint(CLLocationCoordinate2D(latitude: 0, longitude: 0)) }
    private var rect: MKMapRect {
        MKMapRect(x: origin.x, y: origin.y, width: 100_000, height: 100_000)
    }

    func testLargeVisibleHistoryHasBoundedStableAnnotations() {
        let points = (0..<5_000).map { index in
            point(x: Double(index % 100) * 990 + 100, y: Double(index / 100) * 1_980 + 100)
        }
        let sampled = MapPointMarkerSampler.sample(points, in: rect, viewportSize: viewport)
        XCTAssertFalse(sampled.isEmpty)
        XCTAssertLessThanOrEqual(sampled.count, MapPointMarkerSampler.maximumMarkerCount)
        XCTAssertEqual(sampled.map(\.id), MapPointMarkerSampler.sample(points, in: rect, viewportSize: viewport).map(\.id))
        XCTAssertEqual(Set(sampled.map(\.id)).count, sampled.count)
        XCTAssertEqual(points.count, 5_000, "Rendering must not discard source points.")
    }

    func testPanningReplacesOffscreenPointsWithVisiblePoints() {
        let first = point(x: 1_000, y: 1_000)
        let second = point(x: 201_000, y: 1_000)
        let points = [first, second]
        XCTAssertEqual(MapPointMarkerSampler.sample(points, in: rect, viewportSize: viewport).map(\.id), [first.id])
        let panned = MKMapRect(x: rect.minX + 200_000, y: rect.minY, width: rect.width, height: rect.height)
        XCTAssertEqual(MapPointMarkerSampler.sample(points, in: panned, viewportSize: viewport).map(\.id), [second.id])
    }

    func testZoomingInRevealsPreviouslyCollapsedDetail() {
        let points = (0..<40).map { point(x: 100 + Double($0) * 100, y: 1_000) }
        let overview = MapPointMarkerSampler.sample(points, in: rect, viewportSize: viewport)
        let zoomedRect = MKMapRect(x: rect.minX, y: rect.minY, width: 5_000, height: 5_000)
        let detail = MapPointMarkerSampler.sample(points, in: zoomedRect, viewportSize: viewport)
        XCTAssertGreaterThan(detail.count, overview.count)
    }

    func testSelectedVisiblePointWinsItsCellWithoutExceedingBudget() {
        let first = point(x: 1_000, y: 1_000)
        let selected = point(x: 1_001, y: 1_001)
        let sampled = MapPointMarkerSampler.sample([first, selected], in: rect, viewportSize: viewport, selectedPointID: selected.id)
        XCTAssertEqual(sampled.map(\.id), [selected.id])
        let outside = point(x: 201_000, y: 1_000)
        XCTAssertTrue(MapPointMarkerSampler.sample([outside], in: rect, viewportSize: viewport, selectedPointID: outside.id).isEmpty)
    }

    func testViewportCrossingDateLineIncludesBothSides() {
        let world = MKMapRect.world
        let crossing = MKMapRect(x: world.width - 1_000, y: origin.y, width: 2_000, height: 10_000)
        let west = makePoint(MKMapPoint(x: world.width - 500, y: origin.y + 500))
        let east = makePoint(MKMapPoint(x: 500, y: origin.y + 500))
        let outside = point(x: 1_000, y: 1_000)
        let sampled = MapPointMarkerSampler.sample([west, east, outside], in: crossing, viewportSize: viewport)
        XCTAssertEqual(sampled.map(\.id), [west.id, east.id])
    }

    func testSparsePointsKeepTheirOriginalObjectsAndOrder() {
        let points = [point(x: 1_000, y: 1_000), point(x: 90_000, y: 90_000), point(x: 1_000, y: 90_000)]
        let sampled = MapPointMarkerSampler.sample(points, in: rect, viewportSize: viewport)
        XCTAssertEqual(sampled.map(\.id), points.map(\.id))
        for (actual, expected) in zip(sampled, points) { XCTAssertTrue(actual === expected) }
    }

    func testEmptyAndUnknownViewportsNeverRenderUnboundedHistory() {
        let points = [point(x: 1_000, y: 1_000)]
        XCTAssertTrue(MapPointMarkerSampler.sample(points, in: nil, viewportSize: viewport).isEmpty)
        XCTAssertTrue(MapPointMarkerSampler.sample(points, in: .null, viewportSize: viewport).isEmpty)
        XCTAssertTrue(MapPointMarkerSampler.sample(points, in: rect, viewportSize: .zero).isEmpty)
        XCTAssertTrue(MapPointMarkerSampler.sample([], in: rect, viewportSize: viewport).isEmpty)
        XCTAssertTrue(MapPointMarkerSampler.sample(points, in: MKMapRect(x: 0, y: 0, width: 0, height: 1), viewportSize: viewport).isEmpty)
    }

    func testLargeViewportStillHonorsAnnotationBudget() {
        let points = (0..<5_000).map { index in
            point(x: Double(index % 100) * 990 + 100, y: Double(index / 100) * 1_980 + 100)
        }
        let sampled = MapPointMarkerSampler.sample(points, in: rect, viewportSize: CGSize(width: 10_000, height: 10_000))
        XCTAssertLessThanOrEqual(sampled.count, MapPointMarkerSampler.maximumMarkerCount)
    }

    private func point(x: Double, y: Double) -> LocationPoint {
        makePoint(MKMapPoint(x: origin.x + x, y: origin.y + y))
    }

    private func makePoint(_ projected: MKMapPoint) -> LocationPoint {
        let coordinate = projected.coordinate
        return LocationPoint(latitude: coordinate.latitude, longitude: coordinate.longitude, timestamp: Date(timeIntervalSince1970: 1_790_000_000), horizontalAccuracy: 5)
    }
}
