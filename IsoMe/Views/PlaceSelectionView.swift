import SwiftUI
import MapKit
import CoreLocation

enum ManualVisitMode: Equatable {
    case saveCurrentPlace
    case addPlaceManually
    case addPastVisit

    var allowsImplicitCurrentLocation: Bool {
        self != .addPastVisit
    }

    var requestsCurrentLocation: Bool {
        self != .addPastVisit
    }

    var requiresDeparture: Bool {
        self == .addPastVisit
    }

    func resolvedCoordinate(
        selection: ManualLocationSelection?,
        currentLocation: CLLocation?
    ) -> CLLocationCoordinate2D? {
        if let selection, selection.coordinateIsValid {
            return selection.coordinate
        }
        guard allowsImplicitCurrentLocation,
              let currentLocation,
              CLLocationCoordinate2DIsValid(currentLocation.coordinate) else {
            return nil
        }
        return currentLocation.coordinate
    }
}

enum ManualLocationSelectionSource: Equatable {
    case currentLocation
    case savedPlace
    case appleMaps
    case mapPin
}

struct ManualLocationSelection {
    var name: String
    var address: String?
    var coordinate: CLLocationCoordinate2D
    var source: ManualLocationSelectionSource
    var savedPlaceID: UUID?
    var visitPlaceSource: VisitPlaceSource

    init(
        name: String,
        address: String?,
        coordinate: CLLocationCoordinate2D,
        source: ManualLocationSelectionSource,
        savedPlaceID: UUID? = nil,
        visitPlaceSource: VisitPlaceSource? = nil
    ) {
        self.name = name
        self.address = address
        self.coordinate = coordinate
        self.source = source
        self.savedPlaceID = savedPlaceID
        self.visitPlaceSource = visitPlaceSource ?? (source == .appleMaps ? .appleMaps : .userEntered)
    }

    mutating func updateUserEnteredMetadata(name: String, address: String?) {
        self.name = name
        self.address = address
        visitPlaceSource = .userEntered
    }

    var coordinateIsValid: Bool {
        CLLocationCoordinate2DIsValid(coordinate) &&
        coordinate.latitude.isFinite &&
        coordinate.longitude.isFinite
    }

    static func appleMapsResult(_ result: PlaceSearchResult) -> ManualLocationSelection {
        ManualLocationSelection(
            name: result.name,
            address: result.address,
            coordinate: result.coordinate,
            source: .appleMaps
        )
    }

    static func savedPlace(_ place: SavedPlace) -> ManualLocationSelection {
        ManualLocationSelection(
            name: place.name,
            address: place.address,
            coordinate: place.coordinate,
            source: .savedPlace,
            savedPlaceID: place.id,
            visitPlaceSource: .userEntered
        )
    }
}

struct PlaceSelectionView: View {
    @Environment(\.dismiss) private var dismiss
    @StateObject private var searchModel: PlaceSearchViewModel
    @AppStorage("allowNetworkGeocoding") private var allowNetworkGeocoding = true

    private let initialRegion: MKCoordinateRegion?
    private let geocodingService: GeocodingService
    private let onSelect: (ManualLocationSelection) -> Void

    @State private var selection: ManualLocationSelection?
    @State private var cameraPosition: MapCameraPosition
    @State private var visibleRegion: MKCoordinateRegion?
    @State private var reverseGeocodingTask: Task<Void, Never>?
    @State private var isReverseGeocoding = false

    init(
        initialSelection: ManualLocationSelection? = nil,
        initialRegion: MKCoordinateRegion? = nil,
        searchService: any PlaceSearchServicing = PlaceSearchService.shared,
        geocodingService: GeocodingService = GeocodingService(),
        onSelect: @escaping (ManualLocationSelection) -> Void
    ) {
        self.initialRegion = initialRegion
        self.geocodingService = geocodingService
        self.onSelect = onSelect
        _selection = State(initialValue: initialSelection)
        _searchModel = StateObject(wrappedValue: PlaceSearchViewModel(service: searchService))

        if let initialSelection {
            _cameraPosition = State(initialValue: .region(Self.region(around: initialSelection.coordinate)))
        } else if let initialRegion {
            _cameraPosition = State(initialValue: .region(initialRegion))
        } else {
            _cameraPosition = State(initialValue: .automatic)
        }
    }

