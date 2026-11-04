import SwiftUI
import SwiftData
import UniformTypeIdentifiers

struct CountryDaysView: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Query(filter: #Predicate<Visit> { $0.countryCode != nil }) private var visits: [Visit]
    @Query(filter: #Predicate<LocationPoint> { $0.countryCode != nil && !$0.isOutlier }) private var points: [LocationPoint]
    @Query(sort: \CountryStay.firstDay, order: .reverse) private var stays: [CountryStay]
    @StateObject private var resolver = CountryDayResolver()
    @ObservedObject private var storeManager = StoreManager.shared
    @State private var period: CountryDayPeriod = .year
    @State private var showingAddStay = false
    @State private var showingPaywall = false
    @State private var showingExport = false
    @State private var exportDocument = CountryDaysCSVDocument(text: "")
    @State private var errorMessage: String?
    @State private var stayToDelete: CountryStay?
    @State private var editingStay: CountryStay?

    private var summaries: [CountryDaySummary] {
        let bounds = period.bounds()
        return CountryDayCalculator.summaries(visits: visits, points: points, stays: stays,
                                               firstDay: bounds.first, lastDay: bounds.last)
    }

    var body: some View {
        let summaries = summaries
        let atlas = CountryAtlas(summaries: summaries)
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                VStack(alignment: .leading, spacing: 2) {
                    Text("COUNTRY DAYS")
                        .font(TE.mono(.caption2))
                        .tracking(1.5)
                        .foregroundStyle(TE.textMuted)
                    Text("Countries")
                        .font(TE.editorial(.largeTitle))
                        .foregroundStyle(TE.textPrimary)
                        .accessibilityAddTraits(.isHeader)
                }
                .padding(.horizontal, 8)

                Picker("Date range", selection: $period) {
                    ForEach(CountryDayPeriod.allCases) { item in Text(item.title).tag(item) }
                }
                .pickerStyle(.segmented)

                overview(summaries)
                analysis
                countryList(atlas)
                manualStays
                Text("A day counts once per country, using the recorded location’s local date. A travel day can count in more than one country. Open visits and unverified departure times count only the arrival day; route points add further recorded days. Gaps are not filled automatically.")
                    .font(TE.mono(.caption2))
                    .foregroundStyle(TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
                    .padding(.horizontal, 8)
            }
            .padding(16)
            .padding(.bottom, 24)
        }
        .background(TE.surface.ignoresSafeArea())
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
        .toolbar {
            ToolbarItemGroup(placement: .topBarTrailing) {
                Button("Export", systemImage: "square.and.arrow.up") { export(summaries) }
                    .disabled(summaries.isEmpty)
                Button("Add stay", systemImage: "plus") { showingAddStay = true }
            }
        }
        .sheet(isPresented: $showingAddStay) { CountryStayEditor(countedDays: atlas.daysByCountry) }
        .sheet(item: $editingStay) { CountryStayEditor(stay: $0) }
        .sheet(isPresented: $showingPaywall) { PaywallView(storeManager: storeManager) }
        .fileExporter(isPresented: $showingExport, document: exportDocument,
                      contentType: .commaSeparatedText, defaultFilename: "iso-me-country-days") { result in
            switch result {
            case .success: AppReviewPromptCoordinator.shared.recordSuccessfulFileExport()
            case .failure(let error): errorMessage = error.localizedDescription
            }
        }
        .alert("Could Not Complete Action", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) { errorMessage = nil }
        } message: { Text(errorMessage ?? "") }
        .confirmationDialog("Delete manual stay?", isPresented: Binding(get: { stayToDelete != nil }, set: { if !$0 { stayToDelete = nil } }), titleVisibility: .visible) {
            Button("Delete stay", role: .destructive) {
                guard let stay = stayToDelete else { return }
                modelContext.delete(stay)
                do { try modelContext.save() } catch { errorMessage = error.localizedDescription }
                stayToDelete = nil
            }
        }
        .onDisappear { resolver.cancel() }
        .tint(TE.accent)
    }

    private func overview(_ summaries: [CountryDaySummary]) -> some View {
        let uniqueDays = Set(summaries.flatMap { $0.days.map(\.day) }).count
        return PlacesStatStrip(items: [
            (summaries.count.formatted(), summaries.count == 1 ? String(localized: "COUNTRY") : String(localized: "COUNTRIES")),
            (uniqueDays.formatted(), String(localized: "DAYS WITH RECORDED OR MANUAL PRESENCE"))
        ])
        .padding(.horizontal, 8)
    }

    private var analysis: some View {
        CountryCard {
            VStack(alignment: .leading, spacing: 12) {
                Label("Identify countries", systemImage: "location.magnifyingglass")
                    .font(TE.editorial(.title3))
                    .foregroundStyle(TE.textPrimary)
                Text("New recordings identify countries automatically when network geocoding is enabled. Analyze saved locations to include your earlier history. Large histories can take time; you can stop and resume.")
                    .font(.footnote)
                    .foregroundStyle(TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
                if resolver.isRunning {
                    HStack {
                        ProgressView()
                        Text("\(resolver.completed) locations analyzed")
                            .font(TE.mono(.caption)).monospacedDigit()
                        Spacer()
                        Button("Stop") { resolver.cancel() }
                            .frame(minHeight: 44)
                    }
                } else {
                    Button("Analyze saved locations") {
                        Task { await resolver.resolveHistory(in: modelContext) }
                    }
                    .buttonStyle(PlacesInkButtonStyle())
                }
                if let message = resolver.message {
                    Text(message).font(.footnote).foregroundStyle(TE.textMuted)
                }
            }
            .padding(.vertical, 12)
        }
    }

    private func countryList(_ atlas: CountryAtlas) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("DAYS BY COUNTRY")
                .font(TE.mono(.caption2, weight: .semibold))
                .tracking(1.5)
                .foregroundStyle(TE.textMuted)
                .padding(.horizontal, 8)
                .accessibilityAddTraits(.isHeader)
            if atlas.summaries.isEmpty {
                CountryCard {
                    ContentUnavailableView("No country days yet", systemImage: "globe.europe.africa",
                                           description: Text("Analyze saved locations or add a manual stay. Try All time to see older records."))
                }
            } else {
                CountryCard {
                    LazyVStack(spacing: 0) {
                        ForEach(atlas.summaries) { summary in
                            NavigationLink {
                                CountryDayDetailView(summary: summary, ink: atlas.ink(for: summary.countryCode),
                                                     otherCountries: atlas.otherCountries(for: summary.countryCode),
                                                     countedDays: atlas.daysByCountry, periodTitle: period.title)
                            } label: {
                                CountryBarRow(summary: summary, ink: atlas.ink(for: summary.countryCode), maximum: atlas.maximumDays)
                            }
                            .buttonStyle(.plain)
                            if summary.id != atlas.summaries.last?.id {
                                Rectangle().fill(TE.dialTrack).frame(height: 1)
                            }
                        }
                    }
                }
            }
        }
    }

    private var manualStays: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("MANUAL STAYS")
                    .font(TE.mono(.caption2, weight: .semibold))
                    .tracking(1.5)
                    .foregroundStyle(TE.textMuted)
                    .accessibilityAddTraits(.isHeader)
                Spacer()
                Button("Add stay") { showingAddStay = true }
                    .font(.subheadline.weight(.medium))
                    .foregroundStyle(TE.inkRust)
                    .frame(minHeight: 44)
            }
            .padding(.horizontal, 8)
            Text("Fill missing days. Overlapping stays and recorded days are counted only once.")
                .font(.footnote).foregroundStyle(TE.textMuted)
                .padding(.horizontal, 8)
            if !stays.isEmpty {
                CountryCard {
                    LazyVStack(spacing: 0) {
                        ForEach(stays) { stay in
                            HStack {
                                Button {
                                    editingStay = stay
                                } label: {
                                    HStack(spacing: 12) {
                                        RoundedRectangle(cornerRadius: 3)
                                            .strokeBorder(TE.accent, lineWidth: 1.5)
                                            .frame(width: 12, height: 12)
                                            .accessibilityHidden(true)
                                        VStack(alignment: .leading, spacing: 2) {
                                            Text(Locale.current.localizedString(forRegionCode: stay.countryCode) ?? stay.countryCode)
                                                .font(TE.editorial(.title3))
                                                .foregroundStyle(TE.textPrimary)
                                            Text(CountryCivilDate.range(stay.firstDay, stay.lastDay))
                                                .font(TE.mono(.caption2)).foregroundStyle(TE.textMuted)
                                        }
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                    }
                                    .padding(.vertical, 12)
                                    .contentShape(Rectangle())
                                }
                                .buttonStyle(.plain)
                                Button("Delete stay", systemImage: "trash", role: .destructive) { stayToDelete = stay }
                                    .labelStyle(.iconOnly)
                                    .foregroundStyle(TE.inkRust)
                                    .frame(width: 44, height: 44)
                            }
                            if stay.id != stays.last?.id {
                                Rectangle().fill(TE.dialTrack).frame(height: 1)
                            }
                        }
                    }
                }
            }
        }
    }

    private func export(_ summaries: [CountryDaySummary]) {
        guard storeManager.canExport else { showingPaywall = true; return }
        exportDocument = CountryDaysCSVDocument(text: CountryDayCalculator.csv(summaries))
        showingExport = true
    }
}

