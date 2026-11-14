import Foundation
import SwiftData
import SwiftUI
import Combine
import CoreLocation

struct MapSessionFocusRequest: Equatable {
    let id = UUID()
    let sessionID: String
    let title: String
    let range: ClosedRange<Date>
}

struct TimelineDaySnapshot {
    var sessions: [RecordingSessionSummary] = []
    var visits: [Visit] = []
}

@MainActor
@Observable
final class LocationViewModel {
    var locationManager: LocationManager
    private var modelContext: ModelContext
    private let timelineImportService: TimelineImportService

    // Cached data
    var todayVisits: [Visit] = []
    var allVisits: [Visit] = []
    var allRecordingSessions: [RecordingSession] = []
    var savedPlaces: [SavedPlace] = []
    var photoLibraryAccessState: PhotoLibraryAccessState = PhotoLibraryManager.shared.authorizationState
    /// Full point history. Loaded lazily for export so the map does not hydrate
    /// tens of thousands of SwiftData models on launch.
    var locationPoints: [LocationPoint] = []
    var todayLocationPoints: [LocationPoint] = []
    /// Downsampled points used by the map for the currently selected date range.
    var mapLocationPoints: [LocationPoint] = []
    /// Complete cached range metadata, including temporarily inaccessible assets.
    var mapPhotoMoments: [PhotoMoment] = [] {
        didSet { rebuildPhotoPresentation() }
    }
    private(set) var mapAccessiblePhotoMoments: [PhotoMoment] = []
    private(set) var mapPhotoPlaces: [PhotoMomentCluster] = []
    private(set) var mapPhotoMomentClusters: [PhotoMomentCluster] = []
    private(set) var photoPresentationRevision = 0
    let photoDetails = PhotoMomentDetailSelection()
    private var photoPresentationSnapshot: [PhotoPresentationMember] = []
    private var presentedPhotoAccess: PhotoLibraryAccessState?
    /// Raw count for the current map date range, before downsampling.
    var mapLocationPointCount: Int = 0
    var mapPhotoMomentCount: Int = 0
    var totalLocationPointCount: Int = 0

    // UI State
    var mapDateRange: ClosedRange<Date> = Calendar.current.startOfDay(for: Date())...Date() {
        didSet {
            if oldValue != mapDateRange { photoDetails.dismissAll() }
        }
    }
    var mapFocusRequest: MapSessionFocusRequest?
    var showingExportSheet = false
    var showingClearConfirmation = false
    var exportError: String?
    private(set) var visitMutationError: VisitMutationError?

    private var hasLoadedAllLocationPoints = false
    private var sessionLocationPointsCache: [LocationPoint] = []
    private var todayDistanceTraveledCache: Double = 0
    private var sessionDistanceTraveledCache: Double = 0
    private var cancellables = Set<AnyCancellable>()
    private var isAutomaticPhotoSyncInProgress = false
    private var lastAutomaticPhotoSyncAt: Date?
    private var activePhotoSyncRange: ClosedRange<Date>?
    private var pendingPhotoSyncRange: ClosedRange<Date>?

    static let automaticPhotoSyncEnabledKey = "automaticPhotoSyncEnabled"
    static let showPhotoMarkersKey = "showPhotoMarkers"
    static let showVisitSuggestionsKey = "showVisitSuggestions"
    static let defaultSavedPlaceRadiusMeters = 150.0

    private let maximumMapPointCount = 2_500
    private let maximumRawMapPointFetchCount = 10_000
    private let mapFetchBatchSize = 500
    private let automaticPhotoSyncCooldown: TimeInterval = 10 * 60
    private let photoRouteInferenceWindow: TimeInterval = 15 * 60
    private let photoVisitInferenceGracePeriod: TimeInterval = 15 * 60

    static var isAutomaticPhotoSyncEnabled: Bool {
        UserDefaults.standard.bool(forKey: automaticPhotoSyncEnabledKey)
    }

    private let photoAuthorizationProvider: @MainActor () -> PhotoLibraryAccessState
    private let accessiblePhotoIdentifiersProvider: @MainActor ([String]) -> Set<String>

    init(
        modelContext: ModelContext,
        locationManager: LocationManager,
        photoAuthorizationProvider: @escaping @MainActor () -> PhotoLibraryAccessState = {
            PhotoLibraryManager.shared.authorizationState
        },
        accessiblePhotoIdentifiersProvider: @escaping @MainActor ([String]) -> Set<String> = {
            PhotoLibraryManager.shared.accessibleAssetIdentifiers(in: $0)
        }
    ) {
        self.modelContext = modelContext
        self.timelineImportService = TimelineImportService(container: modelContext.container)
        self.locationManager = locationManager
        self.photoAuthorizationProvider = photoAuthorizationProvider
        self.accessiblePhotoIdentifiersProvider = accessiblePhotoIdentifiersProvider
        locationManager.setModelContext(modelContext)

        loadData()

        // Observe location manager for new data points. Append the saved point to
        // in-memory caches instead of refetching the entire day on every update.
        locationManager.$lastSavedLocationPoints
            .dropFirst() // Skip initial value
            .receive(on: DispatchQueue.main)
            .sink { [weak self] points in
                self?.handleSavedLocationPoints(points)
            }
            .store(in: &cancellables)

        NotificationCenter.default.publisher(for: .photoLibraryDidChange)
            .receive(on: DispatchQueue.main)
            .sink { [weak self] _ in
                guard let self else { return }
                // Limited selection can change without changing authorization.
                // Refresh accessibility even when automatic metadata sync is off.
                self.loadMapPhotoMoments(in: self.mapDateRange)
                Task { @MainActor in
                    await self.syncPhotosAutomaticallyIfAuthorized(ignoresCooldown: true)
                }
            }
            .store(in: &cancellables)
    }

    // MARK: - Data Loading

    func loadData() {
        loadTodayVisits()
        loadAllVisits()
        loadRecordingSessions()
        loadSavedPlaces()
        refreshLocationPointCount()
        refreshPhotoLibraryAuthorizationState()
        // Today's path is stored whether or not tracking is on now, so the day's distance
        // survives a relaunch after tracking was stopped.
        loadTodayLocationPoints()
        loadMapLocationPoints(in: mapDateRange)
        loadMapPhotoMoments(in: mapDateRange)
    }

    func loadTodayVisits() {
        let calendar = Calendar.current
        let startOfDay = calendar.startOfDay(for: Date())
        let endOfDay = calendar.date(byAdding: .day, value: 1, to: startOfDay)!

        let predicate = #Predicate<Visit> { visit in
            visit.arrivedAt >= startOfDay && visit.arrivedAt < endOfDay
        }

        var descriptor = FetchDescriptor<Visit>(predicate: predicate)
        descriptor.sortBy = [SortDescriptor(\.arrivedAt, order: .forward)]

