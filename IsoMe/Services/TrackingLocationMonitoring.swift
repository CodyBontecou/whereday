import CoreLocation

/// Internal effect seam shared by the real CoreLocation adapter and isolated tests.
@MainActor
protocol TrackingLocationMonitoring: AnyObject {
    func startActiveUpdates()
    func stopActiveUpdates()
    func startLowPowerMonitoring()
    func stopLowPowerMonitoring()
}

@MainActor
final class CoreLocationTrackingMonitor: TrackingLocationMonitoring {
    private let manager: CLLocationManager
    init(manager: CLLocationManager) { self.manager = manager }
    func startActiveUpdates() { manager.startUpdatingLocation() }
    func stopActiveUpdates() { manager.stopUpdatingLocation() }
    func startLowPowerMonitoring() {
        manager.startMonitoringVisits()
        manager.startMonitoringSignificantLocationChanges()
    }
    func stopLowPowerMonitoring() {
        manager.stopMonitoringVisits()
        manager.stopMonitoringSignificantLocationChanges()
    }
}
