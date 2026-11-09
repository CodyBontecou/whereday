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
                .font(WatchTE.mono(size: 11, weight: .medium, relativeTo: .caption2))
                .foregroundStyle(WatchTE.inkRust)
            Spacer(minLength: 0)
        }
        .padding(.horizontal, 4)
        .accessibilityElement(children: .combine)
        .accessibilityLabel(locationData.isTrackingEnabled ? "Whereday, recording a route" : "Whereday, today")
    }

    // MARK: - Glance: the day dial

    private var glancePanel: some View {
        // Redraw each minute so the arcs, the "now" tick and "30 min" stay current.
        TimelineView(.everyMinute) { timeline in
            let now = timeline.date
            let context = syncClient.todayContext
            let arcs = context?.arcs(now: now) ?? []
            let parts = Self.distanceParts(
                DistanceFormatter.format(meters: todayDistance(on: now), usesMetric: locationData.prefersMetricDistanceUnits)
            )
            let stay = context?.currentStay(on: now)

            VStack(spacing: 6) {
                ZStack {
                    WatchDayDial(arcs: arcs, hasStays: context?.isSameDay(as: now) ?? false, nowFraction: WatchDayDial.dayFraction(of: now))

                    VStack(spacing: 0) {
                        Text(parts.value)
                            .font(WheredayFont.serif(size: 26, relativeTo: .title2))
                            .foregroundStyle(WatchTE.textPrimary)
                            .lineLimit(1)
                            .minimumScaleFactor(0.6)
                        Text(parts.unit.uppercased())
                            .font(WatchTE.mono(size: 9, relativeTo: .caption2))
                            .foregroundStyle(WatchTE.textMuted)
                    }
                    .padding(.horizontal, 22)
                }
                .frame(width: 118, height: 118)
                .accessibilityElement(children: .ignore)
                .accessibilityLabel(dialAccessibilityLabel(stayCount: arcs.count, distance: parts))

                Text(stay?.name ?? glanceTitle)
                    .font(WheredayFont.serif(size: 17, relativeTo: .headline))
                    .foregroundStyle(WatchTE.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.75)

                if let detail = stay.map({ Self.sinceLabel(since: $0.since, now: now) }) ?? glanceDetail {
                    Text(detail)
                        .font(WatchTE.mono(size: 10, relativeTo: .caption2))
                        .foregroundStyle(WatchTE.textMuted)
                        .lineLimit(2)
                        .multilineTextAlignment(.center)
                        .minimumScaleFactor(0.8)
                }
            }
            .frame(maxWidth: .infinity)
        }
    }

    /// The watch's own distance today, or the iPhone's day total when that's further.
    private func todayDistance(on date: Date) -> Double {
        max(locationData.todayDistanceMeters, syncClient.todayContext?.distance(on: date) ?? 0)
    }

    private func dialAccessibilityLabel(stayCount: Int, distance: (value: String, unit: String)) -> String {
        let moved = "\(distance.value) \(distance.unit)"
        if stayCount > 0 {
            return String(localized: "Today's dial, \(stayCount) stays, \(moved) moved")
        }
        return String(localized: "Today's dial, \(moved) moved")
    }

    /// "since 13:50 · 30 min"
    private static func sinceLabel(since: Date, now: Date) -> String {
        let clock = ClockTimeFormatter.string(from: since)
        let duration = StayDurationFormatter.compact(now.timeIntervalSince(since))
        return String(localized: "since \(clock) · \(duration)")
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
        let parts = Self.distanceParts(
            DistanceFormatter.format(
                meters: locationData.routeDistanceMeters ?? 0,
                usesMetric: locationData.prefersMetricDistanceUnits
            )
        )

        return VStack(alignment: .leading, spacing: 4) {
            Text(parts.value)
                .font(WheredayFont.serif(size: 46, relativeTo: .largeTitle))
                .foregroundStyle(WatchTE.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.5)

            HStack(spacing: 0) {
                Text("\(parts.unit.uppercased()) THIS ROUTE")
                if let start = locationData.trackingStartTime {
                    Text(verbatim: " · ")
                    Text(start, style: .timer)
                        .monospacedDigit()
                }
            }
            .font(WatchTE.mono(size: 10, relativeTo: .caption2))
            .foregroundStyle(WatchTE.textMuted)
            .lineLimit(1)
            .minimumScaleFactor(0.7)

            HStack(spacing: 6) {
                Image(systemName: "arrow.triangle.2.circlepath")
                    .font(.caption2.weight(.semibold))
                Text(pendingLabel)
                    .font(WatchTE.mono(size: 10, relativeTo: .caption2))
                    .lineLimit(2)
                    .minimumScaleFactor(0.8)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .foregroundStyle(WatchTE.green)
            .padding(.top, 4)
            .accessibilityElement(children: .combine)

            if let markStatus = syncClient.markStatus {
                Text(markStatus)
                    .font(WatchTE.mono(size: 10, relativeTo: .caption2))
                    .foregroundStyle(WatchTE.textPrimary)
                    .lineLimit(2)
                    .fixedSize(horizontal: false, vertical: true)
            }

            if let remaining = locationData.formattedRemainingTime {
                Text(String(localized: "\(remaining) until auto-off"))
                    .font(WatchTE.mono(size: 10, relativeTo: .caption2))
                    .foregroundStyle(WatchTE.ochre)
                    .lineLimit(1)
            }

            HStack(spacing: 6) {
                Button {
                    let fix = tracker.recentFix
                    syncClient.requestMarkPlace(
                        latitude: fix?.latitude,
                        longitude: fix?.longitude,
                        horizontalAccuracy: nil
                    )
                } label: {
                    Text("Mark")
                }
                .buttonStyle(WatchCapsuleButtonStyle(filled: false))
                .accessibilityIdentifier("watchMarkPlace")
                .accessibilityLabel("Mark this place")
                .accessibilityHint("Checks in here on your iPhone")

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

    /// Sync runs on its own (each place change, every few points, on launch and when the
    /// iPhone becomes reachable), so the route screen only reports it.
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
                .font(WheredayFont.serif(.body))
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
    private static func distanceParts(_ formatted: String) -> (value: String, unit: String) {
        guard let space = formatted.lastIndex(where: { $0 == " " || $0 == "\u{00A0}" || $0 == "\u{202F}" }) else {
            return (formatted, "")
        }
        return (String(formatted[..<space]), String(formatted[formatted.index(after: space)...]))
    }
}

// MARK: - Day dial

/// A 24-hour ring, midnight at the top. Today's stays from the iPhone are drawn as arcs in
/// their places' inks; without them (no iPhone context yet today) the dial shows the part of
/// the day already lived. A rust tick marks "now".
private struct WatchDayDial: View {
    let arcs: [WatchTodayContext.Arc]
    /// True when the iPhone's context is from today, even if it has no stays yet.
    var hasStays: Bool = false
    let nowFraction: Double

    var body: some View {
        GeometryReader { geometry in
            let size = min(geometry.size.width, geometry.size.height)
            let lineWidth = size * 0.11
            let inset = lineWidth / 2 + size * 0.03
            let radius = size / 2 - inset
            let angle = Angle.degrees(nowFraction * 360 - 90)

            ZStack {
                Circle()
                    .inset(by: inset)
                    .stroke(WatchTE.dialTrack, lineWidth: lineWidth)

                if hasStays {
                    ForEach(Array(arcs.enumerated()), id: \.offset) { _, arc in
                        Circle()
                            .inset(by: inset)
                            .trim(from: arc.startFraction, to: arc.endFraction)
                            .stroke(WatchTE.placeInks[arc.inkIndex % WatchTE.placeInks.count],
                                    style: StrokeStyle(lineWidth: lineWidth, lineCap: .butt))
                            .rotationEffect(.degrees(-90))
                    }
                } else {
                    Circle()
                        .inset(by: inset)
                        .trim(from: 0, to: nowFraction)
                        .stroke(WatchTE.navyInk.opacity(0.55), style: StrokeStyle(lineWidth: lineWidth, lineCap: .butt))
                        .rotationEffect(.degrees(-90))
                }

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
    /// Per-place inks, in the order the iPhone assigns them (matches the widgets' night inks).
    static let placeInks: [Color] = [navyInk, inkRust, ochre, green]

    /// IBM Plex Mono, bundled and registered in the watch app.
    static func mono(_ style: Font.TextStyle, weight: Font.Weight = .regular) -> Font {
        WheredayFont.mono(style, weight: weight)
    }

    static func mono(size: CGFloat, weight: Font.Weight = .regular, relativeTo style: Font.TextStyle) -> Font {
        WheredayFont.mono(size: size, weight: weight, relativeTo: style)
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
