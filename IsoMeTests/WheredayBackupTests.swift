import XCTest
import SwiftData
@testable import IsoMe

@MainActor
final class WheredayBackupTests: XCTestCase {
    private let time = Date(timeIntervalSince1970: 1_780_000_000.125)

    private func container() throws -> ModelContainer {
        try ModelContainer(for: Visit.self, LocationPoint.self, RecordingSession.self,
                           SavedPlace.self, PhotoMoment.self, CountryStay.self,
                           configurations: ModelConfiguration(isStoredInMemoryOnly: true))
    }

    private func seed(_ context: ModelContext) throws {
        let visit = Visit(latitude: 37.7, longitude: -122.4, arrivedAt: time,
                          departedAt: time.addingTimeInterval(600), customName: "Café ☕️",
                          locationName: "Detected name", address: "Address", notes: "Visit notes",
                          geocodingCompleted: true)
        visit.sourceRaw = "manual"
        visit.confirmationStatusRaw = "confirmed"
        visit.confirmedAt = time.addingTimeInterval(10)
        visit.updatedAt = time.addingTimeInterval(20)
        visit.originalLatitude = 37.8
        visit.originalLongitude = -122.5
        visit.originalLocationName = "Original"
        visit.originalAddress = "Original address"
        visit.detectedLatitude = 37.9
        visit.detectedLongitude = -122.6
        visit.detectedLocationName = "Detected"
        visit.detectedAddress = "Detected address"
        visit.placeSourceRaw = "manual"
        visit.placeDistanceMeters = 42
        visit.countryCode = "US"
        visit.countryTimeZoneIdentifier = "America/Los_Angeles"
        visit.countryResolutionAttemptedAt = time
        visit.countryDepartureIsInferred = true
        context.insert(visit)
        let point = LocationPoint(latitude: 37.7, longitude: -122.4, timestamp: time,
                                  altitude: 13, speed: 2.5, horizontalAccuracy: 8, isOutlier: true)
        point.countryCode = "US"
        point.countryTimeZoneIdentifier = "America/Los_Angeles"
        point.countryResolutionAttemptedAt = time
        context.insert(point)
        context.insert(RecordingSession(startedAt: time, endedAt: time.addingTimeInterval(900),
                                        customName: "Outing", notes: "Session notes",
                                        receivedLocationCount: 12, distanceFilterMeters: 5))
        context.insert(SavedPlace(name: "Home", latitude: 37.7, longitude: -122.4,
                                  address: "Home address", radiusMeters: 900, kind: .home,
                                  notes: "Place notes", createdAt: time, updatedAt: time))
        context.insert(PhotoMoment(assetLocalIdentifier: "local-photo-id", takenAt: time,
                                   latitude: 37.7, longitude: -122.4,
                                   coordinateSource: .photoGPS, lastSyncedAt: time))
        context.insert(CountryStay(countryCode: "JP", firstDay: "2026-04-06",
                                   lastDay: "2026-04-21", note: "Trip note"))
        try context.save()
    }

    /// Export -> the public import review/commit path -> export must preserve every
    /// stored field, including IDs, attribution, country metadata, and session counts.
    func testFullBackupRoundTripsEveryErasedModelThroughImporter() async throws {
        let source = try container()
        try seed(source.mainContext)
        let data = try WheredayBackupService.export(context: source.mainContext)
        let backup = try WheredayBackupService.decode(data)
        XCTAssertEqual(backup.recordCount, 6)
        XCTAssertEqual(backup.additionalRecordCount, 4)
        let target = try container()
        let worker = TimelineImportService(container: target)
        let preview = try await worker.preview(data: data, fileName: "Whereday Backup.json")
        XCTAssertEqual(preview.payload.format, .wheredayBackup)
        XCTAssertEqual(preview.newAdditionalRecordCount, 4)
        let result = try await worker.commit(preview, options: ImportOptions())
        XCTAssertEqual(result.visitCount, 1)
        XCTAssertEqual(result.pointCount, 1)
        XCTAssertEqual(result.additionalRecordCount, 4)
        let restoredData = try WheredayBackupService.export(context: ModelContext(target))
        var original = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        var restored = try XCTUnwrap(JSONSerialization.jsonObject(with: restoredData) as? [String: Any])
        original.removeValue(forKey: "exportedAt")
        restored.removeValue(forKey: "exportedAt")
        XCTAssertEqual(try JSONSerialization.data(withJSONObject: original, options: .sortedKeys),
                       try JSONSerialization.data(withJSONObject: restored, options: .sortedKeys))
    }

