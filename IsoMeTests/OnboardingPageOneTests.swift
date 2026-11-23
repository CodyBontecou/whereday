import XCTest
@testable import IsoMe

@MainActor
final class OnboardingPageOneTests: XCTestCase {
    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/Los_Angeles")!
        return calendar
    }

    private func date(_ year: Int, _ month: Int, _ day: Int, _ hour: Int = 12, _ minute: Int = 0) -> Date {
        calendar.date(from: DateComponents(year: year, month: month, day: day, hour: hour, minute: minute))!
    }

    // MARK: Nightly notification time

    func testNightlyFiresAtNineTonightBeforeTheCutoff() {
        let fire = NightlyPageSchedule.next(after: date(2026, 11, 23, 14, 5), calendar: calendar)
        XCTAssertEqual(fire.date, date(2026, 11, 23, 21, 0))
        XCTAssertFalse(fire.isTomorrow)
    }

    func testNightlyJustBeforeCutoffStaysTonight() {
        let fire = NightlyPageSchedule.next(after: date(2026, 11, 23, 20, 29), calendar: calendar)
        XCTAssertEqual(fire.date, date(2026, 11, 23, 21, 0))
        XCTAssertFalse(fire.isTomorrow)
    }

    func testNightlyMovesToTomorrowFromHalfPastEight() {
        let atCutoff = NightlyPageSchedule.next(after: date(2026, 11, 23, 20, 30), calendar: calendar)
        XCTAssertEqual(atCutoff.date, date(2026, 11, 24, 21, 0))
        XCTAssertTrue(atCutoff.isTomorrow)

        let lateNight = NightlyPageSchedule.next(after: date(2026, 11, 23, 23, 50), calendar: calendar)
        XCTAssertEqual(lateNight.date, date(2026, 11, 24, 21, 0))
        XCTAssertTrue(lateNight.isTomorrow)
    }

    func testNightlyAcrossMonthEndAndEarlyMorning() {
        XCTAssertEqual(NightlyPageSchedule.next(after: date(2026, 11, 30, 22, 0), calendar: calendar).date,
                       date(2026, 12, 1, 21, 0))
        XCTAssertEqual(NightlyPageSchedule.next(after: date(2026, 12, 1, 0, 10), calendar: calendar).date,
                       date(2026, 12, 1, 21, 0))
    }

    func testNightlyKeepsLocalNineAcrossDaylightSavingChange() {
        // US clocks fall back on 2026-11-01; the next fire is still 9 PM local time.
        let fire = NightlyPageSchedule.next(after: date(2026, 10, 31, 22, 0), calendar: calendar)
        XCTAssertEqual(calendar.component(.hour, from: fire.date), 21)
        XCTAssertEqual(calendar.component(.day, from: fire.date), 1)
    }

    // MARK: Intents

    func testIntentsDefaultToRememberMyDays() {
        let defaults = makeIsolatedDefaults()
        XCTAssertEqual(OnboardingIntentStore.load(defaults: defaults), [.days])
    }

    func testIntentsRoundTripInStableOrder() {
        let defaults = makeIsolatedDefaults()
        OnboardingIntentStore.save([.notes, .days, .routes], defaults: defaults)
        XCTAssertEqual(defaults.string(forKey: "onboardingIntents"), "days,routes,notes")
        XCTAssertEqual(OnboardingIntentStore.load(defaults: defaults), [.days, .routes, .notes])
    }

    func testClearedIntentsStayEmptyAndUnknownKeysAreIgnored() {
        let defaults = makeIsolatedDefaults()
        OnboardingIntentStore.save([], defaults: defaults)
        XCTAssertEqual(defaults.string(forKey: "onboardingIntents"), "")
        XCTAssertEqual(OnboardingIntentStore.load(defaults: defaults), [])

        defaults.set("countries,future,notes", forKey: "onboardingIntents")
        XCTAssertEqual(OnboardingIntentStore.load(defaults: defaults), [.countries, .notes])
    }

    // MARK: Export default

    func testExportSampleFollowsIntentPriority() {
        XCTAssertEqual(OnboardingExportSample.preferred(for: []), .markdown)
        XCTAssertEqual(OnboardingExportSample.preferred(for: [.days]), .markdown)
        XCTAssertEqual(OnboardingExportSample.preferred(for: [.countries]), .csv)
        XCTAssertEqual(OnboardingExportSample.preferred(for: [.routes, .countries]), .gpx)
        XCTAssertEqual(OnboardingExportSample.preferred(for: [.notes, .routes, .countries]), .markdown)
    }

    func testNotesIntentSeedsMarkdownExportOnlyWithoutSavedPreferences() {
        let defaults = makeIsolatedDefaults()
        XCTAssertFalse(ExportPreferencesStore.seedFromOnboarding(intents: [.days], defaults: defaults))
        XCTAssertFalse(ExportPreferencesStore.hasSavedPreferences(defaults: defaults))

        XCTAssertTrue(ExportPreferencesStore.seedFromOnboarding(intents: [.notes], defaults: defaults))
        let seeded = ExportPreferencesStore.load(defaults: defaults)
        XCTAssertEqual(seeded.selectedFormats, [.markdown])
        XCTAssertEqual(seeded.options.format, .markdown)
    }

    func testNotesIntentKeepsExistingExportPreferences() {
        let defaults = makeIsolatedDefaults()
        ExportPreferencesStore.save(options: ExportOptions(), selectedFormats: [.gpx], defaults: defaults)
        XCTAssertFalse(ExportPreferencesStore.seedFromOnboarding(intents: [.notes], defaults: defaults))
        XCTAssertEqual(ExportPreferencesStore.load(defaults: defaults).selectedFormats, [.gpx])
    }

    // MARK: Photo days

    func testPhotoDaysGroupByLocalDayOldestFirst() {
        let today = date(2026, 11, 23, 10)
        let dates = [
            date(2026, 11, 23, 0, 5), date(2026, 11, 23, 23, 59), // today, twice
            date(2026, 11, 22, 23, 30),                           // yesterday, late
            date(2026, 9, 1, 8),                                  // first day of the window
            date(2026, 8, 31, 8),                                 // one day too old
            date(2026, 11, 24, 9)                                 // future: ignored
        ]
        let counts = PhotoDayHistogram.counts(for: dates, today: today, calendar: calendar)

        XCTAssertEqual(counts.count, 84)
        XCTAssertEqual(counts[83], 2)
        XCTAssertEqual(counts[82], 1)
        XCTAssertEqual(counts[0], 1)
        XCTAssertEqual(counts.reduce(0, +), 4)
        XCTAssertEqual(PhotoDayHistogram.daysFound(in: counts), 3)
        XCTAssertEqual(PhotoDayHistogram.startDate(today: today, calendar: calendar), date(2026, 9, 1, 0))
    }

    func testPhotoDayLevels() {
        XCTAssertEqual(PhotoDayHistogram.level(for: 0), 0)
        XCTAssertEqual(PhotoDayHistogram.level(for: 1), 1)
        XCTAssertEqual(PhotoDayHistogram.level(for: 2), 2)
        XCTAssertEqual(PhotoDayHistogram.level(for: 4), 2)
        XCTAssertEqual(PhotoDayHistogram.level(for: 5), 3)
        XCTAssertEqual(PhotoDayHistogram.level(for: 120), 3)
    }

    // MARK: Sample day

    func testSampleDayCoversEveryQuarterHour() {
        XCTAssertEqual(OnboardingSampleStop.day.first?.start, 0)
        XCTAssertEqual(OnboardingSampleStop.day.last?.end, 24)
        for (previous, next) in zip(OnboardingSampleStop.day, OnboardingSampleStop.day.dropFirst()) {
            XCTAssertEqual(previous.end, next.start)
        }
        XCTAssertEqual(OnboardingSampleStop.stop(at: 9.25).start, 8.83)
        XCTAssertEqual(OnboardingSampleStop.stop(at: 23.75).end, 24)
    }
}
