import SwiftUI
import MapKit
import SwiftData

struct VisitDetailView: View {
    @Bindable var visit: Visit
    @Bindable var viewModel: LocationViewModel
    @Environment(\.dismiss) private var dismiss
    @State private var showingDeleteConfirmation = false
    @State private var nameText: String = ""
    @State private var notesText: String = ""
    @State private var arrivedAt: Date = Date()
    @State private var departedAt: Date = Date()
    @State private var isStillHere = false
    @State private var timeValidationMessage: String?
    @FocusState private var focusedField: FocusedField?

    private enum FocusedField: Hashable {
        case name
        case notes
    }

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                // Map
                mapSection

                // Location Info
                locationInfoSection

                // Time Info
                timeInfoSection

                // Notes
                notesSection

                // Actions
                actionsSection
            }
            .padding()
        }
        .navigationTitle("Visit Details")
        .navigationBarTitleDisplayMode(.inline)
        .onAppear {
            syncEditableStateFromVisit()
        }
        .alert("Delete Visit?", isPresented: $showingDeleteConfirmation) {
            Button("Cancel", role: .cancel) {}
            Button("Delete", role: .destructive) {
                viewModel.deleteVisit(visit)
                dismiss()
            }
        } message: {
            Text("This visit will be permanently deleted.")
        }
        .toolbar {
            ToolbarItemGroup(placement: .keyboard) {
                Spacer()
                Button("Done") {
                    focusedField = nil
                    saveName()
                    saveNotes()
                }
            }
        }
    }

    // MARK: - Sections

    private var mapSection: some View {
        Map(initialPosition: .region(MKCoordinateRegion(
            center: visit.coordinate,
            span: MKCoordinateSpan(latitudeDelta: 0.005, longitudeDelta: 0.005)
        ))) {
            Marker(visit.displayName, coordinate: visit.coordinate)
                .tint(viewModel.isCurrentVisit(visit) ? .blue : .red)
        }
        .frame(height: 200)
        .clipShape(RoundedRectangle(cornerRadius: 12))
        .accessibilityLabel("Visit map")
        .accessibilityValue("\(visit.accessibilityLabel). \(visit.accessibilityValue)")
    }

    private var locationInfoSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Name")
                .font(.caption)
                .foregroundStyle(.secondary)

            HStack(alignment: .center, spacing: 8) {
                TextField("Visit name", text: $nameText)
                    .font(.title2.weight(.semibold))
                    .textFieldStyle(.plain)
                    .submitLabel(.done)
                    .focused($focusedField, equals: .name)
                    .onSubmit(saveName)
                    .onChange(of: focusedField) { oldValue, newValue in
                        if oldValue == .name && newValue != .name {
                            saveName()
                        }
                    }

                if visit.hasCustomName {
                    Button("Reset") {
                        resetName()
                    }
                    .font(.caption.weight(.semibold))
                    .buttonStyle(.bordered)
                    .controlSize(.small)
                    .accessibilityHint("Restores the automatically detected visit name.")
                }

                if viewModel.isCurrentVisit(visit) {
                    Text("Now")
                        .font(.caption)
                        .fontWeight(.medium)
                        .padding(.horizontal, 10)
                        .padding(.vertical, 4)
                        .background(.blue)
                        .foregroundStyle(.white)
                        .clipShape(Capsule())
                        .accessibilityLabel("Current visit")
                }
            }

            if visit.hasCustomName, visit.automaticDisplayName != visit.displayName {
                Text("Detected as \(visit.automaticDisplayName)")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }

            if let address = visit.address, !address.isEmpty, address != visit.displayName {
                Text(address)
                    .font(.subheadline)
                    .foregroundStyle(.secondary)
            }

            visitStatusBadges

            if let originalName = visit.originalLocationName,
               visit.confirmationStatus == .corrected,
               originalName != visit.displayName {
                Text("Originally detected as \(originalName)")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }

            NearbyVisitNameSuggestionSection(visit: visit) { suggestion in
                correctVisit(with: suggestion)
            }

            // Coordinates (for debugging/reference)
            Text(String(format: "%.6f, %.6f", visit.latitude, visit.longitude))
                .font(.caption)
                .foregroundStyle(.tertiary)
                .monospaced()
        }
        .padding()
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private var timeInfoSection: some View {
        VStack(spacing: 0) {
            DatePicker("Arrived", selection: $arrivedAt, displayedComponents: [.date, .hourAndMinute])
                .padding()
                .onChange(of: arrivedAt) { _, _ in validateEditedTimes() }

            Divider()

            Toggle("Still here", isOn: $isStillHere)
                .padding()
                .onChange(of: isStillHere) { _, _ in validateEditedTimes() }

            if !isStillHere {
                Divider()

                DatePicker("Departed", selection: $departedAt, displayedComponents: [.date, .hourAndMinute])
                    .padding()
                    .onChange(of: departedAt) { _, _ in validateEditedTimes() }
            }

            Divider()

            HStack {
                Text("Duration")
                    .font(.caption)
                    .foregroundStyle(.secondary)
                Spacer()
                Text(editedDurationText)
                    .font(.title3)
                    .fontWeight(.medium)
            }
            .padding()

            if let timeValidationMessage {
                Divider()
                Text(timeValidationMessage)
                    .font(.caption)
                    .foregroundStyle(.red)
                    .frame(maxWidth: .infinity, alignment: .leading)
                    .padding()
            }

            if hasUnsavedTimeChanges {
                Divider()
                Button {
                    saveTimes()
                } label: {
                    Label("Save Time Changes", systemImage: "clock.badge.checkmark")
                        .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .disabled(timeValidationMessage != nil)
                .padding()
            }
        }
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private var notesSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Text("Notes")
                .font(.caption)
                .foregroundStyle(.secondary)

            TextField("Add notes about this visit...", text: $notesText, axis: .vertical)
                .textFieldStyle(.plain)
                .lineLimit(3...6)
                .focused($focusedField, equals: .notes)
                .onChange(of: focusedField) { oldValue, newValue in
                    if oldValue == .notes && newValue != .notes {
                        saveNotes()
                    }
                }
        }
        .padding()
        .background(Color(.secondarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 12))
    }

    private var actionsSection: some View {
        VStack(spacing: 12) {
            AddEventToCalendarButton { CalendarEventDraft(visit: visit) }

            if visit.confirmationStatus == .unconfirmed {
                Button {
                    confirmVisit()
                } label: {
                    HStack {
                        Image(systemName: "checkmark.seal")
                            .accessibilityHidden(true)
                        Text("Confirm Place")
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.borderedProminent)
                .accessibilityHint("Marks this visit as reviewed and correct.")
            }

            if visit.confirmationStatus == .corrected,
               visit.originalCoordinate != nil {
                Button {
                    undoCorrection()
                } label: {
                    HStack {
                        Image(systemName: "arrow.uturn.backward")
                            .accessibilityHidden(true)
                        Text("Undo Correction")
                    }
                    .frame(maxWidth: .infinity)
                }
                .buttonStyle(.bordered)
                .accessibilityHint("Restores the originally detected visit location.")
            }

            // Open in Maps
            Button {
                openInMaps()
            } label: {
                HStack {
                    Image(systemName: "map")
                        .accessibilityHidden(true)
                    Text("Open in Maps")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .accessibilityLabel("Open visit in Maps")
            .accessibilityValue(visit.displayName)
            .accessibilityHint("Opens this location in your default navigation app when supported.")

            // Delete
            Button(role: .destructive) {
                showingDeleteConfirmation = true
            } label: {
                HStack {
                    Image(systemName: "trash")
                        .accessibilityHidden(true)
                    Text("Delete Visit")
                }
                .frame(maxWidth: .infinity)
            }
            .buttonStyle(.bordered)
            .tint(.red)
            .accessibilityHint("Deletes this visit after confirmation.")
        }
    }

    // MARK: - Actions

    private var editedDeparture: Date? {
        isStillHere ? nil : departedAt
    }

    private var hasUnsavedTimeChanges: Bool {
        abs(arrivedAt.timeIntervalSince(visit.arrivedAt)) > 0.5 || editedDepartureChanged
    }

    private var editedDepartureChanged: Bool {
        switch (editedDeparture, visit.departedAt) {
        case (nil, nil): return false
        case let (lhs?, rhs?): return abs(lhs.timeIntervalSince(rhs)) > 0.5
        default: return true
        }
    }

    private var editedDurationText: String {
        let end = editedDeparture ?? Date()
        let seconds = max(0, end.timeIntervalSince(arrivedAt))
        return VisitDetailDurationFormatter.format(seconds)
    }

    private func syncEditableStateFromVisit() {
        nameText = visit.displayName
        notesText = visit.notes ?? ""
        arrivedAt = visit.arrivedAt
        departedAt = visit.departedAt ?? Date()
        isStillHere = visit.departedAt == nil
        validateEditedTimes()
    }

    private func validateEditedTimes() {
        if !isStillHere, departedAt < arrivedAt {
            timeValidationMessage = String(localized: "Departure must be after arrival.")
        } else {
            timeValidationMessage = nil
        }
    }

    private func saveTimes() {
        validateEditedTimes()
        guard timeValidationMessage == nil else { return }
        guard viewModel.updateVisitTimes(visit, arrivedAt: arrivedAt, departedAt: editedDeparture) else {
            timeValidationMessage = String(localized: "Departure must be after arrival.")
            return
        }
        syncEditableStateFromVisit()
    }

    private func saveName() {
        viewModel.updateVisitName(visit, customName: nameText)
        nameText = visit.displayName
    }

    private func resetName() {
        viewModel.clearVisitName(visit)
        nameText = visit.displayName
    }

    private var visitStatusBadges: some View {
        HStack(spacing: 8) {
            VisitMetadataBadge(
                text: visit.confirmationStatus.displayName,
                systemImage: statusSystemImage,
                tint: statusTint
            )

            VisitMetadataBadge(
                text: visit.source.displayName,
                systemImage: sourceSystemImage,
                tint: .gray
            )
        }
        .padding(.top, 2)
        .accessibilityElement(children: .combine)
        .accessibilityLabel("Visit status: \(visit.confirmationStatus.displayName), source: \(visit.source.displayName)")
    }

    private var statusSystemImage: String {
        switch visit.confirmationStatus {
        case .unconfirmed: return "questionmark.circle"
        case .confirmed: return "checkmark.seal"
        case .corrected: return "mappin.and.ellipse.circle"
        }
    }

    private var sourceSystemImage: String {
        switch visit.source {
        case .automatic: return "location"
        case .manual: return "hand.tap"
        case .imported: return "square.and.arrow.down"
        }
    }

    private var statusTint: Color {
        switch visit.confirmationStatus {
        case .unconfirmed: return .orange
        case .confirmed: return .green
        case .corrected: return .blue
        }
    }

    private func correctVisit(with suggestion: NearbyPlaceSuggestion) {
        focusedField = nil
        viewModel.correctVisit(
            visit,
            name: suggestion.name,
            address: suggestion.address,
            coordinate: suggestion.coordinate,
            placeSource: .appleMaps,
            distanceMeters: suggestion.distanceMeters
        )
        nameText = visit.displayName
    }

    private func confirmVisit() {
        focusedField = nil
        saveName()
        saveNotes()
        viewModel.confirmVisit(visit)
    }

    private func undoCorrection() {
        focusedField = nil
        viewModel.undoVisitCorrection(visit)
        nameText = visit.displayName
    }

    private func saveNotes() {
        viewModel.updateVisitNotes(visit, notes: notesText)
    }

    private func openInMaps() {
        let coordinate = visit.coordinate
        let name = visit.displayName
        Task { @MainActor in
            await VisitMapOpener.open(coordinate: coordinate, name: name)
        }
    }
}

private enum VisitDetailDurationFormatter {
    static func format(_ seconds: TimeInterval) -> String {
        let minutes = max(0, Int(seconds / 60))
        if minutes < 60 { return String(localized: "\(minutes) min") }
        let hours = minutes / 60
        let remainingMinutes = minutes % 60
        return remainingMinutes == 0
            ? String(localized: "\(hours)h")
            : String(localized: "\(hours)h \(remainingMinutes)m")
    }
}

private struct VisitMetadataBadge: View {
    let text: String
    let systemImage: String
    let tint: Color

    var body: some View {
        Label(text, systemImage: systemImage)
            .font(.caption2.weight(.semibold))
            .textCase(.uppercase)
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .foregroundStyle(tint)
            .background(tint.opacity(0.12))
            .clipShape(Capsule())
    }
}

struct NearbyVisitNameSuggestionSection: View {
    let visit: Visit
    let onSelect: (NearbyPlaceSuggestion) -> Void

    @State private var suggestions: [NearbyPlaceSuggestion] = []
    @State private var isLoading = false
    @State private var hasSearched = false
    @State private var loadFailed = false
    @State private var businessSearchText = ""

    private var normalizedBusinessSearchText: String {
        businessSearchText.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    private var filteredSuggestions: [NearbyPlaceSuggestion] {
        let searchText = normalizedBusinessSearchText
        guard !searchText.isEmpty else { return suggestions }

        return suggestions.filter { suggestion in
            suggestion.name.localizedCaseInsensitiveContains(searchText) ||
            (suggestion.address?.localizedCaseInsensitiveContains(searchText) ?? false)
        }
    }

    private var suggestionTaskKey: String {
        String(format: "%@-%.5f-%.5f", visit.id.uuidString, visit.latitude, visit.longitude)
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 6) {
                Label("Nearby businesses", systemImage: "building.2")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(.secondary)

                Spacer()

                if isLoading {
                    ProgressView()
                        .controlSize(.mini)
                        .accessibilityLabel("Loading nearby businesses")
                }
            }

            searchField
                .disabled(suggestions.isEmpty)
                .opacity(suggestions.isEmpty ? 0.65 : 1)

            if suggestions.isEmpty {
                emptyState
            } else if filteredSuggestions.isEmpty {
                filteredEmptyState
            } else {
                suggestionList
            }
        }
        .padding(.top, 6)
        .task(id: suggestionTaskKey) {
            await loadSuggestions(force: true)
        }
    }

    private var searchField: some View {
        HStack(spacing: 8) {
            Image(systemName: "magnifyingglass")
                .font(.caption)
                .foregroundStyle(.secondary)
                .accessibilityHidden(true)

            TextField("Filter businesses", text: $businessSearchText)
                .font(.caption)
                .textInputAutocapitalization(.words)
                .autocorrectionDisabled()
                .submitLabel(.search)

            if !businessSearchText.isEmpty {
                Button {
                    businessSearchText = ""
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(.tertiary)
                        .accessibilityLabel("Clear business filter")
                }
                .buttonStyle(.plain)
            }
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 8)
        .background(Color(.tertiarySystemBackground))
        .clipShape(RoundedRectangle(cornerRadius: 10))
    }

    private var emptyState: some View {
        VStack(alignment: .leading, spacing: 6) {
            if loadFailed {
                Text("Couldn’t load nearby businesses.")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            } else if isLoading || !hasSearched {
                Text("Looking for places near this visit…")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            } else {
                Text("No nearby businesses found. Try again, or edit the visit name manually.")
                    .font(.caption)
                    .foregroundStyle(.tertiary)
            }

            if loadFailed || (hasSearched && suggestions.isEmpty && !isLoading) {
                Button {
                    Task { await loadSuggestions(force: true) }
                } label: {
                    Label("Try nearby search again", systemImage: "arrow.clockwise")
                        .font(.caption)
                }
                .buttonStyle(.borderless)
            }
        }
    }

    private var filteredEmptyState: some View {
        VStack(alignment: .leading, spacing: 6) {
            Text("No nearby businesses match “\(normalizedBusinessSearchText)”.")
                .font(.caption)
                .foregroundStyle(.tertiary)

            Button {
                businessSearchText = ""
            } label: {
                Label("Clear filter", systemImage: "xmark.circle")
                    .font(.caption)
            }
            .buttonStyle(.borderless)
        }
    }

    private var suggestionList: some View {
        VStack(spacing: 8) {
            ForEach(filteredSuggestions) { suggestion in
                Button {
                    onSelect(suggestion)
                } label: {
                    HStack(spacing: 10) {
                        Image(systemName: "mappin.and.ellipse")
                            .foregroundStyle(.blue)
                            .accessibilityHidden(true)

                        VStack(alignment: .leading, spacing: 2) {
                            Text(suggestion.name)
                                .font(.subheadline.weight(.semibold))
                                .foregroundStyle(.primary)
                                .lineLimit(1)

                            if let address = suggestion.address, !address.isEmpty {
                                Text(address)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                    .lineLimit(1)
                            }
                        }

                        Spacer(minLength: 8)

                        Text(suggestion.distanceLabel)
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    }
                    .padding(10)
                    .background(Color(.tertiarySystemBackground))
                    .clipShape(RoundedRectangle(cornerRadius: 10))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Correct visit to \(suggestion.name)")
                .accessibilityValue(suggestion.distanceLabel)
            }
        }
    }

    @MainActor
    private func loadSuggestions(force: Bool = false) async {
        guard force || (!isLoading && !hasSearched) else { return }

        let coordinate = visit.coordinate
        if force {
            suggestions = []
            businessSearchText = ""
            hasSearched = false
        }
        isLoading = true
        loadFailed = false
        defer {
            isLoading = false
            hasSearched = true
        }

        do {
            let nearbySuggestions = try await NearbyPlaceSearchService.shared.suggestions(
                near: coordinate,
                limit: 6
            )

            guard !Task.isCancelled else { return }
            suggestions = nearbySuggestions
        } catch is CancellationError {
            // Ignore cancellation from SwiftUI task lifecycle changes.
        } catch {
            suggestions = []
            loadFailed = true
        }
    }
}

#Preview {
    NavigationStack {
        VisitDetailView(
            visit: Visit.preview,
            viewModel: LocationViewModel(
                modelContext: try! ModelContainer(for: Visit.self, LocationPoint.self, RecordingSession.self, PhotoMoment.self, SavedPlace.self, CountryStay.self).mainContext,
                locationManager: LocationManager()
            )
        )
    }
}
