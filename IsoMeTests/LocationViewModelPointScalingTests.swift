import XCTest
import CoreLocation
import MapKit
import SwiftData
@testable import IsoMe

@MainActor
final class LocationViewModelPointScalingTests: XCTestCase {
    private let mapPointDisplayCap = 2_500

    func testTimelineDayClipsThousandHourRecordingWithoutHydratingHistory() throws {
        let container = try makeInMemoryContainer()
        let start = Calendar.current.startOfDay(for: fixtureDate(dayOffset: 0))
        let end = start.addingTimeInterval(1_000 * 3_600)
        let stored = RecordingSession(startedAt: start, endedAt: end, customName: "Continuous journal")
        container.mainContext.insert(stored)
        _ = try insertPointHistory(count: 60_001, startingAt: start, interval: 60, into: container.mainContext)
        let model = makeViewModel(container: container)
        let day = Calendar.current.date(byAdding: .day, value: 20, to: start)!
        let nextDay = Calendar.current.date(byAdding: .day, value: 1, to: day)!
        let range = day..<nextDay
        let queryStarted = Date()
        let snapshot = try model.timelineDay(in: range, now: end)
        print("Timeline day query with 60,001 stored points: \(Date().timeIntervalSince(queryStarted) * 1000) ms")
        let daily = try XCTUnwrap(snapshot.sessions.first)
        XCTAssertEqual(snapshot.sessions.count, 1)
        XCTAssertEqual(daily.startedAt, day)
        XCTAssertEqual(daily.endedAt, nextDay)
        XCTAssertEqual(daily.duration, nextDay.timeIntervalSince(day))
        XCTAssertEqual(daily.points.count, 1_440)
        XCTAssertTrue(daily.points.allSatisfy { range.contains($0.timestamp) })
        XCTAssertEqual(daily.dayRange, range)
        model.focusMap(on: daily)
        XCTAssertEqual(model.mapLocationPointCount, 1_440, "Show on map must not include the following midnight's point")
        XCTAssertLessThan(daily.dateRange.upperBound, nextDay)
        let gpx = try ExportService.outingsData(outings: [daily], visits: [], format: .gpx)
        let gpxText = try XCTUnwrap(String(data: gpx, encoding: .utf8))
        XCTAssertEqual(gpxText.components(separatedBy: "<trkpt ").count - 1, daily.points.count)
        XCTAssertFalse(daily.isActive)
        XCTAssertTrue(model.locationPoints.isEmpty, "A day query must leave the full-history export cache lazy")
        XCTAssertEqual(stored.startedAt, start)
        XCTAssertEqual(stored.endedAt, end)

        // Full history is available only through the explicit complete-outing path.
        let complete = try XCTUnwrap(model.completeRecordingSession(stored, now: end))
        XCTAssertEqual(complete.points.count, 60_001)
        XCTAssertNil(complete.dayRange)
        XCTAssertGreaterThan(complete.distanceMeters, daily.distanceMeters)
        XCTAssertTrue(model.locationPoints.isEmpty)
    }

    func testTimelineInvalidatesDayMembershipAfterSameCountVisitAndRecordingEdits() throws {
        let container = try makeInMemoryContainer()
        let model = makeViewModel(container: container)
        let day = Calendar.current.startOfDay(for: fixtureDate(dayOffset: 0))
        let end = Calendar.current.date(byAdding: .day, value: 1, to: day)!
        let now = end.addingTimeInterval(7200)
        let visit = Visit(latitude: 18, longitude: -67, arrivedAt: day.addingTimeInterval(-3600), departedAt: nil)
        let recording = RecordingSession(startedAt: day.addingTimeInterval(-3600))
        container.mainContext.insert(visit)
        container.mainContext.insert(recording)
        try container.mainContext.save()
        model.loadData()
        let initial = try model.timelineDay(in: day..<end, now: now)
        XCTAssertEqual(initial.visits.count, 1)
        XCTAssertEqual(initial.sessions.count, 1)
        let visitCount = model.allVisits.count
        let sessionCount = model.allRecordingSessions.count
        let beforeCheckout = model.timelineDataRevision

        // A Watch checkout may arrive after midnight but retain the offline action
        // time from yesterday. The parent reloads data on manualVisitsChanged;
        // Timeline's task key must invalidate even though neither count changed.
        visit.departedAt = day.addingTimeInterval(-60)
        try container.mainContext.save()
        model.loadData()
        XCTAssertGreaterThan(model.timelineDataRevision, beforeCheckout)
        XCTAssertEqual(model.allVisits.count, visitCount)
        XCTAssertEqual(model.allRecordingSessions.count, sessionCount)
        XCTAssertTrue(try model.timelineDay(in: day..<end, now: now).visits.isEmpty)

        let beforeRecordingEdit = model.timelineDataRevision
        recording.endedAt = day.addingTimeInterval(-60)
        try container.mainContext.save()
        model.loadRecordingSessions()
        XCTAssertGreaterThan(model.timelineDataRevision, beforeRecordingEdit)
        XCTAssertEqual(model.allRecordingSessions.count, sessionCount)
        XCTAssertTrue(try model.timelineDay(in: day..<end, now: now).sessions.isEmpty)
        XCTAssertTrue(model.locationPoints.isEmpty)
    }

