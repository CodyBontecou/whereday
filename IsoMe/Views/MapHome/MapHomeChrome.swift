import SwiftUI
import MapKit

// The map home's floating chrome (boards M1, M3, M6, M7): header card, range chips, right rail,
// recording bar, add menu, peek card and the "Are you here?" card.

enum MapHomeStyle {
    /// 0 8 24 rgba(30,43,69,0.14) by day; a deeper black shadow at night.
    static let shadow = TE.adaptive(
        UIColor(red: 30 / 255, green: 43 / 255, blue: 69 / 255, alpha: 0.14),
        UIColor(white: 0, alpha: 0.45)
    )
    /// Vermilion: the live dot, now marker, replay playhead and the highlighted day.
    static let vermilion = TE.placeInks[1]
    /// Selected chips and the recording bar: navy by day, cream at night.
    static let invertedFill = TE.textPrimary
    static let invertedText = TE.surface
    static let secondaryText = TE.adaptive(0x4E4C46, 0xC9CEDA)
}

extension View {
    func mapCardShadow() -> some View {
        shadow(color: MapHomeStyle.shadow, radius: 12, x: 0, y: 8)
    }

    /// A paper card with a hairline border and the board's card shadow.
    func mapCard(cornerRadius: CGFloat) -> some View {
        background(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous).fill(TE.card).mapCardShadow())
            .overlay(RoundedRectangle(cornerRadius: cornerRadius, style: .continuous).strokeBorder(TE.border, lineWidth: 1))
    }
}

// MARK: - Header

/// "SUN · NOV 1 · DAY 305" over "Today", with search.
struct MapHeaderCard: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let kicker: String
    let title: String
    let onSearch: () -> Void

    var body: some View {
        HStack(spacing: 8) {
            VStack(alignment: .leading, spacing: 1) {
                Text(kicker)
                    .font(WheredayFont.mono(size: 9.5, weight: .medium, relativeTo: .caption2))
                    .tracking(1.4)
                    .foregroundStyle(TE.textMuted)
                    .lineLimit(dynamicTypeSize.isAccessibilitySize ? 3 : 1)
                    .minimumScaleFactor(dynamicTypeSize.isAccessibilitySize ? 1 : 0.7)
                Text(title)
                    .font(WheredayFont.serif(size: 22, relativeTo: .title2))
                    .foregroundStyle(TE.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.6)
                    .accessibilityAddTraits(.isHeader)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .accessibilityElement(children: .combine)

            Button(action: onSearch) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 18, weight: .regular))
                    .foregroundStyle(TE.textPrimary)
                    .frame(width: 44, height: 44)
                    .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Search places")
            .accessibilityHint("Finds saved places and visits and shows them on the map.")
        }
        .padding(.leading, 14)
        .padding(.trailing, 6)
        .padding(.vertical, 6)
        .mapCard(cornerRadius: 22)
    }
}

/// Today · Yesterday · 7D · 30D · All · calendar.
struct MapRangeChips: View {
    static let presets: [MapDatePreset] = [.today, .yesterday, .sevenDays, .thirtyDays, .all]

    let selected: MapDatePreset?
    let onSelect: (MapDatePreset) -> Void
    let onCustom: () -> Void

    /// "This month" and picked ranges both live behind the calendar.
    private var isCustom: Bool { selected.map { !Self.presets.contains($0) } ?? true }

    var body: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 6) {
                ForEach(Self.presets, id: \.self) { preset in
                    chip(preset)
                }
                Button(action: onCustom) {
                    Image(systemName: "calendar")
                        .font(.system(size: 14, weight: .regular))
                        .foregroundStyle(isCustom ? MapHomeStyle.invertedText : TE.textPrimary)
                        .frame(width: 34, height: 34)
                        .background(Circle().fill(isCustom ? MapHomeStyle.invertedFill : TE.card))
                        .overlay(Circle().strokeBorder(isCustom ? Color.clear : TE.border, lineWidth: 1))
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Custom range")
                .accessibilityAddTraits(isCustom ? .isSelected : [])
                .accessibilityHint("Picks any day or range from a calendar.")
            }
        }
        .scrollIndicators(.hidden)
        .scrollClipDisabled()
    }

    private func chip(_ preset: MapDatePreset) -> some View {
        let isOn = selected == preset
        return Button { onSelect(preset) } label: {
            Text(preset.label)
                .font(.system(size: 12, weight: .medium))
                .foregroundStyle(isOn ? MapHomeStyle.invertedText : TE.textPrimary)
                .padding(.horizontal, 12)
                .frame(minHeight: 34)
                .background(Capsule().fill(isOn ? MapHomeStyle.invertedFill : TE.card))
                .overlay(Capsule().strokeBorder(isOn ? Color.clear : TE.border, lineWidth: 1))
                .frame(minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(preset.accessibilityLabel)
        .accessibilityAddTraits(isOn ? .isSelected : [])
        .accessibilityHint("Filters the map date range.")
    }
}

// MARK: - Right rail

/// The 56-point live day dial that opens Replay.
struct MapMiniDialButton: View {
    let arcs: [DialArc]
    let now: Date
    let showsNow: Bool
    let accessibilityValue: String
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            DayDial(segments: arcs, now: now, recordingSince: nil, captions: .none, showsNowMarker: showsNow)
                .frame(width: 44, height: 44)
                .frame(width: 56, height: 56)
                .background(Circle().fill(TE.card).mapCardShadow())
                .overlay(Circle().strokeBorder(TE.border, lineWidth: 1))
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel("Replay the day")
        .accessibilityValue(accessibilityValue)
        .accessibilityHint("Plays the route back on the map.")
    }
}

