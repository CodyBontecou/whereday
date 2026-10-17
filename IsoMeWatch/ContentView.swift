import SwiftUI

struct ContentView: View {
    @StateObject private var tracker = WatchLocationTracker()
    @ObservedObject private var syncClient = WatchLocationSyncClient.shared
    @Environment(\.scenePhase) private var scenePhase

    private var locationData: SharedLocationData {
        tracker.locationData
    }

    var body: some View {
        ZStack {
            WatchTE.surface
                .ignoresSafeArea()

            ScrollView {
                VStack(spacing: 8) {
                    appHeader
                    statusPanel
                    permissionView
                    trackingButton
                    syncSection
                    todaySection
                    currentLocationSection
                    errorView
                    resetButton
                }
                .padding(.horizontal, 8)
                // The watch status area adds a generous top inset. Lift the
                // content slightly so the primary START control is fully
                // visible on the initial screen without shrinking the button.
                .padding(.top, -18)
                .padding(.bottom, 22)
            }
            .scrollIndicators(.hidden)
        }
        .onAppear {
            tracker.refresh()
        }
        .onChange(of: scenePhase) { _, phase in
            if phase == .active {
                syncClient.syncPending(force: true)
            }
        }
    }

    private var syncSection: some View {
        VStack(spacing: 6) {
            Button {
                syncClient.syncPending(force: true)
            } label: {
                Label("Sync Now", systemImage: "arrow.triangle.2.circlepath")
                    .font(WatchTE.rounded(.caption, weight: .semibold))
                    .frame(maxWidth: .infinity, minHeight: 36)
            }
            .buttonStyle(WatchInlineButtonStyle())
            .accessibilityIdentifier("watchSyncNow")

            if let status = syncClient.syncStatus {
                Text(status)
                    .font(WatchTE.rounded(.caption2, weight: .medium))
                    .foregroundStyle(WatchTE.textMuted)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var appHeader: some View {
        HStack(spacing: 6) {
            Circle()
                .fill(locationData.isTrackingEnabled ? WatchTE.success : WatchTE.border)
                .frame(width: 5, height: 5)

            Text("ISO.ME")
                .font(WatchTE.mono(.caption2, weight: .bold))
                .tracking(2.4)
                .foregroundStyle(WatchTE.textMuted)

            Circle()
                .fill(locationData.isTrackingEnabled ? WatchTE.success : WatchTE.border)
                .frame(width: 5, height: 5)
        }
        .frame(maxWidth: .infinity)
        .padding(.top, 0)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("iso.me")
    }

    private var statusPanel: some View {
        WatchTECard(fill: WatchTE.lcdBackground) {
            VStack(alignment: .leading, spacing: 8) {
                HStack(spacing: 6) {
                    Circle()
                        .fill(locationData.isTrackingEnabled ? WatchTE.success : WatchTE.textMuted.opacity(0.35))
                        .frame(width: 6, height: 6)

                    Text(locationData.isTrackingEnabled ? "TRACKING" : "STANDBY")
                        .font(WatchTE.mono(.caption2, weight: .semibold))
                        .tracking(1.8)
                        .foregroundStyle(WatchTE.textMuted)

                    Spacer(minLength: 0)

                    Image(systemName: statusIcon)
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(statusColor)
                }

                HStack(alignment: .firstTextBaseline, spacing: 8) {
                    statusReadout

                    Spacer(minLength: 0)
                }

                Text(statusSubtitle)
                    .font(WatchTE.mono(.caption2, weight: .semibold))
                    .tracking(1.4)
                    .foregroundStyle(WatchTE.textMuted)
                    .lineLimit(2)

                if locationData.isTrackingEnabled,
                   let remaining = locationData.formattedRemainingTime {
                    WatchInfoPill(icon: "timer", text: "\(remaining) LEFT", color: WatchTE.warning)
                }
            }
        }
    }

    @ViewBuilder
    private var statusReadout: some View {
        if locationData.isTrackingEnabled,
           let startTime = locationData.trackingStartTime {
            Text(startTime, style: .timer)
                .font(.system(size: 28, weight: .semibold, design: .monospaced))
                .foregroundStyle(WatchTE.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.58)
        } else if locationData.isTrackingEnabled {
            Text("LIVE")
                .font(.system(size: 28, weight: .semibold, design: .monospaced))
                .tracking(1.5)
                .foregroundStyle(WatchTE.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        } else {
            Text("--:--")
                .font(.system(size: 30, weight: .semibold, design: .monospaced))
                .tracking(2)
                .foregroundStyle(WatchTE.textMuted.opacity(0.58))
                .lineLimit(1)
                .minimumScaleFactor(0.7)
        }
    }

    @ViewBuilder
    private var permissionView: some View {
        if let message = tracker.authorizationMessage {
            WatchTECard {
                VStack(alignment: .leading, spacing: 9) {
                    HStack(alignment: .top, spacing: 8) {
                        Image(systemName: tracker.needsLocationPermission ? "location.circle" : "exclamationmark.triangle.fill")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(tracker.needsLocationPermission ? WatchTE.accent : WatchTE.warning)
                            .frame(width: 18)

                        Text(message)
                            .font(WatchTE.rounded(.caption2, weight: .medium))
                            .foregroundStyle(WatchTE.textMuted)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    if tracker.needsLocationPermission {
                        Button {
                            tracker.requestLocationPermission()
                        } label: {
                            HStack(spacing: 6) {
                                Text("ENABLE LOCATION")
                                Image(systemName: "arrow.right")
                            }
                            .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(WatchInlineButtonStyle())
                    }
                }
            }
        }
    }

    private var trackingButton: some View {
        Button {
            tracker.toggleTracking()
        } label: {
            HStack(spacing: 10) {
                ZStack {
                    RoundedRectangle(cornerRadius: 3, style: .continuous)
                        .fill(locationData.isTrackingEnabled ? WatchTE.danger : WatchTE.accent)
                        .frame(width: 34, height: 34)

                    Image(systemName: locationData.isTrackingEnabled ? "stop.fill" : "play.fill")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.white)
                }

                Text(locationData.isTrackingEnabled ? "STOP" : "START")
                    .font(WatchTE.mono(.headline, weight: .bold))
                    .tracking(2.1)
                    .foregroundStyle(WatchTE.textPrimary)

                Spacer(minLength: 0)

                Image(systemName: locationData.isTrackingEnabled ? "xmark" : "arrow.right")
                    .font(.callout.weight(.semibold))
                    .foregroundStyle(WatchTE.textMuted)
            }
        }
        .buttonStyle(WatchTrackingButtonStyle())
        .disabled(!tracker.canUseLocation && !tracker.needsLocationPermission)
        .opacity((!tracker.canUseLocation && !tracker.needsLocationPermission) ? 0.5 : 1)
        .accessibilityLabel(locationData.isTrackingEnabled ? "Stop tracking" : "Start tracking")
    }

    private var todaySection: some View {
        VStack(alignment: .leading, spacing: 6) {
            WatchSectionHeader("TODAY")

            WatchTECard {
                VStack(spacing: 0) {
                    HStack(spacing: 0) {
                        statItem(
                            value: "\(locationData.todayVisitsCount)",
                            label: "VISITS",
                            icon: "mappin.circle"
                        )

                        WatchDivider(axis: .vertical)
                            .padding(.horizontal, 10)

                        statItem(
                            value: locationData.formattedDistance,
                            label: "DISTANCE",
                            icon: "figure.walk"
                        )
                    }
                    .padding(.vertical, 11)
                    .padding(.horizontal, 10)

                    WatchDivider(axis: .horizontal)

                    HStack(spacing: 8) {
                        Image(systemName: "location.fill")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(WatchTE.accent)
                            .frame(width: 18)

                        Text("POINTS")
                            .font(WatchTE.mono(.caption2, weight: .semibold))
                            .tracking(1)
                            .foregroundStyle(WatchTE.textMuted)

                        Spacer(minLength: 0)

                        Text("\(locationData.todayPointsCount)")
                            .font(WatchTE.mono(.caption, weight: .semibold))
                            .foregroundStyle(WatchTE.textPrimary)
                            .monospacedDigit()
                    }
                    .padding(.vertical, 9)
                    .padding(.horizontal, 10)
                }
            }
        }
    }

    @ViewBuilder
    private var currentLocationSection: some View {
        if let locationName = locationData.currentLocationName {
            VStack(alignment: .leading, spacing: 6) {
                WatchSectionHeader("LOCATION")

                WatchTECard {
                    HStack(alignment: .top, spacing: 9) {
                        Image(systemName: "scope")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(WatchTE.accent)
                            .frame(width: 18)
                            .padding(.top, 1)

                        VStack(alignment: .leading, spacing: 5) {
                            Text(locationName)
                                .font(WatchTE.rounded(.caption, weight: .semibold))
                                .foregroundStyle(WatchTE.textPrimary)
                                .lineLimit(2)
                                .minimumScaleFactor(0.8)

                            if let update = locationData.lastUpdateTime {
                                Text(update, style: .time)
                                    .font(WatchTE.mono(.caption2, weight: .medium))
                                    .tracking(0.7)
                                    .foregroundStyle(WatchTE.textMuted)
                            }
                        }

                        Spacer(minLength: 0)
                    }
                }
            }
        }
    }

    @ViewBuilder
    private var errorView: some View {
        if let error = tracker.lastErrorMessage {
            WatchTECard(fill: WatchTE.danger.opacity(0.09), border: WatchTE.danger.opacity(0.28)) {
                HStack(alignment: .top, spacing: 8) {
                    Image(systemName: "exclamationmark.octagon.fill")
                        .font(.caption.weight(.semibold))
                        .foregroundStyle(WatchTE.danger)
                        .frame(width: 18)

                    Text(error)
                        .font(WatchTE.rounded(.caption2, weight: .medium))
                        .foregroundStyle(WatchTE.danger)
                        .fixedSize(horizontal: false, vertical: true)
                }
            }
        }
    }

    @ViewBuilder
    private var resetButton: some View {
        if locationData.todayPointsCount > 0 {
            Button(role: .destructive) {
                tracker.resetToday()
            } label: {
                Text("RESET TODAY")
                    .font(WatchTE.mono(.caption2, weight: .bold))
                    .tracking(1.4)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(WatchResetButtonStyle())
        }
    }

    private func statItem(value: String, label: LocalizedStringKey, icon: String) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            Image(systemName: icon)
                .font(.caption.weight(.semibold))
                .foregroundStyle(WatchTE.accent)

            Text(value)
                .font(WatchTE.mono(.headline, weight: .bold))
                .foregroundStyle(WatchTE.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .monospacedDigit()

            Text(label)
                .font(WatchTE.mono(.caption2, weight: .semibold))
                .tracking(0.9)
                .foregroundStyle(WatchTE.textMuted)
                .lineLimit(1)
                .minimumScaleFactor(0.72)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    private var statusSubtitle: String {
        if locationData.isTrackingEnabled {
            return String(localized: "CAPTURING WATCH PATH")
        }

        if tracker.canUseLocation {
            return String(localized: "READY TO TRACK")
        }

        if tracker.needsLocationPermission {
            return String(localized: "LOCATION NEEDED")
        }

        return String(localized: "CHECK PERMISSION")
    }

    private var statusIcon: String {
        locationData.isTrackingEnabled ? "location.fill" : "location.slash"
    }

    private var statusColor: Color {
        locationData.isTrackingEnabled ? WatchTE.success : WatchTE.textMuted
    }
}

private enum WatchTE {
    static let accent = Color(red: 0.196, green: 0.455, blue: 0.956)
    static let surface = Color(red: 0.96, green: 0.955, blue: 0.94)
    static let card = Color.white
    static let border = Color(red: 0.82, green: 0.81, blue: 0.79)
    static let textPrimary = Color(red: 0.12, green: 0.12, blue: 0.12)
    static let textMuted = Color(red: 0.52, green: 0.51, blue: 0.49)
    static let lcdBackground = Color(red: 0.88, green: 0.90, blue: 0.85)
    static let danger = Color(red: 0.85, green: 0.2, blue: 0.15)
    static let warning = Color(red: 0.92, green: 0.61, blue: 0.14)
    static let success = Color(red: 0.17, green: 0.67, blue: 0.42)

    static func mono(_ style: Font.TextStyle, weight: Font.Weight = .regular) -> Font {
        Font.system(style, design: .monospaced).weight(weight)
    }

    static func rounded(_ style: Font.TextStyle, weight: Font.Weight = .regular) -> Font {
        Font.system(style, design: .rounded).weight(weight)
    }
}

private struct WatchTECard<Content: View>: View {
    var fill: Color = WatchTE.card
    var border: Color = WatchTE.border
    @ViewBuilder let content: () -> Content

    var body: some View {
        content()
            .padding(10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(fill, in: RoundedRectangle(cornerRadius: 4, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .strokeBorder(border, lineWidth: 1)
            }
    }
}

private struct WatchSectionHeader: View {
    let title: LocalizedStringKey

    init(_ title: LocalizedStringKey) {
        self.title = title
    }

    var body: some View {
        Text(title)
            .font(WatchTE.mono(.caption2, weight: .semibold))
            .tracking(1.8)
            .foregroundStyle(WatchTE.textMuted)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(.horizontal, 3)
    }
}

private struct WatchInfoPill: View {
    let icon: String
    let text: LocalizedStringKey
    let color: Color

    var body: some View {
        HStack(spacing: 5) {
            Image(systemName: icon)
                .font(.caption2.weight(.semibold))
            Text(text)
                .font(WatchTE.mono(.caption2, weight: .semibold))
                .tracking(0.7)
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .foregroundStyle(color)
        .padding(.horizontal, 8)
        .padding(.vertical, 5)
        .background(color.opacity(0.12), in: Capsule())
    }
}

private struct WatchDivider: View {
    enum Axis {
        case horizontal
        case vertical
    }

    let axis: Axis

    var body: some View {
        Rectangle()
            .fill(WatchTE.border)
            .frame(
                width: axis == .vertical ? 1 : nil,
                height: axis == .horizontal ? 1 : 52
            )
    }
}

private struct WatchTrackingButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .padding(.horizontal, 10)
            .padding(.vertical, 9)
            .background(WatchTE.card, in: RoundedRectangle(cornerRadius: 4, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .strokeBorder(WatchTE.border, lineWidth: 1)
            }
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .opacity(configuration.isPressed ? 0.86 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

private struct WatchInlineButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(WatchTE.mono(.caption2, weight: .bold))
            .tracking(1.1)
            .foregroundStyle(.white)
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .background(WatchTE.accent, in: RoundedRectangle(cornerRadius: 4, style: .continuous))
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .opacity(configuration.isPressed ? 0.88 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

private struct WatchResetButtonStyle: ButtonStyle {
    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .foregroundStyle(WatchTE.danger)
            .padding(.horizontal, 10)
            .padding(.vertical, 8)
            .background(WatchTE.card.opacity(0.72), in: RoundedRectangle(cornerRadius: 4, style: .continuous))
            .overlay {
                RoundedRectangle(cornerRadius: 4, style: .continuous)
                    .strokeBorder(WatchTE.danger.opacity(0.25), lineWidth: 1)
            }
            .scaleEffect(configuration.isPressed ? 0.98 : 1)
            .opacity(configuration.isPressed ? 0.82 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

#Preview {
    ContentView()
}
