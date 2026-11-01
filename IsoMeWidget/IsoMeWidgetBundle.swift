import WidgetKit
import SwiftUI
import UIKit

@main
struct IsoMeWidgetBundle: WidgetBundle {
    var body: some Widget {
        TimelineStripWidget()
        DayDialWidget()
        IsoMeLiveActivity()
    }
}

// MARK: - Palette

/// Whereday's paper-and-ink palette for the widget extension. Mirrors the app's `TE` tokens,
/// which live only in the app target.
enum WidgetTE {
    static func adaptive(_ light: UInt32, _ dark: UInt32) -> Color {
        Color(uiColor: UIColor { traits in
            UIColor(hex: traits.userInterfaceStyle == .dark ? dark : light)
        })
    }

    static func fixed(_ hex: UInt32) -> Color {
        Color(uiColor: UIColor(hex: hex))
    }

    static let surface = adaptive(0xF3EFE6, 0x0F1626)
    static let card = adaptive(0xFBF9F4, 0x18213A)
    static let border = adaptive(0xD8D2C3, 0x2C3754)
    static let textPrimary = adaptive(0x1E2B45, 0xECE6D8)
    static let textMuted = adaptive(0x66645D, 0x9AA3B8)
    static let accent = adaptive(0x1E2B45, 0xE0B565)
    static let onAccent = adaptive(0xF3EFE6, 0x0F1626)
    static let inkRust = adaptive(0xA8381F, 0xF0805F)
    static let dialTrack = adaptive(0xE4DED0, 0x222C46)
    static let placeInks: [Color] = [
        adaptive(0x1E2B45, 0xECE6D8),
        adaptive(0xC2462A, 0xF0805F),
        adaptive(0xB7862B, 0xE0B565),
        adaptive(0x5E7F5A, 0x9DC097)
    ]

    /// Inks for always-dark surfaces (the navy day-dial widget, the Dynamic Island).
    enum Night {
        static let textPrimary = fixed(0xECE6D8)
        static let textMuted = fixed(0x9AA3B8)
        static let label = fixed(0xC9CEDA)
        static let inkRust = fixed(0xF0805F)
        static let dialTrack = fixed(0x2C3754)
        static let placeInks: [Color] = [
            fixed(0x8FA6D9),
            fixed(0xF0805F),
            fixed(0xE0B565),
            fixed(0x9DC097)
        ]
    }

    /// IBM Plex Mono, bundled and registered in this extension.
    static func mono(_ style: Font.TextStyle, weight: Font.Weight = .regular) -> Font {
        WheredayFont.mono(style, weight: weight)
    }

    static func mono(size: CGFloat, weight: Font.Weight = .regular, relativeTo style: Font.TextStyle) -> Font {
        WheredayFont.mono(size: size, weight: weight, relativeTo: style)
    }

    /// Newsreader, bundled and registered in this extension.
    static func editorial(_ style: Font.TextStyle) -> Font {
        WheredayFont.serif(style)
    }

    static func editorial(size: CGFloat, relativeTo style: Font.TextStyle) -> Font {
        WheredayFont.serif(size: size, relativeTo: style)
    }
}

private extension UIColor {
    convenience init(hex: UInt32) {
        self.init(
            red: CGFloat((hex >> 16) & 0xFF) / 255,
            green: CGFloat((hex >> 8) & 0xFF) / 255,
            blue: CGFloat(hex & 0xFF) / 255,
            alpha: 1
        )
    }
}

// MARK: - Shared day model

/// Today's stays as the widgets draw them.
struct WidgetDay {
    let now: Date
    let stays: [TodayWidgetSnapshot.DayStay]
    let distanceMeters: Double
    let usesMetric: Bool
    let recordedDayCount: Int?

    init(snapshot: TodayWidgetSnapshot?, now: Date) {
        self.now = now
        let calendar = Calendar.current
        let isToday = snapshot.map { calendar.isDate($0.generatedAt, inSameDayAs: now) } ?? false
        stays = snapshot?.dayStays(now: now) ?? []
        // Distance is a per-day total; don't carry yesterday's into today.
        distanceMeters = isToday ? (snapshot?.distanceMeters ?? 0) : 0
        usesMetric = snapshot?.usesMetricDistanceUnits ?? (Locale.current.measurementSystem != .us)
        recordedDayCount = snapshot?.recordedDayCount
    }

    var currentStay: TodayWidgetSnapshot.DayStay? {
        stays.last(where: \.isOngoing)
    }

    var placeCount: Int {
        Set(stays.map(\.placeName)).count
    }

    var nowFraction: Double {
        TodayWidgetSnapshot.fraction(of: now, startOfDay: Calendar.current.startOfDay(for: now))
    }

    var formattedDistance: String {
        DistanceFormatter.format(meters: distanceMeters, usesMetric: usesMetric)
    }

