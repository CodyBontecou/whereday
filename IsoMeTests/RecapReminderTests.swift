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

    // MARK: - Aggregation

    private let home = (37.3349, -122.0090)
    private let cafe = (37.3400, -122.0090)   // ~570 m north
    private let office = (37.3500, -122.0090) // ~1.7 km north

    private func visit(_ name: String?, _ place: (Double, Double), _ day: Int, _ hour: Int, minutes: Int = 60) -> RecapVisit {
        let arrived = date(2026, 11, day, hour)
        return RecapVisit(name: name, latitude: place.0, longitude: place.1, arrivedAt: arrived,
                          departedAt: arrived.addingTimeInterval(TimeInterval(minutes * 60)))
    }

    func testPlacesAreDistinctAndNewOnesAreMarked() {
        let visits = [
            visit("Home", home, 23, 7),
            visit("Corner café", cafe, 23, 9),
            visit(nil, (home.0 + 0.0003, home.1), 23, 18), // ~33 m from home, unnamed: same place
            visit("Office", office, 23, 10)
        ]
        let summary = RecapAggregation.summary(kind: .day, visits: visits, earlierVisits: [visit("Home", home, 20, 8)],
                                               points: [], countryCount: nil, trackingWasOn: true,
                                               periodEnd: date(2026, 11, 23, 21))
        XCTAssertEqual(summary.places.map(\.name), ["Home", "Corner café", "Office"])
        XCTAssertEqual(summary.places.map(\.isNew), [false, true, true])
        XCTAssertEqual(summary.places[0].visitCount, 2)
        XCTAssertEqual(summary.newPlaceCount, 2)
    }

    func testDistanceSkipsGlitches() {
        let points = [
            RecapPoint(latitude: home.0, longitude: home.1, timestamp: date(2026, 11, 23, 8)),
            RecapPoint(latitude: 38.0, longitude: -122.0, timestamp: date(2026, 11, 23, 8, 1), isOutlier: true),
            RecapPoint(latitude: cafe.0, longitude: cafe.1, timestamp: date(2026, 11, 23, 8, 10))
        ]
        XCTAssertEqual(RecapAggregation.distance(along: points), 567, accuracy: 10)
        XCTAssertEqual(RecapAggregation.distance(along: []), 0)
    }

    func testWeeklyAggregationLeadsWithTheMostVisitedPlaces() {
        let visits = [
            visit("Corner café", cafe, 23, 9),
            visit("Office", office, 24, 10), visit("Office", office, 25, 10), visit("Office", office, 26, 10),
            visit("Home", home, 23, 18, minutes: 600), visit("Home", home, 24, 18, minutes: 600)
        ]
        let summary = RecapAggregation.summary(kind: .week, visits: visits, earlierVisits: [], points: [],
                                               countryCount: 2, trackingWasOn: true, periodEnd: date(2026, 11, 29, 21))
        XCTAssertEqual(summary.places.map(\.name), ["Office", "Home", "Corner café"])
        XCTAssertEqual(summary.countryCount, 2)
        // A day recap never reports countries.
        let day = RecapAggregation.summary(kind: .day, visits: visits, earlierVisits: [], points: [],
                                           countryCount: 2, trackingWasOn: true, periodEnd: date(2026, 11, 29, 21))
        XCTAssertNil(day.countryCount)
    }

    // MARK: - Content

    private func summary(_ kind: RecapPeriodKind = .day, names: [String?], meters: Double, countries: Int? = nil) -> RecapSummary {
        RecapSummary(kind: kind,
                     places: names.map { RecapPlace(name: $0, isNew: false, visitCount: 1, duration: 600, firstArrival: Date()) },
                     distanceMeters: meters, countryCount: countries, trackingWasOn: true)
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
