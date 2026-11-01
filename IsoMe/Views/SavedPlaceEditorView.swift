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
    @State private var showingDeleteConfirmation = false
    @State private var showsCoordinates = false
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
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    locationSection
                    nameField
                    radiusSection
                    addressField
                    coordinatesSection

                    if isDirty, let message = formDraft.validationMessage {
                        Text(message)
                            .font(.footnote)
                            .foregroundStyle(TE.inkRust)
                            .fixedSize(horizontal: false, vertical: true)
                    }

                    bottomActions
                        .padding(.top, 8)
                }
                .padding(.horizontal, 16)
                .padding(.top, 10)
                .padding(.bottom, 24)
            }
            .scrollDismissesKeyboard(.interactively)
            .background(TE.surface.ignoresSafeArea())
            .foregroundStyle(TE.textPrimary)
            .navigationTitle(isExistingPlace ? "Edit Location" : "Add Location")
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(.hidden, for: .navigationBar)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel", action: cancel)
                        .foregroundStyle(TE.textMuted)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save", action: save)
                        .fontWeight(.semibold)
                        .foregroundStyle(formDraft.validationMessage == nil ? TE.inkRust : TE.textMuted)
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
            .confirmationDialog("Delete Saved Location?", isPresented: $showingDeleteConfirmation, titleVisibility: .visible) {
                Button("Delete", role: .destructive, action: delete)
                Button("Cancel", role: .cancel) {}
            } message: {
                Text("This removes the reusable location. Your past visits are kept.")
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
        .tint(TE.accent)
    }

    private var isExistingPlace: Bool { initialDraft.existingPlaceID != nil }

    private var placeInk: Color {
        guard let id = initialDraft.existingPlaceID,
              let place = viewModel.savedPlaces.first(where: { $0.id == id }) else { return TE.placeInks[0] }
        return PlaceVisitIndex.ink(for: place, in: viewModel.savedPlaces)
    }

    private var locationSection: some View {
        VStack(alignment: .leading, spacing: 8) {
            Button(action: chooseLocation) {
                if let coordinate = validCoordinate {
                    PlaceMapCard(coordinate: coordinate, radius: previewRadius, ink: placeInk,
                                 name: draft.name.isEmpty ? String(localized: "Saved Location") : draft.name,
                                 caption: "TAP TO MOVE PIN")
                        .frame(height: 190)
                } else {
                    VStack(spacing: 8) {
                        Image(systemName: "mappin.and.ellipse")
                            .font(.title2)
                            .foregroundStyle(TE.accent)
                        Text("Choose Location")
                            .font(.subheadline.weight(.medium))
                        Text("Search for an address or place a pin anywhere, including places missing from Maps.")
                            .font(.footnote)
                            .foregroundStyle(TE.textMuted)
                            .multilineTextAlignment(.center)
                            .fixedSize(horizontal: false, vertical: true)
                    }
                    .padding(20)
                    .frame(maxWidth: .infinity, minHeight: 150)
                    .background(RoundedRectangle(cornerRadius: 16).fill(TE.dialTrack))
                    .overlay(RoundedRectangle(cornerRadius: 16).strokeBorder(TE.border, lineWidth: 1))
                }
            }
            .buttonStyle(.plain)
            .accessibilityLabel(validCoordinate == nil ? "Choose Location" : "Change Location")
            .accessibilityValue(validCoordinate == nil ? "" : String(localized: "Match radius: \(Int(previewRadius)) meters"))
            .accessibilityHint("Search for a place or move a pin on the map.")
        }
    }

    private var nameField: some View {
        fieldGroup("NAME") {
            TextField("Name", text: $draft.name, prompt: Text("e.g. Home or Favorite Cafe"))
                .textInputAutocapitalization(.words)
                .focused($focusedField, equals: .name)
                .accessibilityLabel("Location name")
                .modifier(PlaceEditorFieldChrome())
        }
    }

    private var addressField: some View {
        fieldGroup("ADDRESS · OPTIONAL") {
            TextField("Address", text: $draft.address, prompt: Text("Address (optional)"), axis: .vertical)
                .lineLimit(1...3)
                .textInputAutocapitalization(.words)
                .focused($focusedField, equals: .address)
                .accessibilityLabel("Address, optional")
                .modifier(PlaceEditorFieldChrome())
        }
    }

    private var radiusSection: some View {
        VStack(alignment: .leading, spacing: 4) {
            HStack(alignment: .firstTextBaseline) {
                Text("MATCH RADIUS")
                    .font(TE.mono(.caption2, weight: .semibold))
                    .tracking(1.5)
                    .foregroundStyle(TE.textMuted)
                Spacer(minLength: 8)
                TextField("150", text: $radiusText)
                    .keyboardType(.decimalPad)
                    .multilineTextAlignment(.trailing)
                    .font(TE.editorial(.title3))
                    .focused($focusedField, equals: .radius)
                    .fixedSize()
                    .accessibilityLabel("Match radius in meters")
                Text("m")
                    .font(TE.editorial(.title3))
                    .accessibilityHidden(true)
            }
            Slider(value: radiusSliderBinding, in: 25...500, step: 5) {
                Text("Match radius")
            }
            .tint(TE.accent)
            .accessibilityValue("\(Int(previewRadius)) meters")
            Text("Future automatic visits inside this radius use the saved name. Choose 25–10,000 meters; type a value above 500. Editing a saved location does not change past visits.")
                .font(TE.mono(.caption2))
                .foregroundStyle(TE.textMuted)
                .fixedSize(horizontal: false, vertical: true)
        }
    }

    private var radiusSliderBinding: Binding<Double> {
        Binding(
            get: { min(max(previewRadius, 25), 500) },
            set: { radiusText = Self.coordinateText($0.rounded()) }
        )
    }

    private var coordinatesSection: some View {
        DisclosureGroup(isExpanded: $showsCoordinates) {
            VStack(alignment: .leading, spacing: 12) {
                coordinateField("Latitude", placeholder: "−90 to 90", text: $latitudeText, field: .latitude)
                coordinateField("Longitude", placeholder: "−180 to 180", text: $longitudeText, field: .longitude)
                Text("You can also enter coordinates directly instead of using the map.")
                    .font(TE.mono(.caption2))
                    .foregroundStyle(TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
            }
            .padding(.top, 10)
        } label: {
            Text("COORDINATES")
                .font(TE.mono(.caption2, weight: .semibold))
                .tracking(1.5)
                .foregroundStyle(TE.textMuted)
                .frame(minHeight: 44)
        }
        .tint(TE.textMuted)
    }

    @ViewBuilder
    private var bottomActions: some View {
        if isExistingPlace {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) {
                    deleteButton.frame(maxWidth: .infinity)
                    saveButton.frame(maxWidth: .infinity).layoutPriority(1)
                }
                VStack(spacing: 8) {
                    saveButton
                    deleteButton
                }
            }
        } else {
            saveButton
        }
    }

    private var saveButton: some View {
        Button("Save place", action: save)
            .buttonStyle(PlacesInkButtonStyle(emphasis: .primary))
            .disabled(formDraft.validationMessage != nil)
    }

    private var deleteButton: some View {
        Button("Delete") { showingDeleteConfirmation = true }
            .buttonStyle(PlacesInkButtonStyle(emphasis: .destructive))
    }

    private func fieldGroup<Content: View>(_ title: LocalizedStringKey, @ViewBuilder content: () -> Content) -> some View {
        VStack(alignment: .leading, spacing: 6) {
            Text(title)
                .font(TE.mono(.caption2, weight: .semibold))
                .textCase(.uppercase)
                .tracking(1.4)
                .foregroundStyle(TE.textMuted)
            content()
        }
    }

    private func coordinateField(_ title: LocalizedStringKey, placeholder: LocalizedStringKey, text: Binding<String>, field: Field) -> some View {
        fieldGroup(title) {
            TextField(title, text: text, prompt: Text(placeholder))
                .keyboardType(.numbersAndPunctuation)
                .textInputAutocapitalization(.never)
                .autocorrectionDisabled()
                .focused($focusedField, equals: field)
                .accessibilityLabel(Text(title))
                .modifier(PlaceEditorFieldChrome())
        }
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

    private func delete() {
        focusedField = nil
        guard let id = initialDraft.existingPlaceID,
              let place = viewModel.savedPlaces.first(where: { $0.id == id }) else { dismiss(); return }
        do {
            try viewModel.deleteManagedPlace(place)
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

/// Paper text fields with a hairline border, as in the place and stay editors.
struct PlaceEditorFieldChrome: ViewModifier {
    func body(content: Content) -> some View {
        content
            .padding(.horizontal, 14)
            .padding(.vertical, 12)
            .frame(minHeight: 48)
            .background(RoundedRectangle(cornerRadius: 12).fill(TE.card))
            .overlay(RoundedRectangle(cornerRadius: 12).strokeBorder(TE.border, lineWidth: 1))
    }
}