    var accessibilitySummary: String {
        guard !stays.isEmpty else {
            return String(localized: "No places yet today")
        }
        let places = stays.map { stay in
            String(localized: "\(stay.placeName) from \(stay.start.formatted(date: .omitted, time: .shortened))")
        }
        return places.formatted(.list(type: .and))
    }

    static func clock(_ date: Date) -> String {
        date.formatted(.dateTime.hour(.twoDigits(amPM: .omitted)).minute(.twoDigits))
    }
}

// MARK: - Day dial

/// A 24-hour ring, midnight at the top, with each stay drawn as an arc in its place's ink.
struct WidgetDayDial: View {
    let stays: [TodayWidgetSnapshot.DayStay]
    let inks: [Color]
    let track: Color
    var lineWidthRatio: CGFloat = 0.1
    var nowFraction: Double? = nil
    var nowInk: Color = WidgetTE.Night.inkRust

    var body: some View {
        GeometryReader { geometry in
            let size = min(geometry.size.width, geometry.size.height)
            let lineWidth = max(2, size * lineWidthRatio)
            let inset = lineWidth / 2

            ZStack {
                Circle()
                    .inset(by: inset)
                    .stroke(track, lineWidth: lineWidth)

                ForEach(Array(stays.enumerated()), id: \.offset) { _, stay in
                    Circle()
                        .inset(by: inset)
                        .trim(from: stay.startFraction, to: stay.endFraction)
                        .stroke(inks[stay.inkIndex % inks.count], style: StrokeStyle(lineWidth: lineWidth, lineCap: .butt))
                        .rotationEffect(.degrees(-90))
                }

                if let nowFraction {
                    let radius = size / 2 - inset
                    let angle = Angle.degrees(nowFraction * 360 - 90)
                    Capsule()
                        .fill(nowInk)
                        .frame(width: max(1.5, lineWidth * 0.18), height: lineWidth * 1.3)
                        .rotationEffect(.degrees(nowFraction * 360))
                        .offset(x: cos(angle.radians) * radius, y: sin(angle.radians) * radius)
                }
            }
            .frame(width: size, height: size)
            .frame(maxWidth: .infinity, maxHeight: .infinity)
        }
        .accessibilityHidden(true)
    }
}

// MARK: - Timeline provider

struct TodayEntry: TimelineEntry {
    let date: Date
    let snapshot: TodayWidgetSnapshot?

    var day: WidgetDay { WidgetDay(snapshot: snapshot, now: date) }
}

struct TodayProvider: TimelineProvider {
    func placeholder(in context: Context) -> TodayEntry {
        TodayEntry(date: Date(), snapshot: nil)
    }

    func getSnapshot(in context: Context, completion: @escaping (TodayEntry) -> Void) {
        completion(TodayEntry(date: Date(), snapshot: TodayWidgetSnapshot.load()))
    }

    func getTimeline(in context: Context, completion: @escaping (Timeline<TodayEntry>) -> Void) {
        let snapshot = TodayWidgetSnapshot.load()
        let calendar = Calendar.current
        let now = Date()
        let minuteStart = calendar.dateInterval(of: .minute, for: now)?.start ?? now
        // One entry a minute keeps the clock and the "now" marker honest for the next hour;
        // the app reloads the timeline whenever today's stays change.
        let entries = (0..<60).compactMap { offset -> TodayEntry? in
            guard let date = calendar.date(byAdding: .minute, value: offset, to: minuteStart) else { return nil }
            return TodayEntry(date: offset == 0 ? now : date, snapshot: snapshot)
        }
        completion(Timeline(entries: entries, policy: .atEnd))
    }
}

// MARK: - Medium: timeline strip

struct TimelineStripWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: TodayWidgetSnapshot.widgetKinds[0], provider: TodayProvider()) { entry in
            TimelineStripView(day: entry.day)
                .containerBackground(WidgetTE.surface, for: .widget)
        }
        .configurationDisplayName("Today's places")
        .description("Today's stays as a 24-hour strip.")
        .supportedFamilies([.systemMedium])
    }
}

