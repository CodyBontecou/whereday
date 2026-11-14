import Foundation

struct VisitInferenceOptions: Sendable, Equatable {
    var radiusMeters = 150.0
    var minimumDwellSeconds = 900.0
    var maximumAccuracyMeters = 100.0
    var maximumGapSeconds = 300.0

    func validate() throws {
        guard radiusMeters.isFinite, (25...500).contains(radiusMeters),
              minimumDwellSeconds.isFinite, (300...7_200).contains(minimumDwellSeconds),
              maximumAccuracyMeters.isFinite, (1...500).contains(maximumAccuracyMeters),
              maximumGapSeconds.isFinite, (30...900).contains(maximumGapSeconds),
              maximumGapSeconds <= minimumDwellSeconds else { throw VisitInferenceError.invalidOptions }
    }
}

enum VisitInferenceError: LocalizedError {
    case invalidOptions, reviewRequired
    var errorDescription: String? {
        switch self {
        case .invalidOptions: return String(localized: "Choose valid inference limits: radius 25–500 m, dwell 5–120 minutes, accuracy 1–500 m, and gap 30–900 seconds no longer than dwell.")
        case .reviewRequired: return String(localized: "Review the updated inference and accuracy counts before importing.")
        }
    }
}

/// Pure, local and deterministic. Timestamps are observations, never interpolated.
/// Unknown/poor accuracy, outliers and covered semantic windows break evidence continuity.
/// Four distinct observations are required; two distant endpoints never establish a dwell.
enum VisitInferenceService {
    static func infer(points: [ImportedLocationPoint], options: VisitInferenceOptions = .init(),
                      excluding windows: [ClosedRange<Date>] = []) throws -> [ImportedVisit] {
        try options.validate()
        try Task.checkCancellation()
        guard points.count <= ImportPayload.maximumRecordCount else { throw TimelineImportError.tooManyRecords }
        let ordered = points.sorted {
            if $0.timestamp != $1.timestamp { return $0.timestamp < $1.timestamp }
            if $0.latitude != $1.latitude { return $0.latitude < $1.latitude }
            if $0.longitude != $1.longitude { return $0.longitude < $1.longitude }
            return $0.horizontalAccuracy < $1.horizontalAccuracy
        }
        // Merge coverage once, then advance monotonically instead of checking every window per point.
        var coverage: [ClosedRange<Date>] = []
        for window in windows.sorted(by: { $0.lowerBound < $1.lowerBound }) {
            if let last = coverage.last, window.lowerBound <= last.upperBound {
                coverage[coverage.count - 1] = last.lowerBound...max(last.upperBound, window.upperBound)
            } else { coverage.append(window) }
        }
        struct Dwell {
            var points: [ImportedLocationPoint]
            let session: Int
            var mergedPair = false
        }
        var dwells: [Dwell] = [], cluster: [ImportedLocationPoint] = []
        var session = 0, coverageIndex = 0
        var previous: ImportedLocationPoint?
        func finish() {
            let tight: Bool
            if cluster.isEmpty { tight = false }
            else {
                let center = centroid(cluster)
                tight = cluster.allSatisfy { meters($0, center) <= options.radiusMeters }
            }
            if cluster.count >= 4, let first = cluster.first, let last = cluster.last,
               last.timestamp.timeIntervalSince(first.timestamp) >= options.minimumDwellSeconds,
               meters(first, last) <= options.radiusMeters / 2, tight {
                dwells.append(Dwell(points: cluster, session: session))
            } else if !cluster.isEmpty {
                // Discarded movement evidence must not disappear between two merged dwells.
                session += 1
            }
            cluster.removeAll(keepingCapacity: true)
        }
        func breakSession() { finish(); session += 1; previous = nil }
        for (index, point) in ordered.enumerated() {
            if index.isMultiple(of: 500) { try Task.checkCancellation() }
            while coverageIndex < coverage.count, coverage[coverageIndex].upperBound < point.timestamp { coverageIndex += 1 }
            let covered = coverageIndex < coverage.count && coverage[coverageIndex].contains(point.timestamp)
            guard point.latitude.isFinite, point.longitude.isFinite,
                  (-90...90).contains(point.latitude), (-180...180).contains(point.longitude),
                  GoogleTimelineImportService.validDate(point.timestamp),
                  point.horizontalAccuracy.isFinite, point.horizontalAccuracy >= 0,
                  point.horizontalAccuracy <= options.maximumAccuracyMeters, !point.isOutlier, !covered else {
                breakSession(); continue
            }
            if let previous {
                let elapsed = point.timestamp.timeIntervalSince(previous.timestamp)
                if elapsed == 0 {
                    // Repeated samples do not increase dwell confidence. Conflicts break it.
                    if meters(previous, point) > 1 { breakSession() }
                    continue
                }
                if elapsed > options.maximumGapSeconds { breakSession() }
                else if meters(previous, point) / elapsed > 40 {
                    // Do not seed a new dwell with a teleport endpoint.
                    breakSession(); continue
                }
            }
            if let anchor = cluster.first, meters(anchor, point) > options.radiusMeters { finish() }
            cluster.append(point)
            previous = point
        }
        finish()
        var merged: [Dwell] = []
        for (index, dwell) in dwells.enumerated() {
            if index.isMultiple(of: 100) { try Task.checkCancellation() }
            if let last = merged.last, !last.mergedPair, last.session == dwell.session,
               let end = last.points.last, let start = dwell.points.first,
               start.timestamp.timeIntervalSince(end.timestamp) <= min(120, options.maximumGapSeconds),
               meters(centroid(last.points), centroid(dwell.points)) <= options.radiusMeters / 2 {
                let together = last.points + dwell.points
                let center = centroid(together)
                if together.allSatisfy({ meters($0, center) <= options.radiusMeters }),
                   meters(together[0], together[together.count - 1]) <= options.radiusMeters {
                    merged[merged.count - 1].points = together
                    // Pairwise only: no unbounded chaining through a moving cloud, or quadratic rescans.
                    merged[merged.count - 1].mergedPair = true
                    continue
                }
            }
            merged.append(dwell)
        }
        return merged.compactMap { dwell in
            let first = dwell.points[0], last = dwell.points[dwell.points.count - 1]
            // A semantic visit between samples must also prevent a spanning inferred visit.
            var low = 0, high = coverage.count
            while low < high {
                let middle = (low + high) / 2
                if coverage[middle].upperBound < first.timestamp { low = middle + 1 } else { high = middle }
            }
            guard low == coverage.count || coverage[low].lowerBound > last.timestamp else { return nil }
            let center = centroid(dwell.points)
            return ImportedVisit(latitude: center.latitude, longitude: center.longitude,
                                 arrivedAt: first.timestamp, departedAt: last.timestamp,
                                 locationName: nil, address: nil, notes: nil, sourceRaw: "imported", placeSourceRaw: "import")
        }
    }

