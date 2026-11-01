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
                VStack(spacing: 10) {
                    appHeader
                    if locationData.isTrackingEnabled {
                        routePanel
                    } else {
                        glancePanel
                        permissionView
                        startButton
                    }
                    errorView
                    todaySection
                    if !locationData.isTrackingEnabled {
                        syncSection
                    }
                    resetButton
                }
                .padding(.horizontal, 6)
                // The watch status area adds a generous top inset. Lift the
                // content slightly so the dial and its label fit the first screen.
                .padding(.top, -14)
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

    // MARK: - Header

    private var appHeader: some View {
        HStack(spacing: 4) {
            Text(locationData.isTrackingEnabled ? "● Route" : "Today")
                .font(WatchTE.mono(.caption2, weight: .medium))
                .foregroundStyle(WatchTE.inkRust)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 4)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(locationData.isTrackingEnabled ? "Whereday, recording a route" : "Whereday, today")
    }

    // MARK: - Glance: the day dial

    private var glancePanel: some View {
        VStack(spacing: 6) {
            ZStack {
                WatchDayDial(nowFraction: WatchDayDial.dayFraction(of: Date()))

                VStack(spacing: 0) {
                    Text(distanceParts.value)
                        .font(.system(.title2, design: .serif))
                        .foregroundStyle(WatchTE.textPrimary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.6)
                    Text(distanceParts.unit.uppercased())
                        .font(WatchTE.mono(.caption2))
                        .foregroundStyle(WatchTE.textMuted)
                }
                .padding(.horizontal, 22)
            }
            .frame(width: 112, height: 112)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(String(localized: "Today's dial, \(locationData.formattedDistance) moved"))

            Text(glanceTitle)
                .font(.system(.headline, design: .serif))
                .foregroundStyle(WatchTE.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.75)

            if let detail = glanceDetail {
                Text(detail)
                    .font(WatchTE.mono(.caption2))
                    .foregroundStyle(WatchTE.textMuted)
                    .lineLimit(2)
                    .multilineTextAlignment(.center)
                    .minimumScaleFactor(0.8)
            }
        }
        .frame(maxWidth: .infinity)
    }

    private var glanceTitle: String {
        if tracker.canUseLocation {
            return String(localized: "Standby")
        }
        if tracker.needsLocationPermission {
            return String(localized: "Location needed")
        }
        return String(localized: "Check permission")
    }

    private var glanceDetail: String? {
        guard let update = locationData.lastUpdateTime else {
            return tracker.canUseLocation ? String(localized: "ready to record") : nil
        }
        let time = update.formatted(date: .omitted, time: .shortened)
        if let name = locationData.currentLocationName {
            return String(localized: "\(name) · \(time)")
        }
        return String(localized: "last fix \(time)")
    }

    // MARK: - Route recording

    private var routePanel: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(distanceParts.value)
                .font(.system(size: 44, weight: .regular, design: .serif))
                .foregroundStyle(WatchTE.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.5)

            HStack(spacing: 0) {
                Text("\(distanceParts.unit.uppercased()) TODAY")
                if let start = locationData.trackingStartTime {
                    Text(verbatim: " · ")
                    Text(start, style: .timer)
                        .monospacedDigit()
                }
            }
            .font(WatchTE.mono(.caption2))
            .foregroundStyle(WatchTE.textMuted)
            .lineLimit(1)
            .minimumScaleFactor(0.7)

            HStack(spacing: 6) {
                Image(systemName: "arrow.triangle.2.circlepath")
                    .font(.caption2.weight(.semibold))
                Text(pendingLabel)
                    .font(WatchTE.mono(.caption2))
                    .lineLimit(2)
                    .minimumScaleFactor(0.8)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .foregroundStyle(WatchTE.green)
            .padding(.top, 4)

            if let remaining = locationData.formattedRemainingTime {
                Text(String(localized: "\(remaining) until auto-off"))
                    .font(WatchTE.mono(.caption2))
                    .foregroundStyle(WatchTE.ochre)
                    .lineLimit(1)
            }

            HStack(spacing: 6) {
                Button {
                    syncClient.syncPending(force: true)
                } label: {
                    Text("Sync")
                }
                .buttonStyle(WatchCapsuleButtonStyle(filled: false))
                .accessibilityIdentifier("watchSyncNow")
                .accessibilityLabel("Sync Now")

                Button {
                    tracker.toggleTracking()
                } label: {
                    Text("Stop")
                }
                .buttonStyle(WatchCapsuleButtonStyle(filled: true))
                .accessibilityLabel("Stop tracking")
            }
            .padding(.top, 8)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 4)
    }

    private var pendingLabel: String {
        let count = syncClient.pendingPointCount
        if count > 0 {
            return String(localized: "\(count) pts waiting for iPhone")
        }
        return syncClient.syncStatus ?? String(localized: "Synced with iPhone.")
    }

    // MARK: - Controls

    private var startButton: some View {
        Button {
            tracker.toggleTracking()
        } label: {
            Label("Start route", systemImage: "record.circle")
        }
        .buttonStyle(WatchCapsuleButtonStyle(filled: true))
        .disabled(!tracker.canUseLocation && !tracker.needsLocationPermission)
        .opacity((!tracker.canUseLocation && !tracker.needsLocationPermission) ? 0.5 : 1)
        .accessibilityLabel("Start tracking")
    }

    private var syncSection: some View {
        VStack(spacing: 6) {
            Button {
                syncClient.syncPending(force: true)
            } label: {
                Label("Sync Now", systemImage: "arrow.triangle.2.circlepath")
            }
            .buttonStyle(WatchCapsuleButtonStyle(filled: false))
            .accessibilityIdentifier("watchSyncNow")

            if syncClient.pendingPointCount > 0 {
                Text(String(localized: "\(syncClient.pendingPointCount) pts waiting for iPhone"))
                    .font(WatchTE.mono(.caption2))
                    .foregroundStyle(WatchTE.green)
                    .multilineTextAlignment(.center)
            } else if let status = syncClient.syncStatus {
                Text(status)
                    .font(WatchTE.mono(.caption2))
                    .foregroundStyle(WatchTE.textMuted)
                    .multilineTextAlignment(.center)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    @ViewBuilder
    private var permissionView: some View {
        if let message = tracker.authorizationMessage {
            VStack(alignment: .leading, spacing: 8) {
                Text(message)
                    .font(.footnote)
                    .foregroundStyle(WatchTE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)

                if tracker.needsLocationPermission {
                    Button {
                        tracker.requestLocationPermission()
                    } label: {
                        Text("Enable location")
                    }
                    .buttonStyle(WatchCapsuleButtonStyle(filled: false))
                }
            }
            .padding(10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(WatchTE.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
    }

    private var todaySection: some View {
        HStack(spacing: 0) {
            statItem(value: "\(locationData.todayVisitsCount)", label: "VISITS")
            statItem(value: locationData.todayPointsCount.formatted(), label: "POINTS")
        }
        .padding(.vertical, 8)
        .padding(.horizontal, 10)
        .background(WatchTE.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
    }

    @ViewBuilder
    private var errorView: some View {
        if let error = tracker.lastErrorMessage {
            HStack(alignment: .top, spacing: 8) {
                Image(systemName: "exclamationmark.circle")
                    .font(.caption.weight(.semibold))
                Text(error)
                    .font(.footnote)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .foregroundStyle(WatchTE.inkRust)
            .padding(10)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(WatchTE.card, in: RoundedRectangle(cornerRadius: 14, style: .continuous))
        }
    }

    @ViewBuilder
    private var resetButton: some View {
        if locationData.todayPointsCount > 0 {
            Button(role: .destructive) {
                tracker.resetToday()
            } label: {
                Text("RESET TODAY")
                    .font(WatchTE.mono(.caption2, weight: .medium))
                    .tracking(1.2)
                    .foregroundStyle(WatchTE.inkRust)
                    .frame(maxWidth: .infinity, minHeight: 44)
            }
            .buttonStyle(.plain)
        }
    }

    private func statItem(value: String, label: LocalizedStringKey) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(value)
                .font(.system(.body, design: .serif))
                .foregroundStyle(WatchTE.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .monospacedDigit()

            Text(label)
                .font(WatchTE.mono(.caption2))
                .tracking(1)
                .foregroundStyle(WatchTE.textMuted)
                .lineLimit(1)
                .minimumScaleFactor(0.72)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }

    /// Splits "6.4 km" into its number and unit so the number can lead.
    private var distanceParts: (value: String, unit: String) {
        let formatted = locationData.formattedDistance
        guard let space = formatted.lastIndex(where: { $0 == " " || $0 == "\u{00A0}" || $0 == "\u{202F}" }) else {
            return (formatted, "")
        }
        return (String(formatted[..<space]), String(formatted[formatted.index(after: space)...]))
    }
}

// MARK: - Day dial

/// A 24-hour ring, midnight at the top. The watch doesn't keep today's stays, so the dial shows
/// the part of the day already lived, with a rust "now" tick.
private struct WatchDayDial: View {
    let nowFraction: Double

    var body: some View {
        GeometryReader { geometry in
            let size = min(geometry.size.width, geometry.size.height)
            let lineWidth = size * 0.11
            let inset = lineWidth / 2
            let radius = size / 2 - inset
            let angle = Angle.degrees(nowFraction * 360 - 90)

            ZStack {
                Circle()
                    .inset(by: inset)
                    .stroke(WatchTE.dialTrack, lineWidth: lineWidth)

                Circle()
                    .inset(by: inset)
                    .trim(from: 0, to: nowFraction)
                    .stroke(WatchTE.navyInk.opacity(0.55), style: StrokeStyle(lineWidth: lineWidth, lineCap: .butt))
                    .rotationEffect(.degrees(-90))

                ForEach(0..<4, id: \.self) { quarter in
                    Rectangle()
                        .fill(WatchTE.textMuted.opacity(0.6))
                        .frame(width: 1, height: lineWidth * 0.5)
                        .offset(y: -radius)
                        .rotationEffect(.degrees(Double(quarter) * 90))
                }

                Capsule()
                    .fill(WatchTE.inkRust)
                    .frame(width: 3, height: lineWidth * 1.4)
                    .rotationEffect(.degrees(nowFraction * 360))
                    .offset(x: cos(angle.radians) * radius, y: sin(angle.radians) * radius)
            }
            .frame(width: size, height: size)
        }
        .accessibilityHidden(true)
    }

    static func dayFraction(of date: Date) -> Double {
        let start = Calendar.current.startOfDay(for: date)
        return min(max(date.timeIntervalSince(start) / 86_400, 0), 1)
    }
}

// MARK: - Palette

/// Whereday's night inks for the always-dark watch face. Mirrors the app's `TE` tokens,
/// which live only in the iPhone target.
private enum WatchTE {
    static let surface = Color.black
    static let card = hex(0x18213A)
    static let textPrimary = hex(0xECE6D8)
    static let textMuted = hex(0x9AA3B8)
    static let inkRust = hex(0xF0805F)
    static let onInkRust = hex(0x0F1626)
    static let green = hex(0x9DC097)
    static let ochre = hex(0xE0B565)
    static let navyInk = hex(0x8FA6D9)
    static let dialTrack = hex(0x222C46)
    static let buttonFill = hex(0x222C46)

    static func mono(_ style: Font.TextStyle, weight: Font.Weight = .regular) -> Font {
        Font.system(style, design: .monospaced).weight(weight)
    }

    private static func hex(_ value: UInt32) -> Color {
        Color(
            red: Double((value >> 16) & 0xFF) / 255,
            green: Double((value >> 8) & 0xFF) / 255,
            blue: Double(value & 0xFF) / 255
        )
    }
}

/// 44pt capsule actions: rust when filled, navy-on-black otherwise.
private struct WatchCapsuleButtonStyle: ButtonStyle {
    let filled: Bool
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        configuration.label
            .font(.footnote.weight(.semibold))
            .lineLimit(1)
            .minimumScaleFactor(0.75)
            .foregroundStyle(filled ? WatchTE.onInkRust : WatchTE.textPrimary)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(filled ? WatchTE.inkRust : WatchTE.buttonFill, in: Capsule())
            .opacity(isEnabled ? (configuration.isPressed ? 0.82 : 1) : 0.5)
            .scaleEffect(configuration.isPressed ? 0.97 : 1)
            .animation(.easeOut(duration: 0.12), value: configuration.isPressed)
    }
}

#Preview {
    ContentView()
}
