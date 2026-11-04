import CoreLocation
import SwiftData
import SwiftUI

/// A year in days: every day as a cell, colored by the country it was spent in.
struct AtlasView: View {
    let viewModel: LocationViewModel

    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.modelContext) private var modelContext
    @AppStorage("usesMetricDistanceUnits") private var usesMetricDistanceUnits = true
    @ObservedObject private var localities = LocalityResolver.shared
    @Query(filter: #Predicate<Visit> { $0.countryCode != nil }) private var visits: [Visit]
    @Query(filter: #Predicate<LocationPoint> { $0.countryCode != nil && !$0.isOutlier }) private var points: [LocationPoint]
    @Query(sort: \CountryStay.firstDay, order: .reverse) private var stays: [CountryStay]
    @Query(filter: #Predicate<SavedPlace> { $0.kindRaw == "home" }) private var homePlaces: [SavedPlace]
    /// Bumped when Home's country arrives from a reverse geocode, so the inks recompute.
    @State private var homeGeocodeRevision = 0
    @State private var range: AtlasRange = .year
    @State private var year = Calendar(identifier: .gregorian).component(.year, from: Date())
    @State private var path: [AtlasRoute] = []
    @State private var selectedDay: String?
    @State private var newStay: AtlasNewStay?
    @State private var showingRecap = false
    @State private var selectedDayDetail: AtlasDayDetail?

    private var isTwoPane: Bool { horizontalSizeClass == .regular }

    var body: some View {
        let today = CountryDayCalculator.dayIdentifier(Date(), timeZone: .current)
        let allTime = CountryDayCalculator.summaries(visits: visits, points: points, stays: stays)
        let _ = homeGeocodeRevision
        let home = CountryAtlas.homeCountryCode(homePlaces: homePlaces, visits: visits, points: points)
        // Inks follow the all-time order in both ranges, so a country keeps its colour.
        let atlas = CountryAtlas(summaries: allTime, year: range == .all ? nil : year, homeCountryCode: home)
        let years = AtlasRange.years(in: allTime, today: today)

        NavigationStack(path: $path) {
            Group {
                if isTwoPane {
                    HStack(alignment: .top, spacing: 0) {
                        ScrollView {
                            calendarColumn(atlas: atlas, years: years, today: today)
                                .padding(.bottom, 24)
                        }
                        .frame(maxWidth: .infinity)
                        Rectangle().fill(TE.border).frame(width: 1).ignoresSafeArea()
                        ScrollView {
                            VStack(alignment: .leading, spacing: 0) {
                                selectedDayCard(atlas: atlas, today: today)
                                    .padding(.horizontal, 16)
                                    .padding(.top, 16)
                                countriesColumn(atlas: atlas, today: today)
                            }
                            .padding(.bottom, 24)
                        }
                        .frame(maxWidth: 400)
                    }
                } else {
                    ScrollView {
                        VStack(alignment: .leading, spacing: 0) {
                            calendarColumn(atlas: atlas, years: years, today: today)
                            countriesColumn(atlas: atlas, today: today)
                        }
                        .padding(.bottom, 24)
                    }
                }
            }
            .scrollIndicators(.hidden)
            .background(TE.surface.ignoresSafeArea())
            .foregroundStyle(TE.textPrimary)
            .toolbar(.hidden, for: .navigationBar)
            .navigationDestination(for: AtlasRoute.self) { route in
                switch route {
                case .country(let code):
                    if let summary = atlas.summaries.first(where: { $0.countryCode == code })
                        ?? CountryAtlas(summaries: allTime).summaries.first(where: { $0.countryCode == code }) {
                        CountryDayDetailView(summary: summary, ink: atlas.ink(for: code),
                                             otherCountries: atlas.otherCountries(for: code),
                                             countedDays: atlas.daysByCountry,
                                             periodTitle: range == .all ? LocalizedStringKey("All time") : LocalizedStringKey(String(year)))
                    }
                case .countryDays:
                    CountryDaysView()
                }
            }
        }
        .sheet(item: $newStay) { request in
            CountryStayEditor(countryCode: request.countryCode, day: request.day, countedDays: atlas.daysByCountry)
        }
        .sheet(isPresented: $showingRecap) {
            NavigationStack {
                MonthRecapView(viewModel: viewModel, month: Date())
            }
        }
        .task(id: homePlaces.max(by: { $0.updatedAt < $1.updatedAt })
            .map { LocalityResolver.cell(latitude: $0.latitude, longitude: $0.longitude) } ?? "") {
            guard let place = homePlaces.max(by: { $0.updatedAt < $1.updatedAt }), home == nil else { return }
            if await HomeCountryCache.resolve(latitude: place.latitude, longitude: place.longitude) {
                homeGeocodeRevision += 1
            }
        }
        .task(id: isTwoPane ? (selectedDay ?? today) : "") {
            guard isTwoPane else { return }
            selectedDayDetail = dayDetail(for: selectedDay ?? today)
        }
        .tint(TE.accent)
    }

    // MARK: - Calendar

    private func calendarColumn(atlas: CountryAtlas, years: [Int], today: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            header(years: years)
                .padding(.horizontal, 24)
                .padding(.top, 12)

            if range == .year {
                AtlasYearGrid(year: year, atlas: atlas, today: today, selectedDay: isTwoPane ? selectedDay : nil,
                              onTap: { tapped($0, atlas: atlas, today: today) })
                    .padding(.horizontal, 24)
                    .padding(.top, 18)
            } else {
                LazyVStack(alignment: .leading, spacing: 18) {
                    ForEach(years.reversed(), id: \.self) { item in
                        VStack(alignment: .leading, spacing: 6) {
                            Text(String(item))
                                .font(TE.editorial(.title3))
                                .accessibilityAddTraits(.isHeader)
                            AtlasYearGrid(year: item, atlas: atlas, today: today, selectedDay: isTwoPane ? selectedDay : nil,
                                          onTap: { tapped($0, atlas: atlas, today: today) })
                        }
                    }
                }
                .padding(.horizontal, 24)
                .padding(.top, 18)
            }

            legend
                .padding(.leading, 56)
                .padding(.trailing, 24)
                .padding(.top, 8)
        }
    }

    private func header(years: [Int]) -> some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 12))
            : AnyLayout(HStackLayout(alignment: .bottom, spacing: 12))
        return layout {
            VStack(alignment: .leading, spacing: 2) {
                Text("ATLAS · A YEAR IN DAYS")
                    .font(TE.mono(.caption2))
                    .tracking(1.5)
                    .foregroundStyle(TE.textMuted)
                HStack(spacing: 4) {
                    Text(range == .all ? String(localized: "All years") : String(year))
                        .font(TE.editorial(.largeTitle))
                        .accessibilityAddTraits(.isHeader)
                    if range == .year {
                        yearStepper(years: years)
                    }
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            HStack(spacing: 0) {
                ForEach(AtlasRange.allCases) { item in
                    Button { range = item } label: {
                        Text(item.title)
                            .font(.footnote.weight(.medium))
                            .foregroundStyle(range == item ? TE.surface : TE.textPrimary)
                            .padding(.horizontal, 14)
                            .frame(minHeight: 44)
                            .background(range == item ? TE.accent : Color.clear)
                            .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(range == item ? .isSelected : [])
                }
            }
            .background(TE.card)
            .clipShape(Capsule())
            .overlay(Capsule().strokeBorder(TE.border, lineWidth: 1))
            .accessibilityElement(children: .contain)
            .accessibilityLabel("Range")
        }
    }

    private func yearStepper(years: [Int]) -> some View {
        HStack(spacing: 0) {
            Button { year -= 1 } label: {
                Image(systemName: "chevron.left").frame(width: 36, height: 44)
            }
            .disabled(year <= (years.first ?? year))
            .accessibilityLabel("Previous year")
            Button { year += 1 } label: {
                Image(systemName: "chevron.right").frame(width: 36, height: 44)
            }
            .disabled(year >= (years.last ?? year))
            .accessibilityLabel("Next year")
        }
        .font(.body.weight(.medium))
        .foregroundStyle(TE.textMuted)
        .buttonStyle(.plain)
    }

    private var legend: some View {
        FlowingLegend {
            legendItem("No record") { RoundedRectangle(cornerRadius: 2).fill(TE.dialTrack) }
            legendItem("Manual stay") { RoundedRectangle(cornerRadius: 2).strokeBorder(TE.accent, lineWidth: 1.5) }
            legendItem("Travel day") {
                VStack(spacing: 0) {
                    CountryAtlas.inks[0]
                    CountryAtlas.inks[1]
                }.clipShape(RoundedRectangle(cornerRadius: 2))
            }
            legendItem("Ahead") { RoundedRectangle(cornerRadius: 2).strokeBorder(TE.dialTick, lineWidth: 1) }
        }
        .font(TE.mono(.caption2))
        .foregroundStyle(TE.textMuted)
    }

    private func legendItem<Swatch: View>(_ title: LocalizedStringKey, @ViewBuilder swatch: () -> Swatch) -> some View {
        HStack(spacing: 5) {
            swatch().frame(width: 8, height: 8).accessibilityHidden(true)
            Text(title)
        }
    }

    private func tapped(_ day: String, atlas: CountryAtlas, today: String) {
        guard day <= today else { return }
        if isTwoPane {
            selectedDay = day
            return
        }
        if let top = atlas.countriesByDay[day]?.first {
            path.append(.country(top.countryCode))
        } else {
            newStay = AtlasNewStay(day: day, countryCode: nil)
        }
    }

    // MARK: - Countries

    private func countriesColumn(atlas: CountryAtlas, today: String) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            if isTwoPane {
                Text("DAYS BY COUNTRY")
                    .font(TE.mono(.caption2))
                    .tracking(1.5)
                    .foregroundStyle(TE.textMuted)
                    .padding(.horizontal, 24)
                    .padding(.top, 20)
                    .accessibilityAddTraits(.isHeader)
            }

            if atlas.summaries.isEmpty {
                CountryCard {
                    VStack(alignment: .leading, spacing: 10) {
                        Text("No country days yet")
                            .font(TE.editorial(.title3))
                        Text("Countries appear as you record. Identify countries in your earlier history or add a manual stay to fill the year.")
                            .font(.footnote)
                            .foregroundStyle(TE.textMuted)
                            .fixedSize(horizontal: false, vertical: true)
                        NavigationLink("Identify countries", value: AtlasRoute.countryDays)
                            .buttonStyle(PlacesInkButtonStyle())
                    }
                    .padding(.vertical, 12)
                }
                .padding(.horizontal, 16)
                .padding(.top, 18)
            } else {
                CountryCard {
                    LazyVStack(spacing: 0) {
                        ForEach(atlas.summaries) { summary in
                            NavigationLink(value: AtlasRoute.country(summary.countryCode)) {
                                CountryBarRow(summary: summary, ink: atlas.ink(for: summary.countryCode), maximum: atlas.maximumDays)
                            }
                            .buttonStyle(.plain)
                            .accessibilityHint("Shows the days and trips in this country.")
                            if summary.id != atlas.summaries.last?.id {
                                Rectangle().fill(TE.dialTrack).frame(height: 1)
                            }
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, isTwoPane ? 8 : 18)
            }

            let gaps = atlas.gapCount(through: today, year: range == .year ? year : nil)
            Text(gaps == 0
                 ? String(localized: "Travel days count in both countries.")
                 : String(localized: "Travel days count in both countries. \(gaps) days have no record — tap one to add a stay."))
                .font(TE.mono(.caption2))
                .foregroundStyle(TE.textMuted)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 24)
                .padding(.top, 12)

            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { actionButtons }
                VStack(spacing: 8) { actionButtons }
            }
            .padding(.horizontal, 16)
            .padding(.top, 18)
        }
    }

    @ViewBuilder
    private var actionButtons: some View {
        Button {
            showingRecap = true
        } label: {
            Text("\(Date().formatted(.dateTime.month(.wide))) recap")
        }
        .buttonStyle(PlacesInkButtonStyle())
        .accessibilityHint("Opens a shareable card for this month.")
        NavigationLink("Country days", value: AtlasRoute.countryDays)
            .buttonStyle(PlacesInkButtonStyle())
            .accessibilityHint("Export, analyze history, and manage manual stays.")
        Button("Add a stay") { newStay = AtlasNewStay(day: nil, countryCode: nil) }
            .buttonStyle(PlacesInkButtonStyle(emphasis: .primary))
    }

    // MARK: - Selected day (two-pane)

    @ViewBuilder
    private func selectedDayCard(atlas: CountryAtlas, today: String) -> some View {
        let day = selectedDay ?? today
        let presences = atlas.countriesByDay[day] ?? []
        VStack(alignment: .leading, spacing: 6) {
            Text("SELECTED · \(CountryCivilDate.format(day, .dateTime.weekday(.abbreviated).month(.abbreviated).day()).uppercased())")
                .font(TE.mono(.caption2))
                .tracking(1.2)
                .foregroundStyle(TE.onInkCardMuted)
            let detail = selectedDayDetail?.day == day ? selectedDayDetail : nil
            Text(dayTitle(presences: presences, detail: detail))
                .font(TE.editorial(.title2))
                .fixedSize(horizontal: false, vertical: true)
            if let routeLine = routeLine(detail) {
                Text(routeLine)
                    .font(.footnote)
                    .foregroundStyle(TE.onInkCardMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            Button {
                if presences.isEmpty {
                    newStay = AtlasNewStay(day: day, countryCode: nil)
                } else if let country = presences.first(where: \.recorded) ?? presences.first {
                    // The day belongs to a country's run of days; open that country's dates.
                    path.append(.country(country.countryCode))
                }
            } label: {
                Text(presences.isEmpty ? LocalizedStringKey("Add a stay") : LocalizedStringKey("Open the day"))
                    .font(.subheadline.weight(.semibold))
                    .foregroundStyle(TE.inkCard)
                    .padding(.horizontal, 16)
                    .frame(minHeight: 44)
                    .background(Capsule().fill(TE.onInkCard))
            }
            .buttonStyle(.plain)
            .padding(.top, 6)
        }
        .foregroundStyle(TE.onInkCard)
        .padding(18)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 18).fill(TE.inkCard))
    }

    /// Midnight of a civil day in the device's calendar.
    private func localStart(of day: String) -> Date? {
        guard let utcDate = CountryDayCalculator.date(for: day) else { return nil }
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(secondsFromGMT: 0)!
        let parts = utc.dateComponents([.year, .month, .day], from: utcDate)
        return Calendar.current.date(from: parts)
    }

    /// "City, Country" when the day's city is known, otherwise the countries present.
    private func dayTitle(presences: [CountryPresence], detail: AtlasDayDetail?) -> String {
        guard !presences.isEmpty else { return String(localized: "No record") }
        let countries = presences.map { Locale.current.localizedString(forRegionCode: $0.countryCode) ?? $0.countryCode }
        _ = localities.revision
        if let coordinate = detail?.coordinate,
           let city = localities.locality(latitude: coordinate.latitude, longitude: coordinate.longitude),
           !countries.contains(city) {
            return "\(city), \(countries.formatted(.list(type: .and)))"
        }
        return countries.formatted(.list(type: .and))
    }

    /// The day's visits in order, then the distance moved: "Alfama → LX Factory · 14.2 km".
    private func routeLine(_ detail: AtlasDayDetail?) -> String? {
        guard let detail else { return nil }
        var parts: [String] = []
        if !detail.visitNames.isEmpty { parts.append(detail.visitNames.joined(separator: " → ")) }
        if detail.distanceMeters >= 50 {
            parts.append(DistanceFormatter.format(meters: detail.distanceMeters, usesMetric: usesMetricDistanceUnits))
        }
        return parts.isEmpty ? nil : parts.joined(separator: " · ")
    }

    private func dayDetail(for day: String) -> AtlasDayDetail? {
        let calendar = Calendar.current
        guard let start = localStart(of: day),
              let end = calendar.date(byAdding: .day, value: 1, to: start) else { return nil }
        let visits = viewModel.visitsOverlappingDateRange(start...end.addingTimeInterval(-1))
        var names: [String] = []
        for visit in visits where names.last != visit.displayName {
            names.append(visit.displayName)
        }

        var descriptor = FetchDescriptor<LocationPoint>(
            predicate: #Predicate { $0.timestamp >= start && $0.timestamp < end && !$0.isOutlier },
            sortBy: [SortDescriptor(\.timestamp)]
        )
        descriptor.propertiesToFetch = [\.latitude, \.longitude, \.timestamp]
        let points = (try? modelContext.fetch(descriptor)) ?? []

        let now = Date()
        let longest = visits.max { lhs, rhs in
            (lhs.departedAt ?? now).timeIntervalSince(lhs.arrivedAt) < (rhs.departedAt ?? now).timeIntervalSince(rhs.arrivedAt)
        }
        let coordinate = longest.map { CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude) }
            ?? points.first.map { CLLocationCoordinate2D(latitude: $0.latitude, longitude: $0.longitude) }
        if let coordinate { localities.request([coordinate]) }

        return AtlasDayDetail(
            day: day,
            visitNames: Array(names.prefix(4)),
            distanceMeters: RecordingSessionBuilder.totalDistance(in: points),
            coordinate: coordinate
        )
    }
}

