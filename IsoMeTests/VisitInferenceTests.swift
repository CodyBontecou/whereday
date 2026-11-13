import XCTest
import SwiftData
@testable import IsoMe

final class VisitInferenceTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_577_836_800)
    private func point(_ seconds: Double, latitude: Double = 18, longitude: Double = -66,
                       accuracy: Double = 8, outlier: Bool = false) -> ImportedLocationPoint {
        ImportedLocationPoint(latitude: latitude, longitude: longitude, timestamp: start.addingTimeInterval(seconds),
                              altitude: nil, speed: nil, horizontalAccuracy: accuracy, isOutlier: outlier)
    }
    private func stationary(offset: Double = 0) -> [ImportedLocationPoint] {
        (0...5).map { point(offset + Double($0) * 180) }
    }

    func testStationaryUsesSortedRealEndpointsAndIsDeterministic() throws {
        let points = stationary()
        let a = try VisitInferenceService.infer(points: points.reversed())
        let b = try VisitInferenceService.infer(points: points)
        XCTAssertEqual(a.count, 1)
        XCTAssertEqual(a[0].arrivedAt, start)
        XCTAssertEqual(a[0].departedAt, start.addingTimeInterval(900))
        XCTAssertEqual(a[0].latitude, b[0].latitude)
        XCTAssertEqual(a[0].longitude, -66, accuracy: 0.000001)
        XCTAssertNil(a[0].locationName)
        XCTAssertEqual(a[0].sourceRaw, "imported")
    }

    func testMovingPointsNeverBecomeVisits() throws {
        let points = (0...20).map { point(Double($0) * 180, latitude: 18 + Double($0) * 0.002) }
        XCTAssertTrue(try VisitInferenceService.infer(points: points).isEmpty)
    }

    func testSparseAndRepeatedSamplesDoNotEstablishDwell() throws {
        XCTAssertTrue(try VisitInferenceService.infer(points: [point(0), point(900)]).isEmpty)
        XCTAssertTrue(try VisitInferenceService.infer(points: Array(repeating: point(0), count: 20) + [point(900)]).isEmpty)
    }

    func testImpossibleJumpsAndOutliersCannotEstablishDwell() throws {
        let jumps = (0...30).map { point(Double($0) * 60, latitude: $0.isMultiple(of: 2) ? 18 : 30) }
        XCTAssertTrue(try VisitInferenceService.infer(points: jumps).isEmpty)
        let flagged = (0...5).map { point(Double($0) * 180, outlier: true) }
        XCTAssertTrue(try VisitInferenceService.infer(points: flagged).isEmpty)
    }

    func testLongGapsDoNotBridgeSparseEvidenceOrMergeSessions() throws {
        XCTAssertTrue(try VisitInferenceService.infer(points: [point(0), point(180), point(360), point(1200), point(1380), point(1560)]).isEmpty)
        XCTAssertEqual(try VisitInferenceService.infer(points: stationary() + stationary(offset: 1800)).count, 2)
    }

    func testPoorAndUnknownAccuracyBreakEvidenceEvenBetweenGoodEndpoints() throws {
        for accuracy in [-1.0, 101, .infinity, .nan] {
            let points = [point(0), point(180), point(360), point(540, accuracy: accuracy), point(720), point(900)]
            XCTAssertTrue(try VisitInferenceService.infer(points: points).isEmpty)
        }
    }

    func testConfigurableRadiusDwellAccuracyAndGap() throws {
        var options = VisitInferenceOptions()
        options.minimumDwellSeconds = 600
        options.maximumGapSeconds = 200
        options.radiusMeters = 100
        options.maximumAccuracyMeters = 20
        let points = (0...4).map { point(Double($0) * 150, accuracy: 15) }
        XCTAssertEqual(try VisitInferenceService.infer(points: points, options: options).count, 1)
        options.maximumAccuracyMeters = 10
        XCTAssertTrue(try VisitInferenceService.infer(points: points, options: options).isEmpty)
    }

    func testInverseAccuracyCentroidStaysNearBetterEvidence() throws {
        let points = (0...5).map { point(Double($0) * 180, latitude: $0 == 0 ? 18 : 18.0001, accuracy: $0 == 0 ? 10 : 100) }
        let visit = try XCTUnwrap(VisitInferenceService.infer(points: points).first)
        XCTAssertGreaterThan(visit.latitude, 18)
        XCTAssertLessThan(visit.latitude, 18.00005)
    }

    func testNearbyAdjacentDwellsMergeOnlyWithinSameContinuousSession() throws {
        let first = (0...5).map { point(Double($0) * 180, latitude: $0 == 0 ? 17.99945 : 18) }
        let second = (0...5).map { point(960 + Double($0) * 180, latitude: $0 == 0 ? 18.0009 : 18.00045) }
        let visits = try VisitInferenceService.infer(points: first + second)
        XCTAssertEqual(visits.count, 1)
        XCTAssertEqual(visits.first?.arrivedAt, start)
        XCTAssertEqual(visits.first?.departedAt, start.addingTimeInterval(1860))
    }

    func testDiscardedExcursionCannotBeHiddenByMergingDwells() throws {
        let points = stationary() + [point(960, latitude: 18.0018)] + stationary(offset: 1020)
        let visits = try VisitInferenceService.infer(points: points)
        XCTAssertEqual(visits.count, 2)
        XCTAssertEqual(visits[0].departedAt, start.addingTimeInterval(900))
        XCTAssertEqual(visits[1].arrivedAt, start.addingTimeInterval(1020))
    }

    func testDifferentPlacesDoNotMergeDespiteShortGap() throws {
        let first = stationary()
        let second = (0...5).map { point(960 + Double($0) * 180, latitude: 18.002) }
        XCTAssertEqual(try VisitInferenceService.infer(points: first + second).count, 2)
    }

    func testSemanticWindowsPreventSpanningVisitsWithoutInventingTimes() throws {
        let windows = [start.addingTimeInterval(400)...start.addingTimeInterval(500)]
        XCTAssertTrue(try VisitInferenceService.infer(points: stationary(), excluding: windows).isEmpty)
        let outside = try VisitInferenceService.infer(points: stationary() + stationary(offset: 1800), excluding: [start...start.addingTimeInterval(900)])
        XCTAssertEqual(outside.count, 1)
        XCTAssertEqual(outside[0].arrivedAt, start.addingTimeInterval(1800))
    }

    func testConflictingEqualTimestampsCannotCreateFakeDuration() throws {
        let points = stationary() + stationary().map { point($0.timestamp.timeIntervalSince(start), latitude: 30) }
        XCTAssertTrue(try VisitInferenceService.infer(points: points).isEmpty)
    }

    func testSphericalCentroidHandlesDateLine() throws {
        let points = (0...5).map { point(Double($0) * 180, latitude: 0, longitude: $0.isMultiple(of: 2) ? 179.9999 : -179.9999) }
        let visit = try XCTUnwrap(VisitInferenceService.infer(points: points).first)
        XCTAssertGreaterThan(abs(visit.longitude), 179.99)
    }

    func testInvalidOptionsFailSafely() throws {
        var options = VisitInferenceOptions()
        for value in [Double.nan, .infinity, -1, 0, 501] {
            options.radiusMeters = value
            XCTAssertThrowsError(try VisitInferenceService.infer(points: stationary(), options: options))
        }
        options = .init(); options.minimumDwellSeconds = 0
        XCTAssertThrowsError(try VisitInferenceService.infer(points: [], options: options))
        options = .init(); options.maximumAccuracyMeters = -1
        XCTAssertThrowsError(try VisitInferenceService.infer(points: [], options: options))
        options = .init(); options.maximumGapSeconds = 901
        XCTAssertThrowsError(try VisitInferenceService.infer(points: [], options: options))
    }

    func testCancelledInferenceStopsBeforeProducingVisits() async throws {
        let points = stationary()
        let task = Task { try Task.checkCancellation(); return try VisitInferenceService.infer(points: points) }
        task.cancel()
        do { _ = try await task.value; XCTFail("Cancellation must propagate") } catch is CancellationError { }
    }
}

