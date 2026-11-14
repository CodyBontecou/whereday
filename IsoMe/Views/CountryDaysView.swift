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
        Group {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Picker("Date range", selection: $period) {
                        ForEach(CountryDayPeriod.allCases) { item in Text(item.title).tag(item) }
                    }
                    .pickerStyle(.segmented)

                    overview
                    analysis
                    countryList
                    manualStays
                    Text("A day counts once per country, using the recorded location’s local date. A travel day can count in more than one country. Open visits and unverified departure times count only the arrival day; route points add further recorded days. Gaps are not filled automatically.")
                        .font(.footnote)
                        .foregroundStyle(TE.textMuted)
                        .fixedSize(horizontal: false, vertical: true)
                }
                .padding(16)
                .padding(.bottom, 24)
            }
            .background(FieldNotesBackground())
            .navigationTitle("")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbar {
                ToolbarItemGroup(placement: .topBarTrailing) {
                    Button("Export", systemImage: "square.and.arrow.up") { export() }
                        .disabled(summaries.isEmpty)
                    Button("Add stay", systemImage: "plus") { showingAddStay = true }
                }
            }
            .sheet(isPresented: $showingAddStay) { CountryStayEditor() }
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
        }
    }

    private var overview: some View {
        TECard(paperOpacity: 0.94) {
            VStack(alignment: .leading, spacing: 8) {
                Text(summaries.count == 1 ? String(localized: "1 country") : String(localized: "\(summaries.count) countries"))
                    .font(TE.editorial(.title2))
                    .foregroundStyle(TE.textPrimary)
                    .monospacedDigit()
                let uniqueDays = Set(summaries.flatMap { $0.days.map(\.day) }).count
                Text(uniqueDays == 1 ? String(localized: "1 day with recorded or manual presence") : String(localized: "\(uniqueDays) days with recorded or manual presence"))
                    .font(.subheadline)
                    .foregroundStyle(TE.textMuted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(16)
        }
    }

    private var analysis: some View {
        TECard(paperOpacity: 0.94) {
            VStack(alignment: .leading, spacing: 12) {
                Label("Identify countries", systemImage: "location.magnifyingglass")
                    .font(.headline)
                    .foregroundStyle(TE.textPrimary)
                Text("New recordings identify countries automatically when network geocoding is enabled. Analyze saved locations to include your earlier history. Large histories can take time; you can stop and resume.")
                    .font(.footnote)
                    .foregroundStyle(TE.textMuted)
                if resolver.isRunning {
                    HStack {
                        ProgressView()
                        Text("\(resolver.completed) locations analyzed")
                            .font(.footnote).monospacedDigit()
                        Spacer()
                        Button("Stop") { resolver.cancel() }
                    }
                } else {
                    Button("Analyze saved locations") {
                        Task { await resolver.resolveHistory(in: modelContext) }
                    }
                    .buttonStyle(.bordered)
                }
                if let message = resolver.message {
                    Text(message).font(.footnote).foregroundStyle(TE.textMuted)
                }
            }
            .padding(16)
        }
    }

    private var countryList: some View {
        let countrySummaries = summaries
        return VStack(alignment: .leading, spacing: 8) {
            Text("COUNTRIES").font(TE.mono(.caption, weight: .semibold)).foregroundStyle(TE.textMuted)
            if countrySummaries.isEmpty {
                TECard(paperOpacity: 0.94) {
                    ContentUnavailableView("No country days yet", systemImage: "globe.europe.africa",
                                           description: Text("Analyze saved locations or add a manual stay. Try All time to see older records."))
                }
            } else {
                TECard(paperOpacity: 0.94) {
                    LazyVStack(spacing: 0) {
                        ForEach(countrySummaries) { summary in
                            NavigationLink {
                                CountryDayDetailView(summary: summary)
                            } label: {
                                HStack(spacing: 12) {
                                    Text(summary.countryCode)
                                        .font(TE.mono(.caption, weight: .bold))
                                        .foregroundStyle(TE.accent)
                                        .fixedSize(horizontal: true, vertical: false)
                                        .frame(minWidth: 36)
                                    if dynamicTypeSize.isAccessibilitySize {
                                        VStack(alignment: .leading, spacing: 4) {
                                            Text(summary.name).foregroundStyle(TE.textPrimary)
                                            Text(summary.days.count == 1 ? String(localized: "1 day") : String(localized: "\(summary.days.count) days"))
                                                .monospacedDigit().foregroundStyle(TE.textMuted)
                                        }
                                        .frame(maxWidth: .infinity, alignment: .leading)
                                    } else {
                                        Text(summary.name)
                                            .foregroundStyle(TE.textPrimary)
                                            .frame(maxWidth: .infinity, alignment: .leading)
                                        Text(summary.days.count == 1 ? String(localized: "1 day") : String(localized: "\(summary.days.count) days"))
                                            .monospacedDigit().foregroundStyle(TE.textMuted)
                                    }
                                    Image(systemName: "chevron.right").font(.caption).foregroundStyle(TE.textMuted)
                                }
                                .padding(16)
                                .contentShape(Rectangle())
                            }
                            .buttonStyle(.plain)
                            if summary.id != countrySummaries.last?.id { Divider() }
                        }
                    }
                }
            }
        }
    }

    private var manualStays: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack {
                Text("MANUAL STAYS").font(TE.mono(.caption, weight: .semibold)).foregroundStyle(TE.textMuted)
                Spacer()
                Button("Add stay") { showingAddStay = true }.frame(minHeight: 44)
            }
            Text("Fill missing days. Overlapping stays and recorded days are counted only once.")
                .font(.footnote).foregroundStyle(TE.textMuted)
            if !stays.isEmpty {
                TECard(paperOpacity: 0.94) {
                    LazyVStack(spacing: 0) {
                        ForEach(stays) { stay in
                            HStack {
                                Button {
                                    editingStay = stay
                                } label: {
                                    VStack(alignment: .leading, spacing: 4) {
                                        Text(Locale.current.localizedString(forRegionCode: stay.countryCode) ?? stay.countryCode)
                                            .foregroundStyle(TE.textPrimary)
                                        Text("\(stay.firstDay) – \(stay.lastDay)")
                                            .font(.caption).foregroundStyle(TE.textMuted)
                                    }
                                    .frame(maxWidth: .infinity, alignment: .leading)
                                    .padding(.vertical, 12)
                                }
                                .buttonStyle(.plain)
                                Button("Delete stay", systemImage: "trash", role: .destructive) { stayToDelete = stay }
                                    .labelStyle(.iconOnly).frame(width: 44, height: 44)
                            }
                            .padding(.horizontal, 16)
                            if stay.id != stays.last?.id { Divider() }
                        }
                    }
                }
            }
        }
    }

    private func export() {
        guard storeManager.canExport else { showingPaywall = true; return }
        exportDocument = CountryDaysCSVDocument(text: CountryDayCalculator.csv(summaries))
        showingExport = true
    }
}

