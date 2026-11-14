import SwiftUI
import UniformTypeIdentifiers

struct SavedLocationsView: View {
    @Bindable var viewModel: LocationViewModel
    @State private var searchText = ""
    @State private var sheet: SavedLocationSheet?
    @State private var showingImportPicker = false
    @State private var isReadingFile = false
    @State private var pendingDeletion: SavedPlace?
    @State private var errorMessage: String?

    private var filteredPlaces: [SavedPlace] {
        let query = searchText.trimmingCharacters(in: .whitespacesAndNewlines)
        return viewModel.savedPlaces.filter {
            query.isEmpty || $0.name.localizedCaseInsensitiveContains(query)
                || ($0.address?.localizedCaseInsensitiveContains(query) ?? false)
        }
    }

    var body: some View {
        List {
            Section {
                if viewModel.savedPlaces.isEmpty {
                    ContentUnavailableView {
                        Label("No Saved Locations", systemImage: "mappin.and.ellipse")
                    } description: {
                        Text("Add places you visit to reuse their names, including places missing from Maps.")
                    } actions: {
                        Button("Add Location") { sheet = .editor(SavedPlaceDraft()) }
                            .buttonStyle(.borderedProminent)
                            .disabled(isReadingFile)
                    }
                } else if filteredPlaces.isEmpty {
                    ContentUnavailableView.search(text: searchText)
                } else {
                    ForEach(filteredPlaces) { place in
                        Button {
                            sheet = .editor(SavedPlaceDraft(place))
                        } label: {
                            HStack(spacing: 12) {
                                Image(systemName: "mappin.and.ellipse")
                                    .foregroundStyle(TE.accent)
                                    .accessibilityHidden(true)
                                VStack(alignment: .leading, spacing: 4) {
                                    Text(place.name)
                                        .font(TE.editorial(.headline))
                                        .foregroundStyle(TE.textPrimary)
                                    if let address = place.address, !address.isEmpty {
                                        Text(address).font(.subheadline).foregroundStyle(.secondary)
                                    }
                                }
                                Spacer(minLength: 8)
                                Image(systemName: "chevron.right")
                                    .font(.caption).foregroundStyle(.tertiary)
                                    .accessibilityHidden(true)
                            }
                            .frame(minHeight: 44)
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)
                        .disabled(isReadingFile)
                        .accessibilityHint("Edits this saved location.")
                        .swipeActions {
                            Button("Delete", role: .destructive) { pendingDeletion = place }
                                .tint(.red)
                        }
                    }
                }
            } footer: {
                Text("Saved locations name nearby future visits. Editing or deleting one keeps your past visits unchanged.")
            }
            .listRowBackground(TE.card.opacity(0.94))

            Section {
                Button {
                    showingImportPicker = true
                } label: {
                    HStack(spacing: 12) {
                        Label("Import Locations from CSV", systemImage: "square.and.arrow.down")
                        if isReadingFile { Spacer(); ProgressView() }
                    }
                    .frame(minHeight: 44)
                }
                .disabled(isReadingFile)
            } footer: {
                Text("Use name, latitude, longitude, address, and radius_meters columns. Address and radius are optional; the default radius is 150 meters. You can review the locations before importing.")
            }
            .listRowBackground(TE.card.opacity(0.94))
        }
        .listStyle(.insetGrouped)
        .scrollContentBackground(.hidden)
        .background(FieldNotesBackground())
        .navigationTitle("")
        .navigationBarTitleDisplayMode(.inline)
        .toolbarBackground(.hidden, for: .navigationBar)
        .searchable(text: $searchText, prompt: "Search saved locations")
        .toolbar {
            ToolbarItem(placement: .primaryAction) {
                Button { sheet = .editor(SavedPlaceDraft()) } label: {
                    Image(systemName: "plus")
                }
                .accessibilityLabel("Add Location")
                .disabled(isReadingFile)
            }
        }
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
        .onAppear { viewModel.loadSavedPlaces() }
        .tint(TE.accent)
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
