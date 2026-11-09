import Foundation

/// Shared data model for transferring tracking state between iOS and watchOS
struct SharedLocationData: Codable {
    var isTrackingEnabled: Bool
    var currentLocationName: String?
    var currentAddress: String?
    var lastLatitude: Double?
    var lastLongitude: Double?
    var lastUpdateTime: Date?
    var todayVisitsCount: Int
    var todayDistanceMeters: Double
    var todayPointsCount: Int
    var trackingStartTime: Date?
    var stopAfterHours: Double?
    var usesMetricDistanceUnits: Bool? = nil
    /// Days with recorded history ("Day N"), when the publisher knows it.
    var recordedDayCount: Int? = nil
    /// Distance of the route currently being recorded on this device, in meters.
    /// The watch keeps it per route session; the iPhone leaves it nil.
    var routeDistanceMeters: Double? = nil

    static let appGroupIdentifier = "group.com.bontecou.isome"
    private static let userDefaultsKey = "sharedLocationData"

    /// Save to shared App Group UserDefaults
    func save() {
        guard let defaults = UserDefaults(suiteName: Self.appGroupIdentifier) else { return }
        if let encoded = try? JSONEncoder().encode(self) {
            defaults.set(encoded, forKey: Self.userDefaultsKey)
        }
    }

    /// Load from shared App Group UserDefaults
    static func load() -> SharedLocationData? {
        guard let defaults = UserDefaults(suiteName: Self.appGroupIdentifier),
              let data = defaults.data(forKey: Self.userDefaultsKey),
              let decoded = try? JSONDecoder().decode(SharedLocationData.self, from: data) else {
            return nil
        }
        return decoded
    }

    /// Default empty state
    static var empty: SharedLocationData {
        SharedLocationData(
            isTrackingEnabled: false,
            currentLocationName: nil,
            currentAddress: nil,
            lastLatitude: nil,
            lastLongitude: nil,
            lastUpdateTime: nil,
            todayVisitsCount: 0,
            todayDistanceMeters: 0,
            todayPointsCount: 0,
            trackingStartTime: nil,
            stopAfterHours: nil,
            usesMetricDistanceUnits: true
        )
    }
}

// MARK: - Convenience Properties

extension SharedLocationData {
    var displayLocationName: String {
        currentLocationName ?? currentAddress ?? String(localized: "Unknown")
    }

    var prefersMetricDistanceUnits: Bool {
        usesMetricDistanceUnits ?? true
    }

    var formattedDistance: String {
        DistanceFormatter.format(meters: todayDistanceMeters, usesMetric: prefersMetricDistanceUnits)
    }

    var trackingStatus: String {
        isTrackingEnabled ? String(localized: "Tracking") : String(localized: "Off")
    }

    var remainingTime: TimeInterval? {
        guard let startTime = trackingStartTime,
              let hours = stopAfterHours,
              hours > 0 else {
            return nil
        }
        let elapsed = Date().timeIntervalSince(startTime)
        let total = hours * 3600
        return max(0, total - elapsed)
    }

    var formattedRemainingTime: String? {
        guard let remaining = remainingTime else { return nil }
        let hours = Int(remaining) / 3600
        let minutes = (Int(remaining) % 3600) / 60
        if hours > 0 {
            return String(localized: "\(hours)h \(minutes)m")
        }
        return String(localized: "\(minutes)m")
    }
}

// MARK: - Today context for Apple Watch

/// Today's stays as the iPhone knows them, sent to Apple Watch through the WatchConnectivity
/// application context. The watch keeps the latest copy in its own App Group so the app and its
/// complications can draw the day dial without the phone nearby.
struct WatchTodayContext: Codable, Equatable {
    struct Stay: Codable, Equatable {
        var placeName: String
        var arrivedAt: Date
        /// nil while the stay is ongoing.
        var departedAt: Date?
        /// Index into a place-ink palette, stable per place name within the day.
        var inkIndex: Int
    }

    /// A stay clipped to the day of `now`, as fractions of 24 hours.
    struct Arc: Equatable {
        var startFraction: Double
        var endFraction: Double
        var inkIndex: Int
    }

