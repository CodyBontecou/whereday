import SwiftUI

// Drawings for the first-run flow. Every color comes from the TE tokens, so they follow dark mode.

/// One ring segment as fractions of the day, midnight at the top.
struct OnboardingRingSegment: Identifiable {
    let id: Int
    let start: Double
    let end: Double
    let ink: Color
    /// Seconds before this segment starts drawing itself on the cover.
    var delay: Double = 0
}

/// A plain 24-hour ring with stays drawn as arcs. `progress` grows each arc from its start.
struct OnboardingRing: View {
    let segments: [OnboardingRingSegment]
    var progress: [Int: Double] = [:]
    var lineWidthRatio: CGFloat = 22.0 / 260.0
    var insetRatio: CGFloat = 30.0 / 260.0

    var body: some View {
        GeometryReader { geometry in
            let size = min(geometry.size.width, geometry.size.height)
            let lineWidth = size * lineWidthRatio
            let inset = size * insetRatio
            ZStack {
                Circle()
                    .inset(by: inset)
                    .stroke(TE.dialTrack, lineWidth: lineWidth)
                ForEach(segments) { segment in
                    let fraction = progress[segment.id] ?? 1
                    Circle()
                        .inset(by: inset)
                        .trim(from: segment.start, to: segment.start + (segment.end - segment.start) * fraction)
                        .stroke(segment.ink, style: StrokeStyle(lineWidth: lineWidth, lineCap: .butt))
                        .rotationEffect(.degrees(-90))
                }
            }
            .frame(width: size, height: size)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
    }
}

/// The cover's dial: today's date in the middle while the first places draw themselves in.
struct OnboardingCoverDial: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @State private var drawn = false

    private let segments = [
        OnboardingRingSegment(id: 0, start: 0, end: 0.342, ink: TE.placeInks[0]),
        OnboardingRingSegment(id: 1, start: 0.361, end: 0.392, ink: TE.placeInks[1], delay: 0.5),
        OnboardingRingSegment(id: 2, start: 0.41, end: 0.49, ink: TE.placeInks[2].opacity(0.5), delay: 0.5)
    ]

    var body: some View {
        ZStack {
            ForEach(segments) { segment in
                OnboardingRing(segments: [segment], progress: [segment.id: drawn ? 1 : 0])
                    .animation(reduceMotion ? nil : .easeOut(duration: 1.6).delay(segment.delay), value: drawn)
            }
            VStack(spacing: 2) {
                Text(Date.now.formatted(.dateTime.weekday(.abbreviated)))
                    .font(.custom(WheredayFont.serifFamily, fixedSize: 40))
                Text(Date.now.formatted(.dateTime.month(.abbreviated).day()).uppercased())
                    .font(TE.mono(.caption))
                    .tracking(0.7)
                    .foregroundStyle(TE.textMuted)
            }
        }
        .onAppear { drawn = true }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("A day dial for today, with its first places starting to fill in"))
        .accessibilityAddTraits(.isImage)
    }
}

/// The sample day's dial with a draggable "now" knob.
struct OnboardingScrubDial: View {
    @Binding var hour: Double

    private var segments: [OnboardingRingSegment] {
        OnboardingSampleStop.day.enumerated().compactMap { index, stop in
            guard case let .place(ink) = stop.kind else { return nil }
            return OnboardingRingSegment(id: index, start: stop.start / 24, end: stop.end / 24, ink: TE.placeInks[ink])
        }
    }

    var body: some View {
        GeometryReader { geometry in
            let size = min(geometry.size.width, geometry.size.height)
            let radius = size * 100 / 260
            let angle = hour / 24 * 2 * Double.pi
            ZStack {
                OnboardingRing(segments: segments)
                hourLabel(0, size: size, at: CGPoint(x: 0.5, y: 17 / 260))
                hourLabel(6, size: size, at: CGPoint(x: 250 / 260, y: 0.5))
                hourLabel(12, size: size, at: CGPoint(x: 0.5, y: 249 / 260))
                hourLabel(18, size: size, at: CGPoint(x: 10 / 260, y: 0.5))
                Circle()
                    .fill(TE.surface)
                    .overlay(Circle().strokeBorder(TE.textPrimary, lineWidth: size * 3 / 260))
                    .frame(width: size * 26 / 260, height: size * 26 / 260)
                    .position(x: size / 2 + radius * sin(angle), y: size / 2 - radius * cos(angle))
                Text(ClockTimeFormatter.string(from: Self.date(for: hour)))
                    .font(.custom(WheredayFont.monoFamily, fixedSize: size * 22 / 232).weight(.medium))
                    .monospacedDigit()
                    .lineLimit(1)
                    .minimumScaleFactor(0.5)
                    .frame(maxWidth: size * 0.55)
            }
            .frame(width: size, height: size)
            .contentShape(Circle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        let dx = value.location.x - size / 2
                        let dy = value.location.y - size / 2
                        var turn = atan2(dx, -dy) / (2 * Double.pi)
                        if turn < 0 { turn += 1 }
                        hour = min(23.75, (turn * 24 * 4).rounded() / 4)
                    }
            )
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        // The slider below is the accessible control; the dial repeats it for touch.
        .accessibilityHidden(true)
    }

