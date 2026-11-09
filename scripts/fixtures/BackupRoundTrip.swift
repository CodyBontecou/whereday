import Foundation
import SwiftData
import Darwin

private struct BackupCheckFailure: Error, CustomStringConvertible {
    let description: String
}

private func require(_ condition: @autoclosure () -> Bool, _ message: String) throws {
    if !condition() { throw BackupCheckFailure(description: message) }
}

@main
private struct BackupRoundTripAcceptance {
    private static let time = Date(timeIntervalSince1970: 1_780_000_000.125)
    private static let originalConfirmedAt = time.addingTimeInterval(-120)
    private static let correctionFields = [
        "originalConfirmationStatusRaw", "originalConfirmedAt",
        "originalPlaceSourceRaw", "originalPlaceDistanceMeters"
    ]
    private static let samplingFields = ["routeSamplingModeRawValue", "routeSampleIntervalSeconds"]

    @MainActor
    static func main() async {
        var cases = [[String: Any]]()
        func record(_ name: String, check: @MainActor () async throws -> Void) async {
            do {
                try await check()
                cases.append(["name": name, "passed": true])
            } catch {
                cases.append(["name": name, "passed": false, "failure": String(describing: error)])
            }
        }
        await record("correction-metadata-survives-public-import") {
            let target = try await restore(seedData())
            let visit = try onlyVisit(target)
            try require(visit.originalConfirmationStatusRaw == "confirmed", "Lost original confirmation status")
            try require(visit.originalConfirmedAt == originalConfirmedAt, "Lost original confirmation time")
            try require(visit.originalPlaceSourceRaw == "appleMaps", "Lost original place source")
            try require(visit.originalPlaceDistanceMeters == 42.5, "Lost original place distance")
        }
        await record("session-sampling-survives-public-import") {
            let target = try await restore(seedData())
            let sessions = try ModelContext(target).fetch(FetchDescriptor<RecordingSession>())
            try require(sessions.count == 1, "Expected one recording session")
            try require(sessions[0].routeSamplingModeRawValue == "time", "Lost sampling mode")
            try require(sessions[0].routeSampleIntervalSeconds == 15, "Lost sampling interval")
        }
        await record("undo-correction-restores-original-provenance") {
            let target = try await restore(seedData())
            let visit = try onlyVisit(target)
            visit.restoreOriginalValues(at: time.addingTimeInterval(900))
            try require(visit.latitude == 37.8 && visit.longitude == -122.5, "Undo lost original coordinates")
            try require(visit.confirmationStatus == .confirmed, "Undo inferred incorrect confirmation status")
            try require(visit.confirmedAt == originalConfirmedAt, "Undo lost original confirmation time")
            try require(visit.placeSource == .appleMaps, "Undo lost original place source")
            try require(visit.placeDistanceMeters == 42.5, "Undo lost original place distance")
        }
        await record("legacy-backup-without-new-optional-fields") {
            var root = try object(seedData())
            try mutate(&root, collection: "visits") { row in
                for field in correctionFields { row.removeValue(forKey: field) }
            }
            try mutate(&root, collection: "recordingSessions") { row in
                for field in samplingFields { row.removeValue(forKey: field) }
            }
            let target = try await restore(JSONSerialization.data(withJSONObject: root))
            let visit = try onlyVisit(target)
            let sessions = try ModelContext(target).fetch(FetchDescriptor<RecordingSession>())
            try require(visit.originalConfirmationStatusRaw == nil && visit.originalConfirmedAt == nil
                        && visit.originalPlaceSourceRaw == nil && visit.originalPlaceDistanceMeters == nil,
                        "Absent correction metadata must remain nil")
            try require(sessions.count == 1 && sessions[0].routeSamplingModeRawValue == nil
                        && sessions[0].routeSampleIntervalSeconds == nil,
                        "Absent sampling metadata must remain nil")
        }
        for (name, collection, field, value) in [
            ("reject-inverted-visit-atomically", "visits", "departedAt", time.addingTimeInterval(-1).timeIntervalSince1970 * 1000),
            ("reject-inverted-session-atomically", "recordingSessions", "endedAt", time.addingTimeInterval(-1).timeIntervalSince1970 * 1000),
            ("reject-accuracy-below-minus-one-atomically", "points", "horizontalAccuracy", -100.0),
            ("reject-visit-after-supported-date-range-atomically", "visits", "arrivedAt", Date.distantFuture.addingTimeInterval(1).timeIntervalSince1970 * 1000),
            ("reject-point-before-supported-date-range-atomically", "points", "timestamp", Date.distantPast.addingTimeInterval(-1).timeIntervalSince1970 * 1000)
        ] {
            await record(name) {
                var root = try object(seedData())
                try mutate(&root, collection: collection) { row in
                    row[field] = value
                    // Isolate the supported-date-range invariant from interval ordering.
                    if field == "arrivedAt" { row["departedAt"] = NSNull() }
                }
                let data = try JSONSerialization.data(withJSONObject: root)
                let target = try container()
                let existing = SavedPlace(name: "Keep existing", latitude: 18, longitude: -66)
                target.mainContext.insert(existing)
                try target.mainContext.save()
                let existingID = existing.id
                let worker = TimelineImportService(container: target)
                var rejected = false
                do {
                    let preview = try await worker.preview(data: data, fileName: "synthetic-backup.json")
                    _ = try await worker.commit(preview, options: ImportOptions())
                } catch {
                    rejected = true
                }
                let count = try countRecords(target)
                let places = try ModelContext(target).fetch(FetchDescriptor<SavedPlace>())
                try require(rejected && count == 1 && places.count == 1
                            && places[0].id == existingID && places[0].name == "Keep existing",
                            "Malformed backup accepted or changed existing storage: rejected=\(rejected), records=\(count)")
            }
        }
        let passedCount = cases.filter { $0["passed"] as? Bool == true }.count
        let report: [String: Any] = ["passed": passedCount == cases.count, "passedCount": passedCount, "cases": cases]
        do {
            FileHandle.standardOutput.write(try JSONSerialization.data(withJSONObject: report, options: [.sortedKeys]))
        } catch {
            FileHandle.standardError.write(Data("Cannot encode verification result: \(error)\n".utf8))
            exit(EXIT_FAILURE)
        }
        if passedCount != cases.count { exit(EXIT_FAILURE) }
    }

