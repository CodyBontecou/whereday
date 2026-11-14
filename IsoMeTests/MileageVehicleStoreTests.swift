import XCTest
import SwiftData
@testable import IsoMe

/// Real SwiftData contexts exercise committed state, owned rollback and historical snapshots.
final class MileageVehicleStoreTests: XCTestCase {
    private typealias Store = MileageVehicleStore
    private let date = Date(timeIntervalSince1970: 1_700_000_000)
    private let a = UUID(uuidString: "00000000-0000-4000-8000-000000000001")!
    private let b = UUID(uuidString: "00000000-0000-4000-8000-000000000002")!
    private let c = UUID(uuidString: "00000000-0000-4000-8000-000000000003")!

    @MainActor
    private func container() throws -> ModelContainer {
        let schema = Schema([Vehicle.self, Trip.self])
        let configuration = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)
        return try ModelContainer(for: schema, configurations: [configuration])
    }

    @MainActor
    private func store(_ container: ModelContainer) -> Store {
        var ids = [a, b, c].makeIterator()
        return Store(container: container, clock: { self.date }, makeID: { ids.next() ?? self.c })
    }

    @MainActor
    private func seed(_ vehicles: [Vehicle], in container: ModelContainer) throws {
        let context = ModelContext(container)
        context.autosaveEnabled = false
        for vehicle in vehicles { context.insert(vehicle) }
        try context.save()
    }

    @MainActor
    private func expect(_ expected: Store.StoreError, file: StaticString = #filePath, line: UInt = #line,
                        _ operation: () throws -> Void) {
        do {
            try operation()
            XCTFail("Expected exact store error", file: file, line: line)
        } catch let error as Store.StoreError {
            XCTAssertEqual(error, expected, file: file, line: line)
        } catch {
            XCTFail("Unexpected error type", file: file, line: line)
        }
    }

    @MainActor
    func testFirstActiveCreateIsDefaultAndLaterCreatePreservesIt() throws {
        let container = try container()
        let store = store(container)
        let first = try store.create(.init(name: "Synthetic A"))
        let second = try store.create(.init(name: "Synthetic B"))
        XCTAssertEqual(first.id, a)
        XCTAssertTrue(first.isDefault)
        XCTAssertFalse(second.isDefault)
        XCTAssertEqual(try store.snapshots().filter(\.isDefault).map(\.id), [a])
    }

    @MainActor
    func testExplicitCreateAndSetDefaultPreferences() throws {
        let container = try container()
        let store = store(container)
        try store.create(.init(name: "Synthetic A"))
        let second = try store.create(.init(name: "Synthetic B"), makeDefault: true)
        XCTAssertTrue(second.isDefault)
        XCTAssertEqual(try store.snapshots().filter(\.isDefault).map(\.id), [b])
        XCTAssertTrue(try store.setDefault(id: a).isDefault)
        XCTAssertEqual(try store.snapshots().filter(\.isDefault).map(\.id), [a])
    }

    @MainActor
    func testAllDescriptiveFieldsCreateEditClearAndExactPreservation() throws {
        let container = try container()
        var now = date
        let store = Store(container: container, clock: { now }, makeID: { self.a })
        let draft = Store.Draft(name: "  Synthetic A  ", make: "", model: "Synthetic Model",
                                year: -1, licensePlateLast4: "a b", notes: "Synthetic notes\n")
        let created = try store.create(draft)
        XCTAssertEqual(created.name, draft.name)
        XCTAssertEqual(created.make, "")
        XCTAssertEqual(created.model, draft.model)
        XCTAssertEqual(created.year, -1) // No arbitrary year/plate normalization.
        XCTAssertEqual(created.licensePlateLast4, "a b")
        XCTAssertEqual(created.notes, draft.notes)
        XCTAssertEqual(try store.snapshots().first, created)
        now = date.addingTimeInterval(10)
        let edited = try store.update(id: a, draft: .init(name: "Synthetic renamed", make: "Synthetic make",
            model: "Other model", year: 2030, licensePlateLast4: "Z9", notes: "Changed"))
        XCTAssertEqual(edited.id, a)
        XCTAssertEqual(edited.createdAt, date)
        XCTAssertEqual(edited.updatedAt, now)
        XCTAssertTrue(edited.isDefault)
        XCTAssertFalse(edited.isArchived)
        XCTAssertEqual(edited.name, "Synthetic renamed")
        XCTAssertEqual(edited.make, "Synthetic make")
        XCTAssertEqual(edited.model, "Other model")
        XCTAssertEqual(edited.year, 2030)
        XCTAssertEqual(edited.licensePlateLast4, "Z9")
        XCTAssertEqual(edited.notes, "Changed")
        XCTAssertEqual(try store.snapshots().first, edited)
        let cleared = try store.update(id: a, draft: .init(name: "Synthetic clear"))
        XCTAssertNil(cleared.make)
        XCTAssertNil(cleared.model)
        XCTAssertNil(cleared.year)
        XCTAssertNil(cleared.licensePlateLast4)
        XCTAssertEqual(cleared.notes, "")
        XCTAssertEqual(try store.snapshots().first, cleared)
    }

    @MainActor
    func testBlankNameCreateAndEditRejectWithoutMutation() throws {
        let container = try container()
        let store = store(container)
        expect(.blankName) { _ = try store.create(.init(name: " \n\t")) }
        XCTAssertEqual(try store.snapshots(), [])
        try store.create(.init(name: "Synthetic A"))
        let before = try store.snapshots()
        expect(.blankName) { _ = try store.update(id: a, draft: .init(name: "")) }
        XCTAssertEqual(try store.snapshots(), before)
    }

    @MainActor
    func testArchiveDefaultNondefaultLastAndRepeatKeepsRows() throws {
        let container = try container()
        var now = date
        var ids = [a, b, c].makeIterator()
        let store = Store(container: container, clock: { now }, makeID: { ids.next() ?? self.c })
        try store.create(.init(name: "Synthetic A"))
        try store.create(.init(name: "Synthetic B"))
        try store.create(.init(name: "Synthetic C"))
        now = date.addingTimeInterval(5)
        let nondefault = try store.archive(id: c)
        XCTAssertTrue(nondefault.isArchived)
        XCTAssertEqual(try store.snapshots().filter(\.isDefault).map(\.id), [a])
        let archived = try store.archive(id: a)
        XCTAssertFalse(archived.isDefault)
        XCTAssertEqual(archived.name, "Synthetic A")
        XCTAssertEqual(try store.snapshots().filter(\.isDefault).map(\.id), [b])
        let before = try store.snapshots()
        now = date.addingTimeInterval(10)
        XCTAssertEqual(try store.archive(id: a), archived)
        XCTAssertEqual(try store.snapshots(), before)
        try store.archive(id: b)
        let all = try store.snapshots()
        XCTAssertEqual(all.count, 3)
        XCTAssertTrue(all.allSatisfy(\.isArchived))
        XCTAssertTrue(all.filter(\.isDefault).isEmpty)
    }

    @MainActor
    func testCreateAfterAllArchivedGetsActiveDefault() throws {
        let container = try container()
        let store = store(container)
        try store.create(.init(name: "Synthetic A"))
        try store.archive(id: a)
        XCTAssertTrue(try store.create(.init(name: "Synthetic B")).isDefault)
        XCTAssertEqual(try store.snapshots().filter(\.isDefault).map(\.id), [b])
    }

    @MainActor
    func testMissingAndArchivedDefaultRejectionsAreTypedAndAtomic() throws {
        let container = try container()
        let store = store(container)
        try store.create(.init(name: "Synthetic A"))
        try store.archive(id: a)
        let before = try store.snapshots()
        expect(.archivedVehicle(a)) { _ = try store.setDefault(id: a) }
        expect(.missingVehicle(b)) { _ = try store.setDefault(id: b) }
        expect(.missingVehicle(b)) { _ = try store.archive(id: b) }
        expect(.missingVehicle(b)) { _ = try store.update(id: b, draft: .init(name: "Synthetic B")) }
        XCTAssertEqual(try store.snapshots(), before)
    }

    @MainActor
    func testEditArchivedDoesNotUnarchiveAndNondefaultEditDoesNotStealDefault() throws {
        let container = try container()
        let store = store(container)
        try store.create(.init(name: "Synthetic A"))
        try store.create(.init(name: "Synthetic B"))
        XCTAssertFalse(try store.update(id: b, draft: .init(name: "Synthetic B2")).isDefault)
        try store.archive(id: b)
        let edited = try store.update(id: b, draft: .init(name: "Synthetic archived edit"))
        XCTAssertTrue(edited.isArchived)
        XCTAssertEqual(edited.createdAt, date)
        XCTAssertFalse(edited.isDefault)
        XCTAssertEqual(try store.snapshots().filter(\.isDefault).map(\.id), [a])
    }

    @MainActor
    func testPolicyRepairUsesCreationTimeThenUUIDForTies() throws {
        let container = try container()
        let later = Vehicle(id: c, name: "Synthetic later", now: date.addingTimeInterval(1))
        let high = Vehicle(id: b, name: "Synthetic high", now: date)
        let low = Vehicle(id: a, name: "Synthetic low", now: date)
        for row in [later, high, low] { row.isDefault = true }
        try seed([later, high, low], in: container)
        let store = store(container)
        _ = try store.update(id: c, draft: .init(name: "Synthetic later edit"))
        XCTAssertEqual(try store.snapshots().map(\.id), [a, b, c])
        XCTAssertEqual(try store.snapshots().filter(\.isDefault).map(\.id), [a])
    }

    @MainActor
    func testNoDefaultRepairUsesExistingPolicyWithoutPreferringEditedRow() throws {
        let container = try container()
        try seed([Vehicle(id: b, name: "Synthetic B", now: date),
                  Vehicle(id: a, name: "Synthetic A", now: date)], in: container)
        let store = store(container)
        try store.update(id: b, draft: .init(name: "Synthetic B edit"))
        XCTAssertEqual(try store.snapshots().filter(\.isDefault).map(\.id), [a])
    }

    @MainActor
    func testDuplicateStoredIDsRejectAllMutationsBeforeChanges() throws {
        let container = try container()
        try seed([Vehicle(id: a, name: "Synthetic A1", now: date),
                  Vehicle(id: a, name: "Synthetic A2", now: date)], in: container)
        let store = store(container)
        expect(.duplicateIdentity(a)) { _ = try store.create(.init(name: "Synthetic new")) }
        expect(.duplicateIdentity(a)) { _ = try store.update(id: a, draft: .init(name: "Changed")) }
        expect(.duplicateIdentity(a)) { _ = try store.setDefault(id: a) }
        expect(.duplicateIdentity(a)) { _ = try store.archive(id: a) }
        let fresh = ModelContext(container)
        let rows = try fresh.fetch(FetchDescriptor<Vehicle>())
        XCTAssertEqual(rows.map(\.name).sorted(), ["Synthetic A1", "Synthetic A2"])
        XCTAssertTrue(rows.allSatisfy { !$0.isDefault && !$0.isArchived && $0.updatedAt == date })
    }

    @MainActor
    func testGeneratedDuplicateIDRejectsInsteadOfChoosingExisting() throws {
        let container = try container()
        let store = Store(container: container, clock: { self.date }, makeID: { self.a })
        try store.create(.init(name: "Synthetic A"))
        let before = try store.snapshots()
        expect(.duplicateIdentity(a)) { _ = try store.create(.init(name: "Synthetic duplicate")) }
        XCTAssertEqual(try store.snapshots(), before)
    }

    @MainActor
    func testHistoricalTripVehicleSnapshotIDAndOtherFieldsStayUntouched() throws {
        let container = try container()
        let context = ModelContext(container)
        context.autosaveEnabled = false
        let vehicle = Vehicle(id: a, name: "Synthetic original", now: date)
        let trip = Trip(id: c, startedAt: date, timezoneIdentifier: "UTC", now: date)
        trip.assignVehicle(vehicle, now: date)
        trip.notes = "Synthetic historical notes"
        trip.distanceMeters = 123
        context.insert(vehicle)
        context.insert(trip)
        try context.save()
        let store = Store(container: container, clock: { self.date.addingTimeInterval(10) }, makeID: { self.b })
        try store.update(id: a, draft: .init(name: "Synthetic renamed"))
        try store.create(.init(name: "Synthetic other"), makeDefault: true)
        try store.setDefault(id: a)
        try store.archive(id: a)
        let fresh = ModelContext(container)
        let stored = try XCTUnwrap(fresh.fetch(FetchDescriptor<Trip>()).first)
        XCTAssertEqual(stored.id, c)
        XCTAssertEqual(stored.vehicleID, a)
        XCTAssertEqual(stored.vehicleNameSnapshot, "Synthetic original")
        XCTAssertEqual(stored.updatedAt, date)
        XCTAssertEqual(stored.createdAt, date)
        XCTAssertEqual(stored.notes, "Synthetic historical notes")
        XCTAssertEqual(stored.distanceMeters, 123)
        XCTAssertEqual(try fresh.fetchCount(FetchDescriptor<Trip>()), 1)
    }

    @MainActor
    func testPrivateContextNeverSavesOrRollsBackUnrelatedMainContextChanges() throws {
        let container = try container()
        let main = container.mainContext
        main.autosaveEnabled = false
        let vehicle = Vehicle(id: a, name: "Synthetic A", now: date)
        let trip = Trip(id: b, startedAt: date, timezoneIdentifier: "UTC", now: date)
        trip.notes = "Committed"
        main.insert(vehicle)
        main.insert(trip)
        try main.save()
        trip.notes = "Unsaved staged edit"
        let unsaved = Trip(id: c, startedAt: date, timezoneIdentifier: "UTC", now: date)
        main.insert(unsaved)
        let store = store(container)
        try store.archive(id: a)
        XCTAssertTrue(main.hasChanges)
        XCTAssertEqual(trip.notes, "Unsaved staged edit")
        XCTAssertEqual(unsaved.id, c)
        let fresh = ModelContext(container)
        let trips = try fresh.fetch(FetchDescriptor<Trip>())
        XCTAssertEqual(trips.count, 1)
        XCTAssertEqual(trips.first?.notes, "Committed")
        XCTAssertTrue(try store.snapshots().first?.isArchived == true)
        let rejecting = Store(container: container, beforeSave: { throw SaveProbeFailure.rejected })
        do { _ = try rejecting.update(id: a, draft: .init(name: "Rejected rename")); XCTFail("Expected rejection") }
        catch let error as SaveProbeFailure { XCTAssertEqual(error, .rejected) }
        catch { XCTFail("Unexpected error type") }
        XCTAssertTrue(main.hasChanges)
        XCTAssertEqual(trip.notes, "Unsaved staged edit")
        XCTAssertEqual(try store.snapshots().first?.name, "Synthetic A")
    }

    private enum SaveProbeFailure: Error, Equatable { case rejected }

    @MainActor
    func testBeforeSaveRejectionRollsBackDefaultChangesAndCreationNoPartialResult() throws {
        let container = try container()
        let baselineStore = store(container)
        try baselineStore.create(.init(name: "Synthetic A"))
        let before = try baselineStore.snapshots()
        let rejecting = Store(container: container, clock: { self.date.addingTimeInterval(10) },
                              makeID: { self.b }, beforeSave: { throw SaveProbeFailure.rejected })
        var returned = false
        do {
            _ = try rejecting.create(.init(name: "Synthetic B"), makeDefault: true)
            returned = true
            XCTFail("Expected injected rejection")
        } catch let error as SaveProbeFailure {
            XCTAssertEqual(error, .rejected)
        } catch { XCTFail("Unexpected error type") }
        XCTAssertFalse(returned)
        XCTAssertEqual(try baselineStore.snapshots(), before)
        XCTAssertEqual(try ModelContext(container).fetchCount(FetchDescriptor<Vehicle>()), 1)
    }

    @MainActor
    func testBeforeSaveRejectionRollsBackEditDefaultAndArchive() throws {
        let container = try container()
        let baseline = store(container)
        try baseline.create(.init(name: "Synthetic A"))
        try baseline.create(.init(name: "Synthetic B"))
        let before = try baseline.snapshots()
        let rejecting = Store(container: container, clock: { self.date.addingTimeInterval(10) },
                              beforeSave: { throw SaveProbeFailure.rejected })
        let actions: [() throws -> Void] = [
            { _ = try rejecting.update(id: self.a, draft: .init(name: "Rejected edit")) },
            { _ = try rejecting.setDefault(id: self.b) },
            { _ = try rejecting.archive(id: self.a) }
        ]
        for action in actions {
            do { try action(); XCTFail("Expected injected rejection") }
            catch let error as SaveProbeFailure { XCTAssertEqual(error, .rejected) }
            catch { XCTFail("Unexpected error type") }
            XCTAssertEqual(try baseline.snapshots(), before)
        }
    }

    @MainActor
    func testMutationGuardReleasedAfterRejectedSaveHook() throws {
        let container = try container()
        var reject = true
        let store = Store(container: container, clock: { self.date }, makeID: { self.a }, beforeSave: {
            if reject { throw SaveProbeFailure.rejected }
        })
        do { _ = try store.create(.init(name: "Rejected")); XCTFail("Expected injected rejection") }
        catch let error as SaveProbeFailure { XCTAssertEqual(error, .rejected) }
        catch { XCTFail("Unexpected error type") }
        XCTAssertEqual(try store.snapshots(), [])
        reject = false
        XCTAssertEqual(try store.create(.init(name: "Synthetic retry")).id, a)
        XCTAssertEqual(try store.snapshots().count, 1)
    }

    @MainActor
    func testSynchronousReentryFromSaveHookRejectsBeforeSecondRow() throws {
        let container = try container()
        weak var reentrantStore: Store?
        var observed: [Store.StoreError] = []
        var hookCount = 0
        let store = Store(container: container, clock: { self.date }, makeID: { self.a }, beforeSave: {
            hookCount += 1
            guard let inner = reentrantStore else { XCTFail("Missing test store"); return }
            let actions: [() throws -> Void] = [
                { _ = try inner.create(.init(name: "Synthetic reentry")) },
                { _ = try inner.update(id: self.a, draft: .init(name: "Synthetic reentry")) },
                { _ = try inner.setDefault(id: self.a) },
                { _ = try inner.archive(id: self.a) }
            ]
            for action in actions {
                do { try action(); XCTFail("Expected reentry rejection") }
                catch let error as Store.StoreError { observed.append(error) }
                catch { XCTFail("Unexpected error type") }
            }
        })
        reentrantStore = store
        try store.create(.init(name: "Synthetic outer"))
        XCTAssertEqual(observed, Array(repeating: .reentrantMutation, count: 4))
        XCTAssertEqual(hookCount, 1)
        XCTAssertEqual(try store.snapshots().map(\.name), ["Synthetic outer"])
    }

    @MainActor
    func testVehicleArchiveAndHistoricalTripSnapshotSurviveSQLiteReopen() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent("MileageVehicle-\(UUID())")
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("synthetic.store")
        let schema = Schema([Vehicle.self, Trip.self])
        try autoreleasepool {
            let container = try ModelContainer(for: schema, configurations: ModelConfiguration(schema: schema, url: url))
            let store = store(container)
            try store.create(.init(name: "Synthetic original"))
            try store.create(.init(name: "Synthetic remaining"))
            let context = ModelContext(container)
            context.autosaveEnabled = false
            let vehicle = try XCTUnwrap(context.fetch(FetchDescriptor<Vehicle>()).first { $0.id == a })
            let trip = Trip(id: c, startedAt: date, timezoneIdentifier: "UTC", now: date)
            trip.assignVehicle(vehicle, now: date)
            trip.notes = "Synthetic historical notes"
            context.insert(trip)
            try context.save()
            try store.update(id: a, draft: .init(name: "Synthetic renamed"))
            try store.archive(id: a)
        }
        try autoreleasepool {
            let reopened = try ModelContainer(for: schema, configurations: ModelConfiguration(schema: schema, url: url))
            let snapshots = try Store(container: reopened).snapshots()
            XCTAssertEqual(snapshots.count, 2)
            let archived = try XCTUnwrap(snapshots.first { $0.id == a })
            XCTAssertTrue(archived.isArchived)
            XCTAssertFalse(archived.isDefault)
            XCTAssertEqual(archived.name, "Synthetic renamed")
            XCTAssertEqual(snapshots.filter(\.isDefault).map(\.id), [b])
            let trip = try XCTUnwrap(ModelContext(reopened).fetch(FetchDescriptor<Trip>()).first)
            XCTAssertEqual(trip.id, c)
            XCTAssertEqual(trip.vehicleID, a)
            XCTAssertEqual(trip.vehicleNameSnapshot, "Synthetic original")
            XCTAssertEqual(trip.notes, "Synthetic historical notes")
        }
    }

    @MainActor
    func testPassiveReadsNeverCreateOrRepairDefaults() throws {
        let container = try container()
        let store = store(container)
        XCTAssertEqual(try store.snapshots(), [])
        try seed([Vehicle(id: a, name: "Synthetic A", now: date)], in: container)
        let first = try store.snapshots()
        XCTAssertEqual(first.count, 1)
        XCTAssertFalse(try XCTUnwrap(first.first).isDefault)
        XCTAssertEqual(try store.snapshots(), first)
        let fresh = ModelContext(container)
        let row = try XCTUnwrap(fresh.fetch(FetchDescriptor<Vehicle>()).first)
        XCTAssertFalse(row.isDefault)
        XCTAssertEqual(row.updatedAt, date)
    }
}
