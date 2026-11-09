import XCTest
import SwiftData
@testable import IsoMe

@MainActor
final class TimelineImportTests: XCTestCase {
    private let time = Date(timeIntervalSince1970: 1_577_836_800)

    private func container() throws -> ModelContainer {
        try ModelContainer(for: Visit.self, LocationPoint.self, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
    }

    private func json(_ object: Any) throws -> Data { try JSONSerialization.data(withJSONObject: object) }

    private func raw(_ rows: [[String: Any]]) throws -> Data { try json(["locations": rows]) }

    private func point(milliseconds: Int = 1_577_836_800_000, latitude: Any = 377_000_000, longitude: Any = -1_224_000_000) -> [String: Any] {
        ["latitudeE7": latitude, "longitudeE7": longitude, "timestampMs": String(milliseconds), "accuracy": 8]
    }

    private func backup(visits: [[String: Any]] = [], points: [[String: Any]] = []) throws -> Data {
        try json(["visits": visits, "points": points])
    }

    private func visit(arrival: String = "2020-01-01T00:00:00Z", departure: String? = "2020-01-01T01:00:00Z", latitude: Double = 37.7) -> [String: Any] {
        var value: [String: Any] = ["latitude": latitude, "longitude": -122.4, "arrivedAt": arrival, "locationName": "Cafe"]
        if let departure { value["departedAt"] = departure }
        return value
    }

    func testRawGoogleCoordinatesTimestampVariantsAndUnknownAccuracy() throws {
        let records: [[String: Any]] = [point(),
            ["latitudeE7": 0, "longitudeE7": 0, "timestamp": "2020-01-01T01:00:00.123Z", "altitude": 12, "velocity": 4.5]]
        let payload = try TimelineImportService.parse(data: raw(records), fileName: "Records.JSON")
        XCTAssertEqual(payload.format, .googleRaw)
        XCTAssertTrue(payload.visits.isEmpty)
        XCTAssertEqual(payload.points.count, 2)
        XCTAssertEqual(payload.points[0].latitude, 37.7, accuracy: 0.000001)
        XCTAssertEqual(payload.points[0].longitude, -122.4, accuracy: 0.000001)
        XCTAssertEqual(payload.points[0].timestamp, time)
        XCTAssertEqual(payload.points[0].horizontalAccuracy, 8)
        XCTAssertEqual(payload.points[1].latitude, 0)
        XCTAssertEqual(payload.points[1].longitude, 0)
        XCTAssertEqual(payload.points[1].horizontalAccuracy, -1)
        XCTAssertEqual(payload.points[1].altitude, 12)
        XCTAssertEqual(payload.points[1].speed, 4.5)
    }

    func testInvalidGoogleRecordsAreCountedRatherThanSilentlyImported() throws {
        var invalidTime = point(); invalidTime["timestampMs"] = "NaN"
        var invalidAccuracy = point(); invalidAccuracy["accuracy"] = -5
        let data = try raw([point(), point(latitude: 910_000_000), point(longitude: 1_810_000_000),
                            point(latitude: true), invalidTime, invalidAccuracy])
        let payload = try TimelineImportService.parse(data: data, fileName: "Records.json")
        XCTAssertEqual(payload.points.count, 1)
        XCTAssertEqual(payload.invalidRecordCount, 5)
    }

    func testSemanticVisitsAndTimestampedPaths() throws {
        let data = try json(["timelineObjects": [
            ["placeVisit": ["location": ["latitudeE7": 377_000_000, "longitudeE7": -1_224_000_000,
                                          "name": "Cafe", "address": "1 Test Street"],
                            "duration": ["startTimestampMs": "1577836800000", "endTimestamp": "2020-01-01T01:00:00Z"]]],
            ["activitySegment": ["startLocation": ["latitudeE7": 377_000_000, "longitudeE7": -1_224_000_000],
                                 "endLocation": ["latitudeE7": 377_001_000, "longitudeE7": -1_224_001_000],
                                 "duration": ["startTimestamp": "2020-01-01T01:00:00Z", "endTimestampMs": "1577844000000"],
                                 "simplifiedRawPath": ["points": [["latE7": 377_000_500, "lngE7": -1_224_000_500,
                                                                       "timestampMs": "1577842200000"]]],
                                 "waypointPath": ["waypoints": [["latE7": 377_000_500, "lngE7": -1_224_000_500]]]]],
            ["unrecognized": [:]]
        ]])
        let payload = try TimelineImportService.parse(data: data, fileName: "2020_JANUARY.json")
        XCTAssertEqual(payload.format, .googleSemantic)
        XCTAssertEqual(payload.visits.count, 1)
        XCTAssertEqual(payload.visits[0].locationName, "Cafe")
        XCTAssertEqual(payload.visits[0].address, "1 Test Street")
        XCTAssertEqual(payload.visits[0].sourceRaw, "imported")
        XCTAssertEqual(payload.points.count, 3)
        XCTAssertEqual(payload.invalidRecordCount, 1, "Untimed waypoints must not get invented timestamps")
        XCTAssertEqual(payload.unsupportedRecordCount, 1)
        XCTAssertEqual(payload.dateRange?.upperBound, time.addingTimeInterval(7200))
    }

    func testDeviceTimelineSupportsGeoDegreeCoordinatesAndPathOffsets() throws {
        let segment: [String: Any] = [
            "startTime": "2020-01-01T02:00:00+02:00", "endTime": "2020-01-01T03:00:00+02:00",
            "visit": ["topCandidate": ["placeLocation": ["latLng": "37.7\u{00B0}, -122.4\u{00B0}"], "placeName": "Lunch"]],
            "activity": ["start": ["latLng": "geo:37.7,-122.4"], "end": ["latLng": "geo:37.701,-122.401"]],
            "timelinePath": [["point": "geo:37.7,-122.4", "durationMinutesOffsetFromStartTime": "5"],
                             ["point": "37.701\u{00B0}, -122.401\u{00B0}", "durationMinutesOffsetFromStartTime": 15],
                             ["point": "geo:37.702,-122.402", "time": "2020-01-01T00:30:00Z"],
                             ["point": "geo:37.703,-122.403", "durationMinutesOffsetFromStartTime": 65]]
        ]
        for root: Any in [["semanticSegments": [segment]], [segment]] {
            let payload = try TimelineImportService.parse(data: json(root), fileName: "location-history.json")
            XCTAssertEqual(payload.format, .googleDevice)
            XCTAssertEqual(payload.visits.count, 1)
            XCTAssertEqual(payload.visits[0].arrivedAt, time)
            XCTAssertEqual(payload.points.count, 5)
            XCTAssertEqual(payload.points[1].timestamp, time.addingTimeInterval(300))
            XCTAssertEqual(payload.points[2].timestamp, time.addingTimeInterval(900))
            XCTAssertEqual(payload.invalidRecordCount, 1)
        }
    }

    func testIOSDeviceVisitAcceptsStringPlaceLocationAndRejectsReversedTimes() throws {
        let segment: [String: Any] = ["startTime": "2020-01-01T00:00:00Z", "endTime": "2020-01-01T01:00:00Z",
                                     "visit": ["topCandidate": ["placeLocation": "geo:37.7,-122.4"]]]
        var reversed = segment; reversed["endTime"] = "2019-12-31T23:00:00Z"
        let payload = try TimelineImportService.parse(data: json([segment, reversed]), fileName: "Timeline.json")
        XCTAssertEqual(payload.visits.count, 1)
        XCTAssertNil(payload.visits[0].locationName)
        XCTAssertEqual(payload.invalidRecordCount, 1)
    }

    func testCombinedIsoMeBackupImportsBothVisitsAndPoints() throws {
        let data = try backup(visits: [visit()], points: [["latitude": 37.7, "longitude": -122.4, "timestamp": "2020-01-01T00:00:00Z"]])
        let legacy = try ImportService.importFile(data: data, format: .json)
        XCTAssertEqual(legacy.visits.count, 1)
        XCTAssertEqual(legacy.points.count, 1)
        let payload = try TimelineImportService.parse(data: data, fileName: "backup.json")
        XCTAssertEqual(payload.format, .isoMeJSON)
        XCTAssertEqual(payload.visits.count, 1)
        XCTAssertEqual(payload.points.count, 1)
    }

    func testUnsupportedMalformedAndEmptyFilesDoNotProducePreviews() throws {
        for data in [Data("{".utf8), try json(["unrelated": []]), try raw([]), try json(["locations": "bad"]),
                     try raw([point(latitude: 999_000_000)])] {
            XCTAssertThrowsError(try TimelineImportService.parse(data: data, fileName: "file.json"))
        }
        for name in ["history.zip", "history.kml", "history.gpx", "history.geojson"] {
            XCTAssertThrowsError(try TimelineImportService.parse(data: raw([point()]), fileName: name))
        }
        XCTAssertThrowsError(try TimelineImportService.parse(data: Data(count: ImportPayload.maximumFileSize + 1), fileName: "large.json"))
    }

    func testPreviewIsReadOnlyAndRepeatImportSkipsDuplicates() async throws {
        let container = try container()
        let service = TimelineImportService(container: container)
        let data = try raw([point(), point(), point(milliseconds: 1_577_836_801_000)])
        let preview = try await service.preview(data: data, fileName: "Records.json")
        XCTAssertEqual(preview.newPointCount, 2)
        XCTAssertEqual(preview.duplicatePointCount, 1)
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<LocationPoint>()), 0)
        let first = try await service.commit(preview, options: ImportOptions())
        XCTAssertEqual(first.pointCount, 2)
        XCTAssertEqual(first.duplicatePointCount, 1)
        let second = try await service.commit(preview, options: ImportOptions())
        XCTAssertEqual(second.pointCount, 0, "Confirmation must recheck even a stale preview")
        XCTAssertEqual(second.duplicatePointCount, 3)
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<LocationPoint>()), 2)
        let again = try await service.preview(data: data, fileName: "Records.json")
        XCTAssertEqual(again.newPointCount, 0)
        XCTAssertEqual(again.duplicatePointCount, 3)
    }

    func testPointDuplicatesUseRoundedSecondAndTenMeterDistance() async throws {
        let container = try container()
        container.mainContext.insert(LocationPoint(latitude: 37.7, longitude: -122.4, timestamp: time, horizontalAccuracy: 5))
        try container.mainContext.save()
        let service = TimelineImportService(container: container)
        let data = try raw([point(milliseconds: 1_577_836_800_100, latitude: 377_000_010),
                            point(latitude: 377_010_000), point(milliseconds: 1_577_836_801_000)])
        let preview = try await service.preview(data: data, fileName: "Records.json")
        XCTAssertEqual(preview.duplicatePointCount, 1)
        XCTAssertEqual(preview.newPointCount, 2)
    }

    func testVisitMatchingUsesTimeDistanceAndOpenEndednessWithoutChangingExisting() async throws {
        let container = try container()
        let existing = Visit(latitude: 37.7, longitude: -122.4, arrivedAt: time, departedAt: time.addingTimeInterval(3600),
                             locationName: "My reviewed name", source: .manual, confirmationStatus: .confirmed)
        container.mainContext.insert(existing); try container.mainContext.save()
        let service = TimelineImportService(container: container)
        let data = try backup(visits: [
            visit(arrival: "2020-01-01T00:00:30Z", departure: "2020-01-01T01:04:00Z", latitude: 37.7001),
            visit(arrival: "2020-01-01T00:01:01Z"),
            visit(departure: "2020-01-01T01:05:01Z"),
            visit(latitude: 37.702), visit(departure: nil)
        ])
        let preview = try await service.preview(data: data, fileName: "backup.json")
        XCTAssertEqual(preview.duplicateVisitCount, 1)
        XCTAssertEqual(preview.newVisitCount, 4)
        let result = try await service.commit(preview, options: ImportOptions())
        XCTAssertEqual(result.visitCount, 4)
        XCTAssertEqual(existing.locationName, "My reviewed name")
        XCTAssertEqual(existing.confirmationStatus, .confirmed)
        XCTAssertEqual(existing.departedAt, time.addingTimeInterval(3600))
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<Visit>()), 5)
    }

    func testSelectedTypesOnlyAndImportedVisitNeverNeedsGeocoding() async throws {
        let container = try container()
        let service = TimelineImportService(container: container)
        let data = try backup(visits: [visit()], points: [["latitude": 37.7, "longitude": -122.4, "timestamp": "2020-01-01T00:00:00Z"]])
        let preview = try await service.preview(data: data, fileName: "backup.json")
        let result = try await service.commit(preview, options: ImportOptions(includeVisits: true, includePoints: false))
        XCTAssertEqual(result.visitCount, 1)
        XCTAssertEqual(result.pointCount, 0)
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<LocationPoint>()), 0)
        let stored = try XCTUnwrap(container.mainContext.fetch(FetchDescriptor<Visit>()).first)
        XCTAssertEqual(stored.source, .imported)
        XCTAssertTrue(stored.geocodingCompleted)
        do {
            _ = try await service.commit(preview, options: ImportOptions(includeVisits: false, includePoints: false))
            XCTFail("An empty selection must not commit")
        } catch TimelineImportError.nothingSelected {}
    }

    func testCancellationBeforeCommitDoesNotWriteRecords() async throws {
        let container = try container()
        let service = TimelineImportService(container: container)
        let preview = try await service.preview(data: raw([point()]), fileName: "Records.json")
        let task = Task {
            try Task.checkCancellation()
            return try await service.commit(preview, options: ImportOptions())
        }
        task.cancel()
        do { _ = try await task.value; XCTFail("Cancelled import should fail") } catch is CancellationError {}
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<LocationPoint>()), 0)
    }
}

