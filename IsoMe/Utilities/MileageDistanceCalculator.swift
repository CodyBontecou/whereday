import Foundation

/// Value input deliberately independent of SwiftData and LocationManager.
struct MileageDistanceSample {
    let timestamp: Date
    let latitude: Double
    let longitude: Double
    let horizontalAccuracy: Double
    var isOutlier: Bool = false
}

enum MileageDistanceCalculator {
    struct Configuration {
        var maximumAccuracyMeters: Double = 100
        var maximumSpeedMetersPerSecond: Double = 70
        var maximumGapSeconds: TimeInterval = 120
    }

    struct Result {
        let rawDistanceMeters: Double
        let filteredDistanceMeters: Double
        let rejectedSampleCount: Int
        let gapCount: Int
    }

    /// Sorts fixes stably by time. Raw distance includes valid-coordinate GPS spikes and gaps;
    /// filtered distance never fills a gap. Poor/invalid fixes break continuity. Speed spikes
    /// and explicitly marked outliers do not replace the last good anchor, allowing recovery.
    /// Duplicate timestamps contribute no distance. No odometer or road-routing claims.
    static func calculate(_ samples: [MileageDistanceSample],
                          configuration: Configuration = Configuration()) throws -> Result {
        guard configuration.maximumAccuracyMeters.isFinite, configuration.maximumAccuracyMeters > 0,
              configuration.maximumSpeedMetersPerSecond.isFinite, configuration.maximumSpeedMetersPerSecond > 0,
              configuration.maximumGapSeconds.isFinite, configuration.maximumGapSeconds > 0 else {
            throw MileageValidationError.invalidDistanceConfiguration
        }
        // Non-finite dates cannot participate in a strict chronological ordering.
        guard samples.allSatisfy({ $0.timestamp.timeIntervalSince1970.isFinite }) else {
            throw MileageValidationError.invalidDateRange
        }
        let ordered = samples.enumerated().sorted {
            if $0.element.timestamp == $1.element.timestamp { return $0.offset < $1.offset }
            return $0.element.timestamp < $1.element.timestamp
        }.map(\.element)
        var raw = 0.0
        var filtered = 0.0
        var rejected = 0
        var gaps = 0
        var previousRaw: MileageDistanceSample?
        var anchor: MileageDistanceSample?
        for sample in ordered {
            guard validCoordinate(sample), sample.timestamp.timeIntervalSince1970.isFinite else {
                rejected += 1
                previousRaw = nil
                anchor = nil
                continue
            }
            if let previousRaw, sample.timestamp > previousRaw.timestamp {
                raw += distance(previousRaw, sample)
            }
            previousRaw = sample
            guard sample.horizontalAccuracy.isFinite, sample.horizontalAccuracy >= 0,
                  sample.horizontalAccuracy <= configuration.maximumAccuracyMeters else {
                rejected += 1
                anchor = nil
                continue
            }
            guard !sample.isOutlier else { rejected += 1; continue }
            guard let previous = anchor else { anchor = sample; continue }
            let interval = sample.timestamp.timeIntervalSince(previous.timestamp)
            guard interval > 0 else { rejected += 1; continue }
            if interval > configuration.maximumGapSeconds {
                gaps += 1
                anchor = sample
                continue
            }
            let meters = distance(previous, sample)
            guard meters / interval <= configuration.maximumSpeedMetersPerSecond else {
                rejected += 1
                continue
            }
            filtered += meters
            anchor = sample
        }
        return Result(rawDistanceMeters: raw, filteredDistanceMeters: filtered,
                      rejectedSampleCount: rejected, gapCount: gaps)
    }

    private static func validCoordinate(_ sample: MileageDistanceSample) -> Bool {
        sample.latitude.isFinite && sample.longitude.isFinite &&
            (-90...90).contains(sample.latitude) && (-180...180).contains(sample.longitude)
    }

    /// Great-circle estimate with mean Earth radius, including antimeridian crossings.
    private static func distance(_ a: MileageDistanceSample, _ b: MileageDistanceSample) -> Double {
        let radians = Double.pi / 180
        let dLat = (b.latitude - a.latitude) * radians
        let dLon = (b.longitude - a.longitude) * radians
        let h = pow(sin(dLat / 2), 2) + cos(a.latitude * radians) * cos(b.latitude * radians) * pow(sin(dLon / 2), 2)
        return 6_371_008.8 * 2 * asin(sqrt(min(1, max(0, h))))
    }
}
