import Foundation
import SwiftData
import CoreLocation

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

    init(
        id: UUID = UUID(),
        latitude: Double,
        longitude: Double,
        timestamp: Date,
        altitude: Double? = nil,
        speed: Double? = nil,
        horizontalAccuracy: Double,
        isOutlier: Bool = false
    ) {
        self.id = id
        self.latitude = latitude
        self.longitude = longitude
        self.timestamp = timestamp
        self.altitude = altitude
        self.speed = speed
        self.horizontalAccuracy = horizontalAccuracy
        self.isOutlier = isOutlier
    }

    convenience init(from location: CLLocation) {
        self.init(
            latitude: location.coordinate.latitude,
            longitude: location.coordinate.longitude,
            timestamp: location.timestamp,
            altitude: location.altitude,
            speed: location.speed >= 0 ? location.speed : nil,
            horizontalAccuracy: location.horizontalAccuracy
        )
    }

    var coordinate: CLLocationCoordinate2D {
        CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
    }

    func distance(to other: LocationPoint) -> Double {
        let from = CLLocation(latitude: latitude, longitude: longitude)
        let to = CLLocation(latitude: other.latitude, longitude: other.longitude)
        return from.distance(from: to)
    }

    var accessibilityTimestamp: String {
        timestamp.formatted(date: .abbreviated, time: .shortened)
    }

    var accessibilityCoordinateSummary: String {
        String(format: String(localized: "Latitude %.4f, longitude %.4f"), locale: .current, latitude, longitude)
    }

    var accessibilityAccuracySummary: String {
        String(format: String(localized: "Accuracy about %.0f meters"), locale: .current, horizontalAccuracy)
    }

    var accessibilityValue: String {
        var parts = [
            accessibilityTimestamp,
            accessibilityCoordinateSummary,
            accessibilityAccuracySummary
        ]

        if let speed {
            parts.append(String(format: String(localized: "Speed %.1f meters per second"), locale: .current, speed))
        }

        if isOutlier {
            parts.append(String(localized: "Marked as an outlier"))
        }

        return parts.joined(separator: ". ")
    }
}

extension LocationPoint {
    static var preview: LocationPoint {
        LocationPoint(
            latitude: 37.7749,
            longitude: -122.4194,
            timestamp: Date(),
            altitude: 10.0,
            speed: 1.5,
            horizontalAccuracy: 5.0
        )
    }
}
