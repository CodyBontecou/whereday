import Foundation
import CoreLocation

// Evening recap reminders: pure logic, kept free of UserNotifications and SwiftData so it can be unit tested.
// `RecapReminderScheduler` gathers the data and owns the one pending notification request.
//
// Everything here runs on the phone. No location, count or place name leaves the device.

// MARK: - Settings

/// How often a recap is sent (Settings › Reminders › Day recap).
enum RecapFrequency: String, CaseIterable, Identifiable, Sendable {
    case off, daily, weekdays, weekly

    var id: String { rawValue }

    /// The onboarding "Evening recap" toggle: on is Every evening, off is Off.
    static func onboardingToggle(_ isOn: Bool) -> RecapFrequency { isOn ? .daily : .off }

    var isOn: Bool { self != .off }

    /// Weekly recaps cover Monday to Sunday; the others cover the day they're sent.
    var periodKind: RecapPeriodKind { self == .weekly ? .week : .day }
}

enum RecapPeriodKind: String, Equatable, Sendable {
    case day, week
}

/// The 8, 9 and 10 PM chips, or Custom with a time picker.
enum RecapTimePreset: String, CaseIterable, Identifiable {
    case eight, nine, ten, custom

    var id: String { rawValue }

    var hour: Int? {
        switch self {
        case .eight: 20
        case .nine: 21
        case .ten: 22
        case .custom: nil
        }
    }
}

struct RecapReminderSettings: Equatable {
    var frequency: RecapFrequency = .off
    var hour = RecapReminderSettings.defaultHour
    var minute = 0
    /// Custom stays selected even when the picked time happens to be 9:00.
    var usesCustomTime = false
    var skipQuietDays = true
    var showPlaceNames = false

    static let defaultHour = 21

    enum Key {
        static let frequency = "recapReminder.frequency"
        static let hour = "recapReminder.hour"
        static let minute = "recapReminder.minute"
        static let usesCustomTime = "recapReminder.customTime"
        static let skipQuietDays = "recapReminder.skipQuietDays"
        static let showPlaceNames = "recapReminder.showPlaceNames"
        /// The last moments the app was in the foreground: when it became active and when it left.
        static let lastActiveAt = "recapReminder.lastActiveAt"
        static let lastBackgroundAt = "recapReminder.lastBackgroundAt"
        /// The day onboarding turned the recap on (yyyy-MM-dd); that evening's opens don't skip it.
        static let firstEveningDay = "recapReminder.firstEveningDay"
        static let legacyMigrated = "recapReminder.legacyNightlyMigrated"
    }

    var timePreset: RecapTimePreset {
        if usesCustomTime || minute != 0 { return .custom }
        return RecapTimePreset.allCases.first { $0.hour == hour } ?? .custom
    }

    /// Picking a chip; Custom keeps the current time until the picker changes it.
    mutating func select(_ preset: RecapTimePreset) {
        if let presetHour = preset.hour {
            hour = presetHour
            minute = 0
            usesCustomTime = false
        } else {
            usesCustomTime = true
        }
    }

    static func load(defaults: UserDefaults = .standard) -> RecapReminderSettings {
        var settings = RecapReminderSettings()
        if let raw = defaults.string(forKey: Key.frequency), let frequency = RecapFrequency(rawValue: raw) {
            settings.frequency = frequency
        }
        if let hour = defaults.object(forKey: Key.hour) as? Int, (0...23).contains(hour) { settings.hour = hour }
        if let minute = defaults.object(forKey: Key.minute) as? Int, (0...59).contains(minute) { settings.minute = minute }
        settings.usesCustomTime = defaults.bool(forKey: Key.usesCustomTime)
        if defaults.object(forKey: Key.skipQuietDays) != nil { settings.skipQuietDays = defaults.bool(forKey: Key.skipQuietDays) }
        settings.showPlaceNames = defaults.bool(forKey: Key.showPlaceNames)
        return settings
    }

