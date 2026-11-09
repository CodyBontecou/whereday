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
        visit.originalConfirmationStatusRaw = "confirmed"
        visit.originalConfirmedAt = time.addingTimeInterval(-10)
        visit.originalPlaceSourceRaw = "appleMaps"
        visit.originalPlaceDistanceMeters = 123
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
                                        receivedLocationCount: 12, distanceFilterMeters: 5,
                                        routeSamplingModeRawValue: "time", routeSampleIntervalSeconds: 30))
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
        let restoredContext = ModelContext(target)
        let restoredVisit = try XCTUnwrap(restoredContext.fetch(FetchDescriptor<Visit>()).first)
        XCTAssertEqual(restoredVisit.originalConfirmationStatusRaw, "confirmed")
        XCTAssertEqual(restoredVisit.originalConfirmedAt, time.addingTimeInterval(-10))
        XCTAssertEqual(restoredVisit.originalPlaceSourceRaw, "appleMaps")
        XCTAssertEqual(restoredVisit.originalPlaceDistanceMeters, 123)
        let session = try XCTUnwrap(restoredContext.fetch(FetchDescriptor<RecordingSession>()).first)
        XCTAssertEqual(session.routeSamplingModeRawValue, "time")
        XCTAssertEqual(session.routeSampleIntervalSeconds, 30)
        let restoredData = try WheredayBackupService.export(context: restoredContext)
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

    func testLegacyBackupWithoutCorrectionOrSamplingFieldsStillDecodes() throws {
        let source = try container()
        try seed(source.mainContext)
        let data = try WheredayBackupService.export(context: source.mainContext)
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        for (collection, fields) in [
            ("visits", ["originalConfirmationStatusRaw", "originalConfirmedAt", "originalPlaceSourceRaw", "originalPlaceDistanceMeters"]),
            ("recordingSessions", ["routeSamplingModeRawValue", "routeSampleIntervalSeconds"])
        ] {
            var rows = try XCTUnwrap(object[collection] as? [[String: Any]])
            for field in fields { rows[0].removeValue(forKey: field) }
            object[collection] = rows
        }
        let backup = try WheredayBackupService.decode(JSONSerialization.data(withJSONObject: object))
        XCTAssertNil(backup.visits.first?.originalConfirmationStatusRaw)
        XCTAssertNil(backup.visits.first?.originalConfirmedAt)
        XCTAssertNil(backup.visits.first?.originalPlaceSourceRaw)
        XCTAssertNil(backup.visits.first?.originalPlaceDistanceMeters)
        XCTAssertNil(backup.recordingSessions.first?.routeSamplingModeRawValue)
        XCTAssertNil(backup.recordingSessions.first?.routeSampleIntervalSeconds)
        XCTAssertEqual(backup.recordCount, 6)
    }

    func testMalformedBackupAdmissionRejectsWholeFileBeforeAnyWrite() async throws {
        let source = try container()
        try seed(source.mainContext)
        let data = try WheredayBackupService.export(context: source.mainContext)
        let baseline = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
        for (collection, field, value) in [
            ("visits", "departedAt", time.addingTimeInterval(-1).timeIntervalSince1970 * 1000),
            ("recordingSessions", "endedAt", time.addingTimeInterval(-1).timeIntervalSince1970 * 1000),
            ("points", "horizontalAccuracy", -100.0),
            ("visits", "arrivedAt", Date.distantFuture.addingTimeInterval(1).timeIntervalSince1970 * 1000),
            ("points", "timestamp", Date.distantPast.addingTimeInterval(-1).timeIntervalSince1970 * 1000)
        ] {
            var object = baseline
            var rows = try XCTUnwrap(object[collection] as? [[String: Any]])
            rows[0][field] = value
            if field == "arrivedAt" { rows[0]["departedAt"] = NSNull() }
            object[collection] = rows
            let target = try container()
            let worker = TimelineImportService(container: target)
            do {
                _ = try await worker.preview(data: JSONSerialization.data(withJSONObject: object), fileName: "backup.json")
                XCTFail("Accepted invalid \(collection).\(field)")
            } catch {
                XCTAssertEqual(try WheredayBackupService.decode(WheredayBackupService.export(context: ModelContext(target))).recordCount, 0)
            }
        }
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
    private func temporaryDirectory() -> URL {
        FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
    }

    func testMultipartBackupRestoresAllFieldsThroughPublicFileImporter() async throws {
        let source = try container()
        try seed(source.mainContext)
        let original = try WheredayBackupService.export(context: source.mainContext)
        let directory = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let files = try WheredayBackupService.writeFiles(WheredayBackupService.decode(original),
                                                        directory: directory, maximumRecords: 2)
        XCTAssertEqual(files.count, 4) // Balanced partitions, never more than two records each.
        for file in files {
            let part = try WheredayBackupService.decode(Data(contentsOf: file))
            XCTAssertLessThanOrEqual(part.recordCount, 2)
            XCTAssertEqual(part.part?.count, files.count)
        }
        let target = try container()
        let worker = TimelineImportService(container: target)
        let preview = try await worker.preview(urls: files.reversed())
        XCTAssertEqual(preview.newVisitCount + preview.newPointCount + preview.newAdditionalRecordCount, 6)
        _ = try await worker.commit(preview, options: ImportOptions())
        let restored = try WheredayBackupService.export(context: ModelContext(target))
        func records(_ data: Data) throws -> NSDictionary {
            var root = try XCTUnwrap(JSONSerialization.jsonObject(with: data) as? [String: Any])
            root.removeValue(forKey: "exportedAt")
            return root as NSDictionary
        }
        XCTAssertEqual(try records(original), try records(restored))
        let repeated = try await worker.commit(preview, options: ImportOptions())
        XCTAssertEqual(repeated.visitCount + repeated.pointCount + repeated.additionalRecordCount, 0)
        XCTAssertEqual(repeated.duplicateVisitCount + repeated.duplicatePointCount + repeated.duplicateAdditionalRecordCount, 6)
    }

    func testIncompleteDuplicateAndMixedBackupSetsCannotModifyExistingStorage() async throws {
        let source = try container()
        try seed(source.mainContext)
        let backup = try WheredayBackupService.decode(WheredayBackupService.export(context: source.mainContext))
        let first = temporaryDirectory(), second = temporaryDirectory()
        defer {
            try? FileManager.default.removeItem(at: first)
            try? FileManager.default.removeItem(at: second)
        }
        let files = try WheredayBackupService.writeFiles(backup, directory: first, maximumRecords: 2)
        let otherFiles = try WheredayBackupService.writeFiles(backup, directory: second, maximumRecords: 2)
        let target = try container()
        try seed(target.mainContext)
        let before = try WheredayBackupService.export(context: target.mainContext)
        let worker = TimelineImportService(container: target)
        for selected in [Array(files.dropLast()), [files[0]],
                         [files[0]] + Array(files.dropLast()),
                         [otherFiles[0]] + Array(files.dropFirst())] {
            do {
                let preview = try await worker.preview(urls: selected)
                _ = try await worker.commit(preview, options: ImportOptions())
                XCTFail("Accepted an incomplete, duplicate, or mixed backup set")
            } catch {
                let after = try WheredayBackupService.export(context: ModelContext(target))
                var old = try XCTUnwrap(JSONSerialization.jsonObject(with: before) as? [String: Any])
                var new = try XCTUnwrap(JSONSerialization.jsonObject(with: after) as? [String: Any])
                old.removeValue(forKey: "exportedAt")
                new.removeValue(forKey: "exportedAt")
                XCTAssertEqual(old as NSDictionary, new as NSDictionary)
            }
        }
    }

    func testMalformedLastBackupPartRejectsWholeSet() async throws {
        let source = try container()
        try seed(source.mainContext)
        let directory = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let backup = try WheredayBackupService.decode(WheredayBackupService.export(context: source.mainContext))
        let files = try WheredayBackupService.writeFiles(backup, directory: directory, maximumRecords: 2)
        let last = try XCTUnwrap(files.last)
        var object = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: last)) as? [String: Any])
        var stays = try XCTUnwrap(object["countryStays"] as? [[String: Any]])
        stays[0]["lastDay"] = "2020-01-01"
        object["countryStays"] = stays
        try JSONSerialization.data(withJSONObject: object).write(to: last)
        let target = try container()
        let worker = TimelineImportService(container: target)
        do {
            _ = try await worker.preview(urls: files)
            XCTFail("Accepted malformed final part")
        } catch {
            XCTAssertEqual(try WheredayBackupService.decode(WheredayBackupService.export(context: target.mainContext)).recordCount, 0)
        }
    }

    func testBackupLargerThanFiftyMiBSplitsIntoBoundedRestorableFiles() async throws {
        let source = try container()
        // Each record fits; their complete backup exceeds the ordinary file limit.
        let notes = String(repeating: "x", count: 10 * 1_024 * 1_024)
        for index in 0..<6 {
            source.mainContext.insert(SavedPlace(name: "Large \(index)", latitude: 0, longitude: 0, notes: notes))
        }
        try source.mainContext.save()
        let directory = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        XCTAssertThrowsError(try WheredayBackupService.export(context: source.mainContext))
        let files = try WheredayBackupService.exportFiles(context: source.mainContext, directory: directory)
        XCTAssertGreaterThan(files.count, 1)
        var size = 0
        for file in files {
            let count = try Data(contentsOf: file).count
            XCTAssertLessThanOrEqual(count, ImportPayload.maximumFileSize)
            size += count
        }
        XCTAssertGreaterThan(size, ImportPayload.maximumFileSize)
        let target = try container()
        let worker = TimelineImportService(container: target)
        let preview = try await worker.preview(urls: files)
        _ = try await worker.commit(preview, options: ImportOptions())
        let restored = try ModelContext(target).fetch(FetchDescriptor<SavedPlace>())
        XCTAssertEqual(Set(restored.map(\.name)), Set((0..<6).map { "Large \($0)" }))
        XCTAssertTrue(restored.allSatisfy { $0.notes == notes })
    }

    func testAggregateRecordLimitDoesNotCapMultipartBackup() throws {
        // Decode directly into the value snapshot to exercise the real threshold
        // without constructing 250,001 SwiftData objects just for file planning.
        let count = ImportPayload.maximumRecordCount + 1
        var backup = WheredayBackup()
        let rows: [[String: Any]] = (0..<count).map { _ in
            ["id": UUID().uuidString, "latitude": 0, "longitude": 0, "timestamp": 1_780_000_000_125,
             "horizontalAccuracy": 5, "isOutlier": false]
        }
        let decoder = JSONDecoder()
        decoder.dateDecodingStrategy = .millisecondsSince1970
        backup.points = try decoder.decode([WheredayBackup.LocationPointRecord].self,
                                          from: JSONSerialization.data(withJSONObject: rows))
        let directory = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let files = try WheredayBackupService.writeFiles(backup, directory: directory)
        XCTAssertGreaterThan(files.count, 1)
        let decoded = try files.map { try WheredayBackupService.decode(Data(contentsOf: $0)) }
        XCTAssertTrue(decoded.allSatisfy { $0.recordCount <= ImportPayload.maximumRecordCount })
        let combined = try WheredayBackupService.combine(decoded)
        XCTAssertEqual(combined.recordCount, count)
        XCTAssertEqual(Set(combined.points.map(\.id)), Set(backup.points.map(\.id)))
        let encoder = JSONEncoder()
        encoder.dateEncodingStrategy = .millisecondsSince1970
        let oversizedSingleFile = try encoder.encode(backup)
        XCTAssertLessThan(oversizedSingleFile.count, ImportPayload.maximumFileSize)
        XCTAssertThrowsError(try TimelineImportService.parse(data: oversizedSingleFile, fileName: "backup.json"))
    }

    func testMultipartVersionRejectsLegacyReadersAndDuplicateIDsAcrossParts() async throws {
        let source = try container()
        source.mainContext.insert(SavedPlace(name: "One", latitude: 0, longitude: 0))
        source.mainContext.insert(SavedPlace(name: "Two", latitude: 0, longitude: 0))
        try source.mainContext.save()
        let backup = try WheredayBackupService.decode(WheredayBackupService.export(context: source.mainContext))
        let directory = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        let files = try WheredayBackupService.writeFiles(backup, directory: directory, maximumRecords: 1)
        let first = try WheredayBackupService.decode(Data(contentsOf: files[0]))
        XCTAssertEqual(first.formatVersion, 2)
        var root = try XCTUnwrap(JSONSerialization.jsonObject(with: Data(contentsOf: files[1])) as? [String: Any])
        var rows = try XCTUnwrap(root["savedPlaces"] as? [[String: Any]])
        rows[0]["id"] = try XCTUnwrap(first.savedPlaces.first?.id.uuidString)
        root["savedPlaces"] = rows
        try JSONSerialization.data(withJSONObject: root).write(to: files[1])
        let target = try container()
        let worker = TimelineImportService(container: target)
        do {
            _ = try await worker.preview(urls: files)
            XCTFail("Accepted duplicate IDs across backup parts")
        } catch {
            XCTAssertEqual(try ModelContext(target).fetchCount(FetchDescriptor<SavedPlace>()), 0)
        }
    }

    func testFailedExportCleansNewDirectoryAndPreservesExistingDestination() throws {
        let source = try container()
        try seed(source.mainContext)
        let backup = try WheredayBackupService.decode(WheredayBackupService.export(context: source.mainContext))
        let directory = temporaryDirectory()
        defer { try? FileManager.default.removeItem(at: directory) }
        XCTAssertThrowsError(try WheredayBackupService.writeFiles(backup, directory: directory, maximumBytes: 128))
        XCTAssertFalse(FileManager.default.fileExists(atPath: directory.path))
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: false)
        let sentinel = directory.appendingPathComponent("existing.json")
        try Data("keep".utf8).write(to: sentinel)
        XCTAssertThrowsError(try WheredayBackupService.writeFiles(backup, directory: directory))
        XCTAssertEqual(try String(contentsOf: sentinel, encoding: .utf8), "keep")
    }

}