    @MainActor
    private static func container() throws -> ModelContainer {
        try ModelContainer(for: Visit.self, LocationPoint.self, RecordingSession.self,
                           SavedPlace.self, PhotoMoment.self, CountryStay.self,
                           configurations: ModelConfiguration(isStoredInMemoryOnly: true))
    }

    @MainActor
    private static func seedData() throws -> Data {
        let source = try container()
        let context = source.mainContext
        let visit = Visit(latitude: 37.7, longitude: -122.4, arrivedAt: time,
                          departedAt: time.addingTimeInterval(600), locationName: "Synthetic corrected place",
                          source: .automatic, confirmationStatus: .corrected,
                          confirmedAt: time.addingTimeInterval(10), originalLatitude: 37.8,
                          originalLongitude: -122.5, originalLocationName: "Synthetic original place",
                          placeSource: .userEntered, placeDistanceMeters: 99)
        visit.originalConfirmationStatusRaw = "confirmed"
        visit.originalConfirmedAt = originalConfirmedAt
        visit.originalPlaceSourceRaw = "appleMaps"
        visit.originalPlaceDistanceMeters = 42.5
        context.insert(visit)
        context.insert(LocationPoint(latitude: 37.7, longitude: -122.4, timestamp: time, horizontalAccuracy: 8))
        context.insert(RecordingSession(startedAt: time, endedAt: time.addingTimeInterval(900),
                                        customName: "Synthetic session", receivedLocationCount: 12,
                                        distanceFilterMeters: 5, routeSamplingModeRawValue: "time",
                                        routeSampleIntervalSeconds: 15))
        context.insert(SavedPlace(name: "Synthetic place", latitude: 37.7, longitude: -122.4))
        context.insert(PhotoMoment(assetLocalIdentifier: "synthetic-photo-id", takenAt: time,
                                   latitude: 37.7, longitude: -122.4))
        context.insert(CountryStay(countryCode: "JP", firstDay: "2026-04-06", lastDay: "2026-04-21"))
        try context.save()
        return try WheredayBackupService.export(context: context)
    }

    @MainActor
    private static func restore(_ data: Data) async throws -> ModelContainer {
        let target = try container()
        let worker = TimelineImportService(container: target)
        let preview = try await worker.preview(data: data, fileName: "synthetic-backup.json")
        let previewCount = try countRecords(target)
        try require(previewCount == 0, "Preview wrote records")
        let result = try await worker.commit(preview, options: ImportOptions())
        try require(result.visitCount == 1 && result.pointCount == 1 && result.additionalRecordCount == 4,
                    "Unexpected public commit counts")
        let restoredCount = try countRecords(target)
        try require(restoredCount == 6, "Round trip did not restore all six models")
        return target
    }

    @MainActor
    private static func onlyVisit(_ target: ModelContainer) throws -> Visit {
        let visits = try target.mainContext.fetch(FetchDescriptor<Visit>())
        try require(visits.count == 1, "Expected exactly one visit")
        return visits[0]
    }

    @MainActor
    private static func countRecords(_ target: ModelContainer) throws -> Int {
        let context = ModelContext(target)
        return try context.fetchCount(FetchDescriptor<Visit>())
            + context.fetchCount(FetchDescriptor<LocationPoint>())
            + context.fetchCount(FetchDescriptor<RecordingSession>())
            + context.fetchCount(FetchDescriptor<SavedPlace>())
            + context.fetchCount(FetchDescriptor<PhotoMoment>())
            + context.fetchCount(FetchDescriptor<CountryStay>())
    }

    private static func object(_ data: Data) throws -> [String: Any] {
        guard let root = try JSONSerialization.jsonObject(with: data) as? [String: Any] else {
            throw BackupCheckFailure(description: "Expected backup object")
        }
        return root
    }

    private static func mutate(_ root: inout [String: Any], collection: String,
                               change: (inout [String: Any]) -> Void) throws {
        guard var rows = root[collection] as? [[String: Any]], !rows.isEmpty else {
            throw BackupCheckFailure(description: "Missing seeded \(collection)")
        }
        change(&rows[0])
        root[collection] = rows
    }
}