@MainActor
final class ImportReviewValidationTests: XCTestCase {
    private var visit: [String: Any] {
        ["latitude": 12.345, "longitude": 67.89, "arrivedAt": "2020-01-02T10:00:00Z",
         "departedAt": "2020-01-02T11:00:00Z", "locationName": "QA"]
    }
    private var point: [String: Any] {
        ["latitude": 12.345, "longitude": 67.89, "timestamp": "2020-01-02T10:00:00Z", "horizontalAccuracy": 5]
    }
    private func parse(visits: [[String: Any]] = [], points: [[String: Any]] = []) throws -> ImportPayload {
        let data = try JSONSerialization.data(withJSONObject: ["visits": visits, "points": points])
        return try TimelineImportService.parse(data: data, fileName: "review.json")
    }

    func testMalformedDepartureDoesNotBecomeAnOpenVisit() throws {
        for value: Any in ["not-a-date", true, 123, ["unexpected"]] {
            var bad = visit; bad["departedAt"] = value
            XCTAssertThrowsError(try parse(visits: [visit, bad]), "Invalid departure: \(value)")
        }
        let csv = "arrived_at,departed_at,latitude,longitude\n2020-01-02T10:00:00Z,not-a-date,12.345,67.89\n"
        XCTAssertThrowsError(try TimelineImportService.parse(data: Data(csv.utf8), fileName: "review.csv"))
    }

