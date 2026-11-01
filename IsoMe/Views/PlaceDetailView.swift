import CoreLocation
import MapKit
import SwiftUI

/// The rhythm of one place: visits, typical stay, and when you tend to come here.
struct PlaceDetailView: View {
    let viewModel: LocationViewModel
    /// Two-pane layouts embed the detail next to the list, without their own navigation chrome.
    var isEmbedded = false
    private let placeID: UUID

    @Environment(\.dismiss) private var dismiss
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var editingDraft: SavedPlaceDraft?
    @State private var inspectedVisit: Visit?

    init(place: SavedPlace, viewModel: LocationViewModel, isEmbedded: Bool = false) {
        // Only the identity is kept, so a deletion elsewhere never touches a removed model.
        placeID = place.id
        self.viewModel = viewModel
        self.isEmbedded = isEmbedded
    }

    private var place: SavedPlace? {
        viewModel.savedPlaces.first { $0.id == placeID }
    }

    var body: some View {
        Group {
            if let place {
                content(place: place)
            } else {
                ContentUnavailableView("Place Removed", systemImage: "mappin.slash",
                                       description: Text("This saved place no longer exists. Your past visits are kept."))
            }
        }
        .background(TE.surface.ignoresSafeArea())
        .foregroundStyle(TE.textPrimary)
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
        .sheet(item: $editingDraft) { draft in
            SavedPlaceEditorView(viewModel: viewModel, draft: draft)
        }
        .sheet(item: $inspectedVisit) { visit in
            NavigationStack {
                VisitDetailView(visit: visit, viewModel: viewModel)
                    .toolbar {
                        ToolbarItem(placement: .topBarTrailing) {
                            Button("Done") { inspectedVisit = nil }
                        }
                    }
            }
        }
        .onChange(of: place == nil) { _, removed in
            if removed && !isEmbedded { dismiss() }
        }
        .tint(TE.accent)
    }