/// The paper card used by the country screens.
struct CountryCard<Content: View>: View {
    @ViewBuilder let content: Content

    var body: some View {
        content
            .padding(.horizontal, 16)
            .padding(.vertical, 4)
            .frame(maxWidth: .infinity, alignment: .leading)
            .background(RoundedRectangle(cornerRadius: 18).fill(TE.card))
            .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.border, lineWidth: 1))
    }
}

/// A country with its day count and a bar relative to the most-visited country.
struct CountryBarRow: View {
    let summary: CountryDaySummary
    let ink: Color
    let maximum: Int

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            ViewThatFits(in: .horizontal) {
                HStack(alignment: .firstTextBaseline) {
                    name
                    Spacer(minLength: 8)
                    count
                }
                VStack(alignment: .leading, spacing: 2) {
                    name
                    count
                }
            }
            GeometryReader { geometry in
                ZStack(alignment: .leading) {
                    Capsule().fill(TE.dialTrack)
                    Capsule().fill(ink)
                        .frame(width: max(6, geometry.size.width * Double(summary.days.count) / Double(max(maximum, 1))))
                }
            }
            .frame(height: 6)
            .accessibilityHidden(true)
        }
        .padding(.vertical, 10)
        .frame(minHeight: 44)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private var name: some View {
        Text(summary.name)
            .font(TE.editorial(.title3))
            .foregroundStyle(TE.textPrimary)
    }

    private var count: some View {
        Text(summary.days.count == 1 ? String(localized: "1 day") : String(localized: "\(summary.days.count) days"))
            .font(TE.mono(.footnote))
            .monospacedDigit()
            .foregroundStyle(TE.textPrimary)
    }
}