struct TimelineStripView: View {
    let day: WidgetDay

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text(day.currentStay?.placeName ?? String(localized: "Today"))
                    .font(WidgetTE.editorial(.title3))
                    .foregroundStyle(WidgetTE.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.7)
                Spacer(minLength: 4)
                Text(sinceLabel)
                    .font(WidgetTE.mono(size: 10, relativeTo: .caption2))
                    .tracking(1)
                    .foregroundStyle(WidgetTE.textMuted)
                    .lineLimit(1)
            }

            VStack(spacing: 3) {
                strip
                    .frame(height: 18)
                HStack {
                    ForEach(["00", "06", "12", "18", "24"], id: \.self) { hour in
                        Text(verbatim: hour)
                        if hour != "24" { Spacer(minLength: 0) }
                    }
                }
                .font(WidgetTE.mono(size: 9, relativeTo: .caption2))
                .foregroundStyle(WidgetTE.textMuted)
                .accessibilityHidden(true)
            }
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(day.accessibilitySummary)

            Spacer(minLength: 0)

            HStack(alignment: .bottom, spacing: 8) {
                stat(value: "\(day.placeCount)", label: "PLACES")
                stat(value: day.formattedDistance, label: "MOVED")
                if let recorded = day.recordedDayCount {
                    stat(value: String(localized: "Day \(recorded)"), label: "RECORDED")
                } else {
                    stat(value: "\(day.stays.count)", label: "STAYS")
                }
            }
        }
    }

    private var sinceLabel: String {
        guard let stay = day.currentStay else {
            return day.stays.isEmpty ? String(localized: "NO STAYS YET") : String(localized: "MOVING")
        }
        return String(localized: "SINCE \(WidgetDay.clock(stay.start))")
    }

    private var strip: some View {
        GeometryReader { geometry in
            let width = geometry.size.width
            ZStack(alignment: .leading) {
                RoundedRectangle(cornerRadius: 5, style: .continuous)
                    .fill(WidgetTE.dialTrack)

                ForEach(Array(day.stays.enumerated()), id: \.offset) { _, stay in
                    Rectangle()
                        .fill(WidgetTE.placeInks[stay.inkIndex % WidgetTE.placeInks.count])
                        .frame(width: max(1, (stay.endFraction - stay.startFraction) * width))
                        .offset(x: stay.startFraction * width)
                }

                Rectangle()
                    .fill(WidgetTE.inkRust)
                    .frame(width: 2, height: geometry.size.height + 4)
                    .offset(x: min(max(0, day.nowFraction * width - 1), width - 2))
            }
            .clipShape(RoundedRectangle(cornerRadius: 5, style: .continuous))
        }
    }

    private func stat(value: String, label: LocalizedStringKey) -> some View {
        VStack(alignment: .leading, spacing: 1) {
            Text(value)
                .font(WidgetTE.editorial(size: 18, relativeTo: .body))
                .foregroundStyle(WidgetTE.textPrimary)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
            Text(label)
                .font(WidgetTE.mono(size: 9, relativeTo: .caption2))
                .tracking(1)
                .foregroundStyle(WidgetTE.textMuted)
                .lineLimit(1)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Small: day dial

struct DayDialWidget: Widget {
    var body: some WidgetConfiguration {
        StaticConfiguration(kind: TodayWidgetSnapshot.widgetKinds[1], provider: TodayProvider()) { entry in
            DayDialWidgetView(day: entry.day)
                .containerBackground(WidgetTE.adaptive(0x1E2B45, 0x18213A), for: .widget)
        }
        .configurationDisplayName("Day dial")
        .description("Today's places around a 24-hour dial.")
        .supportedFamilies([.systemSmall])
    }
}

struct DayDialWidgetView: View {
    let day: WidgetDay

    var body: some View {
        ZStack {
            WidgetDayDial(
                stays: day.stays,
                inks: WidgetTE.Night.placeInks,
                track: WidgetTE.Night.dialTrack,
                nowFraction: day.nowFraction
            )
            .padding(4)

            VStack(spacing: 2) {
                Text(WidgetDay.clock(day.now))
                    .font(WidgetTE.editorial(.title))
                    .monospacedDigit()
                    .foregroundStyle(WidgetTE.Night.textPrimary)
                    .minimumScaleFactor(0.6)
                    .lineLimit(1)
                if let stay = day.currentStay {
                    Text(stay.placeName.uppercased())
                        .font(WidgetTE.mono(size: 9, relativeTo: .caption2))
                        .tracking(1)
                        .foregroundStyle(WidgetTE.Night.label)
                        .lineLimit(1)
                        .minimumScaleFactor(0.7)
                }
            }
            .padding(.horizontal, 30)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(String(localized: "24-hour dial: \(day.accessibilitySummary)"))
    }
}

// MARK: - Previews

private extension TodayWidgetSnapshot {
    static var preview: TodayWidgetSnapshot {
        let start = Calendar.current.startOfDay(for: .now)
        func at(_ hours: Double) -> Date { start.addingTimeInterval(hours * 3600) }
        return TodayWidgetSnapshot(
            generatedAt: .now,
            stays: [
                .init(placeName: "Home", arrivedAt: at(-2), departedAt: at(8.2)),
                .init(placeName: "Cafe", arrivedAt: at(8.7), departedAt: at(9.4)),
                .init(placeName: "Office", arrivedAt: at(9.8), departedAt: nil)
            ],
            distanceMeters: 6400,
            usesMetricDistanceUnits: true,
            isTracking: true
        )
    }
}

#Preview("Timeline strip", as: .systemMedium) {
    TimelineStripWidget()
} timeline: {
    TodayEntry(date: .now, snapshot: .preview)
    TodayEntry(date: .now, snapshot: nil)
}

#Preview("Day dial", as: .systemSmall) {
    DayDialWidget()
} timeline: {
    TodayEntry(date: .now, snapshot: .preview)
}
