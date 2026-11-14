import CoreLocation
import Foundation
import WidgetKit
import StoreKit

/// A small, watch-native location tracker so the watch app can run without the
/// iPhone companion app being installed or launched.
@MainActor
final class WatchLocationTracker: NSObject, ObservableObject {
    @Published private(set) var locationData: SharedLocationData
    @Published private(set) var authorizationStatus: CLAuthorizationStatus
    @Published var lastErrorMessage: String?

    private let locationManager = CLLocationManager()
    private let syncClient = WatchLocationSyncClient.shared
    private let defaults: UserDefaults
    private var persistedState: PersistedWatchTrackingState
    private var canTrack = false
    private var accessExpiration: Date?
    private var entitlementUpdates: Task<Void, Never>?
    private var accessTimer: Timer?

    private let minimumMovementDistance: CLLocationDistance = 10
    private let visitDistanceThreshold: CLLocationDistance = 100
    private let acceptableHorizontalAccuracy: CLLocationAccuracy = 100

    override init() {
        let defaults = UserDefaults(suiteName: SharedLocationData.appGroupIdentifier) ?? .standard
        self.defaults = defaults
        self.locationData = SharedLocationData.load() ?? .empty
        self.persistedState = Self.loadPersistedState(from: defaults)
        self.authorizationStatus = locationManager.authorizationStatus

        super.init()

        configureLocationManager()
        SharedRecordingControl.shared.apply = { [weak self] intent in
            self?.applySharedRecordingIntent(intent) ?? false
        }
        syncClient.activate()

        // Persist a cohort marker before writing any new-install state.
        if defaults.object(forKey: PaidTrackingMigration.legacyCohortKey) == nil {
            defaults.set(defaults.data(forKey: Self.persistedStateKey) != nil || authorizationStatus != .notDetermined,
                         forKey: PaidTrackingMigration.legacyCohortKey)
        }
        rollOverIfNeeded()
        Task { await refreshTrackingAccess() }
        entitlementUpdates = Task { [weak self] in
            for await _ in Transaction.updates { await self?.refreshTrackingAccess() }
        }
        syncClient.syncPending(force: true)
    }

    deinit {
        entitlementUpdates?.cancel()
        accessTimer?.invalidate()
    }

    var canUseLocation: Bool {
        authorizationStatus == .authorizedWhenInUse || authorizationStatus == .authorizedAlways
    }

    var needsLocationPermission: Bool {
        authorizationStatus == .notDetermined
    }

    var authorizationMessage: String? {
        switch authorizationStatus {
        case .notDetermined:
            return String(localized: "Enable location to track directly on Apple Watch.")
        case .restricted:
            return String(localized: "Location is restricted on this Apple Watch.")
        case .denied:
            return String(localized: "Location is denied. Enable it in Settings to track from Apple Watch.")
        case .authorizedAlways, .authorizedWhenInUse:
            return nil
        @unknown default:
            return String(localized: "Location permission is unavailable.")
        }
    }

    func refresh() {
        Task { await refreshTrackingAccess() }
        authorizationStatus = locationManager.authorizationStatus
        rollOverIfNeeded()
    }

    func requestLocationPermission() {
        lastErrorMessage = nil
        locationManager.requestWhenInUseAuthorization()
    }

    func toggleTracking() {
        locationData.isTrackingEnabled ? stopTracking() : startTracking()
    }

    private func applySharedRecordingIntent(_ intent: SharedRecordingIntent) -> Bool {
        if !intent.isRecording {
            stopTracking()
            return true
        }
        guard canTrack, canUseLocation, accessExpiration.map({ $0 > Date() }) ?? true else { return false }
        syncClient.adoptSession(id: intent.sessionID, startedAt: intent.startedAt, changedAt: intent.changedAt)
        locationData.trackingStartTime = intent.startedAt
        startTracking()
        return locationData.isTrackingEnabled
    }

    func startTracking() {
        guard canTrack, accessExpiration.map({ $0 > Date() }) ?? true else {
            lastErrorMessage = String(localized: "Open Whereday on iPhone to choose or restore a plan.")
            return
        }
        lastErrorMessage = nil
        rollOverIfNeeded()

        guard canUseLocation else {
            requestLocationPermission()
            return
        }

        if locationData.trackingStartTime == nil {
            locationData.trackingStartTime = Date()
            locationData.routeDistanceMeters = 0
        }
        let wasTracking = locationData.isTrackingEnabled
        locationData.isTrackingEnabled = true
        let startedAt = locationData.trackingStartTime ?? Date()
        let id = syncClient.ensureActiveSession(startedAt: startedAt)
        saveLocationData()
        startLocationUpdates()
        if !wasTracking {
            SharedRecordingControl.shared.publish(isRecording: true, sessionID: id, startedAt: startedAt)
        }
    }