    private func content(place: SavedPlace) -> some View {
        let ink = PlaceVisitIndex.ink(for: place, in: viewModel.savedPlaces)
        let visits = PlaceVisitIndex(places: viewModel.savedPlaces, visits: viewModel.allVisits).visits(for: place)
        let rhythm = PlaceRhythm(visits: visits)

        return ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                PlaceMapCard(coordinate: place.coordinate, radius: place.radiusMeters, ink: ink, name: place.name)
                    .frame(height: 196)
                    .padding(.horizontal, 16)
                    .padding(.top, 8)

                titleBlock(place)
                    .padding(.horizontal, 24)
                    .padding(.top, 18)

                stats(rhythm)
                    .padding(.horizontal, 24)
                    .padding(.top, 18)

                rhythmSection(rhythm, ink: ink)
                    .padding(.horizontal, 24)
                    .padding(.top, 18)

                recentSection(visits)
                    .padding(.horizontal, 24)
                    .padding(.top, 18)

                Button("Edit place") { editingDraft = SavedPlaceDraft(place) }
                    .buttonStyle(PlacesInkButtonStyle(emphasis: .primary))
                    .padding(.horizontal, 16)
                    .padding(.top, 24)
                    .accessibilityHint("Rename, move, resize, or delete this saved place.")
            }
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
    }

    private func titleBlock(_ place: SavedPlace) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("SAVED PLACE · SINCE \(place.createdAt.formatted(.dateTime.month(.abbreviated).year()).uppercased())")
                .font(TE.mono(.caption2))
                .tracking(1.2)
                .foregroundStyle(TE.textMuted)
            Text(place.name)
                .font(TE.editorial(.largeTitle))
                .fixedSize(horizontal: false, vertical: true)
                .accessibilityAddTraits(.isHeader)
            if let address = place.address, !address.isEmpty {
                Text(address)
                    .font(TE.mono(.caption))
                    .foregroundStyle(TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private func stats(_ rhythm: PlaceRhythm) -> some View {
        let year = Calendar.current.component(.year, from: Date())
        return PlacesStatStrip(items: [
            (rhythm.visitCount.formatted(), String(localized: "VISITS")),
            (rhythm.typicalStay.map(PlaceRhythm.formatDuration) ?? "—", String(localized: "TYPICAL STAY")),
            (PlaceRhythm.formatHours(rhythm.timeThisYear), String(localized: "IN \(String(year))"))
        ])
    }

    private func rhythmSection(_ rhythm: PlaceRhythm, ink: Color) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .firstTextBaseline) {
                    rhythmTitle
                    Spacer(minLength: 8)
                    rhythmSummary(rhythm)
                }
                VStack(alignment: .leading, spacing: 4) {
                    rhythmTitle
                    rhythmSummary(rhythm)
                }
            }
            if rhythm.visitCount == 0 {
                Text("No visits here yet. Visits inside the match radius show up here.")
                    .font(TE.editorial(.body))
                    .foregroundStyle(TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
            } else {
                PlaceRhythmGrid(rhythm: rhythm, ink: ink)
            }
        }
    }

    private var rhythmTitle: some View {
        Text("WHEN YOU COME HERE")
            .font(TE.mono(.caption2))
            .tracking(1.5)
            .foregroundStyle(TE.textMuted)
            .accessibilityAddTraits(.isHeader)
    }

    @ViewBuilder
    private func rhythmSummary(_ rhythm: PlaceRhythm) -> some View {
        if let summary = rhythm.summary {
            Text(summary)
                .font(TE.editorial(.subheadline))
                .italic()
                .foregroundStyle(TE.textMuted)
        }
    }

    @ViewBuilder
    private func recentSection(_ visits: [Visit]) -> some View {
        if !visits.isEmpty {
            VStack(alignment: .leading, spacing: 0) {
                Text("RECENT")
                    .font(TE.mono(.caption2))
                    .tracking(1.5)
                    .foregroundStyle(TE.textMuted)
                    .padding(.bottom, 4)
                    .accessibilityAddTraits(.isHeader)
                let recent = Array(visits.prefix(5))
                ForEach(recent) { visit in
                    Button { inspectedVisit = visit } label: {
                        recentRow(visit)
                    }
                    .buttonStyle(.plain)
                    .accessibilityHint(visit.accessibilityHint)
                    if visit.id != recent.last?.id {
                        Rectangle().fill(TE.dialTrack).frame(height: 1)
                    }
                }
            }
        }
    }

    private func recentRow(_ visit: Visit) -> some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 2))
            : AnyLayout(HStackLayout(alignment: .firstTextBaseline, spacing: 8))
        return layout {
            Text(PlaceRhythm.visitRangeLabel(visit, isCurrent: viewModel.isCurrentVisit(visit)))
                .font(.subheadline)
                .frame(maxWidth: .infinity, alignment: .leading)
            Text(viewModel.isCurrentVisit(visit) ? String(localized: "now")
                 : PlaceRhythm.formatDuration((visit.departedAt ?? Date()).timeIntervalSince(visit.arrivedAt)))
                .font(TE.mono(.caption))
                .foregroundStyle(viewModel.isCurrentVisit(visit) ? TE.inkRust : TE.textMuted)
        }
        .padding(.vertical, 9)
        .frame(minHeight: 44)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }
}

// MARK: - Map

/// A still map of a saved place with its match radius drawn in the place's ink.
struct PlaceMapCard: View {
    let coordinate: CLLocationCoordinate2D
    let radius: Double
    let ink: Color
    let name: String
    var caption: LocalizedStringKey?

    var body: some View {
        let span = max(400, radius * 4)
        Map(initialPosition: .region(MKCoordinateRegion(center: coordinate, latitudinalMeters: span, longitudinalMeters: span)),
            interactionModes: []) {
            MapCircle(center: coordinate, radius: radius)
                .foregroundStyle(ink.opacity(0.14))
                .stroke(ink, style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
            Annotation(name, coordinate: coordinate, anchor: .center) {
                Circle()
                    .fill(ink)
                    .frame(width: 20, height: 20)
                    .overlay(Circle().strokeBorder(TE.surface, lineWidth: 3))
            }
            .annotationTitles(.hidden)
        }
        .id("\(coordinate.latitude),\(coordinate.longitude),\(radius)")
        .allowsHitTesting(false)
        .overlay(alignment: .bottomTrailing) {
            Text(caption ?? "RADIUS \(Int(radius.rounded())) M")
                .font(TE.mono(.caption2))
                .foregroundStyle(TE.textPrimary)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(RoundedRectangle(cornerRadius: 6).fill(TE.card.opacity(0.92)))
                .padding(10)
        }
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.border, lineWidth: 1))
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Map around \(name)")
        .accessibilityValue("Match radius: \(Int(radius.rounded())) meters")
    }
}