    func testBackupWithOnlySavedPlacesAndCountryStaysIsRestorable() async throws {
        let source = try container()
        source.mainContext.insert(SavedPlace(name: "Home", latitude: 0, longitude: 0))
        source.mainContext.insert(CountryStay(countryCode: "JP", firstDay: "2026-04-06", lastDay: "2026-04-21"))
        try source.mainContext.save()
        let data = try WheredayBackupService.export(context: source.mainContext)
        let target = try container()
        let worker = TimelineImportService(container: target)
        let preview = try await worker.preview(data: data, fileName: "backup.json")
        XCTAssertEqual(preview.newVisitCount, 0)
        XCTAssertEqual(preview.newPointCount, 0)
        XCTAssertEqual(preview.newAdditionalRecordCount, 2)
        let result = try await worker.commit(preview, options: ImportOptions(includeVisits: false, includePoints: false))
        XCTAssertEqual(result.additionalRecordCount, 2)
        XCTAssertEqual(try ModelContext(target).fetchCount(FetchDescriptor<CountryStay>()), 1)
    }

    func testRepeatedAndStaleReviewsSkipIDsWithoutOverwritingEdits() async throws {
        let source = try container()
        try seed(source.mainContext)
        let data = try WheredayBackupService.export(context: source.mainContext)
        let target = try container()
        let worker = TimelineImportService(container: target)
        let stale = try await worker.preview(data: data, fileName: "backup.json")
        _ = try await worker.commit(stale, options: ImportOptions())
        let context = ModelContext(target)
        let place = try XCTUnwrap(context.fetch(FetchDescriptor<SavedPlace>()).first)
        place.name = "Edited after restore"
        try context.save()
        let result = try await worker.commit(stale, options: ImportOptions())
        XCTAssertEqual(result.visitCount + result.pointCount + result.additionalRecordCount, 0)
        XCTAssertEqual(result.duplicateVisitCount + result.duplicatePointCount + result.duplicateAdditionalRecordCount, 6)
        let fresh = try await worker.preview(data: data, fileName: "backup.json")
        XCTAssertEqual(fresh.newVisitCount + fresh.newPointCount + fresh.newAdditionalRecordCount, 0)
        XCTAssertEqual(try ModelContext(target).fetch(FetchDescriptor<SavedPlace>()).first?.name, "Edited after restore")
    }

    func testMalformedOrFutureBackupCannotFallThroughToPartialVisitImport() throws {
        let source = try container()
        try seed(source.mainContext)
        let data = try WheredayBackupService.export(context: source.mainContext)
        let object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        var future = object
        future["formatVersion"] = 999
        XCTAssertThrowsError(try TimelineImportService.parse(data: JSONSerialization.data(withJSONObject: future), fileName: "backup.json"))
        var partial = object
        partial.removeValue(forKey: "countryStays")
        XCTAssertThrowsError(try TimelineImportService.parse(data: JSONSerialization.data(withJSONObject: partial), fileName: "backup.json"))
        var invalid = object
        var places = try XCTUnwrap(invalid["savedPlaces"] as? [[String: Any]])
        places[0]["latitude"] = 91
        invalid["savedPlaces"] = places
        XCTAssertThrowsError(try TimelineImportService.parse(data: JSONSerialization.data(withJSONObject: invalid), fileName: "backup.json"))
        var duplicate = object
        let stays = try XCTUnwrap(object["countryStays"] as? [[String: Any]])
        duplicate["countryStays"] = stays + stays
        XCTAssertThrowsError(try TimelineImportService.parse(data: JSONSerialization.data(withJSONObject: duplicate), fileName: "backup.json"))
    }

    func testCancelledRestoreSavesNothing() async throws {
        let source = try container()
        try seed(source.mainContext)
        let data = try WheredayBackupService.export(context: source.mainContext)
        let target = try container()
        let worker = TimelineImportService(container: target)
        let preview = try await worker.preview(data: data, fileName: "backup.json")
        let task = Task {
            withUnsafeCurrentTask { $0?.cancel() }
            return try await worker.commit(preview, options: ImportOptions())
        }
        do {
            _ = try await task.value
            XCTFail("Expected cancellation")
        } catch is CancellationError {
            let restored = try WheredayBackupService.decode(WheredayBackupService.export(context: ModelContext(target)))
            XCTAssertEqual(restored.recordCount, 0)
        }
    }
}