    func save(defaults: UserDefaults = .standard) {
        defaults.set(frequency.rawValue, forKey: Key.frequency)
        defaults.set(hour, forKey: Key.hour)
        defaults.set(minute, forKey: Key.minute)
        defaults.set(usesCustomTime, forKey: Key.usesCustomTime)
        defaults.set(skipQuietDays, forKey: Key.skipQuietDays)
        defaults.set(showPlaceNames, forKey: Key.showPlaceNames)
    }
}

/// The one-shot "Page one is written" notification from the first Page One onboarding.
enum RecapReminderMigration {
    static let legacyNotificationIdentifier = "onboarding.page-one"

    /// What to store after the old one-shot: Every evening when it was pending or its toggle was on,
    /// unless a recap frequency was already chosen. Nil means leave the settings alone.
    /// (The old toggle was never persisted, so the app passes `legacyToggleOn: false`; the pending
    /// request is the only trace of it.)
    static func migratedFrequency(stored: RecapFrequency?, legacyRequestPending: Bool,
                                  legacyToggleOn: Bool) -> RecapFrequency? {
        guard stored == nil, legacyRequestPending || legacyToggleOn else { return nil }
        return .daily
    }
}

// MARK: - When

enum RecapSchedule {
    /// The next fire date strictly after `now`. Wall-clock time in `calendar`'s time zone, so 9 PM stays
    /// 9 PM across daylight saving changes; a time that doesn't exist that day moves to the next one.
    static func nextFire(after now: Date, frequency: RecapFrequency, hour: Int, minute: Int,
                         calendar: Calendar = .autoupdatingCurrent) -> Date? {
        let time = DateComponents(hour: hour, minute: minute, second: 0)
        switch frequency {
        case .off:
            return nil
        case .daily:
            return calendar.nextDate(after: now, matching: time, matchingPolicy: .nextTime)
        case .weekly:
            var sunday = time
            sunday.weekday = 1
            return calendar.nextDate(after: now, matching: sunday, matchingPolicy: .nextTime)
        case .weekdays:
            var cursor = now
            for _ in 0..<8 {
                guard let candidate = calendar.nextDate(after: cursor, matching: time, matchingPolicy: .nextTime) else { return nil }
                let weekday = calendar.component(.weekday, from: candidate)
                if (2...6).contains(weekday) { return candidate }
                cursor = candidate
            }
            return nil
        }
    }

    static func nextFire(after now: Date, settings: RecapReminderSettings,
                         calendar: Calendar = .autoupdatingCurrent) -> Date? {
        nextFire(after: now, frequency: settings.frequency, hour: settings.hour, minute: settings.minute, calendar: calendar)
    }

    /// What a recap fired at `fire` covers: that day from midnight, or Monday to Sunday for the weekly one.
    static func period(endingAt fire: Date, kind: RecapPeriodKind, calendar: Calendar = .autoupdatingCurrent) -> DateInterval {
        let day = calendar.startOfDay(for: fire)
        let start = kind == .week ? (calendar.date(byAdding: .day, value: -6, to: day) ?? day) : day
        return DateInterval(start: start, end: max(start, fire))
    }

    /// "Tonight" when the next fire is today, otherwise "tomorrow" (onboarding copy).
    static func startsToday(fire: Date, now: Date, calendar: Calendar = .autoupdatingCurrent) -> Bool {
        calendar.isDate(fire, inSameDayAs: now)
    }
}

// MARK: - What happened

/// One route point, as the recap needs it.
struct RecapPoint: Equatable, Sendable {
    var latitude: Double
    var longitude: Double
    var timestamp: Date
    var isOutlier = false
}

/// A distinct place in the period, as Today names and counts it, with how much of the period it took.
struct RecapPlace: Equatable, Sendable {
    /// The name the place is shown with in the recap; nil when Today can only call it "Unknown Location".
    var name: String?
    /// One of its visits had no visit within `RecapAggregation.samePlaceRadius` before the period started.
    /// Home's stretch before the first stop is never new.
    var isNew: Bool
    var visitCount: Int
    var duration: TimeInterval
    var firstArrival: Date
}

