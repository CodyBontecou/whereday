import CoreLocation
import MapKit
import SwiftUI

struct SavedPlaceEditorView: View {
    let viewModel: LocationViewModel
    private let initialDraft: SavedPlaceDraft

    @Environment(\.dismiss) private var dismiss
    @State private var draft: SavedPlaceDraft
    @State private var latitudeText: String
    @State private var longitudeText: String
    @State private var radiusText: String
    @State private var locationPicker: LocationPickerRequest?
    @State private var showingDiscardConfirmation = false
    @State private var saveError: String?
    @FocusState private var focusedField: Field?

    private enum Field: Hashable {
        case name, address, latitude, longitude, radius
    }

    private struct LocationPickerRequest: Identifiable {
        let id = UUID()
        let selection: ManualLocationSelection?
        let region: MKCoordinateRegion?
    }

    init(viewModel: LocationViewModel, draft: SavedPlaceDraft) {
        self.viewModel = viewModel
        initialDraft = draft
        _draft = State(initialValue: draft)
        _latitudeText = State(initialValue: Self.coordinateText(draft.latitude))
        _longitudeText = State(initialValue: Self.coordinateText(draft.longitude))
        _radiusText = State(initialValue: Self.coordinateText(draft.radiusMeters))
    }

    var body: some View {
        NavigationStack {
            Form {
                detailsSection
                locationSection
                coordinatesSection
                radiusSection

                if isDirty, let message = formDraft.validationMessage {
                    Section {
                        Text(message)
                            .font(.footnote)
                            .foregroundStyle(.secondary)
                    }
                }
            }
            .scrollContentBackground(.hidden)
            .background {
                FieldNotesBackground()
                    .overlay(TE.surface.opacity(0.45))
                    .allowsHitTesting(false)
            }
            .navigationTitle(initialDraft.existingPlaceID == nil ? "Add Location" : "Edit Location")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: cancel)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: save)
                        .disabled(formDraft.validationMessage != nil)
                        .accessibilityHint(formDraft.validationMessage ?? String(localized: "Saves this reusable location."))
                }
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("Done") { focusedField = nil }
                }
            }
            .sheet(item: $locationPicker) { request in
                PlaceSelectionView(
                    initialSelection: request.selection,
                    initialRegion: request.region,
                    onSelect: applyLocationSelection
                )
                .presentationDetents([.large])
            }
            .confirmationDialog("Discard Changes?", isPresented: $showingDiscardConfirmation, titleVisibility: .visible) {
                Button("Discard Changes", role: .destructive) { dismiss() }
                Button("Keep Editing", role: .cancel) {}
            } message: {
                Text("Your changes to this saved location have not been saved.")
            }
            .alert("Unable to Save Location", isPresented: Binding(
                get: { saveError != nil },
                set: { if !$0 { saveError = nil } }
            )) {
                Button("OK", role: .cancel) { saveError = nil }
            } message: {
                Text(saveError ?? "")
            }
        }
        .interactiveDismissDisabled(isDirty)
    }

    private var detailsSection: some View {
        Section {
            TextField("Name", text: $draft.name, prompt: Text("e.g. Home or Favorite Cafe"))
                .textInputAutocapitalization(.words)
                .focused($focusedField, equals: .name)
                .accessibilityLabel("Location name")

            TextField("Address", text: $draft.address, prompt: Text("Address (optional)"), axis: .vertical)
                .lineLimit(1...3)
                .textInputAutocapitalization(.words)
                .focused($focusedField, equals: .address)
                .accessibilityLabel("Address, optional")
        } header: {
            sectionHeader("LOCATION DETAILS")
        }
        .listRowBackground(TE.card.opacity(0.97))
    }

    private var locationSection: some View {
        Section {
            if let coordinate = validCoordinate {
                Map(initialPosition: .region(previewRegion(for: coordinate)), interactionModes: []) {
                    MapCircle(center: coordinate, radius: previewRadius)
                        .foregroundStyle(.blue.opacity(0.15))
                        .stroke(.blue.opacity(0.4), lineWidth: 1)
                    Marker(draft.name.isEmpty ? "Saved Location" : draft.name, coordinate: coordinate)
                        .tint(.blue)
                }
                .id("\(coordinate.latitude),\(coordinate.longitude),\(previewRadius)")
                .frame(height: 180)
                .listRowInsets(EdgeInsets())
                .accessibilityElement(children: .ignore)
                .accessibilityLabel("Saved location map")
                .accessibilityValue("Match radius: \(Int(previewRadius)) meters")
            }

            Button(action: chooseLocation) {
                Label(validCoordinate == nil ? "Choose Location" : "Change Location", systemImage: "mappin.and.ellipse")
                    .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
            }
            .accessibilityHint("Search for a place or move a pin on the map.")
        } header: {
            sectionHeader("LOCATION")
        } footer: {
            Text("Search for an address or place a pin anywhere, including places missing from Maps.")
        }
        .listRowBackground(TE.card.opacity(0.97))
    }

    private var coordinatesSection: some View {
        Section {
            coordinateField("Latitude", placeholder: "−90 to 90", text: $latitudeText, field: .latitude)
            coordinateField("Longitude", placeholder: "−180 to 180", text: $longitudeText, field: .longitude)
        } header: {
            sectionHeader("COORDINATES")
        } footer: {
            Text("You can also enter coordinates directly instead of using the map.")
        }
        .listRowBackground(TE.card.opacity(0.97))
    }

    private var radiusSection: some View {
        Section {
            HStack {
                Text("Match radius")
                Spacer()
                TextField("150", text: $radiusText)
                    .keyboardType(.decimalPad)
                    .multilineTextAlignment(.trailing)
                    .focused($focusedField, equals: .radius)
                    .accessibilityLabel("Match radius in meters")
                Text("m")
                    .foregroundStyle(.secondary)
                    .accessibilityHidden(true)
            }
        } header: {
            sectionHeader("MATCH AREA")
        } footer: {
            Text("Future automatic visits inside this radius use the saved name. Choose 25–10,000 meters. Editing a saved location does not change past visits.")
        }
        .listRowBackground(TE.card.opacity(0.97))
    }

    private func coordinateField(_ title: LocalizedStringKey, placeholder: LocalizedStringKey, text: Binding<String>, field: Field) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(.caption)
                .foregroundStyle(.secondary)
            TextField(title, text: text, prompt: Text(placeholder))
                .keyboardType(.numbersAndPunctuation)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($focusedField, equals: field)
                .accessibilityLabel(Text(title))
        }
    }

    private func sectionHeader(_ title: LocalizedStringKey) -> some View {
        Text(title)
            .font(TE.mono(.caption2, weight: .semibold))
            .foregroundStyle(.secondary)
    }

    private var formDraft: SavedPlaceDraft {
        var result = draft
        result.latitude = Self.number(from: latitudeText)
        result.longitude = Self.number(from: longitudeText)
        result.radiusMeters = Self.number(from: radiusText) ?? .nan
        return result
    }

    private var validCoordinate: CLLocationCoordinate2D? {
        guard let coordinate = formDraft.coordinate,
              coordinate.latitude.isFinite, coordinate.longitude.isFinite,
              CLLocationCoordinate2DIsValid(coordinate) else { return nil }
        return coordinate
    }

    private var previewRadius: Double {
        let radius = formDraft.radiusMeters
        return radius.isFinite && (25...10_000).contains(radius) ? radius : 150
    }

    private var isDirty: Bool {
        draft.name != initialDraft.name || draft.address != initialDraft.address ||
        latitudeText != Self.coordinateText(initialDraft.latitude) ||
        longitudeText != Self.coordinateText(initialDraft.longitude) ||
        radiusText != Self.coordinateText(initialDraft.radiusMeters)
    }

    private func previewRegion(for coordinate: CLLocationCoordinate2D) -> MKCoordinateRegion {
        let distance = max(1_000, previewRadius * 3)
        return MKCoordinateRegion(center: coordinate, latitudinalMeters: distance, longitudinalMeters: distance)
    }

    private func chooseLocation() {
        focusedField = nil
        let selection = validCoordinate.map {
            ManualLocationSelection(name: draft.name, address: draft.address.isEmpty ? nil : draft.address, coordinate: $0, source: .mapPin)
        }
        locationPicker = LocationPickerRequest(selection: selection, region: validCoordinate.map(previewRegion))
    }

    private func applyLocationSelection(_ selection: ManualLocationSelection) {
        guard selection.coordinateIsValid else { return }
        latitudeText = Self.coordinateText(selection.coordinate.latitude)
        longitudeText = Self.coordinateText(selection.coordinate.longitude)
        if draft.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty {
            draft.name = selection.name
        }
        draft.address = selection.address ?? ""
    }

    private func cancel() {
        focusedField = nil
        if isDirty {
            showingDiscardConfirmation = true
        } else {
            dismiss()
        }
    }

    private func save() {
        focusedField = nil
        guard formDraft.validationMessage == nil else { return }
        do {
            try viewModel.saveManagedPlace(formDraft)
            dismiss()
        } catch {
            saveError = error.localizedDescription
        }
    }

    private static func coordinateText(_ value: Double?) -> String {
        guard let value else { return "" }
        let text = String(value)
        return text.hasSuffix(".0") ? String(text.dropLast(2)) : text
    }

    private static func number(from text: String) -> Double? {
        Double(text.trimmingCharacters(in: .whitespacesAndNewlines).replacingOccurrences(of: ",", with: "."))
    }
}
