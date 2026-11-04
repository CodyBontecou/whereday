import WidgetKit
import SwiftUI

// MARK: - Timeline Provider

struct IsoMeProvider: TimelineProvider {
    func placeholder(in context: Context) -> IsoMeEntry {
        IsoMeEntry(
            date: Date(),
            data: SharedLocationData(
                isTrackingEnabled: true,
                currentLocationName: "Home",
                currentAddress: nil,
                lastLatitude: nil,
                lastLongitude: nil,
                lastUpdateTime: Date(),
                todayVisitsCount: 5,
                todayDistanceMeters: 2500,
                todayPointsCount: 0,
                trackingStartTime: nil,
                stopAfterHours: nil
            )
        )
    }

    func getSnapshot(in context: Context, completion: @escaping (IsoMeEntry) -> Void) {
        let data = SharedLocationData.load() ?? .empty
        let entry = IsoMeEntry(date: Date(), data: data, today: WatchTodayContext.load())
        completion(entry)
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<IsoMeEntry>) -> Void) {
        let data = SharedLocationData.load() ?? .empty
        let entry = IsoMeEntry(date: Date(), data: data, today: WatchTodayContext.load())

        // Refresh more often when actively tracking
        let refreshInterval: TimeInterval = data.isTrackingEnabled ? 300 : 900
        let nextUpdate = Date().addingTimeInterval(refreshInterval)

        let timeline = Timeline(entries: [entry], policy: .after(nextUpdate))
        completion(timeline)
    }
}

// MARK: - Timeline Entry

struct IsoMeEntry: TimelineEntry {
    let date: Date
    let data: SharedLocationData
    /// Today's stays from the iPhone, when the watch has received them.
    var today: WatchTodayContext? = nil
}

// MARK: - Widget Definition

struct IsoMeWatchWidget: Widget {
    let kind: String = "IsoMeWatchWidget"


    var body: some WidgetConfiguration {
        StaticConfiguration(kind: kind, provider: IsoMeProvider()) { entry in
            IsoMeWidgetEntryView(entry: entry)
                .containerBackground(.fill.tertiary, for: .widget)
        }
        .configurationDisplayName("Whereday")
        .description("View your tracking status and today's stats.")
        .supportedFamilies([
            .accessoryCircular,
            .accessoryRectangular,
            .accessoryInline,
            .accessoryCorner
        ])
    }
}

// MARK: - Widget Views

struct IsoMeWidgetEntryView: View {
    @Environment(\.widgetFamily) var family
    let entry: IsoMeEntry

    var body: some View {
        switch family {
        case .accessoryCircular:
            CircularWidgetView(data: entry.data, date: entry.date, today: entry.today)
        case .accessoryRectangular:
            RectangularWidgetView(data: entry.data)
        case .accessoryInline:
            InlineWidgetView(data: entry.data)
        case .accessoryCorner:
            CornerWidgetView(data: entry.data)
        default:
            CircularWidgetView(data: entry.data, date: entry.date, today: entry.today)
        }
    }
}

// MARK: - Palette

/// Whereday's night inks for watch complications. Accessory families may be tinted by the face,
/// so rust parts are marked accentable.
private enum WatchWidgetTE {
    static let textPrimary = hex(0xECE6D8)
    static let textMuted = hex(0x9AA3B8)
    static let inkRust = hex(0xF0805F)
    static let dialTrack = hex(0x222C46)
    /// Per-place inks, in the order the iPhone assigns them (same as the watch app's dial).
    static let placeInks: [Color] = [hex(0x8FA6D9), inkRust, hex(0xE0B565), hex(0x9DC097)]

    /// IBM Plex Mono, bundled and registered in this extension.
    static func mono(_ style: Font.TextStyle, weight: Font.Weight = .regular) -> Font {
        WheredayFont.mono(style, weight: weight)
    }

    /// Newsreader, bundled and registered in this extension.
    static func editorial(_ style: Font.TextStyle) -> Font {
        WheredayFont.serif(style)
    }