struct RecapSummary: Equatable, Sendable {
    var kind: RecapPeriodKind
    /// Distinct places: in Today's order for a day, most visited first for a week.
    var places: [RecapPlace]
    var distanceMeters: Double
    /// Distinct countries from Country Days data; nil when unknown (or for a day recap).
    var countryCount: Int?
    /// Any route point, recording session or tracking during the period.
    var trackingWasOn: Bool
    /// Visits recorded in the period (Today's stays that come from a visit). Home's stretch before the first stop
    /// is a place but not a recorded visit, so it doesn't turn a day with nothing recorded into a recap.
    var recordedVisitCount: Int

    var placeCount: Int { places.count }
    var newPlaceCount: Int { places.filter(\.isNew).count }
}

enum RecapAggregation {
    /// A visit with no earlier visit closer than this was to a place never seen before.
    /// Matches the default saved-place radius.
    static let samePlaceRadius: CLLocationDistance = 150

    /// The period as Today and the map show it. Each day's places come from `DayStayPlanner`, the same planner
    /// behind Today's "N PLACES": its stays, named as Today names them (Home inside the saved Home, and Home's
    /// stretch before the first stop), counted by distinct name. The day still going on runs to `now`, like
    /// Today; earlier days of a week end with their last record, like the map's day sheet for a picked day.
    /// A week counts each name once across its days.
    ///
    /// - Parameters:
    ///   - visits: every visit overlapping the period; `index` must be unique.
    ///   - earlierVisits: visits that began before the period, for "new place".
    ///   - points: the period's route points; outliers are dropped, as Today and the map drop them.
    static func summary(kind: RecapPeriodKind, visits: [DayStayVisit], earlierVisits: [DayStayVisit],
                        points: [RecapPoint], home: DayStayHome?, countryCount: Int?, trackingWasOn: Bool,
                        period: DateInterval, now: Date, calendar: Calendar = .autoupdatingCurrent) -> RecapSummary {
        let periodEnd = min(period.end, now)
        let kept = points.filter { !$0.isOutlier }.sorted { $0.timestamp < $1.timestamp }
        let visitsByIndex = Dictionary(visits.map { ($0.index, $0) }, uniquingKeysWith: { first, _ in first })

        struct Place {
            var name: String
            var listedName: String?
            var isNew = false
            var visitIndexes = Set<Int>()
            var homeStretches = 0
            var duration: TimeInterval = 0
            var firstArrival: Date
        }
        var order: [String] = []
        var places: [String: Place] = [:]

        var dayStart = calendar.startOfDay(for: period.start)
        while dayStart < periodEnd {
            let nextMidnight = calendar.date(byAdding: .day, value: 1, to: dayStart) ?? dayStart.addingTimeInterval(86_400)
            defer { dayStart = nextMidnight }
            let dayPoints = kept
                .filter { $0.timestamp >= dayStart && $0.timestamp < nextMidnight }
                .map { DayStayPoint(timestamp: $0.timestamp, latitude: $0.latitude, longitude: $0.longitude) }
            // Today runs to now (TodayDay(viewModel:now:)); a finished day ends with its last record
            // (TodayDay(pastDay:)). The map's per-day counts use the same call.
            let stays = DayStayPlanner.dayStays(visits: visits, points: dayPoints, dayStart: dayStart,
                                                nextMidnight: nextMidnight, now: now, home: home)
            for stay in stays {
                let visit = stay.visitIndex.flatMap { visitsByIndex[$0] }
                if places[stay.name] == nil {
                    order.append(stay.name)
                    places[stay.name] = Place(name: stay.name, firstArrival: visit?.arrivedAt ?? stay.start)
                }
                var place = places[stay.name]!
                // "Unknown Location" isn't worth reading in a notification; the place still counts.
                let known = visit.map { $0.hasKnownName || stay.name != $0.displayName } ?? true
                if place.listedName == nil, known { place.listedName = stay.name }
                place.duration += stay.end.timeIntervalSince(stay.start)
                if let visit {
                    place.visitIndexes.insert(visit.index)
                    place.firstArrival = min(place.firstArrival, visit.arrivedAt)
                    if !place.isNew, isNew(visit, earlierVisits: earlierVisits) { place.isNew = true }
                } else {
                    place.homeStretches += 1
                }
                places[stay.name] = place
            }
        }

        var ordered = order.compactMap { places[$0] }.map { place in
            RecapPlace(name: place.listedName, isNew: place.isNew, visitCount: place.visitIndexes.count + place.homeStretches,
                       duration: place.duration, firstArrival: place.firstArrival)
        }
        if kind == .week {
            // Lead with the places that filled the week.
            ordered.sort {
                if $0.visitCount != $1.visitCount { return $0.visitCount > $1.visitCount }
                if $0.duration != $1.duration { return $0.duration > $1.duration }
                return $0.firstArrival < $1.firstArrival
            }
        }
        let recorded = Set(places.values.flatMap(\.visitIndexes)).count
        return RecapSummary(kind: kind, places: ordered, distanceMeters: distance(along: kept, calendar: calendar),
                            countryCount: kind == .week ? countryCount : nil, trackingWasOn: trackingWasOn,
                            recordedVisitCount: recorded)
    }

