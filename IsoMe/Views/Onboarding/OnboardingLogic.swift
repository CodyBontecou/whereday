import Foundation

// Pure first-run ("Page One") logic, kept out of the views so it can be unit tested.

/// What the person wants Whereday to remember, picked on the second onboarding screen.
enum OnboardingIntent: String, CaseIterable, Identifiable {
    case days, countries, routes, notes

    var id: String { rawValue }
}

/// Persists the intent picks for a later "first week" checklist.
enum OnboardingIntentStore {
    static let key = "onboardingIntents"
    static let defaultIntents: Set<OnboardingIntent> = [.days]

    /// The saved picks, or the default ("Remember my days") when nothing was saved yet.
    /// An empty saved value means every pick was cleared on purpose.
    static func load(defaults: UserDefaults = .standard) -> Set<OnboardingIntent> {
        guard let stored = defaults.string(forKey: key) else { return defaultIntents }
        return Set(stored.split(separator: ",").compactMap { OnboardingIntent(rawValue: String($0)) })
    }

    static func save(_ intents: Set<OnboardingIntent>, defaults: UserDefaults = .standard) {
        defaults.set(serialized(intents), forKey: key)
    }

    /// Comma-separated keys in a stable order: `days,countries,routes,notes`.
    static func serialized(_ intents: Set<OnboardingIntent>) -> String {
        OnboardingIntent.allCases.filter(intents.contains).map(\.rawValue).joined(separator: ",")
    }
}

/// The four sample files shown on the export screen.
enum OnboardingExportSample: String, CaseIterable, Identifiable {
    case markdown, gpx, csv, json

    var id: String { rawValue }

    /// Notes first, then routes, then countries; Markdown otherwise.
    static func preferred(for intents: Set<OnboardingIntent>) -> OnboardingExportSample {
        if intents.contains(.notes) { return .markdown }
        if intents.contains(.routes) { return .gpx }
        if intents.contains(.countries) { return .csv }
        return .markdown
    }

    var label: String {
        switch self {
        case .markdown: "Markdown"
        case .gpx: "GPX"
        case .csv: "CSV"
        case .json: "JSON"
        }
    }

    var fileName: String {
        switch self {
        case .markdown: "Daily Track - 2026-11-23.md"
        case .gpx: "whereday-2026-11-23.gpx"
        case .csv: "visits-2026-11.csv"
        case .json: "whereday-backup.json"
        }
    }

    /// A synthetic sample day; never the person's own data.
    var body: String {
        switch self {
        case .markdown:
            "# Mon, Nov 23\n\n- 08:50 Corner café · 55 min\n- 10:00 Office · 7 h 30 min\n- 18:00 Riverside park · 1 h 15 min\n\n5.6 km moved"
        case .gpx:
            "<trk><name>Mon, Nov 23</name><trkseg>\n  <trkpt lat=\"…\" lon=\"…\">\n    <time>2026-11-23T08:31:04Z</time>\n  </trkpt>\n  …\n</trkseg></trk>"
        case .csv:
            "date,arrival,departure,place\n2026-11-23,08:50,09:45,Corner café\n2026-11-23,10:00,17:30,Office\n2026-11-23,18:00,19:15,Riverside park"
        case .json:
            "{\n  \"visits\": [\n    { \"place\": \"Corner café\",\n      \"arrival\": \"2026-11-23T08:50\" }\n  ]\n}"
        }
    }
}

extension ExportPreferencesStore {
    /// Seeds Markdown as the export format for people who send their days to notes,
    /// without overwriting export preferences they already saved.
    /// Returns whether anything was written.
    @discardableResult
    static func seedFromOnboarding(intents: Set<OnboardingIntent>, defaults: UserDefaults = .standard) -> Bool {
        guard intents.contains(.notes), !hasSavedPreferences(defaults: defaults) else { return false }
        var options = ExportOptions()
        options.format = .markdown
        save(options: options, selectedFormats: [.markdown], defaults: defaults)
        return true
    }
}

