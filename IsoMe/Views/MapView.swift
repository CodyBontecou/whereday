import SwiftUI
import MapKit
import SwiftData
import Combine

struct LocationMapView: View {
    @Environment(ManualVisitRouter.self) private var manualVisitRouter
    @State private var deepLinkedVisit: Visit?
    @State private var handledVisitRouteID: UUID?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Bindable var viewModel: LocationViewModel
    var focusedVisit: Visit? = nil
    var onSelectVisit: ((Visit) -> Void)? = nil
    @ObservedObject private var locationManager: LocationManager
    @State private var selectedVisit: Visit?
    @State private var selectedPointID: UUID?
    @State private var lastPointMarkerTap = Date.distantPast
    @State private var showingFilters = false
    @State private var manualVisitDraft: ManualVisitDraft?
    @State private var currentPlaceSuggestion: NearbyPlaceSuggestion?
    @State private var isLoadingCurrentPlace = false
    @State private var currentPlaceLookupFailed = false
    @State private var dismissedCurrentPlaceLookupKey: String?
    @State private var dismissedCurrentPlaceArea: CurrentPlacePromptSuppression?
    @State private var showFilterBar = Self.initialFilterBarVisibility
    @State private var trackingPillExpanded = false
    @State private var showTravelPath = true
    @State private var showPointMarkers = true
    @State private var showStartEndMarkers = true
    @State private var showSessionPath = false
    @State private var showVisitMarkers = true
    @AppStorage(LocationViewModel.showPhotoMarkersKey) private var showPhotoMarkers = false
    @AppStorage(LocationViewModel.showVisitSuggestionsKey) private var showVisitSuggestions = false
    @AppStorage(PhotoMapMarkerStyle.imagesKey) private var showPhotoMarkerImages = true
    @AppStorage(PhotoMapMarkerStyle.dotsKey) private var showPhotoMarkerDots = false
    @AppStorage("snapTravelPathToRoads") private var snapTravelPathToRoads = true
    @AppStorage("showStraightLinePathSegments") private var showStraightLinePathSegments = false
    @State private var roadSnappedRoute: RoadSnappedRoute?
    @State private var isRouteReplayEnabled = false
    @State private var isRouteReplayPlaying = false
    @State private var routeReplayProgress: Double = 1.0
    @State private var cameraPosition: MapCameraPosition = .userLocation(fallback: .automatic)
    @State private var visibleMapRegion: MKCoordinateRegion?
    @State private var visibleMapRect: MKMapRect?
    @State private var mapViewportSize: CGSize = .zero
    @State private var pendingSessionAutoFocus = false
    @State private var appliedMapFocusRequestID: UUID?
    @State private var activePreset: MapDatePreset? = .today
    @AppStorage("showOutliers") private var showOutliers = false
    @AppStorage("photoPlacesPromptDismissed") private var photoPlacesPromptDismissed = false

    init(viewModel: LocationViewModel, focusedVisit: Visit? = nil, onSelectVisit: ((Visit) -> Void)? = nil) {
        self.viewModel = viewModel
        self.focusedVisit = focusedVisit
        self.onSelectVisit = onSelectVisit
        self.locationManager = viewModel.locationManager
    }

    private static var initialFilterBarVisibility: Bool {
        #if DEBUG
        ProcessInfo.processInfo.arguments.contains("--demo-open-map-filters")
        #else
        false
        #endif
    }

    private var isTracking: Bool {
        locationManager.isTrackingEnabled
    }

    private let routeReplayTimer = Timer.publish(every: 0.25, on: .main, in: .common).autoconnect()

    var filteredVisits: [Visit] {
        viewModel.visitsInDateRange(viewModel.mapDateRange)
    }

    var filteredPoints: [LocationPoint] {
        let points = viewModel.mapLocationPoints
        return showOutliers ? points : points.filter { !$0.isOutlier }
    }

    var filteredPhotoMoments: [PhotoMoment] {
        guard showPhotoMarkers, viewModel.photoLibraryAccessState.canRead else { return [] }
        return viewModel.mapAccessiblePhotoMoments
    }

    var photoMomentClusters: [PhotoMomentCluster] {
        guard showPhotoMarkers, viewModel.photoLibraryAccessState.canRead else { return [] }
        return viewModel.mapPhotoMomentClusters
    }

    var activeSessionPoints: [LocationPoint] {
        guard locationManager.isTrackingEnabled else { return [] }
        let points = viewModel.sessionMapLocationPoints
        return showOutliers ? points : points.filter { !$0.isOutlier }
    }

    var canReplayRoute: Bool {
        routeReplaySourcePoints.count >= 2
    }

    var routeReplaySourcePoints: [LocationPoint] {
        filteredPoints
    }

    private var freshManualLocation: CLLocation? {
        guard let location = locationManager.currentLocation else { return nil }
        let age = Date().timeIntervalSince(location.timestamp)
        guard age <= 5 * 60 else { return nil }
        guard location.horizontalAccuracy >= 0, location.horizontalAccuracy <= 100 else { return nil }
        return location
    }

    private var currentPlaceLookupKey: String {
        guard showVisitSuggestions else { return "disabled" }

        guard let location = freshManualLocation else {
            return "unavailable-\(locationManager.authorizationStatus.rawValue)"
        }

        let latitude = (location.coordinate.latitude * 10000).rounded() / 10000
        let longitude = (location.coordinate.longitude * 10000).rounded() / 10000
        let accuracy = Int(location.horizontalAccuracy.rounded())
        return "\(latitude),\(longitude),\(accuracy)"
    }

    private var currentPlaceSuggestionTaskKey: String {
        let savedPlaceRevision = viewModel.savedPlaces
            .map { "\($0.id.uuidString):\($0.updatedAt.timeIntervalSinceReferenceDate)" }
            .joined(separator: ",")
        return "\(currentPlaceLookupKey)|saved:\(savedPlaceRevision)"
    }

    private var shouldShowCurrentPlacePrompt: Bool {
        showVisitSuggestions &&
        dismissedCurrentPlaceLookupKey != currentPlaceLookupKey &&
        !isCurrentPlacePromptSuppressedInCurrentArea
    }

    private var isCurrentPlacePromptSuppressedInCurrentArea: Bool {
        guard let dismissedCurrentPlaceArea,
              let location = freshManualLocation else {
            return false
        }

        return dismissedCurrentPlaceArea.contains(location.coordinate)
    }

    private var roadSnappingSourceFingerprint: Int {
        RoadSnappedRouteBuilder.fingerprint(for: filteredPoints)
    }

    private var roadSnappingTaskKey: Int {
        RoadSnappedRouteBuilder.taskFingerprint(
            for: filteredPoints,
            isEnabled: snapTravelPathToRoads
        )
    }

    private var preparedRoadSnappedRoute: RoadSnappedRoute? {
        guard snapTravelPathToRoads,
              let roadSnappedRoute,
              roadSnappedRoute.sourceFingerprint == roadSnappingSourceFingerprint,
              roadSnappedRoute.sourcePointCount == filteredPoints.count else {
            return nil
        }

        return roadSnappedRoute
    }

    private var shouldDrawStraightLinePath: Bool {
        showStraightLinePathSegments || !snapTravelPathToRoads
    }

    var routeReplaySnapshot: RouteReplaySnapshot? {
        RouteReplayCalculator.snapshot(points: routeReplaySourcePoints, progress: routeReplayProgress)
    }

    var displayedTravelPathPoints: [LocationPoint] {
        if isRouteReplayEnabled, let routeReplaySnapshot {
            return routeReplaySnapshot.visiblePoints
        }
        return filteredPoints
    }

    private func displayedRoadSegments(
        from route: RoadSnappedRoute,
        upTo sourceIndex: Int? = nil
    ) -> [RoadSnappedRouteSegment] {
        let segments: [RoadSnappedRouteSegment]
        if let sourceIndex {
            segments = route.segments(upTo: sourceIndex)
        } else {
            segments = route.segments
        }

        guard !showStraightLinePathSegments else { return segments }
        return segments.filter(\.isSnapped)
    }

    var spacedPoints: [LocationPoint] {
        MapPointMarkerSampler.sample(
            displayedTravelPathPoints,
            in: visibleMapRect,
            viewportSize: mapViewportSize,
            selectedPointID: selectedPointID
        )
    }

    var body: some View {
        @Bindable var photoDetails = viewModel.photoDetails
        NavigationStack {
            mapWithPresentations
            .onAppear {
                viewModel.loadAllVisits()
                if !applyMapFocusRequestIfNeeded() {
                    if let activePreset {
                        let range = activePreset.range()
                        viewModel.mapDateRange = range
                        viewModel.loadMapLocationPoints(in: range)
                        viewModel.loadMapPhotoMoments(in: range)
                    } else {
                        viewModel.loadMapLocationPoints(in: viewModel.mapDateRange)
                        viewModel.loadMapPhotoMoments(in: viewModel.mapDateRange)
                    }
                }

                syncPhotoMomentsForCurrentRangeIfAuthorized()
                validateRouteReplayState()

                if locationManager.isTrackingEnabled {
                    pendingSessionAutoFocus = true
                    attemptAutoFocusSession()
                }
            }
            .task(id: roadSnappingTaskKey) {
                await refreshRoadSnappedRoute()
            }
            .task(id: currentPlaceSuggestionTaskKey) {
                await refreshCurrentPlaceSuggestion()
            }
            .onReceive(routeReplayTimer) { _ in
                advanceRouteReplayIfNeeded()
            }
            .onReceive(NotificationCenter.default.publisher(for: .appDidBecomeActive)) { _ in
                viewModel.refreshPhotoLibraryAuthorizationState()
                viewModel.loadMapPhotoMoments(in: viewModel.mapDateRange)
                syncPhotoMomentsForCurrentRangeIfAuthorized()
            }
            .onChange(of: showPhotoMarkers) { _, isEnabled in
                if isEnabled {
                    requestPhotoMomentsForCurrentRange()
                } else {
                    photoDetails.dismissAll()
                }
            }
            .onChange(of: filteredPoints.count) { _, _ in
                validateRouteReplayState()
            }
            .onChange(of: viewModel.allVisits.map(\.id)) { _, visitIDs in
                guard let selectedVisit, !visitIDs.contains(selectedVisit.id) else { return }
                self.selectedVisit = nil
            }
            .onChange(of: showOutliers) { _, _ in
                validateRouteReplayState()
            }
            .onChange(of: viewModel.mapFocusRequest?.id) { _, _ in
                _ = applyMapFocusRequestIfNeeded()
            }
            .onChange(of: locationManager.isTrackingEnabled) { _, isEnabled in
                pendingSessionAutoFocus = isEnabled
                if isEnabled {
                    attemptAutoFocusSession()
                }
            }
            .onChange(of: activeSessionPoints.count) { _, _ in
                if pendingSessionAutoFocus {
                    attemptAutoFocusSession()
                }
            }
        }
    }

    private var mapWithPresentations: some View {
        @Bindable var photoDetails = viewModel.photoDetails
        return mapCanvas
        .toolbar(.hidden, for: .navigationBar)
        .onChange(of: manualVisitRouter.request?.id, initial: true) { _, id in
            guard id != nil else { return }
            openManualVisitRoute()
        }
        .onChange(of: canPresentVisitRoute) { _, ready in
            if ready { openManualVisitRoute() }
        }
        .sheet(item: $deepLinkedVisit) { visit in
            NavigationStack { VisitDetailView(visit: visit, viewModel: viewModel) }
        }
        .sheet(item: $manualVisitDraft) { draft in
            ManualVisitSheet(
                viewModel: viewModel,
                locationManager: locationManager,
                draft: draft,
                onSaved: { visit in
                    if draft.hidesCurrentPlacePromptAfterSave {
                        suppressCurrentPlacePromptForCurrentArea()
                    }
                    handleManualVisitSaved(visit, mode: draft.mode)
                }
            )
            .presentationDetents([.medium, .large])
        }
        .sheet(isPresented: $showingFilters) {
            DateRangeFilterSheet(
                dateRange: $viewModel.mapDateRange,
                isPresented: $showingFilters,
                onApply: {
                    activePreset = nil
                    viewModel.loadMapLocationPoints(in: viewModel.mapDateRange)
                    viewModel.loadMapPhotoMoments(in: viewModel.mapDateRange)
                    syncPhotoMomentsForCurrentRangeIfAuthorized()
                    validateRouteReplayState()
                }
            )
        }
        .onChange(of: selectedVisit?.id) { _, _ in
            showSelectedVisitInCompanion()
        }
        .onChange(of: focusedVisit?.id, initial: true) { _, _ in
            focusCompanionVisit()
        }
        .sheet(item: selectedVisitSheet) { visit in
            VisitQuickView(visit: visit, viewModel: viewModel)
                .presentationDetents([.medium, .large])
        }
        .sheet(item: $photoDetails.cluster) { cluster in
            if cluster.isArea {
                PhotoMomentPlacesView(places: cluster.places, title: "Photos in This Area", selection: photoDetails)
            } else {
                PhotoMomentClusterQuickView(cluster: cluster, selection: photoDetails)
                    .presentationDetents([.medium, .large])
            }
        }
        .sheet(isPresented: $photoDetails.showsPlaces) {
            PhotoMomentPlacesView(places: viewModel.mapPhotoPlaces, title: "All Photo Places", selection: photoDetails)
        }
        .fullScreenCover(item: $photoDetails.photo) { photo in
            PhotoMomentFullScreenView(photo: photo)
        }
    }

    private var mapCanvas: some View {
        @Bindable var photoDetails = viewModel.photoDetails
        return ZStack {
            MapReader { mapProxy in
            Map(position: $cameraPosition, selection: $selectedVisit) {
                // Current user location
                UserAnnotation()

                // Travel path from location points. In replay mode, keep the
                // full route hidden and only draw the segment the playhead has reached.
                // When road snapping is enabled, sparse GPS gaps are replaced with
                // MapKit route polylines so the path follows roads instead of drawing
                // abrupt straight chords between disconnected dots.
                if isRouteReplayEnabled, let routeReplaySnapshot {
                    if let preparedRoadSnappedRoute {
                        ForEach(displayedRoadSegments(from: preparedRoadSnappedRoute, upTo: routeReplaySnapshot.index)) { segment in
                            MapPolyline(coordinates: segment.coordinates)
                                .stroke(
                                    LinearGradient(
                                        colors: [.blue.opacity(0.3), .blue.opacity(0.7), .blue],
                                        startPoint: .leading,
                                        endPoint: .trailing
                                    ),
                                    style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round)
                                )
                        }
                    } else if shouldDrawStraightLinePath, routeReplaySnapshot.visiblePoints.count >= 2 {
                        let coordinates = routeReplaySnapshot.visiblePoints.map { $0.coordinate }
                        MapPolyline(coordinates: coordinates)
                            .stroke(
                                LinearGradient(
                                    colors: [.blue.opacity(0.3), .blue.opacity(0.7), .blue],
                                    startPoint: .leading,
                                    endPoint: .trailing
                                ),
                                style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round)
                            )
                    }
                } else if showTravelPath && filteredPoints.count >= 2 {
                    if let preparedRoadSnappedRoute {
                        ForEach(displayedRoadSegments(from: preparedRoadSnappedRoute)) { segment in
                            MapPolyline(coordinates: segment.coordinates)
                                .stroke(
                                    LinearGradient(
                                        colors: [.blue.opacity(0.3), .blue.opacity(0.7), .blue],
                                        startPoint: .leading,
                                        endPoint: .trailing
                                    ),
                                    style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round)
                                )
                        }
                    } else if shouldDrawStraightLinePath {
                        let coordinates = filteredPoints.map { $0.coordinate }
                        MapPolyline(coordinates: coordinates)
                            .stroke(
                                LinearGradient(
                                    colors: [.blue.opacity(0.3), .blue.opacity(0.7), .blue],
                                    startPoint: .leading,
                                    endPoint: .trailing
                                ),
                                style: StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round)
                            )
                    }
                }

                if isRouteReplayEnabled, let routeReplaySnapshot {
                    Annotation(String(localized: "Route replay position"), coordinate: routeReplaySnapshot.currentPoint.coordinate) {
                        RouteReplayMarker(snapshot: routeReplaySnapshot)
                    }
                }