/// Formats civil `yyyy-MM-dd` days without shifting them through the device time zone.
enum CountryCivilDate {
    private static let utc = TimeZone(secondsFromGMT: 0)!

    static func format(_ day: String, _ style: Date.FormatStyle) -> String {
        guard let date = CountryDayCalculator.date(for: day) else { return day }
        var style = style
        style.timeZone = utc
        return date.formatted(style)
    }

    static func short(_ day: String) -> String {
        let currentYear = CountryDayCalculator.dayIdentifier(Date(), timeZone: .current).prefix(4)
        return day.hasPrefix(currentYear)
            ? format(day, .dateTime.month(.abbreviated).day())
            : format(day, .dateTime.month(.abbreviated).day().year())
    }

    static func range(_ first: String, _ last: String) -> String {
        first == last ? short(first) : "\(short(first)) – \(short(last))"
    }

    static func weekday(_ day: String) -> String {
        format(day, .dateTime.weekday(.abbreviated))
    }

    static func next(_ day: String) -> String? {
        guard let date = CountryDayCalculator.date(for: day) else { return nil }
        var calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = utc
        return calendar.date(byAdding: .day, value: 1, to: date).map { CountryDayCalculator.dayIdentifier($0, timeZone: utc) }
    }
}

/// One country's days, grouped into trips of consecutive days (artboard "Country day").
struct CountryDayDetailView: View {
    let summary: CountryDaySummary
    var ink: Color = TE.placeInks[0]
    /// Other countries present on the same local date, for travel days.
    var otherCountries: [String: [String]] = [:]
    /// Days already counted per country, so a new stay can say how many days it adds.
    var countedDays: [String: Set<String>] = [:]
    var periodTitle: LocalizedStringKey?

