import CoreLocation
import SwiftUI
import UniformTypeIdentifiers

/// The Places library. Hosted in a NavigationStack by its caller (the Places tab or Settings).
struct SavedLocationsView: View {
    @Bindable var viewModel: LocationViewModel
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var searchText = ""
    @State private var filter: PlacesFilter = .mostVisited
    @State private var selectedPlaceID: UUID?
    /// Two-pane selection of a visited spot that isn't saved yet.
    @State private var selectedSuggestionID: String?
    @State private var pushedSuggestion: UnsavedPlaceSuggestion?
    @State private var sheet: SavedLocationSheet?
    @State private var showingImportPicker = false
    @State private var isReadingFile = false
    @State private var pendingDeletion: SavedPlace?
    @State private var errorMessage: String?
    @FocusState private var searchFocused: Bool

    private var isTwoPane: Bool { horizontalSizeClass == .regular }

    /// The place the two-pane detail shows: the one picked, or else the first in the list.
    private func effectiveSelectedPlaceID(_ library: PlacesLibrary) -> UUID? {
        guard selectedSuggestionID == nil else { return nil }
        if let selectedPlaceID, library.places.contains(where: { $0.id == selectedPlaceID }) {
            return selectedPlaceID
        }
        let listFilter = filter == .unsaved ? PlacesFilter.mostVisited : filter
        return library.sorted(filter: listFilter, query: searchText).first?.id
            ?? library.sorted(filter: .mostVisited, query: "").first?.id
    }

    var body: some View {
        let library = PlacesLibrary(places: viewModel.savedPlaces, visits: viewModel.allVisits)
        Group {
            if isTwoPane {
                HStack(spacing: 0) {
                    listPane(library)
                        .frame(maxWidth: 420)
                    Rectangle().fill(TE.border).frame(width: 1).ignoresSafeArea()
                    detailPane(library)
                        .frame(maxWidth: .infinity)
                }
            } else {
                listPane(library)
            }
        }
        .background(TE.surface.ignoresSafeArea())
        .foregroundStyle(TE.textPrimary)
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
        .sheet(item: $sheet) { item in
            switch item {
            case .editor(let draft):
                SavedPlaceEditorView(viewModel: viewModel, draft: draft)
            case .importPreview(let preview):
                SavedLocationsImportView(viewModel: viewModel, preview: preview)
            }
        }
        .fileImporter(isPresented: $showingImportPicker, allowedContentTypes: [.commaSeparatedText], allowsMultipleSelection: false) { result in
            switch result {
            case .success(let urls):
                guard let url = urls.first else { return }
                isReadingFile = true
                Task {
                    defer { isReadingFile = false }
                    do {
                        let preview = try await Task.detached(priority: .userInitiated) {
                            let accessing = url.startAccessingSecurityScopedResource()
                            defer { if accessing { url.stopAccessingSecurityScopedResource() } }
                            let size = try url.resourceValues(forKeys: [.fileSizeKey]).fileSize ?? 0
                            guard size <= SavedPlaceImportService.maximumFileSize else {
                                throw SavedPlaceManagementError.invalid(String(localized: "Choose a CSV file smaller than 5 MB."))
                            }
                            return try SavedPlaceImportService.parse(data: Data(contentsOf: url))
                        }.value
                        sheet = .importPreview(preview)
                    } catch { errorMessage = error.localizedDescription }
                }
            case .failure(let error): errorMessage = error.localizedDescription
            }
        }
        .confirmationDialog("Delete Saved Location?", isPresented: Binding(get: { pendingDeletion != nil }, set: { if !$0 { pendingDeletion = nil } }), titleVisibility: .visible) {
            if let place = pendingDeletion {
                Button("Delete", role: .destructive) {
                    if selectedPlaceID == place.id { selectedPlaceID = nil }
                    do { try viewModel.deleteManagedPlace(place) }
                    catch { errorMessage = error.localizedDescription }
                    pendingDeletion = nil
                }
            }
            Button("Cancel", role: .cancel) { pendingDeletion = nil }
        } message: {
            Text("This removes the reusable location. Your past visits are kept.")
        }
        .alert("Saved Locations", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
            Button("OK", role: .cancel) { errorMessage = nil }
        } message: { Text(errorMessage ?? "") }
        .navigationDestination(isPresented: Binding(get: { pushedSuggestion != nil }, set: { if !$0 { pushedSuggestion = nil } })) {
            if let pushedSuggestion {
                PlaceDetailView(suggestion: pushedSuggestion, viewModel: viewModel)
            }
        }
        .onAppear { viewModel.loadSavedPlaces() }
        .tint(TE.accent)
    }