    var body: some View {
        NavigationStack {
            List {
                Section {
                    locationMap
                        .frame(height: 240)
                        .clipShape(RoundedRectangle(cornerRadius: 18))
                        .overlay(RoundedRectangle(cornerRadius: 18).strokeBorder(TE.border, lineWidth: 1))
                        .listRowInsets(EdgeInsets())
                        .listRowBackground(Color.clear)

                    Button {
                        guard let mapCenterCoordinate else { return }
                        selectMapPin(at: mapCenterCoordinate)
                    } label: {
                        Label("Drop Pin at Map Center", systemImage: "mappin")
                            .font(.subheadline.weight(.medium))
                            .foregroundStyle(mapCenterCoordinate == nil ? TE.textMuted : TE.textPrimary)
                            .frame(maxWidth: .infinity, minHeight: 48)
                            .background(RoundedRectangle(cornerRadius: 14).fill(TE.card))
                            .overlay(RoundedRectangle(cornerRadius: 14).strokeBorder(TE.border, lineWidth: 1))
                    }
                    .buttonStyle(.plain)
                    .disabled(mapCenterCoordinate == nil)
                    .listRowInsets(EdgeInsets(top: 10, leading: 0, bottom: 0, trailing: 0))
                    .listRowBackground(Color.clear)
                } footer: {
                    Text("Tap the map to place the pin exactly where you want it.")
                        .font(.caption)
                        .foregroundStyle(TE.textMuted)
                }
                .listRowSeparator(.hidden)

                if let selection {
                    Section {
                        selectedLocationRow(selection)
                    } header: {
                        sectionHeader("SELECTED LOCATION")
                    }
                    .listRowBackground(TE.card)
                }

                searchStateContent
                    .listRowBackground(TE.card)
            }
            .listStyle(.insetGrouped)
            .scrollContentBackground(.hidden)
            .background(TE.surface.ignoresSafeArea())
            .foregroundStyle(TE.textPrimary)
            .tint(TE.inkRust)
            .navigationTitle("Choose Location")
            .navigationBarTitleDisplayMode(.inline)
            .searchable(text: $searchModel.query, prompt: "Search places or addresses")
            .onSubmit(of: .search) {
                searchModel.submit(region: searchRegion)
            }
            .onChange(of: searchModel.query) { _, _ in
                searchModel.queryChanged(region: searchRegion)
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Use") {
                        guard let selection else { return }
                        onSelect(selection)
                        dismiss()
                    }
                    .disabled(selection?.coordinateIsValid != true)
                    .accessibilityLabel("Use This Location")
                }
            }
            .onChange(of: allowNetworkGeocoding) { _, allowed in
                if !allowed {
                    searchModel.clear()
                    reverseGeocodingTask?.cancel()
                    isReverseGeocoding = false
                }
            }
            .onDisappear {
                searchModel.clear()
                reverseGeocodingTask?.cancel()
            }
        }
    }

    private var searchRegion: MKCoordinateRegion? {
        visibleRegion ?? initialRegion
    }

    private var mapCenterCoordinate: CLLocationCoordinate2D? {
        visibleRegion?.center ?? initialRegion?.center ?? selection?.coordinate
    }

    private var locationMap: some View {
        MapReader { proxy in
            Map(position: $cameraPosition) {
                if let selection, selection.coordinateIsValid {
                    Marker(
                        selection.name.isEmpty ? "Selected Location" : selection.name,
                        systemImage: "mappin",
                        coordinate: selection.coordinate
                    )
                    .tint(TE.inkRust)
                }
            }
            .mapStyle(.standard(elevation: .flat))
            .mapControls {
                MapCompass()
                MapScaleView()
            }
            .onMapCameraChange(frequency: .onEnd) { context in
                visibleRegion = context.region
            }
            .onTapGesture { point in
                guard let coordinate = proxy.convert(point, from: .local) else { return }
                selectMapPin(at: coordinate)
            }
            .accessibilityLabel("Location selection map")
            .accessibilityHint("Search for an accessible list of places, or tap the map to move the selected pin.")
        }
    }

    @ViewBuilder
    private var searchStateContent: some View {
        let trimmedQuery = searchModel.query.trimmingCharacters(in: .whitespacesAndNewlines)

        if !allowNetworkGeocoding {
            Section {
                Text(NetworkGeocodingPolicy.LookupError.disabled.localizedDescription)
            }
        } else if searchModel.isSearching {
            Section {
                HStack(spacing: 12) {
                    ProgressView()
                    Text("Searching Apple Maps…")
                        .font(TE.editorial(.body).italic())
                        .foregroundStyle(TE.textMuted)
                }
            }
        } else if let errorMessage = searchModel.errorMessage {
            Section {
                ContentUnavailableView(
                    "Search Unavailable",
                    systemImage: "wifi.exclamationmark",
                    description: Text(errorMessage)
                )
                Button("Try Again") {
                    searchModel.submit(region: searchRegion)
                }
            }
        } else if !searchModel.results.isEmpty {
            Section {
                ForEach(searchModel.results) { result in
                    Button {
                        selectSearchResult(result)
                    } label: {
                        VStack(alignment: .leading, spacing: 3) {
                            Text(result.name)
                                .font(.subheadline.weight(.medium))
                                .foregroundStyle(TE.textPrimary)
                            if let address = result.address, !address.isEmpty {
                                Text(address)
                                    .font(TE.mono(.caption2))
                                    .foregroundStyle(TE.textMuted)
                            }
                        }
                        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        .contentShape(Rectangle())
                    }
                    .buttonStyle(.plain)
                    .accessibilityLabel(result.name)
                    .accessibilityValue(result.address ?? "")
                    .accessibilityHint("Selects this location.")
                }
            } header: {
                sectionHeader("SEARCH RESULTS")
            }
        } else if trimmedQuery.count >= 2 {
            Section {
                ContentUnavailableView.search(text: trimmedQuery)
            }
        } else {
            Section {
                ContentUnavailableView(
                    "Find a Location",
                    systemImage: "magnifyingglass",
                    description: Text("Search for a business, landmark, or street address. You can also navigate and tap the map.")
                )
            }
        }
    }

    private func selectedLocationRow(_ selection: ManualLocationSelection) -> some View {
        VStack(alignment: .leading, spacing: 5) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Image(systemName: selection.source == .mapPin ? "mappin" : "checkmark.circle.fill")
                    .foregroundStyle(TE.inkRust)
                    .accessibilityHidden(true)
                Text(selection.name.isEmpty ? "Pinned location" : selection.name)
                    .font(TE.editorial(.title3))
            }

            if let address = selection.address, !address.isEmpty {
                Text(address)
                    .font(.subheadline)
                    .foregroundStyle(TE.textMuted)
            } else if isReverseGeocoding {
                HStack(spacing: 6) {
                    ProgressView()
                        .controlSize(.small)
                    Text("Finding address…")
                        .font(.subheadline)
                        .foregroundStyle(TE.textMuted)
                }
            } else {
                Text("Add a name after selecting this location.")
                    .font(.subheadline)
                    .foregroundStyle(TE.textMuted)
            }

            Text(String(
                format: "%.6f, %.6f",
                selection.coordinate.latitude,
                selection.coordinate.longitude
            ))
            .font(TE.mono(.caption2))
            .foregroundStyle(TE.textMuted)
        }
        .padding(.vertical, 4)
        .accessibilityElement(children: .combine)
    }

    private func sectionHeader(_ title: LocalizedStringKey) -> some View {
        Text(title)
            .font(TE.mono(.caption2))
            .tracking(1.5)
            .foregroundStyle(TE.textMuted)
    }

    private func selectSearchResult(_ result: PlaceSearchResult) {
        reverseGeocodingTask?.cancel()
        isReverseGeocoding = false
        selection = .appleMapsResult(result)
        cameraPosition = .region(Self.region(around: result.coordinate))
    }

    private func selectMapPin(at coordinate: CLLocationCoordinate2D) {
        guard CLLocationCoordinate2DIsValid(coordinate),
              coordinate.latitude.isFinite,
              coordinate.longitude.isFinite else {
            return
        }

        reverseGeocodingTask?.cancel()

        // Moving the pin starts a new selection. Do not carry a POI's metadata
        // onto a neighboring coordinate while reverse geocoding is in flight.
        selection = ManualLocationSelection(
            name: "",
            address: nil,
            coordinate: coordinate,
            source: .mapPin,
            visitPlaceSource: .userEntered
        )

        guard allowNetworkGeocoding else {
            isReverseGeocoding = false
            return
        }
        isReverseGeocoding = true
        let requestedLocation = CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude)
        reverseGeocodingTask = Task {
            do {
                let result = try await geocodingService.reverseGeocode(location: requestedLocation)
                try Task.checkCancellation()
                guard allowNetworkGeocoding, let currentSelection = selection,
                      Self.coordinatesMatch(currentSelection.coordinate, coordinate) else {
                    return
                }

                let hasGeocodedMetadata = result.name?.isEmpty == false || result.address?.isEmpty == false
                selection = ManualLocationSelection(
                    name: result.name ?? currentSelection.name,
                    address: result.address?.isEmpty == false ? result.address : currentSelection.address,
                    coordinate: coordinate,
                    source: .mapPin,
                    visitPlaceSource: hasGeocodedMetadata ? .coreLocationGeocode : .userEntered
                )
            } catch is CancellationError {
                // The pin moved again before this lookup completed.
            } catch {
                // The coordinate remains valid even when an address cannot be found.
            }

            if let currentSelection = selection,
               Self.coordinatesMatch(currentSelection.coordinate, coordinate) {
                isReverseGeocoding = false
            }
        }
    }

    private static func coordinatesMatch(
        _ lhs: CLLocationCoordinate2D,
        _ rhs: CLLocationCoordinate2D
    ) -> Bool {
        abs(lhs.latitude - rhs.latitude) < 0.000_001 &&
        abs(lhs.longitude - rhs.longitude) < 0.000_001
    }

    private static func region(around coordinate: CLLocationCoordinate2D) -> MKCoordinateRegion {
        MKCoordinateRegion(
            center: coordinate,
            latitudinalMeters: 1_000,
            longitudinalMeters: 1_000
        )
    }
}