// MARK: - Rhythm grid

private struct PlaceRhythmGrid: View {
    let rhythm: PlaceRhythm
    let ink: Color
    @ScaledMetric(relativeTo: .caption2) private var rowHeight: CGFloat = 14

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(Array(rhythm.weekdayOrder.enumerated()), id: \.offset) { _, weekday in
                HStack(spacing: 0) {
                    Text(PlaceRhythm.weekdayLetter(weekday))
                        .font(TE.mono(.caption2))
                        .foregroundStyle(TE.textMuted)
                        .frame(width: 22, alignment: .leading)
                    HStack(spacing: 0) {
                        ForEach(0..<24, id: \.self) { hour in
                            let level = rhythm.level(weekday: weekday, hour: hour)
                            let size: CGFloat = [3, 6, 9, 12][level]
                            Circle()
                                .fill(level == 0 ? TE.dialTick.opacity(0.6) : ink)
                                .frame(width: size, height: size)
                                .frame(maxWidth: .infinity)
                        }
                    }
                }
                .frame(height: max(14, rowHeight))
            }
            HStack(spacing: 0) {
                Color.clear.frame(width: 22, height: 1)
                ForEach([0, 6, 12, 18], id: \.self) { hour in
                    Text(String(format: "%02d", hour))
                        .font(TE.mono(.caption2))
                        .foregroundStyle(TE.textMuted)
                        .frame(maxWidth: .infinity, alignment: .leading)
                }
            }
        }
        .accessibilityElement(children: .ignore)
        .accessibilityAddTraits(.isImage)
        .accessibilityLabel("Visit rhythm by weekday and hour")
        .accessibilityValue(rhythm.accessibilityDescription)
    }
}

// MARK: - Shared pieces

/// A row of serif numbers with mono labels between hairlines; stacks at accessibility sizes.
struct PlacesStatStrip: View {
    let items: [(value: String, label: String)]
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 0))
            : AnyLayout(HStackLayout(alignment: .top, spacing: 0))
        layout {
            ForEach(Array(items.enumerated()), id: \.offset) { index, item in
                HStack(spacing: 0) {
                    if index > 0 && !dynamicTypeSize.isAccessibilitySize {
                        Rectangle().fill(TE.border).frame(width: 1)
                    }
                    VStack(alignment: .leading, spacing: 2) {
                        Text(item.value)
                            .font(TE.editorial(.title))
                            .lineLimit(1)
                            .minimumScaleFactor(0.7)
                        Text(item.label)
                            .font(TE.mono(.caption2))
                            .foregroundStyle(TE.textMuted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(.vertical, 12)
                    .padding(.leading, index > 0 && !dynamicTypeSize.isAccessibilitySize ? 14 : 0)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .accessibilityElement(children: .combine)
                }
            }
        }
        .fixedSize(horizontal: false, vertical: true)
        .overlay(alignment: .top) { Rectangle().fill(TE.border).frame(height: 1) }
        .overlay(alignment: .bottom) { Rectangle().fill(TE.border).frame(height: 1) }
    }
}

/// Rounded ink buttons used across Places and Atlas: filled navy (amber at night) or a bordered card.
struct PlacesInkButtonStyle: ButtonStyle {
    enum Emphasis { case primary, secondary, destructive }

    var emphasis: Emphasis = .secondary
    @Environment(\.isEnabled) private var isEnabled