                // Live session path (moved from Track tab)
                if showSessionPath && activeSessionPoints.count >= 2 {
                    let sessionCoordinates = activeSessionPoints.map { $0.coordinate }
                    MapPolyline(coordinates: sessionCoordinates)
                        .stroke(.blue, style: StrokeStyle(lineWidth: 5, lineCap: .round, lineJoin: .round))
                }

                if showSessionPath, let firstSessionPoint = activeSessionPoints.first {
                    Annotation(String(localized: "Path start"), coordinate: firstSessionPoint.coordinate) {
                        StartMarker(
                            accessibilityLabel: String(localized: "Active session start"),
                            accessibilityValue: firstSessionPoint.accessibilityValue
                        )
                    }
                }

                if showSessionPath,
                   let lastSessionPoint = activeSessionPoints.last,
                   activeSessionPoints.count > 1 {
                    Annotation(String(localized: "Current location"), coordinate: lastSessionPoint.coordinate) {
                        CurrentLocationMarker(
                            accessibilityLabel: String(localized: "Active session current location"),
                            accessibilityValue: lastSessionPoint.accessibilityValue
                        )
                    }
                }

                // Start marker (oldest point in range)
                if showStartEndMarkers, let firstPoint = filteredPoints.first {
                    Annotation("", coordinate: firstPoint.coordinate) {
                        PathStartMarker(timestamp: firstPoint.timestamp)
                    }
                }

                // End marker (newest point in range, if different from start)
                if showStartEndMarkers,
                   let lastPoint = filteredPoints.last,
                   filteredPoints.count > 1 {
                    Annotation("", coordinate: lastPoint.coordinate) {
                        PathEndMarker(timestamp: lastPoint.timestamp)
                    }
                }

                // Point markers (spaced apart)
                if showPointMarkers {
                    ForEach(spacedPoints) { point in
                        Annotation("", coordinate: point.coordinate) {
                            PathPointMarker(
                                point: point,
                                isSelected: selectedPointID == point.id,
                                onTap: { togglePointTooltip(for: point) }
                            )
                        }
                    }
                }

                // Visit markers
                if showVisitMarkers {
                    ForEach(filteredVisits) { visit in
                        Annotation(
                            visit.displayName,
                            coordinate: visit.coordinate,
                            anchor: .bottom
                        ) {
                            Button {
                                selectedPointID = nil
                                selectedVisit = visit
                            } label: {
                                VisitMarker(
                                    visit: visit,
                                    isSelected: selectedVisit?.id == visit.id || focusedVisit?.id == visit.id,
                                    isCurrentVisit: viewModel.isCurrentVisit(visit)
                                )
                            }
                            .buttonStyle(.plain)
                        }
                        .tag(visit)
                    }
                }

                // Photo markers. Nearby photos are grouped so stacked shots
                // taken at the same place open a navigable picker instead of
                // competing for the same tap target.
                ForEach(photoMomentClusters) { cluster in
                    if let photo = cluster.onlyPhoto {
                        Annotation(
                            "Photo",
                            coordinate: photo.coordinate,
                            anchor: showPhotoMarkerDots ? .center : .bottom
                        ) {
                            PhotoMomentMapMarker(
                                photo: photo,
                                isSelected: photoDetails.photo?.id == photo.id,
                                showsImage: showPhotoMarkerImages,
                                showsDots: showPhotoMarkerDots,
                                onDotTap: { point in
                                    selectPhotoDot(at: point, relativeTo: cluster, proxy: mapProxy)
                                },
                                action: { photoDetails.photo = photo }
                            )
                        }
                    } else {
                        Annotation(
                            cluster.isArea ? "Photo Area" : "Photos",
                            coordinate: cluster.coordinate,
                            anchor: showPhotoMarkerDots ? .center : .bottom
                        ) {
                            PhotoMomentClusterMapMarker(
                                cluster: cluster,
                                isSelected: photoDetails.cluster?.id == cluster.id,
                                showsImage: showPhotoMarkerImages,
                                showsDots: showPhotoMarkerDots,
                                onDotTap: { point in
                                    selectPhotoDot(at: point, relativeTo: cluster, proxy: mapProxy)
                                },
                                action: { photoDetails.cluster = cluster }
                            )
                        }
                    }
                }
            }
            .mapControls {
                MapUserLocationButton()
                MapCompass()
                MapScaleView()
            }
            .onMapCameraChange(frequency: .onEnd) { context in
                visibleMapRegion = context.region
                visibleMapRect = context.rect
            }
            .background {
                GeometryReader { geometry in
                    Color.clear
                        .onAppear { mapViewportSize = geometry.size }
                        .onChange(of: geometry.size) { _, size in
                            mapViewportSize = size
                        }
                }
            }
            .simultaneousGesture(
                SpatialTapGesture().onEnded { value in
                    handleMapTap()
                    guard showPhotoMarkerDots else { return }
                    selectPhotoDot(at: value.location, proxy: mapProxy)
                }
            )
            .accessibilityLabel("Location map")
            .accessibilityValue(mapAccessibilitySummary)

            }

            // Bottom liquid-glass tracking + filter controls
            VStack(spacing: 8) {
                Spacer()

                if showPhotoMarkers, !viewModel.mapPhotoPlaces.isEmpty, !photoPlacesPromptDismissed {
                    HStack(spacing: 0) {
                        Button {
                            photoDetails.showsPlaces = true
                        } label: {
                            Label("Browse \(viewModel.mapPhotoPlaces.count) photo places", systemImage: "photo.stack")
                                .font(TE.mono(.caption, weight: .semibold))
                                .padding(12)
                        }
                        .accessibilityHint("Lists every matched place, including places outside the visible map area.")

                        Button {
                            photoPlacesPromptDismissed = true
                        } label: {
                            Image(systemName: "xmark")
                                .font(.caption.weight(.semibold))
                                .foregroundStyle(TE.textMuted)
                                .frame(width: 44, height: 44)
                                .contentShape(Rectangle())
                        }
                        .accessibilityLabel("Close")
                    }
                    .buttonStyle(.plain)
                    .background(TE.card, in: Capsule())
                    .frame(maxWidth: .infinity, alignment: .trailing)
                }

                if isRouteReplayEnabled, let routeReplaySnapshot {
                    RouteReplayControl(
                        snapshot: routeReplaySnapshot,
                        pointCount: routeReplaySourcePoints.count,
                        progress: Binding(
                            get: { routeReplayProgress },
                            set: { routeReplayProgress = RouteReplayCalculator.clampedProgress($0) }
                        ),
                        isPlaying: isRouteReplayPlaying,
                        onPlayPause: toggleRouteReplayPlayback,
                        onScrub: { isRouteReplayPlaying = false },
                        onClose: disableRouteReplay
                    )
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }

                if shouldShowCurrentPlacePrompt {
                    CurrentPlaceCard(
                        suggestion: currentPlaceSuggestion,
                        isLoading: isLoadingCurrentPlace,
                        lookupFailed: currentPlaceLookupFailed,
                        hasFreshLocation: freshManualLocation != nil,
                        hasLocationPermission: locationManager.hasLocationPermission,
                        onSave: openSaveCurrentPlaceSheet,
                        onNotHere: openBlankManualVisitSheet,
                        onRequestLocation: locationManager.requestCurrentLocationForManualVisit,
                        onDismiss: dismissCurrentPlacePrompt
                    )
                    .frame(maxWidth: .infinity, alignment: .trailing)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
                }

                MapTrackingControlPill(
                    viewModel: viewModel,
                    locationManager: locationManager,
                    isTracking: isTracking,
                    isExpanded: $trackingPillExpanded,
                    onPrimaryTap: handleTrackingTap
                )
                .frame(maxWidth: .infinity, alignment: .trailing)

                if isTracking,
                   let remaining = locationManager.remainingTime {
                    MapAutoOffPill(remaining: remaining)
                        .frame(maxWidth: .infinity, alignment: .trailing)
                        .transition(.opacity.combined(with: .move(edge: .bottom)))
                }

                HStack(spacing: 8) {
                    if showFilterBar {
                        QuickFilterBar(
                            activePreset: activePreset,
                            showTravelPath: $showTravelPath,
                            showPointMarkers: $showPointMarkers,
                            showStartEndMarkers: $showStartEndMarkers,
                            showSessionPath: $showSessionPath,
                            showVisitMarkers: $showVisitMarkers,
                            showPhotoMarkers: $showPhotoMarkers,
                            showPhotoMarkerImages: $showPhotoMarkerImages,
                            showPhotoMarkerDots: $showPhotoMarkerDots,
                            snapTravelPathToRoads: $snapTravelPathToRoads,
                            showStraightLinePathSegments: $showStraightLinePathSegments,
                            isRouteReplayEnabled: isRouteReplayEnabled,
                            canReplayRoute: canReplayRoute,
                            hasSessionPoints: !activeSessionPoints.isEmpty,
                            onSelectPreset: { preset in
                                activePreset = preset
                                let range = preset.range()
                                viewModel.mapDateRange = range
                                viewModel.loadMapLocationPoints(in: range)
                                viewModel.loadMapPhotoMoments(in: range)
                                syncPhotoMomentsForCurrentRangeIfAuthorized()
                                validateRouteReplayState()
                            },
                            onSelectCustom: {
                                showingFilters = true
                            },
                            onToggleRouteReplay: toggleRouteReplayMode,
                            onFitContent: { fitMapToContent() },
                            onFitSession: !activeSessionPoints.isEmpty ? { fitMapToSession() } : nil
                        )
                        .transition(.asymmetric(
                            insertion: .move(edge: .trailing).combined(with: .opacity),
                            removal: .move(edge: .trailing).combined(with: .opacity)
                        ))
                    } else {
                        Spacer(minLength: 0)
                    }

                    if !showFilterBar {
                        MapAddPlaceMenu(
                            onSaveCurrentPlace: openSaveCurrentPlaceSheet,
                            onAddPastVisit: openPastVisitSheet,
                            onDropPin: openBlankManualVisitSheet
                        )
                        .transition(.scale(scale: 0.9).combined(with: .opacity))
                    }

                    FilterBarToggle(isOpen: showFilterBar) {
                        withAnimation(reduceMotion ? nil : .spring(response: 0.4, dampingFraction: 0.82)) {
                            showFilterBar.toggle()
                        }
                    }
                }
            }
            .padding(.horizontal, 12)
            .padding(.bottom, 8)
            .animation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.82), value: isTracking)
            .animation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.82), value: isRouteReplayEnabled)
            .onChange(of: isTracking) { _, newValue in
                if !newValue {
                    withAnimation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.82)) {
                        trackingPillExpanded = false
                    }
                }
            }
        }
    }

    private func dismissCurrentPlacePrompt() {
        withAnimation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.82)) {
            suppressCurrentPlacePromptForCurrentArea()
        }
    }

    private func suppressCurrentPlacePromptForCurrentArea() {
        dismissedCurrentPlaceLookupKey = currentPlaceLookupKey

        if let location = freshManualLocation {
            dismissedCurrentPlaceArea = CurrentPlacePromptSuppression(center: location.coordinate)
        }
    }

    private var selectedVisitSheet: Binding<Visit?> {
        Binding<Visit?>(
            get: { onSelectVisit == nil ? selectedVisit : nil },
            set: { selectedVisit = $0 }
        )
    }

    private func showSelectedVisitInCompanion() {
        guard let selectedVisit, let onSelectVisit else { return }
        onSelectVisit(selectedVisit)
        self.selectedVisit = nil
    }

    private func focusCompanionVisit() {
        guard let focusedVisit else { return }
        selectedPointID = nil
        let start = Calendar.current.startOfDay(for: focusedVisit.arrivedAt)
        let end = Calendar.current.date(byAdding: .day, value: 1, to: start) ?? start.addingTimeInterval(86400)
        let range = start...end
        viewModel.mapDateRange = range
        viewModel.loadMapLocationPoints(in: range)
        viewModel.loadMapPhotoMoments(in: range)
        cameraPosition = .region(MKCoordinateRegion(
            center: focusedVisit.coordinate,
            span: MKCoordinateSpan(latitudeDelta: 0.008, longitudeDelta: 0.008)
        ))
    }

    private var canPresentVisitRoute: Bool {
        manualVisitDraft == nil && selectedVisit == nil && deepLinkedVisit == nil && !showingFilters
            && viewModel.photoDetails.cluster == nil && !viewModel.photoDetails.showsPlaces && viewModel.photoDetails.photo == nil
    }

    private func openManualVisitRoute() {
        // A new link waits for the current sheet; never discard an unfinished form.
        guard canPresentVisitRoute else { return }
        guard let request = manualVisitRouter.request, request.id != handledVisitRouteID else { return }
        handledVisitRouteID = request.id
        // Let the tab selection observer see the request before consuming it.
        Task { @MainActor in
            await Task.yield()
            if manualVisitRouter.request?.id == request.id { manualVisitRouter.request = nil }
        }
        switch request.route {
        case .visit(let id):
            do {
                guard let visit = try viewModel.storedVisit(id: id) else {
                    manualVisitRouter.errorMessage = String(localized: "That Whereday item couldn't be found.")
                    return
                }
                deepLinkedVisit = visit
            } catch {
                manualVisitRouter.errorMessage = String(localized: "The visit could not be loaded. Please try again.")
            }
        case .checkIn(let name, let latitude, let longitude, let arrival):
            manualVisitDraft = routeDraft(name: name, latitude: latitude, longitude: longitude,
                arrival: arrival ?? Date(), departure: nil, isPast: false)
        case .pastVisit(let name, let latitude, let longitude, let arrival, let departure):
            let end = departure ?? Date()
            manualVisitDraft = routeDraft(name: name, latitude: latitude, longitude: longitude,
                arrival: arrival ?? end.addingTimeInterval(-3600), departure: end, isPast: true)
        }
    }

    private func routeDraft(name: String?, latitude: Double?, longitude: Double?, arrival: Date,
                            departure: Date?, isPast: Bool) -> ManualVisitDraft {
        var selection: ManualLocationSelection?
        if let latitude, let longitude {
            selection = ManualLocationSelection(name: name ?? "", address: nil,
                coordinate: CLLocationCoordinate2D(latitude: latitude, longitude: longitude), source: .mapPin)
        }
        return ManualVisitDraft(mode: isPast ? .addPastVisit : .saveCurrentPlace,
            name: name ?? "", locationSelection: selection, arrivedAt: arrival,
            departedAt: departure ?? Date(), isStillHere: !isPast,
            title: isPast ? "Add Past Visit" : "Check In", hidesCurrentPlacePromptAfterSave: !isPast)
    }

    private func openSaveCurrentPlaceSheet() {
        locationManager.requestCurrentLocationForManualVisit()

        if let suggestion = currentPlaceSuggestion {
            manualVisitDraft = ManualVisitDraft(
                mode: .saveCurrentPlace,
                name: suggestion.name,
                address: suggestion.address ?? "",
                locationSelection: ManualLocationSelection(
                    name: suggestion.name,
                    address: suggestion.address,
                    coordinate: suggestion.coordinate,
                    source: .appleMaps
                ),
                title: "Save Place"
            )
        } else {
            manualVisitDraft = ManualVisitDraft(mode: .saveCurrentPlace, title: "Save Place")
        }
    }

    private func openBlankManualVisitSheet() {
        locationManager.requestCurrentLocationForManualVisit()
        manualVisitDraft = ManualVisitDraft(mode: .addPlaceManually, title: "Add Place")
    }

    private func openPastVisitSheet() {
        let departedAt = Date()
        let arrivedAt = Calendar.current.date(byAdding: .hour, value: -1, to: departedAt) ?? departedAt
        manualVisitDraft = ManualVisitDraft(
            mode: .addPastVisit,
            arrivedAt: arrivedAt,
            departedAt: departedAt,
            isStillHere: false,
            title: "Add Past Visit",
            hidesCurrentPlacePromptAfterSave: false,
            initialSearchRegion: visibleMapRegion
        )
    }

    private func handleManualVisitSaved(_ visit: Visit, mode: ManualVisitMode) {
        let range: ClosedRange<Date>
        if mode == .addPastVisit {
            let calendar = Calendar.current
            let startOfDay = calendar.startOfDay(for: visit.arrivedAt)
            let startOfNextDay = calendar.date(byAdding: .day, value: 1, to: startOfDay) ?? visit.arrivedAt
            let endOfDay = Date(timeIntervalSinceReferenceDate: startOfNextDay.timeIntervalSinceReferenceDate.nextDown)
            range = startOfDay...endOfDay
            activePreset = nil
        } else {
            range = Self.rangeIncludingSavedVisit(visit.arrivedAt, in: viewModel.mapDateRange)
            if activePreset == .yesterday { activePreset = nil }
        }

        showVisitMarkers = true
        viewModel.mapDateRange = range
        viewModel.loadMapLocationPoints(in: range)
        viewModel.loadMapPhotoMoments(in: range)
        syncPhotoMomentsForCurrentRangeIfAuthorized()
        cameraPosition = .region(MKCoordinateRegion(
            center: visit.coordinate,
            latitudinalMeters: 1_000,
            longitudinalMeters: 1_000
        ))
    }

    static func rangeIncludingSavedVisit(_ arrival: Date, in range: ClosedRange<Date>) -> ClosedRange<Date> {
        min(range.lowerBound, arrival)...max(range.upperBound, arrival)
    }

    private func savedPlaceSuggestion(near location: CLLocation) -> NearbyPlaceSuggestion? {
        viewModel.savedPlaces
            .compactMap { place -> NearbyPlaceSuggestion? in
                let distance = place.distanceMeters(to: location.coordinate)
                guard distance <= place.radiusMeters else { return nil }

                return NearbyPlaceSuggestion(
                    id: "saved-\(place.id.uuidString)",
                    name: place.name,
                    address: place.address,
                    coordinate: place.coordinate,
                    distanceMeters: distance
                )
            }
            .min { lhs, rhs in lhs.distanceMeters < rhs.distanceMeters }
    }

    private func refreshCurrentPlaceSuggestion() async {
        guard showVisitSuggestions else {
            currentPlaceSuggestion = nil
            isLoadingCurrentPlace = false
            currentPlaceLookupFailed = false
            return
        }

        guard let location = freshManualLocation else {
            currentPlaceSuggestion = nil
            isLoadingCurrentPlace = false
            currentPlaceLookupFailed = false
            return
        }

        guard !isCurrentPlacePromptSuppressedInCurrentArea else {
            currentPlaceSuggestion = nil
            isLoadingCurrentPlace = false
            currentPlaceLookupFailed = false
            return
        }

        if let savedPlaceSuggestion = savedPlaceSuggestion(near: location) {
            currentPlaceSuggestion = savedPlaceSuggestion
            isLoadingCurrentPlace = false
            currentPlaceLookupFailed = false
            return
        }

        isLoadingCurrentPlace = true
        currentPlaceLookupFailed = false

        do {
            let suggestions = try await NearbyPlaceSearchService.shared.suggestions(
                near: location.coordinate,
                radius: 500,
                limit: 1
            )
            guard !Task.isCancelled else { return }
            guard !isCurrentPlacePromptSuppressedInCurrentArea else {
                currentPlaceSuggestion = nil
                currentPlaceLookupFailed = false
                isLoadingCurrentPlace = false
                return
            }
            currentPlaceSuggestion = suggestions.first
            currentPlaceLookupFailed = false
        } catch {
            guard !Task.isCancelled else { return }
            currentPlaceSuggestion = nil
            currentPlaceLookupFailed = true
        }

        isLoadingCurrentPlace = false
    }

    @discardableResult
    private func applyMapFocusRequestIfNeeded() -> Bool {
        guard let request = viewModel.mapFocusRequest,
              appliedMapFocusRequestID != request.id else {
            return false
        }

        appliedMapFocusRequestID = request.id
        activePreset = nil
        viewModel.mapDateRange = request.range
        viewModel.loadMapLocationPoints(in: request.range)
        viewModel.loadMapPhotoMoments(in: request.range)
        syncPhotoMomentsForCurrentRangeIfAuthorized()
        validateRouteReplayState()
        fitMapToContent()
        return true
    }

    private func fitMapToContent() {
        let coordinates = filteredVisits.map { $0.coordinate }
            + filteredPoints.map { $0.coordinate }
            + filteredPhotoMoments.map { $0.coordinate }
        guard !coordinates.isEmpty else { return }

        let region = MKCoordinateRegion(coordinates: coordinates)
        cameraPosition = .region(region)
    }

    private func fitMapToSession() {
        guard !activeSessionPoints.isEmpty else { return }
        let coordinates = activeSessionPoints.map { $0.coordinate }
        let region = MKCoordinateRegion(coordinates: coordinates)
        cameraPosition = .region(region)
    }

    private func attemptAutoFocusSession() {
        guard pendingSessionAutoFocus, !activeSessionPoints.isEmpty else { return }
        fitMapToSession()
        pendingSessionAutoFocus = false
    }

    private func selectPhotoDot(at localPoint: CGPoint, relativeTo owner: PhotoMomentCluster, proxy: MapProxy) {
        guard let center = proxy.convert(owner.coordinate, to: .local) else { return }
        let point = PhotoMapDotHitTesting.mapPoint(localPoint: localPoint, markerCenter: center)
        selectPhotoDot(at: point, proxy: proxy)
    }

    private func selectPhotoDot(at point: CGPoint, proxy: MapProxy) {
        let clusters = photoMomentClusters
        let centers = clusters.map { proxy.convert($0.coordinate, to: .local) }
        guard let index = PhotoMapDotHitTesting.targetIndex(at: point, centers: centers) else { return }
        // A path or visit annotation can receive the same touch. The map-level
        // gesture still gives photo dots priority inside their 44-point targets.
        selectedPointID = nil
        selectedVisit = nil
        let target = clusters[index]
        if let photo = target.onlyPhoto {
            viewModel.photoDetails.photo = photo
        } else {
            viewModel.photoDetails.cluster = target
        }
    }

    private func requestPhotoMomentsForCurrentRange() {
        Task {
            if viewModel.photoLibraryAccessState == .notDetermined {
                await viewModel.requestPhotoLibraryAccessAndSync(in: viewModel.mapDateRange)
            } else {
                await viewModel.syncPhotoMomentsIfAuthorized(in: viewModel.mapDateRange)
            }
        }
    }

    private func syncPhotoMomentsForCurrentRangeIfAuthorized() {
        guard showPhotoMarkers else { return }
        Task {
            await viewModel.syncPhotoMomentsIfAuthorized(in: viewModel.mapDateRange)
        }
    }

    private func togglePointTooltip(for point: LocationPoint) {
        lastPointMarkerTap = Date()
        withAnimation(reduceMotion ? nil : .spring(duration: 0.2)) {
            selectedPointID = selectedPointID == point.id ? nil : point.id
        }
    }

    private func handleMapTap() {
        // Map receives a tap alongside annotation buttons. Defer the check so a
        // point-marker tap can mark itself first, then only dismiss on true map taps.
        DispatchQueue.main.async {
            guard Date().timeIntervalSince(lastPointMarkerTap) > 0.15,
                  selectedPointID != nil else {
                return
            }

            withAnimation(reduceMotion ? nil : .spring(duration: 0.2)) {
                selectedPointID = nil
            }
        }
    }

    private func handleTrackingTap() {
        withAnimation(reduceMotion ? nil : .spring(response: 0.3, dampingFraction: 0.8)) {
            if isTracking {
                viewModel.stopTracking()
            } else {
                viewModel.startTracking()
            }
        }
    }

    private func toggleRouteReplayMode() {
        if isRouteReplayEnabled {
            disableRouteReplay()
        } else {
            guard canReplayRoute else { return }
            showTravelPath = true
            routeReplayProgress = 0
            withAnimation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.82)) {
                isRouteReplayEnabled = true
            }
        }
    }

    private func disableRouteReplay() {
        isRouteReplayPlaying = false
        withAnimation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.82)) {
            isRouteReplayEnabled = false
        }
    }

    private func toggleRouteReplayPlayback() {
        guard canReplayRoute else {
            disableRouteReplay()
            return
        }

        if !isRouteReplayEnabled {
            toggleRouteReplayMode()
        }

        if !isRouteReplayPlaying,
           let currentIndex = RouteReplayCalculator.index(for: routeReplayProgress, pointCount: routeReplaySourcePoints.count),
           currentIndex >= routeReplaySourcePoints.count - 1 {
            routeReplayProgress = 0
        }

        isRouteReplayPlaying.toggle()
    }

    private func advanceRouteReplayIfNeeded() {
        guard isRouteReplayEnabled, isRouteReplayPlaying else { return }
        guard canReplayRoute,
              let currentIndex = RouteReplayCalculator.index(for: routeReplayProgress, pointCount: routeReplaySourcePoints.count) else {
            disableRouteReplay()
            return
        }

        let lastIndex = routeReplaySourcePoints.count - 1
        let nextIndex = min(currentIndex + RouteReplayCalculator.playbackStepSize(pointCount: routeReplaySourcePoints.count), lastIndex)
        routeReplayProgress = RouteReplayCalculator.progress(forIndex: nextIndex, pointCount: routeReplaySourcePoints.count)

        if nextIndex >= lastIndex {
            isRouteReplayPlaying = false
        }
    }

    private func validateRouteReplayState() {
        routeReplayProgress = RouteReplayCalculator.clampedProgress(routeReplayProgress)

        guard canReplayRoute else {
            if isRouteReplayEnabled {
                disableRouteReplay()
            } else {
                isRouteReplayPlaying = false
            }
            return
        }

        if isRouteReplayEnabled, routeReplaySnapshot == nil {
            routeReplayProgress = 0
        }
    }

    @MainActor
    private func refreshRoadSnappedRoute() async {
        guard snapTravelPathToRoads else {
            roadSnappedRoute = nil
            return
        }

        let sourcePoints = filteredPoints
        guard sourcePoints.count >= 2 else {
            roadSnappedRoute = nil
            return
        }

        let fingerprint = RoadSnappedRouteBuilder.fingerprint(for: sourcePoints)
        if roadSnappedRoute?.sourceFingerprint == fingerprint {
            return
        }

        let snapPoints = sourcePoints.map { RoadSnappingPoint(point: $0) }
        let route = await RoadSnappedRouteBuilder.buildRoute(
            for: snapPoints,
            sourceFingerprint: fingerprint
        )

        guard !Task.isCancelled else { return }
        roadSnappedRoute = route
    }

    private var mapAccessibilitySummary: String {
        var parts: [String] = []
        parts.append(String(localized: "\(filteredVisits.count) visits"))
        if viewModel.mapLocationPointCount > filteredPoints.count {
            parts.append(String(localized: "Showing \(filteredPoints.count) of \(viewModel.mapLocationPointCount) path points"))
        } else {
            parts.append(String(localized: "\(filteredPoints.count) path points"))
        }

        if showPhotoMarkers {
            if viewModel.mapPhotoMomentCount > filteredPhotoMoments.count {
                parts.append(String(localized: "Showing \(filteredPhotoMoments.count) of \(viewModel.mapPhotoMomentCount) matched photos"))
            } else {
                parts.append(String(localized: "\(filteredPhotoMoments.count) matched photos"))
            }
        }

        if isRouteReplayEnabled, let routeReplaySnapshot {
            parts.append(String(localized: "Route replay: \(routeReplaySnapshot.accessibilitySummary)"))
        }

        if !activeSessionPoints.isEmpty {
            parts.append(String(localized: "Active session: \(viewModel.sessionAccessibilitySummary)"))
        }

        return parts.joined(separator: ". ")
    }
}