// MARK: - Year grid

/// Twelve rows of days. Each cell is filled with its country's ink; travel days split the cell.
private struct AtlasYearGrid: View {
    let year: Int
    let atlas: CountryAtlas
    let today: String
    let selectedDay: String?
    let onTap: (String) -> Void
    @ScaledMetric(relativeTo: .caption2) private var cellHeight: CGFloat = 12

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            ForEach(1...12, id: \.self) { month in
                monthRow(month)
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Calendar of recorded days, colored by country")
    }

    /// Month names in the reader's language, always counted on the Gregorian calendar the grid uses.
    private static let monthCalendar: Calendar = {
        var calendar = Calendar(identifier: .gregorian)
        calendar.locale = .current
        return calendar
    }()

    private func monthRow(_ month: Int) -> some View {
        let days = Self.days(year: year, month: month)
        let cells = days.map { cell(for: $0) }
        return HStack(spacing: 0) {
            Text(Self.monthCalendar.shortStandaloneMonthSymbols[month - 1].uppercased())
                .font(TE.mono(.caption2))
                .foregroundStyle(TE.textMuted)
                .lineLimit(1)
                .minimumScaleFactor(0.6)
                .frame(width: 32, alignment: .leading)
            GeometryReader { geometry in
                let width = geometry.size.width
                Canvas { context, size in
                    let gap: CGFloat = 2
                    let cellWidth = (size.width - gap * 30) / 31
                    for (index, cell) in cells.enumerated() {
                        let rect = CGRect(x: CGFloat(index) * (cellWidth + gap), y: 0, width: cellWidth, height: size.height)
                        draw(cell, in: rect, context: &context)
                    }
                }
                .contentShape(Rectangle())
                .onTapGesture { location in
                    // Every month shares 31 slots, so short months leave trailing space.
                    let slot = Int(location.x / max(1, (width + 2) / 31))
                    if days.indices.contains(slot) { onTap(days[slot]) }
                }
            }
            .frame(height: max(12, cellHeight))
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(Self.monthCalendar.standaloneMonthSymbols[month - 1])
        .accessibilityValue(monthSummary(days))
    }

    private enum Cell {
        case ahead, empty
        case presence([CountryPresence], selected: Bool)
        case emptySelected
    }

    private func cell(for day: String) -> Cell {
        if day > today { return .ahead }
        let presences = atlas.countriesByDay[day] ?? []
        if presences.isEmpty { return day == selectedDay ? .emptySelected : .empty }
        return .presence(presences, selected: day == selectedDay)
    }

    private func draw(_ cell: Cell, in rect: CGRect, context: inout GraphicsContext) {
        let shape = Path(roundedRect: rect, cornerRadius: 2)
        switch cell {
        case .ahead:
            context.stroke(Path(roundedRect: rect.insetBy(dx: 0.5, dy: 0.5), cornerRadius: 2), with: .color(TE.dialTick.opacity(0.7)), lineWidth: 1)
        case .empty, .emptySelected:
            context.fill(shape, with: .color(TE.dialTrack))
            if case .emptySelected = cell {
                context.stroke(Path(roundedRect: rect.insetBy(dx: -1, dy: -1), cornerRadius: 3), with: .color(TE.inkRust), lineWidth: 1.5)
            }
        case .presence(let presences, let selected):
            let recorded = presences.filter(\.recorded)
            if recorded.isEmpty {
                // Manual-only days are outlined so filled cells always mean a recording.
                context.fill(shape, with: .color(TE.surface))
                let ink = atlas.ink(for: presences[0].countryCode)
                context.stroke(Path(roundedRect: rect.insetBy(dx: 0.75, dy: 0.75), cornerRadius: 2), with: .color(ink), lineWidth: 1.5)
            } else {
                var clipped = context
                clipped.clip(to: shape)
                let band = rect.height / CGFloat(recorded.count)
                for (index, presence) in recorded.enumerated() {
                    let slice = CGRect(x: rect.minX, y: rect.minY + band * CGFloat(index), width: rect.width, height: band)
                    clipped.fill(Path(slice), with: .color(atlas.ink(for: presence.countryCode)))
                }
            }
            if selected {
                context.stroke(Path(roundedRect: rect.insetBy(dx: -1, dy: -1), cornerRadius: 3), with: .color(TE.inkRust), lineWidth: 1.5)
            }
        }
    }

    private func monthSummary(_ days: [String]) -> String {
        var counts: [String: Int] = [:]
        var empty = 0
        for day in days where day <= today {
            let presences = atlas.countriesByDay[day] ?? []
            if presences.isEmpty { empty += 1 }
            for presence in presences { counts[presence.countryCode, default: 0] += 1 }
        }
        var parts = counts.sorted { $0.value > $1.value }.map { code, count in
            let name = Locale.current.localizedString(forRegionCode: code) ?? code
            return count == 1 ? String(localized: "\(name), 1 day") : String(localized: "\(name), \(count) days")
        }
        if empty > 0 { parts.append(String(localized: "\(empty) days with no record")) }
        if parts.isEmpty { return String(localized: "Ahead") }
        return parts.joined(separator: "; ")
    }

    static func days(year: Int, month: Int) -> [String] {
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        guard let first = calendar.date(from: DateComponents(year: year, month: month, day: 1)),
              let count = calendar.range(of: .day, in: .month, for: first)?.count else { return [] }
        return (1...count).map { String(format: "%04d-%02d-%02d", year, month, $0) }
    }
}

/// Legend items wrap onto more lines at large text sizes instead of truncating.
private struct FlowingLegend<Content: View>: View {
    @ViewBuilder let content: Content
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    var body: some View {
        if dynamicTypeSize.isAccessibilitySize {
            VStack(alignment: .leading, spacing: 6) { content }
        } else {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 14) { content }
                VStack(alignment: .leading, spacing: 6) { content }
            }
        }
    }
}