    func testDailySummaryExportsKeepBoundedStatisticsAcrossFormats() throws {
        let container = try makeInMemoryContainer()
        let model = makeViewModel(container: container)
        let day = Calendar.current.startOfDay(for: fixtureDate(dayOffset: 0))
        let end = Calendar.current.date(byAdding: .day, value: 1, to: day)!
        let stored = RecordingSession(startedAt: day.addingTimeInterval(-3600), endedAt: end.addingTimeInterval(3600))
        container.mainContext.insert(stored)
        for time in [day.addingTimeInterval(-60), day, end.addingTimeInterval(-60), end] {
            container.mainContext.insert(LocationPoint(latitude: 18, longitude: -67, timestamp: time, horizontalAccuracy: 5))
        }
        try container.mainContext.save()
        let daily = try XCTUnwrap(model.timelineDay(in: day..<end, now: end.addingTimeInterval(7200)).sessions.first)
        let json = try ExportService.outingsData(outings: [daily], visits: [], format: .json)
        let exported = try JSONDecoder().decode(ExportService.OutingsExportData.self, from: json)
        let outing = try XCTUnwrap(exported.outings.first)
        XCTAssertEqual(outing.durationSeconds, end.timeIntervalSince(day))
        XCTAssertEqual(outing.pointCount, 2)
        XCTAssertFalse(outing.isActive)
        let formatter = ISO8601DateFormatter()
        formatter.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        XCTAssertEqual(formatter.date(from: outing.startedAt), day)
        XCTAssertEqual(outing.endedAt.flatMap { formatter.date(from: $0) }, end)
        let csv = try XCTUnwrap(String(data: ExportService.outingsData(outings: [daily], visits: [], format: .csv), encoding: .utf8))
        XCTAssertTrue(csv.contains(formatter.string(from: day)))
        XCTAssertFalse(csv.contains(formatter.string(from: stored.startedAt)))
        let markdown = try XCTUnwrap(String(data: ExportService.outingsData(outings: [daily], visits: [], format: .markdown), encoding: .utf8))
        XCTAssertTrue(markdown.contains("point_count: 2"))
        XCTAssertFalse(markdown.contains(formatter.string(from: stored.startedAt)))
        let complete = try XCTUnwrap(model.completeRecordingSession(stored, now: end.addingTimeInterval(7200)))
        XCTAssertEqual(complete.points.count, 4)
        XCTAssertEqual(complete.duration, daily.duration + 7200)
        XCTAssertTrue(model.locationPoints.isEmpty)
    }

    func testSparseStoredRecordingStillHasADailySliceWithoutRouteFixes() throws {
        let container = try makeInMemoryContainer()
        let model = makeViewModel(container: container)
        let day = Calendar.current.startOfDay(for: fixtureDate(dayOffset: 0))
        let end = Calendar.current.date(byAdding: .day, value: 1, to: day)!
        container.mainContext.insert(RecordingSession(startedAt: day.addingTimeInterval(-3600), endedAt: end.addingTimeInterval(3600)))
        try container.mainContext.save()
        let daily = try XCTUnwrap(model.timelineDay(in: day..<end, now: end.addingTimeInterval(7200)).sessions.first)
        XCTAssertTrue(daily.points.isEmpty)
        XCTAssertEqual(daily.duration, end.timeIntervalSince(day))
        XCTAssertEqual(daily.distanceMeters, 0)
        XCTAssertNil(daily.averageSpeedMetersPerSecond)
        XCTAssertTrue(model.locationPoints.isEmpty)
    }

