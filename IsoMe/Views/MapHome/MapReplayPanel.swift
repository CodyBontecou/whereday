import SwiftUI
import MapKit

// Moved from MapView.swift: the trace of a day and the replay sheet (board M3).

// MARK: - Trace the day

/// The selected range laid out as a time axis: stays as inked segments, and the places to jump between.
@MainActor
struct TraceDay {
    struct Segment: Identifiable {
        let id: UUID
        let start: Double
        let end: Double
        let ink: Color
    }

    struct Place: Identifiable {
        var id: String { name }
        let name: String
        let ink: Color
        /// The first stay at this place within the range.
        let visit: Visit
    }

    let domain: ClosedRange<Date>
    let isSingleDay: Bool
    let visits: [Visit]
    let segments: [Segment]
    let places: [Place]
    let tickLabels: [String]
    private let inks: [String: Color]
    private let home: SavedPlace?

    init(range: ClosedRange<Date>, visits: [Visit], points: [LocationPoint], now: Date, home: SavedPlace? = nil) {
        let calendar = Calendar.current
        let sorted = visits.sorted { $0.arrivedAt < $1.arrivedAt }
        self.visits = sorted
        self.home = home

        // Places take inks as on the Today dial: Home first when saved, then in order of first arrival.
        var order = PlaceInkOrder(leading: home?.name)
        var inks: [String: Color] = [:]
        var places: [Place] = []
        for visit in sorted {
            let name = PlaceInkOrder.placeName(for: visit, home: home)
            guard inks[name] == nil else { continue }
            let ink = PlaceInkOrder.ink(at: order.index(for: name))
            inks[name] = ink
            places.append(Place(name: name, ink: ink, visit: visit))
        }
        self.inks = inks
        self.places = places

        let isSingleDay = range.lowerBound != .distantPast
            && calendar.isDate(range.lowerBound, inSameDayAs: range.upperBound)
        self.isSingleDay = isSingleDay

        let domain: ClosedRange<Date>
        if isSingleDay {
            let start = calendar.startOfDay(for: range.lowerBound)
            let end = calendar.date(byAdding: .day, value: 1, to: start) ?? start.addingTimeInterval(86_400)
            domain = start...end
        } else {
            // Longer ranges span only the recorded stretch, never the empty past.
            let upper = min(range.upperBound, now)
            let earliest = [points.first?.timestamp, sorted.first?.arrivedAt].compactMap { $0 }.min()
            var lower = max(range.lowerBound, earliest ?? range.lowerBound)
            if lower == .distantPast || lower >= upper {
                lower = upper.addingTimeInterval(-86_400)
            }
            domain = lower...upper
        }
        self.domain = domain

        let span = domain.upperBound.timeIntervalSince(domain.lowerBound)
        segments = sorted.compactMap { visit in
            let start = max(visit.arrivedAt, domain.lowerBound)
            let end = min(visit.departedAt ?? now, now, domain.upperBound)
            guard end > start, span > 0 else { return nil }
            return Segment(
                id: visit.id,
                start: start.timeIntervalSince(domain.lowerBound) / span,
                end: end.timeIntervalSince(domain.lowerBound) / span,
                ink: inks[PlaceInkOrder.placeName(for: visit, home: home)] ?? TE.accent
            )
        }

        if isSingleDay {
            tickLabels = ["00", "06", "12", "18", "24"]
        } else {
            let middle = domain.lowerBound.addingTimeInterval(span / 2)
            tickLabels = [domain.lowerBound, middle, domain.upperBound]
                .map { $0.formatted(.dateTime.month(.abbreviated).day()) }
        }
    }

    private var span: TimeInterval {
        domain.upperBound.timeIntervalSince(domain.lowerBound)
    }

    func ink(for visit: Visit) -> Color {
        inks[placeKey(for: visit)] ?? TE.accent
    }

    /// The name a visit's place goes by on the dial and the map (Home inside a saved Home).
    func placeKey(for visit: Visit) -> String {
        PlaceInkOrder.placeName(for: visit, home: home)
    }

    func ink(forPlace key: String?) -> Color {
        key.flatMap { inks[$0] } ?? TE.textPrimary
    }

    func fraction(of date: Date) -> Double {
        guard span > 0 else { return 1 }
        return min(max(date.timeIntervalSince(domain.lowerBound) / span, 0), 1)
    }

    func date(at fraction: Double) -> Date {
        domain.lowerBound.addingTimeInterval(span * min(max(fraction, 0), 1))
    }

    /// Where "now" falls on a single day's axis; the rest of the day is drawn as not yet lived.
    func nowFraction(_ now: Date) -> Double? {
        guard isSingleDay, domain.contains(now) else { return nil }
        return fraction(of: now)
    }

