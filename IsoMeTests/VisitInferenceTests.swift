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
        let points: [ImportedLocationPoint] = (0...5).map { index in
            let seconds = Double(index) * 180.0
            let latitude: Double = index == 0 ? 18.0 : 18.0001
            let accuracy: Double = index == 0 ? 10.0 : 100.0
            return point(seconds, latitude: latitude, accuracy: accuracy)
        }
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
final class GoogleImportCorrectionTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_577_836_800)
    private func container() throws -> ModelContainer {
        try ModelContainer(for: Visit.self, LocationPoint.self, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
    }
    private func preview(options: ImportOptions) -> ImportPreview {
        ImportPreview(fileName: "synthetic.json", payload: ImportPayload(format: .googleRaw),
                      newVisitCount: 1, newPointCount: 2, duplicateVisitCount: 0, duplicatePointCount: 0, options: options)
    }

    func testDelayedPolicySuccessPreservesLatestSelectionAndReenablesConfirmation() async throws {
        var state = ImportReviewState()
        var options = ImportOptions(); options.inference.radiusMeters = 175
        let request = state.begin(options: options)
        let delayed = AsyncStream<ImportPreview>.makeStream()
        let completion = Task { @MainActor in
            for await result in delayed.stream { return state.finish(request, current: options, result: .success(result)) }
            return nil as ImportPreview?
        }
        let reviewed = preview(options: options)
        options.includePoints = false
        XCTAssertTrue(state.isReviewing)
        delayed.continuation.yield(reviewed); delayed.continuation.finish()
        let accepted = await completion.value
        XCTAssertFalse(state.isReviewing)
        let final = try XCTUnwrap(accepted)
        XCTAssertFalse(final.options.includePoints)
        XCTAssertTrue(final.options.includeVisits)
        XCTAssertTrue(state.canConfirm(final, current: options))
    }

    func testDelayedPolicyFailureAfterSelectionChangeClearsOwnerAndCanRetry() async throws {
        var state = ImportReviewState()
        var options = ImportOptions(); options.inference.radiusMeters = 175
        let request = state.begin(options: options)
        let delayed = AsyncStream<Result<ImportPreview, Error>>.makeStream()
        let completion = Task { @MainActor in
            for await result in delayed.stream { return state.finish(request, current: options, result: result) }
            return nil as ImportPreview?
        }
        options.includeVisits = false
        delayed.continuation.yield(.failure(VisitInferenceError.invalidOptions)); delayed.continuation.finish()
        _ = await completion.value
        XCTAssertFalse(state.isReviewing)
        XCTAssertNotNil(state.errorMessage)
        let retry = state.begin(options: options)
        let final = try XCTUnwrap(state.finish(retry, current: options, result: .success(preview(options: options))))
        XCTAssertFalse(final.options.includeVisits)
        XCTAssertTrue(state.canConfirm(final, current: options))
    }

    func testSupersededCompletionOrCancellationCannotClearNewReviewOwner() throws {
        var state = ImportReviewState()
        var options = ImportOptions()
        let old = state.begin(options: options)
        options.inference.radiusMeters = 200
        let current = state.begin(options: options)
        XCTAssertNil(state.finish(old, current: options, result: .success(preview(options: old.options))))
        state.cancel(old)
        XCTAssertTrue(state.isReviewing)
        let accepted = try XCTUnwrap(state.finish(current, current: options, result: .success(preview(options: options))))
        XCTAssertTrue(state.canConfirm(accepted, current: options))
        let cancelled = state.begin(options: options)
        state.cancel(cancelled)
        XCTAssertFalse(state.isReviewing)
        XCTAssertNil(state.finish(cancelled, current: options, result: .success(accepted)))
    }

    func testCurrentOwnerRejectsMismatchedPolicyResultAndClearsBusy() {
        var state = ImportReviewState()
        var options = ImportOptions(); options.inference.radiusMeters = 200
        let request = state.begin(options: options)
        XCTAssertNil(state.finish(request, current: options, result: .success(preview(options: .init()))))
        XCTAssertFalse(state.isReviewing)
        XCTAssertNotNil(state.errorMessage)
    }

    private func stationaryRows(offset: Int = 0) -> [[String: Any]] {
        (0...5).map { ["latitudeE7": 180_000_000, "longitudeE7": -660_000_000,
                       "timestampMs": 1_577_836_800_000 + offset * 1_000 + $0 * 180_000, "accuracy": 8] }
    }

    func testTimestampedRejectedRawSampleBreaksInferenceBeforeSaving() async throws {
        for badCoordinate in [false, true] {
            let store = try container()
            let actual = TimelineImportService(container: store)
            var rejected = stationaryRows()[0]
            rejected["timestampMs"] = 1_577_837_250_000 // 450 seconds, between two three-fix runs.
            if badCoordinate { rejected["latitudeE7"] = 990_000_000 }
            else { rejected["accuracy"] = "NaN" }
            let data = try JSONSerialization.data(withJSONObject: ["locations": stationaryRows() + [rejected]])
            let result = try await actual.preview(data: data, fileName: "barrier.json")
            XCTAssertEqual(result.payload.invalidRecordCount, 1)
            XCTAssertEqual(result.payload.rawInferenceBarrierDates, [start.addingTimeInterval(450)])
            XCTAssertEqual(result.newPointCount, 6)
            XCTAssertEqual(result.inferredVisitCount, 0)
            XCTAssertEqual(result.newVisitCount, 0)
            XCTAssertEqual(try store.mainContext.fetchCount(FetchDescriptor<Visit>()), 0)
            let committed = try await actual.commit(result, options: .init())
            XCTAssertEqual(committed.visitCount, 0)
            XCTAssertEqual(committed.pointCount, 6)
            XCTAssertEqual(try store.mainContext.fetchCount(FetchDescriptor<Visit>()), 0)
            XCTAssertTrue(try store.mainContext.fetch(FetchDescriptor<LocationPoint>()).allSatisfy { $0.latitude == 18 && $0.horizontalAccuracy == 8 })
        }
    }

    func testUnorderableRejectedRawEvidenceDisablesInferenceButKeepsValidRoutes() async throws {
        var badTime = stationaryRows()[0]; badTime["timestampMs"] = "NaN"
        var missingTime = stationaryRows()[0]; missingTime.removeValue(forKey: "timestampMs")
        for rejected: Any in [badTime, missingTime, NSNull()] {
            let store = try container()
            let service = TimelineImportService(container: store)
            let rows: [Any] = stationaryRows().map { $0 } + [rejected]
            let data = try JSONSerialization.data(withJSONObject: ["locations": rows])
            let result = try await service.preview(data: data, fileName: "unorderable.json")
            XCTAssertEqual(result.payload.unorderableRawObservationCount, 1)
            XCTAssertTrue(result.payload.rawInferenceBarrierDates.isEmpty)
            XCTAssertEqual(result.payload.invalidRecordCount, 1)
            XCTAssertEqual(result.inferredVisitCount, 0)
            XCTAssertEqual(result.newPointCount, 6)
            let committed = try await service.commit(result, options: .init())
            XCTAssertEqual(committed.visitCount, 0)
            XCTAssertEqual(committed.pointCount, 6)
            XCTAssertEqual(try store.mainContext.fetchCount(FetchDescriptor<Visit>()), 0)
        }
    }

    func testRejectedInstantDoesNotEraseQualifyingDwellsOnEitherSide() async throws {
        let store = try container()
        let service = TimelineImportService(container: store)
        var rejected = stationaryRows()[0]
        rejected["timestampMs"] = 1_577_837_790_000 // 990 seconds, inside the otherwise short 180-second gap.
        rejected["accuracy"] = "NaN"
        let data = try JSONSerialization.data(withJSONObject: ["locations": stationaryRows() + [rejected] + stationaryRows(offset: 1080)])
        let result = try await service.preview(data: data, fileName: "separate-dwells.json")
        XCTAssertEqual(result.inferredVisitCount, 2)
        let committed = try await service.commit(result, options: .init())
        XCTAssertEqual(committed.visitCount, 2)
        let visits = try store.mainContext.fetch(FetchDescriptor<Visit>(sortBy: [SortDescriptor(\.arrivedAt)]))
        XCTAssertEqual(visits[0].departedAt, start.addingTimeInterval(900))
        XCTAssertEqual(visits[1].arrivedAt, start.addingTimeInterval(1080))
    }

    func testRawBarrierMetadataSurvivesExplicitMultiFileAggregation() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let first = directory.appendingPathComponent("first.json"), second = directory.appendingPathComponent("second.json")
        let rows = stationaryRows()
        var rejected = rows[0]; rejected["timestampMs"] = 1_577_837_250_000; rejected["accuracy"] = "NaN"
        try JSONSerialization.data(withJSONObject: ["locations": Array(rows.prefix(3))]).write(to: first)
        try JSONSerialization.data(withJSONObject: ["locations": Array(rows.suffix(3)) + [rejected]]).write(to: second)
        for urls in [[first, second], [second, first]] {
            let service = TimelineImportService(container: try container())
            let result = try await service.preview(urls: urls)
            XCTAssertEqual(result.payload.rawInferenceBarrierDates, [start.addingTimeInterval(450)])
            XCTAssertEqual(result.inferredVisitCount, 0)
            XCTAssertEqual(result.newPointCount, 6)
        }
        rejected.removeValue(forKey: "timestampMs")
        try JSONSerialization.data(withJSONObject: ["locations": Array(rows.suffix(3)) + [rejected]]).write(to: second)
        let service = TimelineImportService(container: try container())
        let unorderable = try await service.preview(urls: [first, second])
        XCTAssertEqual(unorderable.payload.unorderableRawObservationCount, 1)
        XCTAssertEqual(unorderable.inferredVisitCount, 0)
    }

    func testTimestampedInvalidAndValidDuplicatesAtSameInstantCannotBridgeDwell() async throws {
        let store = try container()
        let service = TimelineImportService(container: store)
        var duplicate = stationaryRows()[0]; duplicate["timestampMs"] = 1_577_837_250_000
        var rejected = duplicate; rejected["accuracy"] = "NaN"
        let data = try JSONSerialization.data(withJSONObject: ["locations": stationaryRows() + [duplicate, rejected]])
        let result = try await service.preview(data: data, fileName: "conflicting-instant.json")
        XCTAssertEqual(result.inferredVisitCount, 0)
        let committed = try await service.commit(result, options: .init())
        XCTAssertEqual(committed.pointCount, 7, "The valid route measurement remains importable, not inference evidence")
        XCTAssertEqual(committed.visitCount, 0)
    }

    func testBarrierMetadataCountsTowardAggregateAdmissionBudget() async throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let invalid: [String: Any] = ["timestampMs": "1577837250000", "accuracy": "NaN"]
        let rows = [stationaryRows()[0]] + Array(repeating: invalid, count: ImportPayload.maximumRecordCount / 2)
        let data = try JSONSerialization.data(withJSONObject: ["locations": rows])
        let urls = [directory.appendingPathComponent("first.json"), directory.appendingPathComponent("second.json")]
        for url in urls { try data.write(to: url) }
        let service = TimelineImportService(container: try container())
        do { _ = try await service.preview(urls: urls); XCTFail("Rejected evidence must not bypass aggregate admission") }
        catch TimelineImportError.tooManyRecords { }
    }

    private func accuracyData(device: Bool, accuracy: Any) throws -> Data {
        if device {
            return try JSONSerialization.data(withJSONObject: ["semanticSegments": [[
                "startTime": "2020-01-01T00:00:00Z", "endTime": "2020-01-01T00:15:00Z",
                "activity": ["start": ["latLng": "geo:18,-66", "accuracy": accuracy],
                             "end": ["latLng": "geo:18,-66", "accuracy": accuracy]],
                "timelinePath": [["point": "geo:18,-66", "time": "2020-01-01T00:07:30Z", "accuracy": accuracy],
                                 ["point": "geo:18,-66", "time": "2020-01-01T00:10:00Z"]]
            ]]])
        }
        return try JSONSerialization.data(withJSONObject: ["timelineObjects": [["activitySegment": [
            "duration": ["startTimestamp": "2020-01-01T00:00:00Z", "endTimestamp": "2020-01-01T00:15:00Z"],
            "startLocation": ["latitudeE7": 180_000_000, "longitudeE7": -660_000_000, "accuracy": accuracy],
            "endLocation": ["latitudeE7": 180_000_000, "longitudeE7": -660_000_000, "accuracy": accuracy],
            "simplifiedRawPath": ["points": [["latE7": 180_000_000, "lngE7": -660_000_000, "timestampMs": "1577837250000", "accuracy": accuracy],
                                             ["latE7": 180_000_000, "lngE7": -660_000_000, "timestampMs": "1577837400000"]]]
        ]]]])
    }

    private func checkInvalidAccuracy(device: Bool) async throws {
        for accuracy in ["NaN", "Infinity"] {
            for includePoor in [false, true] {
                let store = try container()
                let service = TimelineImportService(container: store)
                var options = ImportOptions(); options.includePoorAccuracyPoints = includePoor
                let result = try await service.preview(data: accuracyData(device: device, accuracy: accuracy), fileName: "family.json", options: options)
                XCTAssertEqual(result.payload.invalidRecordCount, 3)
                XCTAssertEqual(result.newPointCount, 1)
                XCTAssertEqual(result.unknownAccuracyPointCount, 1, "Only genuinely absent accuracy is unknown")
                let committed = try await service.commit(result, options: options)
                XCTAssertEqual(committed.pointCount, 1)
                let saved = try store.mainContext.fetch(FetchDescriptor<LocationPoint>())
                XCTAssertEqual(saved.count, 1)
                XCTAssertTrue(saved.allSatisfy { $0.horizontalAccuracy == -1 && $0.timestamp == start.addingTimeInterval(600) })
            }
        }
    }

    func testSemanticNonfiniteAccuracyNeverBecomesUnknownInEitherRoutePolicy() async throws {
        try await checkInvalidAccuracy(device: false)
    }

    func testDeviceNonfiniteAccuracyNeverBecomesUnknownInEitherRoutePolicy() async throws {
        try await checkInvalidAccuracy(device: true)
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
