import ActivityKit
import Foundation
import CoreLocation
import MapKit
import UIKit

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
    private var currentRemainingSeconds: Int?
    private var trackedCoordinates: [CLLocationCoordinate2D] = []
    private var mapSnapshotVersion: Int = 0
    private var isGeneratingSnapshot = false

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
        currentRemainingSeconds = autoStopSeconds
        trackedCoordinates = []
        mapSnapshotVersion = 0

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
            trackedCoordinates.append(newLocation.coordinate)

            // Generate map snapshot (throttled — every 5 points or first point)
            if trackedCoordinates.count == 1 || trackedCoordinates.count % 5 == 0 {
                generateMapSnapshot()
            }
        }

        if let locationName {
            currentLocationName = locationName
        }
        currentRemainingSeconds = remainingSeconds

        let updatedState = LocationActivityAttributes.ContentState(
            locationName: currentLocationName,
            locationsRecorded: locationsRecorded,
            distanceTraveled: totalDistance,
            remainingSeconds: currentRemainingSeconds,
            lastUpdate: Date(),
            usesMetricDistanceUnits: usesMetricDistanceUnits,
            mapSnapshotVersion: mapSnapshotVersion
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
            mapSnapshotVersion: mapSnapshotVersion
        )

        await activity.end(
            ActivityContent(state: finalState, staleDate: nil),
            dismissalPolicy: .immediate
        )

        currentActivity = nil
        isActivityActive = false
        startTime = nil
        currentLocationName = nil
        currentRemainingSeconds = nil
        #if DEBUG
        print("Ended Live Activity")
        #endif
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
    
    // MARK: - Map Snapshot

    /// URL for the map snapshot in the shared App Group container
    static var mapSnapshotURL: URL? {
        FileManager.default
            .containerURL(forSecurityApplicationGroupIdentifier: "group.com.bontecou.isome")?
            .appendingPathComponent("map_snapshot.png")
    }

    private func generateMapSnapshot() {
        guard !isGeneratingSnapshot, trackedCoordinates.count >= 1 else { return }
        isGeneratingSnapshot = true

        let coordinates = trackedCoordinates
        let snapshotURL = Self.mapSnapshotURL

        Task.detached {
            var didWriteSnapshot = false

            if let snapshotImage = await Self.renderMapSnapshot(coordinates: coordinates),
               let data = snapshotImage.pngData(),
               let url = snapshotURL {
                do {
                    try data.write(to: url, options: .atomic)
                    didWriteSnapshot = true
                } catch {
                    #if DEBUG
                    print("❌ Failed to write map snapshot: \(error)")
                    #endif
                }
            }

            await Self.finishMapSnapshotGeneration(didWriteSnapshot: didWriteSnapshot)
        }
    }

    @MainActor
    private static func finishMapSnapshotGeneration(didWriteSnapshot: Bool) {
        let manager = Self.shared
        manager.isGeneratingSnapshot = false

        guard didWriteSnapshot else { return }
        manager.mapSnapshotVersion += 1
        // Push the updated version to the Live Activity
        manager.updateActivity(location: nil)
    }

    private static func renderMapSnapshot(coordinates: [CLLocationCoordinate2D]) async -> UIImage? {
        guard let current = coordinates.last else { return nil }

        // Center on current (latest) location
        let spanLat: CLLocationDegrees = 0.005
        let spanLon: CLLocationDegrees = 0.005

        let region = MKCoordinateRegion(
            center: current,
            span: MKCoordinateSpan(latitudeDelta: spanLat, longitudeDelta: spanLon)
        )

        let options = MKMapSnapshotter.Options()
        options.region = region
        options.size = CGSize(width: 340, height: 120)
        options.scale = UIScreen.main.scale
        options.mapType = .standard
        options.traitCollection = UITraitCollection(userInterfaceStyle: .dark)

        let snapshotter = MKMapSnapshotter(options: options)

        do {
            let snapshot = try await snapshotter.start()

            let renderer = UIGraphicsImageRenderer(size: options.size)
            let image = renderer.image { ctx in
                // Draw the map
                snapshot.image.draw(at: .zero)

                // Draw the path line
                if coordinates.count >= 2 {
                    let path = UIBezierPath()
                    for (i, coord) in coordinates.enumerated() {
                        let point = snapshot.point(for: coord)
                        if i == 0 {
                            path.move(to: point)
                        } else {
                            path.addLine(to: point)
                        }
                    }

                    UIColor.systemBlue.setStroke()
                    path.lineWidth = 3
                    path.lineCapStyle = .round
                    path.lineJoinStyle = .round
                    path.stroke()
                }

                // Draw current position dot
                if let lastCoord = coordinates.last {
                    let point = snapshot.point(for: lastCoord)
                    let dotSize: CGFloat = 10
                    let dotRect = CGRect(
                        x: point.x - dotSize / 2,
                        y: point.y - dotSize / 2,
                        width: dotSize,
                        height: dotSize
                    )
                    UIColor.systemBlue.setFill()
                    UIBezierPath(ovalIn: dotRect).fill()
                    UIColor.white.setStroke()
                    let strokePath = UIBezierPath(ovalIn: dotRect)
                    strokePath.lineWidth = 2
                    strokePath.stroke()
                }
            }

            return image
        } catch {
            #if DEBUG
            print("❌ Map snapshot failed: \(error)")
            #endif
            return nil
        }
    }

    /// End all Live Activities (useful for cleanup)
    func endAllActivities() async {
        for activity in Activity<LocationActivityAttributes>.activities {
            await activity.end(nil, dismissalPolicy: .immediate)
        }
        currentActivity = nil
        isActivityActive = false
        currentLocationName = nil
        currentRemainingSeconds = nil
    }
}