    /// Straight segments between consecutive points, summed within each day and skipping GPS glitches: the
    /// map's range totals (`MultiDayAggregator`). For one day it is Today's "MOVED".
    static func distance(along points: [RecapPoint], calendar: Calendar = .autoupdatingCurrent) -> Double {
        let kept = points.filter { !$0.isOutlier }
        guard kept.count > 1, let first = kept.map(\.timestamp).min(), let last = kept.map(\.timestamp).max() else { return 0 }
        return MultiDayAggregator.summarize(
            visits: [],
            points: kept.map { DayPathSample(timestamp: $0.timestamp, latitude: $0.latitude, longitude: $0.longitude) },
            range: first...last,
            calendar: calendar
        ).totalDistanceMeters
    }

    private static func isNew(_ visit: DayStayVisit, earlierVisits: [DayStayVisit]) -> Bool {
        !earlierVisits.contains { distance(visit.latitude, visit.longitude, $0.latitude, $0.longitude) < samePlaceRadius }
    }

    private static func distance(_ lat1: Double, _ lon1: Double, _ lat2: Double, _ lon2: Double) -> Double {
        CLLocation(latitude: lat1, longitude: lon1).distance(from: CLLocation(latitude: lat2, longitude: lon2))
    }
}

// MARK: - Whether to send

enum RecapSkipReason: String, Equatable, Sendable {
    /// The plan has lapsed (`TrackingAccessPolicy`): nothing new can be recorded.
    case noAccess
    /// No route point, recording session or tracking at any time in the period.
    case trackingOff
    /// No visits and essentially no movement.
    case nothingRecorded
    /// Whereday was in the foreground that evening (after 6 PM, before the fire time).
    case openedThisEvening
    /// Skip quiet days is on and the period was quiet.
    case quietDay
}

enum RecapSkipRules {
    /// Under this much movement with no visits, nothing was recorded.
    static let nothingRecordedMeters: Double = 100
    /// A quiet day: no place first seen in the period, and under 1 km moved.
    static let quietDistanceMeters: Double = 1_000
    /// Opening the app from this hour until the fire time skips that evening's recap.
    static let eveningStartHour = 18

    /// Quiet, deterministically: no visit to a place never seen before the period started,
    /// and less than `quietDistanceMeters` moved. A commute is not quiet; a day at home is.
    /// (A place is new when any of its visits is, so grouping visits by Today's names doesn't change this.)
    static func isQuiet(_ summary: RecapSummary) -> Bool {
        summary.newPlaceCount == 0 && summary.distanceMeters < quietDistanceMeters
    }

