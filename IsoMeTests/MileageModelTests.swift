import XCTest
import SwiftData
@testable import IsoMe

final class MileageModelTests: XCTestCase {
    private let date = Date(timeIntervalSince1970: 1_700_000_000)

    func testTripDefaultsNeverClassifyOrReviewAutomatically() {
        let trip = Trip(startedAt: date, timezoneIdentifier: "UTC", now: date)
        XCTAssertEqual(trip.classification, .unclassified)
        XCTAssertEqual(trip.reviewStatus, .needsReview)
        XCTAssertEqual(trip.sourceRaw, "manualSession")
        XCTAssertEqual(trip.effectiveDistanceMeters, 0)
        XCTAssertNil(trip.endedAt)
        XCTAssertNil(trip.vehicleID)
        XCTAssertNil(trip.classifiedAt)
        XCTAssertFalse(trip.excludeFromReports)
        XCTAssertEqual(trip.createdAt, date)
    }

    func testUnknownClassificationAndReviewRemainConservative() {
        let trip = Trip(startedAt: date)
        trip.classificationRaw = "future-category"
        trip.reviewStatusRaw = "future-review"
        XCTAssertEqual(trip.classification, .unclassified)
        XCTAssertEqual(trip.reviewStatus, .needsReview)
        XCTAssertEqual(trip.classificationRaw, "future-category")
    }

    func testBusinessPurposeTrimsWhitespace() {
        let trip = Trip(startedAt: date)
        XCTAssertFalse(trip.isMissingBusinessPurpose)
        trip.classify(as: .business, reviewed: false, now: date)
        trip.businessPurpose = " \n\t"
        XCTAssertTrue(trip.isMissingBusinessPurpose)
        trip.businessPurpose = "Synthetic equipment delivery"
        XCTAssertFalse(trip.isMissingBusinessPurpose)
        XCTAssertEqual(trip.classifiedAt, date)
        XCTAssertEqual(trip.reviewStatus, .needsReview)
    }

    func testManualOverridePreservesGPSAndCanBeClearedOrZero() throws {
        let trip = Trip(startedAt: date)
        trip.distanceMeters = 500
        trip.rawDistanceMeters = 900
        try trip.setManualDistanceMeters(600, now: date)
        XCTAssertEqual(trip.effectiveDistanceMeters, 600)
        XCTAssertEqual(trip.distanceMeters, 500)
        XCTAssertEqual(trip.rawDistanceMeters, 900)
        try trip.setManualDistanceMeters(0)
        XCTAssertEqual(trip.effectiveDistanceMeters, 0)
        try trip.setManualDistanceMeters(nil)
        XCTAssertEqual(trip.effectiveDistanceMeters, 500)
    }

    func testManualOverrideRejectsInvalidValuesWithoutMutation() throws {
        let trip = Trip(startedAt: date)
        try trip.setManualDistanceMeters(100)
        for invalid in [-1, Double.nan, Double.infinity, -Double.infinity] {
            XCTAssertThrowsError(try trip.setManualDistanceMeters(invalid))
            XCTAssertEqual(trip.manualDistanceMeters, 100)
        }
    }

    func testVehicleSnapshotSurvivesRenameAndArchive() {
        let vehicle = Vehicle(name: "Synthetic Van A", now: date)
        let trip = Trip(startedAt: date)
        trip.assignVehicle(vehicle, now: date)
        vehicle.name = "Synthetic Van B"
        vehicle.isArchived = true
        XCTAssertEqual(trip.vehicleID, vehicle.id)
        XCTAssertEqual(trip.vehicleNameSnapshot, "Synthetic Van A")
        trip.assignVehicle(nil)
        XCTAssertNil(trip.vehicleID)
        XCTAssertNil(trip.vehicleNameSnapshot)
    }

    func testVehicleDefaultsAndEmptyCollection() {
        let vehicle = Vehicle(name: "Synthetic Vehicle", now: date)
        XCTAssertFalse(vehicle.isDefault)
        XCTAssertFalse(vehicle.isArchived)
        XCTAssertNil(vehicle.licensePlateLast4)
        XCTAssertNil(MileageVehiclePolicy.normalizeDefaults(in: []))
        XCTAssertEqual(MileageVehiclePolicy.normalizeDefaults(in: [vehicle]), vehicle.id)
        XCTAssertTrue(vehicle.isDefault)
    }

