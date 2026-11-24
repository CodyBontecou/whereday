import CoreLocation
import SwiftData
import XCTest
@testable import IsoMe

/// One day's distance has one source (`DayDistance`): Today's "MOVED", the map's range chips and totals, and the
/// evening recap must agree, also on a day with a long GPS jump (#170: Today 74 km, the 7D chip 135.2 km and
/// the recap 257.7 km on the same day).
@MainActor
final class DayDistanceTests: XCTestCase {
    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/Los_Angeles")!
        calendar.locale = Locale(identifier: "en_US")
        return calendar
    }

    private func date(_ day: Int, _ hour: Int, _ minute: Int = 0) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 11, day: day, hour: hour, minute: minute))!
    }

    private let mission = (37.7599, -122.4148)
    private let ferryBuilding = (37.7951, -122.3938)
    private let cupertino = (37.3349, -122.0090)
    private let glitch = (37.9000, -122.3000)

    private func leg(_ a: (Double, Double), _ b: (Double, Double)) -> Double {
        DayDistance.meters(latitude1: a.0, longitude1: a.1, latitude2: b.0, longitude2: b.1)
    }

    private func fix(_ at: Date, _ place: (Double, Double), outlier: Bool = false) -> DistanceFix {
        DistanceFix(timestamp: at, latitude: place.0, longitude: place.1, isOutlier: outlier)
    }

    /// A day across San Francisco with one long GPS jump to Cupertino and back (two fixes delivered late; the jump
    /// isn't a single out-and-back fix, so the save-time spike check never flagged them) and one flagged outlier. Given out of time order, as late
    /// fixes are stored.
    private func jumpDay(_ day: Int) -> [DistanceFix] {
        [
            fix(date(day, 8), mission),
            fix(date(day, 9), ferryBuilding),
            fix(date(day, 10), glitch, outlier: true),
            fix(date(day, 12), ferryBuilding),
            fix(date(day, 13), mission),
            fix(date(day, 11, 5), cupertino),
            fix(date(day, 11), cupertino)
        ]
    }

    /// What the day must come to: the SF legs plus the jump out and back; the flagged outlier never counts.
    private var jumpDayMeters: Double {
        leg(mission, ferryBuilding) + leg(ferryBuilding, cupertino) + leg(cupertino, ferryBuilding) + leg(ferryBuilding, mission)
    }

    private func recapPoints(_ fixes: [DistanceFix]) -> [RecapPoint] {
        fixes.map { RecapPoint(latitude: $0.latitude, longitude: $0.longitude, timestamp: $0.timestamp, isOutlier: $0.isOutlier) }
    }

    private func recap(_ kind: RecapPeriodKind, _ fixes: [DistanceFix], fire: Date) -> RecapSummary {
        RecapAggregation.summary(kind: kind, visits: [], earlierVisits: [], points: recapPoints(fixes), home: nil,
                                 countryCount: nil, trackingWasOn: true,
                                 period: RecapSchedule.period(endingAt: fire, kind: kind, calendar: calendar),
                                 now: fire, calendar: calendar)
    }

    // MARK: - The shared rules

    func testALegIsTheSameBothWaysAndCloseToCoreLocation() {
        for (a, b) in [(mission, ferryBuilding), (ferryBuilding, cupertino), (cupertino, glitch), (mission, (51.5, -0.12))] {
            XCTAssertEqual(leg(a, b), leg(b, a))
            let core = CLLocation(latitude: a.0, longitude: a.1).distance(from: CLLocation(latitude: b.0, longitude: b.1))
            XCTAssertEqual(leg(a, b), core, accuracy: core * 0.002)
        }
        XCTAssertEqual(leg(mission, mission), 0)
        // Vincenty's reference: 1° of longitude on the equator is 111,319.49 m on WGS-84.
        XCTAssertEqual(DayDistance.meters(latitude1: 0, longitude1: 0, latitude2: 0, longitude2: 1), 111_319.49, accuracy: 0.01)
    }

    func testAGPSJumpDayIsTheSameOnTodayTheChipAndTheRecap() {
        let fixes = jumpDay(23)
        let day = calendar.startOfDay(for: date(23, 12))
        let today = DayDistance.distances(fixes, calendar: calendar).meters(on: day, calendar: calendar)
        XCTAssertEqual(today, jumpDayMeters, accuracy: 0.01)
        XCTAssertGreaterThan(today, 120_000, "the jump to Cupertino and back counts, as on Today")

        // The 7D chip for that day, from the map's summary (fed the drawn points in time order, outliers removed).
        let drawn = DayDistance.ordered(fixes).map { DayPathSample(timestamp: $0.timestamp, latitude: $0.latitude, longitude: $0.longitude) }
        let range = MultiDayAggregator.summarize(visits: [], points: drawn, range: date(17, 0)...date(23, 21), calendar: calendar)
        let chip = try? XCTUnwrap(range.days.first { $0.day == day })
        XCTAssertEqual(chip?.distanceMeters ?? -1, today, accuracy: 0.01)

        let evening = recap(.day, fixes, fire: date(23, 21))
        XCTAssertEqual(evening.distanceMeters, today, accuracy: 0.01)

        // Formatted the same everywhere: one formatter, one unit setting.
        for metric in [true, false] {
            let shown = DistanceFormatter.format(meters: today, usesMetric: metric)
            XCTAssertEqual(DistanceFormatter.format(meters: chip?.distanceMeters ?? 0, usesMetric: metric), shown)
            XCTAssertEqual(DistanceFormatter.format(meters: evening.distanceMeters, usesMetric: metric), shown)
            XCTAssertTrue(RecapContentBuilder.content(for: evening, showPlaceNames: false, usesMetric: metric).body.contains(shown))
        }
    }

    func testOutliersAndOrderDoNotChangeTheDay() {
        let fixes = jumpDay(23)
        let inOrder = DayDistance.distances(fixes.sorted { $0.timestamp < $1.timestamp }, calendar: calendar)
        XCTAssertEqual(DayDistance.distances(fixes, calendar: calendar), inOrder)
        XCTAssertEqual(DayDistance.distances(fixes.filter { !$0.isOutlier }, calendar: calendar), inOrder)
        XCTAssertEqual(DayDistance.distances([DistanceFix](), calendar: calendar).totalMeters, 0)
        XCTAssertEqual(DayDistance.distances([fix(date(23, 8), mission)], calendar: calendar).days,
                       [calendar.startOfDay(for: date(23, 8)): 0])
    }

    func testADayThatCrossesMidnightStartsFreshLikeToday() {
        // Driving home across midnight: 23:40 at the Ferry Building, 00:20 in the Mission, then on to Cupertino.
        let fixes = [
            fix(date(23, 23, 10), mission),
            fix(date(23, 23, 40), ferryBuilding),
            fix(date(24, 0, 20), mission),
            fix(date(24, 1, 0), cupertino)
        ]
        let distances = DayDistance.distances(fixes, calendar: calendar)
        let monday = calendar.startOfDay(for: date(23, 12))
        let tuesday = calendar.startOfDay(for: date(24, 12))
        XCTAssertEqual(distances.meters(on: monday, calendar: calendar), leg(mission, ferryBuilding), accuracy: 0.01)
        // Tuesday's Today starts at midnight: only its own fixes.
        XCTAssertEqual(distances.meters(on: tuesday, calendar: calendar), leg(mission, cupertino), accuracy: 0.01)
        // The leg across midnight belongs to no day, and to the total of any range holding both days.
        XCTAssertEqual(distances.crossings, [tuesday: leg(ferryBuilding, mission)])
        XCTAssertEqual(distances.totalMeters,
                       leg(mission, ferryBuilding) + leg(ferryBuilding, mission) + leg(mission, cupertino), accuracy: 0.01)

        // Tuesday's recap reads Tuesday only, Today's figure.
        let tuesdayRecap = recap(.day, fixes.filter { $0.timestamp >= tuesday }, fire: date(24, 21))
        XCTAssertEqual(tuesdayRecap.distanceMeters, distances.meters(on: tuesday, calendar: calendar), accuracy: 0.01)

        // The map's chips are the days; its range total adds the crossing.
        let samples = fixes.map { DayPathSample(timestamp: $0.timestamp, latitude: $0.latitude, longitude: $0.longitude) }
        let range = MultiDayAggregator.summarize(visits: [], points: samples, range: date(23, 0)...date(24, 12), calendar: calendar)
        XCTAssertEqual(range.days.map(\.distanceMeters), [distances.meters(on: monday, calendar: calendar),
                                                          distances.meters(on: tuesday, calendar: calendar)])
        XCTAssertEqual(range.totalDistanceMeters, distances.totalMeters, accuracy: 0.01)
    }

    func testTheWeeklyRecapIsTheSevenDayTotal() {
        // Monday Nov 16 to Sunday Nov 22: a jump day on Wednesday, a late night on Friday into Saturday.
        var fixes = jumpDay(18)
        fixes += [fix(date(20, 22), mission), fix(date(20, 23, 50), ferryBuilding),
                  fix(date(21, 0, 30), mission), fix(date(21, 10), cupertino)]
        fixes += [fix(date(22, 9), cupertino), fix(date(22, 9, 30), glitch, outlier: true), fix(date(22, 10), mission)]
        let sunday = date(22, 21)

        let weekly = recap(.week, fixes, fire: sunday)
        // The 7D range on Sunday covers the same seven days.
        let sevenDays = MapDatePreset.sevenDays.range(referenceDate: sunday)
        let samples = DayDistance.ordered(fixes).map { DayPathSample(timestamp: $0.timestamp, latitude: $0.latitude, longitude: $0.longitude) }
        let map = MultiDayAggregator.summarize(visits: [], points: samples, range: sevenDays,
                                               distances: DayDistance.distances(fixes, calendar: calendar), calendar: calendar)
        XCTAssertEqual(map.days.count, 7)
        XCTAssertEqual(weekly.distanceMeters, map.totalDistanceMeters, accuracy: 0.01)

        // The week is the sum of its days (each Today's figure) plus the travel across midnight.
        let daysSum = map.days.map(\.distanceMeters).reduce(0, +)
        let saturday = calendar.startOfDay(for: date(21, 12))
        let saturdayToSunday = leg(cupertino, cupertino)
        XCTAssertEqual(map.crossingMeters, leg(ferryBuilding, mission) + leg(mission, mission) + saturdayToSunday, accuracy: 0.01)
        XCTAssertEqual(weekly.distanceMeters, daysSum + map.crossingMeters, accuracy: 0.01)
        XCTAssertEqual(map.days.first { $0.day == saturday }?.distanceMeters ?? -1, leg(mission, cupertino), accuracy: 0.01)
        for day in map.days {
            let ownDay = fixes.filter { calendar.isDate($0.timestamp, inSameDayAs: day.day) }
            let todayThatDay = recap(.day, ownDay, fire: calendar.date(byAdding: .hour, value: 21, to: day.day)!)
            XCTAssertEqual(day.distanceMeters, todayThatDay.distanceMeters, accuracy: 0.01)
        }
        XCTAssertEqual(DistanceFormatter.format(meters: weekly.distanceMeters, usesMetric: true),
                       DistanceFormatter.format(meters: map.totalDistanceMeters, usesMetric: true))
    }

    // MARK: - The app's caches

    /// Today's "MOVED", the 7D chip and the evening recap computed by the app itself from one stored day, with
    /// fixes arriving late and out of order after launch.
    func testTodayTheChipAndTheRecapAgreeInTheApp() async throws {
        let container = try makeInMemoryContainer()
        let context = container.mainContext
        let now = Date()
        let startOfToday = Calendar.current.startOfDay(for: now)
        let elapsed = now.timeIntervalSince(startOfToday)
        func at(_ fraction: Double) -> Date { startOfToday.addingTimeInterval(elapsed * fraction) }

        let stored: [(Double, (Double, Double), Bool)] = [
            (0.10, mission, false), (0.20, ferryBuilding, false), (0.30, glitch, true), (0.60, ferryBuilding, false)
        ]
        for (fraction, place, outlier) in stored {
            context.insert(LocationPoint(latitude: place.0, longitude: place.1, timestamp: at(fraction),
                                         horizontalAccuracy: 5, isOutlier: outlier))
        }
        try context.save()

        let manager = makeIsolatedLocationManager()
        manager.isTrackingEnabled = false
        manager.trackingStartTime = nil
        let model = LocationViewModel(modelContext: context, locationManager: manager)
        model.mapDateRange = MapDatePreset.sevenDays.range(referenceDate: now)
        model.loadMapLocationPoints()
        XCTAssertEqual(model.todayDistanceTraveled, leg(mission, ferryBuilding) + leg(ferryBuilding, ferryBuilding), accuracy: 0.01)

        // The jump to Cupertino arrives late with older timestamps, then the walk back home.
        let late = [
            LocationPoint(latitude: cupertino.0, longitude: cupertino.1, timestamp: at(0.40), horizontalAccuracy: 5),
            LocationPoint(latitude: cupertino.0, longitude: cupertino.1, timestamp: at(0.45), horizontalAccuracy: 5),
            LocationPoint(latitude: mission.0, longitude: mission.1, timestamp: at(0.90), horizontalAccuracy: 5)
        ]
        late.forEach(context.insert)
        try context.save()
        manager.lastSavedLocationPoints = late
        try await waitForMainQueue()

        let expected = leg(mission, ferryBuilding) + leg(ferryBuilding, cupertino) + leg(cupertino, ferryBuilding)
            + leg(ferryBuilding, mission)
        let today = model.todayDistanceTraveled
        XCTAssertEqual(today, expected, accuracy: 0.01)

        let chip = model.mapDayDistances.meters(on: now)
        XCTAssertEqual(chip, today, accuracy: 0.01)
        let range = MultiDayAggregator.summarize(
            visits: [], points: [], range: model.mapDateRange, distances: model.mapDayDistances)
        XCTAssertEqual(range.days.last?.distanceMeters ?? -1, today, accuracy: 0.01)

        let reader = RecapDataReader(modelContainer: container)
        let fire = now.addingTimeInterval(60)
        let summary = await reader.summary(kind: .day, period: RecapSchedule.period(endingAt: fire, kind: .day, calendar: .current),
                                           now: now, trackingEnabledNow: false, calendar: .current)
        XCTAssertEqual(try XCTUnwrap(summary).distanceMeters, today, accuracy: 0.01)

        XCTAssertEqual(DistanceFormatter.format(meters: chip, usesMetric: true), DistanceFormatter.format(meters: today, usesMetric: true))

        // A reload (relaunch) gives the same figure.
        model.loadTodayLocationPoints()
        XCTAssertEqual(model.todayDistanceTraveled, today, accuracy: 0.01)
    }

    /// LocationManager flags a spike after it was saved, once the next fix shows it came straight back.
    func testAFixFlaggedAfterItCountedIsTakenBackOut() async throws {
        let container = try makeInMemoryContainer()
        let context = container.mainContext
        let now = Date()
        let startOfToday = Calendar.current.startOfDay(for: now)
        let elapsed = now.timeIntervalSince(startOfToday)
        func at(_ fraction: Double) -> Date { startOfToday.addingTimeInterval(elapsed * fraction) }

        let manager = makeIsolatedLocationManager()
        manager.isTrackingEnabled = false
        let model = LocationViewModel(modelContext: context, locationManager: manager)

        let first = LocationPoint(latitude: mission.0, longitude: mission.1, timestamp: at(0.2), horizontalAccuracy: 5)
        let spike = LocationPoint(latitude: glitch.0, longitude: glitch.1, timestamp: at(0.3), horizontalAccuracy: 5)
        [first, spike].forEach(context.insert)
        try context.save()
        manager.lastSavedLocationPoints = [first, spike]
        try await waitForMainQueue()
        XCTAssertEqual(model.todayDistanceTraveled, leg(mission, glitch), accuracy: 0.01)

        let back = LocationPoint(latitude: mission.0, longitude: mission.1, timestamp: at(0.4), horizontalAccuracy: 5)
        spike.isOutlier = true
        context.insert(back)
        try context.save()
        manager.lastSavedLocationPoints = [back]
        try await waitForMainQueue()
        XCTAssertEqual(model.todayDistanceTraveled, 0, accuracy: 0.01)
    }

    // MARK: - Helpers

    private func waitForMainQueue() async throws {
        // The view model receives saved points on the main queue.
        try await Task.sleep(nanoseconds: 50_000_000)
        await Task.yield()
    }

    private func makeInMemoryContainer() throws -> ModelContainer {
        let schema = Schema([Visit.self, LocationPoint.self, RecordingSession.self, PhotoMoment.self, SavedPlace.self, CountryStay.self])
        let configuration = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true, allowsSave: true)
        return try ModelContainer(for: schema, configurations: [configuration])
    }
}
