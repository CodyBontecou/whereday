import Foundation
import SwiftData

/// `generate`: seed synthetic SwiftData models, write the actual iOS single-file and multipart backups, print them.
/// `verify <dir>`: restore every Android-written backup file in <dir> through the public TimelineImportService
/// preview/commit path and print the restored journal re-exported as an iOS backup.
/// In-memory SwiftData only; no app, simulator, device, network or personal history is used.
@main
private struct BackupFixtures {
    private static let time = Date(timeIntervalSince1970: 1_780_000_000.125)

    private static func container() throws -> ModelContainer {
        try ModelContainer(for: Visit.self, LocationPoint.self, RecordingSession.self,
                           SavedPlace.self, PhotoMoment.self, CountryStay.self,
                           configurations: ModelConfiguration(isStoredInMemoryOnly: true))
    }

    @MainActor
    private static func seed(_ context: ModelContext) throws {
        let visit = Visit(latitude: 37.7, longitude: -122.4, arrivedAt: time,
                          departedAt: time.addingTimeInterval(600), customName: "Café ☕️",
                          locationName: "Detected name", address: "Address", notes: "Visit notes",
                          geocodingCompleted: true)
        visit.id = UUID(uuidString: "8E0C7C1D-3E1B-4C33-9A41-6D3C1B2A0001")!
        visit.sourceRaw = "manual"
        visit.confirmationStatusRaw = "corrected"
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
        visit.placeSourceRaw = "userEntered"
        visit.placeDistanceMeters = 42.5
        visit.originalConfirmationStatusRaw = "confirmed"
        visit.originalConfirmedAt = time.addingTimeInterval(-10)
        visit.originalPlaceSourceRaw = "appleMaps"
        visit.originalPlaceDistanceMeters = 123
        visit.countryCode = "US"
        visit.countryTimeZoneIdentifier = "America/Los_Angeles"
        visit.countryResolutionAttemptedAt = time
        visit.countryDepartureIsInferred = true
        context.insert(visit)
        let open = Visit(latitude: -33.86, longitude: 151.21, arrivedAt: time.addingTimeInterval(3_600))
        open.id = UUID(uuidString: "8E0C7C1D-3E1B-4C33-9A41-6D3C1B2A0002")!
        context.insert(open)
        let point = LocationPoint(latitude: 37.7, longitude: -122.4, timestamp: time,
                                  altitude: 13, speed: 2.5, horizontalAccuracy: 8, isOutlier: true)
        point.id = UUID(uuidString: "8E0C7C1D-3E1B-4C33-9A41-6D3C1B2A0003")!
        point.countryCode = "US"
        point.countryTimeZoneIdentifier = "America/Los_Angeles"
        point.countryResolutionAttemptedAt = time
        context.insert(point)
        let bare = LocationPoint(latitude: 0, longitude: 0, timestamp: time.addingTimeInterval(1), horizontalAccuracy: -1)
        bare.id = UUID(uuidString: "8E0C7C1D-3E1B-4C33-9A41-6D3C1B2A0004")!
        context.insert(bare)
        let session = RecordingSession(startedAt: time, endedAt: time.addingTimeInterval(900),
                                       customName: "Outing", notes: "Session notes",
                                       receivedLocationCount: 12, distanceFilterMeters: 5,
                                       routeSamplingModeRawValue: "time", routeSampleIntervalSeconds: 30)
        session.id = UUID(uuidString: "8E0C7C1D-3E1B-4C33-9A41-6D3C1B2A0005")!
        context.insert(session)
        let home = SavedPlace(name: "Home", latitude: 37.7, longitude: -122.4,
                              address: "Home address", radiusMeters: 900, kind: .home,
                              notes: "Place notes", createdAt: time, updatedAt: time)
        home.id = UUID(uuidString: "8E0C7C1D-3E1B-4C33-9A41-6D3C1B2A0006")!
        context.insert(home)
        let tiny = SavedPlace(name: "Desk", latitude: 1, longitude: 2, radiusMeters: 5)
        tiny.id = UUID(uuidString: "8E0C7C1D-3E1B-4C33-9A41-6D3C1B2A0007")!
        tiny.createdAt = time
        tiny.updatedAt = time
        context.insert(tiny)
        let library = PhotoMoment(assetLocalIdentifier: "ABCD1234-0000-0000-0000-000000000000/L0/001", takenAt: time,
                                  latitude: 37.7, longitude: -122.4, coordinateSource: .photoGPS, lastSyncedAt: time)
        library.id = UUID(uuidString: "8E0C7C1D-3E1B-4C33-9A41-6D3C1B2A0008")!
        context.insert(library)
        let android = PhotoMoment(assetLocalIdentifier: "content://media/external/images/media/42", takenAt: time,
                                  latitude: 37.71, longitude: -122.41, coordinateSource: .inferredFromRoute, lastSyncedAt: time)
        android.id = UUID(uuidString: "8E0C7C1D-3E1B-4C33-9A41-6D3C1B2A0009")!
        context.insert(android)
        let stay = CountryStay(countryCode: "JP", firstDay: "2026-04-06", lastDay: "2026-04-21", note: "Trip note")
        stay.id = UUID(uuidString: "8E0C7C1D-3E1B-4C33-9A41-6D3C1B2A0010")!
        context.insert(stay)
        try context.save()
    }

    @MainActor
    static func main() async {
        let arguments = CommandLine.arguments
        do {
            var output: [String: Any] = [:]
            if arguments.count == 3 && arguments[1] == "verify" {
                let directory = URL(fileURLWithPath: arguments[2], isDirectory: true)
                let urls = try FileManager.default.contentsOfDirectory(at: directory, includingPropertiesForKeys: nil)
                    .filter { $0.pathExtension == "json" && $0.lastPathComponent.hasPrefix("Whereday Backup") }
                    .sorted { $0.lastPathComponent < $1.lastPathComponent }
                let target = try container()
                let worker = TimelineImportService(container: target)
                let preview = try await worker.preview(urls: urls)
                let result = try await worker.commit(preview, options: ImportOptions())
                let restored = try WheredayBackupService.export(context: ModelContext(target))
                output = ["files": urls.map(\.lastPathComponent),
                          "restoredCount": result.visitCount + result.pointCount + result.additionalRecordCount,
                          "restored": String(decoding: restored, as: UTF8.self)]
                // A second commit of the same review must skip every record by identifier.
                let repeated = try await worker.commit(preview, options: ImportOptions())
                output["repeatedRestoredCount"] = repeated.visitCount + repeated.pointCount + repeated.additionalRecordCount
            } else if arguments.count == 1 || arguments[1] == "generate" {
                let source = try container()
                try seed(source.mainContext)
                let single = try WheredayBackupService.export(context: source.mainContext)
                let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
                defer { try? FileManager.default.removeItem(at: directory) }
                let files = try WheredayBackupService.writeFiles(WheredayBackupService.decode(single), directory: directory, maximumRecords: 3)
                output = ["single": String(decoding: single, as: UTF8.self),
                          "multipart": try files.map { ["name": $0.lastPathComponent,
                                                         "text": String(decoding: try Data(contentsOf: $0), as: UTF8.self)] }]
            } else {
                throw CocoaError(.featureUnsupported)
            }
            let data = try JSONSerialization.data(withJSONObject: output, options: [.prettyPrinted, .sortedKeys])
            FileHandle.standardOutput.write(data)
        } catch {
            FileHandle.standardError.write(Data("FAIL: \(error)\n".utf8))
            exit(1)
        }
    }
}
