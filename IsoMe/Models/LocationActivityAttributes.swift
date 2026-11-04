import ActivityKit
import AppIntents
import Foundation

enum IsoMeURLs {
    static let stopTracking = URL(string: "whereday://stop")
    static let privacyPolicy = URL(string: "https://whereday.com/privacy")
    static let termsOfService = URL(string: "https://whereday.com/terms")
    static let discordInvite = URL(string: "https://discord.gg/RaQYS4t6gn")

    /// Opens the check-in form, prefilled with a coordinate when one is known.
    /// Saving still goes through the app's normal check-in validation.
    static func checkIn(latitude: Double?, longitude: Double?) -> URL? {
        var components = URLComponents()
        components.scheme = "whereday"
        components.host = "check-in"
        if let latitude, let longitude,
           (-90...90).contains(latitude), (-180...180).contains(longitude) {
            components.queryItems = [
                URLQueryItem(name: "latitude", value: String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), latitude)),
                URLQueryItem(name: "longitude", value: String(format: "%.6f", locale: Locale(identifier: "en_US_POSIX"), longitude))
            ]
        }
        return components.url
    }
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
        /// First named place of this recording, shown as "origin → current"
        var originLocationName: String? = nil
        /// Latest coordinate, used to prefill "Mark this place"
        var latitude: Double? = nil
        var longitude: Double? = nil
        /// How the last few minutes were most likely travelled, estimated from GPS speed.
        /// nil while standing still or before there's enough movement to tell.
        var travelMode: TravelMode? = nil
    }

    /// When tracking started
    var startTime: Date
}

// MARK: - Live Activity buttons

/// "Mark this place" on the Lock Screen and in the Dynamic Island: checks in at the
/// recording's latest coordinate without opening the app.
///
/// Declared here so both the app and the widget extension compile it; as a
/// `LiveActivityIntent`, the system runs `perform()` in the app's process. The app's
/// implementation lives in IsoMeIntents.swift; the widget extension carries a no-op stub.
struct MarkPlaceLiveActivityIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Mark This Place"
    static var description = IntentDescription("Check in at your current place from the Whereday Live Activity.")
    static var openAppWhenRun: Bool = false
    static var isDiscoverable: Bool = false

    @Parameter(title: "Place Name")
    var name: String?

    @Parameter(title: "Latitude")
    var latitude: Double?

    @Parameter(title: "Longitude")
    var longitude: Double?

    init() {}

    init(name: String?, latitude: Double?, longitude: Double?) {
        self.name = name
        self.latitude = latitude
        self.longitude = longitude
    }
}

/// "Stop route" on the Lock Screen and in the Dynamic Island: stops tracking in place.
/// Runs the same logic as the Stop Tracking shortcut (see IsoMeIntents.swift).
struct StopRouteLiveActivityIntent: LiveActivityIntent {
    static var title: LocalizedStringResource = "Stop Route"
    static var description = IntentDescription("Stop the current Whereday recording from the Live Activity.")
    static var openAppWhenRun: Bool = false
    static var isDiscoverable: Bool = false

    init() {}
}

// MARK: - Today snapshot for widgets

/// Today's stays and headline numbers for the Home Screen widgets and the Live Activity dial.
/// The app writes it into the App Group; the widget extension only reads it.
struct TodayWidgetSnapshot: Codable, Hashable {
    struct Stay: Codable, Hashable {
        var placeName: String
        var arrivedAt: Date
        /// nil while the stay is ongoing.
        var departedAt: Date?
    }

    var generatedAt: Date
    var stays: [Stay]
    var distanceMeters: Double
    var usesMetricDistanceUnits: Bool
    var isTracking: Bool
    /// Days with recorded history, when the app knows it.
    var recordedDayCount: Int? = nil

    static let appGroupIdentifier = "group.com.bontecou.isome"
    static let userDefaultsKey = "todayWidgetSnapshot"
    /// Widget kinds that render this snapshot, for targeted timeline reloads.
    static let widgetKinds = ["WheredayTimelineStrip", "WheredayDayDial"]
    /// Most stays a widget needs; keeps the App Group payload small.
    static let maxStays = 48

    func save(to defaults: UserDefaults? = UserDefaults(suiteName: TodayWidgetSnapshot.appGroupIdentifier)) {
        guard let defaults else { return }
        var trimmed = self
        trimmed.stays = Array(stays.sorted { $0.arrivedAt < $1.arrivedAt }.suffix(Self.maxStays))
        if let encoded = try? JSONEncoder().encode(trimmed) {
            defaults.set(encoded, forKey: Self.userDefaultsKey)
        }
    }

    static func load(from defaults: UserDefaults? = UserDefaults(suiteName: TodayWidgetSnapshot.appGroupIdentifier)) -> TodayWidgetSnapshot? {
        guard let defaults,
              let data = defaults.data(forKey: userDefaultsKey) else { return nil }
        return try? JSONDecoder().decode(TodayWidgetSnapshot.self, from: data)
    }

    /// A stay clipped to the day containing `now`, as fractions of 24 hours.
    struct DayStay: Hashable {
        var placeName: String
        var start: Date
        var end: Date
        var startFraction: Double
        var endFraction: Double
        var isOngoing: Bool
        /// Index into a place-ink palette, stable per place name within the day.
        var inkIndex: Int
    }

    /// Stays overlapping the day of `now`, clipped to midnight and `now`.
    /// An ongoing stay only extends to `now` if the snapshot is from the same day.
    func dayStays(now: Date, calendar: Calendar = .current) -> [DayStay] {
        let startOfDay = calendar.startOfDay(for: now)
        var inkByPlace: [String: Int] = [:]
        return stays
            .sorted { $0.arrivedAt < $1.arrivedAt }
            .compactMap { stay in
                let start = max(stay.arrivedAt, startOfDay)
                let end = min(stay.departedAt ?? now, now)
                guard end > start else { return nil }
                let ink = inkByPlace[stay.placeName] ?? inkByPlace.count
                inkByPlace[stay.placeName] = ink
                return DayStay(
                    placeName: stay.placeName,
                    start: start,
                    end: end,
                    startFraction: Self.fraction(of: start, startOfDay: startOfDay),
                    endFraction: Self.fraction(of: end, startOfDay: startOfDay),
                    isOngoing: stay.departedAt == nil,
                    inkIndex: ink
                )
            }
    }

    static func fraction(of date: Date, startOfDay: Date) -> Double {
        min(max(date.timeIntervalSince(startOfDay) / 86_400, 0), 1)
    }
}
