import XCTest
import CoreLocation
@testable import IsoMe

final class MainTabLaunchTests: XCTestCase {
    func testTabBarOrderPutsTheMapFirst() {
        XCTAssertEqual(MainTab.primary, [.map, .atlas, .places, .archive])
        XCTAssertFalse(MainTab.primary.contains(.settings))
    }

    func testTabTagsStayStableForLaunchArguments() {
        XCTAssertEqual(MainTab.map.rawValue, 0)
        XCTAssertEqual(MainTab.archive.rawValue, 1)
        XCTAssertEqual(MainTab.settings.rawValue, 2)
        XCTAssertEqual(MainTab.atlas.rawValue, 5)
        XCTAssertEqual(MainTab.places.rawValue, 6)
        XCTAssertEqual(Set(MainTab.allCases.map(\.rawValue)).count, MainTab.allCases.count)
    }

    func testNoArgumentOpensTheMap() {
        XCTAssertEqual(MainTabLaunch.fromLaunchArguments([]), .home)
        XCTAssertEqual(MainTabLaunch.home.tab, .map)
        XCTAssertFalse(MainTabLaunch.home.opensTimeline)
        XCTAssertFalse(MainTabLaunch.home.expandsDaySheet)
    }

    func testLaunchNumbersMapToTabs() {
        XCTAssertEqual(MainTabLaunch(launchNumber: 0), MainTabLaunch(tab: .map))
        XCTAssertEqual(MainTabLaunch(launchNumber: 1), MainTabLaunch(tab: .archive))
        // Settings lives behind Archive in the closed tab bar.
        XCTAssertEqual(MainTabLaunch(launchNumber: 2), MainTabLaunch(tab: .archive))
        XCTAssertEqual(MainTabLaunch(launchNumber: 3), MainTabLaunch(tab: .map, opensTimeline: true))
        // The old Today tab opens the map with Today's day sheet expanded.
        XCTAssertEqual(MainTabLaunch(launchNumber: 4), MainTabLaunch(tab: .map, expandsDaySheet: true))
        XCTAssertEqual(MainTabLaunch(launchNumber: 5), MainTabLaunch(tab: .atlas))
        XCTAssertEqual(MainTabLaunch(launchNumber: 6), MainTabLaunch(tab: .places))
    }

    func testLaunchArgumentParsing() {
        XCTAssertEqual(MainTabLaunch.fromLaunchArguments(["--seed-screenshot-data", "--default-tab=5"]).tab, .atlas)
        XCTAssertEqual(MainTabLaunch.fromLaunchArguments(["--default-tab=4"]).expandsDaySheet, true)
        XCTAssertEqual(MainTabLaunch.fromLaunchArguments(["--default-tab=9"]), .home)
        XCTAssertEqual(MainTabLaunch.fromLaunchArguments(["--default-tab=map"]), .home)
        XCTAssertEqual(MainTabLaunch.fromLaunchArguments(["--default-tab=-1"]), .home)
    }
}

final class RouteLegPlannerTests: XCTestCase {
    private let base = Date(timeIntervalSinceReferenceDate: 800_000_000)
    private func t(_ minutes: Double) -> Date { base.addingTimeInterval(minutes * 60) }

    private var stays: [RouteStay] {
        [
            RouteStay(key: "Home", arrival: t(0), departure: t(10)),
            RouteStay(key: "Ferry Building", arrival: t(30), departure: t(60)),
            RouteStay(key: "Office", arrival: t(80), departure: nil)
        ]
    }

    func testEachMoveTakesTheColourOfWhereItLeads() {
        // Home (0, 5), toward Ferry (15, 25), at Ferry (35, 55), toward Office (70), at Office (85, 90).
        let times = [0, 5, 15, 25, 35, 55, 70, 85, 90].map(t)
        let legs = RouteLegPlanner.legs(pointTimes: times, stays: stays, now: t(95))

        XCTAssertEqual(legs.map(\.destinationKey), ["Home", "Ferry Building", "Ferry Building", "Office", "Office"])
        XCTAssertEqual(legs.map(\.isMove), [false, true, false, true, false])
        // Legs share their boundary points, so the drawn route is continuous.
        for (previous, next) in zip(legs, legs.dropFirst()) {
            XCTAssertEqual(previous.endIndex, next.startIndex)
        }
        XCTAssertEqual(legs.first?.startIndex, 0)
        XCTAssertEqual(legs.last?.endIndex, times.count - 1)
        // Leaving Home, the stretch is already heading to the Ferry Building.
        XCTAssertEqual(legs[1], RouteLeg(startIndex: 1, endIndex: 4, destinationKey: "Ferry Building", isMove: true))
    }

    func testRouteAfterTheLastStayHasNoDestination() {
        let finished = [RouteStay(key: "Home", arrival: t(0), departure: t(10))]
        let legs = RouteLegPlanner.legs(pointTimes: [t(2), t(8), t(20), t(30)], stays: finished, now: t(40))
        XCTAssertEqual(legs.last?.destinationKey, nil)
        XCTAssertEqual(legs.last?.isMove, true)
    }

