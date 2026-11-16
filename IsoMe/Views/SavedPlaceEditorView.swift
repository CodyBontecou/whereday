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
    @State private var viewportSize = CGSize.zero
    @FocusState private var focusedField: Field?

    private enum Field: Hashable {
        case name, address, latitude, longitude, radius
    }

    private struct LocationPickerRequest: Identifiable {
        let id = UUID()
        let selection: ManualLocationSelection?
        let region: MKCoordinateRegion?
    }

    /// - Parameter kind: Presets the place's kind, e.g. `.home` when saving home from onboarding.
    init(viewModel: LocationViewModel, draft: SavedPlaceDraft, kind: SavedPlaceKind? = nil) {
        self.viewModel = viewModel
        var draft = draft
        if let kind { draft.kind = kind }
        initialDraft = draft
        _draft = State(initialValue: draft)
        _latitudeText = State(initialValue: Self.coordinateText(draft.latitude))
        _longitudeText = State(initialValue: Self.coordinateText(draft.longitude))
        _radiusText = State(initialValue: Self.coordinateText(draft.radiusMeters))
    }

    var body: some View {
        NavigationStack {
            ScrollViewReader { reader in
                ScrollView {
                    VStack(alignment: .leading, spacing: 16) {
                        locationSection
                        nameField
                        kindSection
                        radiusSection
                        addressField
                        coordinatesSection

                        if isDirty, !nameIsMissing, let message = formDraft.validationMessage {
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
                .onGeometryChange(for: CGSize.self) { $0.size } action: { viewportSize = $0 }
                .task(id: viewportSize) {
                    guard let target = focusedField else { return }
                    // Let rotation/keyboard layout settle before finding the field's new frame.
                    await Task.yield()
                    guard !Task.isCancelled, focusedField == target else { return }
                    reader.scrollTo(target, anchor: .center)
                }
            }
            .scrollDismissesKeyboard(.interactively)
            .background(TE.surface.ignoresSafeArea())
            .foregroundStyle(TE.textPrimary)
            .navigationTitle(isExistingPlace ? "Edit place" : "Add place")
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
            if let coordinate = validCoordinate {
                DraggablePinMap(coordinate: coordinate, radius: previewRadius, ink: placeInk,
                                name: draft.name.isEmpty ? String(localized: "Saved Location") : draft.name,
                                onMove: movePin, onSearch: chooseLocation)
                    .frame(height: 210)
            } else {
            Button(action: chooseLocation) {
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
            .buttonStyle(.plain)
            .accessibilityLabel("Choose Location")
            .accessibilityHint("Search for a place or move a pin on the map.")
            }
        }
    }

    /// KIND chips: what this place is to you. Tapping the chosen kind again clears it.
    private var kindSection: some View {
        fieldGroup("KIND") {
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    ForEach(SavedPlaceKind.allCases) { kind in
                        let isOn = draft.kind == kind
                        Button {
                            draft.kind = isOn ? nil : kind
                        } label: {
                            Text(kind.label)
                                .font(.footnote.weight(.medium))
                                .lineLimit(1)
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
                }
            }
            .scrollIndicators(.hidden)
        }
    }

    /// The map moved under the centre pin; keep the typed coordinates in step.
    private func movePin(to coordinate: CLLocationCoordinate2D) {
        latitudeText = Self.coordinateText((coordinate.latitude * 1_000_000).rounded() / 1_000_000)
        longitudeText = Self.coordinateText((coordinate.longitude * 1_000_000).rounded() / 1_000_000)
    }

    private var nameField: some View {
        fieldGroup("NAME") {
            TextField("Name", text: $draft.name, prompt: Text("e.g. Home or Favorite Cafe"))
                .textInputAutocapitalization(.words)
                .focused($focusedField, equals: .name)
                .accessibilityLabel("Location name")
                .id(Field.name)
                .modifier(PlaceEditorFieldChrome())
            if isDirty, nameIsMissing {
                Text("Enter a location name.")
                    .font(.footnote)
                    .foregroundStyle(TE.inkRust)
                    .fixedSize(horizontal: false, vertical: true)
            }
        }
    }

    private var addressField: some View {
        fieldGroup("ADDRESS · OPTIONAL") {
            TextField("Address", text: $draft.address, prompt: Text("Address (optional)"), axis: .vertical)
                .lineLimit(1...3)
                .textInputAutocapitalization(.words)
                .focused($focusedField, equals: .address)
                .accessibilityLabel("Address, optional")
                .id(Field.address)
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
                    .frame(minWidth: 64, maxWidth: 120)
                    .accessibilityLabel("Match radius in meters")
                    .id(Field.radius)
                Text("m")
                    .font(TE.editorial(.title3))
                    .accessibilityHidden(true)
            }
            Slider(value: radiusSliderBinding, in: 25...500, step: 5) {
                Text("Match radius")
            }
            .tint(TE.accent)
            .accessibilityValue("\(Int(previewRadius)) meters")
            Text("Visits inside the circle get this name automatically.")
                .font(TE.mono(.caption2))
                .foregroundStyle(TE.textMuted)
                .fixedSize(horizontal: false, vertical: true)
            Text("Choose 25–10,000 meters; type a value above 500. Past visits keep their names.")
                .font(TE.mono(.caption2))
                .foregroundStyle(TE.textMuted.opacity(0.8))
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
                .id(field)
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

    private var nameIsMissing: Bool {
        draft.name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty
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
        draft.name != initialDraft.name || draft.address != initialDraft.address || draft.kind != initialDraft.kind ||
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

/// The place editor's map: drag the map and the centre pin lands on a new spot, with the
/// match radius drawn around it. Searching or typing coordinates moves the map to match.
private struct DraggablePinMap: View {
    let coordinate: CLLocationCoordinate2D
    let radius: Double
    let ink: Color
    let name: String
    let onMove: (CLLocationCoordinate2D) -> Void
    let onSearch: () -> Void

    @State private var position: MapCameraPosition
    /// The centre last reported by or applied to the map, so its own echo isn't treated as a move.
    @State private var trackedCenter: CLLocationCoordinate2D

    init(coordinate: CLLocationCoordinate2D, radius: Double, ink: Color, name: String,
         onMove: @escaping (CLLocationCoordinate2D) -> Void, onSearch: @escaping () -> Void) {
        self.coordinate = coordinate
        self.radius = radius
        self.ink = ink
        self.name = name
        self.onMove = onMove
        self.onSearch = onSearch
        _position = State(initialValue: .region(Self.region(around: coordinate, radius: radius)))
        _trackedCenter = State(initialValue: coordinate)
    }

    var body: some View {
        Map(position: $position, interactionModes: [.pan, .zoom]) {
            MapCircle(center: coordinate, radius: radius)
                .foregroundStyle(ink.opacity(0.14))
                .stroke(ink, style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
        }
        .onMapCameraChange(frequency: .continuous) { context in
            // MapKit may clamp a typed/search coordinate to its drawable region.
            // Only an actual pan/zoom should write that camera center into the draft.
            guard position.positionedByUser else { return }
            let center = context.region.center
            guard Self.distance(center, trackedCenter) > 0.5 else { return }
            trackedCenter = center
            onMove(center)
        }
        .onChange(of: "\(coordinate.latitude),\(coordinate.longitude)") { _, _ in
            // A search result or typed coordinate: bring the map to it.
            guard Self.distance(coordinate, trackedCenter) > 1 else { return }
            trackedCenter = coordinate
            position = .region(Self.region(around: coordinate, radius: radius))
        }
        .overlay {
            Circle()
                .fill(ink)
                .frame(width: 20, height: 20)
                .overlay(Circle().strokeBorder(TE.surface, lineWidth: 3))
                .shadow(color: .black.opacity(0.18), radius: 3, y: 1)
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
        .overlay(alignment: .bottomTrailing) {
            Text("DRAG PIN TO MOVE")
                .font(TE.mono(.caption2))
                .foregroundStyle(TE.textPrimary)
                .padding(.horizontal, 8)
                .padding(.vertical, 4)
                .background(RoundedRectangle(cornerRadius: 6).fill(TE.card.opacity(0.92)))
                .padding(10)
                .allowsHitTesting(false)
        }
        .overlay(alignment: .topTrailing) {
            Button(action: onSearch) {
                Image(systemName: "magnifyingglass")
                    .font(.system(size: 15, weight: .medium))
                    .foregroundStyle(TE.textPrimary)
                    .frame(width: 44, height: 44)
                    .background(Circle().fill(TE.card.opacity(0.94)))
                    .overlay(Circle().strokeBorder(TE.border, lineWidth: 1))
            }
            .buttonStyle(.plain)
            .padding(8)
            .accessibilityLabel("Search for a place")
            .accessibilityHint("Search for an address or place a pin on a larger map.")
        }
        .clipShape(RoundedRectangle(cornerRadius: 18))
        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.border, lineWidth: 1))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Map around \(name)")
        .accessibilityValue("Match radius: \(Int(radius.rounded())) meters")
        .accessibilityActions {
            Button("Move pin north") { nudge(north: 10, east: 0) }
            Button("Move pin south") { nudge(north: -10, east: 0) }
            Button("Move pin east") { nudge(north: 0, east: 10) }
            Button("Move pin west") { nudge(north: 0, east: -10) }
        }
    }

    /// VoiceOver can't drag a map, so it moves the pin in 10 m steps instead.
    private func nudge(north: Double, east: Double) {
        let latitude = coordinate.latitude + north / 111_000
        let longitude = coordinate.longitude + east / (111_000 * max(0.01, cos(coordinate.latitude * .pi / 180)))
        let moved = CLLocationCoordinate2D(latitude: latitude, longitude: longitude)
        guard CLLocationCoordinate2DIsValid(moved) else { return }
        onMove(moved)
    }

    private static func region(around coordinate: CLLocationCoordinate2D, radius: Double) -> MKCoordinateRegion {
        let span = max(400, radius * 4)
        return MKCoordinateRegion(center: coordinate, latitudinalMeters: span, longitudinalMeters: span)
    }

    private static func distance(_ a: CLLocationCoordinate2D, _ b: CLLocationCoordinate2D) -> CLLocationDistance {
        CLLocation(latitude: a.latitude, longitude: a.longitude)
            .distance(from: CLLocation(latitude: b.latitude, longitude: b.longitude))
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