// MARK: - Tracking Control Pills

struct MapTrackingControlPill: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Bindable var viewModel: LocationViewModel
    @ObservedObject var locationManager: LocationManager
    let isTracking: Bool
    @Binding var isExpanded: Bool
    let onPrimaryTap: () -> Void

    @State private var pulseOpacity: Double = 1.0

    var body: some View {
        HStack(spacing: 10) {
            if isTracking {
                Button {
                    withAnimation(reduceMotion ? nil : .spring(response: 0.4, dampingFraction: 0.82)) {
                        isExpanded.toggle()
                    }
                } label: {
                    statusBlock
                        .frame(minWidth: 44, minHeight: 44)
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Session summary")
                .accessibilityValue(viewModel.sessionAccessibilitySummary)
                .accessibilityHint(isExpanded ? "Collapses session details." : "Expands session details.")
                .transition(.opacity.combined(with: .move(edge: .trailing)))

                if isExpanded {
                    statsBlock
                        .accessibilityElement(children: .ignore)
                        .accessibilityLabel("Session details")
                        .accessibilityValue(viewModel.sessionAccessibilitySummary)
                        .transition(.asymmetric(
                            insertion: .opacity.combined(with: .move(edge: .trailing)),
                            removal: .opacity.combined(with: .move(edge: .trailing))
                        ))
                }
            }

            primaryButton
        }
        .padding(.leading, isTracking ? 14 : 0)
        .padding(.trailing, isTracking ? 6 : 0)
        .padding(.vertical, isTracking ? 6 : 0)
        .background {
            if isTracking {
                Capsule()
                    .fill(.ultraThinMaterial)
                    .overlay {
                        Capsule()
                            .strokeBorder(
                                LinearGradient(
                                    colors: [.white.opacity(0.55), .white.opacity(0.08)],
                                    startPoint: .topLeading,
                                    endPoint: .bottomTrailing
                                ),
                                lineWidth: 0.8
                            )
                    }
                    .shadow(color: .black.opacity(0.18), radius: 14, x: 0, y: 6)
                    .transition(.opacity)
            }
        }
        .animation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.82), value: isTracking)
        .animation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.82), value: isExpanded)
        .onAppear { pulseOpacity = reduceMotion ? 1.0 : 0.35 }
        .onChange(of: reduceMotion) { _, shouldReduceMotion in
            pulseOpacity = shouldReduceMotion ? 1.0 : 0.35
        }
    }

    private var statusBlock: some View {
        HStack(spacing: 8) {
            Circle()
                .fill(isTracking ? TE.accent : TE.textMuted.opacity(0.35))
                .frame(width: 7, height: 7)
                .opacity(isTracking ? pulseOpacity : 1.0)
                .accessibilityHidden(true)
                .animation(
                    isTracking && !reduceMotion
                        ? .easeInOut(duration: 1.2).repeatForever(autoreverses: true)
                        : nil,
                    value: pulseOpacity
                )

            if isTracking {
                TimelineView(.periodic(from: .now, by: 1.0)) { _ in
                    Text(viewModel.formattedSessionTrackingDuration)
                        .font(TE.mono(.subheadline, weight: .semibold))
                        .foregroundStyle(TE.textMuted)
                        .monospacedDigit()
                        .contentTransition(reduceMotion ? .identity : .numericText())
                }
            } else {
                Text("STANDBY")
                    .font(TE.mono(.caption, weight: .semibold))
                    .tracking(1.8)
                    .foregroundStyle(TE.textMuted)
            }
        }
    }

    private var statsBlock: some View {
        HStack(spacing: 8) {
            Text(viewModel.formattedSessionDistance)
                .font(TE.mono(.caption, weight: .medium))
                .foregroundStyle(TE.textMuted)
                .monospacedDigit()

            Rectangle()
                .fill(Color.primary.opacity(0.15))
                .frame(width: 1, height: 12)
                .accessibilityHidden(true)

            Text("\(viewModel.sessionLocationPoints.count) PTS")
                .font(TE.mono(.caption, weight: .medium))
                .foregroundStyle(TE.textMuted)
        }
    }

    private var primaryButton: some View {
        Button(action: onPrimaryTap) {
            Image(systemName: isTracking ? "stop.fill" : "play.fill")
                .font(.caption.weight(.bold))
                .foregroundStyle(.white)
                .frame(width: 34, height: 34)
                .background {
                    Circle()
                        .fill(isTracking ? TE.danger : TE.accent)
                        .overlay {
                            Circle()
                                .stroke(
                                    LinearGradient(
                                        colors: [.white.opacity(0.4), .white.opacity(0.0)],
                                        startPoint: .top,
                                        endPoint: .bottom
                                    ),
                                    lineWidth: 0.8
                                )
                        }
                        .shadow(
                            color: (isTracking ? TE.danger : TE.accent).opacity(0.35),
                            radius: 6,
                            x: 0,
                            y: 3
                        )
                }
                .frame(width: 44, height: 44)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(isTracking ? "Stop tracking" : "Start tracking")
        .accessibilityValue(isTracking ? viewModel.sessionAccessibilitySummary : String(localized: "Tracking is off."))
        .accessibilityHint(isTracking ? "Stops the active session." : "Starts a new location tracking session.")
        .sensoryFeedback(.impact(flexibility: .solid), trigger: isTracking)
    }
}