    func testNoStaysOrTooFewPoints() {
        XCTAssertEqual(RouteLegPlanner.legs(pointTimes: [t(0)], stays: stays, now: t(1)), [])
        let legs = RouteLegPlanner.legs(pointTimes: [t(0), t(1), t(2)], stays: [], now: t(3))
        XCTAssertEqual(legs, [RouteLeg(startIndex: 0, endIndex: 2, destinationKey: nil, isMove: true)])
    }

    func testLegIndexAndMainLegs() {
        let times = [0, 5, 15, 25, 35, 55, 70, 85, 90].map(t)
        let legs = RouteLegPlanner.legs(pointTimes: times, stays: stays, now: t(95))
        XCTAssertEqual(RouteLegPlanner.legIndex(containing: 2, in: legs), 1)
        XCTAssertEqual(RouteLegPlanner.legIndex(containing: 8, in: legs), 4)

        let distances = [20.0, 3_400, 40, 1_100, 10]
        XCTAssertEqual(RouteLegPlanner.mainLegIndices(legs, distances: distances), [1, 3])
        XCTAssertEqual(RouteLegPlanner.mainLegIndices(legs, distances: distances, limit: 1), [1])
        XCTAssertEqual(RouteLegPlanner.mainLegIndices(legs, distances: [1]), [])
    }

    /// Regression: once the road-matched route arrived, its unmatched stretches were merged into one segment and
    /// the whole day was inked like the first leg (terracotta) instead of leg by leg.
    func testAMergedRouteSegmentSplitsIntoOneRunPerLeg() {
        let times = [0, 5, 15, 25, 35, 55, 70, 85, 90].map(t)
        let legs = RouteLegPlanner.legs(pointTimes: times, stays: stays, now: t(95))

        let whole = RouteLegPlanner.legRuns(from: 0, to: times.count - 1, in: legs)
        XCTAssertEqual(whole, legs.enumerated().map { index, leg in
            RouteLegPlanner.LegRun(legIndex: index, startIndex: leg.startIndex, endIndex: leg.endIndex)
        })
        XCTAssertEqual(whole.map { $0.legIndex.flatMap { legs[$0].destinationKey } },
                       ["Home", "Ferry Building", "Ferry Building", "Office", "Office"])
        for (previous, next) in zip(whole, whole.dropFirst()) {
            XCTAssertEqual(previous.endIndex, next.startIndex)
        }
    }

    func testAClippedSegmentStartsAndEndsInsideItsLegs() {
        let times = [0, 5, 15, 25, 35, 55, 70, 85, 90].map(t)
        let legs = RouteLegPlanner.legs(pointTimes: times, stays: stays, now: t(95))
        // Replay draws the route up to the playhead, which can fall in the middle of a leg.
        XCTAssertEqual(RouteLegPlanner.legRuns(from: 2, to: 6, in: legs), [
            RouteLegPlanner.LegRun(legIndex: 1, startIndex: 2, endIndex: 4),
            RouteLegPlanner.LegRun(legIndex: 2, startIndex: 4, endIndex: 5),
            RouteLegPlanner.LegRun(legIndex: 3, startIndex: 5, endIndex: 6)
        ])
        XCTAssertEqual(RouteLegPlanner.legRuns(from: 3, to: 3, in: legs), [])
        XCTAssertEqual(RouteLegPlanner.legRuns(from: 0, to: 2, in: []),
                       [RouteLegPlanner.LegRun(legIndex: nil, startIndex: 0, endIndex: 2)])
    }

    func testInkOrderMatchesTheDial() {
        var order = PlaceInkOrder(leading: "Home")
        XCTAssertEqual(order.index(for: "Home"), 0)
        XCTAssertEqual(order.index(for: "Ferry Building"), 1)
        XCTAssertEqual(order.index(for: "Office"), 2)
        XCTAssertEqual(order.index(for: "Ferry Building"), 1)

        var noHome = PlaceInkOrder()
        XCTAssertEqual(noHome.index(for: "Ferry Building"), 0)
    }
}

final class MultiDayAggregatorTests: XCTestCase {
    private var calendar: Calendar {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(identifier: "America/Los_Angeles")!
        return calendar
    }