        do {
            todayVisits = try modelContext.fetch(descriptor)
        } catch {
            print("Failed to fetch today's visits: \(error)")
            todayVisits = []
        }
    }

    func loadAllVisits() {
        var descriptor = FetchDescriptor<Visit>()
        descriptor.sortBy = [SortDescriptor(\.arrivedAt, order: .reverse)]

        do {
            allVisits = try modelContext.fetch(descriptor)
        } catch {
            print("Failed to fetch all visits: \(error)")
            allVisits = []
        }
    }

    func loadRecordingSessions() {
        var descriptor = FetchDescriptor<RecordingSession>()
        descriptor.sortBy = [SortDescriptor(\.startedAt, order: .reverse)]

        do {
            allRecordingSessions = try modelContext.fetch(descriptor)
        } catch {
            print("Failed to fetch recording sessions: \(error)")
            allRecordingSessions = []
        }
    }

    func loadSavedPlaces() {
        var descriptor = FetchDescriptor<SavedPlace>()
        descriptor.sortBy = [SortDescriptor(\.name, order: .forward)]

        do {
            savedPlaces = try modelContext.fetch(descriptor)
        } catch {
            print("Failed to fetch saved places: \(error)")
            savedPlaces = []
        }
    }

    func refreshLocationPointCount() {
        do {
            totalLocationPointCount = try modelContext.fetchCount(FetchDescriptor<LocationPoint>())
        } catch {
            print("Failed to count location points: \(error)")
            totalLocationPointCount = hasLoadedAllLocationPoints ? locationPoints.count : mapLocationPointCount
        }
    }

    func ensureAllLocationPointsLoaded() {
        guard !hasLoadedAllLocationPoints else { return }
        loadLocationPoints()
    }

    func loadLocationPoints() {
        var descriptor = FetchDescriptor<LocationPoint>()
        descriptor.sortBy = [SortDescriptor(\.timestamp, order: .forward)]

        do {
            locationPoints = try modelContext.fetch(descriptor)
            hasLoadedAllLocationPoints = true
            totalLocationPointCount = locationPoints.count
        } catch {
            print("Failed to fetch location points: \(error)")
            locationPoints = []
            hasLoadedAllLocationPoints = false
        }
    }

    func loadMapLocationPoints(in range: ClosedRange<Date>? = nil) {
        let range = range ?? mapDateRange
        let start = range.lowerBound
        let end = range.upperBound

        let predicate = #Predicate<LocationPoint> { point in
            point.timestamp >= start && point.timestamp <= end
        }

        do {
            let countDescriptor = FetchDescriptor<LocationPoint>(predicate: predicate)
            let rawCount = try modelContext.fetchCount(countDescriptor)

            let points: [LocationPoint]
            if rawCount > maximumRawMapPointFetchCount {
                points = try fetchSampledMapPoints(predicate: predicate, rawCount: rawCount)
            } else {
                var descriptor = FetchDescriptor<LocationPoint>(predicate: predicate)
                descriptor.sortBy = [SortDescriptor(\.timestamp, order: .forward)]
                points = try modelContext.fetch(descriptor)
            }
            mapLocationPointCount = rawCount
            mapLocationPoints = downsample(points: points, maxCount: maximumMapPointCount)
        } catch {
            print("Failed to fetch map location points: \(error)")
            mapLocationPointCount = 0
            mapLocationPoints = []
        }
    }

    func refreshPhotoLibraryAuthorizationState() {
        let previous = photoLibraryAccessState
        photoLibraryAccessState = photoAuthorizationProvider()
        if photoLibraryAccessState.canRead {
            PhotoLibraryManager.shared.startObservingChangesIfNeeded()
            if previous != photoLibraryAccessState { rebuildPhotoPresentation() }
        } else {
            mapPhotoMoments = []
        }
    }

    private func accessiblePhotos(_ photos: [PhotoMoment]) -> [PhotoMoment] {
        guard photoLibraryAccessState.canRead else { return [] }
        let identifiers = accessiblePhotoIdentifiersProvider(photos.map(\.assetLocalIdentifier))
        return PhotoMomentClusterBuilder.sorted(photos.filter { identifiers.contains($0.assetLocalIdentifier) })
    }

    private func rebuildPhotoPresentation() {
        let photos = accessiblePhotos(mapPhotoMoments)
        let snapshot = photos.map(PhotoPresentationMember.init)
        guard snapshot != photoPresentationSnapshot || presentedPhotoAccess != photoLibraryAccessState else { return }
        photoPresentationSnapshot = snapshot
        presentedPhotoAccess = photoLibraryAccessState
        // This synchronously clears directory, place and nested browser state.
        photoDetails.dismissAll()
        photoPresentationRevision += 1
        mapAccessiblePhotoMoments = photos
        mapPhotoMomentCount = photos.count
        mapPhotoPlaces = PhotoMomentClusterBuilder.placeClusters(for: photos)
        mapPhotoMomentClusters = PhotoMomentClusterBuilder.annotations(for: mapPhotoPlaces)
    }

    /// Value snapshots detect edits even when SwiftData returns the same objects.
    private struct PhotoPresentationMember: Equatable {
        let id: UUID
        let assetIdentifier: String
        let takenAt: Date
        let latitude: Double
        let longitude: Double
        let source: String

        init(_ photo: PhotoMoment) {
            id = photo.id
            assetIdentifier = photo.assetLocalIdentifier
            takenAt = photo.takenAt
            latitude = photo.latitude
            longitude = photo.longitude
            source = photo.coordinateSourceRawValue
        }
    }

    func loadMapPhotoMoments(in range: ClosedRange<Date>? = nil) {
        refreshPhotoLibraryAuthorizationState()
        guard photoLibraryAccessState.canRead else { return }

        mapPhotoMoments = fetchPhotoMoments(in: range ?? mapDateRange)
    }

    func requestPhotoLibraryAccessAndSync(in range: ClosedRange<Date>? = nil) async {
        photoLibraryAccessState = await PhotoLibraryManager.shared.requestAuthorization()
        guard photoLibraryAccessState.canRead else {
            mapPhotoMomentCount = 0
            mapPhotoMoments = []
            return
        }

        await syncPhotoMoments(in: range ?? mapDateRange)
    }

    func syncPhotoMomentsIfAuthorized(in range: ClosedRange<Date>? = nil) async {
        refreshPhotoLibraryAuthorizationState()
        guard photoLibraryAccessState.canRead else { return }
        await syncPhotoMoments(in: range ?? mapDateRange)
    }

    func setAutomaticPhotoSyncEnabled(_ isEnabled: Bool) {
        UserDefaults.standard.set(isEnabled, forKey: Self.automaticPhotoSyncEnabledKey)
        UserDefaults.standard.set(isEnabled, forKey: Self.showPhotoMarkersKey)

        if !isEnabled {
            mapPhotoMomentCount = 0
            mapPhotoMoments = []
        }
    }

    func requestPhotoLibraryAccessAndStartAutomaticSync() async {
        setAutomaticPhotoSyncEnabled(true)
        await performAutomaticPhotoSync(requestAuthorizationIfNeeded: true, ignoresCooldown: true)

        if !photoLibraryAccessState.canRead {
            setAutomaticPhotoSyncEnabled(false)
        }
    }

    func syncPhotosAutomaticallyIfAuthorized(ignoresCooldown: Bool = false) async {
        guard Self.isAutomaticPhotoSyncEnabled else { return }
        await performAutomaticPhotoSync(requestAuthorizationIfNeeded: false, ignoresCooldown: ignoresCooldown)
    }

    private func performAutomaticPhotoSync(
        requestAuthorizationIfNeeded: Bool,
        ignoresCooldown: Bool
    ) async {
        guard !isAutomaticPhotoSyncInProgress else { return }

        let now = Date()
        if !ignoresCooldown,
           let lastAutomaticPhotoSyncAt,
           now.timeIntervalSince(lastAutomaticPhotoSyncAt) < automaticPhotoSyncCooldown {
            loadMapPhotoMoments(in: mapDateRange)
            return
        }

        isAutomaticPhotoSyncInProgress = true
        var didSync = false
        defer {
            isAutomaticPhotoSyncInProgress = false
            if didSync {
                lastAutomaticPhotoSyncAt = Date()
            }
        }

        photoLibraryAccessState = PhotoLibraryManager.shared.authorizationState
        if photoLibraryAccessState == .notDetermined {
            guard requestAuthorizationIfNeeded else { return }
            photoLibraryAccessState = await PhotoLibraryManager.shared.requestAuthorization()
        } else if photoLibraryAccessState.canRead {
            PhotoLibraryManager.shared.startObservingChangesIfNeeded()
        }

        guard photoLibraryAccessState.canRead else {
            mapPhotoMomentCount = 0
            mapPhotoMoments = []
            return
        }

        await syncPhotoMoments(in: automaticPhotoSyncRange(referenceDate: now))
        didSync = true
    }

    func syncPhotoMoments(in range: ClosedRange<Date>) async {
        // Range changes and foreground/library notifications can arrive while
        // a batch yields. Finish one sync at a time, then process the latest request.
        if activePhotoSyncRange == range, pendingPhotoSyncRange == nil { return }
        pendingPhotoSyncRange = range
        guard activePhotoSyncRange == nil else { return }
        defer { activePhotoSyncRange = nil }
        while let nextRange = pendingPhotoSyncRange {
            pendingPhotoSyncRange = nil
            activePhotoSyncRange = nextRange
            await performPhotoMomentSync(in: nextRange)
        }
    }

    private func performPhotoMomentSync(in range: ClosedRange<Date>) async {
        photoLibraryAccessState = PhotoLibraryManager.shared.authorizationState
        guard photoLibraryAccessState.canRead else {
            mapPhotoMomentCount = 0
            mapPhotoMoments = []
            return
        }

        let libraryMetadata = PhotoLibraryManager.shared.fetchPhotoMetadata(in: range)
        let metadata = resolvedPhotoMoments(from: libraryMetadata, in: range)
        do {
            try await upsertPhotoMoments(metadata)
            if photoLibraryAccessState == .authorized {
                deleteMissingPhotoMoments(in: range, keeping: Set(metadata.map(\.assetLocalIdentifier)))
            }
            try modelContext.save()
        } catch {
            print("Failed to save photo moments: \(error)")
        }

        loadMapPhotoMoments(in: mapDateRange)
    }

    func loadTodayLocationPoints() {
        let calendar = Calendar.current
        let startOfDay = calendar.startOfDay(for: Date())
        let endOfDay = calendar.date(byAdding: .day, value: 1, to: startOfDay)!

        let predicate = #Predicate<LocationPoint> { point in
            point.timestamp >= startOfDay && point.timestamp < endOfDay
        }

        var descriptor = FetchDescriptor<LocationPoint>(predicate: predicate)
        descriptor.sortBy = [SortDescriptor(\.timestamp, order: .forward)]

        do {
            todayLocationPoints = try modelContext.fetch(descriptor)
            refreshDerivedPointCaches()
        } catch {
            print("Failed to fetch today's location points: \(error)")
            todayLocationPoints = []
            refreshDerivedPointCaches()
        }
    }

    private func fetchSampledMapPoints(
        predicate: Predicate<LocationPoint>,
        rawCount: Int
    ) throws -> [LocationPoint] {
        let batchCount = max(1, Int(ceil(Double(maximumRawMapPointFetchCount) / Double(mapFetchBatchSize))))
        let maxOffset = max(0, rawCount - mapFetchBatchSize)
        var sampled: [LocationPoint] = []
        sampled.reserveCapacity(min(maximumRawMapPointFetchCount, rawCount))
        var usedOffsets = Set<Int>()

        for batchIndex in 0..<batchCount {
            let offset: Int
            if batchCount == 1 {
                offset = 0
            } else {
                offset = Int((Double(batchIndex) * Double(maxOffset) / Double(batchCount - 1)).rounded())
            }

            guard usedOffsets.insert(offset).inserted else { continue }

            var descriptor = FetchDescriptor<LocationPoint>(predicate: predicate)
            descriptor.sortBy = [SortDescriptor(\.timestamp, order: .forward)]
            descriptor.fetchOffset = offset
            descriptor.fetchLimit = mapFetchBatchSize
            sampled.append(contentsOf: try modelContext.fetch(descriptor))
        }

        return sampled.sorted { $0.timestamp < $1.timestamp }
    }

    private func fetchLocationPoints(in range: ClosedRange<Date>? = nil) -> [LocationPoint] {
        var descriptor: FetchDescriptor<LocationPoint>

        if let range {
            let start = range.lowerBound
            let end = range.upperBound
            let predicate = #Predicate<LocationPoint> { point in
                point.timestamp >= start && point.timestamp <= end
            }
            descriptor = FetchDescriptor<LocationPoint>(predicate: predicate)
        } else {
            descriptor = FetchDescriptor<LocationPoint>()
        }

        descriptor.sortBy = [SortDescriptor(\.timestamp, order: .forward)]

        do {
            return try modelContext.fetch(descriptor)
        } catch {
            print("Failed to fetch location points: \(error)")
            return []
        }
    }

    private func fetchPhotoMoments(in range: ClosedRange<Date>) -> [PhotoMoment] {
        let start = range.lowerBound
        let end = range.upperBound
        let predicate = #Predicate<PhotoMoment> { photo in
            photo.takenAt >= start && photo.takenAt <= end
        }

        var descriptor = FetchDescriptor<PhotoMoment>(predicate: predicate)
        descriptor.sortBy = [SortDescriptor(\.takenAt, order: .forward)]

        do {
            return try modelContext.fetch(descriptor)
        } catch {
            print("Failed to fetch photo moments: \(error)")
            return []
        }
    }

    private func resolvedPhotoMoments(
        from libraryMetadata: [PhotoAssetLibraryMetadata],
        in range: ClosedRange<Date>
    ) -> [PhotoAssetMetadata] {
        guard !libraryMetadata.isEmpty else { return [] }

        let pointRange = range.lowerBound.addingTimeInterval(-photoRouteInferenceWindow)...range.upperBound.addingTimeInterval(photoRouteInferenceWindow)
        let fetchedPoints = fetchLocationPoints(in: pointRange)
        let nonOutlierPoints = fetchedPoints.filter { !$0.isOutlier }
        let routePoints = nonOutlierPoints.isEmpty ? fetchedPoints : nonOutlierPoints
        let visits = allVisits.sorted { $0.arrivedAt < $1.arrivedAt }
        let referenceDate = Date()

        return libraryMetadata.compactMap { item in
            if let coordinate = item.photoGPSCoordinate {
                return PhotoAssetMetadata(
                    assetLocalIdentifier: item.assetLocalIdentifier,
                    takenAt: item.takenAt,
                    latitude: coordinate.latitude,
                    longitude: coordinate.longitude,
                    coordinateSource: .photoGPS
                )
            }

            if let point = nearestLocationPoint(to: item.takenAt, in: routePoints),
               abs(point.timestamp.timeIntervalSince(item.takenAt)) <= photoRouteInferenceWindow {
                return PhotoAssetMetadata(
                    assetLocalIdentifier: item.assetLocalIdentifier,
                    takenAt: item.takenAt,
                    latitude: point.latitude,
                    longitude: point.longitude,
                    coordinateSource: .inferredFromRoute
                )
            }

            if let visit = visitContainingPhoto(takenAt: item.takenAt, in: visits, referenceDate: referenceDate) {
                return PhotoAssetMetadata(
                    assetLocalIdentifier: item.assetLocalIdentifier,
                    takenAt: item.takenAt,
                    latitude: visit.latitude,
                    longitude: visit.longitude,
                    coordinateSource: .inferredFromVisit
                )
            }

            return nil
        }
    }

    private func nearestLocationPoint(to date: Date, in points: [LocationPoint]) -> LocationPoint? {
        guard !points.isEmpty else { return nil }

        var lowerBound = 0
        var upperBound = points.count
        while lowerBound < upperBound {
            let midpoint = (lowerBound + upperBound) / 2
            if points[midpoint].timestamp < date {
                lowerBound = midpoint + 1
            } else {
                upperBound = midpoint
            }
        }

        let candidateIndices = [lowerBound - 1, lowerBound].filter { points.indices.contains($0) }
        return candidateIndices
            .map { points[$0] }
            .min { lhs, rhs in
                abs(lhs.timestamp.timeIntervalSince(date)) < abs(rhs.timestamp.timeIntervalSince(date))
            }
    }

    private func visitContainingPhoto(
        takenAt: Date,
        in visits: [Visit],
        referenceDate: Date
    ) -> Visit? {
        visits.first { visit in
            let start = visit.arrivedAt.addingTimeInterval(-photoVisitInferenceGracePeriod)
            let end = (visit.departedAt ?? referenceDate).addingTimeInterval(photoVisitInferenceGracePeriod)
            return takenAt >= start && takenAt <= end
        }
    }

    private func automaticPhotoSyncRange(referenceDate: Date = Date()) -> ClosedRange<Date> {
        let calendar = Calendar.current
        var dates: [Date] = [mapDateRange.lowerBound, mapDateRange.upperBound, referenceDate]

        for visit in allVisits {
            dates.append(visit.arrivedAt)
            if let departedAt = visit.departedAt {
                dates.append(departedAt)
            }
        }

        for session in allRecordingSessions {
            dates.append(session.startedAt)
            if let endedAt = session.endedAt {
                dates.append(endedAt)
            }
        }

        if let pointBounds = locationPointDateBounds() {
            dates.append(pointBounds.first)
            dates.append(pointBounds.last)
        }

        let earliest = dates.min() ?? calendar.startOfDay(for: referenceDate)
        let latest = max(dates.max() ?? referenceDate, referenceDate)
        let start = calendar.startOfDay(for: earliest)
        let endDay = calendar.startOfDay(for: latest)
        let end = calendar.date(byAdding: .day, value: 1, to: endDay) ?? latest
        return start...end
    }

    private func locationPointDateBounds() -> (first: Date, last: Date)? {
        do {
            var firstDescriptor = FetchDescriptor<LocationPoint>()
            firstDescriptor.sortBy = [SortDescriptor(\.timestamp, order: .forward)]
            firstDescriptor.fetchLimit = 1

            guard let firstPoint = try modelContext.fetch(firstDescriptor).first else {
                return nil
            }

            var lastDescriptor = FetchDescriptor<LocationPoint>()
            lastDescriptor.sortBy = [SortDescriptor(\.timestamp, order: .reverse)]
            lastDescriptor.fetchLimit = 1
            let lastPoint = try modelContext.fetch(lastDescriptor).first ?? firstPoint

            return (firstPoint.timestamp, lastPoint.timestamp)
        } catch {
            print("Failed to fetch location point date bounds: \(error)")
            return nil
        }
    }

    func upsertPhotoMoments(_ metadata: [PhotoAssetMetadata]) async throws {
        guard !metadata.isEmpty else { return }
        // A predicate fetch for each asset repeatedly examines pending changes
        // and makes an all-library import quadratic. Build the index once.
        let cached = try modelContext.fetch(FetchDescriptor<PhotoMoment>())
        var byIdentifier: [String: PhotoMoment] = [:]
        for photo in cached {
            byIdentifier[photo.assetLocalIdentifier] = photo
        }
        let now = Date()
        for (index, item) in metadata.enumerated() {
            if index.isMultiple(of: 100) {
                // Give the main run loop time for input and lifecycle events;
                // SwiftData models remain confined to the main actor.
                try await Task.sleep(for: .milliseconds(1))
            }
            if let existing = byIdentifier[item.assetLocalIdentifier] {
                existing.takenAt = item.takenAt
                existing.latitude = item.latitude
                existing.longitude = item.longitude
                existing.coordinateSource = item.coordinateSource
                existing.lastSyncedAt = now
            } else {
                let photo = PhotoMoment(
                    assetLocalIdentifier: item.assetLocalIdentifier,
                    takenAt: item.takenAt,
                    latitude: item.latitude,
                    longitude: item.longitude,
                    coordinateSource: item.coordinateSource,
                    lastSyncedAt: now
                )
                modelContext.insert(photo)
                byIdentifier[item.assetLocalIdentifier] = photo
            }
        }
    }

    private func deleteMissingPhotoMoments(in range: ClosedRange<Date>, keeping identifiers: Set<String>) {
        let cached = fetchPhotoMoments(in: range)
        for photo in cached where photo.coordinateSource == .photoGPS && !identifiers.contains(photo.assetLocalIdentifier) {
            modelContext.delete(photo)
        }
    }

    private func handleSavedLocationPoints(_ points: [LocationPoint]) {
        guard !points.isEmpty else {
            refreshLocationPointCount()
            loadTodayLocationPoints()
            loadMapLocationPoints(in: mapDateRange)
            if hasLoadedAllLocationPoints {
                loadLocationPoints()
            }
            return
        }

        let orderedPoints = points.sorted { $0.timestamp < $1.timestamp }
        totalLocationPointCount += orderedPoints.count

        if hasLoadedAllLocationPoints {
            locationPoints.append(contentsOf: orderedPoints)
        }

        let calendar = Calendar.current
        let startOfToday = calendar.startOfDay(for: Date())
        let endOfToday = calendar.date(byAdding: .day, value: 1, to: startOfToday)!
        var didAppendMapPoint = false

        for point in orderedPoints {
            if point.timestamp >= startOfToday && point.timestamp < endOfToday {
                let previousTodayPoint = todayLocationPoints.last
                todayLocationPoints.append(point)

                if let previousTodayPoint {
                    todayDistanceTraveledCache += previousTodayPoint.distance(to: point)
                }

                if let sessionStart = locationManager.trackingStartTime,
                   point.timestamp >= sessionStart {
                    if let previousSessionPoint = sessionLocationPointsCache.last {
                        sessionDistanceTraveledCache += previousSessionPoint.distance(to: point)
                    }
                    sessionLocationPointsCache.append(point)
                }
            }

            if mapDateRange.contains(point.timestamp) {
                mapLocationPointCount += 1
                mapLocationPoints.append(point)
                didAppendMapPoint = true
            }
        }

        if didAppendMapPoint {
            mapLocationPoints.sort { $0.timestamp < $1.timestamp }
            mapLocationPoints = downsample(points: mapLocationPoints, maxCount: maximumMapPointCount)
        }
    }

    private func refreshDerivedPointCaches() {
        todayDistanceTraveledCache = totalDistance(in: todayLocationPoints)

        guard let sessionStart = locationManager.trackingStartTime else {
            sessionLocationPointsCache = []
            sessionDistanceTraveledCache = 0
            return
        }

        sessionLocationPointsCache = todayLocationPoints.filter { $0.timestamp >= sessionStart }
        sessionDistanceTraveledCache = totalDistance(in: sessionLocationPointsCache)
    }

    private func totalDistance(in points: [LocationPoint]) -> Double {
        guard points.count > 1 else { return 0 }
        var total: Double = 0
        for index in 1..<points.count {
            total += points[index - 1].distance(to: points[index])
        }
        return total
    }

    private func downsample(points: [LocationPoint], maxCount: Int) -> [LocationPoint] {
        guard points.count > maxCount else { return points }
        guard maxCount > 1 else { return Array(points.prefix(max(0, maxCount))) }

        let lastIndex = points.count - 1
        let denominator = Double(maxCount - 1)
        var sampled: [LocationPoint] = []
        sampled.reserveCapacity(maxCount)

        for outputIndex in 0..<maxCount {
            let sourceIndex = Int((Double(outputIndex) * Double(lastIndex) / denominator).rounded())
            sampled.append(points[sourceIndex])
        }

        return sampled
    }

    // MARK: - Computed Properties

    var currentVisit: Visit? {
        allVisits
            .filter { $0.isCurrentVisit }
            .max { $0.arrivedAt < $1.arrivedAt }
    }

    func isCurrentVisit(_ visit: Visit) -> Bool {
        guard visit.departedAt == nil else { return false }
        return currentVisit?.id == visit.id
    }

    // Session-specific location points (only points from current tracking session)
    var sessionLocationPoints: [LocationPoint] {
        guard locationManager.trackingStartTime != nil else { return [] }
        return sessionLocationPointsCache
    }

    var sessionMapLocationPoints: [LocationPoint] {
        downsample(points: sessionLocationPoints, maxCount: maximumMapPointCount)
    }

    // Session tracking stats
    var sessionTrackingDuration: TimeInterval {
        guard let sessionStart = locationManager.trackingStartTime else { return 0 }
        return Date().timeIntervalSince(sessionStart)
    }

    var sessionDistanceTraveled: Double {
        sessionDistanceTraveledCache
    }

    var formattedSessionTrackingDuration: String {
        let totalSeconds = Int(sessionTrackingDuration)
        let minutes = totalSeconds / 60
        let seconds = totalSeconds % 60

        if minutes < 60 {
            return String(format: "%d:%02d", minutes, seconds)
        } else {
            let hours = minutes / 60
            let mins = minutes % 60
            return String(format: "%d:%02d:%02d", hours, mins, seconds)
        }
    }

    var formattedSessionDistance: String {
        formatDistance(sessionDistanceTraveled)
    }

    var sessionAccessibilitySummary: String {
        let pointCount = sessionLocationPoints.count
        guard pointCount > 0 else {
            return String(localized: "No active session path points.")
        }

        var parts = [
            pointCount == 1 ? String(localized: "1 point") : String(localized: "\(pointCount) points"),
            String(localized: "Duration \(formattedSessionTrackingDuration)"),
            String(localized: "Distance \(formattedSessionDistance)")
        ]

        if let first = sessionLocationPoints.first {
            parts.append(String(localized: "Started \(first.accessibilityTimestamp)"))
        }

        if let last = sessionLocationPoints.last, pointCount > 1 {
            parts.append(String(localized: "Latest point \(last.accessibilityTimestamp)"))
        }

        return parts.joined(separator: ". ")
    }

    // Today's tracking stats (kept for other views)
    var todayTrackingDuration: TimeInterval {
        guard let first = todayLocationPoints.first,
              let last = todayLocationPoints.last else { return 0 }
        return last.timestamp.timeIntervalSince(first.timestamp)
    }

    var todayDistanceTraveled: Double {
        todayDistanceTraveledCache
    }

    var formattedTodayTrackingDuration: String {
        let minutes = Int(todayTrackingDuration / 60)
        if minutes < 60 {
            return String(localized: "\(minutes) min")
        } else {
            let hours = minutes / 60
            let mins = minutes % 60
            if mins == 0 {
                return String(localized: "\(hours)h")
            }
            return String(localized: "\(hours)h \(mins)m")
        }
    }

    var formattedTodayDistance: String {
        formatDistance(todayDistanceTraveled)
    }

    private var usesMetricDistanceUnits: Bool {
        let key = "usesMetricDistanceUnits"
        if UserDefaults.standard.object(forKey: key) == nil {
            return true
        }
        return UserDefaults.standard.bool(forKey: key)
    }

    private func formatDistance(_ meters: Double) -> String {
        DistanceFormatter.format(meters: meters, usesMetric: usesMetricDistanceUnits)
    }

    func visitsInDateRange(_ range: ClosedRange<Date>) -> [Visit] {
        allVisits.filter { range.contains($0.arrivedAt) }
    }

    func visitsOverlappingDateRange(_ range: ClosedRange<Date>, referenceDate: Date = Date()) -> [Visit] {
        allVisits
            .filter { visit in
                let visitEnd = visit.departedAt ?? referenceDate
                return visit.arrivedAt <= range.upperBound && visitEnd >= range.lowerBound
            }
            .sorted { $0.arrivedAt < $1.arrivedAt }
    }

    func locationPointsInDateRange(_ range: ClosedRange<Date>) -> [LocationPoint] {
        fetchLocationPoints(in: range)
    }

    func photosInDateRange(_ range: ClosedRange<Date>) -> [PhotoMoment] {
        guard photoLibraryAccessState.canRead else { return [] }
        return accessiblePhotos(fetchPhotoMoments(in: range))
    }

    func recordingSessionSummaries(
        gapThreshold: TimeInterval = RecordingSessionBuilder.defaultGapThreshold,
        inferenceConfiguration: RecordingSessionInferenceConfiguration? = nil
    ) -> [RecordingSessionSummary] {
        RecordingSessionBuilder.summaries(
            storedSessions: allRecordingSessions,
            points: locationPoints,
            activeTrackingStart: locationManager.trackingStartTime,
            gapThreshold: gapThreshold,
            inferenceConfiguration: inferenceConfiguration
        )
    }

    /// Query only the selected day. Opening Timeline must not hydrate the export cache
    /// or materialize every point in a recording that has been running for weeks.
    func timelineDay(
        in range: Range<Date>,
        inferenceConfiguration: RecordingSessionInferenceConfiguration = .legacy(gapThreshold: RecordingSessionBuilder.defaultGapThreshold),
        now: Date = Date()
    ) throws -> TimelineDaySnapshot {
        let start = range.lowerBound
        let end = range.upperBound
        var pointsQuery = FetchDescriptor<LocationPoint>(predicate: #Predicate {
            $0.timestamp >= start && $0.timestamp < end && $0.timestamp <= now
        })
        pointsQuery.sortBy = [SortDescriptor(\.timestamp)]
        let points = try modelContext.fetch(pointsQuery)
        let sessionsQuery = FetchDescriptor<RecordingSession>(predicate: #Predicate {
            $0.startedAt < end && ($0.endedAt ?? now) >= start
        })
        let stored = try modelContext.fetch(sessionsQuery)
        var visitsQuery = FetchDescriptor<Visit>(predicate: #Predicate {
            $0.arrivedAt < end && $0.arrivedAt <= now && ($0.departedAt ?? now) > start
        })
        visitsQuery.sortBy = [SortDescriptor(\.arrivedAt)]
        let sessions = RecordingSessionBuilder.summaries(
            storedSessions: stored, points: points,
            activeTrackingStart: locationManager.trackingStartTime,
            inferenceConfiguration: inferenceConfiguration, now: now
        ).compactMap { $0.clipped(to: range) }
        return TimelineDaySnapshot(sessions: sessions, visits: try modelContext.fetch(visitsQuery))
    }

    /// One limited, descending fetch per record type, instead of loading history to find its last day.
    func latestTimelineDate() throws -> Date? {
        var points = FetchDescriptor<LocationPoint>(sortBy: [SortDescriptor(\.timestamp, order: .reverse)])
        points.fetchLimit = 1
        var visits = FetchDescriptor<Visit>(sortBy: [SortDescriptor(\.arrivedAt, order: .reverse)])
        visits.fetchLimit = 1
        var starts = FetchDescriptor<RecordingSession>(sortBy: [SortDescriptor(\.startedAt, order: .reverse)])
        starts.fetchLimit = 1
        var ends = FetchDescriptor<RecordingSession>(sortBy: [SortDescriptor(\.endedAt, order: .reverse)])
        ends.fetchLimit = 1
        return try [modelContext.fetch(points).first?.timestamp,
                    modelContext.fetch(visits).first?.arrivedAt,
                    modelContext.fetch(starts).first?.startedAt,
                    modelContext.fetch(ends).first?.endedAt].compactMap { $0 }.max()
    }

    /// Full history is fetched only after the person chooses View complete outing.
    func completeRecordingSession(_ stored: RecordingSession, now: Date = Date()) throws -> RecordingSessionSummary? {
        let start = stored.startedAt
        let end = stored.endedAt ?? now
        var query = FetchDescriptor<LocationPoint>(predicate: #Predicate {
            $0.timestamp >= start && $0.timestamp <= end
        })
        query.sortBy = [SortDescriptor(\.timestamp)]
        return RecordingSessionBuilder.summaries(
            storedSessions: [stored], points: try modelContext.fetch(query),
            activeTrackingStart: locationManager.trackingStartTime,
            inferenceConfiguration: .stored(), now: now
        ).first
    }

    func focusMap(on session: RecordingSessionSummary) {
        mapDateRange = session.dateRange
        loadMapLocationPoints(in: session.dateRange)
        loadMapPhotoMoments(in: session.dateRange)
        mapFocusRequest = MapSessionFocusRequest(
            sessionID: session.id,
            title: session.title,
            range: session.dateRange
        )
    }

    // MARK: - Saved Places

    /// Explicit library edits update by identity and never rewrite historical visits.
    @discardableResult
    func saveManagedPlace(_ requestedDraft: SavedPlaceDraft) throws -> SavedPlace {
        if let message = requestedDraft.validationMessage { throw SavedPlaceManagementError.invalid(message) }
        let place: SavedPlace
        let snapshot: SavedPlaceDraft?
        let oldUpdatedAt: Date?
        let workingPlaces = try modelContext.fetch(FetchDescriptor<SavedPlace>())
        // A new place with the same name inside an existing place's radius is that place, as for
        // `createSavedPlace` and CSV import: update it rather than save a duplicate.
        var draft = requestedDraft
        if draft.existingPlaceID == nil,
           let duplicate = workingPlaces.first(where: { requestedDraft.matches(SavedPlaceDraft($0)) }) {
            draft = requestedDraft.merged(into: duplicate)
        }
        if let id = draft.existingPlaceID {
            guard let existing = workingPlaces.first(where: { $0.id == id }) else {
                throw SavedPlaceManagementError.missingPlace
            }
            place = existing
            snapshot = SavedPlaceDraft(existing)
            oldUpdatedAt = existing.updatedAt
        } else {
            place = SavedPlace(id: draft.id, name: draft.name, latitude: draft.latitude!, longitude: draft.longitude!)
            snapshot = nil
            oldUpdatedAt = nil
            modelContext.insert(place)
        }
        draft.apply(to: place)
        place.updatedAt = Date()
        do {
            try modelContext.save()
        } catch {
            if let snapshot {
                snapshot.apply(to: place)
                place.updatedAt = oldUpdatedAt!
            } else {
                modelContext.delete(place)
            }
            throw error
        }
        loadSavedPlaces()
        return place
    }

    func deleteManagedPlace(_ place: SavedPlace) throws {
        modelContext.delete(place)
        do {
            try modelContext.save()
        } catch {
            modelContext.insert(place)
            throw error
        }
        loadSavedPlaces()
    }

    func importSavedPlaces(_ imported: [ImportedSavedPlace], duplicates: SavedPlaceDuplicatePolicy) throws -> SavedPlaceImportSummary {
        let drafts = imported.map(SavedPlaceDraft.init)
        for draft in drafts {
            if let message = draft.validationMessage { throw SavedPlaceManagementError.invalid(message) }
        }
        let workingPlaces = try modelContext.fetch(FetchDescriptor<SavedPlace>())
        let plan = SavedPlaceImportPlan(places: imported, existing: workingPlaces.map(SavedPlaceDraft.init), policy: duplicates)
        var byID = Dictionary(uniqueKeysWithValues: workingPlaces.map { ($0.id, $0) })
        let originalValues = workingPlaces.map { ($0, SavedPlaceDraft($0), $0.updatedAt) }
        var inserted: [SavedPlace] = []
        for entry in plan.entries {
            let draft = SavedPlaceDraft(entry.place)
            switch entry.action {
            case .skip: continue
            case .update:
                guard let match = byID[entry.targetID] else { throw SavedPlaceManagementError.missingPlace }
                draft.apply(to: match)
                match.updatedAt = Date()
            case .add:
                let place = SavedPlace(id: entry.targetID, name: draft.name, latitude: draft.latitude!, longitude: draft.longitude!)
                draft.apply(to: place)
                modelContext.insert(place)
                byID[place.id] = place
                inserted.append(place)
            }
        }
        do {
            try modelContext.save()
        } catch {
            for (place, snapshot, updatedAt) in originalValues {
                snapshot.apply(to: place)
                place.updatedAt = updatedAt
            }
            for place in inserted { modelContext.delete(place) }
            throw error
        }
        loadSavedPlaces()
        return plan.summary
    }

    @discardableResult
    func createSavedPlace(
        name: String,
        address: String?,
        coordinate: CLLocationCoordinate2D,
        radiusMeters: Double = 150
    ) -> SavedPlace? {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedName.isEmpty,
              CLLocationCoordinate2DIsValid(coordinate),
              coordinate.latitude.isFinite,
              coordinate.longitude.isFinite else {
            return nil
        }

        let now = Date()
        let trimmedAddress = address?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        let normalizedRadius = max(25, radiusMeters)

        if let existingPlace = savedPlaces.first(where: { place in
            place.normalizedName.localizedCaseInsensitiveCompare(trimmedName) == .orderedSame
                && place.distanceMeters(to: coordinate) <= max(place.radiusMeters, normalizedRadius)
        }) {
            existingPlace.latitude = coordinate.latitude
            existingPlace.longitude = coordinate.longitude
            existingPlace.address = trimmedAddress
            existingPlace.radiusMeters = normalizedRadius
            existingPlace.updatedAt = now
            try? modelContext.save()
            loadSavedPlaces()
            return existingPlace
        }

        let place = SavedPlace(
            name: trimmedName,
            latitude: coordinate.latitude,
            longitude: coordinate.longitude,
            address: trimmedAddress,
            radiusMeters: normalizedRadius,
            createdAt: now,
            updatedAt: now
        )
        modelContext.insert(place)
        try? modelContext.save()
        loadSavedPlaces()
        return place
    }

    func deleteSavedPlace(_ place: SavedPlace) {
        modelContext.delete(place)
        try? modelContext.save()
        loadSavedPlaces()
    }

    // MARK: - Visit Management

    /// A confirmed or corrected place becomes reusable knowledge for future
    /// automatic visits. Prefer the user's name, then the detected address;
    /// do not create a saved place for a visit with no meaningful place data.
    @discardableResult
    private func rememberConfirmedPlace(for visit: Visit) -> SavedPlace? {
        let name = (visit.exportLocationName ?? visit.address)?
            .trimmingCharacters(in: .whitespacesAndNewlines)
        guard let name, !name.isEmpty else { return nil }

        // Confirming a visit must not undo an explicitly managed pin or radius.
        let draft = SavedPlaceDraft(name: name, latitude: visit.latitude, longitude: visit.longitude)
        if let existing = savedPlaces.filter({ draft.matches(SavedPlaceDraft($0)) })
            .min(by: { draft.distance(to: SavedPlaceDraft($0)) < draft.distance(to: SavedPlaceDraft($1)) }) {
            return existing
        }

        return createSavedPlace(
            name: name,
            address: visit.address,
            coordinate: visit.coordinate,
            radiusMeters: Self.defaultSavedPlaceRadiusMeters
        )
    }

    func deleteVisit(_ visit: Visit) {
        modelContext.delete(visit)
        try? modelContext.save()
        loadData()
    }

    func updateVisitName(_ visit: Visit, customName: String) {
        let trimmed = customName.trimmingCharacters(in: .whitespacesAndNewlines)
        if trimmed.isEmpty || trimmed == visit.automaticDisplayName {
            visit.customName = nil
        } else {
            visit.customName = trimmed
        }
        visit.updatedAt = Date()
        if visit.isConfirmed {
            rememberConfirmedPlace(for: visit)
        }
        try? modelContext.save()
        loadTodayVisits()
        loadAllVisits()
    }

    func clearVisitName(_ visit: Visit) {
        visit.customName = nil
        visit.updatedAt = Date()
        if visit.isConfirmed {
            rememberConfirmedPlace(for: visit)
        }
        try? modelContext.save()
        loadTodayVisits()
        loadAllVisits()
    }

    func updateVisitNotes(_ visit: Visit, notes: String) {
        let trimmed = notes.trimmingCharacters(in: .whitespacesAndNewlines)
        visit.notes = trimmed.isEmpty ? nil : notes
        visit.updatedAt = Date()
        try? modelContext.save()
    }

    func confirmVisit(_ visit: Visit) {
        let now = Date()
        visit.confirmationStatus = .confirmed
        visit.confirmedAt = now
        visit.updatedAt = now
        rememberConfirmedPlace(for: visit)
        try? modelContext.save()
        loadTodayVisits()
        loadAllVisits()
    }

    func correctVisit(
        _ visit: Visit,
        name: String,
        address: String?,
        coordinate: CLLocationCoordinate2D,
        placeSource: VisitPlaceSource,
        distanceMeters: Double? = nil
    ) {
        let trimmedName = name.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmedName.isEmpty,
              CLLocationCoordinate2DIsValid(coordinate),
              coordinate.latitude.isFinite,
              coordinate.longitude.isFinite else {
            return
        }

        let now = Date()
        visit.preserveOriginalValuesIfNeeded()
        visit.countryCode = nil
        visit.countryTimeZoneIdentifier = nil
        visit.countryResolutionAttemptedAt = nil
        visit.latitude = coordinate.latitude
        visit.longitude = coordinate.longitude
        visit.customName = nil
        visit.locationName = trimmedName
        visit.address = address?.trimmingCharacters(in: .whitespacesAndNewlines).nilIfEmpty
        visit.confirmationStatus = .corrected
        visit.confirmedAt = visit.confirmedAt ?? now
        visit.updatedAt = now
        visit.placeSource = placeSource
        visit.placeDistanceMeters = distanceMeters
        visit.geocodingCompleted = true
        rememberConfirmedPlace(for: visit)

        try? modelContext.save()
        loadTodayVisits()
        loadAllVisits()
        loadMapLocationPoints(in: mapDateRange)
    }

    func undoVisitCorrection(_ visit: Visit) {
        guard visit.originalLatitude != nil, visit.originalLongitude != nil else { return }

        visit.restoreOriginalValues()

        try? modelContext.save()
        loadTodayVisits()
        loadAllVisits()
        loadMapLocationPoints(in: mapDateRange)
    }

    @discardableResult
    func createManualVisit(
        name: String, address: String?, coordinate: CLLocationCoordinate2D,
        arrivedAt: Date, departedAt: Date?, placeSource: VisitPlaceSource = .userEntered,
        allowOverlap: Bool = false
    ) -> Visit? {
        let store = ManualVisitStore(context: modelContext)
        let visit = store.createManualVisit(name: name, address: address, coordinate: coordinate,
            arrivedAt: arrivedAt, departedAt: departedAt, placeSource: placeSource, allowOverlap: allowOverlap)
        visitMutationError = store.visitMutationError
        if visit != nil { loadData() }
        return visit
    }

    @discardableResult
    func updateVisitTimes(_ visit: Visit, arrivedAt: Date, departedAt: Date?, allowOverlap: Bool = false) -> Bool {
        let store = ManualVisitStore(context: modelContext)
        let saved = store.updateVisitTimes(visit, arrivedAt: arrivedAt, departedAt: departedAt, allowOverlap: allowOverlap)
        visitMutationError = store.visitMutationError
        if saved { refreshManualVisits() }
        return saved
    }

    @discardableResult
    func checkoutVisit(_ visit: Visit, at date: Date = Date()) -> Bool {
        let store = ManualVisitStore(context: modelContext)
        let saved = store.checkoutVisit(visit, at: date)
        visitMutationError = store.visitMutationError
        if saved { refreshManualVisits() }
        return saved
    }

    /// Splits a stop into two back-to-back visits; the second is at the chosen place.
    @discardableResult
    func splitVisit(
        _ visit: Visit,
        at splitAt: Date,
        secondPlaceName name: String,
        address: String?,
        coordinate: CLLocationCoordinate2D,
        placeSource: VisitPlaceSource,
        distanceMeters: Double? = nil
    ) -> Visit? {
        let store = ManualVisitStore(context: modelContext)
        let second = store.splitVisit(
            visit, at: splitAt, secondPlaceName: name, address: address,
            coordinate: coordinate, placeSource: placeSource, distanceMeters: distanceMeters
        )
        visitMutationError = store.visitMutationError
        guard let second else { return nil }
        // Same as a correction: the chosen place becomes reusable for future visits.
        rememberConfirmedPlace(for: second)
        try? modelContext.save()
        refreshManualVisits()
        return second
    }

    func storedVisit(id: UUID) throws -> Visit? {
        try modelContext.fetch(FetchDescriptor<Visit>(predicate: #Predicate { $0.id == id })).first
    }

    private func refreshManualVisits() {
        loadTodayVisits()
        loadAllVisits()
        loadMapLocationPoints(in: mapDateRange)
        locationManager.syncDataToWatch()
    }

    // MARK: - Recording Session Management

    func deleteRecordingSession(_ session: RecordingSessionSummary) {
        // A daily slice shares the source session's identity. Deleting it would otherwise
        // remove that session while leaving most of its points orphaned.
        guard session.dayRange == nil else { return }

        let isCurrentlyRecordingSession = locationManager.isTrackingEnabled
            && session.isActive
            && (session.storedSession?.endedAt == nil)
        if isCurrentlyRecordingSession {
            locationManager.stopTracking()
        }

        let start = session.startedAt
        let end = max(start, session.storedSession?.endedAt ?? session.effectiveEndDate)
        let predicate = #Predicate<LocationPoint> { point in
            point.timestamp >= start && point.timestamp <= end
        }

        do {
            let points = try modelContext.fetch(FetchDescriptor<LocationPoint>(predicate: predicate))
            for point in points {
                modelContext.delete(point)
            }

            if let storedSession = session.storedSession {
                modelContext.delete(storedSession)
            }

            try modelContext.save()

            if mapFocusRequest?.sessionID == session.id {
                mapFocusRequest = nil
            }

            // Outing summaries are built from the full point cache. Invalidate it
            // so an inferred outing cannot be rebuilt from deleted model objects.
            locationPoints = []
            hasLoadedAllLocationPoints = false
            loadData()
            ensureAllLocationPointsLoaded()
            locationManager.syncDataToWatch()
        } catch {
            print("Failed to delete recording session: \(error)")
        }
    }

    func updateRecordingSession(_ session: RecordingSession, customName: String, notes: String) {
        let trimmedName = customName.trimmingCharacters(in: .whitespacesAndNewlines)
        session.customName = trimmedName.isEmpty ? nil : trimmedName

        let trimmedNotes = notes.trimmingCharacters(in: .whitespacesAndNewlines)
        session.notes = trimmedNotes.isEmpty ? nil : notes

        try? modelContext.save()
        loadRecordingSessions()
    }

    // MARK: - Export

    func exportVisits(format: ExportFormat, dateRange: ClosedRange<Date>? = nil) {
        let visitsToExport: [Visit]
        if let range = dateRange {
            visitsToExport = visitsInDateRange(range)
        } else {
            visitsToExport = allVisits
        }

        do {
            try ExportService.share(
                visits: visitsToExport,
                format: format,
                completion: Self.recordSuccessfulShareExportForReviewPrompt
            )
        } catch {
            exportError = error.localizedDescription
        }
    }

    func exportLocationPoints(format: ExportFormat, dateRange: ClosedRange<Date>? = nil) {
        let pointsToExport = fetchLocationPoints(in: dateRange)

        if dateRange == nil {
            locationPoints = pointsToExport
            hasLoadedAllLocationPoints = true
            totalLocationPointCount = pointsToExport.count
        }

        do {
            try ExportService.shareLocationPoints(
                points: pointsToExport,
                format: format,
                completion: Self.recordSuccessfulShareExportForReviewPrompt
            )
        } catch {
            exportError = error.localizedDescription
        }
    }

    func exportAllData(format: ExportFormat) {
        let pointsToExport = fetchLocationPoints()
        locationPoints = pointsToExport
        hasLoadedAllLocationPoints = true
        totalLocationPointCount = pointsToExport.count

        do {
            try ExportService.shareCombined(
                visits: allVisits,
                points: pointsToExport,
                format: format,
                completion: Self.recordSuccessfulShareExportForReviewPrompt
            )
        } catch {
            exportError = error.localizedDescription
        }
    }

    @MainActor
    func exportWithOptions(_ options: ExportOptions) {
        let needsPoints = options.dataKind != .visits || options.format.isPointsOnly
        let pointsToExport = needsPoints ? fetchLocationPoints(in: options.resolvedDateRange()) : []

        if needsPoints && options.resolvedDateRange() == nil {
            locationPoints = pointsToExport
            hasLoadedAllLocationPoints = true
            totalLocationPointCount = pointsToExport.count
        }

        do {
            try ExportService.share(
                visits: allVisits,
                points: pointsToExport,
                recordingSessions: allRecordingSessions,
                activeTrackingStart: locationManager.trackingStartTime,
                options: options,
                completion: Self.recordSuccessfulShareExportForReviewPrompt
            )
        } catch {
            exportError = error.localizedDescription
        }
    }

    private static func recordSuccessfulShareExportForReviewPrompt(completed: Bool) {
        guard completed else { return }
        Task { @MainActor in
            AppReviewPromptCoordinator.shared.recordSuccessfulFileExport()
        }
    }

    // MARK: - Import

    func prepareImport(from urls: [URL]) async throws -> ImportPreview {
        try await timelineImportService.preview(urls: urls)
    }

    func commitImport(_ preview: ImportPreview, options: ImportOptions) async throws -> ImportCommitResult {
        let result = try await timelineImportService.commit(preview, options: options)
        if result.pointCount > 0 {
            locationPoints = []
            hasLoadedAllLocationPoints = false
        }
        // Imported visits must not be reconciled into live detections or geocoded.
        loadData()
        locationManager.syncDataToWatch()
        return result
    }

    func exportBackup(to directory: URL) throws -> [URL] {
        try WheredayBackupService.exportFiles(context: modelContext, directory: directory)
    }

    // MARK: - Clear Data

    func clearAllData() {
        do {
            try modelContext.delete(model: Visit.self)
            try modelContext.delete(model: LocationPoint.self)
            try modelContext.delete(model: RecordingSession.self)
            try modelContext.delete(model: PhotoMoment.self)
            try modelContext.delete(model: SavedPlace.self)
            try modelContext.delete(model: CountryStay.self)
            try modelContext.save()
            allRecordingSessions = []
            locationPoints = []
            todayLocationPoints = []
            mapLocationPoints = []
            mapPhotoMoments = []
            savedPlaces = []
            sessionLocationPointsCache = []
            mapLocationPointCount = 0
            mapPhotoMomentCount = 0
            totalLocationPointCount = 0
            todayDistanceTraveledCache = 0
            sessionDistanceTraveledCache = 0
            hasLoadedAllLocationPoints = false
            mapFocusRequest = nil
            locationManager.forgetActiveRecordingSession()
            loadData()
        } catch {
            print("Failed to clear data: \(error)")
        }
    }

    // MARK: - Tracking Control

    func startTracking() {
        locationManager.startTracking()
        loadRecordingSessions()
        refreshDerivedPointCaches()
    }

    func stopTracking() {
        locationManager.stopTracking()
        loadRecordingSessions()
        refreshDerivedPointCaches()
    }
}

private extension String {
    var nilIfEmpty: String? {
        isEmpty ? nil : self
    }
}
