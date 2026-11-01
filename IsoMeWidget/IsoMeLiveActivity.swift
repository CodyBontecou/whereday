import ActivityKit
import WidgetKit
import SwiftUI

struct IsoMeLiveActivity: Widget {
    var body: some WidgetConfiguration {
        ActivityConfiguration(for: LocationActivityAttributes.self) { context in
            // Lock Screen / StandBy UI
            LockScreenView(context: context)
        } dynamicIsland: { context in
            let model = LiveActivityModel(context: context)

            return DynamicIsland {
                DynamicIslandExpandedRegion(.leading) {
                    WidgetDayDial(
                        stays: model.day.stays,
                        inks: WidgetTE.Night.placeInks,
                        track: WidgetTE.Night.dialTrack,
                        lineWidthRatio: 0.16,
                        nowFraction: model.day.nowFraction
                    )
                    .frame(width: 40, height: 40)
                    .padding(.leading, 4)
                }

                DynamicIslandExpandedRegion(.trailing) {
                    Text(model.distance)
                        .font(WidgetTE.mono(.footnote, weight: .medium))
                        .foregroundStyle(WidgetTE.Night.inkRust)
                        .lineLimit(1)
                        .padding(.trailing, 4)
                }

                DynamicIslandExpandedRegion(.center) {
                    VStack(alignment: .leading, spacing: 2) {
                        Text("● RECORDING")
                            .font(WidgetTE.mono(.caption2, weight: .medium))
                            .tracking(1.2)
                            .foregroundStyle(WidgetTE.Night.inkRust)
                        Text(model.routeTitle)
                            .font(WidgetTE.editorial(.headline))
                            .foregroundStyle(WidgetTE.Night.textPrimary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.75)
                        Text(model.expandedDetail)
                            .font(WidgetTE.mono(.caption2))
                            .foregroundStyle(WidgetTE.Night.textMuted)
                            .lineLimit(1)
                    }
                    .frame(maxWidth: .infinity, alignment: .leading)
                }

                DynamicIslandExpandedRegion(.bottom) {
                    HStack(spacing: 8) {
                        if let url = model.markPlaceURL {
                            Link(destination: url) {
                                LiveActivityButtonLabel(title: "Mark this place", filled: false, night: true)
                            }
                        }
                        if let url = IsoMeURLs.stopTracking {
                            Link(destination: url) {
                                LiveActivityButtonLabel(title: "Stop tracking", filled: true, night: true)
                            }
                        }
                    }
                    .padding(.top, 4)
                }
            } compactLeading: {
                RecordingRing(fraction: model.day.nowFraction)
                    .frame(width: 20, height: 20)
                    .accessibilityLabel("Recording")
            } compactTrailing: {
                Text(model.distance)
                    .font(WidgetTE.mono(.footnote))
                    .foregroundStyle(WidgetTE.Night.inkRust)
                    .lineLimit(1)
                    .frame(maxWidth: 64)
            } minimal: {
                RecordingRing(fraction: model.day.nowFraction)
                    .frame(width: 20, height: 20)
                    .accessibilityLabel("Recording")
            }
            .keylineTint(WidgetTE.Night.inkRust)
        }
    }
}

// MARK: - Model

/// What the Live Activity shows, derived from the activity state and today's widget snapshot.
private struct LiveActivityModel {
    let state: LocationActivityAttributes.ContentState
    let startTime: Date
    let day: WidgetDay

    init(context: ActivityViewContext<LocationActivityAttributes>) {
        state = context.state
        startTime = context.attributes.startTime
        day = WidgetDay(snapshot: TodayWidgetSnapshot.load(), now: context.state.lastUpdate)
    }

    var distance: String {
        DistanceFormatter.format(
            meters: state.distanceTraveled,
            usesMetric: state.usesMetricDistanceUnits ?? true
        )
    }

    /// "Origin → current" once the recording has passed through two named places.
    var routeTitle: String {
        let current = state.locationName ?? day.currentStay?.placeName
        if let origin = state.originLocationName, let current, origin != current {
            return "\(origin) → \(current)"
        }
        return current ?? String(localized: "Tracking")
    }

    var points: String {
        state.locationsRecorded.formatted()
    }

    var remaining: String? {
        state.remainingSeconds.map(Self.formatTime)
    }

    var expandedDetail: String {
        if let remaining {
            return String(localized: "\(points) PTS · \(remaining) LEFT")
        }
        return String(localized: "\(points) PTS")
    }

    var markPlaceURL: URL? {
        IsoMeURLs.checkIn(latitude: state.latitude, longitude: state.longitude)
    }

    static func formatTime(_ seconds: Int) -> String {
        let hours = seconds / 3600
        let minutes = (seconds % 3600) / 60
        if hours > 0 {
            return String(localized: "\(hours)h \(minutes)m")
        }
        return String(localized: "\(minutes)m")
    }
}

// MARK: - Lock Screen View

struct LockScreenView: View {
    let context: ActivityViewContext<LocationActivityAttributes>

