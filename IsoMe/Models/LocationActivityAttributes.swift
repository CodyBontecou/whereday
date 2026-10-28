import ActivityKit
import Foundation

enum IsoMeURLs {
    static let stopTracking = URL(string: "whereday://stop")
    static let privacyPolicy = URL(string: "https://whereday.com/privacy")
    static let termsOfService = URL(string: "https://whereday.com/terms")
    static let discordInvite = URL(string: "https://discord.gg/RaQYS4t6gn")
}

/// Shared attributes for the Location Tracking Live Activity
struct LocationActivityAttributes: ActivityAttributes {

    /// Static data that doesn't change during the activity
    public struct ContentState: Codable, Hashable {
        /// Last known location name (e.g., "Home", "123 Main St")
        var locationName: String?
        /// Number of locations recorded this session
        var locationsRecorded: Int
        /// Distance traveled in meters
        var distanceTraveled: Double
        /// Time remaining for auto-stop (nil if "Never")
        var remainingSeconds: Int?
        /// Last update timestamp
        var lastUpdate: Date
        /// Preferred distance units (true = metric, false = US standard)
        var usesMetricDistanceUnits: Bool? = nil
        /// Incremented when a new map snapshot is available in the App Group container
        var mapSnapshotVersion: Int = 0
    }

    /// When tracking started
    var startTime: Date
}