/// Layers, Fit (tap fits the range, the menu also fits the live session), and My location.
struct MapToolStack: View {
    let onLayers: () -> Void
    let onFitContent: () -> Void
    let onFitSession: (() -> Void)?
    let onMyLocation: () -> Void

    var body: some View {
        VStack(spacing: 0) {
            cell(systemImage: "square.3.layers.3d", label: "Layers", hint: "Chooses what the map shows.", action: onLayers)
            Menu {
                if let onFitSession {
                    Button("Fit Active Session", systemImage: "location.north.line", action: onFitSession)
                }
                Button("Fit All Visits", systemImage: "arrow.up.left.and.arrow.down.right", action: onFitContent)
            } label: {
                icon("viewfinder")
            } primaryAction: {
                onFitContent()
            }
            .accessibilityLabel("Fit the day")
            .accessibilityHint("Zooms to everything in the range. Hold for more options.")
            cell(systemImage: "location", label: "Show my location", hint: "Centers the map on where you are.", action: onMyLocation)
        }
        .padding(2)
        .mapCard(cornerRadius: 16)
    }

    private func icon(_ systemImage: String) -> some View {
        Image(systemName: systemImage)
            .font(.system(size: 18, weight: .regular))
            .foregroundStyle(TE.textPrimary)
            .frame(width: 44, height: 44)
            .contentShape(RoundedRectangle(cornerRadius: 14))
    }

    private func cell(systemImage: String, label: LocalizedStringKey, hint: LocalizedStringKey, action: @escaping () -> Void) -> some View {
        Button(action: action) { icon(systemImage) }
            .buttonStyle(.plain)
            .accessibilityLabel(Text(label))
            .accessibilityHint(Text(hint))
    }
}

// MARK: - Recording