struct MapAutoOffPill: View {
    let remaining: TimeInterval

    var body: some View {
        HStack(spacing: 6) {
            Image(systemName: "timer")
                .font(.caption2.weight(.medium))
                .foregroundStyle(TE.textMuted)
                .accessibilityHidden(true)

            Text("AUTO-OFF  \(formatTime(remaining))")
                .font(TE.mono(.caption2, weight: .semibold))
                .tracking(1.4)
                .foregroundStyle(TE.textMuted)
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 6)
        .background {
            Capsule()
                .fill(.ultraThinMaterial)
                .overlay {
                    Capsule()
                        .strokeBorder(
                            LinearGradient(
                                colors: [.white.opacity(0.45), .white.opacity(0.05)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            ),
                            lineWidth: 0.6
                        )
                }
                .shadow(color: .black.opacity(0.12), radius: 8, x: 0, y: 3)
        }
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Tracking auto-off")
        .accessibilityValue("Stops in \(spokenTime(remaining)).")
    }

    private func formatTime(_ interval: TimeInterval) -> String {
        let hours = Int(interval) / 3600
        let minutes = (Int(interval) % 3600) / 60
        if hours > 0 { return String(localized: "\(hours)H \(minutes)M") }
        return String(localized: "\(minutes) MIN")
    }

    private func spokenTime(_ interval: TimeInterval) -> String {
        let hours = Int(interval) / 3600
        let minutes = (Int(interval) % 3600) / 60
        if hours > 0 {
            if hours == 1, minutes == 1 {
                return String(localized: "1 hour and 1 minute")
            } else if hours == 1 {
                return String(localized: "1 hour and \(minutes) minutes")
            } else if minutes == 1 {
                return String(localized: "\(hours) hours and 1 minute")
            }
            return String(localized: "\(hours) hours and \(minutes) minutes")
        }
        if minutes == 1 { return String(localized: "1 minute") }
        return String(localized: "\(minutes) minutes")
    }
}

enum VisitMarkerState: CaseIterable {
    case unconfirmed, confirmed, corrected, manual

    init(visit: Visit) {
        if visit.source == .manual { self = .manual }
        else {
            switch visit.confirmationStatus {
            case .unconfirmed: self = .unconfirmed
            case .confirmed: self = .confirmed
            case .corrected: self = .corrected
            }
        }
    }

    var symbol: String {
        switch self {
        case .unconfirmed: "questionmark"
        case .confirmed: "checkmark"
        case .corrected: "pencil"
        case .manual: "hand.point.up.fill"
        }
    }

    var color: Color {
        switch self {
        case .unconfirmed: .orange
        case .confirmed: .green
        case .corrected: .blue
        case .manual: .purple
        }
    }
}

struct VisitMarker: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let visit: Visit
    let isSelected: Bool
    let isCurrentVisit: Bool

    private var state: VisitMarkerState { VisitMarkerState(visit: visit) }

    var body: some View {
        VStack(spacing: 0) {
            ZStack {
                Circle()
                    .fill(state.color)
                    .frame(width: isSelected ? 36 : 28, height: isSelected ? 36 : 28)
                    .overlay {
                        if isCurrentVisit {
                            Circle().stroke(.blue, lineWidth: 3).padding(-4)
                        }
                    }
                    .accessibilityHidden(true)

                Image(systemName: state.symbol)
                    .font(isSelected ? .title3 : .callout)
                    .foregroundStyle(.white)
                    .accessibilityHidden(true)
            }

            Triangle()
                .fill(state.color)
                .frame(width: 10, height: 8)
                .accessibilityHidden(true)
        }
        .frame(minWidth: 44, minHeight: 44)
        .animation(reduceMotion ? nil : .spring(duration: 0.2), value: isSelected)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel(isCurrentVisit ? "Current visit at \(visit.displayName)" : "Visit at \(visit.displayName)")
        .accessibilityValue([visit.confirmationStatus.displayName, visit.source.displayName, visit.accessibilityValue].joined(separator: ". "))
        .accessibilityHint(visit.accessibilityHint)
        .accessibilityAddTraits(.isButton)
    }
}

struct Triangle: Shape {
    func path(in rect: CGRect) -> Path {
        var path = Path()
        path.move(to: CGPoint(x: rect.midX, y: rect.maxY))
        path.addLine(to: CGPoint(x: rect.minX, y: rect.minY))
        path.addLine(to: CGPoint(x: rect.maxX, y: rect.minY))
        path.closeSubpath()
        return path
    }
}

// MARK: - Path Point Markers

struct MapAnnotationTooltipOverlay<Content: View>: View {
    let content: Content

    init(@ViewBuilder content: () -> Content) {
        self.content = content()
    }

    var body: some View {
        VStack(spacing: 6) {
            content

            Color.clear
                .frame(width: 44, height: 44)
                .accessibilityHidden(true)
        }
        .fixedSize(horizontal: true, vertical: true)
        .allowsHitTesting(false)
        .transition(.scale.combined(with: .opacity))
    }
}

struct PathPointMarker: View {
    let point: LocationPoint
    let isSelected: Bool
    let onTap: () -> Void

    private var markerColor: Color {
        if isSelected { return TE.lcdGreen }
        return point.isOutlier ? .orange : .blue
    }

    var body: some View {
        Button(action: onTap) {
            ZStack {
                if isSelected {
                    Circle()
                        .fill(markerColor.opacity(0.18))
                        .frame(width: 24, height: 24)

                    Circle()
                        .stroke(.white.opacity(0.9), lineWidth: 2)
                        .frame(width: 16, height: 16)
                }

                Circle()
                    .fill(markerColor)
                    .frame(width: isSelected ? 12 : 8, height: isSelected ? 12 : 8)
                    .overlay {
                        Circle()
                            .stroke(.white, lineWidth: isSelected ? 2 : 1.5)
                    }
            }
            .shadow(color: markerColor.opacity(isSelected ? 0.55 : 0.35), radius: isSelected ? 6 : 3, y: 1)
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .overlay(alignment: .bottom) {
            if isSelected {
                MapAnnotationTooltipOverlay {
                    PathPointTooltip(point: point)
                }
            }
        }
        .accessibilityLabel(point.isOutlier ? "Outlier path point" : "Path point")
        .accessibilityValue(point.accessibilityValue)
        .accessibilityHint(isSelected ? "Hides point metadata." : "Shows point metadata.")
    }
}

struct PathPointTooltip: View {
    @AppStorage("usesMetricDistanceUnits") private var usesMetricDistanceUnits = true
    let point: LocationPoint

    var body: some View {
        VStack(alignment: .leading, spacing: 6) {
            HStack(alignment: .firstTextBaseline, spacing: 8) {
                Text("POINT")
                    .font(TE.mono(.caption2, weight: .semibold))
                    .tracking(1.2)
                    .foregroundStyle(TE.textMuted)

                Spacer(minLength: 4)

                Text(point.timestamp.formatted(date: .abbreviated, time: .standard))
                    .font(.caption2.weight(.semibold))
                    .monospacedDigit()
                    .foregroundStyle(.primary)
                    .multilineTextAlignment(.trailing)
                    .lineLimit(2)
            }

            Divider()
                .overlay(Color.primary.opacity(0.12))

            PathPointTooltipRow(label: "COORDS", value: coordinateText)
            PathPointTooltipRow(label: "ACCURACY", value: "±\(formatDistance(point.horizontalAccuracy))")

            if let altitude = point.altitude {
                PathPointTooltipRow(label: "ALTITUDE", value: formatDistance(altitude))
            }

            if let speed = point.speed {
                PathPointTooltipRow(label: "SPEED", value: formattedSpeed(speed))
            }

            if point.isOutlier {
                PathPointTooltipRow(label: "STATUS", value: String(localized: "GPS outlier"))
            }
        }
        .padding(10)
        .frame(width: 240, alignment: .leading)
        .background {
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .fill(.ultraThinMaterial)
                .overlay {
                    RoundedRectangle(cornerRadius: 14, style: .continuous)
                        .strokeBorder(Color.white.opacity(0.28), lineWidth: 0.8)
                }
        }
        .shadow(color: .black.opacity(0.18), radius: 10, y: 4)
        .transition(.scale.combined(with: .opacity))
        .accessibilityHidden(true)
    }

    private var coordinateText: String {
        String(format: "%.5f, %.5f", point.latitude, point.longitude)
    }

    private func formatDistance(_ meters: Double) -> String {
        DistanceFormatter.format(meters: meters, usesMetric: usesMetricDistanceUnits)
    }

    private func formattedSpeed(_ metersPerSecond: Double) -> String {
        if usesMetricDistanceUnits {
            return String(format: "%.1f km/h", locale: .current, metersPerSecond * 3.6)
        }
        return String(format: "%.1f mph", locale: .current, metersPerSecond * 2.23693629)
    }
}

struct PathPointTooltipRow: View {
    let label: LocalizedStringKey
    let value: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 8) {
            Text(label)
                .font(TE.mono(.caption2, weight: .medium))
                .tracking(0.8)
                .foregroundStyle(TE.textMuted)
                .frame(width: 62, alignment: .leading)

            Text(value)
                .font(TE.mono(.caption2, weight: .semibold))
                .monospacedDigit()
                .foregroundStyle(.primary)
                .multilineTextAlignment(.leading)
                .lineLimit(2)

            Spacer(minLength: 0)
        }
    }
}

// MARK: - Path Start/End Markers

struct PathStartMarker: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let timestamp: Date
    @State private var showingTooltip = false

    var body: some View {
        Button {
            withAnimation(reduceMotion ? nil : .spring(duration: 0.2)) {
                showingTooltip.toggle()
            }
        } label: {
            ZStack {
                Circle()
                    .fill(.green)
                    .frame(width: 28, height: 28)
                    .accessibilityHidden(true)

                Image(systemName: "flag.fill")
                    .font(.callout)
                    .foregroundStyle(.white)
                    .accessibilityHidden(true)
            }
            .shadow(color: .green.opacity(0.4), radius: 4, y: 2)
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .overlay(alignment: .bottom) {
            if showingTooltip {
                MapAnnotationTooltipOverlay {
                    PathEndpointTooltip(label: "START", timestamp: timestamp)
                }
            }
        }
        .accessibilityLabel("Path start")
        .accessibilityValue(timestamp.formatted(date: .abbreviated, time: .shortened))
        .accessibilityHint(showingTooltip ? "Hides the start time." : "Shows the start time.")
    }
}

struct PathEndpointTooltip: View {
    let label: LocalizedStringKey
    let timestamp: Date

    var body: some View {
        HStack(spacing: 4) {
            Text(label)
                .font(TE.mono(.caption2, weight: .semibold))
                .tracking(0.8)
                .foregroundStyle(TE.textMuted)

            Text(timestamp.formatted(date: .abbreviated, time: .shortened))
                .font(.caption2.weight(.semibold))
                .monospacedDigit()
                .foregroundStyle(.primary)
        }
        .lineLimit(1)
        .padding(.horizontal, 8)
        .padding(.vertical, 3)
        .background(.ultraThinMaterial, in: Capsule())
        .fixedSize(horizontal: true, vertical: false)
        .transition(.scale.combined(with: .opacity))
        .accessibilityHidden(true)
    }
}

struct PathEndMarker: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let timestamp: Date
    @State private var showingTooltip = false

    var body: some View {
        Button {
            withAnimation(reduceMotion ? nil : .spring(duration: 0.2)) {
                showingTooltip.toggle()
            }
        } label: {
            ZStack {
                Circle()
                    .fill(.red)
                    .frame(width: 28, height: 28)
                    .accessibilityHidden(true)

                Image(systemName: "flag.checkered")
                    .font(.callout)
                    .foregroundStyle(.white)
                    .accessibilityHidden(true)
            }
            .shadow(color: .red.opacity(0.4), radius: 4, y: 2)
            .frame(width: 44, height: 44)
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .overlay(alignment: .bottom) {
            if showingTooltip {
                MapAnnotationTooltipOverlay {
                    PathEndpointTooltip(label: "END", timestamp: timestamp)
                }
            }
        }
        .accessibilityLabel("Path end")
        .accessibilityValue(timestamp.formatted(date: .abbreviated, time: .shortened))
        .accessibilityHint(showingTooltip ? "Hides the end time." : "Shows the end time.")
    }
}

struct RouteReplayMarker: View {
    let snapshot: RouteReplaySnapshot

    var body: some View {
        ZStack {
            Circle()
                .fill(.white)
                .frame(width: 34, height: 34)
                .shadow(color: TE.accent.opacity(0.35), radius: 8, y: 3)
                .accessibilityHidden(true)

            Circle()
                .fill(TE.accent)
                .frame(width: 24, height: 24)
                .accessibilityHidden(true)

            Image(systemName: "play.fill")
                .font(.caption2.weight(.bold))
                .foregroundStyle(.white)
                .offset(x: 1)
                .accessibilityHidden(true)
        }
        .frame(minWidth: 44, minHeight: 44)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Route replay position")
        .accessibilityValue(snapshot.accessibilitySummary)
    }
}

struct RouteReplayControl: View {
    @AppStorage("usesMetricDistanceUnits") private var usesMetricDistanceUnits = true
    let snapshot: RouteReplaySnapshot
    let pointCount: Int
    @Binding var progress: Double
    let isPlaying: Bool
    let onPlayPause: () -> Void
    let onScrub: () -> Void
    let onClose: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Button(action: onPlayPause) {
                    Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(.white)
                        .frame(width: 34, height: 34)
                        .background(Circle().fill(TE.accent))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(isPlaying ? "Pause route replay" : "Play route replay")

                VStack(alignment: .leading, spacing: 2) {
                    Text("ROUTE REPLAY")
                        .font(TE.mono(.caption2, weight: .semibold))
                        .tracking(1.6)
                        .foregroundStyle(TE.textMuted)

                    Text(snapshot.currentPoint.timestamp.formatted(date: .abbreviated, time: .shortened))
                        .font(.subheadline.weight(.semibold))
                        .monospacedDigit()
                        .foregroundStyle(.primary)
                }

                Spacer()

                Text("\(snapshot.index + 1)/\(pointCount) PTS")
                    .font(TE.mono(.caption2, weight: .semibold))
                    .foregroundStyle(TE.textMuted)
                    .monospacedDigit()

                Button(action: onClose) {
                    Image(systemName: "xmark")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(Color.primary.opacity(0.7))
                        .frame(width: 30, height: 30)
                        .background(Circle().fill(Color.primary.opacity(0.08)))
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Close route replay")
            }

            Slider(
                value: Binding(
                    get: { progress },
                    set: { newValue in
                        progress = RouteReplayCalculator.clampedProgress(newValue)
                        onScrub()
                    }
                ),
                in: 0...1,
                onEditingChanged: { editing in
                    if editing { onScrub() }
                }
            ) {
                Text("Route replay progress")
            }
            .tint(TE.accent)
            .accessibilityValue(snapshot.accessibilitySummary)

            HStack(spacing: 8) {
                RouteReplayStat(label: "ELAPSED", value: formatDuration(snapshot.elapsedDuration))
                RouteReplayStat(label: "DURATION", value: formatDuration(snapshot.totalDuration))
                RouteReplayStat(label: "DISTANCE", value: formattedDistanceProgress)
            }
        }
        .padding(12)
        .background {
            RoundedRectangle(cornerRadius: 18, style: .continuous)
                .fill(.ultraThinMaterial)
                .overlay {
                    RoundedRectangle(cornerRadius: 18, style: .continuous)
                        .strokeBorder(
                            LinearGradient(
                                colors: [.white.opacity(0.55), .white.opacity(0.08)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            ),
                            lineWidth: 0.8
                        )
                }
                .shadow(color: .black.opacity(0.18), radius: 14, x: 0, y: 6)
        }
        .accessibilityElement(children: .contain)
    }