    // MARK: - List

    private func listPane(_ library: PlacesLibrary) -> some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 0) {
                header(library)
                searchField
                    .padding(.horizontal, 16)
                    .padding(.top, 14)
                filterChips(library)
                    .padding(.top, 12)
                placesCard(library)
                    .padding(.horizontal, 16)
                    .padding(.top, 12)
                actions(library)
                    .padding(.horizontal, 16)
                    .padding(.top, 12)
                footnotes
                    .padding(.horizontal, 24)
                    .padding(.top, 14)
            }
            .padding(.bottom, 24)
        }
        .scrollIndicators(.hidden)
        .scrollDismissesKeyboard(.immediately)
    }

    private func header(_ library: PlacesLibrary) -> some View {
        HStack(alignment: .bottom, spacing: 12) {
            VStack(alignment: .leading, spacing: 2) {
                Text("\(viewModel.savedPlaces.count) SAVED · \(library.visitedPlaceCount) VISITED")
                    .font(TE.mono(.caption2))
                    .tracking(1.5)
                    .foregroundStyle(TE.textMuted)
                Text("Places")
                    .font(TE.editorial(.largeTitle))
                    .accessibilityAddTraits(.isHeader)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Button { sheet = .editor(SavedPlaceDraft()) } label: {
                Image(systemName: "plus")
                    .font(.system(size: 18, weight: .medium))
                    .foregroundStyle(TE.surface)
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(TE.accent))
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Add Location")
            .disabled(isReadingFile)
        }
        .padding(.horizontal, 24)
        .padding(.top, 12)
    }

    private var searchField: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(TE.textMuted)
                .accessibilityHidden(true)
            TextField("Search saved locations", text: $searchText, prompt: Text("Find a place"))
                .focused($searchFocused)
                .submitLabel(.search)
                .autocorrectionDisabled()
            if !searchText.isEmpty {
                Button { searchText = "" } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(TE.textMuted)
                        .frame(width: 44, height: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear search")
            }
        }
        .padding(.leading, 14)
        .frame(minHeight: 44)
        .background(RoundedRectangle(cornerRadius: 14).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(TE.border, lineWidth: 1))
        .contentShape(RoundedRectangle(cornerRadius: 14))
        .onTapGesture { searchFocused = true }
    }

    private func filterChips(_ library: PlacesLibrary) -> some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                chip(.mostVisited, title: "Most visited")
                chip(.recent, title: "Recent")
                if !library.suggestions.isEmpty {
                    chip(.unsaved, title: "Unnamed · \(library.suggestions.count)", dot: TE.inkRust)
                }
                chip(.alphabetical, title: "A–Z")
            }
            .padding(.horizontal, 16)
        }
        .scrollIndicators(.hidden)
    }

    private func chip(_ value: PlacesFilter, title: LocalizedStringKey, dot: Color? = nil) -> some View {
        let isOn = filter == value
        return Button { filter = value } label: {
            HStack(spacing: 6) {
                if let dot {
                    Circle().fill(dot).frame(width: 8, height: 8).accessibilityHidden(true)
                }
                Text(title)
                    .font(.footnote.weight(.medium))
                    .lineLimit(1)
            }
            .foregroundStyle(isOn ? TE.surface : TE.textPrimary)
            .padding(.horizontal, 14)
            .frame(minHeight: 36)
            .background(Capsule().fill(isOn ? TE.accent : TE.card))
            .overlay { if !isOn { Capsule().strokeBorder(TE.border, lineWidth: 1) } }
            .frame(minHeight: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isOn ? .isSelected : [])
    }

    @ViewBuilder
    private func placesCard(_ library: PlacesLibrary) -> some View {
        if filter == .unsaved {
            suggestionsCard(library)
        } else if viewModel.savedPlaces.isEmpty {
            placesCardFrame {
                ContentUnavailableView {
                    Label("No Saved Locations", systemImage: "mappin.and.ellipse")
                } description: {
                    Text("Add places you visit to reuse their names, including places missing from Maps.")
                } actions: {
                    Button("Add Location") { sheet = .editor(SavedPlaceDraft()) }
                        .buttonStyle(PlacesInkButtonStyle(emphasis: .primary))
                        .disabled(isReadingFile)
                }
            }
        } else {
            let places = library.sorted(filter: filter, query: searchText)
            if places.isEmpty {
                placesCardFrame { ContentUnavailableView.search(text: searchText) }
            } else {
                placesCardFrame {
                    LazyVStack(spacing: 0) {
                        ForEach(places, id: \.id) { place in
                            placeLink(place, library: library)
                            if place.id != places.last?.id {
                                Rectangle().fill(TE.dialTrack).frame(height: 1)
                            }
                        }
                    }
                }
            }
        }
    }

    private func placesCardFrame<Content: View>(@ViewBuilder _ content: () -> Content) -> some View {
        content()
            .padding(.horizontal, 16)
            .padding(.vertical, 4)
            .frame(maxWidth: .infinity)
            .background(RoundedRectangle(cornerRadius: 16).fill(TE.card))
            .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(TE.border, lineWidth: 1))
    }

    @ViewBuilder
    private func placeLink(_ place: SavedPlace, library: PlacesLibrary) -> some View {
        let row = placeRow(place, library: library)
        Group {
            if isTwoPane {
                Button {
                    selectedPlaceID = place.id
                    selectedSuggestionID = nil
                } label: { row }
                    .accessibilityAddTraits(effectiveSelectedPlaceID(library) == place.id ? .isSelected : [])
            } else {
                NavigationLink { PlaceDetailView(place: place, viewModel: viewModel) } label: { row }
            }
        }
        .buttonStyle(.plain)
        .disabled(isReadingFile)
        .accessibilityHint("Shows this place's visits and rhythm.")
        .contextMenu {
            Button("Edit", systemImage: "pencil") { sheet = .editor(SavedPlaceDraft(place)) }
            Button("Delete", systemImage: "trash", role: .destructive) { pendingDeletion = place }
        }
        .accessibilityAction(named: "Edit") { sheet = .editor(SavedPlaceDraft(place)) }
        .accessibilityAction(named: "Delete") { pendingDeletion = place }
    }

    private func placeRow(_ place: SavedPlace, library: PlacesLibrary) -> some View {
        let visits = library.index.visits(for: place)
        let selected = isTwoPane && effectiveSelectedPlaceID(library) == place.id
        let isHome = place.kind == .home
        return HStack(spacing: 12) {
            Circle()
                .fill(PlaceVisitIndex.ink(for: place, in: viewModel.savedPlaces))
                .frame(width: 12, height: 12)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 1) {
                Text(place.name)
                    .font(TE.editorial(.title3))
                    .multilineTextAlignment(.leading)
                Text(isHome && !(place.address ?? "").isEmpty ? place.address ?? "" : subtitle(place, lastVisit: visits.first))
                    .font(TE.mono(.caption2))
                    .foregroundStyle(TE.textMuted)
                    .lineLimit(dynamicTypeSize.isAccessibilitySize ? 3 : 1)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Text(isHome ? Self.dayCountLabel(visits) : visits.count == 1 ? String(localized: "1 visit") : String(localized: "\(visits.count) visits"))
                .font(TE.mono(.caption))
                .foregroundStyle(selected ? TE.inkRust : TE.textMuted)
        }
        .frame(minHeight: 58)
        .padding(.horizontal, isTwoPane ? 10 : 0)
        .background {
            // Two-pane: the picked row is drawn like Today's selected stay.
            if isTwoPane {
                RoundedRectangle(cornerRadius: 14)
                    .fill(selected ? TE.surface : Color.clear)
                    .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(selected ? TE.inkRust : Color.clear, lineWidth: 1.5))
            }
        }
        .padding(.horizontal, isTwoPane ? -10 : 0)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private func subtitle(_ place: SavedPlace, lastVisit: Visit?) -> String {
        if let lastVisit {
            if viewModel.isCurrentVisit(lastVisit) { return String(localized: "here now") }
            return String(localized: "last \(Self.relativeDay(lastVisit.arrivedAt))")
        }
        if let address = place.address, !address.isEmpty { return address }
        return String(localized: "No visits yet")
    }

    /// Home counts the distinct days you were there, not individual visits.
    private static func dayCountLabel(_ visits: [Visit]) -> String {
        let calendar = Calendar.current
        let days = Set(visits.map { calendar.startOfDay(for: $0.arrivedAt) }).count
        return days == 1 ? String(localized: "1 day") : String(localized: "\(days) days")
    }

    private static func relativeDay(_ date: Date, now: Date = Date()) -> String {
        let calendar = Calendar.current
        if calendar.isDateInToday(date) { return String(localized: "today") }
        if calendar.isDateInYesterday(date) { return String(localized: "yesterday") }
        let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: date), to: calendar.startOfDay(for: now)).day ?? 0
        if days < 7 { return date.formatted(.dateTime.weekday(.abbreviated)) }
        if calendar.isDate(date, equalTo: now, toGranularity: .year) {
            return date.formatted(.dateTime.month(.abbreviated).day())
        }
        return date.formatted(.dateTime.month(.abbreviated).day().year())
    }

    private func suggestionsCard(_ library: PlacesLibrary) -> some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Places you return to that aren't saved yet. Save one to name its future visits.")
                .font(.footnote)
                .foregroundStyle(TE.textMuted)
                .fixedSize(horizontal: false, vertical: true)
                .padding(.horizontal, 8)
            let suggestions = library.suggestions.filter {
                searchText.isEmpty || $0.name.localizedCaseInsensitiveContains(searchText)
            }
            placesCardFrame {
                if suggestions.isEmpty {
                    ContentUnavailableView.search(text: searchText)
                } else {
                    LazyVStack(spacing: 0) {
                        ForEach(suggestions) { suggestion in
                            suggestionLink(suggestion)
                            if suggestion.id != suggestions.last?.id {
                                Rectangle().fill(TE.dialTrack).frame(height: 1)
                            }
                        }
                    }
                }
            }
        }
    }

    @ViewBuilder
    private func suggestionLink(_ suggestion: UnsavedPlaceSuggestion) -> some View {
        let row = suggestionRow(suggestion)
        Group {
            if isTwoPane {
                Button { selectedSuggestionID = suggestion.id } label: { row }
                    .accessibilityAddTraits(selectedSuggestionID == suggestion.id ? .isSelected : [])
            } else {
                // Pushed by state rather than a row link, so saving the spot (which removes its row) keeps the page open.
                Button { pushedSuggestion = suggestion } label: {
                    HStack(spacing: 8) {
                        row
                        Image(systemName: "chevron.right")
                            .font(.caption.weight(.semibold))
                            .foregroundStyle(TE.textMuted)
                            .accessibilityHidden(true)
                    }
                }
            }
        }
        .buttonStyle(.plain)
        .disabled(isReadingFile)
        .accessibilityHint("Shows this spot's visits and rhythm, with an option to save it.")
        .contextMenu {
            Button("Save", systemImage: "plus") { sheet = .editor(suggestion.draft) }
        }
        .accessibilityAction(named: "Save") { sheet = .editor(suggestion.draft) }
    }

    private func suggestionRow(_ suggestion: UnsavedPlaceSuggestion) -> some View {
        HStack(spacing: 12) {
            Circle()
                .strokeBorder(TE.inkRust, lineWidth: 1.5)
                .frame(width: 12, height: 12)
                .accessibilityHidden(true)
            VStack(alignment: .leading, spacing: 1) {
                Text(suggestion.name)
                    .font(TE.editorial(.title3))
                    .multilineTextAlignment(.leading)
                Text("\(suggestion.visitCount) visits · last \(Self.relativeDay(suggestion.lastVisit))")
                    .font(TE.mono(.caption2))
                    .foregroundStyle(TE.textMuted)
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            Text("UNSAVED")
                .font(TE.mono(.caption2, weight: .semibold))
                .foregroundStyle(TE.inkRust)
        }
        .frame(minHeight: 58)
        .contentShape(Rectangle())
        .accessibilityElement(children: .combine)
    }

    private func actions(_ library: PlacesLibrary) -> some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(spacing: 8))
            : AnyLayout(HStackLayout(spacing: 8))
        return layout {
            Button {
                showingImportPicker = true
            } label: {
                HStack(spacing: 8) {
                    Text("Import CSV")
                    if isReadingFile { ProgressView() }
                }
            }
            .buttonStyle(PlacesInkButtonStyle())
            .disabled(isReadingFile)
            .accessibilityLabel("Import Locations from CSV")

            if !library.suggestions.isEmpty && filter != .unsaved {
                Button("Name \(library.suggestions.count) places") { filter = .unsaved }
                    .buttonStyle(PlacesInkButtonStyle())
                    .accessibilityHint("Shows frequent places that aren't saved yet.")
            }
        }
    }

    private var footnotes: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Saved locations name nearby future visits. Editing or deleting one keeps your past visits unchanged.")
            Text("Use name, latitude, longitude, address, and radius_meters columns. Address and radius are optional; the default radius is 150 meters. You can review the locations before importing.")
        }
        .font(TE.mono(.caption2))
        .foregroundStyle(TE.textMuted)
        .fixedSize(horizontal: false, vertical: true)
    }

    // MARK: - Detail pane

    @ViewBuilder
    private func detailPane(_ library: PlacesLibrary) -> some View {
        let shownID = effectiveSelectedPlaceID(library)
        if let suggestionID = selectedSuggestionID,
           let suggestion = library.suggestions.first(where: { $0.id == suggestionID }) {
            PlaceDetailView(suggestion: suggestion, viewModel: viewModel, isEmbedded: true)
                .id(suggestion.id)
        } else if let place = viewModel.savedPlaces.first(where: { $0.id == shownID }) {
            PlaceDetailView(place: place, viewModel: viewModel, isEmbedded: true)
                .id(place.id)
        } else {
            ContentUnavailableView("Choose a Place", systemImage: "mappin.and.ellipse",
                                   description: Text("Its visits, typical stay, and weekly rhythm appear here."))
                .foregroundStyle(TE.textMuted)
        }
    }
}