    private func hourLabel(_ hour: Int, size: CGFloat, at point: CGPoint) -> some View {
        Text(Self.date(for: Double(hour)).formatted(.dateTime.hour(.defaultDigits(amPM: .narrow))))
            .font(.custom(WheredayFont.monoFamily, fixedSize: size * 10 / 232))
            .foregroundStyle(TE.textMuted)
            .position(x: size * point.x, y: size * point.y)
    }

    static func date(for hour: Double, on day: Date = .now) -> Date {
        let minutes = Int((hour * 60).rounded())
        return Calendar.current.date(byAdding: .minute, value: minutes, to: Calendar.current.startOfDay(for: day)) ?? day
    }
}

/// A small street grid with the same trip drawn at each recording detail.
struct OnboardingPenPreview: View {
    let detail: RecordingDetail

    var body: some View {
        Canvas { context, size in
            let sx = size.width / 342
            let sy = size.height / 150
            func p(_ x: CGFloat, _ y: CGFloat) -> CGPoint { CGPoint(x: x * sx, y: y * sy) }

            var streets = Path()
            for y in [40.0, 104.0] { streets.move(to: p(0, y)); streets.addLine(to: p(342, y)) }
            for x in [70.0, 180.0, 286.0] { streets.move(to: p(x, 0)); streets.addLine(to: p(x, 150)) }
            context.stroke(streets, with: .color(TE.dialTrack), lineWidth: 10 * sy)

            let ink = GraphicsContext.Shading.color(TE.textPrimary)
            switch detail {
            case .places:
                var path = Path()
                path.move(to: p(40, 120)); path.addLine(to: p(180, 104)); path.addLine(to: p(292, 30))
                context.stroke(path, with: ink, style: StrokeStyle(lineWidth: 2, dash: [4, 6]))
            case .routes:
                var path = Path()
                path.move(to: p(40, 120))
                for point in [p(70, 104), p(180, 104), p(180, 40), p(286, 40), p(292, 30)] { path.addLine(to: point) }
                context.stroke(path, with: ink, style: StrokeStyle(lineWidth: 3, lineJoin: .round))
            case .everyStep:
                var path = Path()
                path.move(to: p(40, 120))
                path.addCurve(to: p(70, 104), control1: p(52, 114), control2: p(62, 108))
                path.addCurve(to: p(180, 104), control1: p(100, 106), control2: p(140, 101))
                path.addCurve(to: p(180, 40), control1: p(183, 84), control2: p(177, 60))
                path.addCurve(to: p(286, 40), control1: p(214, 43), control2: p(250, 37))
                path.addCurve(to: p(292, 30), control1: p(289, 36), control2: p(290, 33))
                context.stroke(path, with: ink, lineWidth: 2.5)
                for (x, y) in [(55.0, 112.0), (90, 105), (120, 103), (150, 105), (181, 88), (179, 66), (200, 42), (230, 39), (260, 41)] {
                    let center = p(x, y)
                    context.fill(Path(ellipseIn: CGRect(x: center.x - 3, y: center.y - 3, width: 6, height: 6)), with: ink)
                }
            }

            for (point, index) in [(p(40, 120), 0), (p(180, 104), 1), (p(292, 30), 2)] {
                context.fill(Path(ellipseIn: CGRect(x: point.x - 9, y: point.y - 9, width: 18, height: 18)),
                             with: .color(TE.placeInks[index]))
            }
        }
        .frame(height: 150)
        .background(RoundedRectangle(cornerRadius: 16).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(TE.border, lineWidth: 1))
        .clipShape(RoundedRectangle(cornerRadius: 16))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text(accessibilityText))
        .accessibilityAddTraits(.isImage)
    }

    private var accessibilityText: LocalizedStringKey {
        switch detail {
        case .places: "Preview: three stops joined by a rough dotted line"
        case .routes: "Preview: three stops joined by the streets between them"
        case .everyStep: "Preview: three stops joined by a detailed track with every step"
        }
    }
}

/// Proportional colored bars, for the Always vs While Using comparison.
struct OnboardingProportionBar: View {
    /// Weight and ink; a nil ink leaves that stretch empty.
    let parts: [(weight: Double, ink: Color?)]
    var track: Color? = nil