    private func date(_ day: Int, _ hour: Int, _ minute: Int = 0) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 10, day: day, hour: hour, minute: minute))!
    }

    func testVisitCountsPerPlaceCountDaysNotVisits() {
        let visits = [
            DayVisitSample(placeKey: "Home", arrivedAt: date(27, 8)),
            DayVisitSample(placeKey: "Home", arrivedAt: date(27, 20)),
            DayVisitSample(placeKey: "Home", arrivedAt: date(28, 9)),
            DayVisitSample(placeKey: "Tartine", arrivedAt: date(28, 12)),
            DayVisitSample(placeKey: "Office", arrivedAt: date(29, 10))
        ]
        let summary = MultiDayAggregator.summarize(
            visits: visits, points: [], range: date(26, 0)...date(29, 23), calendar: calendar)

        XCTAssertEqual(summary.placeDayCounts, ["Home": 2, "Tartine": 1, "Office": 1])
        XCTAssertEqual(summary.totalVisits, 5)
        XCTAssertEqual(summary.days.map(\.visitCount), [0, 2, 2, 1])
        XCTAssertEqual(summary.days.map(\.placeCount), [0, 1, 2, 1])
        XCTAssertEqual(summary.defaultSelectedDay, calendar.startOfDay(for: date(29, 0)))
    }

    func testDistanceIsSummedPerDayWithoutBridgingNights() {
        // About 1.11 km per 0.01° of latitude.
        let points = [
            DayPathSample(timestamp: date(27, 9), latitude: 37.70, longitude: -122.4),
            DayPathSample(timestamp: date(27, 10), latitude: 37.71, longitude: -122.4),
            DayPathSample(timestamp: date(28, 9), latitude: 37.80, longitude: -122.4),
            DayPathSample(timestamp: date(28, 10), latitude: 37.82, longitude: -122.4)
        ]
        let summary = MultiDayAggregator.summarize(
            visits: [], points: points, range: date(27, 0)...date(28, 23), calendar: calendar)

        XCTAssertEqual(summary.days.count, 2)
        XCTAssertEqual(summary.days[0].distanceMeters, 1_110, accuracy: 15)
        XCTAssertEqual(summary.days[1].distanceMeters, 2_220, accuracy: 30)
        // The overnight jump of 10 km is not travel.
        XCTAssertEqual(summary.totalDistanceMeters, 3_330, accuracy: 45)
        XCTAssertEqual(summary.longestDayMeters, summary.days[1].distanceMeters)
    }

    func testShortRangesListEveryDayLongRangesOnlyRecordedOnes() {
        let short = MultiDayAggregator.dayList(range: date(1, 0)...date(7, 12), recordedDays: [], calendar: calendar)
        XCTAssertEqual(short.count, 7)

        let recorded: Set<Date> = [calendar.startOfDay(for: date(3, 0)), calendar.startOfDay(for: date(20, 0))]
        let all = MultiDayAggregator.dayList(range: Date.distantPast...date(30, 12), recordedDays: recorded, calendar: calendar)
        XCTAssertEqual(all.count, 28)

        var long = calendar
        long.timeZone = calendar.timeZone
        let wide = MultiDayAggregator.dayList(
            range: calendar.date(byAdding: .day, value: -90, to: date(30, 0))!...date(30, 12),
            recordedDays: recorded, calendar: long)
        XCTAssertEqual(wide, recorded.sorted())
    }

    func testBubbleSizesAndThinning() {
        XCTAssertEqual(MultiDayAggregator.bubbleDiameter(dayCount: 1, maximumDayCount: 1), 24)
        XCTAssertEqual(MultiDayAggregator.bubbleDiameter(dayCount: 1, maximumDayCount: 5), 22)
        XCTAssertEqual(MultiDayAggregator.bubbleDiameter(dayCount: 5, maximumDayCount: 5), 44)

        let thinned = MultiDayAggregator.thinned(Array(0..<1_000), limit: 10)
        XCTAssertEqual(thinned.count, 10)
        XCTAssertEqual(thinned.first, 0)
        XCTAssertEqual(thinned.last, 999)
        XCTAssertEqual(MultiDayAggregator.thinned([1, 2, 3], limit: 10), [1, 2, 3])
    }

    // MARK: Places per day, as Today counts them

    private let homeSpot = (37.3349, -122.0090)
    private let northBeach = (37.8060, -122.4100)
    private let office = (37.3500, -122.0090)
    private let tartine = (37.7614, -122.4241)

    private var savedHome: SavedPlace {
        SavedPlace(name: "Home", latitude: homeSpot.0, longitude: homeSpot.1, radiusMeters: 150, kind: .home)
    }

    private func visit(_ name: String, _ place: (Double, Double), from arrived: Date, to departed: Date?) -> Visit {
        Visit(latitude: place.0, longitude: place.1, arrivedAt: arrived, departedAt: departed,
              locationName: name, geocodingCompleted: true)
    }

    /// Points every 10 minutes, standing at one place.
    private func points(at place: (Double, Double), from start: Date, to end: Date) -> [LocationPoint] {
        stride(from: start.timeIntervalSinceReferenceDate, through: end.timeIntervalSinceReferenceDate, by: 600).map {
            LocationPoint(latitude: place.0, longitude: place.1, timestamp: Date(timeIntervalSinceReferenceDate: $0),
                          horizontalAccuracy: 5)
        }
    }

    /// The map's range summary for the models, through the same conversion `LocationMapView.rangeSummary` uses.
    @MainActor
    private func rangeSummary(visits: [Visit], points: [LocationPoint], home: SavedPlace?,
                              range: ClosedRange<Date>, now: Date) -> RangeSummary {
        MultiDayAggregator.summarize(
            visits: visits.map { DayVisitSample(placeKey: PlaceInkOrder.placeName(for: $0, home: home), arrivedAt: $0.arrivedAt) },
            points: points.map { DayPathSample(timestamp: $0.timestamp, latitude: $0.latitude, longitude: $0.longitude) },
            range: range.lowerBound...min(range.upperBound, now),
            stays: DayStayInput(visits: visits.enumerated().map { DayStayVisit($1, index: $0) },
                                points: points.filter { !$0.isOutlier }.map(DayStayPoint.init),
                                home: home.map(DayStayHome.init), now: now),
            calendar: calendar)
    }

    /// Today's "N PLACES" for `day` (`TodayDay(viewModel:now:)`), or the day sheet's for a finished day
    /// (`TodayDay(pastDay:)`), from the same models.
    @MainActor
    private func todayPlaceCount(_ day: Date, visits: [Visit], points: [LocationPoint], home: SavedPlace?, now: Date) -> Int {
        let start = calendar.startOfDay(for: day)
        let midnight = calendar.date(byAdding: .day, value: 1, to: start)!
        let isToday = now < midnight
        let end = isToday ? now : midnight
        let shown = visits.filter { $0.arrivedAt <= end && ($0.departedAt ?? end) >= start }.sorted { $0.arrivedAt < $1.arrivedAt }
        let dayPoints = points.filter { !$0.isOutlier && $0.timestamp >= start && $0.timestamp < midnight }
        let dayEnd = isToday ? now : DayStayPlanner.pastDayEnd(visits: shown.map { DayStayVisit($0) },
                                                               points: dayPoints.map(DayStayPoint.init), endOfDay: midnight)
        return TodayDay(visits: shown, points: dayPoints, startOfDay: start, now: dayEnd, home: home,
                        isCurrentVisit: { $0.departedAt == nil }).placeCount
    }

    @MainActor
    func testAnOvernightStayCountsOnTheFollowingDay() {
        // A night at a friend's in North Beach from 8 PM, then the office and lunch the next day.
        let visits = [
            visit("North Beach", northBeach, from: date(27, 20), to: date(28, 7, 30)),
            visit("Office", office, from: date(28, 9), to: date(28, 12)),
            visit("Tartine", tartine, from: date(28, 12, 30), to: date(28, 13, 30))
        ]
        let now = date(28, 18)
        let summary = rangeSummary(visits: visits, points: [], home: nil, range: date(27, 0)...date(28, 23, 59), now: now)

        XCTAssertEqual(summary.days.map(\.placeCount), [1, 3])
        XCTAssertEqual(summary.days[1].placeCount, todayPlaceCount(date(28, 0), visits: visits, points: [], home: nil, now: now))
        // The night counts on both days it covers; arrivals (VISITS) still count once.
        XCTAssertEqual(summary.placeDayCounts["North Beach"], 2)
        XCTAssertEqual(summary.days.map(\.visitCount), [1, 2])

        // Without Today's stays, the old per-arrival count missed the night on the 28th.
        let byArrival = MultiDayAggregator.summarize(
            visits: visits.map { DayVisitSample(placeKey: $0.displayName, arrivedAt: $0.arrivedAt) },
            points: [], range: date(27, 0)...now, calendar: calendar)
        XCTAssertEqual(byArrival.days.map(\.placeCount), [1, 2])
    }

    @MainActor
    func testSevenDayCountsEqualTodaysCountForEveryDay() {
        // Oct 22–28, now Wednesday the 28th at 8 PM. A saved Home, an overnight Home visit from the 27th's
        // evening into the 28th (inside Home, so it reads "Home"), a morning at Home known only from points,
        // and an overnight stay away from Home.
        let home = savedHome
        let visits = [
            visit("Office", office, from: date(22, 9), to: date(22, 17)),
            visit("Tartine", tartine, from: date(23, 12), to: date(23, 13)),
            visit("North Beach", northBeach, from: date(24, 21), to: date(25, 9)),
            visit("Office", office, from: date(25, 10), to: date(25, 16)),
            visit("812 Elm St", (homeSpot.0 + 0.0003, homeSpot.1), from: date(27, 19), to: date(28, 8)),
            visit("Office", office, from: date(28, 9), to: date(28, 12)),
            visit("Tartine", tartine, from: date(28, 12, 30), to: date(28, 13, 30)),
            visit("Office", office, from: date(28, 14), to: nil)
        ]
        let points = points(at: homeSpot, from: date(22, 0), to: date(22, 8, 30))
            + points(at: homeSpot, from: date(23, 0), to: date(23, 11))
            + points(at: homeSpot, from: date(26, 0), to: date(26, 23))
            + points(at: homeSpot, from: date(27, 0), to: date(27, 10))
        let now = date(28, 20)
        let summary = rangeSummary(visits: visits, points: points, home: home, range: date(22, 0)...date(28, 23, 59), now: now)

        XCTAssertEqual(summary.days.count, 7)
        let today = summary.days.map { todayPlaceCount($0.day, visits: visits, points: points, home: home, now: now) }
        XCTAssertEqual(summary.days.map(\.placeCount), today)
        // The 27th is Home all day; the 28th is Home (the night from the 27th), Office and Tartine.
        XCTAssertEqual(today, [2, 2, 1, 2, 1, 1, 3])
        XCTAssertEqual(summary.placeDayCounts["Home"], 5)
        XCTAssertEqual(summary.placeDayCounts["North Beach"], 2)
        XCTAssertTrue(summary.days.allSatisfy(\.hasRecord))
    }

}