    @ObservedObject private var storeManager = StoreManager.shared
    @State private var showingAddStay = false
    @State private var showingPaywall = false
    @State private var showingExport = false
    @State private var exportDocument = CountryDaysCSVDocument(text: "")
    @State private var errorMessage: String?

    private struct Segment: Identifiable {
        enum Kind: Equatable {
            case recorded, manual, both, travel([String])
            var isTravel: Bool { if case .travel = self { true } else { false } }
        }
        let first: String
        var last: String
        var count: Int
        let kind: Kind
        var id: String { first }
    }

    private struct Trip: Identifiable {
        let first: String
        var last: String
        var segments: [Segment]
        var id: String { first }
    }

    private func kind(_ presence: CountryPresence) -> Segment.Kind {
        if let others = otherCountries[presence.day], !others.isEmpty { return .travel(others) }
        if presence.recorded && presence.manual { return .both }
        return presence.manual ? .manual : .recorded
    }

    private var trips: [Trip] {
        var trips: [Trip] = []
        for presence in summary.days.sorted(by: { $0.day < $1.day }) {
            let kind = kind(presence)
            if var trip = trips.last, CountryCivilDate.next(trip.last) == presence.day {
                trip.last = presence.day
                if var segment = trip.segments.last, segment.kind == kind, !kind.isTravel {
                    segment.last = presence.day
                    segment.count += 1
                    trip.segments[trip.segments.count - 1] = segment
                } else {
                    trip.segments.append(Segment(first: presence.day, last: presence.day, count: 1, kind: kind))
                }
                trips[trips.count - 1] = trip
            } else {
                trips.append(Trip(first: presence.day, last: presence.day,
                                  segments: [Segment(first: presence.day, last: presence.day, count: 1, kind: kind)]))
            }
        }
        return trips.reversed()
    }

    var body: some View {
        let trips = trips
        let travelDays = summary.days.filter { !(otherCountries[$0.day] ?? []).isEmpty }.count
        let manualDays = summary.days.filter { $0.manual && !$0.recorded }.count
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                VStack(alignment: .leading, spacing: 6) {
                    Group {
                        if let periodTitle {
                            Text("COUNTRY · ") + Text(periodTitle)
                        } else {
                            Text("COUNTRY")
                        }
                    }
                    .textCase(.uppercase)
                    .font(TE.mono(.caption2))
                    .tracking(1.5)
                    .foregroundStyle(TE.textMuted)
                    Text(summary.name)
                        .font(TE.editorial(.largeTitle))
                        .accessibilityAddTraits(.isHeader)
                }
                .padding(.horizontal, 24)
                .padding(.top, 8)

                PlacesStatStrip(items: [
                    (summary.days.count.formatted(), summary.days.count == 1 ? String(localized: "DAY") : String(localized: "DAYS")),
                    (trips.count.formatted(), trips.count == 1 ? String(localized: "TRIP") : String(localized: "TRIPS")),
                    (travelDays.formatted(), String(localized: "TRAVEL DAYS")),
                    (manualDays.formatted(), String(localized: "MANUAL"))
                ])
                .padding(.horizontal, 24)
                .padding(.top, 14)

                LazyVStack(alignment: .leading, spacing: 16) {
                    ForEach(trips) { trip in
                        VStack(alignment: .leading, spacing: 8) {
                            Text(CountryCivilDate.range(trip.first, trip.last).uppercased())
                                .font(TE.mono(.caption2))
                                .tracking(1.5)
                                .foregroundStyle(TE.textMuted)
                                .padding(.horizontal, 8)
                                .accessibilityAddTraits(.isHeader)
                            CountryCard {
                                VStack(spacing: 0) {
                                    ForEach(trip.segments) { segment in
                                        segmentRow(segment)
                                        if segment.id != trip.segments.last?.id {
                                            Rectangle().fill(TE.dialTrack).frame(height: 1)
                                        }
                                    }
                                }
                            }
                        }
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 16)

                Text("Dates use the local time zone of each place. Unknown gaps stay empty — nothing is guessed.")
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
                .padding(.top, 20)
            }
            .padding(.bottom, 24)
        }
        .background(TE.surface.ignoresSafeArea())
        .foregroundStyle(TE.textPrimary)
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
        .sheet(isPresented: $showingAddStay) {
            CountryStayEditor(countryCode: summary.countryCode, countedDays: countedDays)
        }
        .sheet(isPresented: $showingPaywall) { PaywallView(storeManager: storeManager) }
        .fileExporter(isPresented: $showingExport, document: exportDocument,
                      contentType: .commaSeparatedText, defaultFilename: "iso-me-country-days-\(summary.countryCode.lowercased())") { result in
            switch result {
            case .success: AppReviewPromptCoordinator.shared.recordSuccessfulFileExport()
            case .failure(let error): errorMessage = error.localizedDescription
            }
        }
        .alert("Could Not Complete Action", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) { errorMessage = nil }
        } message: { Text(errorMessage ?? "") }
        .tint(TE.accent)
    }

    @ViewBuilder
    private var actionButtons: some View {
        Button("Export CSV") {
            guard storeManager.canExport else { showingPaywall = true; return }
            exportDocument = CountryDaysCSVDocument(text: CountryDayCalculator.csv([summary]))
            showingExport = true
        }
        .buttonStyle(PlacesInkButtonStyle())
        Button("Add a stay") { showingAddStay = true }
            .buttonStyle(PlacesInkButtonStyle(emphasis: .primary))
    }

    private func segmentRow(_ segment: Segment) -> some View {
        HStack(spacing: 12) {
            swatch(segment.kind)
                .frame(width: 12, height: 12)
                .frame(width: 14)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 1) {
                Text(CountryCivilDate.range(segment.first, segment.last))
                    .font(TE.editorial(.title3))
                Text(detail(segment.kind))
                    .font(TE.mono(.caption2))
                    .foregroundStyle(TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Text(segment.count == 1 ? CountryCivilDate.weekday(segment.first) : String(localized: "\(segment.count) days"))
                .font(TE.mono(.caption))
                .foregroundStyle(TE.textMuted)
        }
        .frame(minHeight: 58)
        .accessibilityElement(children: .combine)
    }

    @ViewBuilder
    private func swatch(_ kind: Segment.Kind) -> some View {
        let shape = RoundedRectangle(cornerRadius: 3)
        switch kind {
        case .recorded, .both:
            shape.fill(ink)
        case .manual:
            shape.strokeBorder(ink, lineWidth: 1.5)
        case .travel:
            shape.fill(TE.dialTrack).overlay(shape.strokeBorder(TE.textPrimary, lineWidth: 1.5))
        }
    }

    private func detail(_ kind: Segment.Kind) -> String {
        switch kind {
        case .recorded: String(localized: "Recorded")
        case .both: String(localized: "Recorded + manual")
        case .manual: String(localized: "Manual stay · no record")
        case .travel(let others):
            String(localized: "Travel day · also \(others.formatted(.list(type: .and)))")
        }
    }
}