    private var formattedDistanceProgress: String {
        let current = DistanceFormatter.format(meters: snapshot.distanceMeters, usesMetric: usesMetricDistanceUnits)
        let total = DistanceFormatter.format(meters: snapshot.totalDistanceMeters, usesMetric: usesMetricDistanceUnits)
        return "\(current) / \(total)"
    }

    private func formatDuration(_ duration: TimeInterval) -> String {
        let totalSeconds = max(0, Int(duration.rounded()))
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60

        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        }
        return String(format: "%d:%02d", minutes, seconds)
    }
}

struct RouteReplayStat: View {
    let label: LocalizedStringKey
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(label)
                .font(TE.mono(.caption2, weight: .semibold))
                .tracking(1.1)
                .foregroundStyle(TE.textMuted)

            Text(value)
                .font(TE.mono(.caption, weight: .semibold))
                .foregroundStyle(.primary)
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.75)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.horizontal, 8)
        .padding(.vertical, 7)
        .background {
            RoundedRectangle(cornerRadius: 10, style: .continuous)
                .fill(Color.primary.opacity(0.06))
        }
    }
}

struct DateRangeChip: View {
    let range: ClosedRange<Date>

    var body: some View {
        HStack(spacing: 4) {
            Image(systemName: "calendar")
                .font(.caption2)
            Text(formattedRange)
                .font(.caption)
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 6)
        .background(.ultraThinMaterial, in: Capsule())
    }

    private var formattedRange: String {
        let formatter = DateFormatter()
        formatter.dateStyle = .short

        let start = formatter.string(from: range.lowerBound)
        let end = formatter.string(from: range.upperBound)

        if start == end {
            return start
        }
        return "\(start) - \(end)"
    }
}

struct ManualVisitDraft: Identifiable {
    let id = UUID()
    var mode: ManualVisitMode = .saveCurrentPlace
    var name: String = ""
    var address: String = ""
    var locationSelection: ManualLocationSelection?
    var arrivedAt: Date = Date()
    var departedAt: Date = Date()
    var isStillHere: Bool = true
    var title: LocalizedStringResource = "Save Place"
    var hidesCurrentPlacePromptAfterSave: Bool = true
    var initialSearchRegion: MKCoordinateRegion?
}

private struct CurrentPlacePromptSuppression {
    static let radiusMeters: CLLocationDistance = 500

    let center: CLLocationCoordinate2D
    let radiusMeters: CLLocationDistance

    init(center: CLLocationCoordinate2D, radiusMeters: CLLocationDistance = Self.radiusMeters) {
        self.center = center
        self.radiusMeters = radiusMeters
    }

    func contains(_ coordinate: CLLocationCoordinate2D) -> Bool {
        let dismissedLocation = CLLocation(latitude: center.latitude, longitude: center.longitude)
        let currentLocation = CLLocation(latitude: coordinate.latitude, longitude: coordinate.longitude)
        return currentLocation.distance(from: dismissedLocation) <= radiusMeters
    }
}

private struct CurrentPlaceCard: View {
    let suggestion: NearbyPlaceSuggestion?
    let isLoading: Bool
    let lookupFailed: Bool
    let hasFreshLocation: Bool
    let hasLocationPermission: Bool
    let onSave: () -> Void
    let onNotHere: () -> Void
    let onRequestLocation: () -> Void
    let onDismiss: () -> Void

    var body: some View {
        HStack(alignment: .center, spacing: 8) {
            iconView
            Text(title)
                .font(TE.mono(.caption2, weight: .bold))
                .tracking(0.8)
                .foregroundStyle(TE.textPrimary)
                .lineLimit(1)
                .frame(maxWidth: 145, alignment: .leading)

            actionView
        }
        .padding(.horizontal, 10)
        .padding(.vertical, 7)
        .padding(.trailing, suggestion == nil ? 0 : 12)
        .background(cardBackground)
        .overlay(alignment: .topTrailing) {
            if suggestion != nil {
                Button(action: onDismiss) {
                    Image(systemName: "xmark.circle.fill")
                        .font(.system(size: 14, weight: .bold))
                        .symbolRenderingMode(.palette)
                        .foregroundStyle(.white, .red)
                        .background(.red, in: Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Close visit suggestion")
                .accessibilityHint("Hides this suggested place from the map.")
                .offset(x: 5, y: -5)
            }
        }
        .accessibilityElement(children: .contain)
    }

    private var iconView: some View {
        ZStack {
            Circle()
                .fill(iconTint.opacity(0.14))
            Image(systemName: iconName)
                .font(.system(size: 15, weight: .semibold))
                .foregroundStyle(iconTint)
        }
        .frame(width: 32, height: 32)
    }

    @ViewBuilder
    private var actionView: some View {
        if canSave {
            Button("Save") { onSave() }
                .font(TE.mono(.caption2, weight: .bold))
                .tracking(0.7)
                .buttonStyle(.borderedProminent)
                .controlSize(.mini)
                .accessibilityHint("Opens visit details prefilled with this place.")

            Button { onNotHere() } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 14, weight: .bold))
            }
            .buttonStyle(.borderless)
            .controlSize(.mini)
            .accessibilityLabel("Not here")
            .accessibilityHint("Opens manual place entry.")
        } else {
            Button(hasLocationPermission ? "Find" : "Allow") { onRequestLocation() }
                .font(TE.mono(.caption2, weight: .bold))
                .tracking(0.7)
                .buttonStyle(.bordered)
                .controlSize(.mini)
                .accessibilityHint("Requests a fresh current location.")
        }
    }

    private var cardBackground: some View {
        Capsule()
            .fill(.ultraThinMaterial)
            .overlay {
                Capsule()
                    .strokeBorder(.white.opacity(0.28), lineWidth: 0.6)
            }
            .shadow(color: .black.opacity(0.12), radius: 8, x: 0, y: 3)
    }

    private var canSave: Bool {
        suggestion != nil || hasFreshLocation
    }

    private var iconName: String {
        if isLoading { return "location.viewfinder" }
        if suggestion != nil { return "mappin.and.ellipse" }
        if !hasLocationPermission { return "location.slash" }
        if lookupFailed { return "exclamationmark.triangle" }
        return "location"
    }

    private var iconTint: Color {
        if !hasLocationPermission || lookupFailed { return .orange }
        if canSave { return .blue }
        return .secondary
    }

    private var title: String {
        if let suggestion { return suggestion.name }
        if isLoading { return String(localized: "Finding place…") }
        if !hasLocationPermission { return String(localized: "Use location") }
        if lookupFailed { return String(localized: "Add this place") }
        return String(localized: "Save place")
    }

    private var subtitle: String {
        if let suggestion {
            if let address = suggestion.address, !address.isEmpty {
                return address
            }
            return suggestion.distanceLabel
        }
        if isLoading { return String(localized: "Checking nearby Apple Maps places") }
        if !hasLocationPermission { return String(localized: "Allow location to save a visit") }
        if lookupFailed { return String(localized: "You can still add details manually") }
        if hasFreshLocation { return String(localized: "Add a visit at your current location") }
        return String(localized: "Waiting for fresh GPS accuracy")
    }

}

private struct MapAddPlaceMenu: View {
    let onSaveCurrentPlace: () -> Void
    let onAddPastVisit: () -> Void
    let onDropPin: () -> Void

    var body: some View {
        Menu {
            Button("Save current place", systemImage: "location.fill", action: onSaveCurrentPlace)
            Button("Add past visit", systemImage: "calendar.badge.plus", action: onAddPastVisit)
            Button("Add place manually", systemImage: "mappin.and.ellipse", action: onDropPin)
        } label: {
            Image(systemName: "plus")
                .font(.system(size: 18, weight: .bold))
                .foregroundStyle(TE.textPrimary)
                .frame(width: 44, height: 44)
                .background {
                    Circle()
                        .fill(.ultraThinMaterial)
                        .overlay {
                            Circle()
                                .strokeBorder(.white.opacity(0.28), lineWidth: 0.6)
                        }
                        .shadow(color: .black.opacity(0.12), radius: 8, x: 0, y: 3)
                }
        }
        .menuStyle(.button)
        .buttonStyle(.plain)
        .accessibilityLabel("Add place")
        .accessibilityHint("Opens options to save your current place or add a past visit.")
    }
}

struct ManualVisitSheet: View {
    @Bindable var viewModel: LocationViewModel
    @ObservedObject var locationManager: LocationManager
    @Environment(\.dismiss) private var dismiss

    private let mode: ManualVisitMode
    private let draftTitle: LocalizedStringResource
    private let initialSearchRegion: MKCoordinateRegion?
    private let onSaved: ((Visit) -> Void)?

    @State private var name: String
    @State private var address: String
    @State private var arrivedAt: Date
    @State private var departedAt: Date
    @State private var isStillHere: Bool
    @State private var locationSelection: ManualLocationSelection?
    @State private var selectedSavedPlaceID: UUID?
    @State private var saveAsReusableLocation = false
    @State private var reusableRadiusMeters = 150.0
    @State private var errorMessage: String?
    @State private var showingLocationPicker = false
    @State private var isSaving = false
    @State private var showingOverlapConfirmation = false

    init(
        viewModel: LocationViewModel,
        locationManager: LocationManager,
        draft: ManualVisitDraft = ManualVisitDraft(),
        onSaved: ((Visit) -> Void)? = nil
    ) {
        self.viewModel = viewModel
        self.locationManager = locationManager
        self.mode = draft.mode
        self.draftTitle = draft.title
        self.initialSearchRegion = draft.initialSearchRegion
        self.onSaved = onSaved
        _name = State(initialValue: draft.name)
        _address = State(initialValue: draft.address)
        _arrivedAt = State(initialValue: draft.arrivedAt)
        _departedAt = State(initialValue: draft.departedAt)
        _isStillHere = State(initialValue: draft.isStillHere)
        _locationSelection = State(initialValue: draft.locationSelection)
        _selectedSavedPlaceID = State(initialValue: draft.locationSelection?.savedPlaceID)
    }

    private var manualLocation: CLLocation? {
        guard let location = locationManager.currentLocation else { return nil }
        let age = Date().timeIntervalSince(location.timestamp)
        guard age <= 5 * 60 else { return nil }
        guard location.horizontalAccuracy >= 0, location.horizontalAccuracy <= 100 else { return nil }
        return location
    }

    private var selectedSavedPlace: SavedPlace? {
        guard let selectedSavedPlaceID else { return nil }
        return viewModel.savedPlaces.first { $0.id == selectedSavedPlaceID }
    }

    private var coordinate: CLLocationCoordinate2D? {
        mode.resolvedCoordinate(
            selection: locationSelection,
            currentLocation: manualLocation
        )
    }

    private var isSaveDisabled: Bool {
        isSaving ||
        name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ||
        coordinate == nil ||
        validationMessage != nil
    }

    private var validationMessage: String? {
        if mode.requiresDeparture && isStillHere {
            return String(localized: "Past visits need a departure time.")
        }
        if (!isStillHere || mode.requiresDeparture), departedAt < arrivedAt {
            return String(localized: "Departure can’t be before arrival.")
        }
        return nil
    }

    private var nameBinding: Binding<String> {
        Binding(
            get: { name },
            set: { newName in
                name = newName
                guard var selection = locationSelection else { return }
                selection.updateUserEnteredMetadata(
                    name: newName,
                    address: address.isEmpty ? nil : address
                )
                locationSelection = selection
            }
        )
    }

    private var addressBinding: Binding<String> {
        Binding(
            get: { address },
            set: { newAddress in
                address = newAddress
                guard var selection = locationSelection else { return }
                selection.updateUserEnteredMetadata(
                    name: name,
                    address: newAddress.isEmpty ? nil : newAddress
                )
                locationSelection = selection
            }
        )
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Place") {
                    TextField("Place name", text: nameBinding)
                        .textInputAutocapitalization(.words)
                    TextField("Address or note", text: addressBinding)
                        .textInputAutocapitalization(.words)
                }

                if !viewModel.savedPlaces.isEmpty {
                    Section("Saved Locations") {
                        ForEach(viewModel.savedPlaces) { place in
                            Button {
                                applySavedPlace(place)
                            } label: {
                                HStack {
                                    VStack(alignment: .leading, spacing: 3) {
                                        Text(place.name)
                                        if let address = place.address, !address.isEmpty {
                                            Text(address)
                                                .font(.caption)
                                                .foregroundStyle(.secondary)
                                        }
                                    }
                                    Spacer()
                                    if selectedSavedPlaceID == place.id {
                                        Image(systemName: "checkmark")
                                            .foregroundStyle(.blue)
                                    }
                                }
                                .frame(minHeight: 44)
                            }
                        }
                        .onDelete(perform: deleteSavedPlaces)

                        if selectedSavedPlaceID != nil {
                            if mode == .addPastVisit {
                                Button("Choose another location") {
                                    showingLocationPicker = true
                                }
                            } else {
                                Button("Use current location instead") {
                                    selectedSavedPlaceID = nil
                                    locationSelection = nil
                                    locationManager.requestCurrentLocationForManualVisit()
                                }
                            }
                        }
                    }
                }

                Section("Time") {
                    DatePicker(
                        "Arrived",
                        selection: $arrivedAt,
                        in: Date.distantPast...Date(),
                        displayedComponents: [.date, .hourAndMinute]
                    )

                    if mode.requiresDeparture {
                        DatePicker(
                            "Departed",
                            selection: $departedAt,
                            in: Date.distantPast...Date(),
                            displayedComponents: [.date, .hourAndMinute]
                        )
                    } else {
                        Toggle("Still here", isOn: $isStillHere)
                        if !isStillHere {
                            DatePicker(
                                "Departed",
                                selection: $departedAt,
                                in: Date.distantPast...Date(),
                                displayedComponents: [.date, .hourAndMinute]
                            )
                        }
                    }

                    if let validationMessage {
                        Text(validationMessage)
                            .font(.caption)
                            .foregroundStyle(.red)
                    }
                }

                Section("Location") {
                    if let selection = locationSelection {
                        VStack(alignment: .leading, spacing: 5) {
                            Label(
                                selectedSavedPlace == nil ? "Selected location ready" : "Saved location ready",
                                systemImage: selectedSavedPlace == nil ? "mappin.and.ellipse" : "bookmark.fill"
                            )
                            .foregroundStyle(.green)

                            if !selection.name.isEmpty {
                                Text(selection.name)
                                    .font(.subheadline.weight(.semibold))
                            }
                            if let selectedAddress = selection.address, !selectedAddress.isEmpty {
                                Text(selectedAddress)
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                            }
                            Text(String(
                                format: "%.6f, %.6f",
                                selection.coordinate.latitude,
                                selection.coordinate.longitude
                            ))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .monospaced()
                        }

                        if mode == .addPastVisit {
                            Button("Change Location") {
                                showingLocationPicker = true
                            }
                        }
                    } else if mode == .addPastVisit {
                        Button {
                            showingLocationPicker = true
                        } label: {
                            Label("Search Apple Maps or choose on map", systemImage: "map")
                                .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
                        }
                        Text("Choose where the visit happened. Your current GPS location will not be used.")
                            .font(.caption)
                            .foregroundStyle(.secondary)
                    } else if let manualLocation {
                        VStack(alignment: .leading, spacing: 4) {
                            Label("Current location ready", systemImage: "location.fill")
                                .foregroundStyle(.green)
                            Text(String(
                                format: "%.6f, %.6f • ±%.0fm",
                                manualLocation.coordinate.latitude,
                                manualLocation.coordinate.longitude,
                                manualLocation.horizontalAccuracy
                            ))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .monospaced()
                        }
                    } else {
                        VStack(alignment: .leading, spacing: 8) {
                            Label("Waiting for a fresh accurate location", systemImage: "location")
                                .foregroundStyle(.secondary)
                            Text("Manual visits require a saved location or a current location from the last 5 minutes with accuracy under 100 meters.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Button("Request Location") {
                                locationManager.requestCurrentLocationForManualVisit()
                            }
                        }
                    }
                }