    private var model: LiveActivityModel { LiveActivityModel(context: context) }

    var body: some View {
        let model = self.model

        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .center, spacing: 12) {
                WidgetDayDial(
                    stays: model.day.stays,
                    inks: WidgetTE.placeInks,
                    track: WidgetTE.dialTrack,
                    lineWidthRatio: 0.13,
                    nowFraction: model.day.nowFraction,
                    nowInk: WidgetTE.inkRust
                )
                .frame(width: 46, height: 46)
                .accessibilityElement()
                .accessibilityLabel(String(localized: "Today's dial: \(model.day.accessibilitySummary)"))

                VStack(alignment: .leading, spacing: 1) {
                    Text("● RECORDING")
                        .font(WidgetTE.mono(.caption2, weight: .medium))
                        .tracking(1.2)
                        .foregroundStyle(WidgetTE.inkRust)
                    Text(model.routeTitle)
                        .font(WidgetTE.editorial(.title3))
                        .foregroundStyle(WidgetTE.textPrimary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Text(verbatim: "Whereday")
                    .font(WidgetTE.mono(.caption2))
                    .foregroundStyle(WidgetTE.textMuted)
                    .frame(maxHeight: .infinity, alignment: .top)
            }
            .fixedSize(horizontal: false, vertical: true)

            VStack(spacing: 10) {
                Rectangle()
                    .fill(WidgetTE.border)
                    .frame(height: 1)

                HStack(alignment: .top, spacing: 8) {
                    stat(label: "MOVED") { Text(model.distance) }
                    stat(label: "POINTS") { Text(model.points) }
                    if let remaining = model.remaining {
                        stat(label: "UNTIL AUTO-OFF") { Text(remaining) }
                    } else {
                        stat(label: "RECORDING FOR") {
                            Text(context.attributes.startTime, style: .timer)
                        }
                    }
                }
            }

            HStack(spacing: 8) {
                if let url = model.markPlaceURL {
                    Link(destination: url) {
                        LiveActivityButtonLabel(title: "Mark this place", filled: false, night: false)
                    }
                }
                if let url = IsoMeURLs.stopTracking {
                    Link(destination: url) {
                        LiveActivityButtonLabel(title: "Stop tracking", filled: true, night: false)
                    }
                }
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 16)
        .padding(.bottom, 14)
        .activityBackgroundTint(WidgetTE.surface.opacity(0.94))
        .activitySystemActionForegroundColor(WidgetTE.textPrimary)
    }

    private func stat<Value: View>(label: LocalizedStringKey, @ViewBuilder value: () -> Value) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            value()
                .font(WidgetTE.editorial(.title3))
                .monospacedDigit()
                .foregroundStyle(WidgetTE.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(label)
                .font(WidgetTE.mono(.caption2))
                .foregroundStyle(WidgetTE.textMuted)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Pieces

private struct LiveActivityButtonLabel: View {
    let title: LocalizedStringKey
    let filled: Bool
    let night: Bool

    var body: some View {
        Text(title)
            .font(.subheadline.weight(.medium))
            .lineLimit(1)
            .minimumScaleFactor(0.75)
            .foregroundStyle(foreground)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(background, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
            .overlay {
                if !filled {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .strokeBorder(night ? WidgetTE.Night.dialTrack : WidgetTE.border, lineWidth: 1)
                }
            }
    }

    private var foreground: Color {
        if night {
            return filled ? WidgetTE.fixed(0x0F1626) : WidgetTE.Night.textPrimary
        }
        return filled ? WidgetTE.onAccent : WidgetTE.textPrimary
    }

    private var background: Color {
        if night {
            return filled ? WidgetTE.Night.inkRust : WidgetTE.fixed(0x222C46)
        }
        return filled ? WidgetTE.accent : WidgetTE.card
    }
}

/// The Dynamic Island's recording glyph: a rust arc showing how much of today has passed.
private struct RecordingRing: View {
    let fraction: Double

    var body: some View {
        ZStack {
            Circle()
                .inset(by: 1.5)
                .stroke(WidgetTE.fixed(0x333333), lineWidth: 3)
            Circle()
                .inset(by: 1.5)
                .trim(from: 0, to: max(0.02, fraction))
                .stroke(WidgetTE.Night.inkRust, style: StrokeStyle(lineWidth: 3, lineCap: .butt))
                .rotationEffect(.degrees(-90))
        }
    }
}

// MARK: - Preview

#Preview("Lock Screen", as: .content, using: LocationActivityAttributes(startTime: .now)) {
    IsoMeLiveActivity()
} contentStates: {
    LocationActivityAttributes.ContentState(
        locationName: "Coffee Shop",
        locationsRecorded: 42,
        distanceTraveled: 1234,
        remainingSeconds: 3600,
        lastUpdate: .now,
        originLocationName: "Home"
    )
    LocationActivityAttributes.ContentState(
        locationName: nil,
        locationsRecorded: 5,
        distanceTraveled: 8500,
        remainingSeconds: nil,
        lastUpdate: .now
    )
}
