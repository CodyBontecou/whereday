import Foundation
import SwiftUI

/// Locale-aware distance formatting shared across all targets.
/// Uses `MeasurementFormatter` so decimal separators, unit names, and
/// abbreviations are automatically adapted for the user's locale.
enum DistanceFormatter {
    private static let formatter: MeasurementFormatter = {
        let f = MeasurementFormatter()
        f.unitOptions = .providedUnit
        f.numberFormatter.maximumFractionDigits = 1
        f.numberFormatter.minimumFractionDigits = 0
        return f
    }()

    private static let wholeNumberFormatter: MeasurementFormatter = {
        let f = MeasurementFormatter()
        f.unitOptions = .providedUnit
        f.numberFormatter.maximumFractionDigits = 0
        return f
    }()

    /// Format a distance in meters using the user's preferred unit system.
    static func format(meters: Double, usesMetric: Bool) -> String {
        if usesMetric {
            if meters >= 1000 {
                let km = Measurement(value: meters / 1000, unit: UnitLength.kilometers)
                return formatter.string(from: km)
            }
            let m = Measurement(value: meters.rounded(), unit: UnitLength.meters)
            return wholeNumberFormatter.string(from: m)
        }

        let miles = meters / 1609.344
        if miles < 0.1 {
            let ft = Measurement(value: (meters * 3.28084).rounded(), unit: UnitLength.feet)
            return wholeNumberFormatter.string(from: ft)
        }
        let mi = Measurement(value: miles, unit: UnitLength.miles)
        return formatter.string(from: mi)
    }
}

/// How a stretch of movement was most likely travelled, inferred from its average speed.
/// Whereday records no motion activity, so this is an estimate from GPS alone.
enum TravelMode: String, Codable, CaseIterable, Sendable {
    case walk
    case run
    case bike
    case drive
    case transit
    case fly

    /// Infers a mode from an average speed in metres per second, or nil when too slow or unknown.
    static func infer(averageSpeed metersPerSecond: Double) -> TravelMode? {
        guard metersPerSecond.isFinite, metersPerSecond > 0.3 else { return nil }
        switch metersPerSecond {
        case ..<2.0: return .walk          // < 7.2 km/h
        case ..<3.4: return .run           // < 12 km/h
        case ..<8.0: return .bike          // < 29 km/h
        case ..<45.0: return .drive        // < 162 km/h
        case ..<70.0: return .transit      // high-speed rail
        default: return .fly
        }
    }

    /// Infers a mode from a distance covered over a duration.
    static func infer(meters: Double, seconds: TimeInterval) -> TravelMode? {
        guard seconds > 0 else { return nil }
        return infer(averageSpeed: meters / seconds)
    }

    /// Title-case label, e.g. "Bike".
    var label: String {
        switch self {
        case .walk: return String(localized: "Walk")
        case .run: return String(localized: "Run")
        case .bike: return String(localized: "Bike")
        case .drive: return String(localized: "Drive")
        case .transit: return String(localized: "Train")
        case .fly: return String(localized: "Flight")
        }
    }

    /// Present-participle label for status kickers, e.g. "Walking".
    var activeLabel: String {
        switch self {
        case .walk: return String(localized: "Walking")
        case .run: return String(localized: "Running")
        case .bike: return String(localized: "Cycling")
        case .drive: return String(localized: "Driving")
        case .transit: return String(localized: "On a train")
        case .fly: return String(localized: "Flying")
        }
    }

    var systemImage: String {
        switch self {
        case .walk: return "figure.walk"
        case .run: return "figure.run"
        case .bike: return "bicycle"
        case .drive: return "car"
        case .transit: return "tram"
        case .fly: return "airplane"
        }
    }
}

/// The design's bundled faces: Newsreader for editorial serif, IBM Plex Mono for data.
/// Registered through `UIAppFonts` in every target; sizes scale with Dynamic Type.
enum WheredayFont {
    static let serifFamily = "Newsreader"
    static let monoFamily = "IBM Plex Mono"

    static func serif(size: CGFloat, relativeTo style: Font.TextStyle = .body) -> Font {
        Font.custom(serifFamily, size: size, relativeTo: style)
    }

    static func serif(_ style: Font.TextStyle) -> Font {
        serif(size: defaultSize(for: style), relativeTo: style)
    }

    static func mono(size: CGFloat, weight: Font.Weight = .regular, relativeTo style: Font.TextStyle = .body) -> Font {
        Font.custom(monoFamily, size: size, relativeTo: style).weight(weight)
    }

    static func mono(_ style: Font.TextStyle, weight: Font.Weight = .regular) -> Font {
        mono(size: defaultSize(for: style), weight: weight, relativeTo: style)
    }

    /// Point sizes of the system text styles at the default content size category.
    static func defaultSize(for style: Font.TextStyle) -> CGFloat {
        switch style {
        case .extraLargeTitle2: return 28
        case .extraLargeTitle: return 36
        case .largeTitle: return 34
        case .title: return 28
        case .title2: return 22
        case .title3: return 20
        case .headline: return 17
        case .body: return 17
        case .callout: return 16
        case .subheadline: return 15
        case .footnote: return 13
        case .caption: return 12
        case .caption2: return 11
        @unknown default: return 17
        }
    }
}
