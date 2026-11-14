import Foundation
import CoreLocation
import SwiftData
import Combine
import ActivityKit
import WidgetKit

extension Notification.Name {
    static let trackingAccessRequired = Notification.Name("trackingAccessRequired")
}

@MainActor
final class LocationManager: NSObject, ObservableObject {
    /// Latest live instance, used by App Intents so Siri/Shortcuts can drive tracking.
    static weak var shared: LocationManager?

    private let locationManager = CLLocationManager()
    private var modelContext: ModelContext?

    // Published state
    @Published var authorizationStatus: CLAuthorizationStatus = .notDetermined
    @Published var isTrackingEnabled: Bool = false
    @Published var trackingStartTime: Date?
    @Published var stopAfterHours: Double = 0.0
    @Published var distanceFilter: Double = 5.0
    @Published var currentLocation: CLLocation?
    @Published var lastError: String?

    // Safety-net auto-stop timer (only runs when stopAfterHours > 0)
    private var stopTrackingTimer: Timer?
    private var accessObserver: AnyCancellable?
    private var resumeAfterAccessResolution = false

    // Geocoding service
    private let geocodingService = GeocodingService()

    // Publisher for data changes (fires when new location points are saved)
    @Published var locationPointsSavedCount: Int = 0
    @Published var lastSavedLocationPoint: LocationPoint?
    @Published var lastSavedLocationPoints: [LocationPoint] = []

    // Live Activity manager
    private let liveActivityManager = LiveActivityManager.shared
    @Published var isLiveActivityEnabled: Bool = true
    private let liveActivityEnabledKey = "isLiveActivityEnabled"

    // Daily distance history (recorded for stats; no longer drives auto-start)
    private let dailyDistanceTracker = DailyDistanceTracker.shared

    // Current location name for Live Activity
    private var currentLocationName: String?
    private var currentAddress: String?
    private var lastGeocodedLocation: CLLocation?
    private var pendingManualLocationRequest = false

    // Sliding window of recently saved points for outlier detection
    private var pointBeforeLast: LocationPoint?
    private var lastSavedPoint: LocationPoint?

    // Core Location visit departure events can arrive with slightly different
    // centroid coordinates than their arrival events. Matching by exact doubles
    // leaves stale open visits behind, which then render as many blue "current"
    // pins on the map.
    private let visitCoordinateMatchThresholdMeters: CLLocationDistance = 150
    private let visitArrivalMatchTolerance: TimeInterval = 15 * 60
    private let duplicateVisitMergeThresholdMeters: CLLocationDistance = 100
    private let duplicateVisitMergeGap: TimeInterval = 30 * 60

    private static let activeRecordingSessionIDKey = "activeRecordingSessionID"

    private var activeRecordingSessionID: UUID? {
        get {
            guard let rawValue = UserDefaults.standard.string(forKey: Self.activeRecordingSessionIDKey) else {
                return nil
            }
            return UUID(uuidString: rawValue)
        }
        set {
            if let newValue {
                UserDefaults.standard.set(newValue.uuidString, forKey: Self.activeRecordingSessionIDKey)
            } else {
                UserDefaults.standard.removeObject(forKey: Self.activeRecordingSessionIDKey)
            }
        }
    }

    private var usesMetricDistanceUnits: Bool {
        let key = "usesMetricDistanceUnits"
        if UserDefaults.standard.object(forKey: key) == nil {
            return true
        }
        return UserDefaults.standard.bool(forKey: key)
    }

    private var allowNetworkGeocoding: Bool {
        let key = "allowNetworkGeocoding"
        if UserDefaults.standard.object(forKey: key) == nil {
            return true
        }
        return UserDefaults.standard.bool(forKey: key)
    }

    override init() {
        super.init()
        Self.shared = self
        locationManager.delegate = self
        locationManager.allowsBackgroundLocationUpdates = true
        locationManager.pausesLocationUpdatesAutomatically = false
        locationManager.desiredAccuracy = kCLLocationAccuracyBest

        // Resolve the cohort before any new-install defaults are written.
        let store = StoreManager.shared
        resumeAfterAccessResolution = UserDefaults.standard.bool(forKey: "isTrackingEnabled")
        isTrackingEnabled = resumeAfterAccessResolution && store.canTrack
        accessObserver = store.objectWillChange.sink { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                if store.canTrack {
                    if self.resumeAfterAccessResolution && store.hasResolvedEntitlements {
                        self.resumeAfterAccessResolution = false
                        self.startTracking()
                    }
                } else if self.isTrackingEnabled {
                    self.stopTracking()
                }
            }
        }