    func stopTracking() {
        let control = SharedRecordingControl.shared
        let wasTracking = locationData.isTrackingEnabled
        let id = syncClient.activeSessionID ?? control.intent?.sessionID
        let startedAt = locationData.trackingStartTime ?? control.intent?.startedAt ?? Date()
        let endedAt = control.isApplying ? (control.intent?.changedAt ?? Date()) : Date()
        locationData.isTrackingEnabled = false
        locationData.trackingStartTime = nil
        locationData.stopAfterHours = nil
        locationData.routeDistanceMeters = nil
        syncClient.endActiveSession(endedAt: endedAt)
        saveLocationData()
        locationManager.stopUpdatingLocation()
        if (wasTracking || control.intent?.isRecording == true), let id {
            control.publish(isRecording: false, sessionID: id, startedAt: startedAt, now: endedAt)
        }
    }

    func resetToday() {
        persistedState = PersistedWatchTrackingState(dayStart: Calendar.current.startOfDay(for: Date()))
        locationData.todayVisitsCount = 0
        locationData.todayDistanceMeters = 0
        locationData.todayPointsCount = 0
        locationData.currentLocationName = nil
        locationData.currentAddress = nil
        locationData.lastLatitude = nil
        locationData.lastLongitude = nil
        locationData.lastUpdateTime = nil
        savePersistedState()
        saveLocationData()
    }

    private func configureLocationManager() {
        locationManager.delegate = self
        locationManager.desiredAccuracy = kCLLocationAccuracyBest
        locationManager.distanceFilter = minimumMovementDistance
        locationManager.activityType = .fitness
        locationManager.allowsBackgroundLocationUpdates = true
    }

    @MainActor
    private func refreshTrackingAccess() async {
        var legacy = defaults.bool(forKey: PaidTrackingMigration.legacyCohortKey)
        if !legacy, let result = try? await AppTransaction.shared,
           case .verified(let app) = result,
           PaidTrackingMigration.restoreLegacyAccess(verifiedOriginalVersion: app.originalAppVersion, defaults: defaults) {
            legacy = true
        }
        let lifetimeIDs: Set<String> = ["com.bontecou.isome.lifetime", "com.bontecou.isome.lifetime.individual", "com.bontecou.isome.lifetime.family.upgrade"]
        let subscriptionIDs: Set<String> = ["com.bontecou.isome.individual.weekly", "com.bontecou.isome.individual.yearly", "com.bontecou.isome.family.weekly", "com.bontecou.isome.family.yearly"]
        var lifetime = false
        var expiry: Date?
        for await result in Transaction.currentEntitlements {
            guard case .verified(let transaction) = result,
                  transaction.revocationDate == nil, !transaction.isUpgraded else { continue }
            if lifetimeIDs.contains(transaction.productID) { lifetime = true }
            if subscriptionIDs.contains(transaction.productID), let date = transaction.expirationDate, date > Date() {
                expiry = max(expiry ?? date, date)
            }
        }
        canTrack = legacy || lifetime || expiry != nil
        accessExpiration = legacy || lifetime ? nil : expiry
        accessTimer?.invalidate()
        if let expiration = accessExpiration {
            accessTimer = Timer.scheduledTimer(withTimeInterval: max(1, expiration.timeIntervalSinceNow), repeats: false) { [weak self] _ in
                Task { await self?.refreshTrackingAccess() }
            }
        }
        if !canTrack && locationData.isTrackingEnabled { stopTracking() }
        SharedRecordingControl.shared.flush()
        if canTrack && locationData.isTrackingEnabled && canUseLocation {
            syncClient.ensureActiveSession(startedAt: locationData.trackingStartTime ?? Date())
            startLocationUpdates()
        }
    }

    private func startLocationUpdates() {
        guard canTrack else { return }
        locationManager.startUpdatingLocation()
        locationManager.requestLocation()
    }

    private func handleAuthorizationChange(_ status: CLAuthorizationStatus) {
        authorizationStatus = status
        SharedRecordingControl.shared.flush()

        if canUseLocation, locationData.isTrackingEnabled {
            startLocationUpdates()
        } else if status == .denied || status == .restricted {
            locationManager.stopUpdatingLocation()
        }
    }

    private func handleLocation(_ location: CLLocation) {
        guard locationData.isTrackingEnabled else { return }
        guard canTrack, accessExpiration.map({ $0 > Date() }) ?? true else {
            if locationData.isTrackingEnabled { stopTracking() }
            return
        }
        guard location.horizontalAccuracy >= 0,
              location.horizontalAccuracy <= acceptableHorizontalAccuracy else {
            return
        }

        rollOverIfNeeded(for: location.timestamp)

        locationData.lastLatitude = location.coordinate.latitude
        locationData.lastLongitude = location.coordinate.longitude
        locationData.lastUpdateTime = location.timestamp
        locationData.currentLocationName = formattedCoordinate(for: location)
        locationData.currentAddress = nil
        locationData.usesMetricDistanceUnits = usesMetricDistanceUnits

        if locationData.isTrackingEnabled {
            locationData.todayPointsCount += 1
            accumulateDistance(using: location)
            updateVisitCount(using: location)
            syncClient.recordLocation(location)
        }

        persistedState.lastLatitude = location.coordinate.latitude
        persistedState.lastLongitude = location.coordinate.longitude
        persistedState.lastTimestamp = location.timestamp

        savePersistedState()
        saveLocationData()
    }

