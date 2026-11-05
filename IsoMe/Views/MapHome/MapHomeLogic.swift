import Foundation
import CoreLocation

// Pure logic behind the map home, kept free of SwiftUI and SwiftData so it can be tested directly.

// MARK: - Route legs coloured by destination

/// A stay the route can lead to, reduced to what colouring needs.
struct RouteStay: Equatable {
    /// The place's name as the day dial uses it (Home inside a saved Home).
    let key: String
    let arrival: Date
    /// Nil while the stay is still going on.
    let departure: Date?
}

/// A run of consecutive path points drawn in one ink.
///
/// Legs share their boundary point (`endIndex` of one is `startIndex` of the next) so the drawn route has no gaps.
struct RouteLeg: Equatable {
    let startIndex: Int
    let endIndex: Int
    /// The place this stretch leads to (or is spent at); nil after the last stay, where the destination is unknown.
    let destinationKey: String?
    /// True while moving between stays; false for points recorded during a stay.
    let isMove: Bool
}

enum RouteLegPlanner {
    /// Where a moment of the route belongs: the stay it falls inside, otherwise the next stay it leads to.
    static func destination(at time: Date, stays: [RouteStay], now: Date) -> (key: String?, isMove: Bool) {
        let sorted = stays.sorted { $0.arrival < $1.arrival }
        if let inside = sorted.last(where: { $0.arrival <= time && ($0.departure ?? now) >= time }) {
            return (inside.key, false)
        }
        if let next = sorted.first(where: { $0.arrival > time }) {
            return (next.key, true)
        }
        return (nil, true)
    }

    /// Splits time-ordered points into legs, each coloured by the visit it leads to.
    ///
    /// Each stretch between two points takes the destination of its later point: leaving a stay it is already
    /// heading to the next one, and arriving it belongs to the stay it reaches.
    static func legs(pointTimes: [Date], stays: [RouteStay], now: Date) -> [RouteLeg] {
        guard pointTimes.count >= 2 else { return [] }
        let destinations = pointTimes.map { destination(at: $0, stays: stays, now: now) }

        var legs: [RouteLeg] = []
        for index in 1..<pointTimes.count {
            let key = destinations[index].key
            let isMove = destinations[index].isMove || destinations[index - 1].isMove
            if let last = legs.last, last.destinationKey == key, last.isMove == isMove, last.endIndex == index - 1 {
                legs[legs.count - 1] = RouteLeg(startIndex: last.startIndex, endIndex: index, destinationKey: key, isMove: isMove)
            } else {
                legs.append(RouteLeg(startIndex: index - 1, endIndex: index, destinationKey: key, isMove: isMove))
            }
        }
        return legs
    }

    /// The leg a point index belongs to (the later leg at a shared boundary).
    static func legIndex(containing pointIndex: Int, in legs: [RouteLeg]) -> Int? {
        legs.lastIndex { $0.startIndex <= pointIndex && pointIndex <= $0.endIndex }
    }

    /// A stretch of the route that lies in a single leg.
    struct LegRun: Equatable {
        /// Index into the legs; nil where no leg covers the step.
        let legIndex: Int?
        let startIndex: Int
        let endIndex: Int
    }

    /// Splits the stretch of points `startIndex...endIndex` where it crosses from one leg into the next, so each run
    /// can take its own leg's ink. Neighbouring runs share their boundary point, so the line stays continuous.
    /// A road-matched route merges every unmatched stretch into one long segment; without this split the whole
    /// day took the ink of the first leg.
    static func legRuns(from startIndex: Int, to endIndex: Int, in legs: [RouteLeg]) -> [LegRun] {
        guard endIndex > startIndex else { return [] }
        var runs: [LegRun] = []
        for step in startIndex..<endIndex {
            // The step step→step+1 belongs to the leg it starts in, the later one at a shared boundary.
            let leg = legs.firstIndex { $0.startIndex <= step && step < $0.endIndex }
            if let last = runs.last, last.legIndex == leg {
                runs[runs.count - 1] = LegRun(legIndex: leg, startIndex: last.startIndex, endIndex: step + 1)
            } else {
                runs.append(LegRun(legIndex: leg, startIndex: step, endIndex: step + 1))
            }
        }
        return runs
    }

    /// Which move legs get a departure time chip: the longest ones at least `minimumMeters` long, in route order.
    static func mainLegIndices(_ legs: [RouteLeg], distances: [Double], minimumMeters: Double = 300, limit: Int = 4) -> [Int] {
        guard legs.count == distances.count else { return [] }
        return legs.indices
            .filter { legs[$0].isMove && distances[$0] >= minimumMeters }
            .sorted { distances[$0] > distances[$1] }
            .prefix(limit)
            .sorted()
    }
}

// MARK: - Several days at once

struct DayVisitSample: Equatable {
    let placeKey: String
    let arrivedAt: Date
}

struct DayPathSample: Equatable {
    let timestamp: Date
    let latitude: Double
    let longitude: Double
}