        // Restore saved state
        if UserDefaults.standard.object(forKey: "stopAfterHours") != nil {
            stopAfterHours = UserDefaults.standard.double(forKey: "stopAfterHours")
        }
        if UserDefaults.standard.object(forKey: "distanceFilter") != nil {
            distanceFilter = UserDefaults.standard.double(forKey: "distanceFilter")
        }
        if UserDefaults.standard.object(forKey: liveActivityEnabledKey) != nil {
            isLiveActivityEnabled = UserDefaults.standard.bool(forKey: liveActivityEnabledKey)
        }
        locationManager.distanceFilter = distanceFilter

        authorizationStatus = locationManager.authorizationStatus

        // Listen for stop notification from Live Activity deep link
        NotificationCenter.default.addObserver(
            forName: .stopTracking,
            object: nil,
            queue: .main
        ) { [weak self] _ in
            Task { @MainActor in
                self?.stopTracking()
            }
        }

        // If tracking was on at last launch, resume it (re-attaches CL APIs + Live Activity).
        if isTrackingEnabled && hasLocationPermission {
            startTracking()
        }
    }

    func setModelContext(_ context: ModelContext) {
        self.modelContext = context
        reconcileOpenVisits()
        reconcileRecordingSessions()
    }

    // MARK: - Permission Handling

    func requestAlwaysAuthorization() {
        locationManager.requestAlwaysAuthorization()
    }

    func requestWhenInUseAuthorization() {
        locationManager.requestWhenInUseAuthorization()
    }

    var canRequestAlwaysAuthorization: Bool {
        authorizationStatus == .notDetermined || authorizationStatus == .authorizedWhenInUse
    }

    var hasLocationPermission: Bool {
        authorizationStatus == .authorizedAlways || authorizationStatus == .authorizedWhenInUse
    }

    var hasAlwaysPermission: Bool {
        authorizationStatus == .authorizedAlways
    }

    func requestCurrentLocationForManualVisit() {
        guard hasLocationPermission else {
            pendingManualLocationRequest = true
            requestWhenInUseAuthorization()
            return
        }

        pendingManualLocationRequest = false
        locationManager.requestLocation()
    }

    // MARK: - Tracking Control

    func startTracking() {
        guard StoreManager.shared.canTrack else {
            NotificationCenter.default.post(name: .trackingAccessRequired, object: nil)
            return
        }
        guard hasLocationPermission else {
            requestAlwaysAuthorization()
            return
        }

        isTrackingEnabled = true
        UserDefaults.standard.set(true, forKey: "isTrackingEnabled")

        if trackingStartTime == nil {
            trackingStartTime = Date()
        }

        if let trackingStartTime {
            ensureActiveRecordingSession(startedAt: trackingStartTime)
        }

        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = distanceFilter
        locationManager.startMonitoringVisits()
        locationManager.startMonitoringSignificantLocationChanges()
        locationManager.startUpdatingLocation()

        if isLiveActivityEnabled {
            liveActivityManager.startActivity(autoStopSeconds: liveActivityRemainingSeconds)
        }

        scheduleStopTrackingTimer()
        syncDataToWatch()
    }

    func stopTracking() {
        resumeAfterAccessResolution = false
        let endedAt = Date()
        endActiveRecordingSession(at: endedAt)

        isTrackingEnabled = false
        UserDefaults.standard.set(false, forKey: "isTrackingEnabled")

        stopTrackingTimer?.invalidate()
        stopTrackingTimer = nil
        trackingStartTime = nil

        locationManager.stopMonitoringVisits()
        locationManager.stopMonitoringSignificantLocationChanges()
        locationManager.stopUpdatingLocation()

        Task {
            await liveActivityManager.endAllActivities()
        }

        syncDataToWatch()
    }

    func setStopAfterHours(_ hours: Double) {
        stopAfterHours = hours
        UserDefaults.standard.set(hours, forKey: "stopAfterHours")
        scheduleStopTrackingTimer()

        if isTrackingEnabled, isLiveActivityEnabled {
            liveActivityManager.updateActivity(
                location: nil,
                locationName: currentLocationName,
                remainingSeconds: liveActivityRemainingSeconds
            )
        }
    }

    func setLiveActivityEnabled(_ enabled: Bool) {
        isLiveActivityEnabled = enabled
        UserDefaults.standard.set(enabled, forKey: liveActivityEnabledKey)

        if enabled {
            if isTrackingEnabled {
                liveActivityManager.startActivity(autoStopSeconds: liveActivityRemainingSeconds)
            }
        } else {
            Task {
                await liveActivityManager.endAllActivities()
            }
        }
    }

    func setDistanceFilter(_ meters: Double) {
        distanceFilter = meters
        UserDefaults.standard.set(meters, forKey: "distanceFilter")
        if isTrackingEnabled {
            locationManager.distanceFilter = meters
        }
    }

    private func scheduleStopTrackingTimer() {
        stopTrackingTimer?.invalidate()
        stopTrackingTimer = nil

        guard isTrackingEnabled, stopAfterHours > 0, let start = trackingStartTime else { return }

        let elapsed = Date().timeIntervalSince(start)
        let total = stopAfterHours * 3600
        let remaining = total - elapsed
        guard remaining > 0 else {
            stopTracking()
            return
        }

        stopTrackingTimer = Timer.scheduledTimer(withTimeInterval: remaining, repeats: false) { [weak self] _ in
            Task { @MainActor in
                self?.stopTracking()
            }
        }
    }

    var remainingTime: TimeInterval? {
        guard stopAfterHours > 0, let start = trackingStartTime else { return nil }
        let elapsed = Date().timeIntervalSince(start)
        let total = stopAfterHours * 3600
        return max(0, total - elapsed)
    }

    private var liveActivityRemainingSeconds: Int? {
        remainingTime.map { Int($0) }
    }

    // MARK: - Recording Sessions

    @discardableResult
    func reconcileRecordingSessions(referenceDate: Date = Date()) -> Int {
        guard let context = modelContext else { return 0 }

        do {
            var descriptor = FetchDescriptor<RecordingSession>(
                predicate: #Predicate { session in
                    session.endedAt == nil
                }
            )
            descriptor.sortBy = [SortDescriptor(\.startedAt, order: .forward)]
            let openSessions = try context.fetch(descriptor)
            var changedCount = 0

            if isTrackingEnabled {
                if let latestOpenSession = openSessions.last {
                    for staleSession in openSessions.dropLast() {
                        staleSession.endedAt = max(staleSession.startedAt, latestOpenSession.startedAt)
                        changedCount += 1
                    }

                    activeRecordingSessionID = latestOpenSession.id
                    if trackingStartTime == nil || trackingStartTime! > latestOpenSession.startedAt {
                        trackingStartTime = latestOpenSession.startedAt
                    }
                } else {
                    let start = trackingStartTime ?? referenceDate
                    let session = RecordingSession(startedAt: start)
                    context.insert(session)
                    activeRecordingSessionID = session.id
                    trackingStartTime = start
                    changedCount += 1
                }
            } else {
                for session in openSessions {
                    session.endedAt = max(session.startedAt, referenceDate)
                    changedCount += 1
                }
                activeRecordingSessionID = nil
            }

            if changedCount > 0 {
                try context.save()
            }

            return changedCount
        } catch {
            lastError = String(localized: "Failed to reconcile recording sessions: \(error.localizedDescription)")
            return 0
        }
    }

    private func ensureActiveRecordingSession(startedAt: Date) {
        guard let context = modelContext else { return }

        do {
            if let activeRecordingSessionID,
               let activeSession = try recordingSession(withID: activeRecordingSessionID, in: context),
               activeSession.endedAt == nil {
                return
            }

            let openSessions = try openRecordingSessions(in: context)
            if let latestOpenSession = openSessions.last {
                for staleSession in openSessions.dropLast() {
                    staleSession.endedAt = max(staleSession.startedAt, latestOpenSession.startedAt)
                }
                activeRecordingSessionID = latestOpenSession.id
                try context.save()
                return
            }

            let session = RecordingSession(startedAt: startedAt)
            context.insert(session)
            activeRecordingSessionID = session.id
            try context.save()
        } catch {
            lastError = String(localized: "Failed to start recording session: \(error.localizedDescription)")
        }
    }

    private func endActiveRecordingSession(at endedAt: Date) {
        guard let context = modelContext else {
            activeRecordingSessionID = nil
            return
        }

        do {
            let sessionsToClose: [RecordingSession]
            if let activeRecordingSessionID,
               let activeSession = try recordingSession(withID: activeRecordingSessionID, in: context),
               activeSession.endedAt == nil {
                sessionsToClose = [activeSession]
            } else {
                sessionsToClose = try openRecordingSessions(in: context)
            }

            for session in sessionsToClose {
                session.endedAt = max(session.startedAt, endedAt)
            }

            if !sessionsToClose.isEmpty {
                try context.save()
            }
            activeRecordingSessionID = nil
        } catch {
            lastError = String(localized: "Failed to end recording session: \(error.localizedDescription)")
        }
    }

    private func openRecordingSessions(in context: ModelContext) throws -> [RecordingSession] {
        var descriptor = FetchDescriptor<RecordingSession>(
            predicate: #Predicate { session in
                session.endedAt == nil
            }
        )
        descriptor.sortBy = [SortDescriptor(\.startedAt, order: .forward)]
        return try context.fetch(descriptor)
    }

    private func recordingSession(withID id: UUID, in context: ModelContext) throws -> RecordingSession? {
        let predicate = #Predicate<RecordingSession> { session in
            session.id == id
        }
        var descriptor = FetchDescriptor<RecordingSession>(predicate: predicate)
        descriptor.fetchLimit = 1
        return try context.fetch(descriptor).first
    }

    static func closePersistedRecordingSessions(in context: ModelContext, at endedAt: Date = Date()) {
        do {
            let predicate = #Predicate<RecordingSession> { session in
                session.endedAt == nil
            }
            let sessions = try context.fetch(FetchDescriptor<RecordingSession>(predicate: predicate))
            for session in sessions {
                session.endedAt = max(session.startedAt, endedAt)
            }
            if !sessions.isEmpty {
                try context.save()
            }
            UserDefaults.standard.removeObject(forKey: activeRecordingSessionIDKey)
        } catch {
            // Intents call this from a background launch path where surfacing UI is
            // impossible; leave the persisted off switch below as the source of truth.
        }
    }

    // MARK: - Data Storage

    /// Repairs stale/duplicate visits so the app has at most one current visit
    /// and Core Location jitter does not leave stacked pins for the same stop.
    @discardableResult
    func reconcileOpenVisits(referenceDate: Date = Date()) -> Int {
        guard let context = modelContext else { return 0 }

        do {
            var descriptor = FetchDescriptor<Visit>()
            descriptor.sortBy = [SortDescriptor(\.arrivedAt, order: .forward)]
            let visits = try context.fetch(descriptor)
            let openVisits = visits.filter { $0.departedAt == nil && $0.source == .automatic }
            let latestOpenVisit = openVisits.max { $0.arrivedAt < $1.arrivedAt }
            var changedCount = 0

            for visit in openVisits where openVisits.count > 1 && visit.id != latestOpenVisit?.id {
                visit.departedAt = inferredDepartureDate(
                    for: visit,
                    in: visits,
                    fallback: referenceDate
                )
                visit.countryDepartureIsInferred = true
                changedCount += 1
            }

            changedCount += mergeDuplicateVisits(
                in: visits,
                context: context,
                referenceDate: referenceDate
            )

            if changedCount > 0 {
                try context.save()
                syncDataToWatch()
            }

            return changedCount
        } catch {
            lastError = String(localized: "Failed to reconcile visits: \(error.localizedDescription)")
            return 0
        }
    }

    private func saveVisit(_ clVisit: CLVisit) {
        guard let context = modelContext else { return }

        let arrivalDate = clVisit.arrivalDate
        let departureDate = clVisit.departureDate
        let hasArrival = arrivalDate != Date.distantPast
        let hasDeparture = departureDate != Date.distantFuture
        var visitToGeocodeID: UUID?

        do {
            if hasDeparture,
               let existingVisit = try matchingOpenVisit(for: clVisit, in: context) {
                // Departure update for an existing open visit.
                existingVisit.departedAt = max(existingVisit.arrivedAt, departureDate)
                existingVisit.countryDepartureIsInferred = false
                try applySavedPlaceNameIfNeeded(to: existingVisit, in: context)
            } else if hasArrival {
                // A new arrival means any previous open visit is no longer current,
                // even if iOS never delivered a matching departure callback.
                _ = try closeOpenVisits(before: arrivalDate, in: context)

                let visit = Visit(
                    latitude: clVisit.coordinate.latitude,
                    longitude: clVisit.coordinate.longitude,
                    arrivedAt: arrivalDate
                )

                if hasDeparture {
                    visit.departedAt = max(arrivalDate, departureDate)
                }

                if let duplicateVisit = try matchingDuplicateVisit(
                    for: visit,
                    in: context,
                    referenceDate: Date()
                ) {
                    mergeVisit(visit, into: duplicateVisit)
                    try applySavedPlaceNameIfNeeded(to: duplicateVisit, in: context)
                    visitToGeocodeID = duplicateVisit.id
                } else {
                    try applySavedPlaceNameIfNeeded(to: visit, in: context)
                    context.insert(visit)
                    visitToGeocodeID = visit.id
                }
            }

            try context.save()
            reconcileOpenVisits()
            syncDataToWatch()

            if let visitToGeocodeID,
               let visitToGeocode = try visit(withID: visitToGeocodeID, in: context),
               !visitToGeocode.geocodingCompleted {
                Task {
                    await geocodeVisit(visitToGeocode)
                }
            }
        } catch {
            lastError = String(localized: "Failed to save visit: \(error.localizedDescription)")
        }
    }

    private func matchingOpenVisit(for clVisit: CLVisit, in context: ModelContext) throws -> Visit? {
        let predicate = #Predicate<Visit> { visit in
            visit.departedAt == nil
        }
        var descriptor = FetchDescriptor<Visit>(predicate: predicate)
        descriptor.sortBy = [SortDescriptor(\.arrivedAt, order: .reverse)]
        let openVisits = try context.fetch(descriptor)
        guard !openVisits.isEmpty else { return nil }

        let clVisitLocation = CLLocation(
            latitude: clVisit.coordinate.latitude,
            longitude: clVisit.coordinate.longitude
        )

        return openVisits
            .compactMap { visit -> (visit: Visit, score: Double)? in
                guard visit.source == .automatic else { return nil }
                guard let score = matchScore(
                    for: visit,
                    clVisit: clVisit,
                    clVisitLocation: clVisitLocation
                ) else { return nil }
                return (visit, score)
            }
            .min { $0.score < $1.score }?
            .visit
    }

    private func matchScore(
        for visit: Visit,
        clVisit: CLVisit,
        clVisitLocation: CLLocation
    ) -> Double? {
        let candidateLocations = [
            CLLocation(latitude: visit.latitude, longitude: visit.longitude),
            visit.detectedCoordinate.map { CLLocation(latitude: $0.latitude, longitude: $0.longitude) },
            visit.originalCoordinate.map { CLLocation(latitude: $0.latitude, longitude: $0.longitude) }
        ].compactMap { $0 }

        let distance = candidateLocations
            .map { $0.distance(from: clVisitLocation) }
            .min() ?? .greatestFiniteMagnitude
        let isNearby = distance <= visitCoordinateMatchThresholdMeters

        let hasArrival = clVisit.arrivalDate != Date.distantPast
        let arrivalDelta = hasArrival
            ? abs(visit.arrivedAt.timeIntervalSince(clVisit.arrivalDate))
            : .greatestFiniteMagnitude
        let isSameArrival = arrivalDelta <= visitArrivalMatchTolerance

        guard isNearby || isSameArrival else { return nil }

        let normalizedArrivalScore: Double
        if arrivalDelta.isFinite {
            normalizedArrivalScore = min(arrivalDelta / visitArrivalMatchTolerance, 1)
        } else {
            normalizedArrivalScore = 1
        }

        return distance + (normalizedArrivalScore * visitCoordinateMatchThresholdMeters)
    }

    private func matchingDuplicateVisit(
        for candidate: Visit,
        in context: ModelContext,
        referenceDate: Date
    ) throws -> Visit? {
        var descriptor = FetchDescriptor<Visit>()
        descriptor.sortBy = [SortDescriptor(\.arrivedAt, order: .forward)]
        let visits = try context.fetch(descriptor)

        return visits.first { visit in
            shouldMergeDuplicateVisits(visit, candidate, referenceDate: referenceDate)
        }
    }

    private func mergeDuplicateVisits(
        in chronologicalVisits: [Visit],
        context: ModelContext,
        referenceDate: Date
    ) -> Int {
        var keptVisits: [Visit] = []
        var deletedVisitIDs = Set<UUID>()
        var mergeCount = 0

        for visit in chronologicalVisits where !deletedVisitIDs.contains(visit.id) {
            if let duplicateVisit = keptVisits.first(where: {
                shouldMergeDuplicateVisits($0, visit, referenceDate: referenceDate)
            }) {
                mergeVisit(visit, into: duplicateVisit)
                context.delete(visit)
                deletedVisitIDs.insert(visit.id)
                mergeCount += 1
            } else {
                keptVisits.append(visit)
            }
        }

        return mergeCount
    }

    private func shouldMergeDuplicateVisits(
        _ lhs: Visit,
        _ rhs: Visit,
        referenceDate: Date
    ) -> Bool {
        guard lhs.id != rhs.id, lhs.source == .automatic, rhs.source == .automatic else { return false }

        let lhsLocation = CLLocation(latitude: lhs.latitude, longitude: lhs.longitude)
        let rhsLocation = CLLocation(latitude: rhs.latitude, longitude: rhs.longitude)
        guard lhsLocation.distance(from: rhsLocation) <= duplicateVisitMergeThresholdMeters else {
            return false
        }

        let arrivalDelta = abs(lhs.arrivedAt.timeIntervalSince(rhs.arrivedAt))
        if arrivalDelta <= visitArrivalMatchTolerance {
            return true
        }

        return timeGapBetween(lhs, rhs, referenceDate: referenceDate) <= duplicateVisitMergeGap
    }

    private func timeGapBetween(_ lhs: Visit, _ rhs: Visit, referenceDate: Date) -> TimeInterval {
        let lhsEnd = lhs.departedAt ?? referenceDate
        let rhsEnd = rhs.departedAt ?? referenceDate

        if lhsEnd < rhs.arrivedAt {
            return rhs.arrivedAt.timeIntervalSince(lhsEnd)
        }

        if rhsEnd < lhs.arrivedAt {
            return lhs.arrivedAt.timeIntervalSince(rhsEnd)
        }

        return 0
    }

    private func mergeVisit(_ source: Visit, into target: Visit) {
        target.arrivedAt = min(target.arrivedAt, source.arrivedAt)

        switch (target.departedAt, source.departedAt) {
        case (nil, _), (_, nil):
            target.departedAt = nil
        case let (targetDeparture?, sourceDeparture?):
            target.departedAt = max(targetDeparture, sourceDeparture)
        }

        target.countryDepartureIsInferred = target.countryDepartureIsInferred != false || source.countryDepartureIsInferred != false
        target.customName = target.customName ?? source.customName
        target.locationName = target.locationName ?? source.locationName
        target.address = target.address ?? source.address
        target.geocodingCompleted = target.geocodingCompleted || source.geocodingCompleted

        if let sourceNotes = source.notes?.trimmingCharacters(in: .whitespacesAndNewlines),
           !sourceNotes.isEmpty {
            if let targetNotes = target.notes?.trimmingCharacters(in: .whitespacesAndNewlines),
               !targetNotes.isEmpty,
               targetNotes != sourceNotes {
                target.notes = "\(targetNotes)\n\n\(sourceNotes)"
            } else if target.notes == nil || target.notes?.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty == true {
                target.notes = source.notes
            }
        }
    }

    private func closeOpenVisits(
        before arrivalDate: Date,
        in context: ModelContext,
        excluding excludedID: UUID? = nil
    ) throws -> Int {
        let predicate = #Predicate<Visit> { visit in
            visit.departedAt == nil
        }
        let descriptor = FetchDescriptor<Visit>(predicate: predicate)
        let openVisits = try context.fetch(descriptor)
        var closedCount = 0

        for visit in openVisits where visit.source == .automatic && visit.id != excludedID && visit.arrivedAt <= arrivalDate {
            visit.departedAt = max(visit.arrivedAt, arrivalDate)
            visit.countryDepartureIsInferred = true
            closedCount += 1
        }

        return closedCount
    }

    private func inferredDepartureDate(
        for visit: Visit,
        in chronologicalVisits: [Visit],
        fallback: Date
    ) -> Date {
        let nextArrival = chronologicalVisits.first { candidate in
            candidate.id != visit.id && candidate.arrivedAt > visit.arrivedAt
        }?.arrivedAt

        return max(visit.arrivedAt, nextArrival ?? fallback)
    }

    private func visit(withID id: UUID, in context: ModelContext) throws -> Visit? {
        let predicate = #Predicate<Visit> { visit in
            visit.id == id
        }
        var descriptor = FetchDescriptor<Visit>(predicate: predicate)
        descriptor.fetchLimit = 1
        return try context.fetch(descriptor).first
    }

    @discardableResult
    private func saveLocationPoints(_ locations: [CLLocation]) -> [LocationPoint] {
        guard let context = modelContext else { return [] }

        let validLocations = locations.filter { location in
            location.horizontalAccuracy >= 0 && location.horizontalAccuracy <= 100
        }
        guard !validLocations.isEmpty else { return [] }

        var savedPoints: [LocationPoint] = []
        savedPoints.reserveCapacity(validLocations.count)
        var nextPointBeforeLast = pointBeforeLast
        var nextLastSavedPoint = lastSavedPoint

        for location in validLocations {
            let point = LocationPoint(from: location)

            // Flag obvious teleports: implied speed from the last saved point exceeds
            // what a human moves (~40 m/s / ~90 mph). Cheap check, catches end-of-trail spikes.
            if let last = nextLastSavedPoint {
                let dt = point.timestamp.timeIntervalSince(last.timestamp)
                if dt > 0 {
                    let impliedSpeed = last.distance(to: point) / dt
                    if impliedSpeed > 40 {
                        point.isOutlier = true
                    }
                }
            }

            context.insert(point)

            // Re-evaluate the previously saved point now that we have a point after it.
            // An out-and-back spike looks like: prev→last jumps far, last→new returns near prev.
            if let before = nextPointBeforeLast, let last = nextLastSavedPoint, !last.isOutlier {
                let spikeOut = before.distance(to: last)
                let spikeBack = last.distance(to: point)
                let endpointGap = before.distance(to: point)
                if spikeOut > 100 && spikeBack > 100 && endpointGap < 30 {
                    last.isOutlier = true
                }
            }

            savedPoints.append(point)
            nextPointBeforeLast = nextLastSavedPoint
            nextLastSavedPoint = point
        }

        do {
            try context.save()
            pointBeforeLast = nextPointBeforeLast
            lastSavedPoint = nextLastSavedPoint
            // Notify observers with the full Core Location batch so UI caches can append every point.
            lastSavedLocationPoint = savedPoints.last
            lastSavedLocationPoints = savedPoints
            locationPointsSavedCount += savedPoints.count
            // Sync to watch widget (throttled by WidgetKit)
            syncDataToWatch()
            return savedPoints
        } catch {
            lastError = String(localized: "Failed to save location points: \(error.localizedDescription)")
            return []
        }
    }

    private func applySavedPlaceNameIfNeeded(to visit: Visit, in context: ModelContext) throws {
        guard visit.canReceiveAutomaticGeocodeUpdates else { return }

        let coordinate = visit.coordinate
        var descriptor = FetchDescriptor<SavedPlace>()
        descriptor.sortBy = [SortDescriptor(\.updatedAt, order: .reverse)]
        let savedPlaces = try context.fetch(descriptor)

        guard let match = savedPlaces
            .compactMap({ place -> (place: SavedPlace, distance: CLLocationDistance)? in
                let distance = place.distanceMeters(to: coordinate)
                guard distance <= place.radiusMeters else { return nil }
                return (place, distance)
            })
            .min(by: { $0.distance < $1.distance }) else {
            return
        }

        visit.locationName = match.place.name
        visit.address = match.place.address
        visit.detectedLocationName = match.place.name
        visit.detectedAddress = match.place.address
        visit.placeSource = .userEntered
        visit.placeDistanceMeters = match.distance
        visit.confirmationStatus = .confirmed
        visit.confirmedAt = visit.confirmedAt ?? Date()
        visit.updatedAt = Date()
        visit.geocodingCompleted = true
    }

    // MARK: - Geocoding

    private func geocodeVisit(_ visit: Visit) async {
        guard !visit.geocodingCompleted else { return }
        guard allowNetworkGeocoding else { return }

        let location = CLLocation(latitude: visit.latitude, longitude: visit.longitude)

        do {
            let result = try await geocodingService.reverseGeocode(location: location)
            guard !visit.isDeleted, visit.modelContext != nil,
                  visit.latitude == location.coordinate.latitude,
                  visit.longitude == location.coordinate.longitude else { return }
            if visit.canReceiveAutomaticGeocodeUpdates {
                visit.locationName = result.name
                visit.address = result.address
                visit.detectedLocationName = result.name
                visit.detectedAddress = result.address
                visit.placeSource = .coreLocationGeocode
            }
            visit.countryCode = result.timeZoneIdentifier == nil ? nil : result.countryCode
            visit.countryTimeZoneIdentifier = result.timeZoneIdentifier
            visit.countryResolutionAttemptedAt = Date()
            visit.geocodingCompleted = true

            try modelContext?.save()
        } catch {
            // Mark as completed even on error to avoid retrying too often
            visit.geocodingCompleted = true
            try? modelContext?.save()
        }
    }

    func retryGeocoding(for visit: Visit) async {
        visit.geocodingCompleted = false
        await geocodeVisit(visit)
    }

    // MARK: - Live Activity Geocoding

    private func geocodeForLiveActivity(location: CLLocation) async {
        guard allowNetworkGeocoding else { return }

        // Throttle geocoding - only geocode if we've moved more than 50 meters
        if let lastLocation = lastGeocodedLocation {
            let distance = location.distance(from: lastLocation)
            guard distance > 50 || !Calendar.current.isDate(location.timestamp, inSameDayAs: lastLocation.timestamp) else { return }
        }

        lastGeocodedLocation = location
        let resolvedPoint = lastSavedPoint.flatMap { point in
            point.latitude == location.coordinate.latitude &&
            point.longitude == location.coordinate.longitude &&
            point.timestamp == location.timestamp ? point : nil
        }

        do {
            let result = try await geocodingService.reverseGeocode(location: location)
            currentLocationName = result.name ?? result.address
            currentAddress = result.address
            // Associate only with the actual fix that was resolved, never a later GPS update.
            if let point = resolvedPoint, !point.isDeleted, point.modelContext != nil, !point.isOutlier {
                point.countryCode = result.timeZoneIdentifier == nil ? nil : result.countryCode
                point.countryTimeZoneIdentifier = result.timeZoneIdentifier
                point.countryResolutionAttemptedAt = Date()
                try modelContext?.save()
            }
        } catch {
            // Keep the previous location name on error
        }
    }

    // MARK: - Watch Widget Data Sync

    /// Syncs current tracking state and today's stats to the shared App Group for the watch widget
    func syncDataToWatch() {
        guard let context = modelContext else { return }

        // Get today's date range
        let calendar = Calendar.current
        let startOfToday = calendar.startOfDay(for: Date())
        let endOfToday = calendar.date(byAdding: .day, value: 1, to: startOfToday)!

        // Count today's records without loading every point into memory on each GPS update.
        let visitsPredicate = #Predicate<Visit> { visit in
            visit.arrivedAt >= startOfToday && visit.arrivedAt < endOfToday
        }
        let visitsDescriptor = FetchDescriptor<Visit>(predicate: visitsPredicate)
        let todayVisitsCount = (try? context.fetchCount(visitsDescriptor)) ?? 0

        let pointsPredicate = #Predicate<LocationPoint> { point in
            point.timestamp >= startOfToday && point.timestamp < endOfToday
        }
        let pointsDescriptor = FetchDescriptor<LocationPoint>(predicate: pointsPredicate)
        let todayPointsCount = (try? context.fetchCount(pointsDescriptor)) ?? 0
        let totalDistance = dailyDistanceTracker.distance(for: Date())

        let sharedData = SharedLocationData(
            isTrackingEnabled: isTrackingEnabled,
            currentLocationName: currentLocationName,
            currentAddress: currentAddress,
            lastLatitude: currentLocation?.coordinate.latitude,
            lastLongitude: currentLocation?.coordinate.longitude,
            lastUpdateTime: Date(),
            todayVisitsCount: todayVisitsCount,
            todayDistanceMeters: totalDistance,
            todayPointsCount: todayPointsCount,
            trackingStartTime: trackingStartTime,
            stopAfterHours: stopAfterHours > 0 ? stopAfterHours : nil,
            usesMetricDistanceUnits: usesMetricDistanceUnits
        )

        sharedData.save()
        WidgetCenter.shared.reloadAllTimelines()
    }

    func refreshDistanceUnitPreference() {
        syncDataToWatch()
        liveActivityManager.refreshDistanceUnitPreference()
    }
}

