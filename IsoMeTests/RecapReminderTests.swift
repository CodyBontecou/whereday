import SwiftData
import XCTest
@testable import IsoMe

@MainActor
final class RecapReminderTests: XCTestCase {
    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/Los_Angeles")!
        calendar.locale = Locale(identifier: "en_US")
        return calendar
    }
    private let locale = Locale(identifier: "en_US")

    private var english: Bundle {
        Bundle(url: Bundle.main.bundleURL.appendingPathComponent("en.lproj")) ?? .main
    }

    private func date(_ year: Int, _ month: Int, _ day: Int, _ hour: Int = 12, _ minute: Int = 0) -> Date {
        calendar.date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute))!
    }

    private func next(_ frequency: RecapFrequency, after now: Date, hour: Int = 21, minute: Int = 0) -> Date? {
        RecapSchedule.nextFire(after: now, frequency: frequency, hour: hour, minute: minute, calendar: calendar)
    }

    /// Spaces instead of the narrow no-break space some formatters put before AM/PM.
    private func plain(_ text: String) -> String {
        text.replacingOccurrences(of: "\u{202F}", with: " ").replacingOccurrences(of: "\u{00A0}", with: " ")
    }

    // MARK: - Next fire date

    func testOffNeverFires() {
        XCTAssertNil(next(.off, after: date(2026, 11, 23, 9)))
    }

    func testDailyFiresTonightUntilTheTimeThenTomorrow() {
        // Monday, November 23, 2026.
        XCTAssertEqual(next(.daily, after: date(2026, 11, 23, 14, 5)), date(2026, 11, 23, 21))
        XCTAssertEqual(next(.daily, after: date(2026, 11, 23, 20, 59)), date(2026, 11, 23, 21))
        XCTAssertEqual(next(.daily, after: date(2026, 11, 23, 21, 0)), date(2026, 11, 24, 21))
        XCTAssertEqual(next(.daily, after: date(2026, 11, 23, 23, 50)), date(2026, 11, 24, 21))
    }

    func testDailyAcrossMidnightAndMonthEnd() {
        XCTAssertEqual(next(.daily, after: date(2026, 11, 30, 22)), date(2026, 12, 1, 21))
        XCTAssertEqual(next(.daily, after: date(2026, 12, 1, 0, 10)), date(2026, 12, 1, 21))
        XCTAssertEqual(next(.daily, after: date(2026, 12, 31, 21, 30)), date(2027, 1, 1, 21))
    }

    func testPresetAndCustomTimes() {
        let now = date(2026, 11, 23, 12)
        XCTAssertEqual(next(.daily, after: now, hour: 20), date(2026, 11, 23, 20))
        XCTAssertEqual(next(.daily, after: now, hour: 22), date(2026, 11, 23, 22))
        XCTAssertEqual(next(.daily, after: now, hour: 22, minute: 30), date(2026, 11, 23, 22, 30))
        XCTAssertEqual(next(.daily, after: date(2026, 11, 23, 22, 31), hour: 22, minute: 30), date(2026, 11, 24, 22, 30))
        // A custom time just after midnight belongs to the next day.
        XCTAssertEqual(next(.daily, after: date(2026, 11, 23, 23)), date(2026, 11, 24, 21))
        XCTAssertEqual(next(.daily, after: date(2026, 11, 23, 23), hour: 0, minute: 15), date(2026, 11, 24, 0, 15))
    }

    func testWeekdaysSkipTheWeekend() {
        // Friday the 27th after 9 PM, Saturday and Sunday all move to Monday the 30th.
        XCTAssertEqual(next(.weekdays, after: date(2026, 11, 27, 12)), date(2026, 11, 27, 21))
        XCTAssertEqual(next(.weekdays, after: date(2026, 11, 27, 21, 30)), date(2026, 11, 30, 21))
        XCTAssertEqual(next(.weekdays, after: date(2026, 11, 28, 12)), date(2026, 11, 30, 21))
        XCTAssertEqual(next(.weekdays, after: date(2026, 11, 29, 22)), date(2026, 11, 30, 21))
        XCTAssertEqual(next(.weekdays, after: date(2026, 11, 30, 8)), date(2026, 11, 30, 21))
    }

    func testWeeklyFiresOnSunday() {
        // Wednesday → Sunday the 29th; Sunday before the time → that evening; after it → next Sunday.
        XCTAssertEqual(next(.weekly, after: date(2026, 11, 25, 9)), date(2026, 11, 29, 21))
        XCTAssertEqual(next(.weekly, after: date(2026, 11, 29, 20)), date(2026, 11, 29, 21))
        XCTAssertEqual(next(.weekly, after: date(2026, 11, 29, 21, 1)), date(2026, 12, 6, 21))
        XCTAssertEqual(calendar.component(.weekday, from: next(.weekly, after: date(2026, 12, 2))!), 1)
    }

    func testNineStaysLocalNineAcrossDaylightSavingChanges() {
        // US clocks fall back on Sunday, November 1, 2026 and spring forward on Sunday, March 8, 2026.
        let saturday = date(2026, 10, 31, 21)
        let fallBack = next(.daily, after: date(2026, 10, 31, 22))!
        XCTAssertEqual(calendar.component(.hour, from: fallBack), 21)
        XCTAssertEqual(calendar.component(.day, from: fallBack), 1)
        XCTAssertEqual(fallBack.timeIntervalSince(saturday), 25 * 3_600)
        let weekly = next(.weekly, after: date(2026, 10, 30))!
        XCTAssertEqual(weekly, fallBack)

        let springForward = next(.daily, after: date(2026, 3, 7, 22))!
        XCTAssertEqual(calendar.component(.hour, from: springForward), 21)
        XCTAssertEqual(springForward.timeIntervalSince(date(2026, 3, 7, 21)), 23 * 3_600)
    }

    func testATimeThatDoesNotExistMovesToTheNextOne() {
        // 2:30 AM doesn't exist on March 8, 2026 in Los Angeles.
        let fire = next(.daily, after: date(2026, 3, 7, 23), hour: 2, minute: 30)!
        XCTAssertEqual(calendar.component(.day, from: fire), 8)
        XCTAssertEqual(calendar.component(.hour, from: fire), 3)
    }

    func testPeriodCoversTheDayOrMondayToSunday() {
        let fire = date(2026, 11, 29, 21)
        XCTAssertEqual(RecapSchedule.period(endingAt: fire, kind: .day, calendar: calendar),
                       DateInterval(start: date(2026, 11, 29, 0), end: fire))
        XCTAssertEqual(RecapSchedule.period(endingAt: fire, kind: .week, calendar: calendar),
                       DateInterval(start: date(2026, 11, 23, 0), end: fire))
    }

    // MARK: - Settings, toggle and migration

    func testOnboardingToggleMapsToEveryEveningOrOff() {
        XCTAssertEqual(RecapFrequency.onboardingToggle(true), .daily)
        XCTAssertEqual(RecapFrequency.onboardingToggle(false), .off)
        XCTAssertEqual(RecapFrequency.daily.periodKind, .day)
        XCTAssertEqual(RecapFrequency.weekdays.periodKind, .day)
        XCTAssertEqual(RecapFrequency.weekly.periodKind, .week)
    }

    func testSettingsDefaultsAndRoundTrip() {
        let defaults = makeIsolatedDefaults()
        let fresh = RecapReminderSettings.load(defaults: defaults)
        XCTAssertEqual(fresh.frequency, .off)
        XCTAssertEqual(fresh.hour, 21)
        XCTAssertEqual(fresh.minute, 0)
        XCTAssertEqual(fresh.timePreset, .nine)
        XCTAssertTrue(fresh.skipQuietDays)
        XCTAssertFalse(fresh.showPlaceNames)

        var changed = fresh
        changed.frequency = .weekly
        changed.select(.custom)
        changed.hour = 22
        changed.minute = 45
        changed.skipQuietDays = false
        changed.showPlaceNames = true
        changed.save(defaults: defaults)
        XCTAssertEqual(RecapReminderSettings.load(defaults: defaults), changed)
        XCTAssertEqual(defaults.string(forKey: "recapReminder.frequency"), "weekly")
    }

    func testTimeChipsAndCustom() {
        var settings = RecapReminderSettings()
        settings.select(.eight)
        XCTAssertEqual(settings.hour, 20)
        XCTAssertEqual(settings.timePreset, .eight)
        settings.select(.custom)
        // Custom stays chosen even at a whole hour, and keeps the time until the picker changes it.
        XCTAssertEqual(settings.timePreset, .custom)
        XCTAssertEqual(settings.hour, 20)
        settings.select(.ten)
        XCTAssertEqual(settings.timePreset, .ten)
        settings.minute = 30
        XCTAssertEqual(settings.timePreset, .custom)
    }

    func testLegacyOneShotBecomesEveryEvening() {
        XCTAssertEqual(RecapReminderMigration.migratedFrequency(stored: nil, legacyRequestPending: true, legacyToggleOn: false), .daily)
        XCTAssertEqual(RecapReminderMigration.migratedFrequency(stored: nil, legacyRequestPending: false, legacyToggleOn: true), .daily)
        XCTAssertNil(RecapReminderMigration.migratedFrequency(stored: nil, legacyRequestPending: false, legacyToggleOn: false))
        // A frequency chosen already wins over the old one-shot.
        XCTAssertNil(RecapReminderMigration.migratedFrequency(stored: .weekly, legacyRequestPending: true, legacyToggleOn: true))
        XCTAssertNil(RecapReminderMigration.migratedFrequency(stored: .off, legacyRequestPending: true, legacyToggleOn: false))
        XCTAssertEqual(RecapReminderMigration.legacyNotificationIdentifier, "onboarding.page-one")
    }

    func testMigrationRecordsItsRunWithoutALegacyRequest() async {
        let defaults = makeIsolatedDefaults()
        let scheduler = RecapReminderScheduler(defaults: defaults)
        // The test host has no pending one-shot, so nothing changes, but the run is recorded.
        await scheduler.migrateLegacyNightlyIfNeeded()
        XCTAssertTrue(defaults.bool(forKey: "recapReminder.legacyNightlyMigrated"))
        XCTAssertNil(defaults.string(forKey: "recapReminder.frequency"))
    }

    func testTrialRowOnlyDuringARenewingFreeTrial() {
        let now = date(2026, 11, 23)
        let end = date(2026, 11, 30)
        let id = IsoMePurchaseOption.individualYearly.productID
        XCTAssertEqual(StoreManager.activeTrialEnd(productID: id, introductory: [id], expirations: [id: end],
                                                   willAutoRenew: true, now: now), end)
        XCTAssertNil(StoreManager.activeTrialEnd(productID: id, introductory: [], expirations: [id: end],
                                                 willAutoRenew: true, now: now))
        XCTAssertNil(StoreManager.activeTrialEnd(productID: id, introductory: [id], expirations: [id: end],
                                                 willAutoRenew: false, now: now))
        XCTAssertNil(StoreManager.activeTrialEnd(productID: nil, introductory: [id], expirations: [id: end],
                                                 willAutoRenew: true, now: now))
    }

    // MARK: - Aggregation (the same places as Today)

    private let home = (37.3349, -122.0090)
    private let cafe = (37.3400, -122.0090)   // ~570 m north
    private let office = (37.3500, -122.0090) // ~1.7 km north
    private let gym = (37.3349, -121.9990)    // ~880 m east

    private var savedHome: SavedPlace { SavedPlace(name: "Home", latitude: home.0, longitude: home.1, radiusMeters: 150, kind: .home) }

    private func model(_ name: String?, _ place: (Double, Double), _ day: Int, _ hour: Int, minutes: Int = 60,
                       custom: String? = nil, open: Bool = false) -> Visit {
        let arrived = date(2026, 11, day, hour)
        return Visit(latitude: place.0, longitude: place.1, arrivedAt: arrived,
                     departedAt: open ? nil : arrived.addingTimeInterval(TimeInterval(minutes * 60)),
                     customName: custom, locationName: name, geocodingCompleted: true)
    }

    /// Points every 10 minutes from `from` to `to`, walking in a straight line between two places.
    private func track(_ day: Int, from: (hour: Int, minute: Int), to: (hour: Int, minute: Int),
                       _ a: (Double, Double), _ b: (Double, Double)) -> [LocationPoint] {
        let start = date(2026, 11, day, from.hour, from.minute)
        let end = date(2026, 11, day, to.hour, to.minute)
        let steps = max(1, Int(end.timeIntervalSince(start) / 600))
        return (0...steps).map { step in
            let f = Double(step) / Double(steps)
            return LocationPoint(latitude: a.0 + (b.0 - a.0) * f, longitude: a.1 + (b.1 - a.1) * f,
                                 timestamp: start.addingTimeInterval(Double(step) * 600), horizontalAccuracy: 5)
        }
    }

    /// The recap's summary for the models, through the same conversion `RecapDataReader` uses.
    private func recap(_ kind: RecapPeriodKind = .day, visits: [Visit], earlier: [Visit] = [], points: [LocationPoint],
                       home: SavedPlace?, period: DateInterval, now: Date) -> RecapSummary {
        RecapAggregation.summary(
            kind: kind,
            visits: visits.enumerated().map { DayStayVisit($1, index: $0) },
            earlierVisits: earlier.enumerated().map { DayStayVisit($1, index: $0) },
            points: points.map { RecapPoint(latitude: $0.latitude, longitude: $0.longitude, timestamp: $0.timestamp, isOutlier: $0.isOutlier) },
            home: home.map(DayStayHome.init), countryCount: nil, trackingWasOn: true,
            period: period, now: now, calendar: calendar)
    }

    /// Today's day model for the same data (`TodayDay(viewModel:now:)` with these visits and points).
    private func today(visits: [Visit], points: [LocationPoint], home: SavedPlace?, now: Date) -> TodayDay {
        let startOfDay = calendar.startOfDay(for: now)
        let shown = visits.filter { $0.arrivedAt <= now && ($0.departedAt ?? now) >= startOfDay }.sorted { $0.arrivedAt < $1.arrivedAt }
        return TodayDay(visits: shown, points: points.filter { !$0.isOutlier && $0.timestamp >= startOfDay }, startOfDay: startOfDay,
                        now: now, home: home, isCurrentVisit: { $0.departedAt == nil })
    }

    /// The seeded-day case from #170: the night at Home shows only as Home's stretch (no Home visit), so Today
    /// counts Home and the recap must too.
    private func dayWithHomeStretch() -> (visits: [Visit], points: [LocationPoint], now: Date) {
        let visits = [
            model("Corner café", cafe, 23, 9),
            model("Office", office, 23, 11, minutes: 300),
            model(nil, gym, 23, 17),                          // not geocoded yet: "Unknown Location"
            model("812 Elm St", (home.0 + 0.0003, home.1), 23, 19, open: true) // inside Home: reads "Home"
        ]
        let points = track(23, from: (0, 0), to: (8, 40), home, home)
            + track(23, from: (8, 40), to: (9, 0), home, cafe)
            + track(23, from: (10, 0), to: (11, 0), cafe, office)
            + track(23, from: (16, 0), to: (17, 0), office, gym)
            + track(23, from: (18, 0), to: (19, 0), gym, home)
        return (visits, points, date(2026, 11, 23, 20, 30))
    }

    func testDayRecapCountsAndNamesPlacesLikeToday() {
        let day = dayWithHomeStretch()
        let todayDay = today(visits: day.visits, points: day.points, home: savedHome, now: day.now)
        let summary = recap(visits: day.visits, points: day.points, home: savedHome,
                            period: RecapSchedule.period(endingAt: date(2026, 11, 23, 21), kind: .day, calendar: calendar), now: day.now)

        // Today: Home (the stretch from midnight, and the evening visit inside Home), Corner café, Office, Unknown Location.
        XCTAssertEqual(todayDay.placeNames, ["Home", "Corner café", "Office", "Unknown Location"])
        XCTAssertEqual(todayDay.placeCount, 4)
        XCTAssertEqual(summary.placeCount, todayDay.placeCount)
        // Same names in Today's order; the ungeocoded stop counts but isn't read out.
        XCTAssertEqual(summary.places.map(\.name), ["Home", "Corner café", "Office", nil])
        XCTAssertEqual(summary.recordedVisitCount, 4)
        XCTAssertEqual(content(summary, names: true).body.components(separatedBy: ". ").first, "Home, Corner café, Office, and 1 more")
        XCTAssertTrue(content(summary).body.hasPrefix("4 places and "))
    }

    func testDayRecapAgreesWithTodayWithoutASavedHome() {
        let day = dayWithHomeStretch()
        let todayDay = today(visits: day.visits, points: day.points, home: nil, now: day.now)
        let summary = recap(visits: day.visits, points: day.points, home: nil,
                            period: RecapSchedule.period(endingAt: date(2026, 11, 23, 21), kind: .day, calendar: calendar), now: day.now)
        XCTAssertEqual(todayDay.placeNames, ["Corner café", "Office", "Unknown Location", "812 Elm St"])
        XCTAssertEqual(summary.places.map(\.name), ["Corner café", "Office", nil, "812 Elm St"])
        XCTAssertEqual(summary.placeCount, todayDay.placeCount)
    }

    func testRecapDistanceIsTodaysMoved() {
        let day = dayWithHomeStretch()
        var points = day.points
        // An imported GPS glitch: Today and the map drop outliers, and so does the recap.
        points.append(LocationPoint(latitude: 38.0, longitude: -122.0, timestamp: date(2026, 11, 23, 12, 5),
                                    horizontalAccuracy: 5, isOutlier: true))
        let summary = recap(visits: day.visits, points: points, home: savedHome,
                            period: RecapSchedule.period(endingAt: date(2026, 11, 23, 21), kind: .day, calendar: calendar), now: day.now)
        let moved = TodayDay.pathDistance(points.filter { !$0.isOutlier }.sorted { $0.timestamp < $1.timestamp })
        XCTAssertGreaterThan(moved, 3_000)
        XCTAssertEqual(summary.distanceMeters, moved, accuracy: 0.01)
    }

    func testDistanceSkipsGlitches() {
        let points = [
            RecapPoint(latitude: home.0, longitude: home.1, timestamp: date(2026, 11, 23, 8)),
            RecapPoint(latitude: 38.0, longitude: -122.0, timestamp: date(2026, 11, 23, 8, 1), isOutlier: true),
            RecapPoint(latitude: cafe.0, longitude: cafe.1, timestamp: date(2026, 11, 23, 8, 10))
        ]
        XCTAssertEqual(RecapAggregation.distance(along: points, calendar: calendar), 567, accuracy: 10)
        XCTAssertEqual(RecapAggregation.distance(along: [], calendar: calendar), 0)
        // Like the map's 7D totals, the overnight gap between two days isn't travel.
        let overnight = [
            RecapPoint(latitude: home.0, longitude: home.1, timestamp: date(2026, 11, 23, 23, 50)),
            RecapPoint(latitude: office.0, longitude: office.1, timestamp: date(2026, 11, 24, 8))
        ]
        XCTAssertEqual(RecapAggregation.distance(along: overnight, calendar: calendar), 0)
    }

    func testNewPlacesFollowEarlierVisits() {
        let day = dayWithHomeStretch()
        let earlier = [model("Office", office, 20, 9), model("Somewhere", gym, 21, 9)]
        let summary = recap(visits: day.visits, earlier: earlier, points: day.points, home: savedHome,
                            period: RecapSchedule.period(endingAt: date(2026, 11, 23, 21), kind: .day, calendar: calendar), now: day.now)
        // Home's stretch is never new; the evening visit inside Home has no earlier visit within 150 m, so Home is.
        XCTAssertEqual(summary.places.map(\.isNew), [true, true, false, false])
        XCTAssertEqual(summary.newPlaceCount, 2)
    }

    func testWeeklyRecapCountsDistinctPlacesAcrossTodaysDays() {
        let visits = [
            model("Corner café", cafe, 23, 9),
            model("Office", office, 24, 10), model("Office", office, 25, 10), model("Office", office, 26, 10),
            model("Gym", gym, 26, 18)
        ]
        // Each morning begins at Home, so every day shows Home's stretch.
        let points = [23, 24, 25, 26].flatMap { track($0, from: (0, 0), to: (8, 30), home, home) }
        let now = date(2026, 11, 26, 20)
        let summary = recap(.week, visits: visits, points: points, home: savedHome,
                            period: RecapSchedule.period(endingAt: date(2026, 11, 29, 21), kind: .week, calendar: calendar), now: now)

        // Each day as Today (or the map's day sheet for a past day) shows it.
        var names: [String] = []
        for dayNumber in [23, 24, 25, 26] {
            let start = date(2026, 11, dayNumber, 0)
            let midnight = calendar.date(byAdding: .day, value: 1, to: start)!
            let dayNow = dayNumber == 26 ? now : midnight
            let shown = visits.filter { $0.arrivedAt <= dayNow && ($0.departedAt ?? dayNow) >= start }
            let dayPoints = points.filter { $0.timestamp >= start && $0.timestamp < midnight }
            let end = dayNumber == 26 ? now : DayStayPlanner.pastDayEnd(visits: shown.map { DayStayVisit($0) },
                                                                         points: dayPoints.map(DayStayPoint.init), endOfDay: midnight)
            names += TodayDay(visits: shown, points: dayPoints, startOfDay: start, now: end, home: savedHome,
                              isCurrentVisit: { _ in false }).placeNames
        }
        XCTAssertEqual(Set(names), ["Home", "Corner café", "Office", "Gym"])
        XCTAssertEqual(summary.placeCount, Set(names).count)
        // Most visited first: Home on 4 mornings, Office 3 times.
        XCTAssertEqual(summary.places.map(\.name), ["Home", "Office", "Corner café", "Gym"])
        XCTAssertEqual(summary.places.first?.visitCount, 4)
        // A day recap never reports countries.
        let day = RecapAggregation.summary(kind: .day, visits: [], earlierVisits: [], points: [], home: nil, countryCount: 2,
                                           trackingWasOn: true, period: DateInterval(start: date(2026, 11, 26, 0), end: now),
                                           now: now, calendar: calendar)
        XCTAssertNil(day.countryCount)
    }

    func testANightAtHomeAloneIsStillNothingRecorded() {
        // Points only at Home: Today shows Home's stretch (1 place), but no visit was recorded and nothing moved.
        let points = track(23, from: (0, 0), to: (20, 0), home, home)
        let now = date(2026, 11, 23, 20, 30)
        let todayDay = today(visits: [], points: points, home: savedHome, now: now)
        let summary = recap(visits: [], points: points, home: savedHome,
                            period: RecapSchedule.period(endingAt: date(2026, 11, 23, 21), kind: .day, calendar: calendar), now: now)
        XCTAssertEqual(todayDay.placeCount, 1)
        XCTAssertEqual(summary.placeCount, 1)
        XCTAssertEqual(summary.recordedVisitCount, 0)
        XCTAssertEqual(reason(summary), .nothingRecorded)
        // And a day at Home with a recorded Home visit is quiet, not empty.
        let atHome = recap(visits: [model("812 Elm St", home, 23, 8, minutes: 600)], earlier: [model("812 Elm St", home, 20, 8)],
                           points: points, home: savedHome,
                           period: RecapSchedule.period(endingAt: date(2026, 11, 23, 21), kind: .day, calendar: calendar), now: now)
        XCTAssertEqual(atHome.places.map(\.name), ["Home"])
        XCTAssertEqual(reason(atHome), .quietDay)
    }

    func testReaderMatchesTodayFromTheStore() async throws {
        let schema = Schema([Visit.self, LocationPoint.self, RecordingSession.self, PhotoMoment.self, SavedPlace.self, CountryStay.self])
        let container = try ModelContainer(for: schema, configurations: [ModelConfiguration(schema: schema, isStoredInMemoryOnly: true)])
        let context = ModelContext(container)
        let day = dayWithHomeStretch()
        context.insert(savedHome)
        context.insert(SavedPlace(name: "Aardvark Office", latitude: office.0, longitude: office.1, kind: .work))
        for visit in day.visits { context.insert(visit) }
        for point in day.points { context.insert(point) }
        try context.save()

        let period = RecapSchedule.period(endingAt: date(2026, 11, 23, 21), kind: .day, calendar: calendar)
        let reader = RecapDataReader(modelContainer: container)
        let stored = await reader.summary(kind: .day, period: period, now: day.now, trackingEnabledNow: true, calendar: calendar)
        let summary = try XCTUnwrap(stored)
        let todayDay = today(visits: day.visits, points: day.points, home: savedHome, now: day.now)
        XCTAssertEqual(summary.placeCount, todayDay.placeCount)
        XCTAssertEqual(summary.places.map(\.name), ["Home", "Corner café", "Office", nil])
        XCTAssertEqual(summary.distanceMeters, TodayDay.pathDistance(day.points.sorted { $0.timestamp < $1.timestamp }), accuracy: 0.01)
    }

    // MARK: - Content

    private func summary(_ kind: RecapPeriodKind = .day, names: [String?], meters: Double, countries: Int? = nil) -> RecapSummary {
        RecapSummary(kind: kind,
                     places: names.map { RecapPlace(name: $0, isNew: false, visitCount: 1, duration: 600, firstArrival: Date()) },
                     distanceMeters: meters, countryCount: countries, trackingWasOn: true, recordedVisitCount: names.count)
    }

    private func content(_ summary: RecapSummary, names: Bool = false, metric: Bool = true) -> RecapContent {
        let content = RecapContentBuilder.content(for: summary, showPlaceNames: names, usesMetric: metric,
                                                  locale: locale, bundle: english)
        return RecapContent(title: content.title, body: plain(content.body))
    }

    func testDailyCountsAndPlurals() {
        XCTAssertEqual(content(summary(names: ["Home", "Corner café", "Office", "Riverside park"], meters: 5_600)),
                       RecapContent(title: "Your day", body: "4 places and 5.6 km. Tap to see it on the map."))
        XCTAssertEqual(content(summary(names: ["Home"], meters: 1_200)).body, "1 place and 1.2 km. Tap to see it on the map.")
        XCTAssertEqual(content(summary(names: ["Home", "Office"], meters: 40)).body, "2 places. Tap to see it on the map.")
        XCTAssertEqual(content(summary(names: [], meters: 3_000)).body, "3 km moved. Tap to see it on the map.")
    }

    func testDailyFollowsTheUnitsSetting() {
        XCTAssertEqual(content(summary(names: ["Home", "Office"], meters: 5_600), metric: false).body,
                       "2 places and 3.5 mi. Tap to see it on the map.")
        XCTAssertEqual(content(summary(names: ["Home"], meters: 650)).body, "1 place and 650 m. Tap to see it on the map.")
    }

    func testPlaceNamesAreOptInAndCappedAtThree() {
        let three = summary(names: ["Home", "Corner café", "Riverside park"], meters: 5_600)
        XCTAssertEqual(content(three, names: true).body, "Home, Corner café, and Riverside park. 5.6 km.")
        XCTAssertFalse(content(three).body.contains("Home"), "Names stay off the Lock Screen by default")

        let five = summary(names: ["Home", "Corner café", nil, "Office", "Gym"], meters: 5_600)
        XCTAssertEqual(content(five, names: true).body, "Home, Corner café, Office, and 2 more. 5.6 km.")
        XCTAssertEqual(content(summary(names: ["Home", "Office"], meters: 10), names: true).body, "Home and Office.")
        // With no names at all, the counts are shown instead.
        XCTAssertEqual(content(summary(names: [nil, nil], meters: 2_000), names: true).body,
                       "2 places and 2 km. Tap to see it on the map.")
    }

    func testWeeklyWithAndWithoutCountries() {
        let places: [String?] = ["Home", "Office", "Corner café"] + Array(repeating: nil, count: 8)
        XCTAssertEqual(content(summary(.week, names: places, meters: 1_520_000, countries: 2)),
                       RecapContent(title: "Your week", body: "11 places, 1,520 km and 2 countries. Tap to see the week."))
        // One country, or unknown: the clause is dropped.
        XCTAssertEqual(content(summary(.week, names: places, meters: 42_000, countries: 1)).body,
                       "11 places and 42 km. Tap to see the week.")
        XCTAssertEqual(content(summary(.week, names: places, meters: 42_000, countries: nil)).body,
                       "11 places and 42 km. Tap to see the week.")
        XCTAssertEqual(content(summary(.week, names: places, meters: 1_520_000, countries: 2), names: true).body,
                       "Home, Office, Corner café, and 8 more. 1,520 km across 2 countries.")
        XCTAssertEqual(content(summary(.week, names: ["Home"], meters: 42_000, countries: 1), names: true).body,
                       "Home. 42 km.")
    }

    func testExampleSummariesMatchTheDesignCounts() {
        XCTAssertEqual(content(RecapContentBuilder.exampleSummary(kind: .day, bundle: english)).body,
                       "4 places and 5.6 km. Tap to see it on the map.")
        XCTAssertEqual(content(RecapContentBuilder.exampleSummary(kind: .week, bundle: english)).body,
                       "11 places, 1,520 km and 2 countries. Tap to see the week.")
    }

    func testRecapTimeLabels() {
        XCTAssertEqual(plain(RecapTimeFormatter.string(hour: 21, minute: 0, locale: locale, calendar: calendar)), "9 PM")
        XCTAssertEqual(plain(RecapTimeFormatter.string(hour: 22, minute: 30, locale: locale, calendar: calendar)), "10:30 PM")
    }

    // MARK: - Skip rules

    private var fireDate: Date { date(2026, 11, 23, 21) }

    private func reason(_ summary: RecapSummary, canTrack: Bool = true, skipQuiet: Bool = true,
                        opened: [Date] = [], exemptDay: Date? = nil) -> RecapSkipReason? {
        RecapSkipRules.reason(summary: summary, canTrack: canTrack, skipQuietDays: skipQuiet, fire: fireDate,
                              foregroundDates: opened, exemptDay: exemptDay, calendar: calendar)
    }

    private var busyDay: RecapSummary {
        var day = summary(names: ["Home", "Office"], meters: 5_600)
        day.places[1].isNew = true
        return day
    }

    func testABusyDayIsSent() {
        XCTAssertNil(reason(busyDay))
    }

    func testNoAccessSkips() {
        XCTAssertEqual(reason(busyDay, canTrack: false), .noAccess)
    }

    func testTrackingOffAllPeriodSkips() {
        var day = busyDay
        day.trackingWasOn = false
        XCTAssertEqual(reason(day), .trackingOff)
    }

    func testNothingRecordedSkips() {
        XCTAssertEqual(reason(summary(names: [], meters: 0)), .nothingRecorded)
        XCTAssertEqual(reason(summary(names: [], meters: 99)), .nothingRecorded)
        // Movement alone is something.
        XCTAssertNotEqual(reason(summary(names: [], meters: 2_500)), .nothingRecorded)
    }

    func testOpeningTheAppAfterSixSkipsThatEvening() {
        XCTAssertEqual(reason(busyDay, opened: [date(2026, 11, 23, 18, 0)]), .openedThisEvening)
        XCTAssertEqual(reason(busyDay, opened: [date(2026, 11, 23, 20, 59)]), .openedThisEvening)
        // Before 6 PM, after the fire, or another evening: still sent.
        XCTAssertNil(reason(busyDay, opened: [date(2026, 11, 23, 17, 59)]))
        XCTAssertNil(reason(busyDay, opened: [date(2026, 11, 23, 21, 0)]))
        XCTAssertNil(reason(busyDay, opened: [date(2026, 11, 22, 19)]))
        // The evening onboarding turned it on keeps its "starting tonight" promise.
        XCTAssertNil(reason(busyDay, opened: [date(2026, 11, 23, 19)], exemptDay: date(2026, 11, 23, 12)))
        XCTAssertEqual(reason(busyDay, opened: [date(2026, 11, 23, 19)], exemptDay: date(2026, 11, 22, 12)), .openedThisEvening)
    }

    func testEveningWindowIsEmptyForAnEarlyRecap() {
        XCTAssertNil(RecapSkipRules.eveningWindow(endingAt: date(2026, 11, 23, 17, 30), calendar: calendar))
        XCTAssertEqual(RecapSkipRules.eveningWindow(endingAt: fireDate, calendar: calendar),
                       DateInterval(start: date(2026, 11, 23, 18), end: fireDate))
    }

    func testQuietDayDefinition() {
        // Quiet: no place first seen in the period and under 1 km moved.
        XCTAssertTrue(RecapSkipRules.isQuiet(summary(names: ["Home"], meters: 999)))
        XCTAssertFalse(RecapSkipRules.isQuiet(summary(names: ["Home"], meters: 1_000)))
        var newPlace = summary(names: ["Home", "Bakery"], meters: 200)
        newPlace.places[1].isNew = true
        XCTAssertFalse(RecapSkipRules.isQuiet(newPlace))
    }

    func testQuietDaysSkipOnlyWhenTheSettingIsOn() {
        let quiet = summary(names: ["Home"], meters: 400)
        XCTAssertEqual(reason(quiet), .quietDay)
        XCTAssertNil(reason(quiet, skipQuiet: false))
        // A commute (no new place, but over 1 km) is not quiet.
        XCTAssertNil(reason(summary(names: ["Home", "Office"], meters: 12_000)))
    }

    func testRulesApplyInOrder() {
        var empty = summary(names: [], meters: 0)
        empty.trackingWasOn = false
        XCTAssertEqual(reason(empty, canTrack: false, opened: [date(2026, 11, 23, 19)]), .noAccess)
        XCTAssertEqual(reason(empty, opened: [date(2026, 11, 23, 19)]), .trackingOff)
        let quiet = summary(names: ["Home"], meters: 400)
        XCTAssertEqual(reason(quiet, opened: [date(2026, 11, 23, 19)]), .openedThisEvening)
    }
}
