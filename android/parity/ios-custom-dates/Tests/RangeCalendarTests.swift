import XCTest

/// Drives the iOS 2.0 whole-day range calendar. Absolute cases use past days and assert exact native
/// bounds; relative cases follow the clock and assert the preset or clamped shape around the tap.
final class RangeCalendarTests: XCTestCase {
    enum Step { case day(String), daysFromToday(Int), dayOfThisMonth(Int), chip(String), month(Int), reset }
    enum Expected { case exact(String), preset(String), clampedFromDaysAgo(Int) }

    private var calendar = Calendar(identifier: .gregorian)

    private func parse(_ text: String) -> Date {
        let format = ISO8601DateFormatter(); format.formatOptions = [.withInternetDateTime, .withFractionalSeconds]
        return format.date(from: text)!
    }

    private func label(_ date: Date) -> String {
        let format = DateFormatter(); format.locale = Locale(identifier: "en_US"); format.timeZone = calendar.timeZone
        format.dateStyle = .full; format.timeStyle = .none
        return format.string(from: date)
    }

    @MainActor private func run(_ name: String, zone: String = "UTC", first: String? = nil, last: String? = nil,
                                steps: [Step], expected: Expected, title expectedTitle: String) {
        calendar.timeZone = TimeZone(identifier: zone)!
        let app = XCUIApplication()
        app.launchEnvironment["TZ"] = zone
        app.launchArguments = ["-AppleLocale", "en_US", "-AppleLanguages", "(en)"]
        if let first, let last { app.launchArguments += ["-first", first, "-last", last] }
        app.launch()
        XCTAssertTrue(app.staticTexts["Show days"].waitForExistence(timeout: 10))
        let today = calendar.startOfDay(for: Date())
        var shown = calendar.dateInterval(of: .month, for: today)!.start
        if let last { shown = calendar.dateInterval(of: .month, for: min(parse(last), Date()))!.start }
        func tapDay(_ date: Date) {
            let month = calendar.dateInterval(of: .month, for: date)!.start
            while shown != month {
                let delta = month < shown ? -1 : 1
                app.buttons[delta < 0 ? "Previous month" : "Next month"].tap()
                shown = calendar.date(byAdding: .month, value: delta, to: shown)!
            }
            let button = app.buttons[label(date)]
            XCTAssertTrue(button.waitForExistence(timeout: 5), label(date)); button.tap()
        }
        for step in steps {
            switch step {
            case .day(let day):
                let parts = day.split(separator: "-").map { Int($0)! }
                tapDay(calendar.date(from: DateComponents(year: parts[0], month: parts[1], day: parts[2]))!)
            case .daysFromToday(let offset): tapDay(calendar.date(byAdding: .day, value: offset, to: today)!)
            case .dayOfThisMonth(let day):
                tapDay(calendar.date(byAdding: .day, value: day - 1, to: calendar.dateInterval(of: .month, for: today)!.start)!)
            case .chip(let chip):
                app.buttons[chip].tap(); shown = calendar.dateInterval(of: .month, for: today)!.start
            case .month(let delta):
                app.buttons[delta < 0 ? "Previous month" : "Next month"].tap()
                shown = calendar.date(byAdding: .month, value: delta, to: shown)!
            case .reset:
                app.buttons["Reset"].tap(); shown = calendar.dateInterval(of: .month, for: today)!.start
            }
        }
        let shownTitle = app.buttons.matching(NSPredicate(format: "label BEGINSWITH 'Show '")).firstMatch
        XCTAssertTrue(shownTitle.exists)
        let title = shownTitle.label
        let before = Date()
        app.buttons["Apply"].tap()
        XCTAssertTrue(app.staticTexts["Show days"].waitForNonExistence(timeout: 5))
        let after = Date()
        let result = app.staticTexts["result"].label
        print("CALENDAR_RESULT=\(name)|\(result)|\(title)")
        XCTAssertEqual(title, expectedTitle)
        let parts = result.split(separator: "|").map(String.init)
        XCTAssertEqual(parts[0], "1")
        let lower = Date(timeIntervalSinceReferenceDate: Double(parts[1])!)
        let upper = Date(timeIntervalSinceReferenceDate: Double(parts[2])!)
        switch expected {
        case .exact(let bounds):
            XCTAssertEqual("\(parts[1])|\(parts[2])", bounds)
        case .preset(let preset):
            let start = calendar.startOfDay(for: upper)
            switch preset {
            case "Yesterday":
                XCTAssertEqual(lower, calendar.date(byAdding: .day, value: -1, to: today))
                XCTAssertEqual(upper.timeIntervalSinceReferenceDate, today.timeIntervalSinceReferenceDate.nextDown)
            default:
                XCTAssertTrue(upper >= before.addingTimeInterval(-1) && upper <= after, "upper follows the clock")
                switch preset {
                case "Today": XCTAssertEqual(lower, start)
                case "This month": XCTAssertEqual(lower, calendar.dateInterval(of: .month, for: upper)!.start)
                case "Last 7 days": XCTAssertEqual(lower, calendar.date(byAdding: .day, value: -6, to: start))
                case "Last 30 days": XCTAssertEqual(lower, calendar.date(byAdding: .day, value: -29, to: start))
                case "All time": XCTAssertEqual(lower, .distantPast)
                default: XCTFail(preset)
                }
            }
        case .clampedFromDaysAgo(let days):
            XCTAssertEqual(lower, calendar.date(byAdding: .day, value: -days, to: today))
            XCTAssertTrue(upper >= before.addingTimeInterval(-1) && upper <= after, "end of today is clamped to now")
        }
        let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = "Applied bounds"; attachment.lifetime = .keepAlways; add(attachment)
    }