                Section("Reuse") {
                    if let selectedSavedPlace {
                        Label("Using saved location: \(selectedSavedPlace.name)", systemImage: "bookmark.fill")
                    } else {
                        Button {
                            saveAsReusableLocation.toggle()
                        } label: {
                            HStack {
                                Text("Save as reusable location")
                                    .foregroundStyle(.primary)
                                Spacer()
                                Image(systemName: saveAsReusableLocation ? "checkmark.circle.fill" : "circle")
                                    .foregroundStyle(saveAsReusableLocation ? .green : .secondary)
                            }
                            .contentShape(Rectangle())
                        }
                        .buttonStyle(.plain)

                        if saveAsReusableLocation {
                            Picker("Match radius", selection: $reusableRadiusMeters) {
                                Text("50 m").tag(50.0)
                                Text("100 m").tag(100.0)
                                Text("150 m").tag(150.0)
                                Text("250 m").tag(250.0)
                            }
                            Text("Future automatic visits within this radius will use this name before network geocoding.")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                        }
                    }
                }

                if let errorMessage {
                    Section {
                        Text(errorMessage)
                            .foregroundStyle(.red)
                    }
                }
            }
            .navigationTitle(Text(draftTitle))
            .navigationBarTitleDisplayMode(.inline)
            .onAppear {
                viewModel.loadSavedPlaces()
                if mode.requestsCurrentLocation && locationSelection == nil {
                    locationManager.requestCurrentLocationForManualVisit()
                }
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .disabled(isSaveDisabled)
                }
            }
            .alert("Overlapping Visit", isPresented: $showingOverlapConfirmation) {
                Button("Cancel", role: .cancel) {}
                Button("Save Anyway") { save(allowOverlap: true) }
            } message: {
                Text(viewModel.visitMutationError?.localizedDescription ?? "")
            }
            .sheet(isPresented: $showingLocationPicker) {
                PlaceSelectionView(
                    initialSelection: locationSelection,
                    initialRegion: initialSearchRegion,
                    onSelect: applyLocationSelection
                )
                .presentationDetents([.large])
            }
        }
    }

    private func applySavedPlace(_ place: SavedPlace) {
        applyLocationSelection(.savedPlace(place))
    }

    private func applyLocationSelection(_ newSelection: ManualLocationSelection) {
        var selection = newSelection

        // When the form started without a coordinate, keep details the user typed
        // before opening the map if the pin lookup did not find replacements. When
        // changing an existing coordinate, never carry its metadata to the new pin.
        if locationSelection == nil {
            let selectedName = selection.name.trimmingCharacters(in: .whitespacesAndNewlines)
            let selectedAddress = selection.address?.trimmingCharacters(in: .whitespacesAndNewlines)
            let fallbackName = selectedName.isEmpty ? name : selection.name
            let fallbackAddress = selectedAddress?.isEmpty == false
                ? selection.address
                : (address.isEmpty ? nil : address)

            if fallbackName != selection.name || fallbackAddress != selection.address {
                selection.updateUserEnteredMetadata(
                    name: fallbackName,
                    address: fallbackAddress
                )
            }
        }

        locationSelection = selection
        selectedSavedPlaceID = selection.savedPlaceID
        name = selection.name
        address = selection.address ?? ""
        saveAsReusableLocation = false
        errorMessage = nil
    }

    private func deleteSavedPlaces(at offsets: IndexSet) {
        let placesToDelete = offsets.compactMap { index in
            viewModel.savedPlaces.indices.contains(index) ? viewModel.savedPlaces[index] : nil
        }
        for place in placesToDelete {
            if selectedSavedPlaceID == place.id {
                selectedSavedPlaceID = nil
                locationSelection = nil
            }
            viewModel.deleteSavedPlace(place)
        }
    }

    private func save(allowOverlap: Bool = false) {
        guard let coordinate else {
            if mode == .addPastVisit {
                errorMessage = String(localized: "Search for the place, choose it on the map, or select a saved location.")
            } else {
                errorMessage = String(localized: "Choose a saved location or request a fresh accurate current location.")
                locationManager.requestCurrentLocationForManualVisit()
            }
            return
        }

        guard validationMessage == nil else {
            errorMessage = validationMessage
            return
        }

        isSaving = true
        let visit = viewModel.createManualVisit(
            name: name,
            address: address,
            coordinate: coordinate,
            arrivedAt: arrivedAt,
            departedAt: mode.requiresDeparture ? departedAt : (isStillHere ? nil : departedAt),
            placeSource: locationSelection?.visitPlaceSource ?? .userEntered,
            allowOverlap: allowOverlap
        )

        guard let visit else {
            isSaving = false
            if case .overlap = viewModel.visitMutationError {
                showingOverlapConfirmation = true
            } else {
                errorMessage = viewModel.visitMutationError?.localizedDescription
            }
            return
        }

        if saveAsReusableLocation && selectedSavedPlaceID == nil {
            _ = viewModel.createSavedPlace(
                name: name,
                address: address,
                coordinate: coordinate,
                radiusMeters: reusableRadiusMeters
            )
        }
        onSaved?(visit)
        dismiss()
    }
}

struct DateRangeFilterSheet: View {
    @Binding var dateRange: ClosedRange<Date>
    @Binding var isPresented: Bool
    var onApply: (() -> Void)? = nil

    @State private var startDate: Date
    @State private var endDate: Date

    init(
        dateRange: Binding<ClosedRange<Date>>,
        isPresented: Binding<Bool>,
        onApply: (() -> Void)? = nil
    ) {
        _dateRange = dateRange
        _isPresented = isPresented
        self.onApply = onApply
        _startDate = State(initialValue: dateRange.wrappedValue.lowerBound)
        _endDate = State(initialValue: dateRange.wrappedValue.upperBound)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section("Quick Select") {
                    Button("Today") {
                        let today = Calendar.current.startOfDay(for: Date())
                        startDate = today
                        endDate = Date()
                    }

                    Button("Yesterday") {
                        let calendar = Calendar.current
                        let startOfToday = calendar.startOfDay(for: Date())
                        startDate = calendar.date(byAdding: .day, value: -1, to: startOfToday)!
                        endDate = Date(timeIntervalSinceReferenceDate: startOfToday.timeIntervalSinceReferenceDate.nextDown)
                    }

                    Button("Last 7 Days") {
                        endDate = Date()
                        startDate = Calendar.current.date(byAdding: .day, value: -7, to: endDate)!
                    }

                    Button("Last 30 Days") {
                        endDate = Date()
                        startDate = Calendar.current.date(byAdding: .day, value: -30, to: endDate)!
                    }

                    Button("All Time") {
                        startDate = Date.distantPast
                        endDate = Date()
                    }
                }

                Section("Custom Range") {
                    DatePicker("From", selection: $startDate, displayedComponents: .date)
                    DatePicker("To", selection: $endDate, displayedComponents: .date)
                }
            }
            .navigationTitle("Filter by Date")
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") {
                        isPresented = false
                    }
                }

                ToolbarItem(placement: .confirmationAction) {
                    Button("Apply") {
                        dateRange = startDate...endDate
                        onApply?()
                        isPresented = false
                    }
                }
            }
        }
        .presentationDetents([.medium])
    }
}

struct VisitQuickView: View {
    @Bindable var visit: Visit
    @Bindable var viewModel: LocationViewModel
    @State private var checkoutError: String?
    @State private var nameText: String = ""
    @FocusState private var isNameFieldFocused: Bool

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 16) {
                    // Header
                    VStack(alignment: .leading, spacing: 6) {
                        HStack(alignment: .center, spacing: 8) {
                            VStack(alignment: .leading, spacing: 4) {
                                Text("Name")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)

                                TextField("Visit name", text: $nameText)
                                    .font(.title2.weight(.semibold))
                                    .textFieldStyle(.plain)
                                    .submitLabel(.done)
                                    .focused($isNameFieldFocused)
                                    .onSubmit(saveName)
                                    .onChange(of: isNameFieldFocused) { _, focused in
                                        if !focused {
                                            saveName()
                                        }
                                    }
                            }

                            Spacer()

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
                                    .padding(.horizontal, 8)
                                    .padding(.vertical, 4)
                                    .background(.blue)
                                    .foregroundStyle(.white)
                                    .clipShape(Capsule())
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

                        HStack(spacing: 8) {
                            MapVisitStatusBadge(
                                text: visit.confirmationStatus.displayName,
                                color: statusColor
                            )
                            MapVisitStatusBadge(
                                text: visit.source.displayName,
                                color: .gray
                            )
                        }

                        NearbyVisitNameSuggestionSection(visit: visit) { suggestion in
                            correctVisit(with: suggestion)
                        }
                    }

                    Divider()

                    // Time info
                    HStack(spacing: 24) {
                        VStack(alignment: .leading) {
                            Text("Arrived")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Text(visit.arrivedAt.formatted(date: .abbreviated, time: .shortened))
                                .font(.subheadline)
                        }

                        if let departed = visit.departedAt {
                            VStack(alignment: .leading) {
                                Text("Departed")
                                    .font(.caption)
                                    .foregroundStyle(.secondary)
                                Text(departed.formatted(date: .abbreviated, time: .shortened))
                                    .font(.subheadline)
                            }
                        }

                        Spacer()

                        VStack(alignment: .trailing) {
                            Text("Duration")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Text(visit.formattedDuration)
                                .font(.subheadline)
                                .fontWeight(.medium)
                        }
                    }

                    if let notes = visit.notes, !notes.isEmpty {
                        Divider()

                        VStack(alignment: .leading, spacing: 4) {
                            Text("Notes")
                                .font(.caption)
                                .foregroundStyle(.secondary)
                            Text(notes)
                                .font(.subheadline)
                        }
                    }

                    if visit.confirmationStatus == .unconfirmed {
                        Button {
                            saveName()
                            viewModel.confirmVisit(visit)
                        } label: {
                            Label("Confirm Place", systemImage: "checkmark.seal")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                    }

                    if visit.source == .manual, visit.departedAt == nil {
                        Button {
                            if !viewModel.checkoutVisit(visit) {
                                checkoutError = viewModel.visitMutationError?.localizedDescription
                            }
                        } label: {
                            Label("Check Out", systemImage: "rectangle.portrait.and.arrow.right")
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .disabled(visit.arrivedAt > Date())
                        .accessibilityHint("Ends this manual visit at the current time.")
                    }

                    NavigationLink {
                        VisitDetailView(visit: visit, viewModel: viewModel)
                    } label: {
                        Label("More Details", systemImage: "info.circle")
                            .frame(maxWidth: .infinity)
                    }
                    .buttonStyle(.bordered)
                }
                .padding()
            }
            .navigationBarTitleDisplayMode(.inline)
            .onAppear {
                nameText = visit.displayName
            }
            .alert("Unable to Check Out", isPresented: Binding(
                get: { checkoutError != nil },
                set: { if !$0 { checkoutError = nil } }
            )) {
                Button("OK", role: .cancel) { checkoutError = nil }
            } message: {
                Text(checkoutError ?? "")
            }
            .toolbar {
                ToolbarItemGroup(placement: .keyboard) {
                    Spacer()
                    Button("Done") {
                        isNameFieldFocused = false
                        saveName()
                    }
                }
            }
        }
    }

    private func saveName() {
        viewModel.updateVisitName(visit, customName: nameText)
        nameText = visit.displayName
    }

    private func resetName() {
        viewModel.clearVisitName(visit)
        nameText = visit.displayName
    }

    private var statusColor: Color {
        switch visit.confirmationStatus {
        case .unconfirmed: return .orange
        case .confirmed: return .green
        case .corrected: return .blue
        }
    }

    private func correctVisit(with suggestion: NearbyPlaceSuggestion) {
        isNameFieldFocused = false
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
}

private struct MapVisitStatusBadge: View {
    let text: String
    let color: Color

    var body: some View {
        Text(text.uppercased())
            .font(.caption2.weight(.semibold))
            .padding(.horizontal, 8)
            .padding(.vertical, 4)
            .foregroundStyle(color)
            .background(color.opacity(0.12), in: Capsule())
    }
}

// MARK: - Quick Filter Bar

enum MapDatePreset: CaseIterable, Hashable {
    case today, yesterday, sevenDays, thirtyDays, all

    var label: String {
        switch self {
        case .today: return String(localized: "Today")
        case .yesterday: return String(localized: "Yesterday")
        case .sevenDays: return String(localized: "7D")
        case .thirtyDays: return String(localized: "30D")
        case .all: return String(localized: "All")
        }
    }

    var accessibilityLabel: String {
        switch self {
        case .today: return String(localized: "Today")
        case .yesterday: return String(localized: "Yesterday")
        case .sevenDays: return String(localized: "Last 7 days")
        case .thirtyDays: return String(localized: "Last 30 days")
        case .all: return String(localized: "All time")
        }
    }

    func range(referenceDate: Date = Date()) -> ClosedRange<Date> {
        let calendar = Calendar.current
        switch self {
        case .today:
            return calendar.startOfDay(for: referenceDate)...referenceDate
        case .yesterday:
            let startOfToday = calendar.startOfDay(for: referenceDate)
            let startOfYesterday = calendar.date(byAdding: .day, value: -1, to: startOfToday)!
            let endOfYesterday = Date(timeIntervalSinceReferenceDate: startOfToday.timeIntervalSinceReferenceDate.nextDown)
            return startOfYesterday...endOfYesterday
        case .sevenDays:
            return calendar.date(byAdding: .day, value: -7, to: referenceDate)!...referenceDate
        case .thirtyDays:
            return calendar.date(byAdding: .day, value: -30, to: referenceDate)!...referenceDate
        case .all:
            return Date.distantPast...referenceDate
        }
    }
}

struct FilterBarToggle: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let isOpen: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            Image(systemName: isOpen ? "xmark" : "slider.horizontal.3")
                .font(.body.weight(.semibold))
                .foregroundStyle(isOpen ? Color.white : Color.primary.opacity(0.75))
                .frame(width: 44, height: 44)
                .background {
                    Circle()
                        .fill(isOpen ? AnyShapeStyle(TE.accent) : AnyShapeStyle(.ultraThinMaterial))
                        .overlay {
                            Circle()
                                .strokeBorder(
                                    LinearGradient(
                                        colors: [.white.opacity(0.55), .white.opacity(0.08)],
                                        startPoint: .topLeading,
                                        endPoint: .bottomTrailing
                                    ),
                                    lineWidth: 0.8
                                )
                        }
                        .shadow(color: .black.opacity(0.18), radius: 14, x: 0, y: 6)
                }
                .contentTransition(reduceMotion ? .identity : .symbolEffect(.replace))
        }
        .buttonStyle(.plain)
        .accessibilityLabel(isOpen ? "Close map filters" : "Open map filters")
        .accessibilityHint(isOpen ? "Hides date, layer, and fit controls." : "Shows date, layer, and fit controls.")
        .sensoryFeedback(.impact(flexibility: .soft), trigger: isOpen)
    }
}

struct QuickFilterBar: View {
    let activePreset: MapDatePreset?
    @Binding var showTravelPath: Bool
    @Binding var showPointMarkers: Bool
    @Binding var showStartEndMarkers: Bool
    @Binding var showSessionPath: Bool
    @Binding var showVisitMarkers: Bool
    @Binding var showPhotoMarkers: Bool
    @Binding var showPhotoMarkerImages: Bool
    @Binding var showPhotoMarkerDots: Bool
    @Binding var snapTravelPathToRoads: Bool
    @Binding var showStraightLinePathSegments: Bool
    let isRouteReplayEnabled: Bool
    let canReplayRoute: Bool
    let hasSessionPoints: Bool
    let onSelectPreset: (MapDatePreset) -> Void
    let onSelectCustom: () -> Void
    let onToggleRouteReplay: () -> Void
    let onFitContent: () -> Void
    let onFitSession: (() -> Void)?
    @State private var activeLayerHelp: LayerToggleHelp?