private enum PlacesFilter: Hashable {
    case mostVisited, recent, alphabetical, unsaved
}

/// Saved places with their matched visits, plus frequent spots that aren't saved yet.
private struct PlacesLibrary {
    let places: [SavedPlace]
    let index: PlaceVisitIndex
    let suggestions: [UnsavedPlaceSuggestion]

    init(places: [SavedPlace], visits: [Visit]) {
        self.places = places
        let index = PlaceVisitIndex(places: places, visits: visits)
        self.index = index
        suggestions = UnsavedPlaceSuggestion.suggestions(from: index.unmatched)
        let visitedSaved = places.reduce(0) { $0 + (index.visits(for: $1).isEmpty ? 0 : 1) }
        visitedPlaceCount = visitedSaved + UnsavedPlaceSuggestion.distinctSpotCount(in: index.unmatched)
    }

    /// Saved places with at least one visit, plus every distinct unsaved spot you've stopped at.
    let visitedPlaceCount: Int

    func sorted(filter: PlacesFilter, query: String) -> [SavedPlace] {
        let query = query.trimmingCharacters(in: .whitespacesAndNewlines)
        let matching = places.filter {
            query.isEmpty || $0.name.localizedCaseInsensitiveContains(query)
                || ($0.address?.localizedCaseInsensitiveContains(query) ?? false)
        }
        let ordered: [SavedPlace]
        switch filter {
        case .alphabetical, .unsaved:
            ordered = matching
        case .mostVisited:
            ordered = matching.sorted { index.visits(for: $0).count > index.visits(for: $1).count }
        case .recent:
            ordered = matching.sorted {
                (index.visits(for: $0).first?.arrivedAt ?? .distantPast) > (index.visits(for: $1).first?.arrivedAt ?? .distantPast)
            }
        }
        // Home is pinned to the top, whatever the order.
        return ordered.filter { $0.kind == .home } + ordered.filter { $0.kind != .home }
    }
}

