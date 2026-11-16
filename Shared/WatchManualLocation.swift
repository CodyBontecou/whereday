import CoreLocation
import Foundation

@MainActor
protocol WatchManualLocationManaging: AnyObject {
    var authorizationStatus: CLAuthorizationStatus { get }
    var delegate: (any CLLocationManagerDelegate)? { get set }
    var desiredAccuracy: CLLocationAccuracy { get set }
    func requestWhenInUseAuthorization()
    func requestLocation()
    func stopUpdatingLocation()
}

extension CLLocationManager: WatchManualLocationManaging {}

/// Fresh one-shot Watch location, independent of route recording.
@MainActor
final class WatchManualLocation: NSObject, @preconcurrency CLLocationManagerDelegate {
    struct Fix {
        let location: CLLocation
        /// Captured after permission is granted, immediately before requesting the fix.
        let requestedAt: Date
    }
    private let manager: any WatchManualLocationManaging
    private let now: @MainActor () -> Date
    init(manager: any WatchManualLocationManaging = CLLocationManager(), now: @escaping @MainActor () -> Date = { Date() }) {
        self.manager = manager
        self.now = now
        super.init()
    }
    private var continuation: CheckedContinuation<Fix, Error>?
    private var timeout: Task<Void, Never>?
    private var requestingLocation = false
    private var requestedAt: Date?
    func location() async throws -> Fix {
        try Task.checkCancellation()
        guard continuation == nil else { throw LocationError.unavailable }
        return try await withTaskCancellationHandler {
            try Task.checkCancellation()
            return try await withCheckedThrowingContinuation { continuation in
                self.continuation = continuation
                manager.delegate = self
                manager.desiredAccuracy = kCLLocationAccuracyBest
                if manager.authorizationStatus == .notDetermined {
                    manager.requestWhenInUseAuthorization()
                } else {
                    authorizationChanged()
                }
            }
        } onCancel: { Task { @MainActor in self.finish(.failure(CancellationError())) } }
    }
    func locationManagerDidChangeAuthorization(_ manager: CLLocationManager) {
        authorizationChanged()
    }
    private func authorizationChanged() {
        guard continuation != nil else { return }
        switch manager.authorizationStatus {
        case .notDetermined: break
        case .authorizedWhenInUse, .authorizedAlways:
            guard !requestingLocation else { return }
            requestingLocation = true
            requestedAt = now()
            // The fix timeout begins after the person answers the permission prompt.
            timeout = Task { [weak self] in
                do { try await Task.sleep(for: .seconds(12)) } catch { return }
                self?.finish(.failure(LocationError.unavailable))
            }
            manager.requestLocation()
        default:
            finish(.failure(LocationError.permission))
        }
    }
    func locationManager(_ manager: CLLocationManager, didUpdateLocations locations: [CLLocation]) {
        guard requestingLocation, continuation != nil else { return }
        guard let requestedAt, let location = locations.last, abs(location.timestamp.timeIntervalSince(now())) <= 30,
              location.horizontalAccuracy >= 0, location.horizontalAccuracy <= 100 else {
            finish(.failure(LocationError.unavailable)); return
        }
        finish(.success(Fix(location: location, requestedAt: requestedAt)))
    }
    func locationManager(_ manager: CLLocationManager, didFailWithError error: Error) { finish(.failure(LocationError.unavailable)) }
    private func finish(_ result: Result<Fix, Error>) {
        let pending = continuation; continuation = nil
        timeout?.cancel(); timeout = nil
        requestingLocation = false
        requestedAt = nil
        manager.delegate = nil
        manager.stopUpdatingLocation()
        pending?.resume(with: result)
    }
    enum LocationError: LocalizedError {
        case permission, unavailable
        var errorDescription: String? {
            switch self {
            case .permission: return String(localized: "Allow Watch location in Settings, or choose a saved place.")
            case .unavailable: return String(localized: "A fresh accurate location is unavailable. Choose a saved place or try again.")
            }
        }
    }
}
