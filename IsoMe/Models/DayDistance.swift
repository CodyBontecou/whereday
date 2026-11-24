import Foundation
import SwiftData

/// One route fix, as distance needs it.
struct DistanceFix: Equatable, Sendable {
    var timestamp: Date
    var latitude: Double
    var longitude: Double
    /// GPS glitches flagged after the fix was saved (`LocationManager`: an out-and-back spike; speed alone never
    /// flags a fix, so a flight counts) or imported as outliers. They never count, as Today's dial and moves drop them.
    var isOutlier = false
}

extension DistanceFix {
    init(_ point: LocationPoint) {
        self.init(timestamp: point.timestamp, latitude: point.latitude, longitude: point.longitude, isOutlier: point.isOutlier)
    }
}

/// How far the fixes went, by local day. The one source for every distance a day or range shows: Today's
/// "MOVED", the map's day sheet and peek card, the range chips and totals (7D, 30D, All), and the evening
/// recap (daily and weekly).
struct DayDistances: Equatable, Sendable {
    /// Each local day's own path, keyed by its start of day: the straight legs between consecutive kept fixes
    /// that both fall on that day. Every day with a kept fix has an entry, even at 0.
    var days: [Date: Double] = [:]
    /// Legs from one day's last kept fix to a later day's first, keyed by the day they end on. Days start fresh at
    /// midnight, as Today does, so no day counts them; a range total does.
    var crossings: [Date: Double] = [:]

    /// One day's distance: what Today shows for it.
    func meters(on day: Date, calendar: Calendar = .current) -> Double {
        days[calendar.startOfDay(for: day)] ?? 0
    }

    var crossingMeters: Double { crossings.values.reduce(0, +) }

    /// The days' own distances plus the legs across midnight between them.
    var totalMeters: Double { days.values.reduce(0, +) + crossingMeters }
}

enum DayDistance {
    /// Length of one leg on the WGS-84 ellipsoid, in metres (Vincenty's inverse formula).
    ///
    /// Not `CLLocation.distance(from:)`: on iOS it is neither symmetric nor repeatable for long legs (61,356 m one
    /// way and 61,413 m back between San Francisco and Cupertino, and a short leg came out 0.18 m apart between two
    /// calls), so two screens summing the same fixes could disagree. This gives the same metres every time, within
    /// about 0.1% of CLLocation.
    static func leg(_ a: DistanceFix, _ b: DistanceFix) -> Double {
        meters(latitude1: a.latitude, longitude1: a.longitude, latitude2: b.latitude, longitude2: b.longitude)
    }