final class ReplayClockTests: XCTestCase {
    private let start = Date(timeIntervalSinceReferenceDate: 800_000_000)
    private var domain: ClosedRange<Date> { start...start.addingTimeInterval(4 * 3_600) }

    func testSpeedsPlayMinutesOrHoursPerSecond() {
        XCTAssertEqual(ReplaySpeed.allCases.map(\.label), ["1×", "10×", "60×"])
        XCTAssertEqual(ReplaySpeed.one.recordedSecondsPerSecond, 60)
        XCTAssertEqual(ReplaySpeed.sixty.recordedSecondsPerSecond, 3_600)
    }

    func testAdvancingOnlyWhilePlayingAndStoppingAtTheEnd() {
        var clock = ReplayClock(domain: domain, speed: .sixty)
        clock.advance(byWallSeconds: 1)
        XCTAssertEqual(clock.time, start, "Paused clocks do not move")

        clock.play()
        clock.advance(byWallSeconds: 1)
        XCTAssertEqual(clock.progress, 0.25, accuracy: 0.0001)
        clock.advance(byWallSeconds: 10)
        XCTAssertTrue(clock.isAtEnd)
        XCTAssertFalse(clock.isPlaying)
        XCTAssertEqual(clock.progress, 1)

        // Playing again from the end starts over.
        clock.play()
        XCTAssertEqual(clock.time, start)
        XCTAssertTrue(clock.isPlaying)
    }