// MARK: - Model

private enum AtlasRange: String, CaseIterable, Identifiable {
    case year, all
    var id: Self { self }
    var title: LocalizedStringKey {
        switch self {
        case .year: "Year"
        case .all: "All"
        }
    }

    /// Every year from the first recorded or manual day through this year.
    static func years(in summaries: [CountryDaySummary], today: String) -> [Int] {
        let current = Int(today.prefix(4)) ?? Calendar.current.component(.year, from: Date())
        let earliest = summaries.flatMap { $0.days.map(\.day) }.min().flatMap { Int($0.prefix(4)) } ?? current
        return Array(min(earliest, current)...current)
    }
}

enum AtlasRoute: Hashable {
    case country(String)
    case countryDays
}

/// What the two-pane selected-day card shows about one day.
private struct AtlasDayDetail {
    let day: String
    let visitNames: [String]
    let distanceMeters: Double
    let coordinate: CLLocationCoordinate2D?
}

private struct AtlasNewStay: Identifiable {
    let id = UUID()
    let day: String?
    let countryCode: String?
}

/// Country-day summaries indexed by day and by country, with a stable ink per country.
struct CountryAtlas {
    let summaries: [CountryDaySummary]
    /// Presences per day, most-visited country first.
    let countriesByDay: [String: [CountryPresence]]
    let daysByCountry: [String: Set<String>]
    private let rank: [String: Int]