    private let first = "2026-10-10T18:12:34.125Z", last = "2026-10-12T20:34:56.875Z"
    private var dayOfMonth: Int { Calendar(identifier: .gregorian).component(.day, from: Date()) }
    private func days(_ count: Int) -> String { count == 1 ? "Show 1 day" : "Show \(count) days" }

    // MARK: Absolute whole-day ranges

    @MainActor func testUnchangedRangeSnapsToWholeDays() {
        run("unchanged", first: first, last: last, steps: [], expected: .exact("813283200.0|813542399.9999999"), title: days(3))
    }
    @MainActor func testSingleTapSelectsOneWholeDay() {
        run("single day", first: first, last: last, steps: [.day("2026-10-11")], expected: .exact("813369600.0|813455999.9999999"), title: days(1))
    }
    @MainActor func testSecondTapCompletesTheRange() {
        run("forward range", first: first, last: last, steps: [.day("2026-10-11"), .day("2026-10-13")],
            expected: .exact("813369600.0|813628799.9999999"), title: days(3))
    }
    @MainActor func testRangeOrderFollowsTheDaysNotTheTaps() {
        run("reverse range", first: first, last: last, steps: [.day("2026-10-13"), .day("2026-10-11")],
            expected: .exact("813369600.0|813628799.9999999"), title: days(3))
    }
    @MainActor func testThirdTapStartsANewRange() {
        run("third tap", first: first, last: last, steps: [.day("2026-10-11"), .day("2026-10-13"), .day("2026-10-05")],
            expected: .exact("812851200.0|812937599.9999999"), title: days(1))
    }
    @MainActor func testRangeAcrossMonths() {
        run("across months", first: first, last: last, steps: [.month(-1), .day("2026-09-28"), .month(1), .day("2026-10-03")],
            expected: .exact("812246400.0|812764799.9999999"), title: days(6))
    }

    // MARK: Day boundaries away from UTC

    @MainActor func testSpringForwardDay() {
        run("new york spring", zone: "America/New_York", first: "2026-03-01T07:30:34.125Z", last: "2026-03-10T19:20:56.875Z",
            steps: [.day("2026-03-08")], expected: .exact("794638800.0|794721599.9999999"), title: days(1))
    }
    @MainActor func testFallBackDay() {
        run("new york fall", zone: "America/New_York", first: "2026-10-25T05:30:34.125Z", last: "2026-11-02T19:20:56.875Z",
            steps: [.day("2026-11-01")], expected: .exact("815198400.0|815288399.9999999"), title: days(1))
    }
    @MainActor func testHalfHourSpringForwardDay() {
        run("lord howe spring", zone: "Australia/Lord_Howe", first: "2026-09-26T15:45:34.125Z", last: "2026-10-05T12:20:56.875Z",
            steps: [.day("2026-10-04")], expected: .exact("812727000.0|812811599.9999999"), title: days(1))
    }
    @MainActor func testDayWithoutMidnight() {
        run("santiago midnight gap", zone: "America/Santiago", first: "2026-09-01T12:00:00.000Z", last: "2026-09-10T12:00:00.000Z",
            steps: [.day("2026-09-06")], expected: .exact("810360000.0|810446399.9999999"), title: days(1))
    }

    // MARK: Presets and days that follow the clock

    @MainActor func testMonthChip() {
        run("month chip", steps: [.chip("This month")], expected: .preset("This month"), title: days(dayOfMonth))
    }
    @MainActor func testSevenDayChip() {
        run("seven day chip", steps: [.chip("Last 7 days")], expected: .preset("Last 7 days"), title: days(7))
    }
    @MainActor func testYesterdayChip() {
        run("yesterday chip", first: first, last: last, steps: [.chip("Yesterday")], expected: .preset("Yesterday"), title: days(1))
    }
    @MainActor func testAllTimeChip() {
        run("all time chip", steps: [.chip("All time")], expected: .preset("All time"), title: days(1))
    }
    @MainActor func testResetSelectsToday() {
        run("reset", first: first, last: last, steps: [.reset], expected: .preset("Today"), title: days(1))
    }
    @MainActor func testTappingTodaySelectsTheTodayPreset() {
        run("tap today", first: first, last: last, steps: [.daysFromToday(0)], expected: .preset("Today"), title: days(1))
    }
    @MainActor func testTappingTheMonthSpanSelectsTheMonthPreset() {
        run("tap month span", steps: [.dayOfThisMonth(1), .daysFromToday(0)], expected: .preset(dayOfMonth == 1 ? "Today" : "This month"), title: days(dayOfMonth))
    }
    @MainActor func testSpanEndingTodayIsClampedToNow() {
        run("span ending today", steps: [.daysFromToday(-1), .daysFromToday(0)], expected: .clampedFromDaysAgo(1), title: days(2))
    }
}