    /// Inverse-accuracy spherical mean is defensible only for accepted, tight clusters.
    /// A 1 m floor avoids infinite weight at zero accuracy; spherical coordinates handle the date line.
    private static func centroid(_ points: [ImportedLocationPoint]) -> ImportedLocationPoint {
        var x = 0.0, y = 0.0, z = 0.0
        for point in points {
            let lat = point.latitude * .pi / 180, lon = point.longitude * .pi / 180
            let weight = 1 / max(1, point.horizontalAccuracy)
            x += cos(lat) * cos(lon) * weight; y += cos(lat) * sin(lon) * weight; z += sin(lat) * weight
        }
        return ImportedLocationPoint(latitude: atan2(z, hypot(x, y)) * 180 / .pi,
                                     longitude: atan2(y, x) * 180 / .pi, timestamp: points[0].timestamp,
                                     altitude: nil, speed: nil, horizontalAccuracy: -1, isOutlier: false)
    }

    private static func meters(_ a: ImportedLocationPoint, _ b: ImportedLocationPoint) -> Double {
        let lat1 = a.latitude * .pi / 180, lat2 = b.latitude * .pi / 180
        let dlat = lat2 - lat1, dlon = (b.longitude - a.longitude) * .pi / 180
        let h = pow(sin(dlat / 2), 2) + cos(lat1) * cos(lat2) * pow(sin(dlon / 2), 2)
        return 6_371_000 * 2 * asin(sqrt(min(1, max(0, h))))
    }
}