    /// - Parameters:
    ///   - summaries: all-time summaries (most days first); `year` narrows what is shown.
    ///   - inkOrder: country codes in the order inks are handed out. Defaults to `summaries`' order,
    ///     which is all-time when `summaries` is, so a country keeps its colour between Year and All.
    ///   - homeCountryCode: the saved Home's country. It always takes the first (home) ink;
    ///     without one, the all-time most-days country does.
    init(summaries: [CountryDaySummary], year: Int? = nil, inkOrder: [String]? = nil, homeCountryCode: String? = nil) {
        var order = inkOrder ?? summaries.map(\.countryCode)
        if let homeCountryCode {
            order.removeAll { $0 == homeCountryCode }
            order.insert(homeCountryCode, at: 0)
        }
        for summary in summaries where !order.contains(summary.countryCode) {
            order.append(summary.countryCode)
        }
        var rank: [String: Int] = [:]
        for code in order where rank[code] == nil { rank[code] = rank.count }
        self.rank = rank

        let prefix = year.map { String(format: "%04d-", $0) }
        let scoped: [CountryDaySummary]
        if let prefix {
            scoped = summaries.compactMap { summary in
                let days = summary.days.filter { $0.day.hasPrefix(prefix) }
                return days.isEmpty ? nil : CountryDaySummary(countryCode: summary.countryCode, days: days)
            }
            .sorted {
                if $0.days.count != $1.days.count { return $0.days.count > $1.days.count }
                return $0.name.localizedStandardCompare($1.name) == .orderedAscending
            }
        } else {
            scoped = summaries
        }
        self.summaries = scoped
        var byDay: [String: [CountryPresence]] = [:]
        var byCountry: [String: Set<String>] = [:]
        for summary in scoped {
            for presence in summary.days {
                byDay[presence.day, default: []].append(presence)
                byCountry[summary.countryCode, default: []].insert(presence.day)
            }
        }
        countriesByDay = byDay
        daysByCountry = byCountry
    }