    func visit(at time: Date, now: Date) -> Visit? {
        visits.last { $0.arrivedAt <= time && ($0.departedAt ?? now) >= time }
    }
}

/// The paper sheet under the map: where you were at the scrubbed moment, the day's
/// stays as a bar, a time scrubber, and the places to jump between.
/// Everything the replay controls show and do at one moment of the trace.
@MainActor
struct TraceReplayPanelModel {
    let kicker: String
    let title: String
    let clock: String
    let day: TraceDay
    let nowFraction: Double?
    let focusFraction: Double
    let focusedPlaceID: String?
    let distanceText: String
    /// "Drag to replay · now 14:20".
    let hint: String?
    let isReplaying: Bool
    let isPlaying: Bool
    let canPlay: Bool
    let onScrub: (Double) -> Void
    let onStep: (TimeInterval) -> Void
    let onJump: (TraceDay.Place) -> Void
    let onPlayPause: () -> Void
    let onDone: () -> Void
    var speed: ReplaySpeed = .ten
    var onSpeed: (ReplaySpeed) -> Void = { _ in }
    /// "612 OF 1,182 PTS".
    var pointsText: String? = nil
    /// Reduce Motion: Play jumps to the next stop instead of animating.
    var playsByStop = false

    /// Changes whenever anything drawn changes, so a published copy is replaced in step.
    var signature: [String] {
        [
            kicker, title, clock, distanceText, hint ?? "", pointsText ?? "",
            focusedPlaceID ?? "",
            String(format: "%.5f", focusFraction),
            nowFraction.map { String(format: "%.5f", $0) } ?? "-",
            "\(isReplaying)\(isPlaying)\(canPlay)\(speed.rawValue)\(playsByStop)",
            "\(day.domain.lowerBound.timeIntervalSinceReferenceDate)-\(day.domain.upperBound.timeIntervalSinceReferenceDate)",
            day.places.map(\.name).joined(separator: "|"),
            day.segments.map { "\($0.id)\($0.start)\($0.end)" }.joined(separator: "|")
        ]
    }
}

/// Carries the trace's replay controls from the map to another pane: on a half-open
/// Duo the map stays above the fold and the scrubber, places and steps sit below it.
@MainActor
@Observable
final class TraceReplayBridge {
    var panel: TraceReplayPanelModel?

    init() {}
}

/// The pane below the fold on a half-open Duo: the trace's replay controls, full width.
struct TraceTabletopReplayPane: View {
    @Bindable var bridge: TraceReplayBridge