    func testScrubbingPausesAndClamps() {
        var clock = ReplayClock(domain: domain, speed: .ten)
        clock.play()
        clock.scrub(toFraction: 0.5)
        XCTAssertFalse(clock.isPlaying)
        XCTAssertEqual(clock.progress, 0.5, accuracy: 0.0001)
        clock.scrub(to: start.addingTimeInterval(-100))
        XCTAssertEqual(clock.time, start)
        clock.scrub(toFraction: 2)
        XCTAssertTrue(clock.isAtEnd)
        clock.togglePlayback()
        XCTAssertTrue(clock.isPlaying)
        clock.togglePlayback()
        XCTAssertFalse(clock.isPlaying)
    }

    func testReduceMotionJumpsToTheNextStop() {
        let arrivals = [start.addingTimeInterval(3_600), start.addingTimeInterval(7_200)]
        XCTAssertEqual(ReplayClock.jumpTarget(after: start, arrivals: arrivals, domain: domain), arrivals[0])
        XCTAssertEqual(ReplayClock.jumpTarget(after: arrivals[0], arrivals: arrivals, domain: domain), arrivals[1])
        XCTAssertEqual(ReplayClock.jumpTarget(after: arrivals[1], arrivals: arrivals, domain: domain), domain.upperBound)
    }
}

@MainActor
final class MapLayerPreferencesTests: XCTestCase {
    private var defaults: UserDefaults!
    private let suiteName = "MapLayerPreferencesTests"

    override func setUp() {
        super.setUp()
        UserDefaults().removePersistentDomain(forName: suiteName)
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        UserDefaults().removePersistentDomain(forName: suiteName)
        defaults = nil
        super.tearDown()
    }

    func testDefaultsApplyUntilSomethingIsStored() {
        XCTAssertTrue(MapLayerPreferences.value(for: MapLayerPreferences.visitsKey, in: defaults))
        XCTAssertTrue(MapLayerPreferences.value(for: MapLayerPreferences.travelPathKey, in: defaults))
        XCTAssertFalse(MapLayerPreferences.value(for: MapLayerPreferences.pointMarkersKey, in: defaults))
        XCTAssertTrue(MapLayerPreferences.value(for: MapLayerPreferences.snapToRoadsKey, in: defaults))
        XCTAssertFalse(MapLayerPreferences.value(for: MapLayerPreferences.showOutliersKey, in: defaults))
    }

    func testStoredSwitchesPersist() {
        defaults.set(false, forKey: MapLayerPreferences.visitsKey)
        defaults.set(true, forKey: MapLayerPreferences.pointMarkersKey)
        XCTAssertFalse(MapLayerPreferences.value(for: MapLayerPreferences.visitsKey, in: defaults))
        XCTAssertTrue(MapLayerPreferences.value(for: MapLayerPreferences.pointMarkersKey, in: defaults))
    }