    var maximumDays: Int { summaries.first?.days.count ?? 1 }

    /// The saved Home's country: the most common country among recorded visits (then points)
    /// inside Home's radius, else a cached reverse geocode of Home's coordinate. Nil with no Home,
    /// or while Home's country is still unknown.
    @MainActor
    static func homeCountryCode(homePlaces: [SavedPlace], visits: [Visit], points: [LocationPoint]) -> String? {
        guard let home = homePlaces.max(by: { $0.updatedAt < $1.updatedAt }) else { return nil }
        var counts: [String: Int] = [:]
        for visit in visits {
            guard let code = visit.countryCode,
                  home.contains(CLLocationCoordinate2D(latitude: visit.latitude, longitude: visit.longitude)) else { continue }
            counts[code, default: 0] += 1
        }
        if counts.isEmpty {
            for point in points {
                guard let code = point.countryCode,
                      home.contains(CLLocationCoordinate2D(latitude: point.latitude, longitude: point.longitude)) else { continue }
                counts[code, default: 0] += 1
            }
        }
        if let best = counts.max(by: { $0.value != $1.value ? $0.value < $1.value : $0.key > $1.key }) {
            return best.key
        }
        return HomeCountryCache.country(latitude: home.latitude, longitude: home.longitude)
    }

    /// Country inks in rank order. Home's country takes navy on paper and pale blue at night
    /// (the all-time most-visited country when no Home is saved); every further country gets
    /// its own distinct ink, assigned in all-time order so colours hold between Year and All.
    static let inks: [Color] = [
        TE.adaptive(0x1E2B45, 0xC9D3EC), // home: navy / pale blue
        TE.adaptive(0xC2462A, 0xF0805F), // rust
        TE.adaptive(0xB7862B, 0xE0B565), // ochre
        TE.adaptive(0x5E7F5A, 0x9DC097), // sage
        TE.adaptive(0x7A86A8, 0x8E9BC2), // slate
        TE.adaptive(0x7D4E6B, 0xC99BB9), // plum
        TE.adaptive(0x3E7C7D, 0x86C3C1), // teal
        TE.adaptive(0x8A5A3B, 0xD3A27E), // umber
        TE.adaptive(0x6E7232, 0xC4C77F), // olive
        TE.adaptive(0xB0546A, 0xE995A8), // rose
        TE.adaptive(0x2F5D4E, 0x7FB6A1), // pine
        TE.adaptive(0x4B4F8F, 0xA2A6E2)  // indigo
    ]