    /// Nothing recorded: no recorded visit and essentially no movement. Counted from recorded visits, not
    /// Today's places, so a night at Home that only Home's stretch shows still reads as nothing recorded.
    static func isEmpty(_ summary: RecapSummary) -> Bool {
        summary.recordedVisitCount == 0 && summary.distanceMeters < nothingRecordedMeters
    }

    /// The evening window: from 6 PM on the fire's day until the fire. Empty when the fire is at or before 6 PM.
    static func eveningWindow(endingAt fire: Date, calendar: Calendar = .autoupdatingCurrent) -> DateInterval? {
        guard let start = calendar.date(bySettingHour: eveningStartHour, minute: 0, second: 0, of: fire), start < fire else {
            return nil
        }
        return DateInterval(start: start, end: fire)
    }

    /// The first rule that applies, in the order they are listed in `RecapSkipReason`; nil sends the recap.
    /// - Parameters:
    ///   - foregroundDates: when the app last became active and last left the foreground.
    ///   - exemptDay: the day onboarding turned the recap on; opening the app then doesn't skip "starting tonight".
    static func reason(summary: RecapSummary, canTrack: Bool, skipQuietDays: Bool, fire: Date,
                       foregroundDates: [Date], exemptDay: Date? = nil,
                       calendar: Calendar = .autoupdatingCurrent) -> RecapSkipReason? {
        if !canTrack { return .noAccess }
        if !summary.trackingWasOn { return .trackingOff }
        if isEmpty(summary) { return .nothingRecorded }
        let isExempt = exemptDay.map { calendar.isDate($0, inSameDayAs: fire) } ?? false
        if !isExempt, let window = eveningWindow(endingAt: fire, calendar: calendar),
           foregroundDates.contains(where: { $0 >= window.start && $0 < window.end }) {
            return .openedThisEvening
        }
        if skipQuietDays && isQuiet(summary) { return .quietDay }
        return nil
    }
}

// MARK: - What it says

struct RecapContent: Equatable {
    let title: String
    let body: String
}

/// Complete localized sentences built from the counts. Distances follow the app's Units setting.
enum RecapContentBuilder {
    /// At most this many names, then "and N more".
    static let maximumNames = 3

    static func content(for summary: RecapSummary, showPlaceNames: Bool, usesMetric: Bool,
                        locale: Locale = .autoupdatingCurrent, bundle: Bundle = .main) -> RecapContent {
        let distance = summary.distanceMeters >= RecapSkipRules.nothingRecordedMeters
            ? DistanceFormatter.format(meters: summary.distanceMeters, usesMetric: usesMetric)
            : nil
        let countries = summary.countryCount.flatMap { $0 > 1 ? $0 : nil }
        let names = showPlaceNames ? namesList(summary.places, locale: locale, bundle: bundle) : nil
        switch summary.kind {
        case .day:
            return RecapContent(title: String(localized: "Your day", bundle: bundle),
                                body: dayBody(placeCount: summary.placeCount, distance: distance, names: names, bundle: bundle))
        case .week:
            return RecapContent(title: String(localized: "Your week", bundle: bundle),
                                body: weekBody(placeCount: summary.placeCount, distance: distance, countries: countries,
                                               names: names, bundle: bundle))
        }
    }

    /// "Home, Corner café and Office", or "Home, Corner café, Office and 2 more"; nil when no place has a name.
    static func namesList(_ places: [RecapPlace], locale: Locale = .autoupdatingCurrent, bundle: Bundle = .main) -> String? {
        let named = places.compactMap(\.name)
        guard !named.isEmpty else { return nil }
        var items = Array(named.prefix(maximumNames))
        let remaining = places.count - items.count
        if remaining > 0 { items.append(String(localized: "\(remaining) more", bundle: bundle)) }
        let formatter = ListFormatter()
        formatter.locale = locale
        return formatter.string(from: items) ?? items.joined(separator: ", ")
    }

    private static func places(_ count: Int, bundle: Bundle) -> String {
        String(localized: "\(count) places", bundle: bundle)
    }