/// The old tracking pill and auto-off pill in one ink capsule: "RECORDING · 1,182 PTS" over
/// "AUTO-OFF IN 1:40" with a stop button, or a standby capsule that starts recording.
struct MapRecordingBar: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Bindable var viewModel: LocationViewModel
    @ObservedObject var locationManager: LocationManager
    let isTracking: Bool
    let onPrimaryTap: () -> Void
    @State private var pulses = false

    var body: some View {
        HStack(spacing: 10) {
            Circle()
                .fill(isTracking ? MapHomeStyle.vermilion : TE.textMuted.opacity(0.5))
                .frame(width: 9, height: 9)
                .background(Circle().fill(TE.liveDot.opacity(isTracking ? 0.3 : 0)).padding(-4))
                .opacity(isTracking && pulses ? 0.45 : 1)
                .animation(isTracking && !reduceMotion ? .easeInOut(duration: 1.2).repeatForever(autoreverses: true) : nil, value: pulses)
                .accessibilityHidden(true)

            TimelineView(.periodic(from: .now, by: 1)) { _ in
                VStack(alignment: .leading, spacing: 1) {
                    // Large text wraps instead of clipping.
                    Text(headline)
                        .font(WheredayFont.mono(size: 10, weight: .semibold, relativeTo: .caption2))
                        .tracking(1.2)
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? 3 : 1)
                        .minimumScaleFactor(dynamicTypeSize.isAccessibilitySize ? 1 : 0.7)
                    Text(detail)
                        .font(WheredayFont.mono(size: 9, relativeTo: .caption2))
                        .tracking(1.2)
                        .lineLimit(dynamicTypeSize.isAccessibilitySize ? 3 : 1)
                        .minimumScaleFactor(dynamicTypeSize.isAccessibilitySize ? 1 : 0.7)
                        .opacity(isTracking ? 1 : 0.8)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(isTracking ? Text("Session summary") : Text("Tracking is off."))
            .accessibilityValue(isTracking ? viewModel.sessionAccessibilitySummary : "")

            Button(action: onPrimaryTap) {
                Group {
                    if isTracking {
                        RoundedRectangle(cornerRadius: 3)
                            .fill(isTracking ? TE.card : TE.surface)
                            .frame(width: 12, height: 12)
                    } else {
                        Image(systemName: "record.circle")
                            .font(.system(size: 18, weight: .semibold))
                            .foregroundStyle(MapHomeStyle.invertedText)
                    }
                }
                .frame(width: 38, height: 38)
                .background(Circle().fill(isTracking ? MapHomeStyle.vermilion : MapHomeStyle.invertedFill))
                .frame(width: 44, height: 44)
                .contentShape(Circle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel(isTracking ? "Stop tracking" : "Start tracking")
            .accessibilityValue(isTracking ? viewModel.sessionAccessibilitySummary : String(localized: "Tracking is off."))
            .accessibilityHint(isTracking ? "Stops the active session." : "Starts a new location tracking session.")
            .sensoryFeedback(.impact(flexibility: .solid), trigger: isTracking)
        }
        .foregroundStyle(isTracking ? MapHomeStyle.invertedText : TE.textPrimary)
        .padding(.leading, 16)
        .padding(.trailing, 3)
        .padding(.vertical, dynamicTypeSize.isAccessibilitySize ? 8 : 0)
        .frame(minHeight: 48)
        .background {
            RoundedRectangle(cornerRadius: 24, style: .continuous)
                .fill(isTracking ? MapHomeStyle.invertedFill : TE.card)
                .mapCardShadow()
        }
        .overlay {
            if !isTracking { RoundedRectangle(cornerRadius: 24, style: .continuous).strokeBorder(TE.border, lineWidth: 1) }
        }
        .onAppear { pulses = !reduceMotion }
        .onChange(of: reduceMotion) { _, reduce in pulses = !reduce }
    }

    private var headline: String {
        guard isTracking else { return String(localized: "STANDBY") }
        let points = viewModel.sessionLocationPoints.count.formatted()
        return String(localized: "RECORDING · \(points) PTS")
    }

    private var detail: String {
        guard isTracking else { return String(localized: "TAP TO START RECORDING") }
        if let remaining = locationManager.remainingTime {
            return String(localized: "AUTO-OFF IN \(Self.clock(remaining))")
        }
        return "\(viewModel.formattedSessionTrackingDuration) · \(viewModel.formattedSessionDistance)".uppercased()
    }

    /// "1:40" for an hour and forty minutes, "0:12" for twelve minutes.
    static func clock(_ interval: TimeInterval) -> String {
        let minutes = max(0, Int(interval) / 60)
        return String(format: "%d:%02d", minutes / 60, minutes % 60)
    }
}

/// The round "+" next to the recording bar.
struct MapAddButton: View {
    let isOpen: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: isOpen ? "xmark" : "plus")
                .font(.system(size: 19, weight: .medium))
                .foregroundStyle(TE.textPrimary)
                .frame(width: 48, height: 48)
                .background(Circle().fill(TE.card).mapCardShadow())
                .overlay(Circle().strokeBorder(TE.border, lineWidth: 1))
                .contentShape(Circle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(isOpen ? "Close" : "Add a place")
        .accessibilityHint("Opens options to save your current place or add a past visit.")
    }
}

/// The ink menu over the "+": Save where I am, Log a past stop, Drop a pin.
struct MapAddMenu: View {
    let onSaveCurrentPlace: () -> Void
    let onAddPastVisit: () -> Void
    let onDropPin: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 0) {
            row("Save where I am", systemImage: "location.fill", action: onSaveCurrentPlace)
            row("Log a past stop", systemImage: "clock.arrow.circlepath", action: onAddPastVisit)
            row("Drop a pin", systemImage: "mappin.and.ellipse", action: onDropPin)
        }
        .padding(6)
        .frame(width: 250)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(MapHomeStyle.invertedFill).mapCardShadow())
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Add place")
    }

    private func row(_ title: LocalizedStringKey, systemImage: String, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: 12) {
                Image(systemName: systemImage)
                    .font(.system(size: 16, weight: .regular))
                    .frame(width: 20)
                    .accessibilityHidden(true)
                Text(title)
                    .font(.system(size: 14, weight: .medium))
                    .frame(maxWidth: .infinity, alignment: .leading)
            }
            .foregroundStyle(MapHomeStyle.invertedText)
            .padding(.horizontal, 12)
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }
}