    func testTimelineUsesHalfOpenDaysForPointsSessionsAndVisits() throws {
        let container = try makeInMemoryContainer()
        let model = makeViewModel(container: container)
        let day = Calendar.current.startOfDay(for: fixtureDate(dayOffset: 0))
        let end = Calendar.current.date(byAdding: .day, value: 1, to: day)!
        let previous = RecordingSession(startedAt: day.addingTimeInterval(-3600), endedAt: day)
        let current = RecordingSession(startedAt: day, endedAt: end)
        let next = RecordingSession(startedAt: end, endedAt: end.addingTimeInterval(3600))
        for session in [previous, current, next] { container.mainContext.insert(session) }
        for time in [day.addingTimeInterval(-1), day, day.addingTimeInterval(60), end] {
            container.mainContext.insert(LocationPoint(latitude: 18, longitude: -67, timestamp: time, horizontalAccuracy: 5))
        }
        container.mainContext.insert(Visit(latitude: 18, longitude: -67, arrivedAt: day.addingTimeInterval(-3600), departedAt: day))
        container.mainContext.insert(Visit(latitude: 18, longitude: -67, arrivedAt: day, departedAt: day.addingTimeInterval(3600)))
        container.mainContext.insert(Visit(latitude: 18, longitude: -67, arrivedAt: end, departedAt: nil))
        try container.mainContext.save()
        let snapshot = try model.timelineDay(in: day..<end, now: end.addingTimeInterval(7200))
        XCTAssertEqual(snapshot.sessions.count, 1)
        XCTAssertEqual(snapshot.sessions.first?.storedSession?.id, current.id)
        XCTAssertEqual(snapshot.sessions.first?.points.map(\.timestamp), [day, day.addingTimeInterval(60)])
        XCTAssertEqual(snapshot.visits.count, 1)
        XCTAssertEqual(snapshot.visits.first?.arrivedAt, day)
    }