    func testOlderKeysAreReused() {
        XCTAssertEqual(MapLayerPreferences.snapToRoadsKey, "snapTravelPathToRoads")
        XCTAssertEqual(MapLayerPreferences.straightGapsKey, "showStraightLinePathSegments")
        XCTAssertEqual(MapLayerPreferences.photosKey, LocationViewModel.showPhotoMarkersKey)
        XCTAssertEqual(MapLayerPreferences.suggestionsKey, LocationViewModel.showVisitSuggestionsKey)
        XCTAssertEqual(MapLayerPreferences.photoImagesKey, PhotoMapMarkerStyle.imagesKey)
        XCTAssertEqual(MapLayerPreferences.photoDotsKey, PhotoMapMarkerStyle.dotsKey)
        XCTAssertEqual(MapLayerPreferences.showOutliersKey, "showOutliers")
        XCTAssertEqual(Set(MapLayerPreferences.defaults.keys).count, 12)
    }

    func testPhotoDisplayRoundTripsThroughTheOlderSwitches() {
        XCTAssertEqual(PhotoMarkerDisplay(showsImages: true, showsDots: false), .images)
        XCTAssertEqual(PhotoMarkerDisplay(showsImages: false, showsDots: false), .pins)
        XCTAssertEqual(PhotoMarkerDisplay(showsImages: true, showsDots: true), .dots)
        XCTAssertEqual(PhotoMarkerDisplay(showsImages: false, showsDots: true), .dots)

        // Dots keep the images choice underneath, so switching dots off restores it.
        XCTAssertTrue(PhotoMarkerDisplay.dots.storedFlags(previousImages: true) == (true, true))
        XCTAssertTrue(PhotoMarkerDisplay.dots.storedFlags(previousImages: false) == (false, true))
        XCTAssertTrue(PhotoMarkerDisplay.pins.storedFlags(previousImages: true) == (false, false))
        XCTAssertTrue(PhotoMarkerDisplay.images.storedFlags(previousImages: false) == (true, false))
    }
}

final class MapDatePresetTests: XCTestCase {
    private var calendar: Calendar { Calendar.current }

    private func dayCount(_ range: ClosedRange<Date>) -> Int {
        let first = calendar.startOfDay(for: range.lowerBound)
        let last = calendar.startOfDay(for: range.upperBound)
        return (calendar.dateComponents([.day], from: first, to: last).day ?? 0) + 1
    }

    func testSevenDaysIsTodayAndTheSixDaysBefore() {
        let now = calendar.date(from: DateComponents(year: 2026, month: 11, day: 1, hour: 14, minute: 20))!
        let range = MapDatePreset.sevenDays.range(referenceDate: now)

        XCTAssertEqual(range.upperBound, now)
        XCTAssertEqual(range.lowerBound, calendar.date(from: DateComponents(year: 2026, month: 10, day: 26))!)
        XCTAssertEqual(dayCount(range), 7)
        XCTAssertEqual(MultiDayAggregator.dayList(range: range, recordedDays: [], calendar: calendar).count, 7)
    }

    func testSevenDaysJustAfterMidnightStillCoversSevenDays() {
        let now = calendar.date(from: DateComponents(year: 2026, month: 3, day: 2, hour: 0, minute: 5))!
        let range = MapDatePreset.sevenDays.range(referenceDate: now)
        XCTAssertEqual(dayCount(range), 7)
        XCTAssertEqual(range.lowerBound, calendar.date(from: DateComponents(year: 2026, month: 2, day: 24))!)
    }

    func testThirtyDaysIsTodayAndTheTwentyNineDaysBefore() {
        let now = calendar.date(from: DateComponents(year: 2026, month: 11, day: 5, hour: 9, minute: 40))!
        let range = MapDatePreset.thirtyDays.range(referenceDate: now)

        XCTAssertEqual(range.upperBound, now)
        XCTAssertEqual(range.lowerBound, calendar.date(from: DateComponents(year: 2026, month: 10, day: 7))!)
        XCTAssertEqual(dayCount(range), 30)
        XCTAssertEqual(MultiDayAggregator.dayList(range: range, recordedDays: [], calendar: calendar).count, 30)
    }

    func testThirtyDaysAcrossAMonthBoundaryJustAfterMidnight() {
        let now = calendar.date(from: DateComponents(year: 2026, month: 3, day: 1, hour: 0, minute: 1))!
        let range = MapDatePreset.thirtyDays.range(referenceDate: now)
        XCTAssertEqual(dayCount(range), 30)
        XCTAssertEqual(range.lowerBound, calendar.date(from: DateComponents(year: 2026, month: 1, day: 31))!)
    }

