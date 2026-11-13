import Foundation
import SwiftData

enum TripClassification: String, Codable, CaseIterable {
    case unclassified, business, personal
}

enum TripReviewStatus: String, Codable {
    case needsReview, reviewed, autoClassified
}

enum TripSource: String, Codable {
    case manualSession, autoDrive, historicalBackfill, imported
}

/// Mileage summaries use scalar IDs, never relationships to the GPS point collection.
/// Registered in the shared AppModelStore; manual trip recording is a later slice.
@Model
final class Trip {
    var id: UUID
    var startedAt: Date
    var endedAt: Date?
    var timezoneIdentifier: String
    var startLatitude: Double?
    var startLongitude: Double?
    var endLatitude: Double?
    var endLongitude: Double?
    var startName: String?
    var startAddress: String?
    var endName: String?
    var endAddress: String?
    /// Filtered GPS distance, before any manual override. Never overwrite this on manual edits.
    var distanceMeters: Double = 0
    var rawDistanceMeters: Double = 0
    var manualDistanceMeters: Double?
    var pointCount: Int = 0
    var classificationRaw: String = TripClassification.unclassified.rawValue
    var reviewStatusRaw: String = TripReviewStatus.needsReview.rawValue
    var businessPurpose: String = ""
    var notes: String = ""
    var vehicleID: UUID?
    var vehicleNameSnapshot: String?
    var sourceRaw: String = TripSource.manualSession.rawValue
    var classifiedAt: Date?
    var createdAt: Date
    var updatedAt: Date
    var startOdometerMiles: Double?
    var endOdometerMiles: Double?
    var excludeFromReports: Bool = false

    init(id: UUID = UUID(), startedAt: Date, timezoneIdentifier: String = TimeZone.current.identifier,
         source: TripSource = .manualSession, now: Date = Date()) {
        self.id = id
        self.startedAt = startedAt
        self.timezoneIdentifier = timezoneIdentifier
        self.sourceRaw = source.rawValue
        self.createdAt = now
        self.updatedAt = now
    }

    // Unknown future values remain preserved in storage and are treated conservatively.
    var classification: TripClassification {
        TripClassification(rawValue: classificationRaw) ?? .unclassified
    }

    var reviewStatus: TripReviewStatus {
        TripReviewStatus(rawValue: reviewStatusRaw) ?? .needsReview
    }

    var isMissingBusinessPurpose: Bool {
        classification == .business && businessPurpose.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
    }

    var effectiveDistanceMeters: Double {
        manualDistanceMeters ?? distanceMeters
    }

    func setManualDistanceMeters(_ meters: Double?, now: Date = Date()) throws {
        if let meters, !meters.isFinite || meters < 0 { throw MileageValidationError.invalidDistance }
        manualDistanceMeters = meters
        updatedAt = now
    }

    /// Classification is always an explicit action; creation never infers deductibility.
    func classify(as classification: TripClassification, reviewed: Bool, now: Date = Date()) {
        classificationRaw = classification.rawValue
        reviewStatusRaw = (reviewed ? TripReviewStatus.reviewed : .needsReview).rawValue
        classifiedAt = now
        updatedAt = now
    }

    func assignVehicle(_ vehicle: Vehicle?, now: Date = Date()) {
        vehicleID = vehicle?.id
        vehicleNameSnapshot = vehicle?.name
        updatedAt = now
    }
}

enum MileageValidationError: Error, Equatable {
    case invalidDistance
    case invalidDistanceConfiguration
    case invalidDateRange
    case invalidRate
    case overlappingRates
    case invalidTrip(UUID)
}
