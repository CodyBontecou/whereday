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
final class LocationManager: NSObject, ObservableObject, SmartStartTracking {
    /// Latest live instance, used by App Intents so Siri/Shortcuts can drive tracking.
    static weak var shared: LocationManager?

    private let locationManager: CLLocationManager
    private let monitoring: any TrackingLocationMonitoring
    private let trackingAccess: @MainActor () -> Bool
    private var autoStartCoordinator: AutoStartCoordinator?
    private var lowPowerMonitorsRunning = false
    @Published private(set) var ambientMonitoringActive = false
    private var modelContext: ModelContext?

    // Published state
    @Published var authorizationStatus: CLAuthorizationStatus = .notDetermined
    /// Approximate Location (Precise Location off) still records, but stops and routes become rough.
    @Published var accuracyAuthorization: CLAccuracyAuthorization = .fullAccuracy
    @Published var isTrackingEnabled: Bool = false
    @Published var trackingStartTime: Date?
    @Published private(set) var trackingStartSource: TrackingStartSource?
    private var smartStartState: SmartStartStateStore { SmartStartStateStore(defaults: defaults) }
    @Published var stopAfterHours: Double = 0.0
    @Published var distanceFilter: Double = 5.0
    /// Distance, time, or both. Only affects route points; visits come from iOS visit monitoring.
    @Published var routeSamplingMode: RouteSamplingMode = .distance
    /// Seconds between route points in the time-based modes.
    @Published var routeSampleInterval: TimeInterval = RouteSamplingPolicy.defaultInterval
    @Published var currentLocation: CLLocation?
    @Published var lastError: String?

    static let routeSamplingModeKey = "routeSamplingMode"
    static let routeSampleIntervalKey = "routeSampleInterval"

    private var routeSampler = RouteSampler(policy: RouteSamplingPolicy(
        mode: .distance,
        distanceFilter: RouteSamplingPolicy.defaultDistanceFilter,
        interval: RouteSamplingPolicy.defaultInterval
    ))
    /// Brings the GPS back up shortly before a time-based sample is due. Timers fire in the
    /// background here because active location updates keep the app running.
    private var routeSamplingWakeTimer: Timer?
    /// A one-off "where am I" request (manual visit) keeps the GPS up until it is answered.
    private var preciseFixRequestedAt: Date?

    var routeSamplingPolicy: RouteSamplingPolicy {
        RouteSamplingPolicy(mode: routeSamplingMode, distanceFilter: distanceFilter, interval: routeSampleInterval)
    }