/// One day of a multi-day range, as its chip and the summary card describe it.
struct DaySummary: Identifiable, Equatable {
    let day: Date
    let distanceMeters: Double
    let placeCount: Int
    let visitCount: Int

    var id: Date { day }
    var hasRecord: Bool { distanceMeters > 0 || visitCount > 0 }
}

struct RangeSummary: Equatable {
    let days: [DaySummary]
    /// On how many days of the range each place was visited.
    let placeDayCounts: [String: Int]

    var totalDistanceMeters: Double { days.reduce(0) { $0 + $1.distanceMeters } }
    var totalVisits: Int { days.reduce(0) { $0 + $1.visitCount } }
    var longestDayMeters: Double { days.map(\.distanceMeters).max() ?? 0 }

    /// The day highlighted when the range opens: the latest with anything recorded.
    var defaultSelectedDay: Date? {
        days.last(where: \.hasRecord)?.day ?? days.last?.day
    }
}

enum MultiDayAggregator {
    /// Ranges up to this many days list every day, recorded or not; longer ones list only recorded days.
    static let maximumContinuousDays = 31

    static func summarize(
        visits: [DayVisitSample],
        points: [DayPathSample],
        range: ClosedRange<Date>,
        calendar: Calendar = .current
    ) -> RangeSummary {
        var visitsByDay: [Date: [DayVisitSample]] = [:]
        for visit in visits {
            visitsByDay[calendar.startOfDay(for: visit.arrivedAt), default: []].append(visit)
        }

        // Distance is summed within each day only, so overnight gaps never count as travel.
        var distanceByDay: [Date: Double] = [:]
        let sortedPoints = points.sorted { $0.timestamp < $1.timestamp }
        for (previous, point) in zip(sortedPoints, sortedPoints.dropFirst()) {
            let day = calendar.startOfDay(for: point.timestamp)
            guard calendar.startOfDay(for: previous.timestamp) == day else { continue }
            let a = CLLocation(latitude: previous.latitude, longitude: previous.longitude)
            let b = CLLocation(latitude: point.latitude, longitude: point.longitude)
            distanceByDay[day, default: 0] += a.distance(from: b)
        }
        for point in sortedPoints {
            let day = calendar.startOfDay(for: point.timestamp)
            if distanceByDay[day] == nil { distanceByDay[day] = 0 }
        }

        let recordedDays = Set(visitsByDay.keys).union(distanceByDay.keys)
        let days = dayList(range: range, recordedDays: recordedDays, calendar: calendar)

        var placeDays: [String: Set<Date>] = [:]
        for (day, samples) in visitsByDay {
            for sample in samples { placeDays[sample.placeKey, default: []].insert(day) }
        }

        let summaries = days.map { day in
            let samples = visitsByDay[day] ?? []
            return DaySummary(
                day: day,
                distanceMeters: distanceByDay[day] ?? 0,
                placeCount: Set(samples.map(\.placeKey)).count,
                visitCount: samples.count
            )
        }
        return RangeSummary(days: summaries, placeDayCounts: placeDays.mapValues(\.count))
    }

    /// Every day of a short range, or the recorded days of a long one ("All" can span years).
    static func dayList(range: ClosedRange<Date>, recordedDays: Set<Date>, calendar: Calendar = .current) -> [Date] {
        let last = calendar.startOfDay(for: range.upperBound)
        let first: Date
        if range.lowerBound == .distantPast {
            first = recordedDays.min() ?? last
        } else {
            first = calendar.startOfDay(for: range.lowerBound)
        }
        let span = (calendar.dateComponents([.day], from: first, to: last).day ?? 0) + 1
        guard span <= maximumContinuousDays else {
            return recordedDays.filter { $0 >= first && $0 <= last }.sorted()
        }
        return (0..<max(1, span)).compactMap { calendar.date(byAdding: .day, value: $0, to: first) }
    }

    /// Count bubbles grow with the number of days a place was visited, within readable bounds.
    static func bubbleDiameter(dayCount: Int, maximumDayCount: Int) -> Double {
        guard maximumDayCount > 1 else { return 24 }
        let fraction = Double(max(1, dayCount) - 1) / Double(maximumDayCount - 1)
        return 22 + fraction * 22
    }

    /// Thins one day's path for the faint multi-day layer: at most `limit` points, first and last kept.
    static func thinned<T>(_ items: [T], limit: Int) -> [T] {
        guard items.count > limit, limit >= 2 else { return items }
        let step = Double(items.count - 1) / Double(limit - 1)
        return (0..<limit).map { items[Int((Double($0) * step).rounded())] }
    }
}

// MARK: - Replay

/// Replay speeds: 1× plays a minute of the record each second, 10× ten minutes, 60× an hour.
enum ReplaySpeed: Int, CaseIterable, Identifiable {
    case one = 1, ten = 10, sixty = 60

