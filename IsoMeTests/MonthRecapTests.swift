import XCTest
import CoreLocation
@testable import IsoMe

@MainActor
final class MonthRecapTests: XCTestCase {
    private var calendar: Calendar {
        var value = Calendar(identifier: .gregorian)
        value.timeZone = TimeZone(identifier: "America/New_York")!
        return value
    }

    private func date(_ day: Int, month: Int = 11, hour: Int = 0, minute: Int = 0) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: month, day: day, hour: hour, minute: minute))!
    }

    private func visit(_ start: Date, _ end: Date?, name: String = "Home") -> Visit {
        Visit(latitude: 40, longitude: -74, arrivedAt: start, departedAt: end, customName: name)
    }

    private func point(_ time: Date, longitude: Double = -74, outlier: Bool = false) -> LocationPoint {
        LocationPoint(latitude: 40, longitude: longitude, timestamp: time, horizontalAccuracy: 5, isOutlier: outlier)
    }

    func testMultiDayVisitCountsEachOccupiedDayAcrossDaylightSaving() {
        let recap = MonthRecap(visits: [visit(date(31, month: 10, hour: 23), date(3, hour: 12))],
                               month: date(1), now: date(7), calendar: calendar)
        XCTAssertEqual(recap.recordedDays, 3)
        XCTAssertEqual(Array(recap.visitsPerDay.prefix(4)), [1, 1, 1, 0])
        XCTAssertEqual(recap.visitCount, 1)
        XCTAssertEqual(recap.topPlaces.first?.duration ?? 0, 61 * 3600, accuracy: 0.001)
    }

    func testMidnightDepartureDoesNotOccupyFollowingDay() {
        let recap = MonthRecap(visits: [visit(date(4, hour: 23), date(6))],
                               month: date(1), now: date(7), calendar: calendar)
        XCTAssertEqual(recap.recordedDays, 2)
        XCTAssertEqual(Array(recap.visitsPerDay[3...5]), [1, 1, 0])
    }

    func testOngoingVisitStopsAtNowAndRepeatedPlacesCountOncePerDay() {
        let recap = MonthRecap(visits: [visit(date(4), nil), visit(date(5, hour: 3), date(5, hour: 4)),
                                       visit(date(5, hour: 6), date(5, hour: 7), name: "Cafe")],
                               month: date(1), now: date(6, hour: 12), calendar: calendar)
        XCTAssertEqual(recap.recordedDays, 3)
        XCTAssertEqual(Array(recap.visitsPerDay[3...6]), [1, 2, 1, 0])
        XCTAssertEqual(recap.placeCount, 2)
    }

    func testNextMonthMidnightAndFutureRecordsDoNotLeakIntoRecap() {
        let recap = MonthRecap(visits: [visit(date(1, month: 12), date(2, month: 12), name: "December"),
                                       visit(date(20), date(21), name: "Future")],
                               points: [point(date(1, month: 12)), point(date(20))],
                               month: date(1), now: date(7), calendar: calendar)
        XCTAssertEqual(recap.recordedDays, 0)
        XCTAssertEqual(recap.visitCount, 0)
        XCTAssertEqual(recap.newPlaces, 0)
        XCTAssertEqual(recap.placeCount, 0)
        XCTAssertNil(recap.biggestDay)
    }

    func testMonthClipsVisitAtBothEnds() {
        let recap = MonthRecap(visits: [visit(date(31, month: 10), date(2, month: 12))],
                               month: date(1), now: date(3, month: 12), calendar: calendar)
        XCTAssertEqual(recap.recordedDays, 30)
        XCTAssertEqual(recap.visitsPerDay, Array(repeating: 1, count: 30))
        XCTAssertEqual(recap.topPlaces.first?.duration ?? 0, 721 * 3600, accuracy: 0.001)
        XCTAssertEqual(recap.newPlaces, 0)
    }

    func testUnsortedRoutePointsKeepChronologicalDistanceAndIgnoreOutliers() {
        let a = point(date(3, hour: 9))
        let b = point(date(3, hour: 10), longitude: -73.99)
        let c = point(date(3, hour: 11), longitude: -73.98)
        let recap = MonthRecap(visits: [], points: [b, c, a, point(date(4), outlier: true)],
                               month: date(1), now: date(7), calendar: calendar)
        XCTAssertEqual(recap.totalDistance, a.distance(to: b) + b.distance(to: c), accuracy: 0.001)
        XCTAssertEqual(recap.recordedDays, 1)
        XCTAssertEqual(recap.biggestDay, date(3))
    }
}