    private static func dayBody(placeCount: Int, distance: String?, names: String?, bundle: Bundle) -> String {
        if let names {
            if let distance { return String(localized: "\(names). \(distance).", bundle: bundle) }
            return String(localized: "\(names).", bundle: bundle)
        }
        switch (placeCount, distance) {
        case let (0, distance?):
            return String(localized: "\(distance) moved. Tap to see it on the map.", bundle: bundle)
        case let (count, distance?):
            return String(localized: "\(places(count, bundle: bundle)) and \(distance). Tap to see it on the map.", bundle: bundle)
        case let (count, nil):
            return String(localized: "\(places(count, bundle: bundle)). Tap to see it on the map.", bundle: bundle)
        }
    }

    private static func weekBody(placeCount: Int, distance: String?, countries: Int?, names: String?, bundle: Bundle) -> String {
        if let names {
            switch (distance, countries) {
            case let (distance?, countries?):
                let countryText = String(localized: "\(countries) countries", bundle: bundle)
                return String(localized: "\(names). \(distance) across \(countryText).", bundle: bundle)
            case let (distance?, nil):
                return String(localized: "\(names). \(distance).", bundle: bundle)
            case (nil, _):
                return String(localized: "\(names).", bundle: bundle)
            }
        }
        let placesText = places(placeCount, bundle: bundle)
        switch (placeCount, distance, countries) {
        case let (0, distance?, _):
            return String(localized: "\(distance) moved. Tap to see the week.", bundle: bundle)
        case let (_, distance?, countries?):
            let countryText = String(localized: "\(countries) countries", bundle: bundle)
            return String(localized: "\(placesText), \(distance) and \(countryText). Tap to see the week.", bundle: bundle)
        case let (_, distance?, nil):
            return String(localized: "\(placesText) and \(distance). Tap to see the week.", bundle: bundle)
        case (_, nil, _):
            return String(localized: "\(placesText). Tap to see the week.", bundle: bundle)
        }
    }

    /// The example shown on Settings › Reminders before anything is recorded: the onboarding's synthetic sample day.
    static func exampleSummary(kind: RecapPeriodKind, bundle: Bundle = .main) -> RecapSummary {
        let start = Date(timeIntervalSinceReferenceDate: 0)
        let names = [String(localized: "Home", bundle: bundle), String(localized: "Corner café", bundle: bundle),
                     String(localized: "Office", bundle: bundle), String(localized: "Riverside park", bundle: bundle)]
        switch kind {
        case .day:
            return RecapSummary(kind: .day,
                                places: names.map { RecapPlace(name: $0, isNew: false, visitCount: 1, duration: 3_600, firstArrival: start) },
                                distanceMeters: 5_600, countryCount: nil, trackingWasOn: true, recordedVisitCount: names.count)
        case .week:
            let named = names.enumerated().map { index, name in
                RecapPlace(name: name, isNew: false, visitCount: 7 - index, duration: 3_600, firstArrival: start)
            }
            let others = (0..<7).map { _ in RecapPlace(name: nil, isNew: true, visitCount: 1, duration: 3_600, firstArrival: start) }
            return RecapSummary(kind: .week, places: named + others, distanceMeters: 1_520_000, countryCount: 2, trackingWasOn: true,
                                recordedVisitCount: named.count + others.count)
        }
    }
}

/// "9 PM", or "10:30 PM" when the minutes matter.
enum RecapTimeFormatter {
    static func string(hour: Int, minute: Int, locale: Locale = .autoupdatingCurrent,
                       calendar: Calendar = .autoupdatingCurrent) -> String {
        var calendar = calendar
        calendar.locale = locale
        let date = calendar.date(bySettingHour: hour, minute: minute, second: 0, of: Date(timeIntervalSinceReferenceDate: 86_400 * 2))
            ?? Date()
        if minute == 0 {
            return date.formatted(Date.FormatStyle(locale: locale, calendar: calendar, timeZone: calendar.timeZone).hour())
        }
        return ClockTimeFormatter.string(from: date, locale: locale, timeZone: calendar.timeZone)
    }
}
