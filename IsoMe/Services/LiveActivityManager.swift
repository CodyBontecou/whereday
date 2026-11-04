import ActivityKit
import Foundation
import CoreLocation
import UIKit
import WidgetKit

/// Manages the Live Activity for location tracking
@MainActor
final class LiveActivityManager: ObservableObject {
    
    static let shared = LiveActivityManager()
    
    @Published private(set) var isActivityActive = false
    
    private var currentActivity: Activity<LocationActivityAttributes>?
    private var startTime: Date?
    private var locationsRecorded: Int = 0
    private var totalDistance: Double = 0
    private var lastLocation: CLLocation?
    private var currentLocationName: String?
    private var originLocationName: String?
    private var currentRemainingSeconds: Int?
    /// Fixes from the last few minutes, for the "● RECORDING · WALKING" travel-mode estimate.
    private var recentLocations: [CLLocation] = []
    private static let travelModeWindow: TimeInterval = 180
    private static let travelModeMinimumSpan: TimeInterval = 20
    /// The Live Activity now draws today's dial instead of a map thumbnail; kept at 0 for payload compatibility.
    private let mapSnapshotVersion: Int = 0

    private var usesMetricDistanceUnits: Bool {
        let key = "usesMetricDistanceUnits"
        if UserDefaults.standard.object(forKey: key) == nil {
            return true
        }
        return UserDefaults.standard.bool(forKey: key)
    }
    
    private init() {}
    
    // MARK: - Public API
    
    /// Check if Live Activities are supported and enabled
    var areActivitiesEnabled: Bool {
        ActivityAuthorizationInfo().areActivitiesEnabled
    }
    
    /// Start a new Live Activity for location tracking
    func startActivity(autoStopSeconds: Int?) {
        #if DEBUG
        print("🟡 LiveActivityManager.startActivity called")
        print("   areActivitiesEnabled: \(areActivitiesEnabled)")
        print("   Existing activities count: \(Activity<LocationActivityAttributes>.activities.count)")
        #endif

        guard areActivitiesEnabled else {
            #if DEBUG
            print("❌ Live Activities are not enabled in device Settings")
            print("   Go to Settings > Whereday > Live Activities")
            #endif
            return
        }

        // End any existing activities first
        Task {
            await endAllActivities()
            await startActivityInternal(autoStopSeconds: autoStopSeconds)
        }
    }

    private func startActivityInternal(autoStopSeconds: Int?) async {
        let startedAt = Date()
        startTime = startedAt
        locationsRecorded = 0
        totalDistance = 0
        lastLocation = nil
        currentLocationName = nil
        originLocationName = nil
        currentRemainingSeconds = autoStopSeconds
        recentLocations = []

        let attributes = LocationActivityAttributes(startTime: startedAt)
        let initialState = LocationActivityAttributes.ContentState(
            locationName: nil,
            locationsRecorded: 0,
            distanceTraveled: 0,
            remainingSeconds: autoStopSeconds,
            lastUpdate: Date(),
            usesMetricDistanceUnits: usesMetricDistanceUnits,
            mapSnapshotVersion: 0
        )

        #if DEBUG
        print("🟢 Requesting Live Activity...")
        print("   autoStopSeconds: \(String(describing: autoStopSeconds))")
        #endif

        do {
            let activity = try Activity.request(
                attributes: attributes,
                content: .init(state: initialState, staleDate: nil),
                pushType: nil
            )
            currentActivity = activity
            isActivityActive = true
            #if DEBUG
            print("✅ Started Live Activity: \(activity.id)")
            #endif
        } catch {
            #if DEBUG
            print("❌ Failed to start Live Activity: \(error)")
            #endif
        }
    }

    /// Update the Live Activity with new location data
    func updateActivity(
        location: CLLocation?,
        locationName: String? = nil,
        remainingSeconds: Int? = nil
    ) {
        guard let activity = currentActivity else { return }

        // Update distance if we have a new location
        if let newLocation = location {
            if let last = lastLocation {
                totalDistance += newLocation.distance(from: last)
            }
            lastLocation = newLocation
            locationsRecorded += 1
            recentLocations.append(newLocation)
        }
        let cutoff = Date().addingTimeInterval(-Self.travelModeWindow)
        recentLocations.removeAll { $0.timestamp < cutoff }

        if let locationName {
            currentLocationName = locationName
            if originLocationName == nil {
                originLocationName = locationName
            }
        }
        currentRemainingSeconds = remainingSeconds

        let updatedState = LocationActivityAttributes.ContentState(
            locationName: currentLocationName,
            locationsRecorded: locationsRecorded,
            distanceTraveled: totalDistance,
            remainingSeconds: currentRemainingSeconds,
            lastUpdate: Date(),
            usesMetricDistanceUnits: usesMetricDistanceUnits,
            mapSnapshotVersion: mapSnapshotVersion,
            originLocationName: originLocationName,
            latitude: lastLocation?.coordinate.latitude,
            longitude: lastLocation?.coordinate.longitude,
            travelMode: Self.travelMode(for: recentLocations)
        )

        Task {
            await activity.update(
                ActivityContent(state: updatedState, staleDate: nil)
            )
        }
    }