    static func meters(latitude1: Double, longitude1: Double, latitude2: Double, longitude2: Double) -> Double {
        if latitude1 == latitude2 && longitude1 == longitude2 { return 0 }
        // Order the ends so a leg and its reverse take exactly the same arithmetic.
        let (lat1, lon1, lat2, lon2) = (latitude1, longitude1) <= (latitude2, longitude2)
            ? (latitude1, longitude1, latitude2, longitude2) : (latitude2, longitude2, latitude1, longitude1)
        let a = 6_378_137.0
        let f = 1 / 298.257223563
        let b = (1 - f) * a
        let radians = Double.pi / 180
        let L = (lon2 - lon1) * radians
        let U1 = atan((1 - f) * tan(lat1 * radians))
        let U2 = atan((1 - f) * tan(lat2 * radians))
        let sinU1 = sin(U1), cosU1 = cos(U1), sinU2 = sin(U2), cosU2 = cos(U2)
        var lambda = L
        for _ in 0..<200 {
            let sinLambda = sin(lambda), cosLambda = cos(lambda)
            let sinSigma = sqrt(pow(cosU2 * sinLambda, 2) + pow(cosU1 * sinU2 - sinU1 * cosU2 * cosLambda, 2))
            if sinSigma == 0 { return 0 }
            let cosSigma = sinU1 * sinU2 + cosU1 * cosU2 * cosLambda
            let sigma = atan2(sinSigma, cosSigma)
            let sinAlpha = cosU1 * cosU2 * sinLambda / sinSigma
            let cosSqAlpha = 1 - sinAlpha * sinAlpha
            let cos2SigmaM = cosSqAlpha == 0 ? 0 : cosSigma - 2 * sinU1 * sinU2 / cosSqAlpha
            let C = f / 16 * cosSqAlpha * (4 + f * (4 - 3 * cosSqAlpha))
            let previous = lambda
            lambda = L + (1 - C) * f * sinAlpha
                * (sigma + C * sinSigma * (cos2SigmaM + C * cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)))
            if abs(lambda - previous) < 1e-12 {
                let uSq = cosSqAlpha * (a * a - b * b) / (b * b)
                let A = 1 + uSq / 16_384 * (4_096 + uSq * (-768 + uSq * (320 - 175 * uSq)))
                let B = uSq / 1_024 * (256 + uSq * (-128 + uSq * (74 - 47 * uSq)))
                let deltaSigma = B * sinSigma * (cos2SigmaM + B / 4 * (cosSigma * (-1 + 2 * cos2SigmaM * cos2SigmaM)
                    - B / 6 * cos2SigmaM * (-3 + 4 * sinSigma * sinSigma) * (-3 + 4 * cos2SigmaM * cos2SigmaM)))
                return b * A * (sigma - deltaSigma)
            }
        }
        // Nearly antipodal ends don't converge: a great circle on the mean radius is close enough.
        let dLat = (lat2 - lat1) * radians
        let h = pow(sin(dLat / 2), 2) + cos(lat1 * radians) * cos(lat2 * radians) * pow(sin(L / 2), 2)
        return 2 * 6_371_008.8 * asin(min(1, sqrt(h)))
    }

    /// The fixes in time order (ties keep their given order), outliers dropped, split at local midnight.
    static func distances(_ fixes: [DistanceFix], calendar: Calendar = .current) -> DayDistances {
        var accumulator = DayDistanceAccumulator(calendar: calendar)
        for fix in ordered(fixes) { accumulator.add(fix) }
        return accumulator.distances
    }

    static func distances(_ points: [LocationPoint], calendar: Calendar = .current) -> DayDistances {
        distances(points.map(DistanceFix.init), calendar: calendar)
    }

    /// Kept fixes in time order; equal timestamps keep their given order.
    static func ordered(_ fixes: [DistanceFix]) -> [DistanceFix] {
        let kept = fixes.filter { !$0.isOutlier }
        guard zip(kept, kept.dropFirst()).contains(where: { $0.timestamp > $1.timestamp }) else { return kept }
        return kept.enumerated()
            .sorted { $0.element.timestamp == $1.element.timestamp ? $0.offset < $1.offset : $0.element.timestamp < $1.element.timestamp }
            .map(\.element)
    }
}

/// Streams fixes in time order into `DayDistances` without keeping them (Android's `DayDistanceAccumulator`).
struct DayDistanceAccumulator: Sendable {
    let calendar: Calendar
    private(set) var distances = DayDistances()
    /// The last kept fix and its day.
    private(set) var last: DistanceFix?
    private var lastDay: Date?

    init(calendar: Calendar = .current) {
        self.calendar = calendar
    }

    /// Adds the next fix. Outliers are skipped; a fix older than the last one is a caller error and is skipped too,
    /// so callers that can't promise order recompute with `DayDistance.distances`.
    mutating func add(_ fix: DistanceFix) {
        guard !fix.isOutlier else { return }
        if let last, fix.timestamp < last.timestamp { return }
        let day = calendar.startOfDay(for: fix.timestamp)
        if distances.days[day] == nil { distances.days[day] = 0 }
        if let last {
            let meters = DayDistance.leg(last, fix)
            if lastDay == day {
                distances.days[day, default: 0] += meters
            } else {
                distances.crossings[day, default: 0] += meters
            }
        }
        last = fix
        lastDay = day
    }
}

/// Reads a range's distances from every stored fix, off the main thread, for ranges too long to load whole
/// (the map loads at most 10,000 fixes and draws 2,500).
@ModelActor
actor DayDistanceReader {
    func distances(in range: ClosedRange<Date>, calendar: Calendar) -> DayDistances? {
        let start = range.lowerBound
        let end = range.upperBound
        var accumulator = DayDistanceAccumulator(calendar: calendar)
        let batchSize = 5_000
        var offset = 0
        do {
            while true {
                var descriptor = FetchDescriptor<LocationPoint>(predicate: #Predicate {
                    $0.timestamp >= start && $0.timestamp <= end && !$0.isOutlier
                })
                descriptor.sortBy = [SortDescriptor(\.timestamp, order: .forward)]
                descriptor.fetchOffset = offset
                descriptor.fetchLimit = batchSize
                let batch = try modelContext.fetch(descriptor)
                for point in batch { accumulator.add(DistanceFix(point)) }
                guard batch.count == batchSize else { break }
                offset += batchSize
                // Release the batch's models before the next fetch.
                modelContext.rollback()
            }
        } catch {
            return nil
        }
        return accumulator.distances
    }
}