    func testHistoricalSliceOfOpenRecordingIsNotLiveAndCannotDeleteTheRecording() throws {
        let container = try makeInMemoryContainer()
        let model = makeViewModel(container: container)
        let day = Calendar.current.startOfDay(for: fixtureDate(dayOffset: 0))
        let end = Calendar.current.date(byAdding: .day, value: 1, to: day)!
        let stored = RecordingSession(startedAt: day.addingTimeInterval(-3600))
        container.mainContext.insert(stored)
        container.mainContext.insert(LocationPoint(latitude: 18, longitude: -67, timestamp: day.addingTimeInterval(60), horizontalAccuracy: 5))
        try container.mainContext.save()
        let now = end.addingTimeInterval(7200)
        let daily = try XCTUnwrap(model.timelineDay(in: day..<end, now: now).sessions.first)
        XCTAssertFalse(daily.isActive)
        XCTAssertEqual(daily.endedAt, end)
        XCTAssertEqual(daily.duration, end.timeIntervalSince(day))
        model.deleteRecordingSession(daily)
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<RecordingSession>()), 1)
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<LocationPoint>()), 1)
        XCTAssertNil(stored.endedAt)
        let live = try XCTUnwrap(model.timelineDay(in: end..<end.addingTimeInterval(86400), now: now).sessions.first)
        XCTAssertTrue(live.isActive)
        XCTAssertEqual(live.duration, 7200, "A live day with no new fixes still has a bounded recording duration")
    }

    func testTimelineDayClippingRespectsDaylightSavingBoundaries() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "America/Los_Angeles"))
        for (month, dayNumber, hours) in [(3, 8, 23), (11, 1, 25)] {
            let day = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: month, day: dayNumber)))
            let end = try XCTUnwrap(calendar.date(byAdding: .day, value: 1, to: day))
            let container = try makeInMemoryContainer()
            let model = makeViewModel(container: container)
            container.mainContext.insert(RecordingSession(startedAt: day.addingTimeInterval(-3600), endedAt: end.addingTimeInterval(3600)))
            try container.mainContext.save()
            let daily = try XCTUnwrap(model.timelineDay(in: day..<end, now: end.addingTimeInterval(3600)).sessions.first)
            XCTAssertEqual(daily.duration, Double(hours * 3600))
            XCTAssertEqual(daily.startedAt, day)
            XCTAssertEqual(daily.endedAt, end)
        }
    }

    func testTimelineDayRespectsInferencePreferencesWithoutLoadingHistory() throws {
        let container = try makeInMemoryContainer()
        let model = makeViewModel(container: container)
        let day = Calendar.current.startOfDay(for: fixtureDate(dayOffset: 0))
        for seconds in [-60.0, 0, 60, 3600, 3660, 86400] {
            container.mainContext.insert(LocationPoint(latitude: 18, longitude: -67, timestamp: day.addingTimeInterval(seconds), horizontalAccuracy: 5))
        }
        try container.mainContext.save()
        let range = day..<day.addingTimeInterval(86400)
        let enabled = try model.timelineDay(in: range, now: range.upperBound)
        XCTAssertEqual(enabled.sessions.count, 2)
        XCTAssertEqual(enabled.sessions.flatMap(\.points).count, 4)
        let disabled = try model.timelineDay(in: range, inferenceConfiguration: RecordingSessionInferenceConfiguration(includesInferredSessions: false), now: range.upperBound)
        XCTAssertTrue(disabled.sessions.isEmpty)
        XCTAssertTrue(model.locationPoints.isEmpty)
        XCTAssertEqual(try model.latestTimelineDate(), range.upperBound)
    }

    func testLargePhotoSyncLetsMainThreadHandleInputBeforeCompletion() async throws {
        let container = try makeInMemoryContainer()
        let model = makeViewModel(container: container)
        let start = Date(timeIntervalSince1970: 1_700_000_000)
        let metadata = (0..<2_000).map { index in
            PhotoAssetMetadata(assetLocalIdentifier: "photo-\(index)",
                takenAt: start.addingTimeInterval(Double(index)), latitude: 18,
                longitude: -67, coordinateSource: .photoGPS)
        }
        var handledInput = false
        DispatchQueue.main.async { handledInput = true }

        try await model.upsertPhotoMoments(metadata)

        XCTAssertTrue(handledInput, "Large photo imports must let the main run loop handle input before finishing")
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<PhotoMoment>()), metadata.count)
    }

    func testPhotoSyncUpdatesExistingIdentityAndDoesNotDuplicateAssetsAcrossBatches() async throws {
        let container = try makeInMemoryContainer()
        let model = makeViewModel(container: container)
        let original = PhotoMoment(assetLocalIdentifier: "existing", takenAt: .distantPast,
            latitude: 1, longitude: 2)
        container.mainContext.insert(original)
        try container.mainContext.save()
        let update = PhotoAssetMetadata(assetLocalIdentifier: "existing", takenAt: .now,
            latitude: 18, longitude: -67, coordinateSource: .inferredFromRoute)
        let newPhoto = PhotoAssetMetadata(assetLocalIdentifier: "new", takenAt: .now,
            latitude: 19, longitude: -66, coordinateSource: .photoGPS)

        try await model.upsertPhotoMoments([update] + Array(repeating: newPhoto, count: 201))
        try container.mainContext.save()

        let photos = try container.mainContext.fetch(FetchDescriptor<PhotoMoment>())
        XCTAssertEqual(photos.count, 2)
        let updated = try XCTUnwrap(photos.first { $0.assetLocalIdentifier == "existing" })
        XCTAssertEqual(updated.id, original.id)
        XCTAssertEqual(updated.takenAt, update.takenAt)
        XCTAssertEqual(updated.latitude, 18)
        XCTAssertEqual(updated.longitude, -67)
        XCTAssertEqual(updated.coordinateSource, .inferredFromRoute)
    }

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

    func testManualVisitCheckoutPersistsAndCannotCheckoutTwice() throws {
        let container = try makeInMemoryContainer()
        let viewModel = makeViewModel(container: container)
        let arrival = Date().addingTimeInterval(-3600)
        let departure = arrival.addingTimeInterval(1800)
        let visit = try XCTUnwrap(viewModel.createManualVisit(
            name: "Cafe", address: nil,
            coordinate: CLLocationCoordinate2D(latitude: 37.7, longitude: -122.4),
            arrivedAt: arrival, departedAt: nil
        ))
        let confirmedAt = visit.confirmedAt
        XCTAssertFalse(viewModel.checkoutVisit(visit, at: arrival.addingTimeInterval(-1)))
        XCTAssertNil(visit.departedAt)
        XCTAssertTrue(viewModel.checkoutVisit(visit, at: departure))
        XCTAssertEqual(visit.departedAt, departure)
        XCTAssertEqual(visit.confirmedAt, confirmedAt)
        XCTAssertEqual(visit.confirmationStatus, .confirmed)
        XCTAssertEqual(visit.countryDepartureIsInferred, false)
        XCTAssertFalse(viewModel.checkoutVisit(visit, at: departure.addingTimeInterval(60)))
        XCTAssertEqual(visit.departedAt, departure)
        let freshContext = ModelContext(container)
        let savedVisit = try XCTUnwrap(freshContext.fetch(FetchDescriptor<Visit>()).first)
        XCTAssertEqual(savedVisit.departedAt, departure)
    }

    func testCheckoutRejectsAutomaticAndFutureManualVisits() throws {
        let container = try makeInMemoryContainer()
        let viewModel = makeViewModel(container: container)
        let automatic = Visit(latitude: 37.7, longitude: -122.4, arrivedAt: Date().addingTimeInterval(-60))
        container.mainContext.insert(automatic)
        XCTAssertFalse(viewModel.checkoutVisit(automatic))
        XCTAssertNil(automatic.departedAt)
        let future = try XCTUnwrap(viewModel.createManualVisit(
            name: "Tomorrow", address: nil,
            coordinate: CLLocationCoordinate2D(latitude: 37.7, longitude: -122.4),
            arrivedAt: Date().addingTimeInterval(3600), departedAt: nil,
            allowOverlap: true
        ))
        XCTAssertFalse(viewModel.checkoutVisit(future))
        XCTAssertFalse(viewModel.checkoutVisit(future, at: Date().addingTimeInterval(7200)))
        XCTAssertNil(future.departedAt)
    }

    func testManualOverlapWarningCanBeOverriddenWithoutLosingEitherVisit() throws {
        let container = try makeInMemoryContainer()
        let viewModel = makeViewModel(container: container)
        let arrival = Date().addingTimeInterval(-7200)
        let coordinate = CLLocationCoordinate2D(latitude: 37.7, longitude: -122.4)
        // Insert after the view model loads: overlap detection must query storage.
        let automatic = Visit(latitude: 37.7, longitude: -122.4, arrivedAt: arrival,
                              departedAt: arrival.addingTimeInterval(3600), locationName: "Detected Cafe")
        container.mainContext.insert(automatic)
        try container.mainContext.save()
        XCTAssertNil(viewModel.createManualVisit(
            name: "Lunch", address: nil, coordinate: coordinate,
            arrivedAt: arrival.addingTimeInterval(600), departedAt: arrival.addingTimeInterval(1800)
        ))
        guard case .overlap = viewModel.visitMutationError else {
            return XCTFail("Expected an overlap warning")
        }
        let manual = try XCTUnwrap(viewModel.createManualVisit(
            name: "Lunch", address: nil, coordinate: coordinate,
            arrivedAt: arrival.addingTimeInterval(600), departedAt: arrival.addingTimeInterval(1800),
            allowOverlap: true
        ))
        XCTAssertEqual(viewModel.allVisits.count, 2)
        XCTAssertTrue(viewModel.allVisits.contains { $0.id == automatic.id })
        XCTAssertTrue(viewModel.allVisits.contains { $0.id == manual.id })
        XCTAssertNil(viewModel.visitMutationError)
    }

    func testAdjacentManualVisitsAndOverlapEditing() throws {
        let container = try makeInMemoryContainer()
        let viewModel = makeViewModel(container: container)
        let arrival = Date().addingTimeInterval(-7200)
        let boundary = arrival.addingTimeInterval(1800)
        let coordinate = CLLocationCoordinate2D(latitude: 37.7, longitude: -122.4)
        let first = try XCTUnwrap(viewModel.createManualVisit(
            name: "Breakfast", address: nil, coordinate: coordinate,
            arrivedAt: arrival, departedAt: boundary
        ))
        let second = try XCTUnwrap(viewModel.createManualVisit(
            name: "Coffee", address: nil, coordinate: coordinate,
            arrivedAt: boundary, departedAt: boundary.addingTimeInterval(1800)
        ))
        XCTAssertEqual(viewModel.allVisits.count, 2)
        XCTAssertTrue(viewModel.updateVisitTimes(first, arrivedAt: arrival, departedAt: boundary))
        XCTAssertFalse(viewModel.updateVisitTimes(second, arrivedAt: arrival, departedAt: boundary.addingTimeInterval(1800)))
        XCTAssertEqual(second.arrivedAt, boundary)
        XCTAssertTrue(viewModel.updateVisitTimes(second, arrivedAt: arrival, departedAt: boundary.addingTimeInterval(1800), allowOverlap: true))
        XCTAssertEqual(viewModel.allVisits.count, 2)
    }

    func testReconciliationPreservesOpenManualVisitsAndExplicitOverlaps() throws {
        let container = try makeInMemoryContainer()
        let viewModel = makeViewModel(container: container)
        let arrival = Date().addingTimeInterval(-7200)
        let coordinate = CLLocationCoordinate2D(latitude: 37.7, longitude: -122.4)
        let first = try XCTUnwrap(viewModel.createManualVisit(
            name: "First", address: nil, coordinate: coordinate,
            arrivedAt: arrival, departedAt: nil
        ))
        XCTAssertNil(viewModel.createManualVisit(
            name: "Second", address: nil, coordinate: coordinate,
            arrivedAt: arrival.addingTimeInterval(1800), departedAt: nil
        ))
        let second = try XCTUnwrap(viewModel.createManualVisit(
            name: "Second", address: nil, coordinate: coordinate,
            arrivedAt: arrival.addingTimeInterval(1800), departedAt: nil, allowOverlap: true
        ))
        viewModel.locationManager.reconcileOpenVisits()
        XCTAssertNil(first.departedAt)
        XCTAssertNil(second.departedAt)
        XCTAssertEqual(viewModel.allVisits.count, 2)
        XCTAssertTrue(viewModel.checkoutVisit(first, at: arrival.addingTimeInterval(3600)))
        XCTAssertNil(second.departedAt)
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

        let confirmedAt = visit.confirmedAt
        viewModel.undoVisitCorrection(visit)

        // Undo returns the visit to how it stood before the correction: confirmed, as the user left it.
        XCTAssertEqual(visit.confirmationStatus, .confirmed)
        XCTAssertEqual(visit.confirmedAt, confirmedAt)
        XCTAssertNil(visit.placeSource)
        XCTAssertNil(visit.placeDistanceMeters)
        XCTAssertEqual(visit.locationName, "Detected Block")
        XCTAssertEqual(visit.address, "Market St")
        assertCoordinate(visit.coordinate, equals: originalCoordinate)
    }

    func testCorrectedOpenVisitReceivesDepartureAtDetectedCoordinate() async throws {
        let defaults = makeIsolatedDefaults()
        defaults.set(false, forKey: "allowNetworkGeocoding")
        let container = try makeInMemoryContainer()
        let manager = makeIsolatedLocationManager(defaults: defaults)
        manager.setModelContext(container.mainContext)
        let arrival = Date().addingTimeInterval(-1800)
        let visit = Visit(latitude: 18.4, longitude: -66.1, arrivedAt: arrival)
        container.mainContext.insert(visit)
        visit.preserveOriginalValuesIfNeeded()
        visit.latitude = 18.5
        visit.longitude = -66.2
        visit.confirmationStatus = .corrected
        try container.mainContext.save()
        let departure = Date().addingTimeInterval(-60)
        manager.locationManager(CLLocationManager(), didVisit: DepartureFixture(
            coordinate: CLLocationCoordinate2D(latitude: 18.4, longitude: -66.1),
            arrival: arrival, departure: departure))
        try await waitUntil { visit.departedAt != nil }
        XCTAssertEqual(visit.departedAt, departure)
        XCTAssertEqual(visit.latitude, 18.5)
        XCTAssertEqual(visit.confirmationStatus, .corrected)
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<Visit>()), 1)
    }

    func testLiveBatchAppendAddsEveryPointToMapAndSessionCaches() async throws {
        let defaults = makeIsolatedDefaults()
        defaults.set(false, forKey: "allowNetworkGeocoding")
        let container = try makeInMemoryContainer()
        let manager = makeIsolatedLocationManager(defaults: defaults)
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
        let manager = makeIsolatedLocationManager()
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
        // LocationManager state lives in each test's own suite (makeIsolatedLocationManager), not here.
        UserDefaults.standard.removeObject(forKey: RecordingSessionInferenceConfiguration.includesInferredSessionsKey)
        UserDefaults.standard.removeObject(forKey: RecordingSessionInferenceConfiguration.gapPresetKey)
        UserDefaults.standard.removeObject(forKey: RecordingSessionInferenceConfiguration.minimumDurationPresetKey)
        UserDefaults.standard.removeObject(forKey: RecordingSessionInferenceConfiguration.minimumPointCountKey)
    }

    // MARK: - Relaunch, overnight stays and undoing corrections

    func testRelaunchWithTrackingOffKeepsTodaysDistance() throws {
        let container = try makeInMemoryContainer()
        let startOfToday = Calendar.current.startOfDay(for: Date())
        for (index, latitude) in [37.7700, 37.7710, 37.7720].enumerated() {
            container.mainContext.insert(LocationPoint(
                latitude: latitude,
                longitude: -122.4,
                timestamp: startOfToday.addingTimeInterval(Double(index + 1) * 60),
                altitude: nil,
                speed: nil,
                horizontalAccuracy: 5,
                isOutlier: false
            ))
        }
        try container.mainContext.save()

        // makeViewModel turns tracking off, as after a relaunch with tracking stopped.
        let viewModel = makeViewModel(container: container)

        XCTAssertEqual(viewModel.todayLocationPoints.count, 3)
        XCTAssertGreaterThan(viewModel.todayDistanceTraveled, 200)
        XCTAssertLessThan(viewModel.todayDistanceTraveled, 250)
    }

    func testTodayMapIncludesAnOngoingStayThatBeganYesterday() throws {
        let container = try makeInMemoryContainer()
        let startOfToday = Calendar.current.startOfDay(for: Date())
        let overnight = Visit(latitude: 37.79, longitude: -122.39,
                              arrivedAt: startOfToday.addingTimeInterval(-2 * 3600),
                              locationName: "Ferry Building Errand", geocodingCompleted: true)
        let earlier = Visit(latitude: 37.78, longitude: -122.40,
                            arrivedAt: startOfToday.addingTimeInterval(-6 * 3600),
                            departedAt: startOfToday.addingTimeInterval(-5 * 3600),
                            locationName: "Yesterday Only", geocodingCompleted: true)
        container.mainContext.insert(overnight)
        container.mainContext.insert(earlier)
        try container.mainContext.save()
        let viewModel = makeViewModel(container: container)
        let today = startOfToday...startOfToday.addingTimeInterval(86_399)
        viewModel.mapDateRange = today

        let defaults = UserDefaults.standard
        let storedMinimum = defaults.object(forKey: MapLayerSettings.minimumStayMinutesKey)
        defaults.removeObject(forKey: MapLayerSettings.minimumStayMinutesKey)
        defer { defaults.set(storedMinimum, forKey: MapLayerSettings.minimumStayMinutesKey) }

        let mapVisits = LocationMapView(viewModel: viewModel).filteredVisits
        XCTAssertEqual(mapVisits.map(\.id), [overnight.id])
        XCTAssertEqual(viewModel.visitsInDateRange(today).count, 0, "arrival-only filtering is what dropped the stay")
    }

    func testUndoingACorrectionOnAManualVisitKeepsItConfirmed() throws {
        let container = try makeInMemoryContainer()
        let viewModel = makeViewModel(container: container)
        let coordinate = CLLocationCoordinate2D(latitude: 37.7776, longitude: -122.4070)
        let visit = try XCTUnwrap(viewModel.createManualVisit(
            name: "Deli Board",
            address: "1058 Folsom St",
            coordinate: coordinate,
            arrivedAt: fixtureDate(dayOffset: 21),
            departedAt: fixtureDate(dayOffset: 21).addingTimeInterval(3600),
            placeSource: .appleMaps
        ))
        let confirmedAt = try XCTUnwrap(visit.confirmedAt)

        viewModel.correctVisit(visit, name: "Garage Door", address: "Folsom St",
                               coordinate: CLLocationCoordinate2D(latitude: 37.7777, longitude: -122.4071),
                               placeSource: .userEntered, distanceMeters: 11)
        XCTAssertEqual(visit.confirmationStatus, .corrected)

        viewModel.undoVisitCorrection(visit)

        XCTAssertEqual(visit.confirmationStatus, .confirmed)
        XCTAssertEqual(visit.confirmedAt, confirmedAt)
        XCTAssertEqual(visit.placeSource, .appleMaps)
        XCTAssertNil(visit.placeDistanceMeters)
        XCTAssertEqual(visit.locationName, "Deli Board")
        XCTAssertEqual(visit.address, "1058 Folsom St")
        assertCoordinate(visit.coordinate, equals: coordinate)
    }

    func testUndoingACorrectionRestoresEachPriorStatus() throws {
        let container = try makeInMemoryContainer()
        let viewModel = makeViewModel(container: container)
        let detected = Visit(latitude: 37.70, longitude: -122.40, arrivedAt: fixtureDate(dayOffset: 22),
                             departedAt: fixtureDate(dayOffset: 22).addingTimeInterval(1800),
                             locationName: "Detected", geocodingCompleted: true)
        let confirmed = Visit(latitude: 37.71, longitude: -122.41, arrivedAt: fixtureDate(dayOffset: 23),
                              departedAt: fixtureDate(dayOffset: 23).addingTimeInterval(1800),
                              locationName: "Confirmed", geocodingCompleted: true)
        container.mainContext.insert(detected)
        container.mainContext.insert(confirmed)
        try container.mainContext.save()
        viewModel.loadAllVisits()
        viewModel.confirmVisit(confirmed)
        let corrected = CLLocationCoordinate2D(latitude: 37.72, longitude: -122.42)

        for visit in [detected, confirmed] {
            viewModel.correctVisit(visit, name: "Elsewhere", address: nil, coordinate: corrected, placeSource: .appleMaps)
            viewModel.undoVisitCorrection(visit)
        }

        XCTAssertEqual(detected.confirmationStatus, .unconfirmed)
        XCTAssertNil(detected.confirmedAt)
        XCTAssertNil(detected.placeSource)
        XCTAssertEqual(confirmed.confirmationStatus, .confirmed)
        XCTAssertNotNil(confirmed.confirmedAt)

        // A second correction after undoing records the then-current state afresh.
        viewModel.confirmVisit(detected)
        viewModel.correctVisit(detected, name: "Elsewhere", address: nil, coordinate: corrected, placeSource: .appleMaps)
        viewModel.undoVisitCorrection(detected)
        XCTAssertEqual(detected.confirmationStatus, .confirmed)
    }

    func testUndoingACorrectionMadeBeforeTheStatusWasRecordedInfersIt() throws {
        let container = try makeInMemoryContainer()
        let viewModel = makeViewModel(container: container)
        // As stored by earlier versions: corrected, with only the original location kept.
        let legacyManual = Visit(latitude: 37.73, longitude: -122.43, arrivedAt: fixtureDate(dayOffset: 24),
                                 departedAt: fixtureDate(dayOffset: 24).addingTimeInterval(1800),
                                 locationName: "Fixed", geocodingCompleted: true, source: .manual,
                                 confirmationStatus: .corrected, confirmedAt: fixtureDate(dayOffset: 24),
                                 originalLatitude: 37.70, originalLongitude: -122.40,
                                 originalLocationName: "Typed", placeSource: .appleMaps)
        let legacyAutomatic = Visit(latitude: 37.73, longitude: -122.43, arrivedAt: fixtureDate(dayOffset: 25),
                                    departedAt: fixtureDate(dayOffset: 25).addingTimeInterval(1800),
                                    locationName: "Fixed", geocodingCompleted: true,
                                    confirmationStatus: .corrected, confirmedAt: fixtureDate(dayOffset: 25),
                                    originalLatitude: 37.70, originalLongitude: -122.40,
                                    originalLocationName: "Detected", placeSource: .appleMaps)
        container.mainContext.insert(legacyManual)
        container.mainContext.insert(legacyAutomatic)
        try container.mainContext.save()

        viewModel.undoVisitCorrection(legacyManual)
        viewModel.undoVisitCorrection(legacyAutomatic)

        XCTAssertEqual(legacyManual.confirmationStatus, .confirmed)
        XCTAssertEqual(legacyManual.confirmedAt, fixtureDate(dayOffset: 24))
        XCTAssertEqual(legacyManual.placeSource, .userEntered)
        XCTAssertEqual(legacyManual.locationName, "Typed")
        XCTAssertEqual(legacyAutomatic.confirmationStatus, .unconfirmed)
        XCTAssertNil(legacyAutomatic.confirmedAt)
        XCTAssertNil(legacyAutomatic.placeSource)
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
        let manager = makeIsolatedLocationManager()
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

// The persisted Visit fields immediately before correction metadata was introduced (38da646^).
private enum PreCorrectionSchema {
    @Model
    final class Visit {
        var id: UUID
        var latitude: Double
        var longitude: Double
        var arrivedAt: Date
        var departedAt: Date?
        var customName: String?
        var locationName: String?
        var address: String?
        var notes: String?
        var geocodingCompleted: Bool

        init(id: UUID, departure: Date?) {
            self.id = id
            latitude = 18.4
            longitude = -66.1
            arrivedAt = Date(timeIntervalSince1970: 1_700_000_000)
            departedAt = departure
            customName = "Legacy name"
            locationName = "Detected name"
            address = "Legacy address"
            notes = "Preserve this"
            geocodingCompleted = true
        }
    }
}

@MainActor
final class VisitMigrationTests: XCTestCase {
    func testPreCorrectionDiskStoreMigratesAndReopensWithoutDataLoss() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("legacy.store")
        let openID = UUID()
        let completedID = UUID()
        let departure = Date(timeIntervalSince1970: 1_700_003_600)
        try autoreleasepool {
            let oldSchema = Schema([PreCorrectionSchema.Visit.self])
            let old = try ModelContainer(for: oldSchema, configurations: ModelConfiguration(schema: oldSchema, url: url))
            let context = ModelContext(old)
            context.insert(PreCorrectionSchema.Visit(id: openID, departure: nil))
            context.insert(PreCorrectionSchema.Visit(id: completedID, departure: departure))
            try context.save()
        }
        let current = Schema([Visit.self, LocationPoint.self, RecordingSession.self, PhotoMoment.self, SavedPlace.self, CountryStay.self])
        try autoreleasepool {
            let migrated = try ModelContainer(for: current, configurations: ModelConfiguration(schema: current, url: url))
            let context = ModelContext(migrated)
            let visits = try context.fetch(FetchDescriptor<Visit>())
            XCTAssertEqual(visits.count, 2)
            for visit in visits {
                XCTAssertEqual(visit.source, .automatic)
                XCTAssertEqual(visit.confirmationStatus, .unconfirmed)
                XCTAssertEqual(visit.customName, "Legacy name")
                XCTAssertEqual(visit.locationName, "Detected name")
                XCTAssertEqual(visit.address, "Legacy address")
                XCTAssertEqual(visit.notes, "Preserve this")
                XCTAssertEqual(visit.latitude, 18.4)
                XCTAssertTrue(visit.geocodingCompleted)
            }
            XCTAssertNil(visits.first { $0.id == openID }?.departedAt)
            XCTAssertEqual(visits.first { $0.id == completedID }?.departedAt, departure)
            let open = try XCTUnwrap(visits.first { $0.id == openID })
            open.preserveOriginalValuesIfNeeded()
            open.confirmationStatus = .corrected
            open.latitude = 19
            try context.save()
        }
        let reopened = try ModelContainer(for: current, configurations: ModelConfiguration(schema: current, url: url))
        let visits = try ModelContext(reopened).fetch(FetchDescriptor<Visit>())
        let corrected = try XCTUnwrap(visits.first { $0.id == openID })
        XCTAssertEqual(corrected.confirmationStatus, .corrected)
        XCTAssertEqual(corrected.originalLatitude, 18.4)
        XCTAssertEqual(corrected.latitude, 19)
        XCTAssertEqual(visits.count, 2)
    }
}

private final class DepartureFixture: CLVisit {
    private let point: CLLocationCoordinate2D
    private let arrival: Date
    private let departure: Date
    init(coordinate: CLLocationCoordinate2D, arrival: Date, departure: Date) {
        point = coordinate
        self.arrival = arrival
        self.departure = departure
        super.init()
    }
    required init?(coder: NSCoder) { fatalError("Not used") }
    override var coordinate: CLLocationCoordinate2D { point }
    override var arrivalDate: Date { arrival }
    override var departureDate: Date { departure }
    override var horizontalAccuracy: CLLocationAccuracy { 10 }
}