// MARK: - CLLocationManagerDelegate

extension LocationManager: CLLocationManagerDelegate {
    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        Task { @MainActor in
            authorizationStatus = manager.authorizationStatus
            if hasLocationPermission && pendingManualLocationRequest {
                requestCurrentLocationForManualVisit()
            }

            if hasLocationPermission && isTrackingEnabled {
                // Reattach the CL APIs once permission lands
                startTracking()
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didVisit visit: CLVisit) {
        Task { @MainActor in
            guard StoreManager.shared.canTrack else { return }
            saveVisit(visit)
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        Task { @MainActor in
            guard let location = locations.last else { return }
            currentLocation = location

            guard StoreManager.shared.canTrack else { return }

            // Record every delivered location for daily distance history (stats only).
            for deliveredLocation in locations {
                dailyDistanceTracker.recordLocation(deliveredLocation)
            }

            guard isTrackingEnabled, StoreManager.shared.canTrack else { return }

            saveLocationPoints(locations)

            // Geocode/update Live Activity only for the latest location to avoid doing
            // network/UI work for every point in a Core Location batch.
            await geocodeForLiveActivity(location: location)

            if isLiveActivityEnabled {
                liveActivityManager.updateActivity(
                    location: location,
                    locationName: currentLocationName,
                    remainingSeconds: liveActivityRemainingSeconds
                )
            }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        Task { @MainActor in
            if let clError = error as? CLError {
                switch clError.code {
                case .denied:
                    lastError = String(localized: "Location access denied")
                case .network:
                    lastError = String(localized: "Network error")
                default:
                    lastError = error.localizedDescription
                }
            } else {
                lastError = error.localizedDescription
            }
        }
    }
}