/// A spot visited at least three times with no saved place around it.
struct UnsavedPlaceSuggestion: Identifiable {
    let id: String
    let name: String
    let address: String?
    let coordinate: CLLocationCoordinate2D
    let visitCount: Int
    let lastVisit: Date

    var draft: SavedPlaceDraft {
        SavedPlaceDraft(name: name, address: address ?? "", latitude: coordinate.latitude, longitude: coordinate.longitude)
    }

    /// The ~150 m grid cell a visit falls in; suggestions are keyed by it.
    static func gridKey(for visit: Visit) -> String? {
        guard visit.latitude.isFinite && visit.longitude.isFinite else { return nil }
        let step = 0.0014
        return "\(Int((visit.latitude / step).rounded())):\(Int((visit.longitude / step).rounded()))"
    }

    /// Distinct grid cells among visits that match no saved place.
    static func distinctSpotCount(in unmatched: [Visit]) -> Int {
        Set(unmatched.compactMap(gridKey(for:))).count
    }

    /// This spot's visits that still match no saved place, newest first.
    func visits(places: [SavedPlace], visits: [Visit]) -> [Visit] {
        PlaceVisitIndex(places: places, visits: visits).unmatched.filter { Self.gridKey(for: $0) == id }
    }

    /// Visits are grouped on a ~150 m grid; `visits` are expected newest first.
    static func suggestions(from visits: [Visit], minimumVisits: Int = 3, limit: Int = 12) -> [UnsavedPlaceSuggestion] {
        var groups: [String: [Visit]] = [:]
        for visit in visits {
            guard let key = gridKey(for: visit) else { continue }
            groups[key, default: []].append(visit)
        }
        return groups.compactMap { key, group -> UnsavedPlaceSuggestion? in
            guard group.count >= minimumVisits, let latest = group.first else { return nil }
            let names = Dictionary(grouping: group, by: \.displayName).mapValues(\.count)
            let name = names.max { $0.value == $1.value ? $0.key > $1.key : $0.value < $1.value }?.key ?? latest.displayName
            let latitude = group.map(\.latitude).reduce(0, +) / Double(group.count)
            let longitude = group.map(\.longitude).reduce(0, +) / Double(group.count)
            return UnsavedPlaceSuggestion(
                id: key, name: name, address: group.lazy.compactMap(\.address).first,
                coordinate: CLLocationCoordinate2D(latitude: latitude, longitude: longitude),
                visitCount: group.count, lastVisit: latest.arrivedAt
            )
        }
        .sorted { $0.visitCount == $1.visitCount ? $0.lastVisit > $1.lastVisit : $0.visitCount > $1.visitCount }
        .prefix(limit)
        .map { $0 }
    }
}