    var body: some View {
        Group {
            if let panel = bridge.panel {
                TraceScrubPanel(
                    model: panel,
                    isExpandedLayout: true,
                    maxHeight: .infinity,
                    isOwnPane: true
                )
                .frame(maxHeight: .infinity, alignment: .top)
            } else {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(TE.surface.ignoresSafeArea())
    }
}

/// The replay sheet (board M3): where you were at the playhead ("WALKING · 4 KM/H" / "Along Market Street")
/// with a big clock, the day's stays as a segmented bar over the scrubber, play/pause with 1× · 10× · 60×,
/// Done, and "4.1 KM SO FAR · 612 OF 1,182 PTS".
struct TraceScrubPanel: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let model: TraceReplayPanelModel
    /// Wide and tabletop layouts get larger type, place chips and step buttons.
    let isExpandedLayout: Bool
    let maxHeight: CGFloat
    /// A pane of its own (half-open) draws no sheet edge.
    var isOwnPane = false

    private var day: TraceDay { model.day }

    var body: some View {
        // Hug the content, scrolling only once it would pass the cap.
        CappedHeightLayout(maxHeight: maxHeight) {
            ViewThatFits(in: .vertical) {
                panel
                ScrollView { panel }
                    .scrollIndicators(.hidden)
            }
            .background {
                if isOwnPane {
                    TE.surface.ignoresSafeArea()
                } else {
                    UnevenRoundedRectangle(topLeadingRadius: 26, topTrailingRadius: 26, style: .continuous)
                        .fill(TE.surface)
                        .shadow(color: MapHomeStyle.shadow, radius: 15, x: 0, y: -10)
                        .ignoresSafeArea(edges: .bottom)
                }
            }
        }
    }

    private var panel: some View {
        VStack(alignment: .leading, spacing: isExpandedLayout ? 16 : 12) {
            header

            VStack(spacing: 0) {
                TraceDayBar(segments: day.segments, nowFraction: model.nowFraction)
                    .frame(height: isExpandedLayout ? 12 : 8)

                TraceScrubber(
                    fraction: model.focusFraction,
                    accessibilityValue: "\(model.clock). \(model.title)",
                    onScrub: model.onScrub,
                    onStep: model.onStep
                )
                .accessibilityHint(Text(model.hint ?? ""))

                HStack(spacing: 0) {
                    ForEach(Array(day.tickLabels.enumerated()), id: \.offset) { index, label in
                        if index > 0 { Spacer(minLength: 4) }
                        Text(label)
                    }
                }
                .font(TE.mono(.caption2))
                .foregroundStyle(TE.textMuted)
                .padding(.top, -8)
                .accessibilityHidden(true)
            }

            if isExpandedLayout && !day.places.isEmpty {
                TraceFlowLayout(spacing: 8) {
                    ForEach(day.places) { place in
                        TracePlaceChip(place: place, isSelected: place.id == model.focusedPlaceID) {
                            model.onJump(place)
                        }
                    }
                }
            }

            if isExpandedLayout {
                stepButtons
            }

            controls
            footer
        }
        .frame(maxWidth: 720, alignment: .leading)
        .padding(.horizontal, isExpandedLayout ? 32 : 22)
        .padding(.top, 18)
        .padding(.bottom, isOwnPane ? 24 : 12)
        .frame(maxWidth: .infinity)
        .foregroundStyle(TE.textPrimary)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Replay controls")
    }

    private var header: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
            : AnyLayout(HStackLayout(alignment: .lastTextBaseline, spacing: 12))
        return layout {
            VStack(alignment: .leading, spacing: 3) {
                Text(model.kicker)
                    .font(WheredayFont.mono(size: 10.5, weight: .medium, relativeTo: .caption2))
                    .tracking(1.4)
                    .foregroundStyle(TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
                Text(model.title)
                    .font(WheredayFont.serif(size: isExpandedLayout ? 32 : 24, relativeTo: .title))
                    .lineLimit(2)
                    .minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(model.clock)
                .font(WheredayFont.serif(size: isExpandedLayout ? 60 : 42, relativeTo: .largeTitle))
                .monospacedDigit()
                .lineLimit(1)
                .fixedSize()
        }
        .accessibilityElement(children: .combine)
    }

    /// Play/pause, speed chips, Done.
    private var controls: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
            : AnyLayout(HStackLayout(spacing: 8))
        return layout {
            HStack(spacing: 8) {
                Button(action: model.onPlayPause) {
                    Image(systemName: model.playsByStop ? "forward.end.fill" : (model.isPlaying ? "pause.fill" : "play.fill"))
                        .font(.system(size: 18, weight: .bold))
                        .foregroundStyle(MapHomeStyle.invertedText)
                        .frame(width: 52, height: 52)
                        .background(Circle().fill(MapHomeStyle.invertedFill))
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .disabled(!model.canPlay)
                .opacity(model.canPlay ? 1 : 0.5)
                .accessibilityLabel(model.playsByStop ? Text("Next stop") : (model.isPlaying ? Text("Pause replay") : Text("Play replay")))
                .accessibilityHint("Plays the recorded route from the playhead.")

                if !model.playsByStop {
                    ForEach(ReplaySpeed.allCases) { speed in
                        chip(speed.label, isOn: speed == model.speed) { model.onSpeed(speed) }
                            .accessibilityLabel(Text("Speed \(speed.label)"))
                            .accessibilityAddTraits(speed == model.speed ? .isSelected : [])
                    }
                }
            }
            if !dynamicTypeSize.isAccessibilitySize { Spacer(minLength: 0) }
            chip(String(localized: "Done"), isOn: false, action: model.onDone)
                .accessibilityHint("Ends the replay and draws the whole route.")
        }
    }

    private func chip(_ title: String, isOn: Bool, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            Text(title)
                .font(.system(size: 13, weight: .medium))
                .foregroundStyle(isOn ? MapHomeStyle.invertedText : TE.textPrimary)
                .lineLimit(1)
                .padding(.horizontal, 14)
                .frame(minHeight: 40)
                .background(Capsule().fill(isOn ? MapHomeStyle.invertedFill : TE.card))
                .overlay(Capsule().strokeBorder(isOn ? Color.clear : TE.border, lineWidth: 1))
                .frame(minHeight: 44)
                .contentShape(Capsule())
        }
        .buttonStyle(.plain)
    }

    private var stepButtons: some View {
        HStack(spacing: 10) {
            Button { model.onStep(-15 * 60) } label: {
                Text("−15 min").stepLabel(filled: false)
            }
            .accessibilityLabel("Back 15 minutes")

            Button { model.onStep(15 * 60) } label: {
                Text("+15 min").stepLabel(filled: false)
            }
            .accessibilityLabel("Forward 15 minutes")
        }
        .buttonStyle(.plain)
    }

    private var footer: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 12) {
                Text(model.distanceText.uppercased())
                Spacer(minLength: 8)
                if let pointsText = model.pointsText { Text(pointsText) }
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(model.distanceText.uppercased())
                if let pointsText = model.pointsText { Text(pointsText) }
            }
        }
        .font(WheredayFont.mono(size: 10.5, weight: .medium, relativeTo: .caption2))
        .tracking(1)
        .foregroundStyle(MapHomeStyle.secondaryText)
        .padding(.top, 10)
        .overlay(alignment: .top) {
            Rectangle().fill(TE.border).frame(height: 1)
        }
    }
}

