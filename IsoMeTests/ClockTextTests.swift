import XCTest
@testable import IsoMe

final class ClockTextTests: XCTestCase {
    private func date(hour: Int, minute: Int) -> Date {
        Calendar.current.date(from: DateComponents(year: 2026, month: 11, day: 3, hour: hour, minute: minute))!
    }

    /// The dial and replay labels sit on a 00–24 scale, so they must read 24-hour time even in 12-hour locales.
    func testClockTextUsesTwentyFourHourTime() {
        XCTAssertEqual(LocationMapView.clockText(date(hour: 15, minute: 4)), "15:04")
        XCTAssertEqual(LocationMapView.clockText(date(hour: 0, minute: 0)), "00:00")
        XCTAssertEqual(LocationMapView.clockText(date(hour: 9, minute: 30)), "09:30")
    }
}