@MainActor
final class VisitInferenceImportTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_577_836_800)
    private func container() throws -> ModelContainer {
        try ModelContainer(for: Visit.self, LocationPoint.self, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
    }
    private func raw(accuracy: Double = 8) throws -> Data {
        try JSONSerialization.data(withJSONObject: ["locations": (0...5).map {
            ["latitudeE7": 180_000_000, "longitudeE7": -660_000_000,
             "timestampMs": 1_577_836_800_000 + $0 * 180_000, "accuracy": accuracy] as [String: Any]
        }])
    }

    func testRawDefaultsOnReadOnlyPreviewAndRepeatImportDeduplicatesInferredVisit() async throws {
        let store = try container(), worker = TimelineImportService(container: try container())
        let service = TimelineImportService(container: store)
        let preview = try await service.preview(data: raw(), fileName: "Records.json")
        XCTAssertEqual(preview.inferredVisitCount, 1)
        XCTAssertEqual(preview.newVisitCount, 1)
        XCTAssertEqual(preview.newPointCount, 6)
        XCTAssertEqual(try store.mainContext.fetchCount(FetchDescriptor<Visit>()), 0)
        _ = try await service.commit(preview, options: .init())
        let again = try await service.preview(data: raw(), fileName: "Records.json")
        XCTAssertEqual(again.newVisitCount, 0)
        XCTAssertEqual(again.duplicateVisitCount, 1)
        let repeatResult = try await service.commit(again, options: .init())
        XCTAssertEqual(repeatResult.visitCount + repeatResult.pointCount, 0)
        XCTAssertEqual(try store.mainContext.fetchCount(FetchDescriptor<Visit>()), 1)
        // A different worker/container has no hidden service state.
        let independent = try await worker.preview(data: raw(), fileName: "Records.json")
        XCTAssertEqual(independent.newVisitCount, 1)
    }

    func testOptionOffAndReviewBeforeChangedPolicyCanSave() async throws {
        let store = try container()
        let service = TimelineImportService(container: store)
        var off = ImportOptions(); off.inferVisits = false
        let preview = try await service.preview(data: raw(), fileName: "Records.json", options: off)
        XCTAssertEqual(preview.inferredVisitCount, 0)
        XCTAssertEqual(preview.newVisitCount, 0)
        var on = off; on.inferVisits = true
        do { _ = try await service.commit(preview, options: on); XCTFail("Must review new inferred counts") }
        catch VisitInferenceError.reviewRequired { }
        let reviewed = try await service.review(preview, options: on)
        XCTAssertEqual(reviewed.inferredVisitCount, 1)
        let result = try await service.commit(reviewed, options: on)
        XCTAssertEqual(result.visitCount, 1)
        XCTAssertEqual(try store.mainContext.fetchCount(FetchDescriptor<Visit>()), 1)
    }

    func testPoorAccuracyRouteOptInNeverEnablesInferenceFromPoorSamples() async throws {
        let service = TimelineImportService(container: try container())
        let preview = try await service.preview(data: raw(accuracy: 200), fileName: "Records.json")
        XCTAssertEqual(preview.filteredPointCount, 6)
        XCTAssertEqual(preview.newPointCount + preview.inferredVisitCount, 0)
        var options = ImportOptions(); options.includePoorAccuracyPoints = true
        let reviewed = try await service.review(preview, options: options)
        XCTAssertEqual(reviewed.newPointCount, 6)
        XCTAssertEqual(reviewed.inferredVisitCount, 0)
    }

    func testUnknownAccuracyRemainsRouteOnlyInBothPoorAccuracyToggleStates() async throws {
        let store = try container()
        let service = TimelineImportService(container: store)
        var root = try XCTUnwrap(JSONSerialization.jsonObject(with: raw()) as? [String: Any])
        var rows = try XCTUnwrap(root["locations"] as? [[String: Any]])
        for index in rows.indices { rows[index].removeValue(forKey: "accuracy") }
        root["locations"] = rows
        let data = try JSONSerialization.data(withJSONObject: root)
        let preview = try await service.preview(data: data, fileName: "unmeasured.json")
        XCTAssertEqual(preview.newPointCount, 6)
        XCTAssertEqual(preview.unknownAccuracyPointCount, 6)
        XCTAssertEqual(preview.filteredPointCount, 0)
        XCTAssertEqual(preview.inferredVisitCount, 0)
        var options = ImportOptions(); options.includePoorAccuracyPoints = true
        let reviewed = try await service.review(preview, options: options)
        XCTAssertEqual(reviewed.newPointCount, 6)
        XCTAssertEqual(reviewed.unknownAccuracyPointCount, 6)
        XCTAssertEqual(reviewed.filteredPointCount + reviewed.inferredVisitCount, 0)
        XCTAssertEqual(try store.mainContext.fetchCount(FetchDescriptor<LocationPoint>()), 0)
        let result = try await service.commit(reviewed, options: options)
        XCTAssertEqual(result.pointCount, 6)
        XCTAssertEqual(result.visitCount, 0)
        XCTAssertTrue(try store.mainContext.fetch(FetchDescriptor<LocationPoint>()).allSatisfy { $0.horizontalAccuracy == -1 })
    }

    func testNonfiniteAccuracyIsRejectedNotTreatedAsUnknownOrPoorOptIn() async throws {
        let store = try container()
        let service = TimelineImportService(container: store)
        var root = try XCTUnwrap(JSONSerialization.jsonObject(with: raw()) as? [String: Any])
        let rows = try XCTUnwrap(root["locations"] as? [[String: Any]])
        var bad = rows
        for index in bad.indices { bad[index]["accuracy"] = index.isMultiple(of: 2) ? "NaN" : "Infinity" }
        root["locations"] = [rows[0]] + bad
        var options = ImportOptions(); options.includePoorAccuracyPoints = true
        let preview = try await service.preview(data: JSONSerialization.data(withJSONObject: root), fileName: "invalid.json", options: options)
        XCTAssertEqual(preview.payload.invalidRecordCount, 6)
        XCTAssertEqual(preview.newPointCount, 1)
        XCTAssertEqual(preview.unknownAccuracyPointCount, 0)
        XCTAssertEqual(preview.inferredVisitCount, 0)
        root["locations"] = bad
        do {
            _ = try await service.preview(data: JSONSerialization.data(withJSONObject: root), fileName: "all-invalid.json", options: options)
            XCTFail("Nonfinite-only input must not produce a preview")
        } catch TimelineImportError.noRecords { }
        XCTAssertEqual(try store.mainContext.fetchCount(FetchDescriptor<LocationPoint>()), 0)
    }

    func testExistingSemanticCoverageBeginningBeforeRawRangeSuppressesInference() async throws {
        let store = try container()
        store.mainContext.insert(Visit(latitude: 18, longitude: -66, arrivedAt: start.addingTimeInterval(-3600),
                                       departedAt: start.addingTimeInterval(3600), source: .imported))
        try store.mainContext.save()
        let service = TimelineImportService(container: store)
        let preview = try await service.preview(data: raw(), fileName: "Records.json")
        XCTAssertEqual(preview.inferredVisitCount, 0)
        XCTAssertEqual(preview.suppressedInferredVisitCount, 1)
        XCTAssertEqual(try store.mainContext.fetchCount(FetchDescriptor<Visit>()), 1)
    }

    func testMixedGoogleDefaultsOffAndOptInProtectsSelectedSemanticWindow() async throws {
        let store = try container()
        let service = TimelineImportService(container: store)
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let rawURL = directory.appendingPathComponent("Records.json")
        let semanticURL = directory.appendingPathComponent("2020_JANUARY.json")
        try raw().write(to: rawURL)
        let semantic = try JSONSerialization.data(withJSONObject: ["timelineObjects": [["placeVisit": [
            "location": ["latitudeE7": 180_000_000, "longitudeE7": -660_000_000],
            "duration": ["startTimestamp": "2019-12-31T23:00:00Z", "endTimestamp": "2020-01-01T01:00:00Z"]
        ]]]])
        try semantic.write(to: semanticURL)
        let preview = try await service.preview(urls: [semanticURL, rawURL])
        XCTAssertFalse(preview.options.inferenceEnabled(for: preview.payload))
        XCTAssertEqual(preview.newVisitCount, 1)
        XCTAssertEqual(preview.inferredVisitCount, 0)
        XCTAssertEqual(preview.sourceByteCount, Int64(try raw().count + semantic.count))
        var on = ImportOptions(); on.inferVisits = true
        let reviewed = try await service.review(preview, options: on)
        XCTAssertEqual(reviewed.inferredVisitCount, 0)
        XCTAssertEqual(reviewed.newVisitCount, 1)
        XCTAssertEqual(try store.mainContext.fetchCount(FetchDescriptor<Visit>()), 0)
    }

    func testInferOnlySelectionUsesRealEndpointsAndNeverRequestsGeocoding() async throws {
        let store = try container()
        let service = TimelineImportService(container: store)
        let preview = try await service.preview(data: raw(), fileName: "Records.json")
        var options = ImportOptions(); options.includePoints = false
        let result = try await service.commit(preview, options: options)
        XCTAssertEqual(result.visitCount, 1)
        XCTAssertEqual(result.pointCount, 0)
        let saved = try XCTUnwrap(store.mainContext.fetch(FetchDescriptor<Visit>()).first)
        XCTAssertEqual(saved.arrivedAt, start)
        XCTAssertEqual(saved.departedAt, start.addingTimeInterval(900))
        XCTAssertTrue(saved.geocodingCompleted)
        XCTAssertNil(saved.locationName)
    }

    func testLegacyBackupPointsNeverTriggerInferenceOrAccuracyFiltering() async throws {
        let data = try JSONSerialization.data(withJSONObject: ["points": (0...5).map {
            ["latitude": 18, "longitude": -66, "timestampUnix": 1_577_836_800 + $0 * 180, "horizontalAccuracy": 200]
        }])
        let service = TimelineImportService(container: try container())
        var options = ImportOptions(); options.inferVisits = true
        let preview = try await service.preview(data: data, fileName: "backup.json", options: options)
        XCTAssertEqual(preview.inferredVisitCount, 0)
        XCTAssertEqual(preview.newPointCount, 6)
        XCTAssertEqual(preview.filteredPointCount, 0)
    }

    func testInvalidOptionsAndCancelledCommitDoNotPersist() async throws {
        let store = try container()
        let service = TimelineImportService(container: store)
        var options = ImportOptions(); options.inference.radiusMeters = .nan
        do { _ = try await service.preview(data: raw(), fileName: "Records.json", options: options); XCTFail("Invalid options") }
        catch VisitInferenceError.invalidOptions { }
        let preview = try await service.preview(data: raw(), fileName: "Records.json")
        let task = Task { try Task.checkCancellation(); return try await service.commit(preview, options: .init()) }
        task.cancel()
        do { _ = try await task.value; XCTFail("Cancelled commit") } catch is CancellationError { }
        XCTAssertEqual(try store.mainContext.fetchCount(FetchDescriptor<Visit>()), 0)
    }
}
