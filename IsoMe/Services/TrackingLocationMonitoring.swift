import CoreLocation

/// A fired Timer may already have queued work that cancellation cannot retract.
@MainActor
protocol StopAfterScheduling: AnyObject {
    func schedule(after seconds: TimeInterval, expiry: @escaping @MainActor () -> Void) -> () -> Void
}

@MainActor
final class StopAfterTimerScheduler: StopAfterScheduling {
    func schedule(after seconds: TimeInterval, expiry: @escaping @MainActor () -> Void) -> () -> Void {
        let timer = Timer.scheduledTimer(withTimeInterval: seconds, repeats: false) { _ in
            Task { @MainActor in expiry() }
        }
        return { timer.invalidate() }
    }
}

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