    func testOtherPresetsKeepTheirMeaning() {
        let now = calendar.date(from: DateComponents(year: 2026, month: 11, day: 1, hour: 14, minute: 20))!
        XCTAssertEqual(MapDatePreset.today.range(referenceDate: now).lowerBound, calendar.startOfDay(for: now))
        XCTAssertEqual(dayCount(MapDatePreset.yesterday.range(referenceDate: now)), 1)
        XCTAssertEqual(MapDatePreset.all.range(referenceDate: now).lowerBound, .distantPast)
    }
}

/// Regression: the visit sheet's "Where were you?" candidates used MKDistanceFormatter, which follows the locale
/// (miles in the US), while the rest of the map follows the app's unit setting.
final class NearbyPlaceDistanceLabelTests: XCTestCase {
    private let suggestion = NearbyPlaceSuggestion(
        id: "grove",
        name: "The Grove",
        address: nil,
        coordinate: CLLocationCoordinate2D(latitude: 37.7877, longitude: -122.4077),
        distanceMeters: 1_127
    )

    func testCandidateDistanceFollowsTheUnitSetting() {
        XCTAssertEqual(suggestion.distanceLabel(usesMetric: true), DistanceFormatter.format(meters: 1_127, usesMetric: true))
        XCTAssertEqual(suggestion.distanceLabel(usesMetric: false), DistanceFormatter.format(meters: 1_127, usesMetric: false))
        XCTAssertTrue(suggestion.distanceLabel(usesMetric: true).contains("km"), suggestion.distanceLabel(usesMetric: true))
        XCTAssertTrue(suggestion.distanceLabel(usesMetric: false).contains("mi"), suggestion.distanceLabel(usesMetric: false))
    }
}

@MainActor
final class TraceDayDomainTests: XCTestCase {
    private func calendar(_ zone: String = "America/New_York") -> Calendar {
        var value = Calendar(identifier: .gregorian)
        value.timeZone = TimeZone(identifier: zone)!
        return value
    }

    private func day(_ start: Date, calendar: Calendar) -> TraceDay {
        let end = calendar.date(byAdding: .day, value: 1, to: start)!
        return TraceDay(range: start...end.addingTimeInterval(-1), visits: [], points: [],
                        now: end.addingTimeInterval(3600), calendar: calendar)
    }

    func testRightEdgeAndAccessibilityStepsStayOnSelectedDay() {
        let calendar = calendar()
        let start = calendar.date(from: DateComponents(year: 2026, month: 11, day: 6))!
        let day = day(start, calendar: calendar)
        let lastSecond = calendar.date(byAdding: .day, value: 1, to: start)!.addingTimeInterval(-1)
        XCTAssertEqual(day.date(at: 1), lastSecond)
        XCTAssertEqual(day.date(at: 2), lastSecond)
        XCTAssertEqual(day.clampedTime(lastSecond.addingTimeInterval(15 * 60)), lastSecond)
        XCTAssertEqual(day.date(at: -1), start)
        XCTAssertEqual(day.date(at: 0.25), start.addingTimeInterval(6 * 3600),
                       "Clamping the endpoint must not shift intermediate clock labels")
    }

    func testDaylightSavingDayEndsAtItsOwnLastSecond() {
        let calendar = calendar()
        for (month, date, hours) in [(3, 8, 23), (11, 1, 25)] {
            let start = calendar.date(from: DateComponents(year: 2026, month: month, day: date))!
            let day = day(start, calendar: calendar)
            XCTAssertEqual(day.domain.upperBound.timeIntervalSince(start), Double(hours * 3600))
            XCTAssertTrue(calendar.isDate(day.date(at: 1), inSameDayAs: start))
            XCTAssertEqual(calendar.component(.hour, from: day.date(at: 1)), 23)
            XCTAssertEqual(calendar.component(.minute, from: day.date(at: 1)), 59)
        }
    }

    func testMultiDayRangeRetainsExactUpperBoundary() {
        let calendar = calendar()
        let start = calendar.date(from: DateComponents(year: 2026, month: 11, day: 4))!
        let end = calendar.date(byAdding: .day, value: 3, to: start)!
        let day = TraceDay(range: start...end, visits: [], points: [], now: end, calendar: calendar)
        XCTAssertFalse(day.isSingleDay)
        XCTAssertEqual(day.date(at: 1), end)
        XCTAssertEqual(day.clampedTime(end.addingTimeInterval(3600)), end)
    }
}

final class TimelineStaySummaryTests: XCTestCase {
    private let day = Date(timeIntervalSinceReferenceDate: 800_000_000)

    func testOngoingVisitOnHistoricalDayShowsOnlyThatDaysDuration() {
        let summary = TimelineStaySummary(arrivedAt: day.addingTimeInterval(-3600), departedAt: nil,
            dayRange: day...day.addingTimeInterval(86400), isCurrent: true,
            now: day.addingTimeInterval(100000))
        XCTAssertEqual(summary.start, day)
        XCTAssertEqual(summary.end, day.addingTimeInterval(86400))
        XCTAssertFalse(summary.isCurrent)
        XCTAssertEqual(summary.trailingText, "24h")
        XCTAssertTrue(summary.accessibilityValue.hasSuffix(" · 24h"))
        XCTAssertFalse(summary.accessibilityValue.contains("now"))
    }