/// When the one "tonight's page" notification fires.
enum NightlyPageSchedule {
    static let hour = 21
    /// After this time today, the notification moves to tomorrow night.
    static let cutoffHour = 20
    static let cutoffMinute = 30
    static let notificationIdentifier = "onboarding.page-one"

    struct Fire: Equatable {
        let date: Date
        let isTomorrow: Bool
    }

    static func next(after now: Date, calendar: Calendar = .current) -> Fire {
        let today = calendar.startOfDay(for: now)
        let cutoff = calendar.date(bySettingHour: cutoffHour, minute: cutoffMinute, second: 0, of: today) ?? now
        let isTomorrow = now >= cutoff
        let day = isTomorrow ? (calendar.date(byAdding: .day, value: 1, to: today) ?? today) : today
        let fire = calendar.date(bySettingHour: hour, minute: 0, second: 0, of: day)
            ?? day.addingTimeInterval(TimeInterval(hour * 3600))
        return Fire(date: fire, isTomorrow: isTomorrow)
    }
}

/// Days with geotagged photos over the last 12 weeks, for the backfill heatmap.
enum PhotoDayHistogram {
    static let dayCount = 84

    /// Photo counts per local day, oldest first; the last element is `today`.
    static func counts(for dates: [Date], today: Date, calendar: Calendar = .current, dayCount: Int = dayCount) -> [Int] {
        var counts = Array(repeating: 0, count: dayCount)
        let lastDay = calendar.startOfDay(for: today)
        for date in dates {
            let day = calendar.startOfDay(for: date)
            guard let offset = calendar.dateComponents([.day], from: day, to: lastDay).day,
                  offset >= 0, offset < dayCount else { continue }
            counts[dayCount - 1 - offset] += 1
        }
        return counts
    }

    /// The first day the heatmap covers.
    static func startDate(today: Date, calendar: Calendar = .current, dayCount: Int = dayCount) -> Date {
        calendar.date(byAdding: .day, value: -(dayCount - 1), to: calendar.startOfDay(for: today))
            ?? calendar.startOfDay(for: today)
    }

    /// 0 = none, 1 = one photo, 2 = a few, 3 = many.
    static func level(for count: Int) -> Int {
        switch count {
        case ..<1: 0
        case 1: 1
        case 2...4: 2
        default: 3
        }
    }

    static func daysFound(in counts: [Int]) -> Int {
        counts.filter { $0 > 0 }.count
    }
}

/// The synthetic day used on the "Drag through a day" screen.
struct OnboardingSampleStop: Equatable {
    enum Kind { case place(ink: Int), travel }

    let start: Double
    let end: Double
    let name: LocalizedStringResource
    let detail: LocalizedStringResource
    let kind: Kind

    static func == (lhs: Self, rhs: Self) -> Bool { lhs.start == rhs.start && lhs.end == rhs.end }

    static let day: [OnboardingSampleStop] = [
        .init(start: 0, end: 8.5, name: "Home", detail: "Asleep, then coffee at the counter", kind: .place(ink: 0)),
        .init(start: 8.5, end: 8.83, name: "Walking", detail: "Travel · 0.9 km", kind: .travel),
        .init(start: 8.83, end: 9.75, name: "Corner café", detail: "Visit · 55 min · 2 photos", kind: .place(ink: 1)),
        .init(start: 9.75, end: 10, name: "Walking", detail: "Travel · 0.4 km", kind: .travel),
        .init(start: 10, end: 17.5, name: "Office", detail: "Visit · 7 h 30 min", kind: .place(ink: 2)),
        .init(start: 17.5, end: 18, name: "Cycling", detail: "Travel · 3.2 km", kind: .travel),
        .init(start: 18, end: 19.25, name: "Riverside park", detail: "Visit · 1 h 15 min · 5 photos", kind: .place(ink: 3)),
        .init(start: 19.25, end: 19.67, name: "Walking", detail: "Travel · 1.1 km", kind: .travel),
        .init(start: 19.67, end: 24, name: "Home", detail: "Back for the night", kind: .place(ink: 0))
    ]

    static func stop(at hour: Double) -> OnboardingSampleStop {
        day.first { hour >= $0.start && hour < $0.end } ?? day[0]
    }
}