// MARK: - Peek card

/// What the closed day sheet shows: where you are (or the day's summary) beside a small dial.
struct MapPeekContent {
    var kicker: String
    var title: String
    var value: String
    var caption: String
    var arcs: [DialArc]
    var showsNow: Bool
}

struct MapPeekCard: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let content: MapPeekContent
    let now: Date
    let action: () -> Void

    var body: some View {
        if dynamicTypeSize.isAccessibilitySize {
            largeText
        } else {
            regular
        }
    }

    /// At accessibility sizes the card stacks its lines and lets them wrap.
    private var largeText: some View {
        Button(action: action) {
            VStack(alignment: .leading, spacing: 4) {
                Text(content.kicker)
                    .font(WheredayFont.mono(size: 9, weight: .medium, relativeTo: .caption2))
                    .foregroundStyle(TE.textMuted)
                Text(content.title)
                    .font(WheredayFont.serif(size: 18, relativeTo: .headline))
                    .foregroundStyle(TE.textPrimary)
                    .lineLimit(3)
                Text(verbatim: "\(content.value) · \(content.caption)")
                    .font(WheredayFont.mono(size: 9, weight: .medium, relativeTo: .caption2))
                    .foregroundStyle(TE.textMuted)
            }
            .multilineTextAlignment(.leading)
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(14)
            .mapCard(cornerRadius: 22)
            .contentShape(RoundedRectangle(cornerRadius: 22))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Open the day so far")
        .accessibilityValue("\(content.kicker), \(content.title). \(content.value), \(content.caption)")
        .accessibilityAddTraits(.isButton)
    }

    private var regular: some View {
        Button(action: action) {
            HStack(spacing: 12) {
                DayDial(segments: content.arcs, now: now, recordingSince: nil, captions: .none, showsNowMarker: content.showsNow)
                    .frame(width: 48, height: 48)
                    .accessibilityHidden(true)
                VStack(alignment: .leading, spacing: 2) {
                    Text(content.kicker)
                        .font(WheredayFont.mono(size: 9, weight: .medium, relativeTo: .caption2))
                        .tracking(1.2)
                        .foregroundStyle(TE.textMuted)
                        .lineLimit(1)
                    Text(content.title)
                        .font(WheredayFont.serif(size: 18, relativeTo: .headline))
                        .foregroundStyle(TE.textPrimary)
                        .lineLimit(1)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                VStack(alignment: .trailing, spacing: 2) {
                    Text(content.value)
                        .font(WheredayFont.serif(size: 16, relativeTo: .subheadline))
                        .foregroundStyle(TE.textPrimary)
                        .lineLimit(1)
                    Text(content.caption)
                        .font(WheredayFont.mono(size: 9, weight: .medium, relativeTo: .caption2))
                        .tracking(1.2)
                        .foregroundStyle(TE.textMuted)
                        .lineLimit(1)
                }
                Image(systemName: "chevron.up")
                    .font(.system(size: 13, weight: .semibold))
                    .foregroundStyle(TE.textMuted)
                    .accessibilityHidden(true)
            }
            .padding(.leading, 8)
            .padding(.trailing, 14)
            .frame(minHeight: 64)
            .mapCard(cornerRadius: 22)
            .contentShape(RoundedRectangle(cornerRadius: 22))
        }
        .buttonStyle(.plain)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Open the day so far")
        .accessibilityValue("\(content.kicker), \(content.title). \(content.value), \(content.caption)")
        .accessibilityAddTraits(.isButton)
    }
}

// MARK: - Are you here?

/// The old current-place suggestion, as a card: "ARE YOU HERE? · 6 MIN", the place, how far it is,
/// and Somewhere else / Save this place. Waiting, permission and failure states keep their old wording.
struct MapHereCard: View {
    let suggestion: NearbyPlaceSuggestion?
    let isSavedPlace: Bool
    let minutesHere: Int?
    let isLoading: Bool
    let lookupFailed: Bool
    let hasFreshLocation: Bool
    let hasLocationPermission: Bool
    let onSave: () -> Void
    let onSomewhereElse: () -> Void
    let onRequestLocation: () -> Void
    let onDismiss: () -> Void

    @AppStorage("usesMetricDistanceUnits") private var usesMetricDistanceUnits = true
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    private var canSave: Bool { suggestion != nil || hasFreshLocation }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 8) {
                VStack(alignment: .leading, spacing: 3) {
                    Text(kicker)
                        .font(WheredayFont.mono(size: 10, weight: .semibold, relativeTo: .caption2))
                        .tracking(1.2)
                        .foregroundStyle(MapHomeStyle.vermilion)
                    Text(title)
                        .font(WheredayFont.serif(size: 24, relativeTo: .title2))
                        .foregroundStyle(TE.textPrimary)
                        .lineLimit(2)
                    Text(subtitle)
                        .font(WheredayFont.mono(size: 10, weight: .medium, relativeTo: .caption2))
                        .tracking(1)
                        .foregroundStyle(TE.textMuted)
                        .lineLimit(2)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)

                Button(action: onDismiss) {
                    Image(systemName: "xmark")
                        .font(.system(size: 12, weight: .semibold))
                        .foregroundStyle(TE.textMuted)
                        .frame(width: 44, height: 44)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .padding(.top, -10)
                .padding(.trailing, -10)
                .accessibilityLabel("Close visit suggestion")
                .accessibilityHint("Hides this suggested place from the map.")
            }

            if canSave {
                if dynamicTypeSize.isAccessibilitySize {
                    VStack(spacing: 8) { actions }
                } else {
                    // Somewhere else · Save this place, 1 : 1.4.
                    GeometryReader { geometry in
                        let unit = (geometry.size.width - 8) / 2.4
                        HStack(spacing: 8) {
                            somewhereElseButton.frame(width: unit)
                            saveButton.frame(width: unit * 1.4)
                        }
                    }
                    .frame(height: 44)
                }
            } else {
                Button(action: onRequestLocation) {
                    actionLabel(hasLocationPermission ? "Find" : "Allow", filled: false)
                }
                .buttonStyle(.plain)
                .accessibilityHint("Requests a fresh current location.")
            }
        }
        .padding(14)
        .mapCard(cornerRadius: 22)
        .accessibilityElement(children: .contain)
    }

    @ViewBuilder
    private var actions: some View {
        saveButton
        somewhereElseButton
    }

    private var somewhereElseButton: some View {
        Button(action: onSomewhereElse) {
            actionLabel("Somewhere else", filled: false)
        }
        .buttonStyle(.plain)
        .accessibilityHint("Opens manual place entry.")
    }

    private var saveButton: some View {
        Button(action: onSave) {
            actionLabel("Save this place", filled: true)
        }
        .buttonStyle(.plain)
        .accessibilityHint("Opens visit details prefilled with this place.")
    }

    private func actionLabel(_ title: LocalizedStringKey, filled: Bool) -> some View {
        Text(title)
            .font(.system(size: 14, weight: filled ? .semibold : .medium))
            .foregroundStyle(filled ? MapHomeStyle.invertedText : TE.textPrimary)
            .lineLimit(1)
            .minimumScaleFactor(0.8)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(Capsule().fill(filled ? MapHomeStyle.invertedFill : TE.card))
            .overlay(Capsule().strokeBorder(filled ? Color.clear : TE.border, lineWidth: 1))
            .contentShape(Capsule())
    }

    private var kicker: String {
        if let minutesHere, minutesHere > 0 {
            return String(localized: "ARE YOU HERE? · \(minutesHere) MIN")
        }
        return String(localized: "ARE YOU HERE?")
    }

    private var title: String {
        if let suggestion { return suggestion.name }
        if isLoading { return String(localized: "Finding place…") }
        if !hasLocationPermission { return String(localized: "Use location") }
        if lookupFailed { return String(localized: "Add this place") }
        return String(localized: "Save place")
    }

    private var subtitle: String {
        if let suggestion {
            let distance = DistanceFormatter.format(meters: suggestion.distanceMeters, usesMetric: usesMetricDistanceUnits)
            let source = isSavedPlace ? String(localized: "SAVED PLACE") : String(localized: "APPLE MAPS")
            return String(localized: "\(distance) AWAY · \(source)").uppercased()
        }
        if isLoading { return String(localized: "Checking nearby Apple Maps places").uppercased() }
        if !hasLocationPermission { return String(localized: "Allow location to save a visit").uppercased() }
        if lookupFailed { return String(localized: "You can still add details manually").uppercased() }
        if hasFreshLocation { return String(localized: "Add a visit at your current location").uppercased() }
        return String(localized: "Waiting for fresh GPS accuracy").uppercased()
    }
}