private struct CountryDayDetailView: View {
    let summary: CountryDaySummary
    var body: some View {
        List {
            Section {
                LabeledContent("Country", value: summary.name)
                LabeledContent("Days present", value: summary.days.count.formatted())
            }
            .listRowBackground(TE.card.opacity(0.94))
            Section("Local dates") {
                ForEach(summary.days, id: \.day) { presence in
                    LabeledContent(presence.day) {
                        Text(presence.recorded && presence.manual ? "Recorded + manual" : presence.manual ? "Manual" : "Recorded")
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .listRowBackground(TE.card.opacity(0.94))
        }
        .scrollContentBackground(.hidden)
        .background(FieldNotesBackground())
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
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

private struct CountryStayEditor: View {
    @Environment(\.modelContext) private var modelContext
    @Environment(\.dismiss) private var dismiss
    var stay: CountryStay?
    @State private var countryCode = Locale.current.region?.identifier ?? "PT"
    @State private var firstDate = Date()
    @State private var lastDate = Date()
    @State private var errorMessage: String?
    @State private var hasInitialized = false

    private var countries: [(code: String, name: String)] {
        Locale.Region.isoRegions.filter { $0.identifier.count == 2 }.map {
            (code: $0.identifier, name: Locale.current.localizedString(forRegionCode: $0.identifier) ?? $0.identifier)
        }.sorted { $0.name.localizedStandardCompare($1.name) == .orderedAscending }
    }
    private var firstDay: String { CountryDayCalculator.dayIdentifier(firstDate, timeZone: .current) }
    private var lastDay: String { CountryDayCalculator.dayIdentifier(lastDate, timeZone: .current) }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    NavigationLink {
                        CountrySelectionView(countryCode: $countryCode)
                    } label: {
                        LabeledContent("Country", value: Locale.current.localizedString(forRegionCode: countryCode) ?? countryCode)
                    }
                    DatePicker("Arrival day", selection: $firstDate, in: ...Date(), displayedComponents: .date)
                    DatePicker("Departure day", selection: $lastDate, in: ...Date(), displayedComponents: .date)
                } footer: {
                    Text("Both dates count, including a same-day visit. Recorded presence in other countries is kept.")
                }
                .listRowBackground(TE.card.opacity(0.97))
                if firstDay > lastDay {
                    Text("Departure must be on or after arrival.").foregroundStyle(.red)
                }
                if let errorMessage { Text(errorMessage).foregroundStyle(.red) }
            }
            .scrollContentBackground(.hidden)
            .background {
                FieldNotesBackground()
                    .overlay(TE.surface.opacity(0.45))
                    .allowsHitTesting(false)
            }
            .navigationTitle(stay == nil ? "Add Country Stay" : "Edit Country Stay")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .disabled(firstDay > lastDay || !countries.contains(where: { $0.code == countryCode }))
                }
            }
            .onAppear {
                guard !hasInitialized else { return }
                hasInitialized = true
                guard let stay else { return }
                countryCode = stay.countryCode
                // Convert civil dates to noon in the device's current zone for the date picker.
                firstDate = pickerDate(stay.firstDay) ?? Date()
                lastDate = pickerDate(stay.lastDay) ?? Date()
            }
        }
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
