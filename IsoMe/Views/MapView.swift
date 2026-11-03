import SwiftUI
import MapKit
import SwiftData
import Combine

/// How the trace map sits on screen: the full-screen "scrub the day" map, or a
/// companion pane beside the timeline where the replay panel opens on request.
enum TraceMapPresentation {
    case full, companion
}

struct LocationMapView: View {
    @Environment(ManualVisitRouter.self) private var manualVisitRouter
    @State private var deepLinkedVisit: Visit?
    @State private var handledVisitRouteID: UUID?
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.horizontalSizeClass) private var horizontalSizeClass
    @Bindable var viewModel: LocationViewModel
    var focusedVisit: Visit? = nil
    var onSelectVisit: ((Visit) -> Void)? = nil
    var presentation: TraceMapPresentation = .full
    /// When set, the replay controls are published here for another pane instead of overlaid.
    var replayBridge: TraceReplayBridge? = nil
    /// Called when the inspector for `focusedVisit` is closed, so the owner can clear its focus
    /// (and a later tap on the same stay reopens it).
    var onCloseFocusedVisit: (() -> Void)? = nil
    /// When set, the companion's Replay button drives this instead of its own state, so the owner
    /// can rearrange its panes (for example the tabletop layout on a half-open Duo).
    var companionReplay: Binding<Bool>? = nil
    @ObservedObject private var locationManager: LocationManager
    /// Streets under the playhead, keyed by `StreetNameLookup.cellKey`.
    @State private var streetNames: [String: String] = [:]
    @State private var calendarDraft: CalendarEventDraft?
    @Namespace private var mapScope
    @AppStorage("usesMetricDistanceUnits") private var usesMetricDistanceUnits = true
    /// A time the person dragged to; cleared when playback or a new range takes over.
    @State private var scrubbedTime: Date?
    @State private var isTracePanelCollapsed = false
    @State private var showsCompanionReplay = false
    @State private var tracePanelHeight: CGFloat = 0
    @State private var dismissedFocusedVisitID: UUID?
    @State private var selectedVisit: Visit?
    @State private var selectedPointID: UUID?
    @State private var lastPointMarkerTap = Date.distantPast
    @State private var showingFilters = Self.initialFilterSheetVisibility
    @State private var manualVisitDraft: ManualVisitDraft?
    @State private var currentPlaceSuggestion: NearbyPlaceSuggestion?
    @State private var isLoadingCurrentPlace = false
    @State private var currentPlaceLookupFailed = false
    @State private var dismissedCurrentPlaceLookupKey: String?
    @State private var dismissedCurrentPlaceArea: CurrentPlacePromptSuppression?
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
    @AppStorage(MapLayerSettings.minimumStayMinutesKey) private var minimumStayMinutes = 0
    @AppStorage("photoPlacesPromptDismissed") private var photoPlacesPromptDismissed = false

    init(
        viewModel: LocationViewModel,
        focusedVisit: Visit? = nil,
        onSelectVisit: ((Visit) -> Void)? = nil,
        presentation: TraceMapPresentation = .full,
        replayBridge: TraceReplayBridge? = nil,
        onCloseFocusedVisit: (() -> Void)? = nil,
        companionReplay: Binding<Bool>? = nil
    ) {
        self.companionReplay = companionReplay
        self.viewModel = viewModel
        self.onCloseFocusedVisit = onCloseFocusedVisit
        self.focusedVisit = focusedVisit
        self.onSelectVisit = onSelectVisit
        self.presentation = presentation
        self.replayBridge = replayBridge
        self.locationManager = viewModel.locationManager
    }

    private static var initialFilterSheetVisibility: Bool {
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
        let visits = viewModel.visitsInDateRange(viewModel.mapDateRange)
        guard minimumStayMinutes > 0 else { return visits }
        // Ongoing stays are always drawn; only finished stops below the minimum are left off.
        let minimum = TimeInterval(minimumStayMinutes * 60)
        return visits.filter { visit in
            guard let departedAt = visit.departedAt else { return true }
            return departedAt.timeIntervalSince(visit.arrivedAt) >= minimum
        }
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
            .task(id: traceStreetLookupTarget?.key) {
                await refreshTraceStreetName()
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
            .onChange(of: viewModel.mapDateRange) { _, _ in
                scrubbedTime = nil
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
        .sheet(item: $calendarDraft) { draft in
            CalendarEventReviewSheet(draft: draft)
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
                activePreset: activePreset,
                recordedDays: recordedDays,
                layers: layerSettings,
                onApply: { preset in
                    activePreset = preset
                    if let preset {
                        viewModel.mapDateRange = preset.range()
                    }
                    viewModel.loadMapLocationPoints(in: viewModel.mapDateRange)
                    viewModel.loadMapPhotoMoments(in: viewModel.mapDateRange)
                    syncPhotoMomentsForCurrentRangeIfAuthorized()
                    scrubbedTime = nil
                    validateRouteReplayState()
                }
            )
        }
        .onChange(of: selectedVisit?.id) { _, _ in
            showSelectedVisitInCompanion()
        }
        .onChange(of: focusedVisit?.id, initial: true) { _, id in
            // A new focus (or the owner clearing it) reopens the inspector for the next tap.
            if id != dismissedFocusedVisitID { dismissedFocusedVisitID = nil }
            focusCompanionVisit()
        }
        .sheet(item: selectedVisitSheet) { visit in
            VisitQuickView(visit: visit, viewModel: viewModel)
                .presentationDetents([.medium, .large])
        }
        .sheet(item: $photoDetails.cluster) { cluster in
            if cluster.isArea {
                PhotoMomentPlacesView(places: cluster.places, title: String(localized: "Photos in This Area"), selection: photoDetails, placeName: photoPlaceName)
            } else {
                PhotoMomentClusterQuickView(cluster: cluster, selection: photoDetails)
                    .presentationDetents([.medium, .large])
            }
        }
        .sheet(isPresented: $photoDetails.showsPlaces) {
            PhotoMomentPlacesView(places: viewModel.mapPhotoPlaces, title: String(localized: "Moments"), selection: photoDetails, placeName: photoPlaceName)
        }
        .fullScreenCover(item: $photoDetails.photo) { photo in
            PhotoMomentFullScreenView(photo: photo)
        }
    }

    // MARK: - Trace canvas

    private static let pathStroke = StrokeStyle(lineWidth: 4, lineCap: .round, lineJoin: .round)

    private var traceDay: TraceDay {
        TraceDay(range: viewModel.mapDateRange, visits: filteredVisits, points: filteredPoints, now: Date())
    }

    /// Wide layouts inspect a visit in place, like the unfolded companion; phones keep the sheet.
    private var usesInlineInspector: Bool {
        horizontalSizeClass == .regular && onSelectVisit == nil
    }

    private var inspectorVisit: Visit? {
        guard usesInlineInspector else { return nil }
        if let selectedVisit { return selectedVisit }
        if let focusedVisit, focusedVisit.id != dismissedFocusedVisitID { return focusedVisit }
        return nil
    }

    /// The overlaid panel; with a replay bridge the controls sit in another pane instead.
    private var showsTracePanel: Bool {
        guard replayBridge == nil else { return false }
        return presentation == .full || isCompanionReplayShown
    }

    private var isCompanionReplayShown: Bool {
        companionReplay?.wrappedValue ?? showsCompanionReplay
    }

    private var mapCanvas: some View {
        @Bindable var photoDetails = viewModel.photoDetails
        let day = traceDay
        return GeometryReader { container in
            ZStack(alignment: .bottom) {
                traceMap(day: day, photoDetails: photoDetails)
                    .safeAreaPadding(.bottom, showsTracePanel ? tracePanelHeight : 0)

                VStack(spacing: 8) {
                    topChrome
                    Spacer(minLength: 0)
                    floatingCards(photoDetails: photoDetails)
                        .padding(.horizontal, 12)

                    if let replayBridge {
                        TimelineView(.everyMinute) { context in
                            publishTracePanel(day: day, now: context.date, to: replayBridge)
                        }
                    } else if showsTracePanel {
                        TimelineView(.everyMinute) { context in
                            tracePanel(day: day, now: context.date, maxHeight: container.size.height * 0.62)
                        }
                        .background {
                            GeometryReader { geometry in
                                Color.clear
                                    .onAppear { tracePanelHeight = geometry.size.height }
                                    .onChange(of: geometry.size.height) { _, height in
                                        tracePanelHeight = height
                                    }
                            }
                        }
                        .transition(.move(edge: .bottom).combined(with: .opacity))
                    }
                }
            }
        }
        .mapScope(mapScope)
        .animation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.82), value: isTracking)
        .animation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.82), value: isRouteReplayEnabled)
        .animation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.82), value: isCompanionReplayShown)
        .animation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.82), value: inspectorVisit?.id)
        .onChange(of: isTracking) { _, newValue in
            if !newValue {
                withAnimation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.82)) {
                    trackingPillExpanded = false
                }
            }
        }
    }

    private func traceMap(day: TraceDay, photoDetails: PhotoMomentDetailSelection) -> some View {
        MapReader { mapProxy in
            Map(position: $cameraPosition, selection: $selectedVisit, scope: mapScope) {
                // Current user location
                UserAnnotation()

                // Travel path from location points. In replay mode the whole
                // route stays faint and only the stretch the playhead has
                // reached is inked. When road snapping is enabled, sparse GPS
                // gaps are replaced with MapKit route polylines so the path
                // follows roads instead of drawing abrupt straight chords.
                if isRouteReplayEnabled, let routeReplaySnapshot {
                    if showTravelPath {
                        travelPath(upTo: nil, visiblePoints: filteredPoints, ink: TE.textPrimary.opacity(0.18))
                    }
                    travelPath(upTo: routeReplaySnapshot.index, visiblePoints: routeReplaySnapshot.visiblePoints, ink: TE.textPrimary)
                } else if showTravelPath && filteredPoints.count >= 2 {
                    travelPath(upTo: nil, visiblePoints: filteredPoints, ink: TE.textPrimary)
                }

                if isRouteReplayEnabled, let routeReplaySnapshot {
                    Annotation(String(localized: "Route replay position"), coordinate: routeReplaySnapshot.currentPoint.coordinate) {
                        RouteReplayMarker(snapshot: routeReplaySnapshot)
                    }
                    .annotationTitles(.hidden)
                }

                // Live session path (moved from Track tab)
                if showSessionPath && activeSessionPoints.count >= 2 {
                    let sessionCoordinates = activeSessionPoints.map { $0.coordinate }
                    MapPolyline(coordinates: sessionCoordinates)
                        .stroke(TE.inkRust, style: Self.pathStroke)
                }

                if showSessionPath, let firstSessionPoint = activeSessionPoints.first {
                    Annotation(String(localized: "Path start"), coordinate: firstSessionPoint.coordinate) {
                        StartMarker(
                            accessibilityLabel: String(localized: "Active session start"),
                            accessibilityValue: firstSessionPoint.accessibilityValue
                        )
                    }
                    .annotationTitles(.hidden)
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
                    .annotationTitles(.hidden)
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

                // Visit markers, inked like the day's places on the dial.
                if showVisitMarkers {
                    ForEach(filteredVisits) { visit in
                        Annotation(
                            visit.displayName,
                            coordinate: visit.coordinate,
                            anchor: .center
                        ) {
                            Button {
                                selectedPointID = nil
                                selectedVisit = visit
                            } label: {
                                VisitMarker(
                                    visit: visit,
                                    ink: day.ink(for: visit),
                                    isSelected: selectedVisit?.id == visit.id || focusedVisit?.id == visit.id,
                                    isCurrentVisit: viewModel.isCurrentVisit(visit),
                                    showsLabel: day.places.count <= 6 || selectedVisit?.id == visit.id || focusedVisit?.id == visit.id
                                )
                            }
                            .buttonStyle(.plain)
                        }
                        .annotationTitles(.hidden)
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
                        .annotationTitles(.hidden)
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
                        .annotationTitles(.hidden)
                    }
                }
            }
            .mapStyle(.standard(emphasis: .muted))
            .mapControls {
                // Location, compass and scale live in the trace chrome.
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
    }

    @MapContentBuilder
    private func travelPath(upTo sourceIndex: Int?, visiblePoints: [LocationPoint], ink: Color) -> some MapContent {
        if let preparedRoadSnappedRoute {
            ForEach(displayedRoadSegments(from: preparedRoadSnappedRoute, upTo: sourceIndex)) { segment in
                MapPolyline(coordinates: segment.coordinates)
                    .stroke(ink, style: Self.pathStroke)
            }
        } else if shouldDrawStraightLinePath, visiblePoints.count >= 2 {
            MapPolyline(coordinates: visiblePoints.map { $0.coordinate })
                .stroke(ink, style: Self.pathStroke)
        }
    }

    // MARK: - Trace chrome

    /// The top-leading corner stays clear for the "Today" capsule drawn by the presenter.
    private var topChrome: some View {
        HStack(alignment: .top, spacing: 12) {
            if presentation == .companion {
                companionReplayButton
            }

            Spacer(minLength: 0)

            if replayBridge != nil {
                // Half-open: the replay controls sit below the fold.
                Text("TABLETOP · MAP ABOVE THE FOLD")
                    .font(TE.mono(.caption2, weight: .medium))
                    .tracking(1)
                    .foregroundStyle(TE.textPrimary)
                    .lineLimit(1)
                    .minimumScaleFactor(0.8)
                    .padding(.horizontal, 12)
                    .frame(minHeight: 32)
                    .background(Capsule().fill(TE.card.opacity(0.92)))
                    .padding(.top, 6)
                    .accessibilityLabel("Tabletop. Map above the fold.")
            }

            VStack(spacing: 8) {
                MapChromeButton(systemImage: "square.3.layers.3d", label: "Map layers") {
                    showingFilters = true
                }
                .accessibilityHint("Choose the days and layers the map shows.")

                MapAddPlaceMenu(
                    onSaveCurrentPlace: openSaveCurrentPlaceSheet,
                    onAddPastVisit: openPastVisitSheet,
                    onDropPin: openBlankManualVisitSheet
                )

                FitMenuButton(
                    onFitContent: { fitMapToContent() },
                    onFitSession: !activeSessionPoints.isEmpty ? { fitMapToSession() } : nil
                )

                MapUserLocationButton(scope: mapScope)
                    .buttonBorderShape(.circle)
                    .tint(TE.textPrimary)

                MapCompass(scope: mapScope)
            }
        }
        .padding(.horizontal, 16)
        .padding(.top, 4)
    }

    private var companionReplayButton: some View {
        Button {
            withAnimation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.82)) {
                if let companionReplay {
                    companionReplay.wrappedValue.toggle()
                } else {
                    showsCompanionReplay.toggle()
                }
            }
        } label: {
            Label(
                isCompanionReplayShown ? "Close replay" : "Replay",
                systemImage: isCompanionReplayShown ? "xmark" : "play.fill"
            )
            .font(.footnote.weight(.medium))
            .padding(.horizontal, 14)
            .frame(minHeight: 44)
            .background(Capsule().fill(TE.card))
            .overlay(Capsule().strokeBorder(TE.border, lineWidth: 1))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .foregroundStyle(TE.textPrimary)
        .accessibilityHint("Shows the time scrubber for replaying the day.")
    }

    private func floatingCards(photoDetails: PhotoMomentDetailSelection) -> some View {
        VStack(alignment: .trailing, spacing: 8) {
            if showPhotoMarkers, !viewModel.mapPhotoPlaces.isEmpty, !photoPlacesPromptDismissed {
                HStack(spacing: 0) {
                    Button {
                        photoDetails.showsPlaces = true
                    } label: {
                        Label("Browse \(viewModel.mapPhotoPlaces.count) photo places", systemImage: "photo.stack")
                            .font(.footnote.weight(.medium))
                            .padding(.leading, 14)
                            .padding(.trailing, 4)
                            .frame(minHeight: 44)
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
                .foregroundStyle(TE.textPrimary)
                .background(Capsule().fill(TE.card))
                .overlay(Capsule().strokeBorder(TE.border, lineWidth: 1))
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
                .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            if let inspectorVisit, !(presentation == .companion && isCompanionReplayShown) {
                inspectorCard(for: inspectorVisit)
                    .frame(maxWidth: 440)
                    .transition(.move(edge: .bottom).combined(with: .opacity))
            }

            HStack(alignment: .bottom, spacing: 8) {
                MapScaleView(scope: mapScope)

                Spacer(minLength: 0)

                VStack(alignment: .trailing, spacing: 8) {
                    MapTrackingControlPill(
                        viewModel: viewModel,
                        locationManager: locationManager,
                        isTracking: isTracking,
                        isExpanded: $trackingPillExpanded,
                        onPrimaryTap: handleTrackingTap
                    )

                    if isTracking,
                       let remaining = locationManager.remainingTime {
                        MapAutoOffPill(remaining: remaining)
                            .transition(.opacity.combined(with: .move(edge: .bottom)))
                    }
                }
            }
        }
        .frame(maxWidth: .infinity, alignment: .trailing)
    }

    private func inspectorCard(for visit: Visit) -> some View {
        MapVisitInspectorCard(
            visit: visit,
            monthVisitOrdinal: monthVisitOrdinal(for: visit),
            photoCount: photos(for: visit).count,
            onConfirm: { viewModel.confirmVisit(visit) },
            onDetails: { deepLinkedVisit = visit },
            onPhotos: { openPhotos(for: visit) },
            onClose: closeInspector,
            onCalendar: { calendarDraft = CalendarEventDraft(visit: visit) }
        )
    }

    private func closeInspector() {
        if selectedVisit != nil {
            selectedVisit = nil
        } else if let onCloseFocusedVisit {
            dismissedFocusedVisitID = nil
            onCloseFocusedVisit()
        } else {
            dismissedFocusedVisitID = focusedVisit?.id
        }
    }

    /// Which visit to this place this is within its calendar month, counting from the first.
    private func monthVisitOrdinal(for visit: Visit) -> Int {
        let calendar = Calendar.current
        return viewModel.allVisits.filter { other in
            other.displayName == visit.displayName &&
            other.arrivedAt <= visit.arrivedAt &&
            calendar.isDate(other.arrivedAt, equalTo: visit.arrivedAt, toGranularity: .month)
        }.count
    }

    private func photos(for visit: Visit) -> [PhotoMoment] {
        let end = visit.departedAt ?? Date()
        guard end >= visit.arrivedAt else { return [] }
        return viewModel.mapAccessiblePhotoMoments.filter { $0.takenAt >= visit.arrivedAt && $0.takenAt <= end }
    }

    private func openPhotos(for visit: Visit) {
        let photos = photos(for: visit)
        guard !photos.isEmpty else { return }
        if photos.count == 1 {
            viewModel.photoDetails.photo = photos[0]
        } else {
            viewModel.photoDetails.cluster = PhotoMomentCluster(
                id: "visit-\(visit.id.uuidString)",
                coordinate: visit.coordinate,
                photos: photos
            )
        }
    }

    /// Names a photo place after the visit it was taken during, when one is close by.
    private func photoPlaceName(_ place: PhotoMomentCluster) -> String? {
        guard let first = place.sortedPhotos.first else { return nil }
        let location = CLLocation(latitude: place.coordinate.latitude, longitude: place.coordinate.longitude)
        return filteredVisits.first { visit in
            let end = visit.departedAt ?? Date()
            guard first.takenAt >= visit.arrivedAt.addingTimeInterval(-600),
                  first.takenAt <= end.addingTimeInterval(600) else { return false }
            let visitLocation = CLLocation(latitude: visit.coordinate.latitude, longitude: visit.coordinate.longitude)
            return location.distance(from: visitLocation) <= 250
        }?.displayName
    }

    private var recordedDays: Set<Date> {
        let calendar = Calendar.current
        return Set(viewModel.allVisits.map { calendar.startOfDay(for: $0.arrivedAt) })
    }

    private var layerSettings: MapLayerSettings {
        MapLayerSettings(
            visits: $showVisitMarkers,
            routes: $showTravelPath,
            photos: $showPhotoMarkers,
            photoImages: $showPhotoMarkerImages,
            photoDots: $showPhotoMarkerDots,
            roadMatchedPath: $snapTravelPathToRoads,
            straightLineGaps: $showStraightLinePathSegments,
            pointMarkers: $showPointMarkers,
            startEndMarkers: $showStartEndMarkers,
            sessionPath: $showSessionPath,
            hasSessionPoints: !activeSessionPoints.isEmpty,
            minimumStayMinutes: $minimumStayMinutes
        )
    }

    // MARK: - Scrubbing the day

    private func tracePanelModel(day: TraceDay, now: Date) -> TraceReplayPanelModel {
        let focus = focusTime(day: day, now: now)
        let status = traceStatus(at: focus, day: day, now: now)
        let visitAtFocus = day.visit(at: focus, now: now)
        return TraceReplayPanelModel(
            kicker: status.kicker,
            title: status.title,
            clock: Self.clockText(focus),
            day: day,
            nowFraction: day.nowFraction(now),
            focusFraction: day.fraction(of: focus),
            focusedPlaceID: visitAtFocus.map { $0.displayName },
            distanceText: traceDistanceText,
            hint: String(localized: "Drag to replay · now \(Self.clockText(now))"),
            isReplaying: isRouteReplayEnabled,
            isPlaying: isRouteReplayPlaying,
            canPlay: canReplayRoute,
            onScrub: { fraction in scrub(to: day.date(at: fraction), day: day) },
            onStep: { delta in scrub(to: focus.addingTimeInterval(delta), day: day) },
            onJump: { place in jump(to: place, day: day) },
            onPlayPause: {
                scrubbedTime = nil
                toggleRouteReplayPlayback()
            },
            onDone: disableRouteReplay
        )
    }

    private func tracePanel(day: TraceDay, now: Date, maxHeight: CGFloat) -> some View {
        TraceScrubPanel(
            model: tracePanelModel(day: day, now: now),
            isExpandedLayout: horizontalSizeClass == .regular,
            maxHeight: maxHeight,
            isCollapsed: $isTracePanelCollapsed
        )
    }

    /// Half-open: the replay controls live in the pane below the fold, so publish them there.
    private func publishTracePanel(day: TraceDay, now: Date, to bridge: TraceReplayBridge) -> some View {
        let model = tracePanelModel(day: day, now: now)
        return Color.clear
            .frame(width: 0, height: 0)
            .accessibilityHidden(true)
            .onChange(of: model.signature, initial: true) { _, _ in
                bridge.panel = model
            }
            .onDisappear {
                if bridge.panel?.signature == model.signature { bridge.panel = nil }
            }
    }

    private var traceDistanceText: String {
        if isRouteReplayEnabled, let routeReplaySnapshot {
            let distance = DistanceFormatter.format(meters: routeReplaySnapshot.distanceMeters, usesMetric: usesMetricDistanceUnits)
            return String(localized: "\(distance) so far")
        }
        let distance = DistanceFormatter.format(
            meters: RouteReplayCalculator.totalDistance(in: filteredPoints),
            usesMetric: usesMetricDistanceUnits
        )
        return String(localized: "\(distance) moved")
    }

    private func focusTime(day: TraceDay, now: Date) -> Date {
        if let scrubbedTime { return scrubbedTime }
        if isRouteReplayEnabled, let routeReplaySnapshot { return routeReplaySnapshot.currentPoint.timestamp }
        return min(max(now, day.domain.lowerBound), day.domain.upperBound)
    }

    static func clockText(_ date: Date) -> String {
        date.formatted(.verbatim("\(hour: .twoDigits(clock: .twentyFourHour, hourCycle: .zeroBased)):\(minute: .twoDigits)", timeZone: .current, calendar: .current))
    }

    /// Describes where the day stood at a moment, using only recorded stays and points.
    private func traceStatus(at time: Date, day: TraceDay, now: Date) -> (kicker: String, title: String) {
        let datePrefix = day.isSingleDay ? "" : time.formatted(.dateTime.month(.abbreviated).day()).uppercased() + " · "

        if time > now.addingTimeInterval(60) {
            return (String(localized: "LATER TODAY"), String(localized: "Not lived yet"))
        }

        if let visit = day.visit(at: time, now: now) {
            let since = Self.clockText(visit.arrivedAt)
            return (datePrefix + String(localized: "STAYING AT · SINCE \(since)"), visit.displayName)
        }

        let points = filteredPoints
        if let index = movingPointIndex(at: time, day: day, now: now) {
            if let arriving = arrivingStatus(at: time, day: day, now: now, points: points) {
                return (datePrefix + arriving.kicker, arriving.title)
            }
            // "WALKING · 4 KM/H": the mode is an estimate from GPS speed alone.
            var kicker = String(localized: "ON THE MOVE")
            if let speed = movementSpeed(at: index, in: points) {
                if let mode = TravelMode.infer(averageSpeed: speed) {
                    kicker = mode.activeLabel.uppercased()
                }
                kicker += " · " + formattedSpeed(speed).uppercased()
            }
            let title: String
            if let street = streetNames[StreetNameLookup.cellKey(for: points[index].coordinate)] {
                title = String(localized: "Along \(Self.streetPhrase(street))")
            } else if let next = day.visits.first(where: { $0.arrivedAt > time }) {
                title = String(localized: "Toward \(next.displayName)")
            } else if let previous = day.visits.last(where: { ($0.departedAt ?? now) < time }) {
                title = String(localized: "After \(previous.displayName)")
            } else {
                title = String(localized: "On the move")
            }
            return (datePrefix + kicker, title)
        }

        return (datePrefix + String(localized: "NO RECORD"), String(localized: "Nothing recorded"))
    }

    /// "ARRIVING · BIKE 3.4 KM": the closing stretch of a move into the next recorded stay.
    /// The mode and distance describe the whole move, from the last stay (or first fix) to the arrival.
    private func arrivingStatus(
        at time: Date,
        day: TraceDay,
        now: Date,
        points: [LocationPoint]
    ) -> (kicker: String, title: String)? {
        guard let next = day.visits.first(where: { $0.arrivedAt > time }) else { return nil }
        let previousEnd = day.visits.last(where: { ($0.departedAt ?? now) <= time })?.departedAt
        let movePoints = points.filter { point in
            point.timestamp <= next.arrivedAt && (previousEnd.map { point.timestamp >= $0 } ?? true)
        }
        guard let first = movePoints.first else { return nil }
        let start = previousEnd ?? first.timestamp
        let duration = next.arrivedAt.timeIntervalSince(start)
        guard duration > 0 else { return nil }
        // The last third of the move, or its final five minutes, reads as arriving.
        let remaining = next.arrivedAt.timeIntervalSince(time)
        guard remaining <= max(5 * 60, duration / 3) else { return nil }

        let meters = RouteReplayCalculator.totalDistance(in: movePoints)
        guard meters > 50 else { return nil }
        let distance = DistanceFormatter.format(meters: meters, usesMetric: usesMetricDistanceUnits)
        let kicker: String
        if let mode = TravelMode.infer(meters: meters, seconds: duration) {
            kicker = String(localized: "ARRIVING · \(mode.label.uppercased()) \(distance.uppercased())")
        } else {
            kicker = String(localized: "ARRIVING · \(distance.uppercased())")
        }
        return (kicker, next.displayName)
    }

    /// The recorded point the trace is "on the move" at, when the moment falls outside every stay.
    private func movingPointIndex(at time: Date, day: TraceDay, now: Date) -> Int? {
        guard time <= now.addingTimeInterval(60), day.visit(at: time, now: now) == nil else { return nil }
        let points = filteredPoints
        guard let index = RouteReplayCalculator.index(atOrBefore: time, in: points),
              abs(points[index].timestamp.timeIntervalSince(time)) <= 15 * 60 else { return nil }
        return index
    }

    /// GPS speed when the fix carries one, otherwise the pace to the neighbouring point.
    private func movementSpeed(at index: Int, in points: [LocationPoint]) -> Double? {
        if let speed = points[index].speed, speed.isFinite, speed > 0.5 { return speed }
        let neighbour = index + 1 < points.count ? index + 1 : index - 1
        guard points.indices.contains(neighbour) else { return nil }
        let a = points[min(index, neighbour)]
        let b = points[max(index, neighbour)]
        let seconds = b.timestamp.timeIntervalSince(a.timestamp)
        guard seconds > 0, seconds <= 15 * 60 else { return nil }
        let speed = a.distance(to: b) / seconds
        return speed > 0.5 ? speed : nil
    }

    /// "The Embarcadero" reads as "the Embarcadero" mid-sentence.
    private static func streetPhrase(_ street: String) -> String {
        guard street.hasPrefix("The ") else { return street }
        return "the " + street.dropFirst(4)
    }

    /// The street lookup follows the playhead only while it is between stays.
    private var traceStreetLookupTarget: (key: String, coordinate: CLLocationCoordinate2D)? {
        guard showsTracePanel || replayBridge != nil else { return nil }
        let now = Date()
        let day = traceDay
        let time = focusTime(day: day, now: now)
        guard let index = movingPointIndex(at: time, day: day, now: now) else { return nil }
        let coordinate = filteredPoints[index].coordinate
        return (StreetNameLookup.cellKey(for: coordinate), coordinate)
    }

    private func refreshTraceStreetName() async {
        guard let target = traceStreetLookupTarget, streetNames[target.key] == nil else { return }
        // Scrubbing and playback cross many cells; look up only where the playhead settles.
        try? await Task.sleep(nanoseconds: 700_000_000)
        guard !Task.isCancelled,
              let street = await StreetNameLookup.shared.street(at: target.coordinate) else { return }
        streetNames[target.key] = street
    }

    private func formattedSpeed(_ metersPerSecond: Double) -> String {
        if usesMetricDistanceUnits {
            return String(format: "%.0f km/h", locale: .current, metersPerSecond * 3.6)
        }
        return String(format: "%.0f mph", locale: .current, metersPerSecond * 2.23693629)
    }

    /// Moves the playhead to a moment; the path is inked up to the last point recorded by then.
    private func scrub(to time: Date, day: TraceDay) {
        let clamped = min(max(time, day.domain.lowerBound), day.domain.upperBound)
        scrubbedTime = clamped
        isRouteReplayPlaying = false
        guard canReplayRoute else { return }

        if !isRouteReplayEnabled {
            showTravelPath = true
            isRouteReplayEnabled = true
        }

        let points = routeReplaySourcePoints
        let index = RouteReplayCalculator.index(atOrBefore: clamped, in: points) ?? 0
        routeReplayProgress = RouteReplayCalculator.progress(forIndex: index, pointCount: points.count)
    }

    private func jump(to place: TraceDay.Place, day: TraceDay) {
        let visit = place.visit
        let start = max(visit.arrivedAt, day.domain.lowerBound)
        let end = min(visit.departedAt ?? Date(), day.domain.upperBound)
        scrub(to: start.addingTimeInterval(max(0, min(60, end.timeIntervalSince(start) / 2))), day: day)
        withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.35)) {
            cameraPosition = .region(MKCoordinateRegion(
                center: visit.coordinate,
                latitudinalMeters: 900,
                longitudinalMeters: 900
            ))
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
        manualVisitDraft = ManualVisitDraft(mode: .addPlaceManually, title: "Log a stop", initialSearchRegion: visibleMapRegion)
    }

    private func openPastVisitSheet() {
        let departedAt = Date()
        let arrivedAt = Calendar.current.date(byAdding: .hour, value: -1, to: departedAt) ?? departedAt
        manualVisitDraft = ManualVisitDraft(
            mode: .addPastVisit,
            arrivedAt: arrivedAt,
            departedAt: departedAt,
            isStillHere: false,
            title: "Log a stop",
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
        scrubbedTime = nil
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
                    .fill(TE.card)
                    .overlay(Capsule().strokeBorder(TE.border, lineWidth: 1))
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
                .fill(isTracking ? TE.liveDot : TE.textMuted.opacity(0.35))
                .frame(width: 8, height: 8)
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
                .fill(TE.border)
                .frame(width: 1, height: 12)
                .accessibilityHidden(true)

            Text("\(viewModel.sessionLocationPoints.count) PTS")
                .font(TE.mono(.caption, weight: .medium))
                .foregroundStyle(TE.textMuted)
        }
    }

    private var primaryButton: some View {
        Button(action: onPrimaryTap) {
            Image(systemName: isTracking ? "stop.fill" : "record.circle")
                .font(.system(size: isTracking ? 13 : 18, weight: .semibold))
                .foregroundStyle(isTracking ? TE.onInkCard : TE.textPrimary)
                .frame(width: 44, height: 44)
                .background {
                    if isTracking {
                        Circle().fill(TE.inkRust)
                    } else {
                        Circle()
                            .fill(TE.card)
                            .overlay(Circle().strokeBorder(TE.border, lineWidth: 1))
                    }
                }
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
        .background(Capsule().fill(TE.card))
        .overlay(Capsule().strokeBorder(TE.border, lineWidth: 1))
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

    /// Unconfirmed stops are drawn hollow so they read as "still a guess".
    var isTentative: Bool { self == .unconfirmed }
}

/// A place dot in its day ink, with an optional mono caption beside it.
struct VisitMarker: View {
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let visit: Visit
    var ink: Color = TE.accent
    let isSelected: Bool
    let isCurrentVisit: Bool
    var showsLabel = false

    private var state: VisitMarkerState { VisitMarkerState(visit: visit) }

    var body: some View {
        ZStack {
            if isSelected {
                Circle()
                    .fill(ink.opacity(0.16))
                    .overlay(Circle().strokeBorder(ink, lineWidth: 1.5))
                    .frame(width: 52, height: 52)
            }

            if isCurrentVisit {
                Circle()
                    .fill(TE.liveDot.opacity(0.25))
                    .frame(width: 30, height: 30)
            }

            Circle()
                .fill(state.isTentative ? TE.card : ink)
                .overlay {
                    if state.isTentative {
                        Circle().strokeBorder(ink, style: StrokeStyle(lineWidth: 3, dash: [3, 2]))
                    }
                }
                .frame(width: 18, height: 18)
                .background(Circle().fill(TE.surface).padding(-3))
        }
        .frame(width: 44, height: 44)
        .overlay(alignment: .leading) {
            if showsLabel {
                Text(visit.displayName.uppercased())
                    .font(TE.mono(.caption2, weight: .medium))
                    .foregroundStyle(TE.textPrimary)
                    .lineLimit(1)
                    .padding(.horizontal, 4)
                    .background(TE.surface.opacity(0.75), in: RoundedRectangle(cornerRadius: 3))
                    .fixedSize()
                    .offset(x: 40)
                    .allowsHitTesting(false)
            }
        }
        .contentShape(Circle())
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
        if isSelected { return TE.inkRust }
        return point.isOutlier ? TE.placeInks[2] : TE.textPrimary
    }

    var body: some View {
        Button(action: onTap) {
            ZStack {
                if isSelected {
                    Circle()
                        .fill(markerColor.opacity(0.18))
                        .frame(width: 26, height: 26)
                }

                Circle()
                    .fill(markerColor)
                    .frame(width: isSelected ? 11 : 7, height: isSelected ? 11 : 7)
                    .overlay {
                        Circle()
                            .stroke(TE.surface, lineWidth: isSelected ? 2 : 1.5)
                    }
            }
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
                    .foregroundStyle(TE.textPrimary)
                    .multilineTextAlignment(.trailing)
                    .lineLimit(2)
            }

            Rectangle()
                .fill(TE.border)
                .frame(height: 1)

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
        .padding(12)
        .frame(width: 240, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 14, style: .continuous).strokeBorder(TE.border, lineWidth: 1))
        .shadow(color: .black.opacity(0.10), radius: 10, y: 4)
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
                .foregroundStyle(TE.textPrimary)
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
            Circle()
                .fill(TE.placeInks[3])
                .frame(width: 16, height: 16)
                .overlay(Circle().stroke(TE.surface, lineWidth: 3))
                .accessibilityHidden(true)
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
                .foregroundStyle(TE.textPrimary)
        }
        .lineLimit(1)
        .padding(.horizontal, 8)
        .padding(.vertical, 4)
        .background(Capsule().fill(TE.card))
        .overlay(Capsule().strokeBorder(TE.border, lineWidth: 1))
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
            RoundedRectangle(cornerRadius: 3)
                .fill(TE.placeInks[1])
                .frame(width: 16, height: 16)
                .overlay(RoundedRectangle(cornerRadius: 3).stroke(TE.surface, lineWidth: 3))
                .accessibilityHidden(true)
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

/// The playhead: a paper dot ringed in rust with a soft halo.
struct RouteReplayMarker: View {
    let snapshot: RouteReplaySnapshot

    var body: some View {
        ZStack {
            Circle()
                .fill(TE.inkRust.opacity(0.18))
                .frame(width: 32, height: 32)

            Circle()
                .fill(TE.surface)
                .frame(width: 14, height: 14)
                .overlay(Circle().stroke(TE.inkRust, lineWidth: 3))
        }
        .frame(minWidth: 44, minHeight: 44)
        .accessibilityElement(children: .ignore)
        .accessibilityLabel("Route replay position")
        .accessibilityValue(snapshot.accessibilitySummary)
    }
}

struct RouteReplayControl: View {
    @AppStorage("usesMetricDistanceUnits") private var usesMetricDistanceUnits = true
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    let snapshot: RouteReplaySnapshot
    let pointCount: Int
    @Binding var progress: Double
    let isPlaying: Bool
    let onPlayPause: () -> Void
    let onScrub: () -> Void
    let onClose: () -> Void

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(spacing: 12) {
                Button(action: onPlayPause) {
                    Image(systemName: isPlaying ? "pause.fill" : "play.fill")
                        .font(.subheadline.weight(.bold))
                        .foregroundStyle(TE.onInkCard)
                        .frame(width: 44, height: 44)
                        .background(Circle().fill(TE.accent))
                }
                .buttonStyle(.plain)
                .accessibilityLabel(isPlaying ? "Pause route replay" : "Play route replay")

                VStack(alignment: .leading, spacing: 2) {
                    Text("ROUTE REPLAY · \(snapshot.index + 1)/\(pointCount) PTS")
                        .font(TE.mono(.caption2))
                        .tracking(1.4)
                        .foregroundStyle(TE.textMuted)
                        .monospacedDigit()

                    Text(snapshot.currentPoint.timestamp.formatted(date: .abbreviated, time: .shortened))
                        .font(TE.editorial(.title3))
                        .monospacedDigit()
                        .foregroundStyle(TE.textPrimary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)

                Button(action: onClose) {
                    Image(systemName: "xmark")
                        .font(.caption.weight(.bold))
                        .foregroundStyle(TE.textPrimary)
                        .frame(width: 44, height: 44)
                        .overlay(Circle().strokeBorder(TE.border, lineWidth: 1))
                        .contentShape(Circle())
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
            .tint(TE.inkRust)
            .accessibilityValue(snapshot.accessibilitySummary)

            let stats = dynamicTypeSize.isAccessibilitySize
                ? AnyLayout(VStackLayout(alignment: .leading, spacing: 10))
                : AnyLayout(HStackLayout(alignment: .top, spacing: 0))
            stats {
                RouteReplayStat(label: "ELAPSED", value: formatDuration(snapshot.elapsedDuration))
                RouteReplayStat(label: "DURATION", value: formatDuration(snapshot.totalDuration))
                RouteReplayStat(label: "DISTANCE", value: formattedDistanceProgress)
            }
            .overlay(alignment: .top) { Rectangle().fill(TE.border).frame(height: 1) }
        }
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(TE.border, lineWidth: 1))
        .accessibilityElement(children: .contain)
    }

    private var formattedDistanceProgress: String {
        let current = DistanceFormatter.format(meters: snapshot.distanceMeters, usesMetric: usesMetricDistanceUnits)
        let total = DistanceFormatter.format(meters: snapshot.totalDistanceMeters, usesMetric: usesMetricDistanceUnits)
        return "\(current) / \(total)"
    }

    private func formatDuration(_ duration: TimeInterval) -> String {
        RouteReplayCalculator.formattedDuration(duration)
    }
}

/// A serif figure over a mono caption, as in the day's headline numbers.
struct RouteReplayStat: View {
    let label: LocalizedStringKey
    let value: String

    var body: some View {
        VStack(alignment: .leading, spacing: 2) {
            Text(value)
                .font(TE.editorial(.body))
                .foregroundStyle(TE.textPrimary)
                .monospacedDigit()
                .lineLimit(1)
                .minimumScaleFactor(0.75)

            Text(label)
                .font(TE.mono(.caption2))
                .tracking(1.1)
                .foregroundStyle(TE.textMuted)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(.top, 10)
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
                .font(.footnote.weight(.medium))
                .foregroundStyle(TE.textPrimary)
                .lineLimit(1)
                .frame(maxWidth: 160, alignment: .leading)

            actionView
        }
        .padding(.leading, 8)
        .padding(.trailing, suggestion == nil ? 6 : 14)
        .background(cardBackground)
        .overlay(alignment: .topTrailing) {
            if suggestion != nil {
                Button(action: onDismiss) {
                    Image(systemName: "xmark")
                        .font(.system(size: 9, weight: .bold))
                        .foregroundStyle(TE.textPrimary)
                        .frame(width: 20, height: 20)
                        .background(Circle().fill(TE.card))
                        .overlay(Circle().strokeBorder(TE.border, lineWidth: 1))
                        .frame(width: 44, height: 44)
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Close visit suggestion")
                .accessibilityHint("Hides this suggested place from the map.")
                .offset(x: 17, y: -17)
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
            Button { onSave() } label: {
                Text("Save")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(TE.onInkCard)
                    .padding(.horizontal, 14)
                    .frame(minHeight: 32)
                    .background(Capsule().fill(TE.accent))
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint("Opens visit details prefilled with this place.")

            Button { onNotHere() } label: {
                Image(systemName: "ellipsis")
                    .font(.system(size: 14, weight: .bold))
                    .foregroundStyle(TE.textPrimary)
                    .frame(width: 36, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Not here")
            .accessibilityHint("Opens manual place entry.")
        } else {
            Button { onRequestLocation() } label: {
                Text(hasLocationPermission ? "Find" : "Allow")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(TE.textPrimary)
                    .padding(.horizontal, 14)
                    .frame(minHeight: 32)
                    .overlay(Capsule().strokeBorder(TE.border, lineWidth: 1))
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityHint("Requests a fresh current location.")
        }
    }

    private var cardBackground: some View {
        Capsule()
            .fill(TE.card)
            .overlay(Capsule().strokeBorder(TE.border, lineWidth: 1))
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
        if !hasLocationPermission || lookupFailed { return TE.inkRust }
        if canSave { return TE.accent }
        return TE.textMuted
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
            MapChromeButtonLabel(systemImage: "plus")
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
    @State private var overlapAlertMessage = ""
    @State private var searchQuery = ""
    @Environment(\.modelContext) private var modelContext
    @AppStorage("usesMetricDistanceUnits") private var usesMetricDistanceUnits = true
    @StateObject private var placeSearch = PlaceSearchViewModel()
    @FocusState private var isSearchFocused: Bool
    @State private var previewCamera: MapCameraPosition
    @State private var previewCenter: CLLocationCoordinate2D?
    /// Stored stays the stop's times would overlap, checked before saving.
    @State private var overlapCheck: VisitOverlapCheck = .none
    @State private var pinLookupTask: Task<Void, Never>?

    private static let pinGeocoder = GeocodingService()

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
        if let selection = draft.locationSelection, selection.coordinateIsValid {
            _previewCamera = State(initialValue: .region(Self.previewRegion(around: selection.coordinate)))
            _previewCenter = State(initialValue: selection.coordinate)
        } else if let region = draft.initialSearchRegion {
            _previewCamera = State(initialValue: .region(region))
            _previewCenter = State(initialValue: region.center)
        } else {
            _previewCamera = State(initialValue: .userLocation(fallback: .automatic))
        }
    }

    private static func previewRegion(around coordinate: CLLocationCoordinate2D) -> MKCoordinateRegion {
        MKCoordinateRegion(center: coordinate, latitudinalMeters: 700, longitudinalMeters: 700)
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

    private var trimmedQuery: String {
        searchQuery.trimmingCharacters(in: .whitespacesAndNewlines)
    }

    /// Saved places whose name or address matches the search field; all of them when it is empty.
    private var matchingSavedPlaces: [SavedPlace] {
        guard !trimmedQuery.isEmpty else { return viewModel.savedPlaces }
        return viewModel.savedPlaces.filter { place in
            place.name.localizedCaseInsensitiveContains(trimmedQuery) ||
            (place.address?.localizedCaseInsensitiveContains(trimmedQuery) ?? false)
        }
    }

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    searchField
                    mapPreview
                    resultsCard
                    timeCard

                    if let validationMessage {
                        LogStopNotice(text: validationMessage, systemImage: "exclamationmark.triangle.fill")
                    } else {
                        ForEach(Array(overlapNotices.enumerated()), id: \.offset) { _, notice in
                            LogStopNotice(text: notice, systemImage: "exclamationmark.triangle")
                                .accessibilityAddTraits(.updatesFrequently)
                        }
                    }

                    placeCard
                    locationCard
                    reuseCard

                    if let errorMessage {
                        LogStopNotice(text: errorMessage, systemImage: "exclamationmark.circle.fill")
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 8)
                .padding(.bottom, 16)
            }
            .scrollDismissesKeyboard(.interactively)
            .safeAreaInset(edge: .bottom) { saveBar }
            .foregroundStyle(TE.textPrimary)
            .background(TE.surface.ignoresSafeArea())
            .navigationTitle(Text(draftTitle))
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(TE.surface, for: .navigationBar)
            .onAppear {
                viewModel.loadSavedPlaces()
                if mode.requestsCurrentLocation && locationSelection == nil {
                    locationManager.requestCurrentLocationForManualVisit()
                }
            }
            .onDisappear { pinLookupTask?.cancel() }
            .onChange(of: overlapCheckKey, initial: true) { _, _ in
                refreshOverlapCheck()
            }
            .onChange(of: coordinateKey, initial: true) { _, _ in
                guard let coordinate else { return }
                previewCamera = .region(Self.previewRegion(around: coordinate))
            }
            .onChange(of: searchQuery) { _, query in
                placeSearch.query = query
                placeSearch.queryChanged(region: searchRegion)
            }
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button("Cancel") { dismiss() }
                        .foregroundStyle(TE.textMuted)
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button("Save") { save() }
                        .fontWeight(.semibold)
                        .foregroundStyle(isSaveDisabled ? TE.textMuted : TE.inkRust)
                        .disabled(isSaveDisabled)
                }
            }
            .alert("Overlapping Visit", isPresented: $showingOverlapConfirmation) {
                Button("Cancel", role: .cancel) {}
                Button("Save Anyway") { save(allowOverlap: true) }
            } message: {
                Text(overlapAlertMessage)
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

    // MARK: Search and results

    private var searchField: some View {
        HStack(spacing: 10) {
            Image(systemName: "magnifyingglass")
                .foregroundStyle(TE.textPrimary)
                .accessibilityHidden(true)
            TextField("Search Apple Maps or saved places", text: $searchQuery)
                .focused($isSearchFocused)
                .textInputAutocapitalization(.words)
                .autocorrectionDisabled()
                .submitLabel(.search)
                .onSubmit { placeSearch.submit(region: searchRegion) }
            if placeSearch.isSearching {
                ProgressView()
                    .controlSize(.small)
                    .accessibilityLabel("Searching Apple Maps")
            }
            if !searchQuery.isEmpty {
                Button {
                    searchQuery = ""
                    placeSearch.clear()
                } label: {
                    Image(systemName: "xmark.circle.fill")
                        .foregroundStyle(TE.textMuted)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Clear search")
            }
        }
        .padding(.leading, 14)
        .frame(minHeight: 48)
        .background(RoundedRectangle(cornerRadius: 14, style: .continuous).fill(TE.card))
        .overlay(
            RoundedRectangle(cornerRadius: 14, style: .continuous)
                .strokeBorder(isSearchFocused ? TE.textPrimary : TE.border, lineWidth: isSearchFocused ? 2 : 1)
        )
    }

    /// Where distances are measured from and Apple Maps searches are centred.
    private var referenceCoordinate: CLLocationCoordinate2D? {
        if let location = locationManager.currentLocation, CLLocationCoordinate2DIsValid(location.coordinate) {
            return location.coordinate
        }
        return initialSearchRegion?.center ?? coordinate ?? previewCenter
    }

    private var searchRegion: MKCoordinateRegion? {
        guard let referenceCoordinate else { return initialSearchRegion }
        return MKCoordinateRegion(center: referenceCoordinate, latitudinalMeters: 20_000, longitudinalMeters: 20_000)
    }

    /// Apple Maps results, minus places already saved at the same spot.
    private var appleMapsResults: [PlaceSearchResult] {
        guard !trimmedQuery.isEmpty else { return [] }
        let saved = matchingSavedPlaces
        return placeSearch.results.filter { result in
            !saved.contains { place in
                Self.distance(from: place.coordinate, to: result.coordinate) < 75
            }
        }
    }

    private static func distance(from a: CLLocationCoordinate2D, to b: CLLocationCoordinate2D) -> CLLocationDistance {
        CLLocation(latitude: a.latitude, longitude: a.longitude)
            .distance(from: CLLocation(latitude: b.latitude, longitude: b.longitude))
    }

    private func distanceText(to coordinate: CLLocationCoordinate2D) -> String? {
        guard let referenceCoordinate else { return nil }
        return DistanceFormatter.format(
            meters: Self.distance(from: referenceCoordinate, to: coordinate),
            usesMetric: usesMetricDistanceUnits
        )
    }

    /// The pin's match radius: the saved place's, the radius being saved, or the fix's accuracy.
    private var previewRadius: CLLocationDistance {
        if let selectedSavedPlace { return selectedSavedPlace.radiusMeters }
        if saveAsReusableLocation { return reusableRadiusMeters }
        if locationSelection == nil, let manualLocation { return max(25, manualLocation.horizontalAccuracy) }
        return 50
    }

    private var coordinateKey: String {
        guard let coordinate else { return "none" }
        return String(format: "%.6f,%.6f", coordinate.latitude, coordinate.longitude)
    }

    private var mapPreview: some View {
        Map(position: $previewCamera) {
            if let coordinate {
                MapCircle(center: coordinate, radius: previewRadius)
                    .foregroundStyle(TE.inkRust.opacity(0.12))
                    .stroke(TE.inkRust, style: StrokeStyle(lineWidth: 1, dash: [4, 4]))
                Annotation("", coordinate: coordinate, anchor: .center) {
                    Circle()
                        .fill(TE.inkRust)
                        .frame(width: 20, height: 20)
                        .overlay(Circle().strokeBorder(TE.surface, lineWidth: 3))
                }
                .annotationTitles(.hidden)
            }
        }
        .mapStyle(.standard(emphasis: .muted))
        .mapControls {}
        .onMapCameraChange(frequency: .onEnd) { context in
            previewCenter = context.region.center
        }
        .overlay {
            // The centre a dropped pin would use.
            Image(systemName: "plus")
                .font(.caption.weight(.semibold))
                .foregroundStyle(TE.textPrimary.opacity(0.55))
                .allowsHitTesting(false)
                .accessibilityHidden(true)
        }
        .overlay(alignment: .topTrailing) {
            Button {
                showingLocationPicker = true
            } label: {
                Image(systemName: "arrow.up.left.and.arrow.down.right")
                    .font(.caption.weight(.semibold))
                    .foregroundStyle(TE.textPrimary)
                    .frame(width: 32, height: 32)
                    .background(Circle().fill(TE.card.opacity(0.92)))
                    .frame(width: 44, height: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .accessibilityLabel("Choose on a larger map")
        }
        .frame(height: 120)
        .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(TE.border, lineWidth: 1))
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Map preview")
        .accessibilityValue(coordinate == nil ? String(localized: "No pin yet") : String(localized: "Pin placed"))
    }

    private func isSelectedAppleMapsResult(_ result: PlaceSearchResult) -> Bool {
        guard let locationSelection, locationSelection.source == .appleMaps else { return false }
        return Self.distance(from: locationSelection.coordinate, to: result.coordinate) < 1
    }

    /// One row of the merged results: a saved place or an Apple Maps match.
    private enum LogStopResult: Identifiable {
        case saved(SavedPlace, inkIndex: Int)
        case appleMaps(PlaceSearchResult)

        var id: String {
            switch self {
            case .saved(let place, _): "saved-\(place.id.uuidString)"
            case .appleMaps(let result): "maps-\(result.id)"
            }
        }
    }

    /// How well a name answers the query: a name that starts with it beats one that contains it,
    /// which beats a match on the address alone.
    private func relevanceTier(name: String, address: String?) -> Int {
        guard !trimmedQuery.isEmpty else { return 0 }
        let options: String.CompareOptions = [.caseInsensitive, .diacriticInsensitive]
        if name.range(of: trimmedQuery, options: options.union(.anchored)) != nil { return 0 }
        if name.range(of: trimmedQuery, options: options) != nil { return 1 }
        if address?.range(of: trimmedQuery, options: options) != nil { return 2 }
        return 3
    }

    /// Saved places and Apple Maps results in one list, by relevance and then distance;
    /// a saved place goes first only when the two are otherwise equal.
    private var mergedResults: [LogStopResult] {
        let reference = referenceCoordinate
        struct Ranked { let result: LogStopResult; let tier: Int; let distance: CLLocationDistance; let isSaved: Bool; let order: Int }
        var ranked: [Ranked] = []
        for (index, place) in matchingSavedPlaces.enumerated() {
            ranked.append(Ranked(
                result: .saved(place, inkIndex: index),
                tier: relevanceTier(name: place.name, address: place.address),
                distance: reference.map { Self.distance(from: $0, to: place.coordinate) } ?? 0,
                isSaved: true,
                order: index
            ))
        }
        for (index, result) in appleMapsResults.enumerated() {
            ranked.append(Ranked(
                result: .appleMaps(result),
                tier: relevanceTier(name: result.name, address: result.address),
                distance: reference.map { Self.distance(from: $0, to: result.coordinate) } ?? 0,
                isSaved: false,
                order: index
            ))
        }
        return ranked.sorted { a, b in
            if a.tier != b.tier { return a.tier < b.tier }
            // Within a few metres two places are as near as each other.
            if abs(a.distance - b.distance) > 5 { return a.distance < b.distance }
            if a.isSaved != b.isSaved { return a.isSaved }
            return a.order < b.order
        }.map(\.result)
    }

    @ViewBuilder
    private func resultRow(_ entry: LogStopResult) -> some View {
        switch entry {
        case .saved(let place, let inkIndex):
            savedPlaceRow(place, inkIndex: inkIndex)
        case .appleMaps(let result):
            appleMapsRow(result)
        }
    }

    private func savedPlaceRow(_ place: SavedPlace, inkIndex index: Int) -> some View {
        Button {
            applySavedPlace(place)
        } label: {
            LogStopResultRow(
                title: place.name,
                subtitle: place.address,
                trailing: selectedSavedPlaceID == place.id ? nil : String(localized: "Saved"),
                ink: TE.placeInks[index % TE.placeInks.count],
                isSelected: selectedSavedPlaceID == place.id
            )
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(selectedSavedPlaceID == place.id ? .isSelected : [])
        .contextMenu {
            Button("Delete Saved Location", systemImage: "trash", role: .destructive) {
                deleteSavedPlace(place)
            }
        }
        .accessibilityAction(named: "Delete saved location") {
            deleteSavedPlace(place)
        }
    }

    private func appleMapsRow(_ result: PlaceSearchResult) -> some View {
        let isSelected = isSelectedAppleMapsResult(result)
        return Button {
            applyLocationSelection(.appleMapsResult(result))
        } label: {
            LogStopResultRow(
                title: result.name,
                subtitle: [result.address, String(localized: "Apple Maps")]
                    .compactMap { $0 }
                    .filter { !$0.isEmpty }
                    .joined(separator: " · "),
                trailing: distanceText(to: result.coordinate),
                ink: TE.textMuted,
                isSelected: isSelected,
                uppercasesTrailing: false
            )
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }

    @ViewBuilder
    private var resultsCard: some View {
        let results = mergedResults
        VStack(spacing: 0) {
            ForEach(results) { entry in
                resultRow(entry)
                Divider().overlay(TE.border).padding(.leading, 38)
            }

            if let searchError = placeSearch.errorMessage, !trimmedQuery.isEmpty {
                resultsMessage(searchError)
            } else if results.isEmpty, trimmedQuery.count >= 2, !placeSearch.isSearching {
                resultsMessage(String(localized: "No places match “\(trimmedQuery)”."))
            }

            Button {
                dropPinAtMapCentre()
            } label: {
                LogStopResultRow(
                    title: String(localized: "Drop a pin here"),
                    subtitle: String(localized: "Use the map centre"),
                    trailing: nil,
                    ink: TE.inkRust,
                    systemImage: "mappin.and.ellipse",
                    isSelected: locationSelection?.source == .mapPin
                )
            }
            .buttonStyle(.plain)
            .disabled(previewCenter == nil)
            .accessibilityHint("Pins the stop where the map preview is centred.")

            if mode != .addPastVisit, selectedSavedPlaceID != nil || locationSelection != nil {
                Divider().overlay(TE.border).padding(.leading, 38)
                Button {
                    pinLookupTask?.cancel()
                    selectedSavedPlaceID = nil
                    locationSelection = nil
                    locationManager.requestCurrentLocationForManualVisit()
                } label: {
                    LogStopResultRow(
                        title: String(localized: "Use current location instead"),
                        subtitle: nil,
                        trailing: nil,
                        ink: TE.inkRust,
                        systemImage: "location.fill"
                    )
                }
                .buttonStyle(.plain)
            }
        }
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(TE.border, lineWidth: 1))
    }

    private func resultsMessage(_ text: String) -> some View {
        VStack(spacing: 0) {
            Text(text)
                .font(.footnote)
                .foregroundStyle(TE.textMuted)
                .padding(.horizontal, 12)
                .padding(.vertical, 10)
                .frame(maxWidth: .infinity, alignment: .leading)
            Divider().overlay(TE.border).padding(.leading, 12)
        }
    }

    /// Pins the stop at the preview map's centre, then names it from Apple's lookup if still unnamed.
    private func dropPinAtMapCentre() {
        guard let center = previewCenter, CLLocationCoordinate2DIsValid(center) else { return }
        applyLocationSelection(ManualLocationSelection(name: "", address: nil, coordinate: center, source: .mapPin))

        pinLookupTask?.cancel()
        guard NetworkGeocodingPolicy.isAllowed else { return }
        pinLookupTask = Task {
            let location = CLLocation(latitude: center.latitude, longitude: center.longitude)
            guard let result = try? await Self.pinGeocoder.reverseGeocode(location: location),
                  !Task.isCancelled,
                  let selection = locationSelection,
                  selection.source == .mapPin,
                  Self.distance(from: selection.coordinate, to: center) < 1 else { return }
            if name.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty,
               let suggested = result.name ?? result.address?.components(separatedBy: ",").first {
                nameBinding.wrappedValue = suggested
            }
            if address.isEmpty, let suggestedAddress = result.address, !suggestedAddress.isEmpty {
                addressBinding.wrappedValue = suggestedAddress
            }
        }
    }

    // MARK: Overlaps

    /// The departure the stop would be saved with; nil while still there.
    private var proposedDeparture: Date? {
        mode.requiresDeparture ? departedAt : (isStillHere ? nil : departedAt)
    }

    private var overlapCheckKey: String {
        "\(arrivedAt.timeIntervalSinceReferenceDate)|\(proposedDeparture?.timeIntervalSinceReferenceDate ?? -1)"
    }

    private func refreshOverlapCheck() {
        guard validationMessage == nil else {
            overlapCheck = .none
            return
        }
        overlapCheck = ManualVisitStore(context: modelContext)
            .overlapCheck(arrivedAt: arrivedAt, departedAt: proposedDeparture)
    }

    /// "Overlaps Mission Office by 15 min (until 12:30). Saving will end that visit at 12:15."
    private var overlapNotices: [String] {
        switch overlapCheck {
        case .none:
            return []
        case .conflict(let visit):
            let start = LocationMapView.clockText(visit.arrivedAt)
            let end = visit.departedAt.map(LocationMapView.clockText) ?? String(localized: "now")
            return [String(localized: "\(visit.displayName) (\(start)–\(end)) falls inside this stop, so it can’t be trimmed. Review the times, or save to keep both visits.")]
        case .trims(let resolutions):
            return resolutions.map { resolution in
                let name = resolution.visit.displayName
                let minutes = max(1, Int((resolution.overlap / 60).rounded()))
                switch resolution.change {
                case .endsAt(let end):
                    let newEnd = LocationMapView.clockText(end)
                    if let until = resolution.visit.departedAt.map(LocationMapView.clockText) {
                        return String(localized: "Overlaps \(name) by \(minutes) min (until \(until)). Saving will end that visit at \(newEnd).")
                    }
                    return String(localized: "Overlaps \(name), which is still in progress. Saving will end that visit at \(newEnd).")
                case .startsAt(let start):
                    let from = LocationMapView.clockText(resolution.visit.arrivedAt)
                    let newStart = LocationMapView.clockText(start)
                    return String(localized: "Overlaps \(name) by \(minutes) min (from \(from)). Saving will start that visit at \(newStart).")
                }
            }
        }
    }

    // MARK: Place, time, location

    private var placeCard: some View {
        VStack(alignment: .leading, spacing: 0) {
            LogStopField(label: "PLACE") {
                TextField("Place name", text: nameBinding)
                    .font(TE.editorial(.title3))
                    .textInputAutocapitalization(.words)
            }
            Divider().overlay(TE.border).padding(.leading, 16)
            LogStopField(label: "ADDRESS OR NOTE") {
                TextField("Address or note", text: addressBinding)
                    .font(.subheadline)
                    .textInputAutocapitalization(.words)
            }
        }
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(TE.border, lineWidth: 1))
    }

    private var showsDeparturePicker: Bool {
        mode.requiresDeparture || !isStillHere
    }

    private var timeCard: some View {
        VStack(spacing: 0) {
            ViewThatFits(in: .horizontal) {
                HStack(spacing: 10) { timeFields }
                VStack(spacing: 10) { timeFields }
            }
            .padding(12)

            if !mode.requiresDeparture {
                Divider().overlay(TE.border)
                Toggle(isOn: $isStillHere) {
                    Text("Still here")
                        .font(.subheadline)
                }
                .toggleStyle(TEToggleStyle())
                .padding(.horizontal, 16)
                .padding(.vertical, 10)
            }
        }
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(TE.border, lineWidth: 1))
    }

    @ViewBuilder
    private var timeFields: some View {
        LogStopTimeField(label: "ARRIVED", selection: $arrivedAt)
        if showsDeparturePicker {
            LogStopTimeField(label: mode.requiresDeparture ? "DEPARTED" : "LEFT", selection: $departedAt)
        }
    }

    @ViewBuilder
    private var locationCard: some View {
        VStack(alignment: .leading, spacing: 8) {
            if let selection = locationSelection {
                locationStatus(
                    selectedSavedPlace == nil ? "Selected location ready" : "Saved location ready",
                    systemImage: selectedSavedPlace == nil ? "mappin.and.ellipse" : "bookmark.fill",
                    isReady: true
                )
                if !selection.name.isEmpty {
                    Text(selection.name)
                        .font(.subheadline.weight(.semibold))
                }
                if let selectedAddress = selection.address, !selectedAddress.isEmpty {
                    Text(selectedAddress)
                        .font(.footnote)
                        .foregroundStyle(TE.textMuted)
                }
                coordinateText(String(
                    format: "%.6f, %.6f",
                    selection.coordinate.latitude,
                    selection.coordinate.longitude
                ))
            } else if mode == .addPastVisit {
                locationStatus("No location yet", systemImage: "map", isReady: false)
                Text("Choose where the visit happened. Your current GPS location will not be used.")
                    .font(.footnote)
                    .foregroundStyle(TE.textMuted)
            } else if let manualLocation {
                locationStatus("Current location ready", systemImage: "location.fill", isReady: true)
                coordinateText(String(
                    format: "%.6f, %.6f • ±%.0fm",
                    manualLocation.coordinate.latitude,
                    manualLocation.coordinate.longitude,
                    manualLocation.horizontalAccuracy
                ))
            } else {
                locationStatus("Waiting for a fresh accurate location", systemImage: "location", isReady: false)
                Text("Manual visits require a saved location or a current location from the last 5 minutes with accuracy under 100 meters.")
                    .font(.footnote)
                    .foregroundStyle(TE.textMuted)
                Button("Request Location") {
                    locationManager.requestCurrentLocationForManualVisit()
                }
                .font(.subheadline.weight(.semibold))
                .foregroundStyle(TE.inkRust)
                .frame(minHeight: 44)
                .buttonStyle(.plain)
            }
        }
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(TE.border, lineWidth: 1))
    }

    private func locationStatus(_ title: LocalizedStringKey, systemImage: String, isReady: Bool) -> some View {
        Label(title, systemImage: systemImage)
            .font(TE.mono(.caption, weight: .semibold))
            .foregroundStyle(isReady ? TE.success : TE.textMuted)
    }

    private func coordinateText(_ text: String) -> some View {
        Text(text)
            .font(TE.mono(.caption))
            .foregroundStyle(TE.textMuted)
    }

    @ViewBuilder
    private var reuseCard: some View {
        VStack(alignment: .leading, spacing: 10) {
            if let selectedSavedPlace {
                Label("Using saved location: \(selectedSavedPlace.name)", systemImage: "bookmark.fill")
                    .font(.subheadline)
            } else {
                Toggle(isOn: $saveAsReusableLocation) {
                    Text("Save as reusable location")
                        .font(.subheadline)
                }
                .toggleStyle(TEToggleStyle())

                if saveAsReusableLocation {
                    Picker("Match radius", selection: $reusableRadiusMeters) {
                        Text("50 m").tag(50.0)
                        Text("100 m").tag(100.0)
                        Text("150 m").tag(150.0)
                        Text("250 m").tag(250.0)
                    }
                    .pickerStyle(.segmented)
                    Text("Future automatic visits within this radius will use this name before network geocoding.")
                        .font(.footnote)
                        .foregroundStyle(TE.textMuted)
                }
            }
        }
        .fixedSize(horizontal: false, vertical: true)
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(16)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(TE.border, lineWidth: 1))
    }

    private var saveTitle: String {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        return trimmedName.isEmpty ? String(localized: "Save") : String(localized: "Add \(trimmedName)")
    }

    private var saveBar: some View {
        Button { save() } label: {
            Text(saveTitle)
                .font(.body.weight(.semibold))
                .lineLimit(1)
                .foregroundStyle(TE.onInkCard)
                .frame(maxWidth: .infinity, minHeight: 52)
                .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(TE.accent))
                .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
        }
        .buttonStyle(.plain)
        .disabled(isSaveDisabled)
        .opacity(isSaveDisabled ? 0.45 : 1)
        .padding(.horizontal, 16)
        .padding(.top, 8)
        .padding(.bottom, 12)
        .background(TE.surface)
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

    private func deleteSavedPlace(_ place: SavedPlace) {
        if selectedSavedPlaceID == place.id {
            selectedSavedPlaceID = nil
            locationSelection = nil
        }
        viewModel.deleteSavedPlace(place)
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
        let placeSource = locationSelection?.visitPlaceSource ?? .userEntered
        let visit: Visit?
        let mutationError: VisitMutationError?
        if allowOverlap {
            visit = viewModel.createManualVisit(
                name: name,
                address: address,
                coordinate: coordinate,
                arrivedAt: arrivedAt,
                departedAt: proposedDeparture,
                placeSource: placeSource,
                allowOverlap: true
            )
            mutationError = viewModel.visitMutationError
        } else {
            // Overlapped stays (recorded or manual) are trimmed in the same save, as the notice said.
            let store = ManualVisitStore(context: modelContext)
            visit = store.createManualVisitTrimmingOverlaps(
                name: name,
                address: address,
                coordinate: coordinate,
                arrivedAt: arrivedAt,
                departedAt: proposedDeparture,
                placeSource: placeSource
            )
            mutationError = store.visitMutationError
            if visit != nil { viewModel.loadData() }
        }

        guard let visit else {
            isSaving = false
            if case .overlap = mutationError {
                overlapAlertMessage = mutationError?.localizedDescription ?? ""
                showingOverlapConfirmation = true
            } else {
                errorMessage = mutationError?.localizedDescription
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

// MARK: - Log a stop parts

/// One place in the "Log a stop" results: an ink dot, the name and address, and a quiet trailing tag.
private struct LogStopResultRow: View {
    let title: String
    let subtitle: String?
    let trailing: String?
    let ink: Color
    var systemImage: String? = nil
    var isSelected = false
    /// Tags like "Saved" are set in mono capitals; distances keep their unit case.
    var uppercasesTrailing = true

    var body: some View {
        HStack(spacing: 12) {
            Group {
                if let systemImage {
                    Image(systemName: systemImage)
                        .font(.footnote.weight(.semibold))
                        .foregroundStyle(ink)
                } else {
                    Circle().fill(ink).frame(width: 10, height: 10)
                }
            }
            .frame(width: 14)
            .accessibilityHidden(true)

            VStack(alignment: .leading, spacing: 2) {
                Text(title)
                    .font(.subheadline.weight(isSelected ? .semibold : .medium))
                    .foregroundStyle(TE.textPrimary)
                if let subtitle, !subtitle.isEmpty {
                    Text(subtitle)
                        .font(.caption)
                        .foregroundStyle(TE.textMuted)
                }
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            if isSelected {
                Image(systemName: "checkmark")
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(TE.inkRust)
                    .accessibilityHidden(true)
            } else if let trailing {
                Text(uppercasesTrailing ? trailing.uppercased() : trailing)
                    .font(TE.mono(.caption2, weight: .medium))
                    .tracking(1)
                    .foregroundStyle(TE.textMuted)
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 10)
        .frame(minHeight: 52)
        .background(isSelected ? TE.inkRust.opacity(0.08) : Color.clear)
        .contentShape(Rectangle())
    }
}

/// A mono-labelled field inside a "Log a stop" card.
private struct LogStopField<Content: View>: View {
    let label: LocalizedStringKey
    @ViewBuilder let content: Content

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(TE.mono(.caption2, weight: .semibold))
                .tracking(1.2)
                .foregroundStyle(TE.textMuted)
            content
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 10)
        .frame(maxWidth: .infinity, minHeight: 44, alignment: .leading)
    }
}

/// An arrival or departure time: mono label over a compact date and time picker.
private struct LogStopTimeField: View {
    let label: LocalizedStringKey
    @Binding var selection: Date

    var body: some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(TE.mono(.caption2, weight: .semibold))
                .tracking(1.2)
                .foregroundStyle(TE.textMuted)
                .accessibilityHidden(true)
            DatePicker(
                label,
                selection: $selection,
                in: Date.distantPast...Date(),
                displayedComponents: [.date, .hourAndMinute]
            )
            .labelsHidden()
            .tint(TE.inkRust)
        }
        .padding(10)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(TE.surface))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(TE.border, lineWidth: 1))
    }
}

/// The rust-inked warning strip under the times, as in the overlap notice.
private struct LogStopNotice: View {
    let text: String
    let systemImage: String

    var body: some View {
        HStack(alignment: .firstTextBaseline, spacing: 10) {
            Image(systemName: systemImage)
                .accessibilityHidden(true)
            Text(text)
                .fixedSize(horizontal: false, vertical: true)
        }
        .font(.footnote)
        .foregroundStyle(TE.inkRust)
        .padding(12)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 12, style: .continuous).fill(TE.inkRust.opacity(0.08)))
        .overlay(RoundedRectangle(cornerRadius: 12, style: .continuous).strokeBorder(TE.inkRust.opacity(0.35), lineWidth: 1))
        .accessibilityElement(children: .combine)
    }
}

/// "Show days": pick the map's range from presets or a calendar, and switch its layers.
struct DateRangeFilterSheet: View {
    @Binding var dateRange: ClosedRange<Date>
    @Binding var isPresented: Bool
    var recordedDays: Set<Date> = []
    var layers: MapLayerSettings? = nil
    var onApply: ((MapDatePreset?) -> Void)? = nil

    @State private var preset: MapDatePreset?
    @State private var lowerDay: Date
    @State private var upperDay: Date
    /// The first tap of a new range, waiting for its other end.
    @State private var anchorDay: Date?
    @State private var displayedMonth: Date
    @State private var activeLayerHelp: LayerToggleHelp?

    init(
        dateRange: Binding<ClosedRange<Date>>,
        isPresented: Binding<Bool>,
        activePreset: MapDatePreset? = nil,
        recordedDays: Set<Date> = [],
        layers: MapLayerSettings? = nil,
        onApply: ((MapDatePreset?) -> Void)? = nil
    ) {
        _dateRange = dateRange
        _isPresented = isPresented
        self.recordedDays = recordedDays
        self.layers = layers
        self.onApply = onApply

        let calendar = Calendar.current
        let range = activePreset?.range() ?? dateRange.wrappedValue
        let upper = calendar.startOfDay(for: min(range.upperBound, Date()))
        let lower = range.lowerBound == .distantPast ? upper : calendar.startOfDay(for: range.lowerBound)
        _preset = State(initialValue: activePreset)
        _lowerDay = State(initialValue: min(lower, upper))
        _upperDay = State(initialValue: upper)
        _displayedMonth = State(initialValue: calendar.dateInterval(of: .month, for: upper)?.start ?? upper)
    }

    private var calendar: Calendar { Calendar.current }

    private var dayCount: Int {
        (calendar.dateComponents([.day], from: lowerDay, to: upperDay).day ?? 0) + 1
    }

    private var rangeText: String {
        if preset == .all { return String(localized: "All time") }
        let style = Date.FormatStyle.dateTime.month(.abbreviated).day()
        if lowerDay == upperDay { return lowerDay.formatted(style) }
        return "\(lowerDay.formatted(style)) – \(upperDay.formatted(style))"
    }

    /// Days the apply button counts: the highlighted span, or for "All" every day since the first record.
    private var applyDayCount: Int {
        guard preset == .all else { return dayCount }
        let today = calendar.startOfDay(for: Date())
        guard let first = recordedDays.min(), first < today else { return 1 }
        return (calendar.dateComponents([.day], from: first, to: today).day ?? 0) + 1
    }

    /// Always "Show N days", presets included, so the button says how much the map will draw.
    private var applyTitle: String {
        let count = applyDayCount
        return count == 1 ? String(localized: "Show 1 day") : String(localized: "Show \(count) days")
    }

    var body: some View {
        VStack(spacing: 0) {
            header

            ScrollView {
                VStack(alignment: .leading, spacing: 14) {
                    presetChips

                    MapRangeCalendar(
                        displayedMonth: $displayedMonth,
                        lowerDay: lowerDay,
                        upperDay: upperDay,
                        isAllTime: preset == .all,
                        recordedDays: recordedDays,
                        rangeText: rangeText,
                        onSelect: selectDay
                    )

                    if let layers {
                        layerCard(layers)
                    }
                }
                .padding(.horizontal, 16)
                .padding(.top, 12)
                .padding(.bottom, 16)
            }

            Button(action: apply) {
                Text(applyTitle)
                    .font(.body.weight(.semibold))
                    .foregroundStyle(TE.onInkCard)
                    .frame(maxWidth: .infinity, minHeight: 52)
                    .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(TE.accent))
                    .contentShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
            }
            .buttonStyle(.plain)
            .padding(.horizontal, 16)
            .padding(.bottom, 12)
        }
        .foregroundStyle(TE.textPrimary)
        .background(TE.surface.ignoresSafeArea())
        .presentationDetents([.large])
        .presentationDragIndicator(.visible)
        #if DEBUG
        .onAppear {
            if ProcessInfo.processInfo.arguments.contains("--demo-road-layer-help") {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.5) {
                    activeLayerHelp = .roadMatchedPath
                }
            }
        }
        #endif
    }

    private var header: some View {
        ZStack {
            Text("Show days")
                .font(.body.weight(.semibold))
                .accessibilityAddTraits(.isHeader)

            HStack {
                Button("Reset") { select(.today) }
                    .foregroundStyle(TE.textMuted)
                Spacer()
                Button("Apply", action: apply)
                    .fontWeight(.semibold)
                    .foregroundStyle(TE.inkRust)
            }
            .buttonStyle(.plain)
            .font(.subheadline)
            .frame(minHeight: 44)
        }
        .padding(.horizontal, 20)
        .padding(.top, 14)
    }

    private var presetChips: some View {
        ScrollView(.horizontal) {
            HStack(spacing: 8) {
                ForEach(MapDatePreset.allCases, id: \.self) { option in
                    let isOn = preset == option
                    Button {
                        select(option)
                    } label: {
                        Text(option.accessibilityLabel)
                            .font(.footnote.weight(.medium))
                            .foregroundStyle(isOn ? TE.onInkCard : TE.textPrimary)
                            .padding(.horizontal, 14)
                            .frame(minHeight: 44)
                            .background(Capsule().fill(isOn ? TE.accent : TE.card))
                            .overlay(Capsule().strokeBorder(isOn ? Color.clear : TE.border, lineWidth: 1))
                            .contentShape(Capsule())
                    }
                    .buttonStyle(.plain)
                    .accessibilityAddTraits(isOn ? .isSelected : [])
                    .accessibilityHint("Filters the map date range.")
                }
            }
        }
        .scrollIndicators(.hidden)
    }

    private func layerCard(_ layers: MapLayerSettings) -> some View {
        VStack(alignment: .leading, spacing: 0) {
            MapLayerRow(title: "Visits", subtitle: visitsLayerSubtitle(layers), systemImage: "mappin.circle.fill",
                        help: .visitMarkers, isOn: layers.visits, activeHelp: $activeLayerHelp,
                        showsDivider: layers.minimumStayMinutes == nil || !layers.visits.wrappedValue)
            if let minimumStay = layers.minimumStayMinutes, layers.visits.wrappedValue {
                MapMinimumStayRow(minutes: minimumStay)
            }
            MapLayerRow(title: "Routes", subtitle: "Paths between stops", systemImage: "point.topleft.down.to.point.bottomright.curvepath",
                        help: .travelPath, isOn: layers.routes, activeHelp: $activeLayerHelp)
            MapLayerRow(title: "Photos", subtitle: "Matched by time and place", systemImage: "camera.fill",
                        help: .photoMarkers, isOn: layers.photos, activeHelp: $activeLayerHelp)
            MapLayerRow(title: "Photo images", subtitle: "Thumbnails in photo markers", systemImage: "photo",
                        help: .photoImages, isOn: layers.photoImages, activeHelp: $activeLayerHelp)
                .disabled(layers.photoDots.wrappedValue)
            MapLayerRow(title: "Photo dots", subtitle: "Small dots instead of images", systemImage: "circle.fill",
                        help: .photoDots, isOn: layers.photoDots, activeHelp: $activeLayerHelp)
            MapLayerRow(title: "Road-matched path", subtitle: "Bends GPS gaps onto roads", systemImage: "road.lanes",
                        help: .roadMatchedPath, isOn: layers.roadMatchedPath, activeHelp: $activeLayerHelp)
            MapLayerRow(title: "Straight-line path gaps", subtitle: "Shows unmatched gaps", systemImage: "line.diagonal",
                        help: .straightLinePathGaps, isOn: layers.straightLineGaps, activeHelp: $activeLayerHelp)
            MapLayerRow(title: "Point markers", subtitle: "Sampled GPS dots", systemImage: "smallcircle.filled.circle",
                        help: .pointMarkers, isOn: layers.pointMarkers, activeHelp: $activeLayerHelp)
            MapLayerRow(title: "Start and end markers", subtitle: "First and latest points", systemImage: "flag.fill",
                        help: .startEndMarkers, isOn: layers.startEndMarkers, activeHelp: $activeLayerHelp,
                        showsDivider: layers.hasSessionPoints)
            if layers.hasSessionPoints {
                MapLayerRow(title: "Active session path", subtitle: "The session running now", systemImage: "waveform.path.ecg",
                            help: .activeSessionPath, isOn: layers.sessionPath, activeHelp: $activeLayerHelp,
                            showsDivider: false)
            }
        }
        .padding(.horizontal, 16)
        .padding(.vertical, 4)
        .background(RoundedRectangle(cornerRadius: 16, style: .continuous).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 16, style: .continuous).strokeBorder(TE.border, lineWidth: 1))
    }

    /// "Stops longer than 5 min", from the map's own shortest-stop setting.
    private func visitsLayerSubtitle(_ layers: MapLayerSettings) -> LocalizedStringKey {
        let minutes = layers.minimumStayMinutes?.wrappedValue ?? 0
        return minutes > 0 ? "Stops longer than \(minutes) min" : "Every stop, any length"
    }

    private func select(_ option: MapDatePreset) {
        preset = option
        anchorDay = nil
        let range = option.range()
        upperDay = calendar.startOfDay(for: range.upperBound)
        lowerDay = option == .all ? upperDay : calendar.startOfDay(for: range.lowerBound)
        displayedMonth = calendar.dateInterval(of: .month, for: upperDay)?.start ?? upperDay
    }

    private func selectDay(_ day: Date) {
        if let anchorDay {
            lowerDay = min(anchorDay, day)
            upperDay = max(anchorDay, day)
            self.anchorDay = nil
        } else {
            lowerDay = day
            upperDay = day
            anchorDay = day
        }

        let today = calendar.startOfDay(for: Date())
        let yesterday = calendar.date(byAdding: .day, value: -1, to: today)
        let startOfMonth = calendar.dateInterval(of: .month, for: today)?.start
        if lowerDay == upperDay, lowerDay == today {
            preset = .today
        } else if lowerDay == upperDay, lowerDay == yesterday {
            preset = .yesterday
        } else if lowerDay == startOfMonth, upperDay == today {
            preset = .thisMonth
        } else {
            preset = nil
        }
    }

    private func apply() {
        if let preset {
            dateRange = preset.range()
        } else {
            let nextDay = calendar.date(byAdding: .day, value: 1, to: upperDay) ?? upperDay
            let endOfDay = Date(timeIntervalSinceReferenceDate: nextDay.timeIntervalSinceReferenceDate.nextDown)
            dateRange = lowerDay...max(lowerDay, min(endOfDay, Date()))
        }
        onApply?(preset)
        isPresented = false
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
                VStack(alignment: .leading, spacing: 18) {
                    header
                    timesCard

                    NearbyVisitNameSuggestionSection(visit: visit) { suggestion in
                        correctVisit(with: suggestion)
                    }

                    if let notes = visit.notes, !notes.isEmpty {
                        VStack(alignment: .leading, spacing: 6) {
                            Text("NOTE")
                                .font(TE.mono(.caption2, weight: .semibold))
                                .tracking(1.2)
                                .foregroundStyle(TE.textMuted)
                            Text(notes)
                                .font(TE.editorial(.body))
                                .fixedSize(horizontal: false, vertical: true)
                        }
                        .frame(maxWidth: .infinity, alignment: .leading)
                        .padding(16)
                        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(TE.card))
                        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(TE.border, lineWidth: 1))
                    }

                    actions
                }
                .padding(.horizontal, 20)
                .padding(.vertical, 16)
            }
            .foregroundStyle(TE.textPrimary)
            .background(TE.surface.ignoresSafeArea())
            .navigationBarTitleDisplayMode(.inline)
            .toolbarBackground(TE.surface, for: .navigationBar)
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

    private var header: some View {
        VStack(alignment: .leading, spacing: 8) {
            HStack(spacing: 8) {
                if viewModel.isCurrentVisit(visit) {
                    Circle().fill(TE.liveDot).frame(width: 8, height: 8)
                        .accessibilityHidden(true)
                }
                Text(statusCaption)
                    .font(TE.mono(.caption2, weight: .semibold))
                    .tracking(1.2)
                    .foregroundStyle(visit.confirmationStatus == .unconfirmed ? TE.inkRust : TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
                Spacer(minLength: 0)
                if visit.hasCustomName {
                    Button("Reset") {
                        resetName()
                    }
                    .font(.footnote.weight(.semibold))
                    .foregroundStyle(TE.inkRust)
                    .frame(minHeight: 44)
                    .buttonStyle(.plain)
                    .accessibilityHint("Restores the automatically detected visit name.")
                }
            }

            TextField("Visit name", text: $nameText, axis: .vertical)
                .font(TE.editorial(.title))
                .textFieldStyle(.plain)
                .submitLabel(.done)
                .focused($isNameFieldFocused)
                .onSubmit(saveName)
                .onChange(of: isNameFieldFocused) { _, focused in
                    if !focused {
                        saveName()
                    }
                }
                .accessibilityLabel("Name")

            if !subtitle.isEmpty {
                Text(subtitle)
                    .font(.footnote)
                    .foregroundStyle(TE.textMuted)
            }

            if visit.hasCustomName, visit.automaticDisplayName != visit.displayName {
                Text("Detected as \(visit.automaticDisplayName)")
                    .font(.caption)
                    .foregroundStyle(TE.textMuted)
            }
        }
    }

    private var timesCard: some View {
        ViewThatFits(in: .horizontal) {
            HStack(alignment: .bottom, spacing: 10) {
                timeColumns
                Spacer(minLength: 0)
                durationText
            }
            VStack(alignment: .leading, spacing: 10) {
                timeColumns
                durationText
            }
        }
        .padding(16)
        .frame(maxWidth: .infinity, alignment: .leading)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(TE.border, lineWidth: 1))
    }

    @ViewBuilder
    private var timeColumns: some View {
        timeColumn("ARRIVED", date: visit.arrivedAt)
        if let departed = visit.departedAt {
            timeColumn("LEFT", date: departed)
        }
    }

    private func timeColumn(_ label: LocalizedStringKey, date: Date) -> some View {
        VStack(alignment: .leading, spacing: 4) {
            Text(label)
                .font(TE.mono(.caption2, weight: .semibold))
                .tracking(1.2)
                .foregroundStyle(TE.textMuted)
            Text(LocationMapView.clockText(date))
                .font(TE.mono(.title3, weight: .medium))
            Text(date.formatted(.dateTime.month(.abbreviated).day()))
                .font(.caption)
                .foregroundStyle(TE.textMuted)
        }
        .padding(.trailing, 14)
        .accessibilityElement(children: .combine)
    }

    private var durationText: some View {
        Text(visit.formattedDuration)
            .font(TE.mono(.subheadline, weight: .semibold))
            .foregroundStyle(TE.inkRust)
            .accessibilityLabel(Text("Duration \(visit.formattedDuration)"))
    }

    @ViewBuilder
    private var actions: some View {
        VStack(spacing: 10) {
            if visit.confirmationStatus == .unconfirmed {
                Button {
                    saveName()
                    viewModel.confirmVisit(visit)
                } label: {
                    actionLabel("Confirm Place", systemImage: "checkmark.seal", filled: true)
                }
                .buttonStyle(.plain)
            }

            if visit.source == .manual, visit.departedAt == nil {
                Button {
                    if !viewModel.checkoutVisit(visit) {
                        checkoutError = viewModel.visitMutationError?.localizedDescription
                    }
                } label: {
                    actionLabel("Check Out", systemImage: "rectangle.portrait.and.arrow.right", filled: visit.confirmationStatus != .unconfirmed)
                }
                .buttonStyle(.plain)
                .disabled(visit.arrivedAt > Date())
                .accessibilityHint("Ends this manual visit at the current time.")
            }

            NavigationLink {
                VisitDetailView(visit: visit, viewModel: viewModel)
            } label: {
                actionLabel("More Details", systemImage: "info.circle", filled: false)
            }
            .buttonStyle(.plain)
        }
    }

    private func actionLabel(_ title: LocalizedStringKey, systemImage: String, filled: Bool) -> some View {
        Label(title, systemImage: systemImage)
            .font(.subheadline.weight(.semibold))
            .foregroundStyle(filled ? TE.onInkCard : TE.textPrimary)
            .frame(maxWidth: .infinity, minHeight: 48)
            .background(Capsule().fill(filled ? TE.accent : TE.card))
            .overlay(Capsule().strokeBorder(filled ? Color.clear : TE.border, lineWidth: 1))
            .contentShape(Capsule())
    }

    /// "DETECTED · NOT CONFIRMED", in the artboard's words, from the visit's source and status.
    private var statusCaption: String {
        var parts = [visit.source.displayName, visit.confirmationStatus.displayName]
        if viewModel.isCurrentVisit(visit) {
            parts.insert(String(localized: "Now"), at: 0)
        }
        return parts.joined(separator: " · ").uppercased()
    }

    private var subtitle: String {
        var parts: [String] = []
        if let address = visit.address, !address.isEmpty, address != visit.displayName {
            parts.append(address)
        }
        let calendar = Calendar.current
        if calendar.isDateInToday(visit.arrivedAt) {
            parts.append(String(localized: "Today"))
        } else if calendar.isDateInYesterday(visit.arrivedAt) {
            parts.append(String(localized: "Yesterday"))
        } else {
            parts.append(visit.arrivedAt.formatted(.dateTime.weekday(.abbreviated).month(.abbreviated).day()))
        }
        return parts.joined(separator: " · ")
    }

    private func saveName() {
        viewModel.updateVisitName(visit, customName: nameText)
        nameText = visit.displayName
    }

    private func resetName() {
        viewModel.clearVisitName(visit)
        nameText = visit.displayName
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

// MARK: - Quick Filter Bar

enum MapDatePreset: CaseIterable, Hashable {
    case today, yesterday, sevenDays, thisMonth, thirtyDays, all

    var label: String {
        switch self {
        case .today: return String(localized: "Today")
        case .yesterday: return String(localized: "Yesterday")
        case .sevenDays: return String(localized: "7D")
        case .thisMonth: return String(localized: "Month")
        case .thirtyDays: return String(localized: "30D")
        case .all: return String(localized: "All")
        }
    }

    var accessibilityLabel: String {
        switch self {
        case .today: return String(localized: "Today")
        case .yesterday: return String(localized: "Yesterday")
        case .sevenDays: return String(localized: "Last 7 days")
        case .thisMonth: return String(localized: "This month")
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
        case .thisMonth:
            let startOfMonth = calendar.dateInterval(of: .month, for: referenceDate)?.start
                ?? calendar.startOfDay(for: referenceDate)
            return startOfMonth...referenceDate
        case .thirtyDays:
            return calendar.date(byAdding: .day, value: -30, to: referenceDate)!...referenceDate
        case .all:
            return Date.distantPast...referenceDate
        }
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

struct LayerToggleHelpPopover: View {
    let systemImage: String
    let help: LayerToggleHelp
    let isOn: Bool

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 10) {
                Image(systemName: systemImage)
                    .font(.headline.weight(.semibold))
                    .foregroundStyle(isOn ? TE.onInkCard : TE.textPrimary)
                    .frame(width: 34, height: 34)
                    .background {
                        Circle().fill(isOn ? TE.accent : TE.dialTrack)
                    }
                    .accessibilityHidden(true)

                VStack(alignment: .leading, spacing: 2) {
                    Text(help.title)
                        .font(TE.editorial(.title3))
                        .foregroundStyle(TE.textPrimary)
                    Text(isOn ? "CURRENTLY SHOWN" : "CURRENTLY HIDDEN")
                        .font(TE.mono(.caption2, weight: .semibold))
                        .tracking(1.2)
                        .foregroundStyle(isOn ? TE.accent : TE.textMuted)
                }
            }

            Text(help.message)
                .font(.footnote)
                .foregroundStyle(TE.textMuted)
                .fixedSize(horizontal: false, vertical: true)
        }
        .frame(width: 260, alignment: .leading)
        .padding(14)
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
            MapChromeButtonLabel(systemImage: "scope")
        }
        .accessibilityLabel("Fit map")
        .accessibilityHint("Shows options for zooming the map to available content.")
    }
}

/// A 44-point paper disc used for every control floating over the map.
struct MapChromeButtonLabel: View {
    let systemImage: String

    var body: some View {
        Image(systemName: systemImage)
            .font(.system(size: 18, weight: .regular))
            .foregroundStyle(TE.textPrimary)
            .frame(width: 44, height: 44)
            .background(Circle().fill(TE.card))
            .overlay(Circle().strokeBorder(TE.border, lineWidth: 1))
            .contentShape(Circle())
    }
}

struct MapChromeButton: View {
    let systemImage: String
    let label: LocalizedStringKey
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            MapChromeButtonLabel(systemImage: systemImage)
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
    }
}

// MARK: - Trace the day

/// The selected range laid out as a time axis: stays as inked segments, and the places to jump between.
@MainActor
struct TraceDay {
    struct Segment: Identifiable {
        let id: UUID
        let start: Double
        let end: Double
        let ink: Color
    }

    struct Place: Identifiable {
        var id: String { name }
        let name: String
        let ink: Color
        /// The first stay at this place within the range.
        let visit: Visit
    }

    let domain: ClosedRange<Date>
    let isSingleDay: Bool
    let visits: [Visit]
    let segments: [Segment]
    let places: [Place]
    let tickLabels: [String]
    private let inks: [String: Color]

    init(range: ClosedRange<Date>, visits: [Visit], points: [LocationPoint], now: Date) {
        let calendar = Calendar.current
        let sorted = visits.sorted { $0.arrivedAt < $1.arrivedAt }
        self.visits = sorted

        // Places take inks in order of first arrival, as on the Today dial.
        var inks: [String: Color] = [:]
        var places: [Place] = []
        for visit in sorted where inks[visit.displayName] == nil {
            let ink = TE.placeInks[inks.count % TE.placeInks.count]
            inks[visit.displayName] = ink
            places.append(Place(name: visit.displayName, ink: ink, visit: visit))
        }
        self.inks = inks
        self.places = places

        let isSingleDay = range.lowerBound != .distantPast
            && calendar.isDate(range.lowerBound, inSameDayAs: range.upperBound)
        self.isSingleDay = isSingleDay

        let domain: ClosedRange<Date>
        if isSingleDay {
            let start = calendar.startOfDay(for: range.lowerBound)
            let end = calendar.date(byAdding: .day, value: 1, to: start) ?? start.addingTimeInterval(86_400)
            domain = start...end
        } else {
            // Longer ranges span only the recorded stretch, never the empty past.
            let upper = min(range.upperBound, now)
            let earliest = [points.first?.timestamp, sorted.first?.arrivedAt].compactMap { $0 }.min()
            var lower = max(range.lowerBound, earliest ?? range.lowerBound)
            if lower == .distantPast || lower >= upper {
                lower = upper.addingTimeInterval(-86_400)
            }
            domain = lower...upper
        }
        self.domain = domain

        let span = domain.upperBound.timeIntervalSince(domain.lowerBound)
        segments = sorted.compactMap { visit in
            let start = max(visit.arrivedAt, domain.lowerBound)
            let end = min(visit.departedAt ?? now, now, domain.upperBound)
            guard end > start, span > 0 else { return nil }
            return Segment(
                id: visit.id,
                start: start.timeIntervalSince(domain.lowerBound) / span,
                end: end.timeIntervalSince(domain.lowerBound) / span,
                ink: inks[visit.displayName] ?? TE.accent
            )
        }

        if isSingleDay {
            tickLabels = ["00", "06", "12", "18", "24"]
        } else {
            let middle = domain.lowerBound.addingTimeInterval(span / 2)
            tickLabels = [domain.lowerBound, middle, domain.upperBound]
                .map { $0.formatted(.dateTime.month(.abbreviated).day()) }
        }
    }

    private var span: TimeInterval {
        domain.upperBound.timeIntervalSince(domain.lowerBound)
    }

    func ink(for visit: Visit) -> Color {
        inks[visit.displayName] ?? TE.accent
    }

    func fraction(of date: Date) -> Double {
        guard span > 0 else { return 1 }
        return min(max(date.timeIntervalSince(domain.lowerBound) / span, 0), 1)
    }

    func date(at fraction: Double) -> Date {
        domain.lowerBound.addingTimeInterval(span * min(max(fraction, 0), 1))
    }

    /// Where "now" falls on a single day's axis; the rest of the day is drawn as not yet lived.
    func nowFraction(_ now: Date) -> Double? {
        guard isSingleDay, domain.contains(now) else { return nil }
        return fraction(of: now)
    }

    func visit(at time: Date, now: Date) -> Visit? {
        visits.last { $0.arrivedAt <= time && ($0.departedAt ?? now) >= time }
    }
}

/// The paper sheet under the map: where you were at the scrubbed moment, the day's
/// stays as a bar, a time scrubber, and the places to jump between.
/// Everything the replay controls show and do at one moment of the trace.
@MainActor
struct TraceReplayPanelModel {
    let kicker: String
    let title: String
    let clock: String
    let day: TraceDay
    let nowFraction: Double?
    let focusFraction: Double
    let focusedPlaceID: String?
    let distanceText: String
    /// "Drag to replay · now 14:20".
    let hint: String?
    let isReplaying: Bool
    let isPlaying: Bool
    let canPlay: Bool
    let onScrub: (Double) -> Void
    let onStep: (TimeInterval) -> Void
    let onJump: (TraceDay.Place) -> Void
    let onPlayPause: () -> Void
    let onDone: () -> Void

    /// Changes whenever anything drawn changes, so a published copy is replaced in step.
    var signature: [String] {
        [
            kicker, title, clock, distanceText, hint ?? "",
            focusedPlaceID ?? "",
            String(format: "%.5f", focusFraction),
            nowFraction.map { String(format: "%.5f", $0) } ?? "-",
            "\(isReplaying)\(isPlaying)\(canPlay)",
            "\(day.domain.lowerBound.timeIntervalSinceReferenceDate)-\(day.domain.upperBound.timeIntervalSinceReferenceDate)",
            day.places.map(\.name).joined(separator: "|"),
            day.segments.map { "\($0.id)\($0.start)\($0.end)" }.joined(separator: "|")
        ]
    }
}

/// Carries the trace's replay controls from the map to another pane: on a half-open
/// Duo the map stays above the fold and the scrubber, places and steps sit below it.
@MainActor
@Observable
final class TraceReplayBridge {
    var panel: TraceReplayPanelModel?
    var isCollapsed = false

    init() {}
}

/// The pane below the fold on a half-open Duo: the trace's replay controls, full width.
struct TraceTabletopReplayPane: View {
    @Bindable var bridge: TraceReplayBridge

    var body: some View {
        Group {
            if let panel = bridge.panel {
                TraceScrubPanel(
                    model: panel,
                    isExpandedLayout: true,
                    maxHeight: .infinity,
                    isCollapsed: .constant(false),
                    showsGrabber: false
                )
                .frame(maxHeight: .infinity, alignment: .top)
            } else {
                ProgressView()
                    .frame(maxWidth: .infinity, maxHeight: .infinity)
            }
        }
        .background(TE.surface.ignoresSafeArea())
    }
}

struct TraceScrubPanel: View {
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    let kicker: String
    let title: String
    let clock: String
    let day: TraceDay
    let nowFraction: Double?
    let focusFraction: Double
    let focusedPlaceID: String?
    let distanceText: String
    let isReplaying: Bool
    let isPlaying: Bool
    let canPlay: Bool
    /// Wide and tabletop layouts get larger type, wrapped chips and step buttons.
    let isExpandedLayout: Bool
    let maxHeight: CGFloat
    @Binding var isCollapsed: Bool
    let onScrub: (Double) -> Void
    let onStep: (TimeInterval) -> Void
    let onJump: (TraceDay.Place) -> Void
    let onPlayPause: () -> Void
    let onDone: () -> Void
    /// "Drag to replay · now 14:20", shown in the footer.
    var hint: String? = nil
    /// A pane of its own (half-open) has nothing to collapse.
    var showsGrabber = true

    init(
        model: TraceReplayPanelModel,
        isExpandedLayout: Bool,
        maxHeight: CGFloat,
        isCollapsed: Binding<Bool>,
        showsGrabber: Bool = true
    ) {
        self.init(
            kicker: model.kicker,
            title: model.title,
            clock: model.clock,
            day: model.day,
            nowFraction: model.nowFraction,
            focusFraction: model.focusFraction,
            focusedPlaceID: model.focusedPlaceID,
            distanceText: model.distanceText,
            isReplaying: model.isReplaying,
            isPlaying: model.isPlaying,
            canPlay: model.canPlay,
            isExpandedLayout: isExpandedLayout,
            maxHeight: maxHeight,
            isCollapsed: isCollapsed,
            onScrub: model.onScrub,
            onStep: model.onStep,
            onJump: model.onJump,
            onPlayPause: model.onPlayPause,
            onDone: model.onDone,
            hint: model.hint,
            showsGrabber: showsGrabber
        )
    }

    init(
        kicker: String,
        title: String,
        clock: String,
        day: TraceDay,
        nowFraction: Double?,
        focusFraction: Double,
        focusedPlaceID: String?,
        distanceText: String,
        isReplaying: Bool,
        isPlaying: Bool,
        canPlay: Bool,
        isExpandedLayout: Bool,
        maxHeight: CGFloat,
        isCollapsed: Binding<Bool>,
        onScrub: @escaping (Double) -> Void,
        onStep: @escaping (TimeInterval) -> Void,
        onJump: @escaping (TraceDay.Place) -> Void,
        onPlayPause: @escaping () -> Void,
        onDone: @escaping () -> Void,
        hint: String? = nil,
        showsGrabber: Bool = true
    ) {
        self.kicker = kicker
        self.title = title
        self.clock = clock
        self.day = day
        self.nowFraction = nowFraction
        self.focusFraction = focusFraction
        self.focusedPlaceID = focusedPlaceID
        self.distanceText = distanceText
        self.isReplaying = isReplaying
        self.isPlaying = isPlaying
        self.canPlay = canPlay
        self.isExpandedLayout = isExpandedLayout
        self.maxHeight = maxHeight
        _isCollapsed = isCollapsed
        self.onScrub = onScrub
        self.onStep = onStep
        self.onJump = onJump
        self.onPlayPause = onPlayPause
        self.onDone = onDone
        self.hint = hint
        self.showsGrabber = showsGrabber
    }

    var body: some View {
        // Hug the content, scrolling only once it would pass the cap.
        CappedHeightLayout(maxHeight: maxHeight) {
            panel
        }
    }

    private var panel: some View {
        VStack(spacing: 0) {
            if showsGrabber {
                grabber
            } else {
                Color.clear.frame(height: 28)
            }

            VStack(alignment: .leading, spacing: isExpandedLayout ? 18 : 12) {
                header

                if !isCollapsed {
                    ViewThatFits(in: .vertical) {
                        details
                        ScrollView {
                            details
                        }
                        .scrollIndicators(.hidden)
                    }
                    .transition(.opacity)
                }
            }
            .frame(maxWidth: 720, alignment: .leading)
            .padding(.horizontal, isExpandedLayout ? 32 : 24)
            .padding(.bottom, isExpandedLayout ? 24 : 12)
        }
        .frame(maxWidth: .infinity)
        .foregroundStyle(TE.textPrimary)
        .background {
            if showsGrabber {
                UnevenRoundedRectangle(topLeadingRadius: 26, topTrailingRadius: 26, style: .continuous)
                    .fill(TE.surface)
                    .shadow(color: .black.opacity(0.12), radius: 30, x: 0, y: -10)
                    .ignoresSafeArea(edges: .bottom)
            } else {
                TE.surface.ignoresSafeArea()
            }
        }
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Replay controls")
    }

    private var grabber: some View {
        Button {
            withAnimation(reduceMotion ? nil : .spring(response: 0.35, dampingFraction: 0.85)) {
                isCollapsed.toggle()
            }
        } label: {
            Capsule()
                .fill(TE.border)
                .frame(width: 36, height: 4)
                .frame(maxWidth: .infinity, minHeight: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(isCollapsed ? "Show replay controls" : "Hide replay controls")
    }

    private var header: some View {
        let layout = dynamicTypeSize.isAccessibilitySize
            ? AnyLayout(VStackLayout(alignment: .leading, spacing: 8))
            : AnyLayout(HStackLayout(alignment: .lastTextBaseline, spacing: 12))
        return layout {
            VStack(alignment: .leading, spacing: 4) {
                Text(kicker)
                    .font(TE.mono(.caption2))
                    .tracking(1.4)
                    .foregroundStyle(TE.textMuted)
                    .fixedSize(horizontal: false, vertical: true)
                Text(title)
                    .font(TE.editorial(isExpandedLayout ? .largeTitle : .title))
                    .lineLimit(2)
                    .minimumScaleFactor(0.8)
            }
            .frame(maxWidth: .infinity, alignment: .leading)

            Text(clock)
                .font(WheredayFont.serif(size: isExpandedLayout ? 60 : 44, relativeTo: .largeTitle))
                .monospacedDigit()
                .lineLimit(1)
                .fixedSize()
        }
        .accessibilityElement(children: .combine)
    }

    private var details: some View {
        VStack(alignment: .leading, spacing: isExpandedLayout ? 18 : 12) {
            VStack(spacing: 0) {
                TraceDayBar(segments: day.segments, nowFraction: nowFraction)
                    .frame(height: isExpandedLayout ? 12 : 8)

                TraceScrubber(
                    fraction: focusFraction,
                    accessibilityValue: "\(clock). \(title)",
                    onScrub: onScrub,
                    onStep: onStep
                )

                HStack(spacing: 0) {
                    ForEach(Array(day.tickLabels.enumerated()), id: \.offset) { index, label in
                        if index > 0 { Spacer(minLength: 4) }
                        Text(label)
                    }
                }
                .font(TE.mono(.caption2))
                .foregroundStyle(TE.textMuted)
                .padding(.top, -6)
                .accessibilityHidden(true)
            }

            if !day.places.isEmpty {
                chips
            }

            if isExpandedLayout {
                stepButtons
            }

            footer
        }
    }

    @ViewBuilder
    private var chips: some View {
        if isExpandedLayout {
            TraceFlowLayout(spacing: 8) {
                chipViews
            }
        } else {
            ScrollView(.horizontal) {
                HStack(spacing: 8) {
                    chipViews
                }
            }
            .scrollIndicators(.hidden)
        }
    }

    private var chipViews: some View {
        ForEach(day.places) { place in
            TracePlaceChip(place: place, isSelected: place.id == focusedPlaceID) {
                onJump(place)
            }
        }
    }

    private var stepButtons: some View {
        HStack(spacing: 10) {
            Button { onStep(-15 * 60) } label: {
                Text("−15 min").stepLabel(filled: false)
            }
            .accessibilityLabel("Back 15 minutes")

            Button(action: onPlayPause) {
                Text(isPlaying ? "Pause" : "Play day").stepLabel(filled: true)
            }
            .disabled(!canPlay)

            Button { onStep(15 * 60) } label: {
                Text("+15 min").stepLabel(filled: false)
            }
            .accessibilityLabel("Forward 15 minutes")
        }
        .buttonStyle(.plain)
    }

    private var footer: some View {
        ViewThatFits(in: .horizontal) {
            HStack(spacing: 12) {
                Text(distanceText)
                Spacer(minLength: 8)
                hintText
                footerActions
            }
            VStack(alignment: .leading, spacing: 4) {
                HStack(spacing: 12) {
                    Text(distanceText)
                    Spacer(minLength: 8)
                    hintText
                }
                .frame(minHeight: 32)
                HStack(spacing: 16) { footerActions }
            }
            VStack(alignment: .leading, spacing: 4) {
                Text(distanceText)
                hintText
                HStack(spacing: 16) { footerActions }
            }
        }
        .font(TE.mono(.caption))
        .foregroundStyle(TE.textMuted)
        .padding(.top, 4)
        .overlay(alignment: .top) {
            Rectangle().fill(TE.border).frame(height: 1)
        }
    }

    @ViewBuilder
    private var hintText: some View {
        if let hint {
            Text(hint)
                .lineLimit(1)
                .accessibilityHidden(true)
        }
    }

    @ViewBuilder
    private var footerActions: some View {
        if !isExpandedLayout {
            Button(action: onPlayPause) {
                Label(isPlaying ? "Pause" : "Play day", systemImage: isPlaying ? "pause.fill" : "play.fill")
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(canPlay ? TE.inkRust : TE.textMuted)
            .disabled(!canPlay)
            .accessibilityHint("Plays the recorded route from the playhead.")
        }

        if isReplaying {
            Button(action: onDone) {
                Text("Show all")
                    .frame(minHeight: 44)
                    .contentShape(Rectangle())
            }
            .buttonStyle(.plain)
            .foregroundStyle(TE.textPrimary)
            .accessibilityHint("Ends the replay and draws the whole route.")
        }
    }
}

private extension Text {
    func stepLabel(filled: Bool) -> some View {
        self
            .font(.subheadline.weight(filled ? .semibold : .medium))
            .foregroundStyle(filled ? TE.onInkCard : TE.textPrimary)
            .lineLimit(1)
            .minimumScaleFactor(0.8)
            .frame(maxWidth: .infinity, minHeight: 56)
            .background(Capsule().fill(filled ? TE.accent : TE.card))
            .overlay(Capsule().strokeBorder(filled ? Color.clear : TE.border, lineWidth: 1))
            .contentShape(Capsule())
    }
}

/// The day's stays as one bar; the part of today not yet lived is hatched.
/// Offers its content at most `maxHeight` and takes only the height the content uses,
/// unlike `.frame(maxHeight:)`, which grows to the cap.
private struct CappedHeightLayout: Layout {
    let maxHeight: CGFloat

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        guard let content = subviews.first else { return .zero }
        return content.sizeThatFits(cappedProposal(proposal))
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        subviews.first?.place(at: bounds.origin, proposal: ProposedViewSize(width: bounds.width, height: bounds.height))
    }

    private func cappedProposal(_ proposal: ProposedViewSize) -> ProposedViewSize {
        ProposedViewSize(width: proposal.width, height: min(proposal.height ?? maxHeight, maxHeight))
    }
}

struct TraceDayBar: View {
    let segments: [TraceDay.Segment]
    let nowFraction: Double?

    var body: some View {
        Canvas { context, size in
            let bar = Path(roundedRect: CGRect(origin: .zero, size: size), cornerRadius: size.height / 2)
            context.clip(to: bar)
            context.fill(bar, with: .color(TE.dialTrack))

            for segment in segments {
                let rect = CGRect(
                    x: size.width * segment.start,
                    y: 0,
                    width: max(1, size.width * (segment.end - segment.start)),
                    height: size.height
                )
                context.fill(Path(rect), with: .color(segment.ink))
            }

            if let nowFraction {
                var stripe = size.width * nowFraction + 4
                while stripe < size.width {
                    context.fill(Path(CGRect(x: stripe, y: 0, width: 4, height: size.height)), with: .color(TE.surface))
                    stripe += 8
                }
            }
        }
        .accessibilityHidden(true)
    }
}

/// A rust-ringed thumb on a hairline; VoiceOver adjusts it in 15-minute steps.
struct TraceScrubber: View {
    let fraction: Double
    let accessibilityValue: String
    let onScrub: (Double) -> Void
    let onStep: (TimeInterval) -> Void

    private let thumbSize: CGFloat = 26

    var body: some View {
        GeometryReader { geometry in
            let width = geometry.size.width
            ZStack(alignment: .leading) {
                Capsule()
                    .fill(TE.border)
                    .frame(height: 4)

                Circle()
                    .fill(TE.surface)
                    .overlay(Circle().strokeBorder(TE.inkRust, lineWidth: 3))
                    .frame(width: thumbSize, height: thumbSize)
                    .position(x: width * fraction, y: geometry.size.height / 2)
            }
            .frame(maxHeight: .infinity)
            .contentShape(Rectangle())
            .gesture(
                DragGesture(minimumDistance: 0)
                    .onChanged { value in
                        guard width > 0 else { return }
                        onScrub(Double(value.location.x / width))
                    }
            )
        }
        .frame(height: 44)
        .accessibilityElement()
        .accessibilityLabel("Time of day")
        .accessibilityValue(accessibilityValue)
        .accessibilityAdjustableAction { direction in
            switch direction {
            case .increment: onStep(15 * 60)
            case .decrement: onStep(-15 * 60)
            @unknown default: break
            }
        }
    }
}

struct TracePlaceChip: View {
    let place: TraceDay.Place
    let isSelected: Bool
    let action: () -> Void

    var body: some View {
        Button(action: action) {
            HStack(spacing: 8) {
                Circle()
                    .fill(place.ink)
                    .frame(width: 8, height: 8)
                Text(place.name)
                    .lineLimit(1)
            }
            .font(.footnote.weight(.medium))
            .foregroundStyle(TE.textPrimary)
            .padding(.horizontal, 14)
            .frame(minHeight: 44)
            .background(Capsule().fill(TE.card))
            .overlay(Capsule().strokeBorder(isSelected ? TE.inkRust : TE.border, lineWidth: isSelected ? 1.5 : 1))
            .contentShape(Capsule())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(place.name)
        .accessibilityHint("Moves the replay to this place.")
        .accessibilityAddTraits(isSelected ? .isSelected : [])
    }
}

/// Wraps chips onto new lines when there is room for the whole set.
struct TraceFlowLayout: Layout {
    var spacing: CGFloat = 8

    func sizeThatFits(proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) -> CGSize {
        let maxWidth = proposal.width ?? .infinity
        var x: CGFloat = 0
        var y: CGFloat = 0
        var rowHeight: CGFloat = 0
        var width: CGFloat = 0
        for subview in subviews {
            var size = subview.sizeThatFits(.unspecified)
            size.width = min(size.width, maxWidth)
            if x > 0, x + size.width > maxWidth {
                x = 0
                y += rowHeight + spacing
                rowHeight = 0
            }
            width = max(width, x + size.width)
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
        return CGSize(width: width, height: y + rowHeight)
    }

    func placeSubviews(in bounds: CGRect, proposal: ProposedViewSize, subviews: Subviews, cache: inout ()) {
        var x = bounds.minX
        var y = bounds.minY
        var rowHeight: CGFloat = 0
        for subview in subviews {
            var size = subview.sizeThatFits(.unspecified)
            size.width = min(size.width, bounds.width)
            if x > bounds.minX, x + size.width > bounds.maxX {
                x = bounds.minX
                y += rowHeight + spacing
                rowHeight = 0
            }
            subview.place(at: CGPoint(x: x, y: y), proposal: ProposedViewSize(size))
            x += size.width + spacing
            rowHeight = max(rowHeight, size.height)
        }
    }
}

/// The selected stay, inspected in place on wide layouts instead of in a sheet.
struct MapVisitInspectorCard: View {
    let visit: Visit
    let monthVisitOrdinal: Int
    let photoCount: Int
    let onConfirm: () -> Void
    let onDetails: () -> Void
    let onPhotos: () -> Void
    let onClose: () -> Void
    /// Reviews the stay as a Calendar event (CalendarEventReviewSheet); nil hides the action.
    var onCalendar: (() -> Void)? = nil

    private var isUnconfirmed: Bool { visit.confirmationStatus == .unconfirmed }

    var body: some View {
        VStack(alignment: .leading, spacing: 12) {
            HStack(alignment: .top, spacing: 10) {
                VStack(alignment: .leading, spacing: 2) {
                    Text(timeCaption)
                        .font(TE.mono(.caption2))
                        .tracking(1.2)
                        .foregroundStyle(TE.textMuted)
                    Text(visit.displayName)
                        .font(TE.editorial(.title))
                        .lineLimit(2)
                    if !subtitle.isEmpty {
                        Text(subtitle)
                            .font(.footnote)
                            .foregroundStyle(TE.textMuted)
                    }
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .accessibilityElement(children: .combine)

                Button(action: onClose) {
                    Image(systemName: "xmark")
                        .font(.footnote.weight(.semibold))
                        .frame(width: 44, height: 44)
                        .overlay(Circle().strokeBorder(TE.border, lineWidth: 1))
                        .contentShape(Circle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel("Close inspector")
            }

            ViewThatFits(in: .horizontal) {
                HStack(spacing: 8) { actions }
                VStack(spacing: 8) { actions }
            }
            .buttonStyle(.plain)
        }
        .padding(.horizontal, 18)
        .padding(.vertical, 16)
        .foregroundStyle(TE.textPrimary)
        .background(RoundedRectangle(cornerRadius: 20, style: .continuous).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 20, style: .continuous).strokeBorder(TE.border, lineWidth: 1))
        .shadow(color: .black.opacity(0.12), radius: 30, x: 0, y: 10)
        .accessibilityElement(children: .contain)
        .accessibilityLabel("Selected visit")
    }

    @ViewBuilder
    private var actions: some View {
        if isUnconfirmed {
            Button(action: onConfirm) { actionLabel("Confirm", filled: true) }
            Button(action: onDetails) { actionLabel("Details", filled: false) }
        } else {
            Button(action: onDetails) { actionLabel("Details", filled: true) }
        }

        if let onCalendar {
            Button(action: onCalendar) { actionLabel("Calendar", filled: false) }
                .accessibilityLabel("Add Event to Calendar")
        }

        if photoCount > 0 {
            Button(action: onPhotos) {
                actionLabel(photoCount == 1 ? "1 photo" : "\(photoCount) photos", filled: false)
            }
        }
    }

    private func actionLabel(_ title: LocalizedStringKey, filled: Bool) -> some View {
        Text(title)
            .font(.footnote.weight(filled ? .semibold : .medium))
            .foregroundStyle(filled ? TE.onInkCard : TE.textPrimary)
            .lineLimit(1)
            .padding(.horizontal, 12)
            .frame(maxWidth: .infinity, minHeight: 44)
            .background(Capsule().fill(filled ? TE.accent : Color.clear))
            .overlay(Capsule().strokeBorder(filled ? Color.clear : TE.border, lineWidth: 1))
            .contentShape(Capsule())
    }

    private var timeCaption: String {
        let start = LocationMapView.clockText(visit.arrivedAt)
        let end = visit.departedAt.map(LocationMapView.clockText) ?? String(localized: "now")
        return "\(start) – \(end) · \(visit.formattedDuration)".uppercased()
    }

    private var subtitle: String {
        var parts: [String] = []
        if let address = visit.address, !address.isEmpty, address != visit.displayName {
            parts.append(address)
        }
        if monthVisitOrdinal > 0,
           let ordinal = NumberFormatter.localizedString(from: NSNumber(value: monthVisitOrdinal), number: .ordinal) as String? {
            parts.append(String(localized: "\(ordinal) visit this month"))
        }
        return parts.joined(separator: " · ")
    }
}

// MARK: - Show days sheet

/// Live bindings for every map layer the "Show days" sheet can switch.
struct MapLayerSettings {
    var visits: Binding<Bool>
    var routes: Binding<Bool>
    var photos: Binding<Bool>
    var photoImages: Binding<Bool>
    var photoDots: Binding<Bool>
    var roadMatchedPath: Binding<Bool>
    var straightLineGaps: Binding<Bool>
    var pointMarkers: Binding<Bool>
    var startEndMarkers: Binding<Bool>
    var sessionPath: Binding<Bool>
    var hasSessionPoints: Bool
    /// Completed stays shorter than this many minutes are left off the map; 0 shows every stop.
    var minimumStayMinutes: Binding<Int>? = nil

    static let minimumStayMinutesKey = "mapMinimumStayMinutes"
    static let minimumStayOptions = [0, 5, 10, 15, 30]
}

/// The shortest stop the Visits layer draws, chosen from a few lengths.
private struct MapMinimumStayRow: View {
    @Binding var minutes: Int

    var body: some View {
        VStack(spacing: 0) {
            HStack(spacing: 12) {
                Text("Shortest stop")
                    .font(.subheadline)
                    .foregroundStyle(TE.textPrimary)
                Spacer(minLength: 8)
                Picker("Shortest stop", selection: $minutes) {
                    ForEach(MapLayerSettings.minimumStayOptions, id: \.self) { option in
                        if option == 0 {
                            Text("Any length").tag(option)
                        } else {
                            Text("\(option) min").tag(option)
                        }
                    }
                }
                .labelsHidden()
                .tint(TE.inkRust)
            }
            .padding(.leading, 28)
            .frame(minHeight: 48)

            Rectangle()
                .fill(TE.dialTrack)
                .frame(height: 1)
        }
    }
}

private struct MapLayerRow: View {
    let title: LocalizedStringKey
    let subtitle: LocalizedStringKey
    let systemImage: String
    let help: LayerToggleHelp
    @Binding var isOn: Bool
    @Binding var activeHelp: LayerToggleHelp?
    var showsDivider = true

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
        VStack(spacing: 0) {
            HStack(spacing: 4) {
                Toggle(isOn: $isOn) {
                    VStack(alignment: .leading, spacing: 1) {
                        Text(title)
                            .font(.subheadline)
                            .foregroundStyle(TE.textPrimary)
                        Text(subtitle)
                            .font(TE.mono(.caption2))
                            .foregroundStyle(TE.textMuted)
                    }
                }
                .toggleStyle(TEToggleStyle())

                Button {
                    activeHelp = help
                } label: {
                    Image(systemName: "info.circle")
                        .font(.body)
                        .foregroundStyle(TE.textMuted)
                        .frame(width: 44, height: 44)
                        .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityLabel(String(localized: "About \(String(localized: help.title))"))
                .popover(isPresented: isShowingHelp) {
                    LayerToggleHelpPopover(systemImage: systemImage, help: help, isOn: isOn)
                        .presentationCompactAdaptation(.popover)
                }
            }
            .padding(.vertical, 4)
            .frame(minHeight: 52)

            if showsDivider {
                Rectangle()
                    .fill(TE.dialTrack)
                    .frame(height: 1)
            }
        }
    }
}

/// A month of days to pick a single day or a range from; dots mark days with recorded visits.
private struct MapRangeCalendar: View {
    @Binding var displayedMonth: Date
    let lowerDay: Date
    let upperDay: Date
    let isAllTime: Bool
    let recordedDays: Set<Date>
    let rangeText: String
    let onSelect: (Date) -> Void

    private var calendar: Calendar { Calendar.current }

    private var columns: [GridItem] {
        Array(repeating: GridItem(.flexible(), spacing: 0), count: 7)
    }

    private var weekdaySymbols: [String] {
        let symbols = calendar.veryShortStandaloneWeekdaySymbols
        let first = calendar.firstWeekday - 1
        return Array(symbols[first...] + symbols[..<first])
    }

    /// Leading blanks for the month's first weekday, then each day of the month.
    private var cells: [Date?] {
        guard let interval = calendar.dateInterval(of: .month, for: displayedMonth),
              let dayCount = calendar.range(of: .day, in: .month, for: displayedMonth)?.count else { return [] }
        let leading = (calendar.component(.weekday, from: interval.start) - calendar.firstWeekday + 7) % 7
        let days: [Date?] = (0..<dayCount).map { calendar.date(byAdding: .day, value: $0, to: interval.start) }
        return Array(repeating: nil, count: leading) + days
    }

    private var canShowNextMonth: Bool {
        guard let next = calendar.date(byAdding: .month, value: 1, to: displayedMonth) else { return false }
        return next <= Date()
    }

    var body: some View {
        VStack(alignment: .leading, spacing: 10) {
            HStack(spacing: 4) {
                Text(displayedMonth.formatted(.dateTime.month(.wide).year()))
                    .font(TE.editorial(.title2))
                    .accessibilityAddTraits(.isHeader)
                Spacer(minLength: 8)
                Text(rangeText)
                    .font(TE.mono(.caption))
                    .foregroundStyle(TE.inkRust)
                monthButton(systemImage: "chevron.left", label: "Previous month", value: -1)
                monthButton(systemImage: "chevron.right", label: "Next month", value: 1)
                    .disabled(!canShowNextMonth)
            }
            .padding(.leading, 6)

            LazyVGrid(columns: columns, spacing: 4) {
                ForEach(Array(weekdaySymbols.enumerated()), id: \.offset) { _, symbol in
                    Text(symbol)
                        .font(TE.mono(.caption2))
                        .foregroundStyle(TE.textMuted)
                        .frame(maxWidth: .infinity)
                        .accessibilityHidden(true)
                }

                ForEach(Array(cells.enumerated()), id: \.offset) { _, day in
                    if let day {
                        dayCell(day)
                    } else {
                        Color.clear.frame(height: 40)
                    }
                }
            }
        }
        .padding(.horizontal, 12)
        .padding(.vertical, 14)
        .background(RoundedRectangle(cornerRadius: 18, style: .continuous).fill(TE.card))
        .overlay(RoundedRectangle(cornerRadius: 18, style: .continuous).strokeBorder(TE.border, lineWidth: 1))
    }

    private func monthButton(systemImage: String, label: LocalizedStringKey, value: Int) -> some View {
        Button {
            if let month = calendar.date(byAdding: .month, value: value, to: displayedMonth) {
                displayedMonth = month
            }
        } label: {
            Image(systemName: systemImage)
                .font(.footnote.weight(.semibold))
                .frame(width: 36, height: 44)
                .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(Text(label))
    }

    private func dayCell(_ day: Date) -> some View {
        let isEndpoint = !isAllTime && (day == lowerDay || day == upperDay)
        let isInRange = !isAllTime && day >= lowerDay && day <= upperDay
        let isFuture = day > Date()
        let isRecorded = recordedDays.contains(day)

        return Button {
            onSelect(day)
        } label: {
            VStack(spacing: 2) {
                Text(day.formatted(.dateTime.day()))
                    .font(.subheadline)
                    .minimumScaleFactor(0.6)
                    .lineLimit(1)
                Circle()
                    .fill(isRecorded ? (isEndpoint ? TE.liveDot : TE.placeInks[1]) : Color.clear)
                    .frame(width: 4, height: 4)
            }
            .foregroundStyle(isEndpoint ? TE.onInkCard : TE.textPrimary)
            .opacity(isFuture ? 0.35 : 1)
            .frame(maxWidth: .infinity, minHeight: 40)
            .background {
                if isEndpoint {
                    RoundedRectangle(cornerRadius: 12, style: .continuous).fill(TE.accent)
                } else if isInRange {
                    Rectangle().fill(TE.dialTrack)
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .disabled(isFuture)
        .accessibilityLabel(day.formatted(date: .complete, time: .omitted))
        .accessibilityValue(isRecorded ? String(localized: "Has visits") : "")
        .accessibilityAddTraits(isInRange ? .isSelected : [])
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

    /// The last point recorded at or before `date` in time-ordered points; the first point when `date` precedes them all.
    static func index(atOrBefore date: Date, in points: [LocationPoint]) -> Int? {
        guard !points.isEmpty else { return nil }
        var low = 0
        var high = points.count - 1
        guard points[low].timestamp <= date else { return 0 }
        while low < high {
            let mid = (low + high + 1) / 2
            if points[mid].timestamp <= date {
                low = mid
            } else {
                high = mid - 1
            }
        }
        return low
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

#if compiler(>=6.4)
/// Reports the hinge as soon as the view joins a window: `UIHingeInteraction` calls its handler with
/// the initial state, so a foldable is recognised at launch even while it is closed.
@available(iOS 27.1, *)
struct HingePresenceProbe: UIViewRepresentable {
    /// Called with the current hinge status, or nil when the hierarchy reports no hinge.
    let onUpdate: (UIHinge.Status?) -> Void

    func makeUIView(context: Context) -> UIView {
        let view = UIView(frame: .zero)
        view.isUserInteractionEnabled = false
        view.isAccessibilityElement = false
        let coordinator = context.coordinator
        view.addInteraction(UIHingeInteraction { _, update in
            coordinator.onUpdate(update.hinge?.status)
        })
        return view
    }

    func updateUIView(_ uiView: UIView, context: Context) {
        context.coordinator.onUpdate = onUpdate
    }

    func makeCoordinator() -> Coordinator { Coordinator(onUpdate: onUpdate) }

    @MainActor
    final class Coordinator {
        var onUpdate: (UIHinge.Status?) -> Void
        init(onUpdate: @escaping (UIHinge.Status?) -> Void) { self.onUpdate = onUpdate }
    }
}
#endif