    var body: some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: 6) {
                #if DEBUG
                if ProcessInfo.processInfo.arguments.contains("--seed-screenshot-data") {
                    LayerToggleButton(
                        systemImage: "road.lanes",
                        label: "Road-matched path",
                        help: .roadMatchedPath,
                        isOn: $snapTravelPathToRoads,
                        activeHelp: $activeLayerHelp
                    )
                    LayerToggleButton(
                        systemImage: "line.diagonal",
                        label: "Straight-line path gaps",
                        help: .straightLinePathGaps,
                        isOn: $showStraightLinePathSegments,
                        activeHelp: $activeLayerHelp
                    )
                    PillSeparator()
                }
                #endif

                ForEach(MapDatePreset.allCases, id: \.self) { preset in
                    PresetPill(
                        label: preset.label,
                        accessibilityLabel: preset.accessibilityLabel,
                        isActive: activePreset == preset
                    ) {
                        onSelectPreset(preset)
                    }
                }

                PresetPill(
                    label: String(localized: "Custom"),
                    icon: "calendar",
                    accessibilityLabel: String(localized: "Custom date range"),
                    isActive: activePreset == nil
                ) {
                    onSelectCustom()
                }

                PillSeparator()

                LayerToggleButton(
                    systemImage: "mappin.circle.fill",
                    label: "Visit markers",
                    help: .visitMarkers,
                    isOn: $showVisitMarkers,
                    activeHelp: $activeLayerHelp
                )
                LayerToggleButton(
                    systemImage: "camera.fill",
                    label: "Photo markers",
                    help: .photoMarkers,
                    isOn: $showPhotoMarkers,
                    activeHelp: $activeLayerHelp
                )
                LayerToggleButton(
                    systemImage: "photo",
                    label: "Photo images",
                    help: .photoImages,
                    isOn: $showPhotoMarkerImages,
                    activeHelp: $activeLayerHelp
                )
                .disabled(showPhotoMarkerDots)
                LayerToggleButton(
                    systemImage: "circle.fill",
                    label: "Photo dots",
                    help: .photoDots,
                    isOn: $showPhotoMarkerDots,
                    activeHelp: $activeLayerHelp
                )
                LayerToggleButton(
                    systemImage: "point.topleft.down.to.point.bottomright.curvepath",
                    label: "Travel path",
                    help: .travelPath,
                    isOn: $showTravelPath,
                    activeHelp: $activeLayerHelp
                )
                LayerToggleButton(
                    systemImage: "road.lanes",
                    label: "Road-matched path",
                    help: .roadMatchedPath,
                    isOn: $snapTravelPathToRoads,
                    activeHelp: $activeLayerHelp
                )
                LayerToggleButton(
                    systemImage: "line.diagonal",
                    label: "Straight-line path gaps",
                    help: .straightLinePathGaps,
                    isOn: $showStraightLinePathSegments,
                    activeHelp: $activeLayerHelp
                )
                LayerToggleButton(
                    systemImage: "smallcircle.filled.circle",
                    label: "Point markers",
                    help: .pointMarkers,
                    isOn: $showPointMarkers,
                    activeHelp: $activeLayerHelp
                )
                LayerToggleButton(
                    systemImage: "flag.fill",
                    label: "Start and end markers",
                    help: .startEndMarkers,
                    isOn: $showStartEndMarkers,
                    activeHelp: $activeLayerHelp
                )
                if hasSessionPoints {
                    LayerToggleButton(
                        systemImage: "waveform.path.ecg",
                        label: "Active session path",
                        help: .activeSessionPath,
                        isOn: $showSessionPath,
                        activeHelp: $activeLayerHelp
                    )
                }

                RouteReplayToggleButton(
                    isOn: isRouteReplayEnabled,
                    isEnabled: canReplayRoute,
                    action: onToggleRouteReplay
                )

                PillSeparator()

                FitMenuButton(
                    onFitContent: onFitContent,
                    onFitSession: onFitSession
                )
            }
            .padding(.horizontal, 8)
            .padding(.vertical, 6)
        }
        .background {
            Capsule()
                .fill(.ultraThinMaterial)
                .overlay {
                    Capsule()
                        .strokeBorder(
                            LinearGradient(
                                colors: [.white.opacity(0.55), .white.opacity(0.08)],
                                startPoint: .topLeading,
                                endPoint: .bottomTrailing
                            ),
                            lineWidth: 0.8
                        )
                }
                .shadow(color: .black.opacity(0.18), radius: 14, x: 0, y: 6)
        }
        #if DEBUG
        .overlay(alignment: .topLeading) {
            if ProcessInfo.processInfo.arguments.contains("--demo-road-layer-help") {
                LayerToggleHelpPopover(systemImage: "road.lanes", help: .roadMatchedPath, isOn: snapTravelPathToRoads)
                    .background(.regularMaterial, in: RoundedRectangle(cornerRadius: 14))
                    .shadow(color: .black.opacity(0.18), radius: 16, x: 0, y: 8)
                    .offset(x: 0, y: -190)
            }
        }
        .onAppear {
            if ProcessInfo.processInfo.arguments.contains("--demo-road-layer-help") {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                    activeLayerHelp = .roadMatchedPath
                }
            }
        }
        #endif
    }
}

struct PresetPill: View {
    let label: String
    var icon: String? = nil
    var accessibilityLabel: String? = nil
    let isActive: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 4) {
                if let icon = icon {
                    Image(systemName: icon)
                        .font(.caption.weight(.medium))
                        .accessibilityHidden(true)
                }
                Text(verbatim: label)
                    .font(.subheadline.weight(.semibold))
            }
            .foregroundStyle(isActive ? Color.white : Color.primary)
            .padding(.horizontal, 12)
            .padding(.vertical, 7)
            .frame(minHeight: 44)
            .background {
                Capsule()
                    .fill(isActive ? TE.accent : Color.clear)
            }
        }
        .buttonStyle(.plain)
        .accessibilityLabel(accessibilityLabel ?? label)
        .accessibilityValue(isActive ? String(localized: "Selected") : String(localized: "Not selected"))
        .accessibilityHint("Filters the map date range.")
    }
}

struct LayerToggleHelp: Identifiable, Equatable {
    let id: String
    let title: LocalizedStringResource
    let message: LocalizedStringResource

    static let visitMarkers = LayerToggleHelp(
        id: "visit-markers",
        title: "Visit markers",
        message: "Shows places Whereday detected as stops in the selected date range. Hide them when you want an uncluttered route-only map."
    )

    static let travelPath = LayerToggleHelp(
        id: "travel-path",
        title: "Travel path",
        message: "Draws your GPS trail for the selected date range. Turn it off to focus on visits, pins, or the live session."
    )

    static let photoMarkers = LayerToggleHelp(
        id: "photo-markers",
        title: "Photo markers",
        message: "Shows Photos library pictures for the selected date range. Whereday uses photo GPS when available, otherwise the nearest route point or visit; photo files stay in Photos."
    )

    static let photoImages = LayerToggleHelp(
        id: "photo-images",
        title: "Photo images",
        message: "Shows thumbnail previews inside photo markers. Turn this off to use compact camera pins while keeping photos available on the map. Photo dots override this setting without changing it."
    )

    static let photoDots = LayerToggleHelp(
        id: "photo-dots",
        title: "Photo dots",
        message: "Uses small dots instead of photo images or camera pins. Larger dots represent grouped photos. Tap a dot to browse; previews load only when you open the photos. Turn this off to restore your image or pin preference."
    )

    static let roadMatchedPath = LayerToggleHelp(
        id: "road-matched-path",
        title: "Road-matched path",
        message: "Uses Apple Maps routes to bend sparse GPS gaps onto nearby roads when possible, instead of drawing rough point-to-point lines."
    )

    static let straightLinePathGaps = LayerToggleHelp(
        id: "straight-line-path-gaps",
        title: "Straight-line path gaps",
        message: "Reveals fallback straight segments wherever road matching has no confident route. Useful when you want to inspect raw gaps."
    )

    static let pointMarkers = LayerToggleHelp(
        id: "point-markers",
        title: "Point markers",
        message: "Shows sampled GPS dots along the path. Tap a dot to inspect its timestamp, coordinates, and accuracy."
    )

    static let startEndMarkers = LayerToggleHelp(
        id: "start-end-markers",
        title: "Start and end markers",
        message: "Marks the first and latest recorded points in the current date range so you can see where the route begins and ends."
    )

    static let activeSessionPath = LayerToggleHelp(
        id: "active-session-path",
        title: "Active session path",
        message: "Overlays the route for the tracking session that is currently running, separate from the historical date-range path."
    )
}

struct LayerToggleButtonLabel: View {
    let systemImage: String
    let isOn: Bool

    private var iconColor: Color {
        isOn ? Color.white : Color.primary.opacity(0.55)
    }

    private var fillColor: Color {
        isOn ? TE.accent : Color.clear
    }

    var body: some View {
        Image(systemName: systemImage)
            .font(.subheadline.weight(.semibold))
            .frame(width: 44, height: 44)
            .foregroundStyle(iconColor)
            .background(Circle().fill(fillColor))
    }
}

struct LayerToggleButton: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let systemImage: String
    let label: LocalizedStringResource
    let help: LayerToggleHelp
    @Binding var isOn: Bool
    @Binding var activeHelp: LayerToggleHelp?

    private var isShowingHelp: Binding<Bool> {
        Binding(
            get: { activeHelp?.id == help.id },
            set: { isPresented in
                guard !isPresented, activeHelp?.id == help.id else { return }
                activeHelp = nil
            }
        )
    }

    var body: some View {
        buttonContent
            .popover(isPresented: isShowingHelp) {
                popoverContent
            }
            .sensoryFeedback(.selection, trigger: activeHelp?.id == help.id)
    }

    private var buttonContent: some View {
        LayerToggleButtonLabel(systemImage: systemImage, isOn: isOn)
            .contentShape(Circle())
            .gesture(layerGesture)
            .accessibilityElement(children: .ignore)
            .accessibilityLabel(Text(label))
            .accessibilityValue(isOn ? "Shown" : "Hidden")
            .accessibilityHint("Double-tap to toggle this map layer. Touch and hold for an explanation.")
            .accessibilityAddTraits(.isButton)
            .accessibilityAction {
                toggleLayer()
            }
            .accessibilityAction(named: Text("Show Explanation")) {
                activeHelp = help
            }
    }

    private var popoverContent: some View {
        LayerToggleHelpPopover(systemImage: systemImage, help: help, isOn: isOn)
            .presentationCompactAdaptation(.popover)
    }

    private var layerGesture: some Gesture {
        LongPressGesture(minimumDuration: 0.45)
            .exclusively(before: TapGesture())
            .onEnded { value in
                switch value {
                case .first(_):
                    activeHelp = help
                case .second(_):
                    toggleLayer()
                }
            }
    }

    private func toggleLayer() {
        activeHelp = nil
        withAnimation(reduceMotion ? nil : .spring(duration: 0.25)) {
            isOn.toggle()
        }
    }
}

struct LayerToggleHelpPopover: View {
    let systemImage: String
    let help: LayerToggleHelp
    let isOn: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Image(systemName: systemImage)
                    .font(.headline.weight(.semibold))
                    .foregroundStyle(Color.white)
                    .frame(width: 34, height: 34)
                    .background {
                        Circle().fill(isOn ? TE.accent : TE.textMuted.opacity(0.55))
                    }
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 2) {
                    Text(help.title)
                        .font(TE.mono(.subheadline, weight: .bold))
                        .foregroundStyle(.primary)
                    Text(isOn ? "CURRENTLY SHOWN" : "CURRENTLY HIDDEN")
                        .font(TE.mono(.caption2, weight: .semibold))
                        .tracking(1.2)
                        .foregroundStyle(isOn ? TE.accent : TE.textMuted)
                }
            }

            Text(help.message)
                .font(.footnote)
                .foregroundStyle(.secondary)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(width: 260, alignment: .leading)
        .padding(14)
    }
}

struct RouteReplayToggleButton: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let isOn: Bool
    let isEnabled: Bool
    let action: () -> Void

    var body: some View {
        Button {
            withAnimation(reduceMotion ? nil : .spring(duration: 0.25)) {
                action()
            }
        } label: {
            Image(systemName: isOn ? "pause.rectangle.fill" : "play.rectangle")
                .font(.subheadline.weight(.semibold))
                .frame(width: 44, height: 44)
                .foregroundStyle(foregroundStyle)
                .background {
                    Circle()
                        .fill(isOn ? TE.accent : Color.clear)
                }
                .contentTransition(reduceMotion ? .identity : .symbolEffect(.replace))
        }
        .buttonStyle(.plain)
        .disabled(!isEnabled && !isOn)
        .accessibilityLabel(isOn ? "Close route replay" : "Open route replay")
        .accessibilityValue(isEnabled ? (isOn ? "Replay controls are open" : "Available") : "Unavailable")
        .accessibilityHint(isEnabled ? "Shows controls for scrubbing and replaying the visible route." : "Select a date range with at least two path points.")
    }

    private var foregroundStyle: Color {
        if isOn { return .white }
        return isEnabled ? Color.primary.opacity(0.7) : Color.primary.opacity(0.25)
    }
}

struct PillSeparator: View {
    var body: some View {
        Rectangle()
            .fill(Color.primary.opacity(0.12))
            .frame(width: 1, height: 20)
            .padding(.horizontal, 2)
            .accessibilityHidden(true)
    }
}

struct FitMenuButton: View {
    let onFitContent: () -> Void
    let onFitSession: (() -> Void)?

    var body: some View {
        Menu {
            if let onFitSession {
                Button {
                    onFitSession()
                } label: {
                    Label("Fit Active Session", systemImage: "location.north.line")
                }
            }
            Button {
                onFitContent()
            } label: {
                Label("Fit All Visits", systemImage: "arrow.up.left.and.arrow.down.right")
            }
        } label: {
            Image(systemName: "scope")
                .font(.subheadline.weight(.semibold))
                .frame(width: 44, height: 44)
                .foregroundStyle(Color.primary.opacity(0.7))
        }
        .accessibilityLabel("Fit map")
        .accessibilityHint("Shows options for zooming the map to available content.")
    }
}

// MARK: - Route Replay Helpers

struct RouteReplaySnapshot {
    let index: Int
    let progress: Double
    let totalPointCount: Int
    let visiblePoints: [LocationPoint]
    let currentPoint: LocationPoint
    let elapsedDuration: TimeInterval
    let totalDuration: TimeInterval
    let distanceMeters: Double
    let totalDistanceMeters: Double

    var accessibilitySummary: String {
        let elapsed = RouteReplayCalculator.formattedDuration(elapsedDuration)
        let duration = RouteReplayCalculator.formattedDuration(totalDuration)
        let distance = DistanceFormatter.format(meters: distanceMeters, usesMetric: true)
        let totalDistance = DistanceFormatter.format(meters: totalDistanceMeters, usesMetric: true)
        return String(localized: "Point \(index + 1) of \(totalPointCount). Time \(currentPoint.accessibilityTimestamp). Elapsed \(elapsed) of \(duration). Distance \(distance) of \(totalDistance).")
    }
}

enum RouteReplayCalculator {
    static func clampedProgress(_ progress: Double) -> Double {
        guard progress.isFinite else { return 0 }
        return min(1, max(0, progress))
    }

    static func index(for progress: Double, pointCount: Int) -> Int? {
        guard pointCount > 0 else { return nil }
        guard pointCount > 1 else { return 0 }
        let lastIndex = pointCount - 1
        let scaled = clampedProgress(progress) * Double(lastIndex)
        return min(lastIndex, max(0, Int(scaled.rounded())))
    }

    static func progress(forIndex index: Int, pointCount: Int) -> Double {
        guard pointCount > 1 else { return 0 }
        let lastIndex = pointCount - 1
        let clampedIndex = min(lastIndex, max(0, index))
        return Double(clampedIndex) / Double(lastIndex)
    }

    static func playbackStepSize(pointCount: Int) -> Int {
        guard pointCount > 1 else { return 1 }
        // Keep long, downsampled routes from taking several minutes while still
        // advancing smoothly for short paths.
        let targetTicks = 180.0
        return max(1, Int(ceil(Double(pointCount - 1) / targetTicks)))
    }

    static func snapshot(points: [LocationPoint], progress: Double) -> RouteReplaySnapshot? {
        guard points.count >= 2, let index = index(for: progress, pointCount: points.count) else {
            return nil
        }

        let visiblePoints = Array(points.prefix(index + 1))
        let currentPoint = points[index]
        let firstTimestamp = points.first?.timestamp ?? currentPoint.timestamp
        let lastTimestamp = points.last?.timestamp ?? currentPoint.timestamp

        return RouteReplaySnapshot(
            index: index,
            progress: clampedProgress(progress),
            totalPointCount: points.count,
            visiblePoints: visiblePoints,
            currentPoint: currentPoint,
            elapsedDuration: currentPoint.timestamp.timeIntervalSince(firstTimestamp),
            totalDuration: lastTimestamp.timeIntervalSince(firstTimestamp),
            distanceMeters: totalDistance(in: visiblePoints),
            totalDistanceMeters: totalDistance(in: points)
        )
    }