    var generatedAt: Date
    var stays: [Stay]
    /// The ongoing stay's place, if any.
    var currentStayName: String?
    /// When the ongoing stay began (not clipped to midnight).
    var currentStaySince: Date?
    /// Today's distance on the iPhone, in meters.
    var distanceMeters: Double
    var usesMetricDistanceUnits: Bool
    var recordedDayCount: Int? = nil

    /// Key inside the WatchConnectivity application context dictionary.
    static let applicationContextKey = "com.bontecou.isome.watchTodayContext"
    private static let userDefaultsKey = "watchTodayContext"
    /// Most stays the watch needs; keeps the application context small.
    static let maxStays = 48

    func isSameDay(as date: Date, calendar: Calendar = .current) -> Bool {
        calendar.isDate(generatedAt, inSameDayAs: date)
    }

    /// Stays overlapping the day of `now`, clipped to midnight and `now`.
    /// Empty when the context is from an earlier day.
    func arcs(now: Date, calendar: Calendar = .current) -> [Arc] {
        guard isSameDay(as: now, calendar: calendar) else { return [] }
        let startOfDay = calendar.startOfDay(for: now)
        return stays.compactMap { stay in
            let start = max(stay.arrivedAt, startOfDay)
            let end = min(stay.departedAt ?? now, now)
            guard end > start else { return nil }
            return Arc(
                startFraction: Self.fraction(of: start, startOfDay: startOfDay),
                endFraction: Self.fraction(of: end, startOfDay: startOfDay),
                inkIndex: stay.inkIndex
            )
        }
    }

    /// Today's distance, or zero when the context is from an earlier day.
    func distance(on date: Date) -> Double {
        isSameDay(as: date) ? distanceMeters : 0
    }

    /// The ongoing stay, only while the context is from today.
    func currentStay(on date: Date) -> (name: String, since: Date)? {
        guard isSameDay(as: date),
              let name = currentStayName,
              let since = currentStaySince,
              since <= date else { return nil }
        return (name, since)
    }

    static func fraction(of date: Date, startOfDay: Date) -> Double {
        min(max(date.timeIntervalSince(startOfDay) / 86_400, 0), 1)
    }

    func save() {
        guard let defaults = UserDefaults(suiteName: SharedLocationData.appGroupIdentifier),
              let encoded = try? JSONEncoder().encode(self) else { return }
        defaults.set(encoded, forKey: Self.userDefaultsKey)
    }

    static func load() -> WatchTodayContext? {
        guard let defaults = UserDefaults(suiteName: SharedLocationData.appGroupIdentifier),
              let data = defaults.data(forKey: userDefaultsKey) else { return nil }
        return try? JSONDecoder().decode(WatchTodayContext.self, from: data)
    }
}

/// "30 min", "1h 20m": how long a stay has lasted, for compact watch labels.
/// A clock time that follows the phone's 12/24-hour setting ("3:04 PM" or "15:04"),
/// written in the user's digits (Arabic-Indic, Persian…).
enum ClockTimeFormatter {
    static func string(from date: Date, locale: Locale = .autoupdatingCurrent, timeZone: TimeZone = .current) -> String {
        var calendar = Calendar.current
        calendar.locale = locale
        calendar.timeZone = timeZone
        return date.formatted(
            Date.FormatStyle(date: .omitted, time: .shortened, locale: locale, calendar: calendar, timeZone: timeZone)
        )
    }
}

enum StayDurationFormatter {
    /// "1h 20m", "12h 59m", "2h" in the user's language and digits; nil under an hour.
    static func hoursAndMinutes(_ seconds: TimeInterval, locale: Locale = .current) -> String {
        let minutes = max(0, Int(seconds) / 60)
        return Duration.seconds(minutes * 60)
            .formatted(.units(allowed: [.hours, .minutes], width: .narrow).locale(locale))
    }

    static func compact(_ seconds: TimeInterval) -> String {
        let minutes = max(0, Int(seconds) / 60)
        if minutes < 60 {
            return String(localized: "\(minutes) min")
        }
        let hours = minutes / 60
        let remainder = minutes % 60
        if remainder == 0 {
            return String(localized: "\(hours)h")
        }
        return String(localized: "\(hours)h \(remainder)m")
    }
}