    var id: Int { rawValue }
    var label: String { "\(rawValue)×" }
    /// Recorded seconds that pass per second of playback.
    var recordedSecondsPerSecond: TimeInterval { Double(rawValue) * 60 }
}

/// The replay playhead: where it is, whether it is running, and how fast.
struct ReplayClock: Equatable {
    var domain: ClosedRange<Date>
    var time: Date
    var isPlaying = false
    var speed: ReplaySpeed = .ten

    init(domain: ClosedRange<Date>, time: Date? = nil, speed: ReplaySpeed = .ten) {
        self.domain = domain
        self.time = min(max(time ?? domain.lowerBound, domain.lowerBound), domain.upperBound)
        self.speed = speed
    }

    var progress: Double {
        let span = domain.upperBound.timeIntervalSince(domain.lowerBound)
        guard span > 0 else { return 1 }
        return min(max(time.timeIntervalSince(domain.lowerBound) / span, 0), 1)
    }

    var isAtEnd: Bool { time >= domain.upperBound }

    /// Starts playing, from the beginning if the playhead had reached the end.
    mutating func play() {
        if isAtEnd { time = domain.lowerBound }
        isPlaying = true
    }

    mutating func pause() { isPlaying = false }

    mutating func togglePlayback() {
        isPlaying ? pause() : play()
    }

    /// Moves the playhead by wall-clock time at the current speed; stops at the end.
    mutating func advance(byWallSeconds seconds: TimeInterval) {
        guard isPlaying, seconds > 0 else { return }
        time = min(time.addingTimeInterval(seconds * speed.recordedSecondsPerSecond), domain.upperBound)
        if isAtEnd { isPlaying = false }
    }

    /// Dragging the playhead pauses playback.
    mutating func scrub(to date: Date) {
        isPlaying = false
        time = min(max(date, domain.lowerBound), domain.upperBound)
    }

    mutating func scrub(toFraction fraction: Double) {
        let span = domain.upperBound.timeIntervalSince(domain.lowerBound)
        scrub(to: domain.lowerBound.addingTimeInterval(span * min(max(fraction, 0), 1)))
    }

    /// With Reduce Motion, Play jumps to the next stop instead of animating: the next arrival after the playhead,
    /// or the end of the range.
    static func jumpTarget(after time: Date, arrivals: [Date], domain: ClosedRange<Date>) -> Date {
        arrivals.sorted().first { $0 > time.addingTimeInterval(1) && domain.contains($0) } ?? domain.upperBound
    }
}

// MARK: - Layer settings

/// Every map layer switch and where it is kept. The older keys are reused as they were.
enum MapLayerPreferences {
    static let visitsKey = "mapLayer.visits"
    static let travelPathKey = "mapLayer.travelPath"
    static let pointMarkersKey = "mapLayer.gpsPoints"
    static let startEndKey = "mapLayer.startEnd"
    static let sessionPathKey = "mapLayer.liveSession"
    static let snapToRoadsKey = "snapTravelPathToRoads"
    static let straightGapsKey = "showStraightLinePathSegments"
    static let photosKey = "showPhotoMarkers" // LocationViewModel.showPhotoMarkersKey
    static let photoImagesKey = PhotoMapMarkerStyle.imagesKey
    static let photoDotsKey = PhotoMapMarkerStyle.dotsKey
    static let suggestionsKey = "showVisitSuggestions" // LocationViewModel.showVisitSuggestionsKey
    static let showOutliersKey = "showOutliers"

    /// Defaults match the old map, except GPS points: the map home starts uncluttered and Replay shows them.
    static let defaults: [String: Bool] = [
        visitsKey: true,
        travelPathKey: true,
        pointMarkersKey: false,
        startEndKey: true,
        sessionPathKey: false,
        snapToRoadsKey: true,
        straightGapsKey: false,
        photosKey: false,
        photoImagesKey: true,
        photoDotsKey: false,
        suggestionsKey: false,
        showOutliersKey: false
    ]

    static func value(for key: String, in defaults: UserDefaults = .standard) -> Bool {
        if defaults.object(forKey: key) == nil { return Self.defaults[key] ?? false }
        return defaults.bool(forKey: key)
    }
}

/// The Photos layer's segmented choice, stored in the two older switches.
enum PhotoMarkerDisplay: String, CaseIterable, Identifiable {
    case images, pins, dots

    var id: String { rawValue }

    /// Dots override the images switch without changing it, as before.
    init(showsImages: Bool, showsDots: Bool) {
        if showsDots { self = .dots } else { self = showsImages ? .images : .pins }
    }

    var showsImages: Bool { self == .images }
    var showsDots: Bool { self == .dots }

    /// The images switch keeps its last value when dots are chosen, so turning dots off restores it.
    func storedFlags(previousImages: Bool) -> (images: Bool, dots: Bool) {
        switch self {
        case .images: (true, false)
        case .pins: (false, false)
        case .dots: (previousImages, true)
        }
    }
}