private enum SavedLocationSheet: Identifiable {
    case editor(SavedPlaceDraft)
    case importPreview(SavedPlaceImportPreview)

    var id: String {
        switch self {
        case .editor(let draft): "editor-\(draft.id)"
        case .importPreview: "import-preview"
        }
    }
}

private struct SavedLocationsImportView: View {
    @Environment(\.dismiss) private var dismiss
    @Bindable var viewModel: LocationViewModel
    let preview: SavedPlaceImportPreview
    @State private var duplicatePolicy = SavedPlaceDuplicatePolicy.keepExisting
    @State private var errorMessage: String?
    @State private var completedSummary: SavedPlaceImportSummary?

    private var plan: SavedPlaceImportPlan {
        SavedPlaceImportPlan(places: preview.places, existing: viewModel.savedPlaces.map(SavedPlaceDraft.init), policy: duplicatePolicy)
    }

    var body: some View {
        let currentPlan = plan
        NavigationStack {
            List {
                Section {
                    LabeledContent("New locations", value: currentPlan.summary.added.formatted())
                    LabeledContent("Updated locations", value: currentPlan.summary.updated.formatted())
                    LabeledContent("Skipped duplicates", value: currentPlan.summary.skipped.formatted())
                    LabeledContent("Invalid rows", value: preview.invalidRows.count.formatted())
                    Picker("Duplicate locations", selection: $duplicatePolicy) {
                        ForEach(SavedPlaceDuplicatePolicy.allCases) { policy in
                            Text(policy.title).tag(policy)
                        }
                    }
                } header: {
                    Text("Import Summary")
                } footer: {
                    Text("A duplicate has the same name near an existing location or an earlier row. Invalid rows are skipped. Updates change future matching and keep past visits unchanged.")
                }
                Section("Locations") {
                    let actions = Dictionary(uniqueKeysWithValues: currentPlan.entries.map { ($0.id, $0.action) })
                    ForEach(preview.rows) { row in
                        VStack(alignment: .leading, spacing: 5) {
                            Text(row.name.isEmpty ? String(localized: "Unnamed location") : row.name)
                                .font(.headline)
                            if let place = row.place {
                                if let address = place.address { Text(address).foregroundStyle(.secondary) }
                                Text("\(place.latitude.formatted()), \(place.longitude.formatted())")
                                    .font(.caption).foregroundStyle(.secondary).textSelection(.enabled)
                                switch actions[place.id] {
                                case .add: Label("New location", systemImage: "plus.circle").foregroundStyle(.secondary)
                                case .update: Label("Update matching location", systemImage: "arrow.triangle.2.circlepath").foregroundStyle(.secondary)
                                case .skip: Label("Keep existing location", systemImage: "equal.circle").foregroundStyle(.secondary)
                                case nil: EmptyView()
                                }
                            }
                            ForEach(row.errors, id: \.self) { error in
                                Label(error, systemImage: "exclamationmark.circle").foregroundStyle(.red)
                            }
                            Text("Row \(row.rowNumber)").font(.caption).foregroundStyle(.secondary)
                        }
                        .padding(.vertical, 4)
                    }
                }
            }
            .navigationTitle("Review Import")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button("Cancel") { dismiss() } }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Import \(currentPlan.summary.added + currentPlan.summary.updated)") {
                        do { completedSummary = try viewModel.importSavedPlaces(preview.places, duplicates: duplicatePolicy) }
                        catch { errorMessage = error.localizedDescription }
                    }
                    .accessibilityLabel("Import locations")
                    .accessibilityValue(Text("\(currentPlan.summary.added + currentPlan.summary.updated)"))
                    .disabled(currentPlan.summary.added + currentPlan.summary.updated == 0)
                }
            }
            .alert("Import Complete", isPresented: Binding(get: { completedSummary != nil }, set: { if !$0 { completedSummary = nil } })) {
                Button("Done") { dismiss() }
            } message: { Text(completedSummary?.message ?? "") }
            .alert("Import Failed", isPresented: Binding(get: { errorMessage != nil }, set: { if !$0 { errorMessage = nil } })) {
                Button("OK", role: .cancel) { errorMessage = nil }
            } message: { Text(errorMessage ?? "") }
            .tint(TE.accent)
        }
    }
}