    func testDuplicateDefaultsRepairDeterministicallyAndSelectionWins() {
        let first = Vehicle(name: "Synthetic A", now: date)
        let second = Vehicle(name: "Synthetic B", now: date.addingTimeInterval(1))
        first.isDefault = true
        second.isDefault = true
        XCTAssertEqual(MileageVehiclePolicy.normalizeDefaults(in: [second, first]), first.id)
        XCTAssertFalse(second.isDefault)
        XCTAssertEqual(MileageVehiclePolicy.normalizeDefaults(in: [first, second], preferredID: second.id), second.id)
        XCTAssertFalse(first.isDefault)
        XCTAssertTrue(second.isDefault)
    }

    func testArchiveReassignsDefaultAndAllArchivedLeavesNone() {
        let first = Vehicle(name: "Synthetic A", now: date)
        let second = Vehicle(name: "Synthetic B", now: date.addingTimeInterval(1))
        first.isDefault = true
        first.isArchived = true
        XCTAssertEqual(MileageVehiclePolicy.normalizeDefaults(in: [first, second], preferredID: first.id), second.id)
        XCTAssertFalse(first.isDefault)
        second.isArchived = true
        XCTAssertNil(MileageVehiclePolicy.normalizeDefaults(in: [first, second]))
        XCTAssertFalse(second.isDefault)
    }

    func testDefaultRepairIsIdempotent() {
        let vehicle = Vehicle(name: "Synthetic A", now: date)
        MileageVehiclePolicy.normalizeDefaults(in: [vehicle], now: date)
        MileageVehiclePolicy.normalizeDefaults(in: [vehicle], now: date.addingTimeInterval(10))
        XCTAssertEqual(vehicle.updatedAt, date)
    }

    @MainActor
    func testNewModelsReopenFromSyntheticDiskStore() throws {
        // This is new-model persistence, NOT the legacy app migration acceptance test.
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let schema = Schema([Trip.self, Vehicle.self])
        let config = ModelConfiguration(schema: schema, url: directory.appendingPathComponent("synthetic.store"))
        let id = UUID()
        do {
            let container = try ModelContainer(for: schema, configurations: [config])
            let context = ModelContext(container)
            let vehicle = Vehicle(name: "Synthetic disk vehicle", now: date)
            let trip = Trip(id: id, startedAt: date, timezoneIdentifier: "UTC", now: date)
            trip.assignVehicle(vehicle)
            trip.classify(as: .personal, reviewed: true, now: date)
            context.insert(trip)
            context.insert(vehicle)
            try context.save()
        }
        let reopened = try ModelContainer(for: schema, configurations: [config])
        let context = ModelContext(reopened)
        let stored = try XCTUnwrap(context.fetch(FetchDescriptor<Trip>()).first)
        XCTAssertEqual(stored.id, id)
        XCTAssertEqual(stored.classification, .personal)
        XCTAssertEqual(stored.vehicleNameSnapshot, "Synthetic disk vehicle")
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Vehicle>()), 1)
    }

    @MainActor
    func testClassificationAndOverridePersistInIsolatedContainer() throws {
        let schema = Schema([Trip.self, Vehicle.self])
        let config = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)
        let container = try ModelContainer(for: schema, configurations: [config])
        let context = ModelContext(container)
        let vehicle = Vehicle(name: "Synthetic A", now: date)
        let trip = Trip(startedAt: date, timezoneIdentifier: "UTC", now: date)
        trip.classify(as: .business, reviewed: true, now: date)
        trip.businessPurpose = "Synthetic delivery"
        trip.assignVehicle(vehicle)
        try trip.setManualDistanceMeters(123)
        context.insert(vehicle)
        context.insert(trip)
        try context.save()
        let fresh = ModelContext(container)
        let stored = try XCTUnwrap(fresh.fetch(FetchDescriptor<Trip>()).first)
        XCTAssertEqual(stored.classification, .business)
        XCTAssertEqual(stored.reviewStatus, .reviewed)
        XCTAssertEqual(stored.effectiveDistanceMeters, 123)
        XCTAssertEqual(stored.vehicleID, vehicle.id)
        XCTAssertEqual(stored.vehicleNameSnapshot, "Synthetic A")
        XCTAssertEqual(stored.timezoneIdentifier, "UTC")
    }
}