    func makeBody(configuration: Configuration) -> some View {
        let filled = emphasis == .primary && isEnabled
        configuration.label
            .font(.subheadline.weight(filled ? .semibold : .medium))
            .multilineTextAlignment(.center)
            .fixedSize(horizontal: false, vertical: true)
            .foregroundStyle(filled ? TE.surface : emphasis == .destructive ? TE.inkRust : isEnabled ? TE.textPrimary : TE.textMuted)
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .frame(maxWidth: .infinity, minHeight: 48)
            .background(
                RoundedRectangle(cornerRadius: 14)
                    .fill(filled ? TE.accent : emphasis == .destructive ? Color.clear : TE.card)
            )
            .overlay {
                if !filled {
                    RoundedRectangle(cornerRadius: 14)
                        .strokeBorder(emphasis == .destructive ? TE.inkRust.opacity(0.35) : TE.border, lineWidth: 1)
                }
            }
            .opacity(configuration.isPressed ? 0.75 : 1)
            .contentShape(RoundedRectangle(cornerRadius: 14))
    }
}

/// Visits matched to saved places by match radius. Each visit belongs to its nearest containing place.
struct PlaceVisitIndex {
    private var visitsByPlace: [UUID: [Visit]] = [:]
    private(set) var unmatched: [Visit] = []

    init(places: [SavedPlace], visits: [Visit]) {
        let candidates = places.map { (id: $0.id, place: $0, latitude: $0.latitude, radius: $0.radiusMeters) }
        for visit in visits {
            var best: (id: UUID, distance: Double)?
            for candidate in candidates {
                // One degree of latitude is ~111 km, so a cheap check skips most places.
                guard abs(candidate.latitude - visit.latitude) * 111_000 <= candidate.radius else { continue }
                let distance = candidate.place.distanceMeters(to: visit.coordinate)
                guard distance <= candidate.radius else { continue }
                if best == nil || distance < best!.distance { best = (candidate.id, distance) }
            }
            if let best {
                visitsByPlace[best.id, default: []].append(visit)
            } else {
                unmatched.append(visit)
            }
        }
    }

    /// Visits keep the caller's order (newest first for `allVisits`).
    func visits(for place: SavedPlace) -> [Visit] {
        visitsByPlace[place.id] ?? []
    }

    /// A stable ink per place, cycling the place inks in the library's name order.
    static func ink(for place: SavedPlace, in places: [SavedPlace]) -> Color {
        let index = places.firstIndex { $0.id == place.id } ?? 0
        return TE.placeInks[index % TE.placeInks.count]
    }
}

/// When and how long you spend at one place, from its matched visits.
struct PlaceRhythm {
    let visitCount: Int
    let typicalStay: TimeInterval?
    let timeThisYear: TimeInterval
    /// Seconds present per weekday (1...7, Calendar numbering) and hour.
    private let cells: [[TimeInterval]]
    private let maximum: TimeInterval
    let weekdayOrder: [Int]

    init(visits: [Visit], now: Date = Date(), calendar: Calendar = .current) {
        visitCount = visits.count
        let durations = visits.compactMap { visit -> TimeInterval? in
            guard let departed = visit.departedAt, departed > visit.arrivedAt else { return nil }
            return departed.timeIntervalSince(visit.arrivedAt)
        }.sorted()
        typicalStay = durations.isEmpty ? nil : durations[durations.count / 2]

        let yearStart = calendar.dateInterval(of: .year, for: now)?.start ?? now
        var cells = Array(repeating: Array(repeating: TimeInterval(0), count: 24), count: 8)
        var thisYear: TimeInterval = 0
        for visit in visits {
            let end = min(visit.departedAt ?? now, now)
            guard end > visit.arrivedAt else { continue }
            thisYear += max(0, end.timeIntervalSince(max(visit.arrivedAt, yearStart)))
            // A week is enough to show the pattern without walking a stale open visit hour by hour.
            let rhythmEnd = min(end, visit.arrivedAt.addingTimeInterval(7 * 86_400))
            var cursor = visit.arrivedAt
            while cursor < rhythmEnd {
                guard let hour = calendar.dateInterval(of: .hour, for: cursor) else { break }
                let segmentEnd = min(hour.end, rhythmEnd)
                let parts = calendar.dateComponents([.weekday, .hour], from: cursor)
                cells[parts.weekday ?? 1][parts.hour ?? 0] += segmentEnd.timeIntervalSince(cursor)
                cursor = segmentEnd
            }
        }
        self.cells = cells
        timeThisYear = thisYear
        maximum = cells.joined().max() ?? 0
        weekdayOrder = (0..<7).map { (calendar.firstWeekday - 1 + $0) % 7 + 1 }
    }