    func testBooleanValuesAreNotJSONNumbers() throws {
        for key in ["latitude", "longitude", "originalLatitude", "originalLongitude", "detectedLatitude", "detectedLongitude", "placeDistanceMeters"] {
            var bad = visit; bad[key] = true
            XCTAssertThrowsError(try parse(visits: [bad]), "Visit field: \(key)")
        }
        for key in ["latitude", "longitude", "timestampUnix", "altitude", "speed", "horizontalAccuracy"] {
            var bad = point; bad[key] = false
            XCTAssertThrowsError(try parse(points: [bad]), "Point field: \(key)")
        }
    }

    func testAbsentNullAndBlankDepartureAndRealNumericValuesRemainSupported() throws {
        var open = visit; open.removeValue(forKey: "departedAt")
        var null = visit; null["departedAt"] = NSNull()
        var blank = visit; blank["departedAt"] = ""
        let payload = try parse(visits: [open, null, blank])
        XCTAssertEqual(payload.visits.count, 3)
        XCTAssertTrue(payload.visits.allSatisfy { $0.departedAt == nil })
        var valid = point
        valid["isOutlier"] = true
        valid["altitude"] = NSNull()
        valid["timestampUnix"] = 1_577_959_200
        XCTAssertEqual(try parse(points: [valid]).points.first?.isOutlier, true)
    }

    func testRejectedPreviewLeavesExistingHistoryUntouched() async throws {
        let container = try ModelContainer(for: Visit.self, LocationPoint.self,
                                          configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let existing = Visit(latitude: 1, longitude: 2, arrivedAt: Date(timeIntervalSince1970: 100), customName: "Keep me")
        container.mainContext.insert(existing)
        try container.mainContext.save()
        var bad = visit; bad["departedAt"] = "broken"
        let data = try JSONSerialization.data(withJSONObject: ["visits": [visit, bad], "points": []])
        do {
            _ = try await TimelineImportService(container: container).preview(data: data, fileName: "mixed.json")
            XCTFail("Malformed input must not be offered for import")
        } catch { }
        let stored = try ModelContext(container).fetch(FetchDescriptor<Visit>())
        XCTAssertEqual(stored.count, 1)
        XCTAssertEqual(stored.first?.id, existing.id)
        XCTAssertEqual(stored.first?.customName, "Keep me")
    }
}