enum CountryDayPeriod: String, CaseIterable, Identifiable {
    case year, rolling, all
    var id: Self { self }
    var title: LocalizedStringKey {
        switch self {
        case .year: "This year"
        case .rolling: "Last 365 days"
        case .all: "All time"
        }
    }
    func bounds(now: Date = Date()) -> (first: String?, last: String?) {
        let today = CountryDayCalculator.dayIdentifier(now, timeZone: .current)
        switch self {
        case .all: return (nil, nil)
        case .year: return (String(today.prefix(4)) + "-01-01", String(today.prefix(4)) + "-12-31")
        case .rolling:
            var calendar = Calendar(identifier: .gregorian)
            calendar.timeZone = TimeZone(secondsFromGMT: 0)!
            let todayDate = CountryDayCalculator.date(for: today) ?? now
            let start = calendar.date(byAdding: .day, value: -364, to: todayDate) ?? todayDate
            return (CountryDayCalculator.dayIdentifier(start, timeZone: calendar.timeZone), nil)
        }
    }
}

/// Adds or edits a manual country stay (artboard "Stay editor").
struct CountryStayEditor: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss
    var stay: CountryStay?
    /// Days already counted per country, used to say how many new days a stay adds.
    var countedDays: [String: Set<String>]
    private let initialCountryCode: String?
    private let initialDay: String?
    @State private var countryCode = Locale.current.region?.identifier ?? "PT"
    @State private var firstDate = Date()
    @State private var lastDate = Date()
    @State private var errorMessage: String?
    @State private var hasInitialized = false

    init(stay: CountryStay? = nil, countryCode: String? = nil, day: String? = nil, countedDays: [String: Set<String>] = [:]) {
        self.stay = stay
        self.countedDays = countedDays
        initialCountryCode = countryCode
        initialDay = day
    }

    private var countries: [(code: String, name: String)] {
        Locale.Region.isoRegions.filter { $0.identifier.count == 2 }.map {
            (code: $0.identifier, name: Locale.current.localizedString(forRegionCode: $0.identifier) ?? $0.identifier)
        }.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }
    private var firstDay: String { CountryDayCalculator.dayIdentifier(firstDate, timeZone: .current) }
    private var lastDay: String { CountryDayCalculator.dayIdentifier(lastDate, timeZone: .current) }
    private var countryName: String { Locale.current.localizedString(forRegionCode: countryCode) ?? countryCode }
    private var canSave: Bool { firstDay <= lastDay && countries.contains(where: { $0.code == countryCode }) }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    NavigationLink {
                        CountrySelectionView(countryCode: $countryCode)
                    } label: {
                        HStack(spacing: 12) {
                            RoundedRectangle(cornerRadius: 3)
                                .strokeBorder(TE.accent, lineWidth: 1.5)
                                .frame(width: 12, height: 12)
                                .accessibilityHidden(true)
                            VStack(alignment: .leading, spacing: 0) {
                                Text("COUNTRY")
                                    .font(TE.mono(.caption2))
                                    .tracking(1.2)
                                    .foregroundStyle(TE.textMuted)
                                Text(countryName)
                                    .font(TE.editorial(.title2))
                                    .foregroundStyle(TE.textPrimary)
                            }
                            .frame(maxWidth: .infinity, alignment: .leading)
                            Text("Change ›")
                                .font(TE.mono(.caption))
                                .foregroundStyle(TE.textMuted)
                                .accessibilityHidden(true)
                        }
                        .padding(.horizontal, 16)
                        .frame(minHeight: 64)
                        .background(RoundedRectangle(cornerRadius: 16).fill(TE.card))
                        .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(TE.border, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel("Country")
                    .accessibilityValue(countryName)
                    .accessibilityHint("Choose the country for this stay.")

                    ViewThatFits(in: .horizontal) {
                        HStack(alignment: .top, spacing: 10) { datePickers }
                        VStack(alignment: .leading, spacing: 10) { datePickers }
                    }

                    if firstDay > lastDay {
                        Text("Departure must be on or after arrival.")
                            .font(.footnote)
                            .foregroundStyle(TE.inkRust)
                    }
                    if let errorMessage {
                        Text(errorMessage).font(.footnote).foregroundStyle(TE.inkRust)
                    }

                    VStack(alignment: .leading, spacing: 6) {
                        Text(impactTitle)
                            .font(TE.editorial(.title3))
                        Text("Both dates count, including a same-day visit. Recorded presence in other countries is kept, and days that already have records won't be counted twice. You'll see these days outlined in the Atlas.")
                            .font(.footnote)
                            .foregroundStyle(TE.textMuted)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(14)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .background(RoundedRectangle(cornerRadius: 14).fill(TE.dialTrack))
                    .accessibilityElement(children: .combine)

                    Button(stay == nil ? LocalizedStringKey("Add stay") : LocalizedStringKey("Save stay")) { save() }
                        .buttonStyle(PlacesInkButtonStyle(emphasis: .primary))
                        .disabled(!canSave)
                        .padding(.top, 8)
                }
                .padding(.horizontal, 16)
                .padding(.top, 14)
                .padding(.bottom, 24)
            }
            .background(TE.surface.ignoresSafeArea())
            .foregroundStyle(TE.textPrimary)
            .navigationTitle(stay == nil ? "Add Country Stay" : "Edit Country Stay")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .foregroundStyle(TE.textMuted)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .fontWeight(.semibold)
                        .foregroundStyle(canSave ? TE.inkRust : TE.textMuted)
                        .disabled(!canSave)
                }
            }
            .onAppear {
                guard !hasInitialized else { return }
                hasInitialized = true
                if let stay {
                    countryCode = stay.countryCode
                    // Convert civil dates to noon in the device's current zone for the date picker.
                    firstDate = pickerDate(stay.firstDay) ?? Date()
                    lastDate = pickerDate(stay.lastDay) ?? Date()
                    return
                }
                if let initialCountryCode { countryCode = initialCountryCode }
                if let initialDay, let date = pickerDate(initialDay) {
                    firstDate = date
                    lastDate = date
                }
            }
        }
        .tint(TE.accent)
    }

    @ViewBuilder
    private var datePickers: some View {
        dateField("FROM", accessibilityTitle: "Arrival day", selection: $firstDate)
        dateField("TO", accessibilityTitle: "Departure day", selection: $lastDate)
    }

    private func dateField(_ title: LocalizedStringKey, accessibilityTitle: LocalizedStringKey, selection: Binding<Date>) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(title)
                .font(TE.mono(.caption2))
                .tracking(1.2)
                .foregroundStyle(TE.textMuted)
                .accessibilityHidden(true)
            DatePicker(accessibilityTitle, selection: selection, in: ...Date(), displayedComponents: .date)
                .labelsHidden()
                .datePickerStyle(.compact)
                .padding(.horizontal, 8)
                .frame(maxWidth: .infinity, minHeight: 48, alignment: .leading)
                .background(RoundedRectangle(cornerRadius: 12).fill(TE.card))
                .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(TE.border, lineWidth: 1))
                .accessibilityLabel(Text(accessibilityTitle))
        }
        .frame(maxWidth: .infinity)
    }

    private var impactTitle: String {
        let days = CountryDayCalculator.inclusiveDays(from: firstDay, through: lastDay)
        guard !days.isEmpty else { return String(localized: "No days selected") }
        if stay != nil {
            return days.count == 1 ? String(localized: "Covers 1 day in \(countryName)")
                : String(localized: "Covers \(days.count) days in \(countryName)")
        }
        let counted = countedDays[countryCode] ?? []
        let added = days.filter { !counted.contains($0) }.count
        if added == 0 { return String(localized: "Already counted in \(countryName)") }
        return added == 1 ? String(localized: "Adds 1 day to \(countryName)")
            : String(localized: "Adds \(added) days to \(countryName)")
    }

    private func pickerDate(_ day: String) -> Date? {
        guard let date = CountryDayCalculator.date(for: day) else { return nil }
        var utc = Calendar(identifier: .gregorian)
        utc.timeZone = TimeZone(secondsFromGMT: 0)!
        var components = utc.dateComponents([.year, .month, .day], from: date)
        components.hour = 12
        var local = Calendar(identifier: .gregorian)
        local.timeZone = .current
        return local.date(from: components)
    }

    private func save() {
        guard canSave else { return }
        if let stay {
            stay.countryCode = countryCode
            stay.firstDay = firstDay
            stay.lastDay = lastDay
        } else {
            modelContext.insert(CountryStay(countryCode: countryCode, firstDay: firstDay, lastDay: lastDay))
        }
        do { try modelContext.save(); dismiss() } catch { errorMessage = error.localizedDescription }
    }
}