    /// 0 means never; 1–3 are thirds of the busiest hour.
    func level(weekday: Int, hour: Int) -> Int {
        let value = cells[weekday][hour]
        guard value > 0, maximum > 0 else { return 0 }
        return min(3, Int((value / maximum) * 3) + 1)
    }

    private var peak: (weekday: Int, hour: Int)? {
        guard maximum > 0 else { return nil }
        for weekday in 1...7 {
            for hour in 0..<24 where cells[weekday][hour] == maximum { return (weekday, hour) }
        }
        return nil
    }

    /// An honest one-line read of the pattern, e.g. "mostly weekday evenings".
    var summary: String? {
        guard let peak, visitCount >= 3 else { return nil }
        let calendar = Calendar.current
        let total = cells.joined().reduce(0, +)
        let weekend = (1...7).filter { calendar.isDateInWeekend(Self.referenceDate(weekday: $0)) }
            .reduce(TimeInterval(0)) { $0 + cells[$1].reduce(0, +) }
        let part: String
        switch peak.hour {
        case 5..<11: part = String(localized: "mornings")
        case 11..<14: part = String(localized: "midday")
        case 14..<17: part = String(localized: "afternoons")
        case 17..<22: part = String(localized: "evenings")
        default: part = String(localized: "nights")
        }
        if total > 0, weekend / total >= 0.6 { return String(localized: "mostly weekend \(part)") }
        if total > 0, weekend / total <= 0.15 { return String(localized: "mostly weekday \(part)") }
        return String(localized: "mostly \(part)")
    }

    var accessibilityDescription: String {
        guard let peak else { return String(localized: "No visits yet") }
        let day = Calendar.current.standaloneWeekdaySymbols[peak.weekday - 1]
        let time = Calendar.current.date(bySettingHour: peak.hour, minute: 0, second: 0, of: Date())?
            .formatted(date: .omitted, time: .shortened) ?? "\(peak.hour)"
        return String(localized: "Most often \(day) around \(time). \(summary ?? "")")
    }

    static func weekdayLetter(_ weekday: Int) -> String {
        Calendar.current.veryShortStandaloneWeekdaySymbols[weekday - 1]
    }

    private static func referenceDate(weekday: Int) -> Date {
        // Any week works; this finds a date with the requested weekday.
        let calendar = Calendar.current
        let start = calendar.startOfDay(for: Date())
        return (0..<7).compactMap { calendar.date(byAdding: .day, value: $0, to: start) }
            .first { calendar.component(.weekday, from: $0) == weekday } ?? start
    }

    static func formatDuration(_ interval: TimeInterval) -> String {
        let minutes = max(0, Int(interval / 60))
        if minutes < 60 { return String(localized: "\(minutes)m") }
        let hours = minutes / 60
        if hours >= 48 { return String(localized: "\(hours / 24)d \(hours % 24)h") }
        return minutes % 60 == 0 ? String(localized: "\(hours)h") : String(localized: "\(hours)h \(minutes % 60)m")
    }

    static func formatHours(_ interval: TimeInterval) -> String {
        interval < 3_600 ? formatDuration(interval) : String(localized: "\(Int(interval / 3_600))h")
    }

    static func dayLabel(_ date: Date, now: Date = Date()) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(date) { return String(localized: "Today") }
        if calendar.isDateInYesterday(date) { return String(localized: "Yesterday") }
        let sameYear = calendar.isDate(date, equalTo: now, toGranularity: .year)
        return sameYear
            ? date.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day())
            : date.formatted(.dateTime.month(.abbreviated).day().year())
    }

    static func visitRangeLabel(_ visit: Visit, isCurrent: Bool) -> String {
        let start = visit.arrivedAt.formatted(date: .omitted, time: .shortened)
        let day = dayLabel(visit.arrivedAt)
        if isCurrent || visit.departedAt == nil { return String(localized: "\(day) · since \(start)") }
        let end = (visit.departedAt ?? visit.arrivedAt).formatted(date: .omitted, time: .shortened)
        return String(localized: "\(day) · \(start) – \(end)")
    }
}
