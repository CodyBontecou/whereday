import XCTest
import CoreLocation
import SwiftData
@testable import IsoMe

@MainActor
final class SavedPlaceManagementTests: XCTestCase {
    func testManagedCreationSavesReusableLocationWithoutCreatingAVisit() throws {
        let (container, viewModel) = try makeEnvironment()
        let draft = SavedPlaceDraft(
            name: "  Unlisted picnic spot  ",
            address: "  Beside the footpath  ",
            latitude: 38.7223,
            longitude: -9.1393,
            radiusMeters: 500
        )

        let created = try viewModel.saveManagedPlace(draft)
        let stored = try XCTUnwrap(persistedPlaces(in: container).first)

        XCTAssertEqual(created.id, draft.id)
        XCTAssertEqual(stored.id, draft.id)
        XCTAssertEqual(stored.name, "Unlisted picnic spot")
        XCTAssertEqual(stored.address, "Beside the footpath")
        XCTAssertEqual(stored.latitude, 38.7223)
        XCTAssertEqual(stored.longitude, -9.1393)
        XCTAssertEqual(stored.radiusMeters, 500)
        XCTAssertEqual(viewModel.savedPlaces.map(\.id), [created.id])
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<Visit>()), 0)
        XCTAssertTrue(viewModel.allVisits.isEmpty)
    }

    func testRenamingAndMovingEditsByUUIDEvenWhenAnotherLocationMatches() throws {
        let (container, viewModel) = try makeEnvironment()
        let home = try viewModel.saveManagedPlace(SavedPlaceDraft(name: "Home", latitude: 38, longitude: -9))
        let office = try viewModel.saveManagedPlace(SavedPlaceDraft(name: "Office", latitude: 39, longitude: -8))
        let originalCreatedAt = home.createdAt
        var draft = SavedPlaceDraft(home)
        draft.name = "Office"
        draft.address = "New entrance"
        draft.latitude = office.latitude
        draft.longitude = office.longitude
        draft.radiusMeters = 300

        let edited = try viewModel.saveManagedPlace(draft)
        let stored = try persistedPlaces(in: container)
        let storedHome = try XCTUnwrap(stored.first { $0.id == home.id })

        XCTAssertEqual(edited.id, home.id)
        XCTAssertEqual(Set(stored.map(\.id)), [home.id, office.id])
        XCTAssertEqual(storedHome.name, "Office")
        XCTAssertEqual(storedHome.address, "New entrance")
        XCTAssertEqual(storedHome.latitude, 39)
        XCTAssertEqual(storedHome.longitude, -8)
        XCTAssertEqual(storedHome.radiusMeters, 300)
        XCTAssertEqual(storedHome.createdAt, originalCreatedAt)
        XCTAssertNil(office.address)
        XCTAssertEqual(office.radiusMeters, 150)
        XCTAssertEqual(viewModel.savedPlaces.count, 2)
    }

    func testConfirmingMatchingVisitPreservesManagedPinAddressAndRadius() throws {
        let (container, viewModel) = try makeEnvironment()
        let place = try viewModel.saveManagedPlace(SavedPlaceDraft(
            name: "Café",
            address: "User-selected entrance",
            latitude: 38,
            longitude: -9,
            radiusMeters: 600
        ))
        let originalUpdatedAt = place.updatedAt
        let visit = Visit(
            latitude: 38.001,
            longitude: -9,
            arrivedAt: Date(timeIntervalSince1970: 1_700_000_000),
            departedAt: Date(timeIntervalSince1970: 1_700_003_600),
            locationName: "café",
            address: "Detected street address",
            geocodingCompleted: true
        )
        container.mainContext.insert(visit)
        try container.mainContext.save()

        viewModel.confirmVisit(visit)
        let stored = try persistedPlaces(in: container)
        let saved = try XCTUnwrap(stored.first)

        XCTAssertTrue(visit.isConfirmed)
        XCTAssertEqual(stored.count, 1)
        XCTAssertEqual(saved.id, place.id)
        XCTAssertEqual(saved.name, "Café")
        XCTAssertEqual(saved.address, "User-selected entrance")
        XCTAssertEqual(saved.latitude, 38)
        XCTAssertEqual(saved.longitude, -9)
        XCTAssertEqual(saved.radiusMeters, 600)
        XCTAssertEqual(saved.updatedAt, originalUpdatedAt)
    }

    func testEditingAndDeletingSavedLocationLeaveHistoricalVisitUntouched() throws {
        let (container, viewModel) = try makeEnvironment()
        let arrivedAt = Date(timeIntervalSince1970: 1_700_000_000)
        let departedAt = arrivedAt.addingTimeInterval(3_600)
        let visit = Visit(
            latitude: 38,
            longitude: -9,
            arrivedAt: arrivedAt,
            departedAt: departedAt,
            customName: "Lunch with Rue",
            locationName: "Original Café",
            address: "Original address",
            notes: "Keep this history",
            geocodingCompleted: true,
            source: .manual,
            confirmationStatus: .confirmed,
            confirmedAt: departedAt,
            updatedAt: departedAt,
            placeSource: .userEntered
        )
        container.mainContext.insert(visit)
        try container.mainContext.save()
        let place = try viewModel.saveManagedPlace(SavedPlaceDraft(name: "Original Café", latitude: 38, longitude: -9))
        var draft = SavedPlaceDraft(place)
        draft.name = "Renamed Café"
        draft.address = "New address"
        draft.latitude = 40
        draft.longitude = -7
        draft.radiusMeters = 50

        try viewModel.saveManagedPlace(draft)
        try viewModel.deleteManagedPlace(place)

        let context = ModelContext(container)
        let stored = try XCTUnwrap(context.fetch(FetchDescriptor<Visit>()).first)
        XCTAssertEqual(stored.id, visit.id)
        XCTAssertEqual(stored.locationName, "Original Café")
        XCTAssertEqual(stored.customName, "Lunch with Rue")
        XCTAssertEqual(stored.address, "Original address")
        XCTAssertEqual(stored.notes, "Keep this history")
        XCTAssertEqual(stored.latitude, 38)
        XCTAssertEqual(stored.longitude, -9)
        XCTAssertEqual(stored.arrivedAt, arrivedAt)
        XCTAssertEqual(stored.departedAt, departedAt)
        XCTAssertEqual(stored.confirmedAt, departedAt)
        XCTAssertEqual(stored.updatedAt, departedAt)
        XCTAssertEqual(stored.source, .manual)
        XCTAssertEqual(stored.confirmationStatus, .confirmed)
        XCTAssertEqual(stored.placeSource, .userEntered)
        XCTAssertTrue(try persistedPlaces(in: container).isEmpty)
        XCTAssertTrue(viewModel.savedPlaces.isEmpty)
    }

    func testInvalidEditsCreatesAndImportsDoNotMutateSavedPlaces() throws {
        let (container, viewModel) = try makeEnvironment()
        let place = try viewModel.saveManagedPlace(SavedPlaceDraft(name: "Home", address: "Original", latitude: 38, longitude: -9))
        let original = SavedPlaceDraft(place)
        let originalUpdatedAt = place.updatedAt
        let invalidChanges: [(inout SavedPlaceDraft) -> Void] = [
            { $0.name = " \n " },
            { $0.latitude = nil },
            { $0.latitude = .nan },
            { $0.latitude = 91 },
            { $0.longitude = .infinity },
            { $0.longitude = -181 },
            { $0.radiusMeters = .nan },
            { $0.radiusMeters = 24 },
            { $0.radiusMeters = 10_001 }
        ]
        for change in invalidChanges {
            var draft = original
            change(&draft)
            XCTAssertThrowsError(try viewModel.saveManagedPlace(draft))
            draft.existingPlaceID = nil
            XCTAssertThrowsError(try viewModel.saveManagedPlace(draft))
        }
        var missingDraft = original
        missingDraft.existingPlaceID = UUID()
        XCTAssertThrowsError(try viewModel.saveManagedPlace(missingDraft))

        let imported = [
            importedPlace(name: "Home", address: "Must not replace original", latitude: 38, longitude: -9),
            importedPlace(name: "New but invalid", latitude: 40, longitude: -7, radius: .infinity)
        ]
        XCTAssertThrowsError(try viewModel.importSavedPlaces(imported, duplicates: .updateExisting))
        let stored = try persistedPlaces(in: container)
        let unchanged = try XCTUnwrap(stored.first)
        XCTAssertEqual(stored.count, 1)
        XCTAssertEqual(SavedPlaceDraft(unchanged), original)
        XCTAssertEqual(unchanged.updatedAt, originalUpdatedAt)
        XCTAssertEqual(viewModel.savedPlaces.count, 1)
    }

    func testKeepExistingImportSkipsStoredAndWithinFileDuplicates() throws {
        let (container, viewModel) = try makeEnvironment()
        let home = try viewModel.saveManagedPlace(SavedPlaceDraft(name: "Home", address: "Original", latitude: 38, longitude: -9))
        let originalHome = SavedPlaceDraft(home)
        let imported = [
            importedPlace(name: " home ", address: "Ignored", latitude: 38.0001, longitude: -9),
            importedPlace(name: "Park", address: "First imported address", latitude: 38.5, longitude: -9),
            importedPlace(name: "park", address: "Ignored duplicate", latitude: 38.5001, longitude: -9)
        ]
        let plan = SavedPlaceImportPlan(places: imported, existing: viewModel.savedPlaces.map(SavedPlaceDraft.init), policy: .keepExisting)

        let summary = try viewModel.importSavedPlaces(imported, duplicates: .keepExisting)
        let stored = try persistedPlaces(in: container)
        let savedHome = try XCTUnwrap(stored.first { $0.id == home.id })
        let savedPark = try XCTUnwrap(stored.first { $0.id == imported[1].id })

        assertSummary(summary, matches: plan.summary)
        XCTAssertEqual(summary.added, 1)
        XCTAssertEqual(summary.updated, 0)
        XCTAssertEqual(summary.skipped, 2)
        XCTAssertEqual(stored.count, 2)
        XCTAssertEqual(SavedPlaceDraft(savedHome), originalHome)
        XCTAssertEqual(savedPark.name, "Park")
        XCTAssertEqual(savedPark.address, "First imported address")
        XCTAssertEqual(savedPark.latitude, 38.5)
        XCTAssertEqual(viewModel.savedPlaces.count, 2)
    }

    func testUpdateExistingImportPreservesIDsAndMergesWithinFileDuplicates() throws {
        let (container, viewModel) = try makeEnvironment()
        let home = try viewModel.saveManagedPlace(SavedPlaceDraft(name: "Home", address: "Original", latitude: 38, longitude: -9))
        let imported = [
            importedPlace(name: "home", address: "First update", latitude: 38.0001, longitude: -9, radius: 200),
            importedPlace(name: "HOME", address: "Last update", latitude: 38.0002, longitude: -9, radius: 250),
            importedPlace(name: "Café", address: "Initial address", latitude: 39, longitude: -8),
            importedPlace(name: "café", address: "Final address", latitude: 39.0001, longitude: -8, radius: 300)
        ]
        let plan = SavedPlaceImportPlan(places: imported, existing: viewModel.savedPlaces.map(SavedPlaceDraft.init), policy: .updateExisting)

        let summary = try viewModel.importSavedPlaces(imported, duplicates: .updateExisting)
        let stored = try persistedPlaces(in: container)
        let savedHome = try XCTUnwrap(stored.first { $0.id == home.id })
        let savedCafe = try XCTUnwrap(stored.first { $0.id == imported[2].id })

        assertSummary(summary, matches: plan.summary)
        XCTAssertEqual(summary.added, 1)
        XCTAssertEqual(summary.updated, 3)
        XCTAssertEqual(summary.skipped, 0)
        XCTAssertEqual(stored.count, 2)
        XCTAssertEqual(savedHome.name, "HOME")
        XCTAssertEqual(savedHome.address, "Last update")
        XCTAssertEqual(savedHome.latitude, 38.0002)
        XCTAssertEqual(savedHome.radiusMeters, 250)
        XCTAssertEqual(savedCafe.name, "café")
        XCTAssertEqual(savedCafe.address, "Final address")
        XCTAssertEqual(savedCafe.latitude, 39.0001)
        XCTAssertEqual(savedCafe.radiusMeters, 300)
    }

    func testImportPlanChoosesNearestDeterministicallyAndTracksSequentialPinMoves() throws {
        let (container, viewModel) = try makeEnvironment()
        let west = try viewModel.saveManagedPlace(SavedPlaceDraft(name: "Coffee", latitude: 0, longitude: 0, radiusMeters: 250))
        let east = try viewModel.saveManagedPlace(SavedPlaceDraft(name: "Coffee", latitude: 0, longitude: 0.002, radiusMeters: 250))
        let imported = [
            importedPlace(name: "Coffee", latitude: 0, longitude: 0.0019, radius: 250),
            importedPlace(name: "Coffee", latitude: 0, longitude: 0.004, radius: 250),
            importedPlace(name: "Coffee", latitude: 0, longitude: 0.006, radius: 250)
        ]
        let existing = viewModel.savedPlaces.map(SavedPlaceDraft.init)
        let plan = SavedPlaceImportPlan(places: imported, existing: existing, policy: .updateExisting)
        let reversedPlan = SavedPlaceImportPlan(places: imported, existing: Array(existing.reversed()), policy: .updateExisting)

        XCTAssertEqual(plan.entries.map(\.targetID), [east.id, east.id, east.id])
        XCTAssertEqual(reversedPlan.entries.map(\.targetID), plan.entries.map(\.targetID))
        XCTAssertTrue(plan.entries.allSatisfy { if case .update = $0.action { return true }; return false })
        let summary = try viewModel.importSavedPlaces(imported, duplicates: .updateExisting)
        let stored = try persistedPlaces(in: container)
        assertSummary(summary, matches: plan.summary)
        XCTAssertEqual(summary.added, 0)
        XCTAssertEqual(summary.updated, 3)
        XCTAssertEqual(stored.count, 2)
        XCTAssertEqual(try XCTUnwrap(stored.first { $0.id == east.id }).longitude, 0.006)
        XCTAssertEqual(try XCTUnwrap(stored.first { $0.id == west.id }).longitude, 0)

        let smallerID = try XCTUnwrap(UUID(uuidString: "00000000-0000-0000-0000-000000000001"))
        let largerID = try XCTUnwrap(UUID(uuidString: "00000000-0000-0000-0000-000000000002"))
        let tieCandidates = [
            SavedPlaceDraft(id: largerID, name: "Tie", latitude: 0, longitude: 0.001, radiusMeters: 250),
            SavedPlaceDraft(id: smallerID, name: "Tie", latitude: 0, longitude: -0.001, radiusMeters: 250)
        ]
        let midpoint = importedPlace(name: "Tie", latitude: 0, longitude: 0)
        let tiedPlan = SavedPlaceImportPlan(places: [midpoint], existing: tieCandidates, policy: .updateExisting)
        let reversedTiePlan = SavedPlaceImportPlan(places: [midpoint], existing: Array(tieCandidates.reversed()), policy: .updateExisting)
        XCTAssertEqual(tiedPlan.entries.first?.targetID, smallerID)
        XCTAssertEqual(reversedTiePlan.entries.first?.targetID, smallerID)
    }

    func testImportPlanMatchesSameNameAcrossInternationalDateLine() throws {
        let existing = SavedPlaceDraft(name: "Harbor", latitude: 0, longitude: 179.999, radiusMeters: 600)
        let incoming = importedPlace(name: " harbor ", latitude: 0, longitude: -179.997)
        XCTAssertLessThan(SavedPlaceDraft(incoming).distance(to: existing), 600)

        let keepPlan = SavedPlaceImportPlan(places: [incoming], existing: [existing], policy: .keepExisting)
        let updatePlan = SavedPlaceImportPlan(places: [incoming], existing: [existing], policy: .updateExisting)

        XCTAssertEqual(keepPlan.entries.first?.targetID, existing.id)
        XCTAssertEqual(keepPlan.summary.added, 0)
        XCTAssertEqual(keepPlan.summary.skipped, 1)
        XCTAssertEqual(updatePlan.entries.first?.targetID, existing.id)
        XCTAssertEqual(updatePlan.summary.added, 0)
        XCTAssertEqual(updatePlan.summary.updated, 1)
    }

    func testImportPlanMatchesNearBothPolesAndStillChecksActualDistance() throws {
        for latitude in [89.999, -89.999] {
            let existing = SavedPlaceDraft(name: "Polar station", latitude: latitude, longitude: -170, radiusMeters: 150)
            let incoming = importedPlace(name: "Polar station", latitude: latitude, longitude: 170)
            let distance = SavedPlaceDraft(incoming).distance(to: existing)
            XCTAssertLessThan(distance, 150)
            XCTAssertGreaterThan(distance, 25)

            let matchingPlan = SavedPlaceImportPlan(places: [incoming], existing: [existing], policy: .updateExisting)
            XCTAssertEqual(matchingPlan.entries.first?.targetID, existing.id)
            XCTAssertEqual(matchingPlan.summary.added, 0)
            XCTAssertEqual(matchingPlan.summary.updated, 1)

            let outsideRadius = importedPlace(name: "Polar station", latitude: latitude, longitude: 10)
            XCTAssertGreaterThan(SavedPlaceDraft(outsideRadius).distance(to: existing), 150)
            let separatePlan = SavedPlaceImportPlan(places: [outsideRadius], existing: [existing], policy: .updateExisting)
            XCTAssertEqual(separatePlan.entries.first?.targetID, outsideRadius.id)
            XCTAssertEqual(separatePlan.summary.added, 1)
            XCTAssertEqual(separatePlan.summary.updated, 0)
        }
    }

    func testSequentialImportMovesBetweenLatitudeBucketsAndRemovesOldPosition() throws {
        let (container, viewModel) = try makeEnvironment()
        let original = try viewModel.saveManagedPlace(SavedPlaceDraft(name: "Trail", latitude: 0.099, longitude: 0, radiusMeters: 200))
        let imported = [
            importedPlace(name: "Trail", latitude: 0.1005, longitude: 0, radius: 200),
            importedPlace(name: "Trail", latitude: 0.102, longitude: 0, radius: 200),
            importedPlace(name: "Trail", latitude: 0.0989, longitude: 0, radius: 200)
        ]
        let originalDraft = SavedPlaceDraft(original)
        XCTAssertLessThan(SavedPlaceDraft(imported[0]).distance(to: originalDraft), 200)
        XCTAssertGreaterThan(SavedPlaceDraft(imported[1]).distance(to: originalDraft), 200)
        let plan = SavedPlaceImportPlan(places: imported, existing: [originalDraft], policy: .updateExisting)

        XCTAssertEqual(plan.entries.map(\.targetID), [original.id, original.id, imported[2].id])
        XCTAssertEqual(plan.summary.updated, 2)
        XCTAssertEqual(plan.summary.added, 1)
        let summary = try viewModel.importSavedPlaces(imported, duplicates: .updateExisting)
        let stored = try persistedPlaces(in: container)
        assertSummary(summary, matches: plan.summary)
        XCTAssertEqual(stored.count, 2)
        XCTAssertEqual(try XCTUnwrap(stored.first { $0.id == original.id }).latitude, 0.102)
        XCTAssertEqual(try XCTUnwrap(stored.first { $0.id == imported[2].id }).latitude, 0.0989)
    }

    private func makeEnvironment() throws -> (ModelContainer, LocationViewModel) {
        let schema = Schema([Visit.self, LocationPoint.self, RecordingSession.self, PhotoMoment.self, SavedPlace.self, CountryStay.self])
        let configuration = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true, allowsSave: true)
        let container = try ModelContainer(for: schema, configurations: [configuration])
        let manager = makeIsolatedLocationManager()
        manager.isTrackingEnabled = false
        manager.trackingStartTime = nil
        return (container, LocationViewModel(modelContext: container.mainContext, locationManager: manager))
    }

    private func persistedPlaces(in container: ModelContainer) throws -> [SavedPlace] {
        let context = ModelContext(container)
        return try context.fetch(FetchDescriptor<SavedPlace>())
    }

    private func importedPlace(name: String, address: String? = nil, latitude: Double, longitude: Double, radius: Double = 150) -> ImportedSavedPlace {
        ImportedSavedPlace(name: name, address: address, latitude: latitude, longitude: longitude, radiusMeters: radius)
    }

    private func assertSummary(_ actual: SavedPlaceImportSummary, matches expected: SavedPlaceImportSummary, file: StaticString = #filePath, line: UInt = #line) {
        XCTAssertEqual(actual.added, expected.added, file: file, line: line)
        XCTAssertEqual(actual.updated, expected.updated, file: file, line: line)
        XCTAssertEqual(actual.skipped, expected.skipped, file: file, line: line)
    }
}
