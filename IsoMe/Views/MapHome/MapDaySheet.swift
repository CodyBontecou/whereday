import SwiftUI

// The map home's bottom sheet (boards M1, M2, M6): a floating peek card that opens into a detented sheet
// over the map. The map above the sheet stays interactive.

enum MapSheetDetent: Int, Comparable {
    case peek, medium, large

    static func < (lhs: Self, rhs: Self) -> Bool { lhs.rawValue < rhs.rawValue }

    var larger: MapSheetDetent { MapSheetDetent(rawValue: min(rawValue + 1, 2)) ?? .large }
    var smaller: MapSheetDetent { MapSheetDetent(rawValue: max(rawValue - 1, 0)) ?? .peek }

    /// Where the sheet's top edge sits, as a fraction of the map's height (the board's sheet starts at 330 of 844).
    var topFraction: CGFloat {
        switch self {
        case .peek: 1
        case .medium: 0.39
        case .large: 0.12
        }
    }
}

struct MapDaySheet<Peek: View, Content: View>: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Binding var detent: MapSheetDetent
    /// The floating tab bar's height; content ends above it.
    let bottomInset: CGFloat
    let containerHeight: CGFloat
    @ViewBuilder var peek: () -> Peek
    @ViewBuilder var content: () -> Content
    @GestureState private var dragOffset: CGFloat = 0

    private var animation: Animation? { reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.86) }

    var body: some View {
        Group {
            if detent == .peek {
                peek()
                    .padding(.horizontal, 12)
                    .padding(.bottom, bottomInset + 8)
                    .gesture(dragGesture)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            } else {
                sheet
                    .transition(.move(edge: .bottom))
            }
        }
        .animation(animation, value: detent)
    }

    private var sheetHeight: CGFloat {
        max(200, containerHeight * (1 - detent.topFraction) - dragOffset)
    }

    private var sheet: some View {
        VStack(spacing: 0) {
            Button {
                withAnimation(animation) { detent = detent == .large ? .medium : .large }
            } label: {
                Capsule()
                    .fill(TE.adaptive(0xCFC8B7, 0x2C3754))
                    .frame(width: 36, height: 4)
                    .frame(maxWidth: .infinity, minHeight: 30)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .gesture(dragGesture)
            .accessibilityLabel(detent == .large ? "Shrink the day" : "Expand the day")
            .accessibilityAction(named: "Collapse the day") {
                withAnimation(animation) { detent = .peek }
            }

            content()
                .frame(maxHeight: .infinity, alignment: .top)
                .padding(.bottom, bottomInset)
        }
        .frame(height: sheetHeight)
        .frame(maxWidth: .infinity)
        .background {
            UnevenRoundedRectangle(topLeadingRadius: 26, topTrailingRadius: 26, style: .continuous)
                .fill(TE.surface)
                .shadow(color: MapHomeStyle.shadow, radius: 15, x: 0, y: -10)
                .ignoresSafeArea(edges: .bottom)
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("The day so far")
    }

    private var dragGesture: some Gesture {
        DragGesture(minimumDistance: 8)
            .updating($dragOffset) { value, state, _ in
                if detent != .peek { state = value.translation.height }
            }
            .onEnded { value in
                let distance = value.predictedEndTranslation.height
                withAnimation(animation) {
                    if distance < -60 {
                        detent = detent.larger
                    } else if distance > 60 {
                        detent = distance > 360 ? .peek : detent.smaller
                    }
                }
            }
    }
}

// MARK: - Several days (M6)

/// "THURSDAY · OCT 29" / "9.2 km, 4 places", the range's totals, and a strip of day buttons with
/// small distance bars. Tapping a day highlights its path on the map.
struct MapRangeCard: View {
    let summary: RangeSummary
    let selectedDay: Date?
    let usesMetric: Bool
    let onSelectDay: (Date) -> Void
    let onExpand: () -> Void

    private var selected: DaySummary? {
        summary.days.first { $0.day == selectedDay } ?? summary.days.last
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(alignment: .top, spacing: 8) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(selected.map { Self.dayKicker($0.day) } ?? "")
                        .font(WheredayFont.mono(size: 9, weight: .medium, relativeTo: .caption2))
                        .tracking(1.2)
                        .foregroundStyle(TE.textMuted)
                    Text(selected.map(daySentence) ?? "")
                        .font(WheredayFont.serif(size: 18, relativeTo: .headline))
                        .foregroundStyle(TE.textPrimary)
                        .lineLimit(1)
                        .minimumScaleFactor(0.8)
                }
                .accessibilityElement(children: .combine)
                Spacer(minLength: 4)
                Text(totals)
                    .font(WheredayFont.mono(size: 9, weight: .medium, relativeTo: .caption2))
                    .tracking(1.2)
                    .foregroundStyle(TE.textMuted)
                    .multilineTextAlignment(.trailing)
                Button(action: onExpand) {
                    Image(systemName: "chevron.up")
                        .font(.system(size: 13, weight: .semibold))
                        .foregroundStyle(TE.textMuted)
                        .frame(width: 32, height: 32)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Every day in the range")
                .padding(.top, -6)
                .padding(.trailing, -6)
            }

            dayStrip
        }
        .padding(12)
        .mapCard(cornerRadius: 22)
    }

    /// Up to a week fills the card; longer ranges scroll, newest at the trailing edge.
    /// A lazy strip keeps 30 days (or every recorded day of "All") cheap to lay out.
    @ViewBuilder
    private var dayStrip: some View {
        if summary.days.count <= 8 {
            HStack(spacing: 5) {
                ForEach(summary.days) { day in dayButton(day).frame(maxWidth: .infinity) }
            }
        } else {
            ScrollViewReader { proxy in
                ScrollView(.horizontal) {
                    LazyHStack(spacing: 5) {
                        ForEach(summary.days) { day in
                            dayButton(day).frame(width: 40).id(day.day)
                        }
                    }
                }
                .scrollIndicators(.hidden)
                // A fixed height: a lazy strip re-measures as it scrolls, which would ripple into the map's layout.
                .frame(height: 64)
                .onAppear { proxy.scrollTo(selectedDay ?? summary.days.last?.day, anchor: .trailing) }
            }
        }
    }

    private func dayButton(_ day: DaySummary) -> some View {
        let isSelected = day.day == selected?.day
        let longest = max(summary.longestDayMeters, 1)
        let fraction = day.distanceMeters / longest
        return Button { onSelectDay(day.day) } label: {
            VStack(spacing: 6) {
                ZStack(alignment: .bottom) {
                    Capsule().fill(Color.clear).frame(width: 8, height: 30)
                    Capsule()
                        .fill(isSelected ? TE.card : TE.textPrimary)
                        .opacity(isSelected ? 0.9 : 0.35)
                        .frame(width: 8, height: max(3, 30 * fraction))
                }
                Text(Self.dayLetter(day.day))
                    .font(WheredayFont.mono(size: 11, relativeTo: .caption))
                    .foregroundStyle(isSelected ? TE.card : TE.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
            }
            .frame(maxWidth: .infinity, minHeight: 64)
            .background(RoundedRectangle(cornerRadius: 14).fill(isSelected ? MapHomeStyle.vermilion : TE.card))
            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(isSelected ? Color.clear : TE.border, lineWidth: 1))
            .opacity(day.hasRecord || isSelected ? 1 : 0.55)
            .contentShape(RoundedRectangle(cornerRadius: 14))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(day.day.formatted(date: .complete, time: .omitted))
        .accessibilityValue(daySentence(day))
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    private var totals: String {
        let distance = DistanceFormatter.format(meters: summary.totalDistanceMeters, usesMetric: usesMetric)
        let visits = summary.totalVisits
        return String(localized: "\(distance) · \(visits) VISITS").uppercased()
    }

    /// "9.2 km, 4 places".
    private func daySentence(_ day: DaySummary) -> String {
        let distance = DistanceFormatter.format(meters: day.distanceMeters, usesMetric: usesMetric)
        return day.placeCount == 1
            ? String(localized: "\(distance), 1 place")
            : String(localized: "\(distance), \(day.placeCount) places")
    }

    /// "THURSDAY · OCT 29".
    static func dayKicker(_ day: Date) -> String {
        let weekday = day.formatted(.dateTime.weekday(.wide))
        let date = day.formatted(.dateTime.month(.abbreviated).day())
        return "\(weekday) · \(date)".uppercased()
    }

    /// One letter per day ("T"), or the day of the month once a range runs past a week.
    static func dayLetter(_ day: Date) -> String {
        day.formatted(.dateTime.weekday(.narrow))
    }
}

/// The expanded sheet for several days: one row per day, newest first.
struct MapRangeDayList: View {
    let summary: RangeSummary
    let selectedDay: Date?
    let usesMetric: Bool
    let onSelectDay: (Date) -> Void

    var body: some View {
        ScrollView {
            LazyVStack(alignment: .leading, spacing: 0) {
                Text("DAY BY DAY")
                    .font(WheredayFont.mono(size: 10.5, weight: .medium, relativeTo: .caption2))
                    .tracking(1.5)
                    .foregroundStyle(TE.textMuted)
                    .padding(.bottom, 8)
                    .accessibilityAddTraits(.isHeader)
                ForEach(summary.days.reversed()) { day in
                    Button { onSelectDay(day.day) } label: { row(day) }
                        .buttonStyle(.plain)
                }
            }
            .padding(.horizontal, 20)
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
    }

    private func row(_ day: DaySummary) -> some View {
        let isSelected = day.day == selectedDay
        return HStack(spacing: 10) {
            Circle()
                .fill(isSelected ? MapHomeStyle.vermilion : TE.textPrimary.opacity(day.hasRecord ? 0.6 : 0.2))
                .frame(width: 10, height: 10)
                .accessibilityHidden(true)
            Text(MapRangeCard.dayKicker(day.day))
                .font(TE.mono(.caption))
                .foregroundStyle(TE.textMuted)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(DistanceFormatter.format(meters: day.distanceMeters, usesMetric: usesMetric))
                .font(WheredayFont.serif(size: 19, relativeTo: .title3))
            Text(day.placeCount == 1 ? String(localized: "1 place") : String(localized: "\(day.placeCount) places"))
                .font(TE.mono(.caption))
                .foregroundStyle(TE.textMuted)
                .frame(minWidth: 64, alignment: .trailing)
        }
        .frame(minHeight: 48)
        .overlay(alignment: .bottom) { Rectangle().fill(TE.dialTrack).frame(height: 1) }
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}