    func ink(for countryCode: String) -> Color {
        let index = rank[countryCode] ?? 0
        return Self.inks[index % Self.inks.count]
    }

    /// For each of the country's days, the other countries present that day.
    func otherCountries(for countryCode: String) -> [String: [String]] {
        var result: [String: [String]] = [:]
        for day in daysByCountry[countryCode] ?? [] {
            let others = (countriesByDay[day] ?? []).map(\.countryCode).filter { $0 != countryCode }
            if !others.isEmpty {
                result[day] = others.map { Locale.current.localizedString(forRegionCode: $0) ?? $0 }
            }
        }
        return result
    }

    /// Days with no record between the first recorded day and today (within `year` when given).
    func gapCount(through today: String, year: Int?) -> Int {
        guard var cursor = countriesByDay.keys.min() else { return 0 }
        var last = today
        if let year {
            let start = String(format: "%04d-01-01", year)
            let end = String(format: "%04d-12-31", year)
            if cursor < start { cursor = start }
            if end < last { last = end }
        }
        guard cursor <= last,
              var date = CountryDayCalculator.date(for: cursor),
              let end = CountryDayCalculator.date(for: last) else { return 0 }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        var gaps = 0
        while date <= end {
            let parts = calendar.dateComponents([.year, .month, .day], from: date)
            let day = String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
            if countriesByDay[day] == nil { gaps += 1 }
            guard let next = calendar.date(byAdding: .day, value: 1, to: date) else { break }
            date = next
        }
        return gaps
    }
}