    static func totalDistance(in points: [LocationPoint]) -> Double {
        guard points.count > 1 else { return 0 }
        var total: Double = 0
        for index in 1..<points.count {
            total += points[index - 1].distance(to: points[index])
        }
        return total
    }

    static func formattedDuration(_ duration: TimeInterval) -> String {
        let totalSeconds = max(0, Int(duration.rounded()))
        let hours = totalSeconds / 3600
        let minutes = (totalSeconds % 3600) / 60
        let seconds = totalSeconds % 60

        if hours > 0 {
            return String(format: "%d:%02d:%02d", hours, minutes, seconds)
        }
        return String(format: "%d:%02d", minutes, seconds)
    }
}

// MARK: - Road Snapping Helpers

struct RoadSnappingPoint: @unchecked Sendable {
    let coordinate: CLLocationCoordinate2D
    let timestamp: Date
    let speed: Double?
    let horizontalAccuracy: Double

    init(
        coordinate: CLLocationCoordinate2D,
        timestamp: Date,
        speed: Double? = nil,
        horizontalAccuracy: Double
    ) {
        self.coordinate = coordinate
        self.timestamp = timestamp
        self.speed = speed
        self.horizontalAccuracy = horizontalAccuracy
    }

    init(point: LocationPoint) {
        self.init(
            coordinate: point.coordinate,
            timestamp: point.timestamp,
            speed: point.speed,
            horizontalAccuracy: point.horizontalAccuracy
        )
    }
}

extension RoadSnappingPoint {
    init(_ point: LocationPoint) {
        self.init(point: point)
    }
}

struct RoadSnappedRouteSegment: Identifiable, @unchecked Sendable {
    let id: String
    let startIndex: Int
    let endIndex: Int
    let coordinates: [CLLocationCoordinate2D]
    let isSnapped: Bool

    init(
        startIndex: Int,
        endIndex: Int,
        coordinates: [CLLocationCoordinate2D],
        isSnapped: Bool
    ) {
        self.id = "\(startIndex)-\(endIndex)-\(isSnapped ? "snapped" : "raw")"
        self.startIndex = startIndex
        self.endIndex = endIndex
        self.coordinates = coordinates
        self.isSnapped = isSnapped
    }

    func mergingRaw(
        endIndex newEndIndex: Int,
        coordinates newCoordinates: [CLLocationCoordinate2D]
    ) -> RoadSnappedRouteSegment {
        var mergedCoordinates = coordinates
        if newCoordinates.count > 1 {
            mergedCoordinates.append(contentsOf: newCoordinates.dropFirst())
        }

        return RoadSnappedRouteSegment(
            startIndex: startIndex,
            endIndex: newEndIndex,
            coordinates: mergedCoordinates,
            isSnapped: false
        )
    }

    func clipped(toEndIndex requestedEndIndex: Int) -> RoadSnappedRouteSegment? {
        guard !isSnapped else { return nil }
        let clippedEndIndex = min(endIndex, max(startIndex, requestedEndIndex))
        guard clippedEndIndex > startIndex else { return nil }

        let coordinateCount = clippedEndIndex - startIndex + 1
        guard coordinates.count >= coordinateCount else { return nil }

        return RoadSnappedRouteSegment(
            startIndex: startIndex,
            endIndex: clippedEndIndex,
            coordinates: Array(coordinates.prefix(coordinateCount)),
            isSnapped: false
        )
    }
}

struct RoadSnappedRoute: @unchecked Sendable {
    let sourceFingerprint: Int
    let sourcePointCount: Int
    let segments: [RoadSnappedRouteSegment]

    var hasSnappedSegments: Bool {
        segments.contains { $0.isSnapped }
    }

    func segments(upTo sourceIndex: Int) -> [RoadSnappedRouteSegment] {
        guard sourceIndex > 0 else { return [] }

        var visibleSegments: [RoadSnappedRouteSegment] = []
        for segment in segments {
            if segment.endIndex <= sourceIndex {
                visibleSegments.append(segment)
                continue
            }

            if segment.startIndex < sourceIndex,
               let clipped = segment.clipped(toEndIndex: sourceIndex) {
                visibleSegments.append(clipped)
            }
            break
        }

        return visibleSegments
    }
}

enum RoadSnappedRouteBuilder {
    private static let minimumSnapDistance: CLLocationDistance = 120
    private static let maximumSnapDistance: CLLocationDistance = 25_000
    private static let maximumSnapTimeGap: TimeInterval = 2 * 60 * 60
    private static let maximumSnapSpeed: CLLocationSpeed = 55
    private static let walkingSpeedThreshold: CLLocationSpeed = 3.2
    private static let maximumDirectionsRequests = 40
    private static let duplicateCoordinateThreshold: CLLocationDistance = 2

    static func fingerprint(for points: [LocationPoint]) -> Int {
        var hasher = Hasher()
        hasher.combine(points.count)

        for point in points {
            hasher.combine(point.id)
            hasher.combine(point.latitude)
            hasher.combine(point.longitude)
            hasher.combine(point.timestamp)
            hasher.combine(point.isOutlier)
        }

        return hasher.finalize()
    }

    static func taskFingerprint(for points: @autoclosure () -> [LocationPoint], isEnabled: Bool) -> Int {
        // Keep point filtering and hashing lazy while road snapping is disabled.
        guard isEnabled else { return 0 }
        var hasher = Hasher()
        hasher.combine(isEnabled)
        hasher.combine(fingerprint(for: points()))
        return hasher.finalize()
    }

    static func buildRoute(
        for points: [RoadSnappingPoint],
        sourceFingerprint: Int
    ) async -> RoadSnappedRoute {
        guard points.count >= 2 else {
            return RoadSnappedRoute(
                sourceFingerprint: sourceFingerprint,
                sourcePointCount: points.count,
                segments: []
            )
        }

        let candidates = zip(points.indices, points.dropFirst()).compactMap { index, endPoint -> CandidateSegment? in
            let startPoint = points[index]
            let endIndex = index + 1
            guard startPoint.coordinate.isValidRoadSnapCoordinate,
                  endPoint.coordinate.isValidRoadSnapCoordinate else {
                return nil
            }

            return CandidateSegment(
                startIndex: index,
                endIndex: endIndex,
                start: startPoint,
                end: endPoint
            )
        }

        let snapIndexes = Set(
            candidates
                .filter(shouldAttemptRoadSnap)
                .sorted { $0.distance > $1.distance }
                .prefix(maximumDirectionsRequests)
                .map(\.startIndex)
        )

        var routeSegments: [RoadSnappedRouteSegment] = []
        routeSegments.reserveCapacity(candidates.count)

        for candidate in candidates {
            if Task.isCancelled { break }

            if snapIndexes.contains(candidate.startIndex),
               let coordinates = await snappedCoordinates(for: candidate) {
                appendSegment(
                    startIndex: candidate.startIndex,
                    endIndex: candidate.endIndex,
                    coordinates: coordinates,
                    isSnapped: true,
                    to: &routeSegments
                )
            } else {
                appendSegment(
                    startIndex: candidate.startIndex,
                    endIndex: candidate.endIndex,
                    coordinates: [candidate.start.coordinate, candidate.end.coordinate],
                    isSnapped: false,
                    to: &routeSegments
                )
            }
        }

        return RoadSnappedRoute(
            sourceFingerprint: sourceFingerprint,
            sourcePointCount: points.count,
            segments: routeSegments
        )
    }

    private static func shouldAttemptRoadSnap(_ segment: CandidateSegment) -> Bool {
        guard segment.distance >= minimumSnapDistance,
              segment.distance <= maximumSnapDistance else {
            return false
        }

        guard segment.elapsed > 0,
              segment.elapsed <= maximumSnapTimeGap else {
            return false
        }

        let impliedSpeed = segment.distance / segment.elapsed
        guard impliedSpeed <= maximumSnapSpeed else {
            return false
        }

        let maxAccuracy = max(segment.start.horizontalAccuracy, segment.end.horizontalAccuracy)
        guard maxAccuracy <= max(120, segment.distance * 0.75) else {
            return false
        }

        return true
    }

    private static func appendSegment(
        startIndex: Int,
        endIndex: Int,
        coordinates: [CLLocationCoordinate2D],
        isSnapped: Bool,
        to segments: inout [RoadSnappedRouteSegment]
    ) {
        guard coordinates.count >= 2 else { return }

        if !isSnapped,
           let lastSegment = segments.last,
           !lastSegment.isSnapped,
           lastSegment.endIndex == startIndex {
            segments[segments.count - 1] = lastSegment.mergingRaw(
                endIndex: endIndex,
                coordinates: coordinates
            )
        } else {
            segments.append(RoadSnappedRouteSegment(
                startIndex: startIndex,
                endIndex: endIndex,
                coordinates: coordinates,
                isSnapped: isSnapped
            ))
        }
    }

    private static func snappedCoordinates(for segment: CandidateSegment) async -> [CLLocationCoordinate2D]? {
        for transport in preferredTransports(for: segment) {
            guard !Task.isCancelled else { return nil }

            let request = MKDirections.Request()
            request.source = MKMapItem(placemark: MKPlacemark(coordinate: segment.start.coordinate))
            request.destination = MKMapItem(placemark: MKPlacemark(coordinate: segment.end.coordinate))
            request.transportType = transport
            request.requestsAlternateRoutes = false

            do {
                let response = try await MKDirections(request: request).calculate()
                guard let route = response.routes.first,
                      isPlausible(route: route, for: segment) else {
                    continue
                }

                let coordinates = stitchedCoordinates(
                    route.polyline.roadSnapCoordinates,
                    source: segment.start.coordinate,
                    destination: segment.end.coordinate
                )

                guard coordinates.count >= 2 else { continue }
                return coordinates
            } catch {
                continue
            }
        }

        return nil
    }

    private static func preferredTransports(for segment: CandidateSegment) -> [MKDirectionsTransportType] {
        let impliedSpeed = segment.distance / max(segment.elapsed, 1)
        if impliedSpeed <= walkingSpeedThreshold {
            return [.walking, .automobile]
        }

        return [.automobile, .walking]
    }

    private static func isPlausible(route: MKRoute, for segment: CandidateSegment) -> Bool {
        guard route.distance.isFinite, route.distance > 0 else { return false }
        let maximumRouteDistance = max(segment.distance + 1_000, segment.distance * 4)
        return route.distance <= maximumRouteDistance
    }

    private static func stitchedCoordinates(
        _ routeCoordinates: [CLLocationCoordinate2D],
        source: CLLocationCoordinate2D,
        destination: CLLocationCoordinate2D
    ) -> [CLLocationCoordinate2D] {
        var stitched = [source]

        for coordinate in routeCoordinates where coordinate.isValidRoadSnapCoordinate {
            guard let previous = stitched.last else {
                stitched.append(coordinate)
                continue
            }

            if previous.roadSnapDistance(to: coordinate) > duplicateCoordinateThreshold {
                stitched.append(coordinate)
            }
        }

        if let previous = stitched.last,
           previous.roadSnapDistance(to: destination) <= duplicateCoordinateThreshold {
            stitched[stitched.count - 1] = destination
        } else {
            stitched.append(destination)
        }

        return stitched
    }

    private struct CandidateSegment {
        let startIndex: Int
        let endIndex: Int
        let start: RoadSnappingPoint
        let end: RoadSnappingPoint

        var distance: CLLocationDistance {
            start.coordinate.roadSnapDistance(to: end.coordinate)
        }

        var elapsed: TimeInterval {
            end.timestamp.timeIntervalSince(start.timestamp)
        }
    }
}

private extension MKPolyline {
    var roadSnapCoordinates: [CLLocationCoordinate2D] {
        guard pointCount > 0 else { return [] }
        var coordinates = Array(
            repeating: kCLLocationCoordinate2DInvalid,
            count: pointCount
        )
        getCoordinates(&coordinates, range: NSRange(location: 0, length: pointCount))
        return coordinates
    }
}

private extension CLLocationCoordinate2D {
    var isValidRoadSnapCoordinate: Bool {
        CLLocationCoordinate2DIsValid(self) && latitude.isFinite && longitude.isFinite
    }

    func roadSnapDistance(to other: CLLocationCoordinate2D) -> CLLocationDistance {
        MKMapPoint(self).distance(to: MKMapPoint(other))
    }
}

// MARK: - Helper Extension

extension MKCoordinateRegion {
    init(coordinates: [CLLocationCoordinate2D]) {
        guard !coordinates.isEmpty else {
            self = MKCoordinateRegion()
            return
        }

        var minLat = coordinates[0].latitude
        var maxLat = coordinates[0].latitude
        var minLon = coordinates[0].longitude
        var maxLon = coordinates[0].longitude

        for coordinate in coordinates {
            minLat = min(minLat, coordinate.latitude)
            maxLat = max(maxLat, coordinate.latitude)
            minLon = min(minLon, coordinate.longitude)
            maxLon = max(maxLon, coordinate.longitude)
        }

        let center = CLLocationCoordinate2D(
            latitude: (minLat + maxLat) / 2,
            longitude: (minLon + maxLon) / 2
        )

        let span = MKCoordinateSpan(
            latitudeDelta: max(0.01, (maxLat - minLat) * 1.5),
            longitudeDelta: max(0.01, (maxLon - minLon) * 1.5)
        )

        self = MKCoordinateRegion(center: center, span: span)
    }
}

#Preview {
    LocationMapView(viewModel: LocationViewModel(
        modelContext: try! ModelContainer(for: Visit.self, LocationPoint.self, RecordingSession.self, PhotoMoment.self, SavedPlace.self, CountryStay.self).mainContext,
        locationManager: LocationManager()
    ))
    .environment(ManualVisitRouter())
}


/// Bounds interactive GPS annotations independently of the stored route detail.
/// Camera rectangles are projected map bounds, so longitude wraps at the date line.
@MainActor
enum MapPointMarkerSampler {
    static let maximumMarkerCount = 160
    private static let cellSize: Double = 48

    static func sample(
        _ points: [LocationPoint],
        in rect: MKMapRect?,
        viewportSize: CGSize,
        selectedPointID: UUID? = nil
    ) -> [LocationPoint] {
        guard let rect, !rect.isNull,
              rect.origin.x.isFinite, rect.origin.y.isFinite,
              rect.width.isFinite, rect.height.isFinite,
              rect.width > 0, rect.height > 0,
              viewportSize.width.isFinite, viewportSize.height.isFinite,
              viewportSize.width > 0, viewportSize.height > 0 else {
            // Wait for the first camera/layout update rather than creating an
            // unbounded annotation set before the viewport is known.
            return []
        }

        var columns = max(1, Int(min(Double(maximumMarkerCount), viewportSize.width / cellSize)))
        var rows = max(1, Int(min(Double(maximumMarkerCount), viewportSize.height / cellSize)))
        if columns * rows > maximumMarkerCount {
            let scale = sqrt(Double(maximumMarkerCount) / Double(columns * rows))
            columns = max(1, Int(Double(columns) * scale))
            rows = max(1, Int(Double(rows) * scale))
        }

        let worldWidth = MKMapRect.world.width
        let width = min(rect.width, worldWidth)
        var cells: [Int: (index: Int, point: LocationPoint)] = [:]
        cells.reserveCapacity(columns * rows)

        for (index, point) in points.enumerated() {
            guard point.latitude.isFinite, point.longitude.isFinite,
                  abs(point.latitude) <= 90, abs(point.longitude) <= 180 else { continue }
            let projected = MKMapPoint(point.coordinate)
            var x = (projected.x - rect.minX).truncatingRemainder(dividingBy: worldWidth)
            if x < 0 { x += worldWidth }
            let y = projected.y - rect.minY
            guard x.isFinite, y.isFinite, x <= width, y >= 0, y <= rect.height else { continue }

            let column = min(columns - 1, Int(x / width * Double(columns)))
            let row = min(rows - 1, Int(y / rect.height * Double(rows)))
            let cell = row * columns + column
            if cells[cell] == nil || point.id == selectedPointID {
                cells[cell] = (index, point)
            }
        }

        // Keep route order and model identities stable across view updates.
        return cells.values.sorted { $0.index < $1.index }.map(\.point)
    }
}