    private static func hex(_ value: UInt32) -> Color {
        Color(
            red: Double((value >> 16) & 0xFF) / 255,
            green: Double((value >> 8) & 0xFF) / 255,
            blue: Double(value & 0xFF) / 255
        )
    }
}

private func dayFraction(of date: Date) -> Double {
    let start = Calendar.current.startOfDay(for: date)
    return min(max(date.timeIntervalSince(start) / 86_400, 0), 1)
}

/// Splits "6.4 km" into its number and unit so the number can lead.
private func distanceParts(_ formatted: String) -> (value: String, unit: String) {
    guard let space = formatted.lastIndex(where: { $0 == " " || $0 == "\u{00A0}" || $0 == "\u{202F}" }) else {
        return (formatted, "")
    }
    return (String(formatted[..<space]), String(formatted[formatted.index(after: space)...]))
}

// MARK: - Circular Widget (the day dial)

struct CircularWidgetView: View {
    let data: SharedLocationData
    var date: Date = Date()
    /// Today's stays from the iPhone; drawn as per-place arcs like the watch app's dial.
    var today: WatchTodayContext? = nil

    /// The watch's own distance today, or the iPhone's day total when that's further.
    private var distanceText: String {
        let meters = max(data.todayDistanceMeters, today?.distance(on: date) ?? 0)
        return DistanceFormatter.format(meters: meters, usesMetric: data.prefersMetricDistanceUnits)
    }

    var body: some View {
        let parts = distanceParts(distanceText)
        let hasToday = today?.isSameDay(as: date) ?? false
        let arcs = today?.arcs(now: date) ?? []

        ZStack {
            AccessoryWidgetBackground()

            Circle()
                .inset(by: 4)
                .stroke(WatchWidgetTE.dialTrack, lineWidth: 4)

            if hasToday {
                ForEach(Array(arcs.enumerated()), id: \.offset) { _, arc in
                    Circle()
                        .inset(by: 4)
                        .trim(from: arc.startFraction, to: arc.endFraction)
                        .stroke(WatchWidgetTE.placeInks[arc.inkIndex % WatchWidgetTE.placeInks.count],
                                style: StrokeStyle(lineWidth: 4, lineCap: .butt))
                        .rotationEffect(.degrees(-90))
                }

                // "Now" tick, accentable so tinted faces still show where the day is.
                Circle()
                    .inset(by: 4)
                    .trim(from: max(0, dayFraction(of: date) - 0.006), to: dayFraction(of: date))
                    .stroke(WatchWidgetTE.inkRust, style: StrokeStyle(lineWidth: 6, lineCap: .butt))
                    .rotationEffect(.degrees(-90))
                    .widgetAccentable()
            } else {
                Circle()
                    .inset(by: 4)
                    .trim(from: 0, to: max(0.01, dayFraction(of: date)))
                    .stroke(data.isTrackingEnabled ? WatchWidgetTE.inkRust : WatchWidgetTE.textMuted,
                            style: StrokeStyle(lineWidth: 4, lineCap: .butt))
                    .rotationEffect(.degrees(-90))
                    .widgetAccentable()
            }

            VStack(spacing: 0) {
                Text(parts.value)
                    .font(WatchWidgetTE.editorial(.body))
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                Text(parts.unit.uppercased())
                    .font(WatchWidgetTE.mono(.caption2))
                    .foregroundStyle(WatchWidgetTE.textMuted)
            }
            .padding(.horizontal, 9)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(localized: "\(data.trackingStatus), \(distanceText) today"))
    }
}

// MARK: - Rectangular Widget (Larger complication)

struct RectangularWidgetView: View {
    let data: SharedLocationData

    var body: some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(data.isTrackingEnabled ? "● ROUTE" : "WHEREDAY")
                .font(WatchWidgetTE.mono(.caption2, weight: .medium))
                .tracking(1)
                .foregroundStyle(data.isTrackingEnabled ? WatchWidgetTE.inkRust : WatchWidgetTE.textMuted)
                .widgetAccentable()