    func testCurrentVisitWithinSelectedDayStillShowsNow() {
        let summary = TimelineStaySummary(arrivedAt: day.addingTimeInterval(3600), departedAt: nil,
            dayRange: day...day.addingTimeInterval(86400), isCurrent: true,
            now: day.addingTimeInterval(7200))
        XCTAssertTrue(summary.isCurrent)
        XCTAssertEqual(summary.trailingText, "now")
    }

    func testMidnightBelongsToFollowingDayForCurrentLabel() {
        let midnight = day.addingTimeInterval(86400)
        let summary = TimelineStaySummary(arrivedAt: day, departedAt: nil,
            dayRange: day...midnight, isCurrent: true, now: midnight)
        XCTAssertFalse(summary.isCurrent)
        XCTAssertEqual(summary.trailingText, "24h")
    }

    func testCompletedOrFutureVisitCannotShowNow() {
        let range = day...day.addingTimeInterval(86400)
        let completed = TimelineStaySummary(arrivedAt: day, departedAt: day.addingTimeInterval(1800),
            dayRange: range, isCurrent: true, now: day.addingTimeInterval(7200))
        XCTAssertFalse(completed.isCurrent)
        XCTAssertEqual(completed.trailingText, "30m")
        let future = TimelineStaySummary(arrivedAt: day.addingTimeInterval(10800), departedAt: nil,
            dayRange: range, isCurrent: true, now: day.addingTimeInterval(7200))
        XCTAssertFalse(future.isCurrent)
        XCTAssertEqual(future.trailingText, "0m")
    }

    func testHistoricalDayDurationRespectsBothDaylightSavingTransitions() throws {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = try XCTUnwrap(TimeZone(identifier: "America/New_York"))
        for (month, date, hours) in [(3, 8, 23), (11, 1, 25)] {
            let start = try XCTUnwrap(calendar.date(from: DateComponents(year: 2026, month: month, day: date)))
            let end = try XCTUnwrap(calendar.date(byAdding: .day, value: 1, to: start))
            let summary = TimelineStaySummary(arrivedAt: start.addingTimeInterval(-3600), departedAt: nil,
                dayRange: start...end, isCurrent: true, now: end.addingTimeInterval(3600))
            XCTAssertFalse(summary.isCurrent)
            XCTAssertEqual(summary.trailingText, "\(hours)h")
        }
    }
}

final class MapSheetDragTests: XCTestCase {
    private let height: CGFloat = 874

    private func settle(_ detent: MapSheetDetent, drag: CGFloat, predicted: CGFloat? = nil) -> MapSheetDetent {
        MapSheetDetent.settledDetent(from: detent, translation: drag, predicted: predicted ?? drag, containerHeight: height)
    }

    func testANudgeKeepsTheDetent() {
        XCTAssertEqual(settle(.medium, drag: 40), .medium)
        XCTAssertEqual(settle(.medium, drag: -40), .medium)
        XCTAssertEqual(settle(.large, drag: 60), .large)
    }

    func testADragAndHoldSettlesWhereTheFingerLeftIt() {
        // The scrawl: grab the medium sheet, drag well down, hold, let go with no velocity.
        XCTAssertEqual(settle(.medium, drag: 300, predicted: 300), .peek)
        XCTAssertEqual(settle(.large, drag: 250, predicted: 250), .medium)
        XCTAssertEqual(settle(.large, drag: 600, predicted: 600), .peek)
    }

    func testAFlickCarriesOnInItsDirection() {
        XCTAssertEqual(settle(.medium, drag: 50, predicted: 320), .peek)
        XCTAssertEqual(settle(.medium, drag: -50, predicted: -260), .large)
    }

    func testMomentumNeverReversesTheFinger() {
        XCTAssertEqual(settle(.medium, drag: 300, predicted: 20), .peek)
    }

    func testThePeekCardOnlyOpens() {
        XCTAssertEqual(settle(.peek, drag: -80), .medium)
        XCTAssertEqual(settle(.peek, drag: 80), .peek)
    }

    func testTheSheetTracksTheFingerDownAndStretchesOnlyALittlePastLarge() {
        let medium = MapSheetDetent.sheetHeight(detent: .medium, containerHeight: height, dragOffset: 0)
        XCTAssertEqual(MapSheetDetent.sheetHeight(detent: .medium, containerHeight: height, dragOffset: 100), medium - 100, accuracy: 0.01)

        let large = MapSheetDetent.sheetHeight(detent: .large, containerHeight: height, dragOffset: 0)
        let pulledUp = MapSheetDetent.sheetHeight(detent: .large, containerHeight: height, dragOffset: -400)
        XCTAssertGreaterThan(pulledUp, large)
        XCTAssertLessThan(pulledUp, large + 40)
    }
}