    /// End the current Live Activity
    func endActivity() async {
        guard let activity = currentActivity else { return }

        let finalState = LocationActivityAttributes.ContentState(
            locationName: String(localized: "Tracking stopped."),
            locationsRecorded: locationsRecorded,
            distanceTraveled: totalDistance,
            remainingSeconds: nil,
            lastUpdate: Date(),
            usesMetricDistanceUnits: usesMetricDistanceUnits,
            mapSnapshotVersion: mapSnapshotVersion,
            originLocationName: originLocationName
        )

        await activity.end(
            ActivityContent(state: finalState, staleDate: nil),
            dismissalPolicy: .immediate
        )

        currentActivity = nil
        isActivityActive = false
        startTime = nil
        currentLocationName = nil
        originLocationName = nil
        currentRemainingSeconds = nil
        recentLocations = []
        #if DEBUG
        print("Ended Live Activity")
        #endif
    }

    /// Estimates walk/bike/drive from the path covered by recent fixes, falling back to the
    /// latest fix's reported speed when they span too little time. nil when standing still.
    static func travelMode(for locations: [CLLocation]) -> TravelMode? {
        let ordered = locations.sorted { $0.timestamp < $1.timestamp }
        guard let first = ordered.first, let last = ordered.last else { return nil }
        let span = last.timestamp.timeIntervalSince(first.timestamp)
        if span >= travelModeMinimumSpan {
            let meters = zip(ordered, ordered.dropFirst()).reduce(0) { $0 + $1.1.distance(from: $1.0) }
            return TravelMode.infer(meters: meters, seconds: span)
        }
        guard last.speed > 0 else { return nil }
        return TravelMode.infer(averageSpeed: last.speed)
    }

    /// Forces a Live Activity redraw after the distance unit preference changes.
    func refreshDistanceUnitPreference() {
        guard currentActivity != nil else { return }
        updateActivity(
            location: nil,
            locationName: currentLocationName,
            remainingSeconds: currentRemainingSeconds
        )
    }
    
    /// End all Live Activities (useful for cleanup)
    func endAllActivities() async {
        for activity in Activity<LocationActivityAttributes>.activities {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
        currentActivity = nil
        isActivityActive = false
        currentLocationName = nil
        originLocationName = nil
        currentRemainingSeconds = nil
    }
}

// MARK: - Today widget snapshot

extension LiveActivityManager {
    /// Publishes today's stays for the Home Screen widgets and the Live Activity dial.
    /// Call with visits overlapping today whenever today's visits or distance change.
    static func publishTodayWidgetSnapshot(
        visits: [Visit],
        distanceMeters: Double,
        usesMetricDistanceUnits: Bool,
        isTracking: Bool,
        recordedDayCount: Int? = nil,
        now: Date = Date()
    ) {
        let startOfDay = Calendar.current.startOfDay(for: now)
        let stays = visits
            .filter { $0.arrivedAt <= now && ($0.departedAt ?? now) >= startOfDay }
            .map {
                TodayWidgetSnapshot.Stay(
                    placeName: $0.displayName,
                    arrivedAt: $0.arrivedAt,
                    departedAt: $0.departedAt
                )
            }
        let snapshot = TodayWidgetSnapshot(
            generatedAt: now,
            stays: stays,
            distanceMeters: distanceMeters,
            usesMetricDistanceUnits: usesMetricDistanceUnits,
            isTracking: isTracking,
            recordedDayCount: recordedDayCount
        )
        // Apple Watch draws the same day dial; the receiver skips unchanged contexts.
        WatchLocationSyncReceiver.publishTodayContext(Self.watchTodayContext(from: snapshot))
        guard snapshot != TodayWidgetSnapshot.load().map({ var copy = $0; copy.generatedAt = now; return copy }) else { return }
        snapshot.save()
        for kind in TodayWidgetSnapshot.widgetKinds {
            WidgetCenter.shared.reloadTimelines(ofKind: kind)
        }
    }

    /// The watch's copy of today: stays with per-place inks (assigned in arrival order, like the
    /// widgets' `dayStays`) plus the ongoing stay for "since 13:50 · 30 min".
    static func watchTodayContext(from snapshot: TodayWidgetSnapshot) -> WatchTodayContext {
        var inkByPlace: [String: Int] = [:]
        let ordered = snapshot.stays.sorted { $0.arrivedAt < $1.arrivedAt }
        let stays = ordered.suffix(WatchTodayContext.maxStays).map { stay -> WatchTodayContext.Stay in
            let ink = inkByPlace[stay.placeName] ?? inkByPlace.count
            inkByPlace[stay.placeName] = ink
            return WatchTodayContext.Stay(
                placeName: stay.placeName,
                arrivedAt: stay.arrivedAt,
                departedAt: stay.departedAt,
                inkIndex: ink
            )
        }
        let current = ordered.last(where: { $0.departedAt == nil })
        return WatchTodayContext(
            generatedAt: snapshot.generatedAt,
            stays: Array(stays),
            currentStayName: current?.placeName,
            currentStaySince: current?.arrivedAt,
            distanceMeters: snapshot.distanceMeters,
            usesMetricDistanceUnits: snapshot.usesMetricDistanceUnits,
            recordedDayCount: snapshot.recordedDayCount
        )
    }
}
