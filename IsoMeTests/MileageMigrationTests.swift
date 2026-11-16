import XCTest
import SwiftData
@testable import IsoMe

/// Exact persisted LocationPoint fields at f003fe3e, before the optional tripID addition.
/// The other legacy entities use the unchanged production definitions in the old schema.
private enum PreMileageSchema {
    @Model
    final class LocationPoint {
        var id: UUID
        var latitude: Double
        var longitude: Double
        var timestamp: Date
        var altitude: Double?
        var speed: Double?
        var horizontalAccuracy: Double
        var isOutlier: Bool = false
        var countryCode: String?
        var countryTimeZoneIdentifier: String?
        var countryResolutionAttemptedAt: Date?

        init(id: UUID, timestamp: Date) {
            self.id = id
            self.latitude = 0.001
            self.longitude = 0.002
            self.timestamp = timestamp
            self.altitude = 42
            self.speed = 12
            self.horizontalAccuracy = 7
            self.isOutlier = true
            self.countryCode = "ZZ"
            self.countryTimeZoneIdentifier = "UTC"
            self.countryResolutionAttemptedAt = timestamp
        }
    }
}

@MainActor
final class MileageMigrationTests: XCTestCase {
    func testPreMileageStoreMigratesToSharedSchemaAndReopensWithoutDataLoss() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("synthetic-legacy.store")
        let pointID = UUID()
        let visitID = UUID()
        let sessionID = UUID()
        let tripID = UUID()
        let vehicleID = UUID()
        let timestamp = Date(timeIntervalSince1970: 1_700_000_000)
        try autoreleasepool {
            let oldSchema = Schema([Visit.self, PreMileageSchema.LocationPoint.self, RecordingSession.self,
                                    PhotoMoment.self, SavedPlace.self, CountryStay.self])
            let old = try ModelContainer(for: oldSchema, configurations: ModelConfiguration(schema: oldSchema, url: url))
            let context = ModelContext(old)
            context.insert(PreMileageSchema.LocationPoint(id: pointID, timestamp: timestamp))
            let visit = Visit(id: visitID, latitude: 0.003, longitude: 0.004, arrivedAt: timestamp,
                              departedAt: timestamp.addingTimeInterval(300), customName: "Synthetic visit",
                              locationName: "Synthetic detected place", address: "Synthetic address token",
                              notes: "Synthetic visit notes", geocodingCompleted: true,
                              confirmationStatus: .corrected, confirmedAt: timestamp, updatedAt: timestamp,
                              originalLatitude: 0.005, originalLongitude: 0.006,
                              originalLocationName: "Synthetic original", originalAddress: "Synthetic original address")
            visit.originalConfirmationStatusRaw = "confirmed"
            visit.originalConfirmedAt = timestamp.addingTimeInterval(-60)
            visit.originalPlaceSourceRaw = "synthetic-original-source"
            visit.originalPlaceDistanceMeters = 15
            visit.placeSourceRaw = "synthetic-place-source"
            visit.placeDistanceMeters = 18
            visit.countryCode = "ZZ"
            visit.countryTimeZoneIdentifier = "UTC"
            visit.countryResolutionAttemptedAt = timestamp
            context.insert(visit)
            context.insert(RecordingSession(id: sessionID, startedAt: timestamp,
                                            endedAt: timestamp.addingTimeInterval(300), customName: "Synthetic outing",
                                            notes: "Synthetic session notes", receivedLocationCount: 12,
                                            distanceFilterMeters: 10, routeSamplingModeRawValue: "synthetic-mode",
                                            routeSampleIntervalSeconds: 5))
            try context.save()
        }
        try autoreleasepool {
            let migrated = try ModelContainer(for: AppModelStore.schema,
                                               configurations: ModelConfiguration(schema: AppModelStore.schema, url: url))
            let context = ModelContext(migrated)
            try assertLegacyFields(context, pointID: pointID, visitID: visitID, sessionID: sessionID, timestamp: timestamp)
            XCTAssertNil(try context.fetch(FetchDescriptor<LocationPoint>()).first?.tripID)
            XCTAssertEqual(try context.fetchCount(FetchDescriptor<Trip>()), 0)
            XCTAssertEqual(try context.fetchCount(FetchDescriptor<Vehicle>()), 0)
            let vehicle = Vehicle(id: vehicleID, name: "Synthetic migration vehicle", now: timestamp)
            let trip = Trip(id: tripID, startedAt: timestamp, timezoneIdentifier: "UTC", now: timestamp)
            trip.endedAt = timestamp.addingTimeInterval(300)
            trip.assignVehicle(vehicle, now: timestamp)
            context.insert(vehicle)
            context.insert(trip)
            MileageVehiclePolicy.normalizeDefaults(in: [vehicle], now: timestamp)
            let point = try XCTUnwrap(context.fetch(FetchDescriptor<LocationPoint>()).first)
            point.tripID = trip.id
            try context.save()
        }
        let reopened = try ModelContainer(for: AppModelStore.schema,
                                          configurations: ModelConfiguration(schema: AppModelStore.schema, url: url))
        let context = ModelContext(reopened)
        try assertLegacyFields(context, pointID: pointID, visitID: visitID, sessionID: sessionID, timestamp: timestamp)
        XCTAssertEqual(try context.fetch(FetchDescriptor<LocationPoint>()).first?.tripID, tripID)
        let trip = try XCTUnwrap(context.fetch(FetchDescriptor<Trip>()).first)
        XCTAssertEqual(trip.id, tripID)
        XCTAssertEqual(trip.classification, .unclassified)
        XCTAssertEqual(trip.reviewStatus, .needsReview)
        XCTAssertEqual(trip.vehicleID, vehicleID)
        XCTAssertEqual(trip.vehicleNameSnapshot, "Synthetic migration vehicle")
        let vehicle = try XCTUnwrap(context.fetch(FetchDescriptor<Vehicle>()).first)
        XCTAssertEqual(vehicle.id, vehicleID)
        XCTAssertTrue(vehicle.isDefault)
    }

    private func assertLegacyFields(_ context: ModelContext, pointID: UUID, visitID: UUID,
                                    sessionID: UUID, timestamp: Date) throws {
        let points = try context.fetch(FetchDescriptor<LocationPoint>())
        XCTAssertEqual(points.count, 1)
        let point = try XCTUnwrap(points.first)
        XCTAssertEqual(point.id, pointID)
        XCTAssertEqual(point.latitude, 0.001)
        XCTAssertEqual(point.longitude, 0.002)
        XCTAssertEqual(point.timestamp, timestamp)
        XCTAssertEqual(point.altitude, 42)
        XCTAssertEqual(point.speed, 12)
        XCTAssertEqual(point.horizontalAccuracy, 7)
        XCTAssertTrue(point.isOutlier)
        XCTAssertEqual(point.countryCode, "ZZ")
        XCTAssertEqual(point.countryTimeZoneIdentifier, "UTC")
        XCTAssertEqual(point.countryResolutionAttemptedAt, timestamp)
        let visits = try context.fetch(FetchDescriptor<Visit>())
        XCTAssertEqual(visits.count, 1)
        let visit = try XCTUnwrap(visits.first)
        XCTAssertEqual(visit.id, visitID)
        XCTAssertEqual(visit.latitude, 0.003)
        XCTAssertEqual(visit.longitude, 0.004)
        XCTAssertEqual(visit.arrivedAt, timestamp)
        XCTAssertEqual(visit.departedAt, timestamp.addingTimeInterval(300))
        XCTAssertEqual(visit.customName, "Synthetic visit")
        XCTAssertEqual(visit.locationName, "Synthetic detected place")
        XCTAssertEqual(visit.address, "Synthetic address token")
        XCTAssertEqual(visit.notes, "Synthetic visit notes")
        XCTAssertTrue(visit.geocodingCompleted)
        XCTAssertEqual(visit.source, .automatic)
        XCTAssertEqual(visit.confirmationStatus, .corrected)
        XCTAssertEqual(visit.confirmedAt, timestamp)
        XCTAssertEqual(visit.updatedAt, timestamp)
        XCTAssertEqual(visit.originalLatitude, 0.005)
        XCTAssertEqual(visit.originalLongitude, 0.006)
        XCTAssertEqual(visit.originalLocationName, "Synthetic original")
        XCTAssertEqual(visit.originalAddress, "Synthetic original address")
        XCTAssertEqual(visit.detectedLatitude, 0.003)
        XCTAssertEqual(visit.detectedLongitude, 0.004)
        XCTAssertEqual(visit.detectedLocationName, "Synthetic detected place")
        XCTAssertEqual(visit.detectedAddress, "Synthetic address token")
        XCTAssertEqual(visit.originalConfirmationStatusRaw, "confirmed")
        XCTAssertEqual(visit.originalConfirmedAt, timestamp.addingTimeInterval(-60))
        XCTAssertEqual(visit.originalPlaceSourceRaw, "synthetic-original-source")
        XCTAssertEqual(visit.originalPlaceDistanceMeters, 15)
        XCTAssertEqual(visit.placeSourceRaw, "synthetic-place-source")
        XCTAssertEqual(visit.placeDistanceMeters, 18)
        XCTAssertEqual(visit.countryDepartureIsInferred, false)
        XCTAssertEqual(visit.countryCode, "ZZ")
        XCTAssertEqual(visit.countryTimeZoneIdentifier, "UTC")
        XCTAssertEqual(visit.countryResolutionAttemptedAt, timestamp)
        let sessions = try context.fetch(FetchDescriptor<RecordingSession>())
        XCTAssertEqual(sessions.count, 1)
        let session = try XCTUnwrap(sessions.first)
        XCTAssertEqual(session.id, sessionID)
        XCTAssertEqual(session.startedAt, timestamp)
        XCTAssertEqual(session.endedAt, timestamp.addingTimeInterval(300))
        XCTAssertEqual(session.customName, "Synthetic outing")
        XCTAssertEqual(session.notes, "Synthetic session notes")
        XCTAssertEqual(session.receivedLocationCount, 12)
        XCTAssertEqual(session.distanceFilterMeters, 10)
        XCTAssertEqual(session.routeSamplingModeRawValue, "synthetic-mode")
        XCTAssertEqual(session.routeSampleIntervalSeconds, 5)
    }
}