    var body: some View {
        GeometryReader { geometry in
            let total = parts.reduce(0) { $0 + $1.weight }
            let gaps = CGFloat(max(0, parts.count - 1)) * 2
            HStack(spacing: 2) {
                ForEach(parts.indices, id: \.self) { index in
                    Rectangle()
                        .fill(parts[index].ink ?? Color.clear)
                        .frame(width: max(0, (geometry.size.width - gaps) * parts[index].weight / total))
                }
            }
        }
        .frame(height: 14)
        .background(track ?? Color.clear)
        .clipShape(RoundedRectangle(cornerRadius: 4))
    }
}

/// Twelve weeks of days, oldest top-left, filled down each column; today is the last cell.
struct OnboardingHeatmap: View {
    /// One count per day, oldest first.
    let counts: [Int]
    let showsPhotos: Bool

    private let columns = 12
    private let rows = 7

    var body: some View {
        Grid(horizontalSpacing: 4, verticalSpacing: 4) {
            ForEach(0..<rows, id: \.self) { row in
                GridRow {
                    ForEach(0..<columns, id: \.self) { column in
                        let index = column * rows + row
                        RoundedRectangle(cornerRadius: 3)
                            .fill(fill(at: index))
                            .aspectRatio(1, contentMode: .fit)
                    }
                }
            }
        }
    }

    private func fill(at index: Int) -> Color {
        if index == counts.count - 1 { return TE.placeInks[1] }
        guard showsPhotos, counts.indices.contains(index) else { return TE.dialTrack }
        switch PhotoDayHistogram.level(for: counts[index]) {
        case 1: return TE.textPrimary.opacity(0.3)
        case 2: return TE.textPrimary.opacity(0.62)
        case 3: return TE.textPrimary
        default: return TE.dialTrack
        }
    }
}

/// The lock screen Live Activity, drawn dark like the lock screen it sits on.
struct OnboardingLiveActivityCard: View {
    let placeTitle: String

    var body: some View {
        VStack(spacing: 12) {
            Text("LIVE ACTIVITY WHILE ROUTES RECORD")
                .font(TE.mono(.caption2))
                .tracking(0.8)
                .foregroundStyle(TE.textMuted)
                .multilineTextAlignment(.center)
            VStack(alignment: .leading, spacing: 10) {
                HStack {
                    HStack(spacing: 8) {
                        Circle().fill(TE.inkRust).frame(width: 8, height: 8)
                        Text(placeTitle)
                            .font(WheredayFont.serif(size: 19, relativeTo: .title3))
                            .lineLimit(1)
                    }
                    Spacer(minLength: 8)
                    Text(verbatim: "2.4 km")
                        .font(TE.mono(.subheadline))
                        .foregroundStyle(TE.accent)
                }
                GeometryReader { geometry in
                    Capsule().fill(TE.border)
                        .overlay(alignment: .leading) {
                            Capsule().fill(TE.accent).frame(width: geometry.size.width * 0.62)
                        }
                }
                .frame(height: 4)
                Text("312 points · auto-off in 1 h 40 m")
                    .font(TE.mono(.caption2))
                    .foregroundStyle(TE.textMuted)
            }
            .foregroundStyle(TE.textPrimary)
            .padding(.horizontal, 16)
            .padding(.vertical, 14)
            .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
        }
        .padding(.horizontal, 14)
        .padding(.vertical, 18)
        .background(RoundedRectangle(cornerRadius: 22).fill(TE.surface))
        .environment(\.colorScheme, .dark)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Text("Example Live Activity on the Lock Screen: \(placeTitle), 2.4 km recorded, auto-off in 1 hour 40 minutes"))
        .accessibilityAddTraits(.isImage)
    }
}

/// A tiny Apple Watch face showing a run in progress.
struct OnboardingWatchFace: View {
    var body: some View {
        VStack(spacing: 2) {
            ZStack {
                Circle().stroke(TE.dialTrack, lineWidth: 5)
                Circle()
                    .trim(from: 0, to: 58 / 94.2)
                    .stroke(TE.accent, lineWidth: 5)
                    .rotationEffect(.degrees(-90))
            }
            .frame(width: 30, height: 30)
            Text(verbatim: "1.2 km")
                .font(.custom(WheredayFont.monoFamily, fixedSize: 10))
                .foregroundStyle(TE.textPrimary)
        }
        .frame(width: 76, height: 92)
        .background(RoundedRectangle(cornerRadius: 24).fill(TE.surface))
        .overlay(RoundedRectangle(cornerRadius: 24).strokeBorder(TE.border, lineWidth: 3))
        .environment(\.colorScheme, .dark)
        .accessibilityHidden(true)
    }
}