    private func accumulateDistance(using location: CLLocation) {
        guard let previous = persistedState.lastLocation else {
            return
        }

        let distance = location.distance(from: previous)
        guard distance >= minimumMovementDistance else {
            return
        }

        locationData.todayDistanceMeters += distance
        // Only movement after the route began counts toward the route.
        if let start = locationData.trackingStartTime,
           let previousTime = persistedState.lastTimestamp, previousTime >= start {
            locationData.routeDistanceMeters = (locationData.routeDistanceMeters ?? 0) + distance
        }
    }

    /// The latest accurate fix, when recent enough to stand for "here" (for Mark).
    var recentFix: (latitude: Double, longitude: Double)? {
        guard let latitude = locationData.lastLatitude,
              let longitude = locationData.lastLongitude,
              let time = locationData.lastUpdateTime,
              abs(time.timeIntervalSinceNow) <= 120 else { return nil }
        return (latitude, longitude)
    }

    private func updateVisitCount(using location: CLLocation) {
        guard let countedVisitLocation = persistedState.lastCountedVisitLocation else {
            locationData.todayVisitsCount = max(locationData.todayVisitsCount, 1)
            persistedState.lastCountedVisitLatitude = location.coordinate.latitude
            persistedState.lastCountedVisitLongitude = location.coordinate.longitude
            return
        }

        guard location.distance(from: countedVisitLocation) >= visitDistanceThreshold else {
            return
        }

        locationData.todayVisitsCount += 1
        persistedState.lastCountedVisitLatitude = location.coordinate.latitude
        persistedState.lastCountedVisitLongitude = location.coordinate.longitude
    }

    private func rollOverIfNeeded(for date: Date = Date()) {
        let calendar = Calendar.current
        guard !calendar.isDate(persistedState.dayStart, inSameDayAs: date) else {
            return
        }

        let wasTracking = locationData.isTrackingEnabled
        let trackingStartTime = locationData.trackingStartTime
        let stopAfterHours = locationData.stopAfterHours
        let routeDistanceMeters = locationData.routeDistanceMeters

        persistedState = PersistedWatchTrackingState(dayStart: calendar.startOfDay(for: date))
        locationData = .empty
        locationData.isTrackingEnabled = wasTracking
        locationData.trackingStartTime = trackingStartTime
        locationData.stopAfterHours = stopAfterHours
        // A route that runs past midnight keeps its own distance.
        locationData.routeDistanceMeters = routeDistanceMeters
        locationData.usesMetricDistanceUnits = usesMetricDistanceUnits
        savePersistedState()
        saveLocationData()
    }

    private func saveLocationData() {
        locationData.save()
        WidgetCenter.shared.reloadAllTimelines()
    }

    private func savePersistedState() {
        guard let encoded = try? JSONEncoder().encode(persistedState) else { return }
        defaults.set(encoded, forKey: Self.persistedStateKey)
    }

    private var usesMetricDistanceUnits: Bool {
        Locale.current.measurementSystem != .us
    }

    private func formattedCoordinate(for location: CLLocation) -> String {
        String(
            format: "%.4f, %.4f",
            locale: Locale(identifier: "en_US_POSIX"),
            location.coordinate.latitude,
            location.coordinate.longitude
        )
    }

    private static let persistedStateKey = "watchLocationTrackingState"

    private static func loadPersistedState(from defaults: UserDefaults) -> PersistedWatchTrackingState {
        guard let data = defaults.data(forKey: persistedStateKey),
              let decoded = try? JSONDecoder().decode(PersistedWatchTrackingState.self, from: data) else {
            return PersistedWatchTrackingState(dayStart: Calendar.current.startOfDay(for: Date()))
        }
        return decoded
    }
}

extension WatchLocationTracker: CLLocationManagerDelegate {
    nonisolated func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        DispatchQueue.main.async { [weak self] in
            self?.handleAuthorizationChange(manager.authorizationStatus)
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        DispatchQueue.main.async { [weak self] in
            locations.forEach { self?.handleLocation($0) }
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) {
        DispatchQueue.main.async { [weak self] in
            if let clError = error as? CLError, clError.code == .locationUnknown {
                return
            }
            self?.lastErrorMessage = error.localizedDescription
        }
    }
}

private struct PersistedWatchTrackingState: Codable {
    var dayStart: Date
    var lastLatitude: Double?
    var lastLongitude: Double?
    var lastTimestamp: Date?
    var lastCountedVisitLatitude: Double?
    var lastCountedVisitLongitude: Double?

    var lastLocation: CLLocation? {
        guard let lastLatitude, let lastLongitude else { return nil }
        return CLLocation(latitude: lastLatitude, longitude: lastLongitude)
    }

    var lastCountedVisitLocation: CLLocation? {
        guard let lastCountedVisitLatitude, let lastCountedVisitLongitude else { return nil }
        return CLLocation(latitude: lastCountedVisitLatitude, longitude: lastCountedVisitLongitude)
    }
}
