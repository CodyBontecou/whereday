import XCTest
import MapKit
@testable import IsoMe

final class ClockTextTests: XCTestCase {
    private func date(hour: Int, minute: Int) -> Date {
        Calendar.current.date(from: DateComponents(year: 2026, month: 11, day: 3, hour: hour, minute: minute))!
    }

    private let utc = TimeZone(secondsFromGMT: 0)!

    private func utcDate(hour: Int, minute: Int) -> Date {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = utc
        return calendar.date(from: DateComponents(year: 2026, month: 11, day: 3, hour: hour, minute: minute))!
    }

    private func normalized(_ text: String) -> String {
        // Formatters may use narrow no-break spaces before AM/PM.
        text.replacingOccurrences(of: "\u{202F}", with: " ").replacingOccurrences(of: "\u{00A0}", with: " ")
    }

    /// Clock labels follow the phone's 12/24-hour setting rather than forcing 24-hour time.
    func testClockTextFollowsTheHourCycle() {
        let twelveHour = Locale(identifier: "en_US")
        let twentyFourHour = Locale(identifier: "en_US@hours=h23")
        XCTAssertEqual(normalized(ClockTimeFormatter.string(from: utcDate(hour: 15, minute: 4), locale: twelveHour, timeZone: utc)), "3:04 PM")
        XCTAssertEqual(normalized(ClockTimeFormatter.string(from: utcDate(hour: 0, minute: 0), locale: twelveHour, timeZone: utc)), "12:00 AM")
        XCTAssertEqual(ClockTimeFormatter.string(from: utcDate(hour: 15, minute: 4), locale: twentyFourHour, timeZone: utc), "15:04")
        XCTAssertEqual(ClockTimeFormatter.string(from: utcDate(hour: 15, minute: 4), locale: Locale(identifier: "en_GB"), timeZone: utc), "15:04")
        XCTAssertEqual(LocationMapView.clockText(date(hour: 15, minute: 4)), ClockTimeFormatter.string(from: date(hour: 15, minute: 4)))
    }

    /// Times are written in the user's own digits rather than always Latin.
    func testClockTextUsesTheLocaleDigits() {
        let arabic = ClockTimeFormatter.string(from: utcDate(hour: 11, minute: 39), locale: Locale(identifier: "ar_EG"), timeZone: utc)
        XCTAssertTrue(arabic.contains("١١:٣٩"), arabic)
        XCTAssertFalse(arabic.contains("11"), arabic)
        XCTAssertEqual(ClockTimeFormatter.string(from: utcDate(hour: 11, minute: 39), locale: Locale(identifier: "fa_IR"), timeZone: utc), "۱۱:۳۹")
    }

    func testHourDurationsAreLocalized() {
        let english = Locale(identifier: "en_US")
        XCTAssertEqual(StayDurationFormatter.hoursAndMinutes(80 * 60, locale: english), "1h 20m")
        XCTAssertEqual(StayDurationFormatter.hoursAndMinutes(779 * 60, locale: english), "12h 59m")
        XCTAssertEqual(StayDurationFormatter.hoursAndMinutes(120 * 60, locale: english), "2h")

        let arabic = StayDurationFormatter.hoursAndMinutes(779 * 60, locale: Locale(identifier: "ar_EG"))
        XCTAssertEqual(arabic, "١٢س ٥٩د")
        XCTAssertFalse(arabic.contains("h"))
        XCTAssertEqual(StayDurationFormatter.hoursAndMinutes(80 * 60, locale: Locale(identifier: "ru_RU")), "1 ч 20 мин")
    }

    @MainActor
    func testMoveAndStayDurationsUseTheLocalizedFormatterFromAnHour() {
        let expected = StayDurationFormatter.hoursAndMinutes(80 * 60)
        XCTAssertEqual(TodayDay.formatMoveDuration(80 * 60), expected)
        XCTAssertEqual(TodayDay.formatDuration(80 * 60), expected)
    }
}

final class MapPinLabelRoomTests: XCTestCase {
    private let center = CLLocationCoordinate2D(latitude: 37.7776, longitude: -122.4070)

    private func room(viewport: CGSize = CGSize(width: 400, height: 800)) -> MapPinLabelRoom {
        let point = MKMapPoint(center)
        // A 4,000-point-wide rectangle centred on the pin: 10 map points per screen point.
        let rect = MKMapRect(x: point.x - 2_000, y: point.y - 4_000, width: 4_000, height: 8_000)
        return MapPinLabelRoom(visibleRect: rect, viewportSize: viewport, clearRightInset: 80)
    }

    func testProjectsTheCoordinateFromTheSettledCamera() throws {
        XCTAssertEqual(try XCTUnwrap(room().localX(for: center)), 200, accuracy: 0.001)
    }

    func testLabelStaysRightWhenItFitsAndMovesLeftOrTruncatesOtherwise() {
        let layout = room()
        // Right side: 400 - 80 - 200 - 20 = 100; left side: 200 - 28 = 172.
        XCTAssertTrue(layout.placement(for: center, labelWidth: 90) == (false, nil))
        XCTAssertTrue(layout.placement(for: center, labelWidth: 150) == (true, nil))
        XCTAssertTrue(layout.placement(for: center, labelWidth: 300) == (true, 172))
    }

    func testNoPlacementBeforeTheCameraHasSettled() {
        let unsettled = MapPinLabelRoom(visibleRect: nil, viewportSize: CGSize(width: 400, height: 800), clearRightInset: 80)
        XCTAssertNil(unsettled.localX(for: center))
        XCTAssertTrue(unsettled.placement(for: center, labelWidth: 500) == (false, nil))
    }
}
