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
        let entry = IsoMeEntry(date: Date(), data: data)
        completion(entry)
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<IsoMeEntry>) -> Void) {
        let data = SharedLocationData.load() ?? .empty
        let entry = IsoMeEntry(date: Date(), data: data)

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
            CircularWidgetView(data: entry.data)
        case .accessoryRectangular:
            RectangularWidgetView(data: entry.data)
        case .accessoryInline:
            InlineWidgetView(data: entry.data)
        case .accessoryCorner:
            CornerWidgetView(data: entry.data)
        default:
            CircularWidgetView(data: entry.data)
        }
    }
}

// MARK: - Circular Widget (Small circular complication)

struct CircularWidgetView: View {
    let data: SharedLocationData

    var body: some View {
        ZStack {
            AccessoryWidgetBackground()

            VStack(spacing: 2) {
                Image(systemName: statusIcon)
                    .font(.title2)
                    .foregroundStyle(statusColor)

                Text("\(data.todayVisitsCount)")
                    .font(.caption2)
                    .fontWeight(.semibold)
            }
        }
    }

    private var statusIcon: String {
        data.isTrackingEnabled ? "location.fill" : "location.slash"
    }

    private var statusColor: Color {
        data.isTrackingEnabled ? .green : .secondary
    }
}

// MARK: - Rectangular Widget (Larger complication)

struct RectangularWidgetView: View {
    let data: SharedLocationData

    var body: some View {
        HStack(spacing: 8) {
            // Left: Status icon
            VStack {
                Image(systemName: statusIcon)
                    .font(.title2)
                    .foregroundStyle(statusColor)
                Text(data.trackingStatus)
                    .font(.caption2)
            }
            .frame(width: 50)

            // Right: Stats
            VStack(alignment: .leading, spacing: 2) {
                if let locationName = data.currentLocationName {
                    Text(locationName)
                        .font(.caption)
                        .fontWeight(.semibold)
                        .lineLimit(1)
                }

                HStack(spacing: 8) {
                    Label("\(data.todayVisitsCount)", systemImage: "mappin")
                    Label(data.formattedDistance, systemImage: "figure.walk")
                }
                .font(.caption2)

                if data.isTrackingEnabled,
                   let remaining = data.formattedRemainingTime {
                    Text("\(remaining) left")
                        .font(.caption2)
                        .foregroundStyle(.orange)
                }
            }
        }
    }

    private var statusIcon: String {
        data.isTrackingEnabled ? "location.fill" : "location.slash"
    }

    private var statusColor: Color {
        data.isTrackingEnabled ? .green : .secondary
    }
}

// MARK: - Inline Widget (Single line text)

struct InlineWidgetView: View {
    let data: SharedLocationData

    var body: some View {
        if data.isTrackingEnabled {
            Label("\(data.todayVisitsCount) visits • \(data.formattedDistance)", systemImage: "location.fill")
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
            Image(systemName: statusIcon)
                .font(.title3)
                .foregroundStyle(statusColor)
        }
        .widgetLabel {
            if data.isTrackingEnabled {
                Text("\(data.todayVisitsCount) visits")
            } else {
                Text("Off")
            }
        }
    }

    private var statusIcon: String {
        data.isTrackingEnabled ? "location.fill" : "location.slash"
    }

    private var statusColor: Color {
        data.isTrackingEnabled ? .green : .secondary
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