    // Safety-net auto-stop timer (only runs when stopAfterHours > 0)
    private let stopAfterScheduler: any StopAfterScheduling
    private var cancelStopAfterTimer: (() -> Void)?
    private var stopAfterGeneration = UUID()
    private let recordDistanceLocation: @MainActor (CLLocation) -> Void
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
            guard let rawValue = defaults.string(forKey: Self.activeRecordingSessionIDKey) else {
                return nil
            }
            return UUID(uuidString: rawValue)
        }
        set {
            if let newValue {
                defaults.set(newValue.uuidString, forKey: Self.activeRecordingSessionIDKey)
            } else {
                defaults.removeObject(forKey: Self.activeRecordingSessionIDKey)
            }
        }
    }

    /// Drops the persisted active-session pointer, e.g. after Erase All Data deleted the session it named.
    func forgetActiveRecordingSession() {
        activeRecordingSessionID = nil
    }

    private var usesMetricDistanceUnits: Bool {
        let key = "usesMetricDistanceUnits"
        if defaults.object(forKey: key) == nil {
            return true
        }
        return defaults.bool(forKey: key)
    }

    private var allowNetworkGeocoding: Bool {
        let key = "allowNetworkGeocoding"
        if defaults.object(forKey: key) == nil {
            return true
        }
        return defaults.bool(forKey: key)
    }

    /// Where tracking state and preferences persist. The app uses `.standard`; tests pass a suite of their own
    /// so they never touch the host app's `isTrackingEnabled` or `activeRecordingSessionID`.
    private let defaults: UserDefaults

    /// - Parameters:
    ///   - defaults: Storage for `isTrackingEnabled`, `activeRecordingSessionID`, `stopAfterHours`,
    ///     `distanceFilter`, `isLiveActivityEnabled`, and the preferences this manager reads.
    ///   - registersAsShared: Whether this instance becomes `LocationManager.shared`. Tests pass `false`
    ///     so a test manager never replaces the host app's.
    init(defaults: UserDefaults = .standard, registersAsShared: Bool = true,
         monitoring: (any TrackingLocationMonitoring)? = nil,
         trackingAccess: (@MainActor () -> Bool)? = nil,
         motionDetector: (any ActivityDetecting)? = nil,
         startsSmartStartRuntime: Bool = true,
         stopAfterScheduler: (any StopAfterScheduling)? = nil,
         recordDistanceLocation: (@MainActor (CLLocation) -> Void)? = nil) {
        self.stopAfterScheduler = stopAfterScheduler ?? StopAfterTimerScheduler()
        self.recordDistanceLocation = recordDistanceLocation ?? { DailyDistanceTracker.shared.recordLocation($0) }
        let trackingAccess: @MainActor () -> Bool = trackingAccess ?? { StoreManager.shared.canTrack }
        let nativeManager = CLLocationManager()
        self.locationManager = nativeManager
        self.monitoring = monitoring ?? CoreLocationTrackingMonitor(manager: nativeManager)
        self.trackingAccess = trackingAccess
        self.defaults = defaults
        super.init()
        if registersAsShared {
            // Until central boot is unified, superseding a shared manager must
            // not leave its motion stream running or able to restart on foreground.
            Self.shared?.autoStartCoordinator?.shutdown()
            Self.shared = self
            SharedRecordingControl.shared.apply = { [weak self] intent in
                self?.applySharedRecordingIntent(intent) ?? false
            }
        }
        locationManager.delegate = self
        locationManager.allowsBackgroundLocationUpdates = true
        locationManager.pausesLocationUpdatesAutomatically = false
        locationManager.desiredAccuracy = kCLLocationAccuracyBest

        // Resolve the cohort before any new-install defaults are written.
        let store = StoreManager.shared
        resumeAfterAccessResolution = defaults.bool(forKey: "isTrackingEnabled")
        isTrackingEnabled = resumeAfterAccessResolution && trackingAccess()
        accessObserver = store.objectWillChange.sink { [weak self] in
            Task { @MainActor in
                guard let self else { return }
                if Self.shared === self { SharedRecordingControl.shared.retryApplication() }
                if self.trackingAccess() {
                    if self.resumeAfterAccessResolution && store.hasResolvedEntitlements {
                        self.resumeAfterAccessResolution = false
                        self.startTracking(reason: .relaunch)
                    }
                } else if self.isTrackingEnabled {
                    self.stopTracking(reason: .accessEnded)
                }
                if Self.shared === self { SharedRecordingControl.shared.flush() }
                self.autoStartCoordinator?.refresh()
            }
        }

        // Restore session metadata only for a persisted active session. Legacy sessions
        // receive a start timestamp on reattachment, without inventing past history.
        if resumeAfterAccessResolution {
            trackingStartTime = smartStartState.trackingStartTime
            trackingStartSource = smartStartState.trackingStartSource
        } else {
            smartStartState.endSession(manually: false, at: Date())
        }

        // Restore saved state
        if defaults.object(forKey: "stopAfterHours") != nil {
            stopAfterHours = defaults.double(forKey: "stopAfterHours")
        }
        if defaults.object(forKey: "distanceFilter") != nil {
            distanceFilter = defaults.double(forKey: "distanceFilter")
        }
        if let rawMode = defaults.string(forKey: Self.routeSamplingModeKey),
           let mode = RouteSamplingMode(rawValue: rawMode) {
            routeSamplingMode = mode
        }
        if defaults.object(forKey: Self.routeSampleIntervalKey) != nil {
            let interval = defaults.double(forKey: Self.routeSampleIntervalKey)
            if interval > 0 { routeSampleInterval = interval }
        }
        if defaults.object(forKey: liveActivityEnabledKey) != nil {
            isLiveActivityEnabled = defaults.bool(forKey: liveActivityEnabledKey)
        }
        routeSampler.policy = routeSamplingPolicy
        locationManager.distanceFilter = routeSamplingPolicy.coreLocationDistanceFilter

        authorizationStatus = locationManager.authorizationStatus
        accuracyAuthorization = locationManager.accuracyAuthorization

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
            startTracking(reason: .relaunch)
        }
        if startsSmartStartRuntime {
            autoStartCoordinator = AutoStartCoordinator(
                tracking: self, motion: motionDetector ?? ActivityDetectionManager(),
                state: smartStartState, canTrack: trackingAccess)
            autoStartCoordinator?.refresh()
        }
    }

    func setModelContext(_ context: ModelContext) {
        self.modelContext = context
        reconcileOpenVisits()
        reconcileRecordingSessions()
        if Self.shared === self { SharedRecordingControl.shared.flush() }
    }

    /// No permission prompts or access bypasses from background peer commands.
    func applySharedRecordingIntent(_ intent: SharedRecordingIntent) -> Bool {
        guard let context = modelContext else { return false }
        if !intent.isRecording {
            stopTracking()
            return true
        }
        guard trackingAccess(), hasLocationPermission else { return false }
        do {
            if activeRecordingSessionID != intent.sessionID {
                endActiveRecordingSession(at: intent.changedAt)
            }
            let session = try recordingSession(withID: intent.sessionID, in: context)
                ?? makeRecordingSession(startedAt: intent.startedAt)
            if session.modelContext == nil {
                session.id = intent.sessionID
                context.insert(session)
            }
            session.endedAt = nil
            activeRecordingSessionID = intent.sessionID
            trackingStartTime = intent.startedAt
            try context.save()
            // An accepted peer command owns this session even when a local one was
            // already running. Reattach without the public idempotent-start guard so
            // persisted metadata and the expiry lease move to the peer's session.
            trackingStartSource = .manual
            startTracking(reason: .user, source: .manual, allowsPermissionPrompt: false)
            return isTrackingEnabled
        } catch {
            lastError = error.localizedDescription
            return false
        }
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

    /// Location is allowed, but only as an approximate position.
    var hasReducedAccuracy: Bool {
        hasLocationPermission && accuracyAuthorization == .reducedAccuracy
    }

    // MARK: - Recorded days

    private var cachedFirstRecordedDate: Date?
    private var firstRecordedDateCheckedAt: Date?

    /// The earliest day with a saved point or visit, refreshed at most once a minute.
    func firstRecordedDate(referenceDate: Date = Date()) -> Date? {
        if let checkedAt = firstRecordedDateCheckedAt, referenceDate.timeIntervalSince(checkedAt) < 60, checkedAt <= referenceDate {
            return cachedFirstRecordedDate
        }
        guard let context = modelContext else { return cachedFirstRecordedDate }

        var pointDescriptor = FetchDescriptor<LocationPoint>(sortBy: [SortDescriptor(\.timestamp, order: .forward)])
        pointDescriptor.fetchLimit = 1
        var visitDescriptor = FetchDescriptor<Visit>(sortBy: [SortDescriptor(\.arrivedAt, order: .forward)])
        visitDescriptor.fetchLimit = 1
        let earliestPoint = (try? context.fetch(pointDescriptor))?.first?.timestamp
        let earliestVisit = (try? context.fetch(visitDescriptor))?.first?.arrivedAt
        cachedFirstRecordedDate = [earliestPoint, earliestVisit].compactMap { $0 }.min()
        firstRecordedDateCheckedAt = referenceDate
        return cachedFirstRecordedDate
    }

    /// "Day N": calendar days since the first recorded day, inclusive (the first day is Day 1).
    /// Nil until anything has been recorded.
    func recordedDayNumber(on date: Date = Date()) -> Int? {
        guard let first = firstRecordedDate(referenceDate: date) else { return nil }
        let calendar = Calendar.current
        let days = calendar.dateComponents([.day], from: calendar.startOfDay(for: first), to: calendar.startOfDay(for: date)).day ?? 0
        return max(1, days + 1)
    }

    func requestCurrentLocationForManualVisit() {
        guard hasLocationPermission else {
            pendingManualLocationRequest = true
            requestWhenInUseAuthorization()
            return
        }

        pendingManualLocationRequest = false
        if isTrackingEnabled {
            // Between time-based samples the GPS may be powered down; a manual visit wants a precise fix.
            preciseFixRequestedAt = Date()
            applyRoutePowerLevel()
        }
        locationManager.requestLocation()
    }

    // MARK: - Ambient and active monitoring

    var smartStartSettings: SmartStartSettings { smartStartState.settings }

    /// Settings requires explicit confirmation before enabling. This does not ask
    /// for Location permission; Always must be obtained via the existing UI.
    func updateSmartStartSettings(_ settings: SmartStartSettings) throws {
        if let autoStartCoordinator {
            try autoStartCoordinator.updateSettings(settings)
        } else {
            try smartStartState.saveSettings(settings)
            if settings.isEnabled { startAmbientMonitoring() } else { stopAmbientMonitoring() }
        }
    }

    func startAmbientMonitoring() {
        guard smartStartState.settings.isEnabled, hasAlwaysPermission, trackingAccess() else {
            stopAmbientMonitoring()
            return
        }
        ambientMonitoringActive = true
        reconcileLowPowerMonitoring()
    }

    func stopAmbientMonitoring() {
        ambientMonitoringActive = false
        reconcileLowPowerMonitoring()
    }

    private func reconcileLowPowerMonitoring() {
        // Full manual tracking retains its pre-Smart-Start visit monitoring.
        let needed = ambientMonitoringActive || (isTrackingEnabled && hasLocationPermission && trackingAccess())
        guard needed != lowPowerMonitorsRunning else { return }
        lowPowerMonitorsRunning = needed
        if needed { monitoring.startLowPowerMonitoring() }
        else { monitoring.stopLowPowerMonitoring() }
    }

    private func startActiveLocationUpdates() {
        reconcileLowPowerMonitoring()
        monitoring.startActiveUpdates()
    }

    private func stopActiveLocationUpdates() {
        monitoring.stopActiveUpdates()
        reconcileLowPowerMonitoring()
    }

    /// A policy stop must not masquerade as explicit manual intent/cooldown.
    func stopTrackingAutomatically() {
        guard isTrackingEnabled, trackingStartSource?.isAutomatic == true else { return }
        stopTracking(reason: .autoStop)
    }

    // MARK: - Tracking Control

    /// Why tracking started or stopped, for Diagnostics.
    private enum TrackingChangeReason {
        case user, relaunch, permissionGranted, autoStop, accessEnded
    }

    func startTracking() {
        startTracking(source: .manual, allowsPermissionPrompt: true)
    }

    func startTracking(source: TrackingStartSource, allowsPermissionPrompt: Bool = false) {
        guard !isTrackingEnabled else { return }
        // Automatic callers may never trigger a permission prompt or bypass opt-in.
        if source.isAutomatic {
            guard smartStartState.settings.isEnabled, hasAlwaysPermission else { return }
        }
        startTracking(reason: .user, source: source,
                      allowsPermissionPrompt: allowsPermissionPrompt && !source.isAutomatic)
    }

    private func startTracking(reason: TrackingChangeReason, source: TrackingStartSource = .manual,
                               allowsPermissionPrompt: Bool = true) {
        guard trackingAccess() else {
            NotificationCenter.default.post(name: .trackingAccessRequired, object: nil)
            return
        }
        guard hasLocationPermission else {
            if allowsPermissionPrompt { requestAlwaysAuthorization() }
            return
        }

        let wasTracking = isTrackingEnabled
        isTrackingEnabled = true
        defaults.set(true, forKey: "isTrackingEnabled")
        logTrackingStart(reason: reason, wasTracking: wasTracking)

        if trackingStartTime == nil {
            trackingStartTime = Date()
        }
        if trackingStartSource == nil { trackingStartSource = source }

        if let trackingStartTime, let trackingStartSource {
            // Reattachment preserves both timestamps and source; it is not a new auto-start.
            if smartStartState.trackingStartTime == nil {
                smartStartState.beginSession(source: trackingStartSource, at: trackingStartTime)
            } else {
                smartStartState.saveSessionMetadata(source: trackingStartSource, at: trackingStartTime)
            }
            ensureActiveRecordingSession(startedAt: trackingStartTime)
        }

        if !wasTracking {
            routeSampler.reset()
        }
        routeSampler.policy = routeSamplingPolicy
        locationManager.distanceFilter = routeSamplingPolicy.coreLocationDistanceFilter
        applyRoutePowerLevel()
        startActiveLocationUpdates()

        if isLiveActivityEnabled {
            liveActivityManager.startActivity(autoStopSeconds: liveActivityRemainingSeconds)
        }

        scheduleStopTrackingTimer()
        syncDataToWatch()
        if isTrackingEnabled, !wasTracking, reason != .relaunch, Self.shared === self, let id = activeRecordingSessionID {
            SharedRecordingControl.shared.publish(isRecording: true, sessionID: id,
                startedAt: trackingStartTime ?? Date())
        }
    }

    func stopTracking() {
        stopTracking(reason: .user)
    }

    private func stopTracking(reason: TrackingChangeReason) {
        resumeAfterAccessResolution = false
        let control = SharedRecordingControl.shared
        let endedAt = control.isApplying && control.intent?.isRecording == false
            ? (control.intent?.changedAt ?? Date()) : Date()
        let sharedSessionID = activeRecordingSessionID ?? control.intent?.sessionID
        let sharedStartedAt = trackingStartTime ?? control.intent?.startedAt ?? endedAt
        let wasTracking = isTrackingEnabled
        if isTrackingEnabled {
            let minutes = trackingStartTime.map { Int(endedAt.timeIntervalSince($0) / 60) }
            let span = minutes.map { " after \($0 / 60)h \(String(format: "%02d", $0 % 60))m" } ?? ""
            switch reason {
            case .autoStop:
                LogManager.shared.info("Tracking stopped on its own\(span) · auto-stop", category: .location)
            case .accessEnded:
                LogManager.shared.warning("Tracking stopped\(span) · recording access ended", category: .location)
            default:
                LogManager.shared.info("Tracking stopped\(span)", category: .location)
            }
        }
        endActiveRecordingSession(at: endedAt)
        smartStartState.endSession(manually: reason == .user, at: endedAt)
        trackingStartSource = nil

        isTrackingEnabled = false
        defaults.set(false, forKey: "isTrackingEnabled")

        invalidateStopAfterTimer()
        trackingStartTime = nil
        routeSamplingWakeTimer?.invalidate()
        routeSamplingWakeTimer = nil
        routeSampler.reset()
        preciseFixRequestedAt = nil
        locationManager.desiredAccuracy = kCLLocationAccuracyBest

        // Revalidate opt-in and Always before deciding whether ambient stays armed.
        if !smartStartState.settings.isEnabled || !hasAlwaysPermission || !trackingAccess() {
            ambientMonitoringActive = false
        }
        stopActiveLocationUpdates()

        Task {
            await liveActivityManager.endAllActivities()
        }

        syncDataToWatch()
        if (wasTracking || control.intent?.isRecording == true), Self.shared === self, let id = sharedSessionID {
            if control.isApplying && reason == .autoStop {
                // A delayed remote start may already exceed this phone's timer.
                // Publish the resulting local stop after the receive transaction ends.
                Task { @MainActor [weak self] in
                    guard let self, !self.isTrackingEnabled else { return }
                    control.publish(isRecording: false, sessionID: id, startedAt: sharedStartedAt, now: endedAt)
                }
            } else {
                control.publish(isRecording: false, sessionID: id, startedAt: sharedStartedAt, now: endedAt)
            }
        }
    }

    private func logTrackingStart(reason: TrackingChangeReason, wasTracking: Bool) {
        let mode: String
        switch routeSamplingMode {
        case .distance: mode = "every \(Int(distanceFilter)) m"
        case .time: mode = "every \(Int(routeSampleInterval)) s"
        case .distanceAndTime: mode = "every \(Int(routeSampleInterval)) s once \(Int(distanceFilter)) m away"
        }
        switch reason {
        case .relaunch:
            LogManager.shared.warning("Background session resumed after relaunch · \(mode)", category: .location)
        case .permissionGranted:
            LogManager.shared.info("Location updates reattached after permission change", category: .location)
        default:
            guard !wasTracking else { return }
            let autoStop = stopAfterHours > 0 ? " · stops after \(stopAfterHours.formatted()) h" : ""
            LogManager.shared.info("Tracking started · \(mode)\(autoStop)", category: .location)
        }
    }

    func setStopAfterHours(_ hours: Double) {
        stopAfterHours = hours
        defaults.set(hours, forKey: "stopAfterHours")
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
        defaults.set(enabled, forKey: liveActivityEnabledKey)

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
        defaults.set(meters, forKey: "distanceFilter")
        routeSamplingSettingsChanged()
    }

    func setRouteSamplingMode(_ mode: RouteSamplingMode) {
        routeSamplingMode = mode
        defaults.set(mode.rawValue, forKey: Self.routeSamplingModeKey)
        routeSamplingSettingsChanged()
    }

    func setRouteSampleInterval(_ seconds: TimeInterval) {
        guard seconds > 0 else { return }
        routeSampleInterval = seconds
        defaults.set(seconds, forKey: Self.routeSampleIntervalKey)
        routeSamplingSettingsChanged()
    }

    private func routeSamplingSettingsChanged() {
        routeSampler.policy = routeSamplingPolicy
        guard isTrackingEnabled else { return }
        locationManager.distanceFilter = routeSamplingPolicy.coreLocationDistanceFilter
        routeSampler.refreshPowerLevel(at: Date())
        applyRoutePowerLevel()
        LogManager.shared.info("Route points · \(routeSamplingPolicy.summary)", category: .location)
    }

    // MARK: - Route sampling power

    /// Sets Core Location's accuracy from the sampler, and schedules the GPS to come back
    /// up before the next time-based sample is due.
    private func applyRoutePowerLevel() {
        if let requestedAt = preciseFixRequestedAt, Date().timeIntervalSince(requestedAt) > 60 {
            preciseFixRequestedAt = nil
        }
        let level: RoutePowerLevel = preciseFixRequestedAt != nil ? .precise : routeSampler.powerLevel
        if locationManager.desiredAccuracy != level.desiredAccuracy {
            locationManager.desiredAccuracy = level.desiredAccuracy
        }

        routeSamplingWakeTimer?.invalidate()
        routeSamplingWakeTimer = nil
        guard isTrackingEnabled, let wake = routeSampler.nextPowerUpDate else { return }
        let delay = max(1, wake.timeIntervalSinceNow)
        routeSamplingWakeTimer = Timer.scheduledTimer(withTimeInterval: delay, repeats: false) { [weak self] _ in
            Task { @MainActor in
                guard let self, self.isTrackingEnabled else { return }
                self.routeSampler.refreshPowerLevel(at: Date())
                self.applyRoutePowerLevel()
            }
        }
    }

    private func invalidateStopAfterTimer() {
        stopAfterGeneration = UUID()
        cancelStopAfterTimer?()
        cancelStopAfterTimer = nil
    }

    private func scheduleStopTrackingTimer() {
        invalidateStopAfterTimer()

        guard isTrackingEnabled, stopAfterHours > 0, let start = trackingStartTime else { return }

        let elapsed = Date().timeIntervalSince(start)
        let total = stopAfterHours * 3600
        let remaining = total - elapsed
        guard remaining > 0 else {
            stopTracking(reason: .autoStop)
            return
        }

        let token = stopAfterGeneration
        cancelStopAfterTimer = stopAfterScheduler.schedule(after: remaining) { [weak self] in
            guard let self, self.isTrackingEnabled, self.stopAfterGeneration == token,
                  self.trackingStartTime == start else { return }
            self.stopTracking(reason: .autoStop)
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
        let previousStart = trackingStartTime

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
                    let session = makeRecordingSession(startedAt: start)
                    context.insert(session)
                    activeRecordingSessionID = session.id
                    trackingStartTime = start
                    changedCount += 1
                }
                if let trackingStartTime {
                    let source = trackingStartSource ?? .manual
                    trackingStartSource = source
                    smartStartState.saveSessionMetadata(source: source, at: trackingStartTime)
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

            if isTrackingEnabled, trackingStartTime != previousStart {
                scheduleStopTrackingTimer()
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

            let session = makeRecordingSession(startedAt: startedAt)
            context.insert(session)
            activeRecordingSessionID = session.id
            try context.save()
        } catch {
            lastError = String(localized: "Failed to start recording session: \(error.localizedDescription)")
        }
    }

    private func makeRecordingSession(startedAt: Date) -> RecordingSession {
        RecordingSession(
            startedAt: startedAt,
            distanceFilterMeters: distanceFilter,
            routeSamplingModeRawValue: routeSamplingMode.rawValue,
            routeSampleIntervalSeconds: routeSamplingMode.usesTimeInterval ? routeSampleInterval : nil
        )
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

    static func closePersistedRecordingSessions(in context: ModelContext, at endedAt: Date = Date(),
                                                defaults: UserDefaults = .standard) {
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
            defaults.removeObject(forKey: activeRecordingSessionIDKey)
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
        var newArrivalAwaitingName = false

        do {
            if hasDeparture,
               let existingVisit = try matchingOpenVisit(for: clVisit, in: context) {
                // Departure update for an existing open visit.
                let departedAt = max(existingVisit.arrivedAt, departureDate)
                existingVisit.departedAt = departedAt
                existingVisit.countryDepartureIsInferred = false
                LogManager.shared.info("Visit ended (\(Int(departedAt.timeIntervalSince(existingVisit.arrivedAt) / 60)) min)", category: .visit)
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
                    if visit.geocodingCompleted {
                        // Named by a saved place already.
                        LogManager.shared.info("Arrived \(visit.displayName) (saved place)", category: .visit)
                    } else {
                        newArrivalAwaitingName = true
                    }
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
                    if newArrivalAwaitingName {
                        // Logged once the place has a name, when Apple Maps found one.
                        let name = visitToGeocode.isDeleted ? nil : (visitToGeocode.normalizedCustomName ?? visitToGeocode.locationName)
                        LogManager.shared.info(name.map { "Arrived \($0)" } ?? "Arrived at a new place", category: .visit)
                    }
                }
            } else if newArrivalAwaitingName {
                LogManager.shared.info("Arrived at a new place", category: .visit)
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
        // Reviewed stops named after different places (e.g. a split visit, or two
        // neighbouring saved places) are separate visits, however close together.
        if Self.areDistinctReviewedPlaces(lhs, rhs) { return false }

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

    static func areDistinctReviewedPlaces(_ lhs: Visit, _ rhs: Visit) -> Bool {
        guard lhs.isConfirmed, rhs.isConfirmed else { return false }
        return lhs.displayName.compare(rhs.displayName, options: [.caseInsensitive, .diacriticInsensitive]) != .orderedSame
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
    private func saveLocationPoints(_ locations: [CLLocation], receivedCount: Int? = nil) -> [LocationPoint] {
        guard let context = modelContext else { return [] }

        // Count every fix the system delivered so the Route screen can show "kept of received".
        if let activeRecordingSessionID,
           let activeSession = try? recordingSession(withID: activeRecordingSessionID, in: context) {
            activeSession.recordReceivedLocations(receivedCount ?? locations.count)
        }

        let validLocations = locations.filter { location in
            location.horizontalAccuracy >= 0 && location.horizontalAccuracy <= 100
        }
        let droppedCount = locations.count - validLocations.count
        if droppedCount > 0 {
            LogManager.shared.info(
                "Dropped \(droppedCount) imprecise point\(droppedCount == 1 ? "" : "s") (over ±100 m)",
                category: .location
            )
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

        // Widgets and the Live Activity dial draw today's stays, including one carried over from last night.
        let overlappingPredicate = #Predicate<Visit> { visit in
            visit.arrivedAt < endOfToday && (visit.departedAt == nil || visit.departedAt! > startOfToday)
        }
        var overlappingDescriptor = FetchDescriptor<Visit>(predicate: overlappingPredicate)
        overlappingDescriptor.sortBy = [SortDescriptor(\.arrivedAt, order: .forward)]
        LiveActivityManager.publishTodayWidgetSnapshot(
            visits: (try? context.fetch(overlappingDescriptor)) ?? [],
            distanceMeters: totalDistance,
            usesMetricDistanceUnits: usesMetricDistanceUnits,
            isTracking: isTrackingEnabled,
            recordedDayCount: recordedDayNumber()
        )

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
            handleAuthorizationChange(status: manager.authorizationStatus, accuracy: manager.accuracyAuthorization)
        }
    }

    func handleAuthorizationChange(status: CLAuthorizationStatus, accuracy: CLAccuracyAuthorization) {
        let previousStatus = authorizationStatus
        let previousAccuracy = accuracyAuthorization
        authorizationStatus = status
        accuracyAuthorization = accuracy
        if hasLocationPermission && pendingManualLocationRequest {
            requestCurrentLocationForManualVisit()
        }

        if previousStatus != authorizationStatus || previousAccuracy != accuracyAuthorization {
            Self.logAuthorizationChange(status: authorizationStatus, accuracy: accuracyAuthorization)
        }

        autoStartCoordinator?.refresh()
        if !hasAlwaysPermission { stopAmbientMonitoring() }
        reconcileLowPowerMonitoring()
        if Self.shared === self { SharedRecordingControl.shared.flush() }

        if hasLocationPermission && isTrackingEnabled {
            // Reattach the CL APIs once permission lands
            startTracking(reason: .permissionGranted)
        }
    }

    private static func logAuthorizationChange(status: CLAuthorizationStatus, accuracy: CLAccuracyAuthorization) {
        let access: String
        switch status {
        case .authorizedAlways: access = "Always"
        case .authorizedWhenInUse: access = "While Using"
        case .denied: access = "Off"
        case .restricted: access = "Restricted"
        case .notDetermined: access = "Not asked yet"
        @unknown default: access = "Unknown"
        }
        let precision = accuracy == .reducedAccuracy ? "approximate" : "precise"
        let message = "Location access changed · \(access) · \(precision)"
        if status == .denied || status == .restricted {
            LogManager.shared.warning(message, category: .location)
        } else {
            LogManager.shared.info(message, category: .location)
        }
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didVisit visit: CLVisit) {
        Task { @MainActor in handleVisit(visit) }
    }

    func handleVisit(_ visit: CLVisit) {
        let wasRecording = isTrackingEnabled
        if ambientMonitoringActive { autoStartCoordinator?.locationWake() }
        guard wasRecording, isTrackingEnabled, trackingAccess(), admitsVisitToSession(visit) else { return }
        saveVisit(visit)
    }

    /// Never synthesize a pre-session visit from a late callback. A departure
    /// inside this session may still close a previously persisted open visit.
    private func admitsVisitToSession(_ visit: CLVisit) -> Bool {
        guard let start = trackingStartTime else { return false }
        if visit.arrivalDate != .distantPast, visit.arrivalDate >= start { return true }
        guard visit.departureDate != .distantFuture, visit.departureDate >= start,
              let context = modelContext else { return false }
        return (try? matchingOpenVisit(for: visit, in: context)) != nil
    }

    nonisolated func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        Task { @MainActor in await handleLocationUpdates(locations) }
    }

    /// The delegate and injection tests use the same actor-isolated processing path.
    func handleLocationUpdates(_ locations: [CLLocation]) async {
        guard let deliveredLocation = locations.last else { return }
        currentLocation = deliveredLocation
        let wasRecording = isTrackingEnabled
        if ambientMonitoringActive && !wasRecording { autoStartCoordinator?.locationWake() }

        guard wasRecording, isTrackingEnabled, trackingAccess(), let start = trackingStartTime else { return }
        // Handler time/generation alone cannot identify a cached CoreLocation fix.
        // Admit by the actual (possibly restored) session start before ANY consumer.
        let admittedLocations = locations.filter { $0.timestamp >= start }
        guard let location = admittedLocations.last else { return }

        // Record only admitted active-session locations for daily distance history (stats only).
        for deliveredLocation in admittedLocations {
            recordDistanceLocation(deliveredLocation)
        }

        // Distance mode passes every fix through; time modes keep one per interval.
        let samples = routeSampler.process(admittedLocations)
        if preciseFixRequestedAt != nil,
           admittedLocations.contains(where: { $0.horizontalAccuracy >= 0 && $0.horizontalAccuracy <= RouteSamplingPolicy.preciseAccuracy }) {
            preciseFixRequestedAt = nil
        }
        applyRoutePowerLevel()
        saveLocationPoints(samples, receivedCount: admittedLocations.count)

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
            // kCLErrorLocationUnknown is transient: Core Location keeps trying.
            if (error as? CLError)?.code == .locationUnknown {
                LogManager.shared.warning("Location unavailable for now · still trying", category: .location)
            } else {
                LogManager.shared.error("Location failed · \(error.localizedDescription)", category: .location)
            }
        }
    }
}