/// Reverse-geocoded country for the saved Home's coordinate, cached on disk per ~1 km cell.
/// Used only when no recorded visit or point inside Home already carries a country.
@MainActor
enum HomeCountryCache {
    private static let key = "atlas.homeCountryByCell.v1"
    private static var inFlight = Set<String>()

    static func country(latitude: Double, longitude: Double) -> String? {
        let cache = UserDefaults.standard.dictionary(forKey: key) as? [String: String] ?? [:]
        return cache[LocalityResolver.cell(latitude: latitude, longitude: longitude)]
    }

    /// Looks the country up once when it isn't cached and network geocoding is allowed.
    /// Returns true when a new country was stored.
    @discardableResult
    static func resolve(latitude: Double, longitude: Double) async -> Bool {
        let cell = LocalityResolver.cell(latitude: latitude, longitude: longitude)
        guard country(latitude: latitude, longitude: longitude) == nil,
              NetworkGeocodingPolicy.isAllowed,
              CLLocationCoordinate2DIsValid(CLLocationCoordinate2D(latitude: latitude, longitude: longitude)),
              !inFlight.contains(cell) else { return false }
        inFlight.insert(cell)
        defer { inFlight.remove(cell) }
        guard let placemarks = try? await CLGeocoder().reverseGeocodeLocation(CLLocation(latitude: latitude, longitude: longitude)),
              let code = placemarks.first?.isoCountryCode, !code.isEmpty else { return false }
        var cache = UserDefaults.standard.dictionary(forKey: key) as? [String: String] ?? [:]
        cache[cell] = code.uppercased()
        UserDefaults.standard.set(cache, forKey: key)
        return true
    }
}