private struct CountrySelectionView: View {
    @Binding var countryCode: String
    @Environment(\.dismiss) private var dismiss
    @State private var search = ""

    private var countries: [(code: String, name: String)] {
        Locale.Region.isoRegions.filter { $0.identifier.count == 2 }.map {
            (code: $0.identifier, name: Locale.current.localizedString(forRegionCode: $0.identifier) ?? $0.identifier)
        }.filter { search.isEmpty || $0.name.localizedStandardContains(search) || $0.code.localizedStandardContains(search) }
        .sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }

    var body: some View {
        List(countries, id: \.code) { item in
            Button {
                countryCode = item.code
                dismiss()
            } label: {
                HStack {
                    Text(item.name).foregroundStyle(.primary)
                    Spacer()
                    if countryCode == item.code { Image(systemName: "checkmark") }
                }
            }
        }
        .navigationTitle("Country")
        .navigationBarTitleDisplayMode(.inline)
        .searchable(text: $search, prompt: "Search countries")
        .overlay {
            if countries.isEmpty { ContentUnavailableView.search(text: search) }
        }
    }
}

struct CountryDaysCSVDocument: FileDocument {
    static var readableContentTypes: [UTType] { [.commaSeparatedText] }
    var text: String
    init(text: String) { self.text = text }
    init(configuration: ReadConfiguration) throws {
        text = String(decoding: configuration.file.regularFileContents ?? Data(), as: UTF8.self)
    }
    func fileWrapper(configuration: WriteConfiguration) throws -> FileWrapper {
        FileWrapper(regularFileWithContents: Data(text.utf8))
    }
}