            Text(data.formattedDistance)
                .font(WatchWidgetTE.editorial(.title3))
                .lineLimit(1)
                .minimumScaleFactor(0.7)

            Group {
                if data.isTrackingEnabled, let remaining = data.formattedRemainingTime {
                    Text("\(data.todayVisitsCount) VISITS · \(remaining) LEFT")
                } else {
                    Text("\(data.todayVisitsCount) VISITS · \(data.todayPointsCount) PTS")
                }
            }
            .font(WatchWidgetTE.mono(.caption2))
            .foregroundStyle(WatchWidgetTE.textMuted)
            .lineLimit(1)
            .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
    }
}

// MARK: - Inline Widget (Single line text)

struct InlineWidgetView: View {
    let data: SharedLocationData

    var body: some View {
        if data.isTrackingEnabled {
            Label("\(data.todayVisitsCount) visits • \(data.formattedDistance)", systemImage: "record.circle")
        } else {
            Label("Tracking Off", systemImage: "location.slash")
        }
    }
}

// MARK: - Corner Widget (Shown in corner of watch face)

struct CornerWidgetView: View {
    let data: SharedLocationData

    var body: some View {
        ZStack {
            Image(systemName: data.isTrackingEnabled ? "record.circle" : "location.slash")
                .font(.title3)
                .foregroundStyle(data.isTrackingEnabled ? WatchWidgetTE.inkRust : WatchWidgetTE.textMuted)
                .widgetAccentable()
        }
        .widgetLabel {
            if data.isTrackingEnabled {
                Text(data.formattedDistance)
            } else {
                Text("Off")
            }
        }
    }
}

// MARK: - Previews

#Preview("Circular - Active", as: .accessoryCircular) {
    IsoMeWatchWidget()
} timeline: {
    IsoMeEntry(date: .now, data: SharedLocationData(
        isTrackingEnabled: true,
        currentLocationName: "Coffee Shop",
        currentAddress: nil,
        lastLatitude: nil,
        lastLongitude: nil,
        lastUpdateTime: .now,
        todayVisitsCount: 3,
        todayDistanceMeters: 1500,
        todayPointsCount: 42,
        trackingStartTime: Date().addingTimeInterval(-1800),
        stopAfterHours: 2
    ))
}

#Preview("Rectangular - Active", as: .accessoryRectangular) {
    IsoMeWatchWidget()
} timeline: {
    IsoMeEntry(date: .now, data: SharedLocationData(
        isTrackingEnabled: true,
        currentLocationName: "Coffee Shop",
        currentAddress: nil,
        lastLatitude: nil,
        lastLongitude: nil,
        lastUpdateTime: .now,
        todayVisitsCount: 3,
        todayDistanceMeters: 1500,
        todayPointsCount: 42,
        trackingStartTime: Date().addingTimeInterval(-1800),
        stopAfterHours: 2
    ))
}

#Preview("Inline - Tracking", as: .accessoryInline) {
    IsoMeWatchWidget()
} timeline: {
    IsoMeEntry(date: .now, data: SharedLocationData(
        isTrackingEnabled: true,
        currentLocationName: nil,
        currentAddress: nil,
        lastLatitude: nil,
        lastLongitude: nil,
        lastUpdateTime: .now,
        todayVisitsCount: 5,
        todayDistanceMeters: 3200,
        todayPointsCount: 0,
        trackingStartTime: nil,
        stopAfterHours: nil
    ))
}

#Preview("Corner - Off", as: .accessoryCorner) {
    IsoMeWatchWidget()
} timeline: {
    IsoMeEntry(date: .now, data: SharedLocationData(
        isTrackingEnabled: false,
        currentLocationName: nil,
        currentAddress: nil,
        lastLatitude: nil,
        lastLongitude: nil,
        lastUpdateTime: nil,
        todayVisitsCount: 0,
        todayDistanceMeters: 0,
        todayPointsCount: 0,
        trackingStartTime: nil,
        stopAfterHours: nil
    ))
}