private extension Text {
    func stepLabel(filled: Bool) -> some View {
        self
            .font(.subheadline.weight(filled ? .semibold : .medium))
            .foregroundStyle(filled ? TE.onInkCard : TE.textPrimary)
            .lineLimit(1)
            .minimumScaleFactor(0.8)
            .frame(maxWidth: .infinity, minHeight: 56)
            .background(Capsule().fill(filled ? TE.accent : TE.card))
            .overlay(Capsule().strokeBorder(filled ? Color.clear : TE.border, lineWidth: 1))
            .contentShape(Capsule())
    }
}

/// The day's stays as one bar; the part of today not yet lived is hatched.
/// Offers its content at most `maxHeight` and takes only the height the content uses,
/// unlike `.frame(maxHeight:)`, which grows to the cap.
private struct CappedHeightLayout: Layout {
    let maxHeight: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let content = subviews.first else { return .zero }
        return content.sizeThatFits(cappedProposal(proposal))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(at: bounds.origin, proposal: ProposedViewSize(width: bounds.width, height: bounds.height))
    }

    private func cappedProposal(_ proposal: ProposedViewSize) -> ProposedViewSize {
        ProposedViewSize(width: proposal.width, height: min(proposal.height ?? maxHeight, maxHeight))
    }
}

struct TraceDayBar: View {
    let segments: [TraceDay.Segment]
    let nowFraction: Double?

    var body: some View {
        Canvas { context, size in
            let bar = Path(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: size.height / 2)
            context.clip(to: bar)
            context.fill(bar, with: .color(TE.dialTrack))

            for segment in segments {
                let rect = CGRect(
                    x: size.width * segment.start,
                    y: 0,
                    width: max(1, size.width * (segment.end - segment.start)),
                    height: size.height
                )
                context.fill(Path(rect), with: .color(segment.ink))
            }

            if let nowFraction {
                var stripe = size.width * nowFraction + 4
                while stripe < size.width {
                    context.fill(Path(CGRect(x: stripe, y: 0, width: 4, height: size.height)), with: .color(TE.surface))
                    stripe += 8
                }
            }
        }
        .accessibilityHidden(true)
    }
}

/// A rust-ringed thumb on a hairline; VoiceOver adjusts it in 15-minute steps.
struct TraceScrubber: View {
    let fraction: Double
    let accessibilityValue: String
    let onScrub: (Double) -> Void
    let onStep: (TimeInterval) -> Void

    private let thumbSize: CGFloat = 26

    var body: some View {
        GeometryReader { geometry in
            let width = geometry.size.width
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(TE.border)
                    .frame(height: 4)

                Circle()
                    .fill(TE.surface)
                    .overlay(Circle().strokeBorder(TE.inkRust, lineWidth: 3))
                    .frame(width: thumbSize, height: thumbSize)
                    .position(x: width * fraction, y: geometry.size.height / 2)
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        guard width > 0 else { return }
                        onScrub(Double(value.location.x / width))
                    }
            )
        }
        .frame(height: 44)
        .accessibilityElement()
        .accessibilityLabel("Time of day")
        .accessibilityValue(accessibilityValue)
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: onStep(15 * 60)
            case .decrement: onStep(-15 * 60)
            @unknown default: break
            }
        }
    }
}

struct TracePlaceChip: View {
    let place: TraceDay.Place
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Circle()
                    .fill(place.ink)
                    .frame(width: 8, height: 8)
                Text(place.name)
                    .lineLimit(1)
            }
            .font(.footnote.weight(.medium))
            .foregroundStyle(TE.textPrimary)
            .padding(.horizontal, 14)
            .frame(minHeight: 44)
            .background(Capsule().fill(TE.card))
            .overlay(Capsule().strokeBorder(isSelected ? TE.inkRust : TE.border, lineWidth: isSelected ? 1.5 : 1))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(place.name)
        .accessibilityHint("Moves the replay to this place.")
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

/// Wraps chips onto new lines when there is room for the whole set.
struct TraceFlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var width: CGFloat = 0
        for subview in subviews {
            var size = subview.sizeThatFits(.unspecified)
            size.width = min(size.width, maxWidth)
            if x > 0, x + size.width > maxWidth {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            width = max(width, x + size.width)
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: width, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0
        for subview in subviews {
            var size = subview.sizeThatFits(.unspecified)
            size.width = min(size.width, bounds.width)
            if x > bounds.minX, x + size.width > bounds.maxX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

