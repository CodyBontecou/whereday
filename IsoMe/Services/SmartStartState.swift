import Foundation

/// A shortcut is explicit user intent, not an inferred Smart Start signal.
enum TrackingStartSource: String, Codable, Equatable {
    case manual, shortcut, smartMotion, carPlayShortcut, workoutShortcut
    case significantLocationChange, visitDeparture

    var isAutomatic: Bool {
        switch self {
        case .smartMotion, .significantLocationChange, .visitDeparture: return true
        default: return false
        }
    }
}

struct SmartStartSettings: Codable, Equatable {
    /// Shared with the actual CoreMotion query window; no accepted threshold
    /// can require more continuous evidence than the adapter can produce.
    static let maximumActivityDuration: TimeInterval = 900

    enum ValidationError: Error, Equatable {
        case unsupportedMovementDuration, unsupportedStationaryDuration, invalidCooldown
    }

    func validate() throws {
        guard minimumMovementDuration.isFinite, minimumMovementDuration > 0,
              minimumMovementDuration <= Self.maximumActivityDuration else {
            throw ValidationError.unsupportedMovementDuration
        }
        guard stationaryDuration.isFinite, stationaryDuration > 0,
              stationaryDuration <= Self.maximumActivityDuration else {
            throw ValidationError.unsupportedStationaryDuration
        }
        guard manualStopCooldown.isFinite, manualStopCooldown >= 0,
              restartCooldown.isFinite, restartCooldown >= 0 else {
            throw ValidationError.invalidCooldown
        }
    }

    var isEnabled = false
    var drivingEnabled = true
    var walkingRunningEnabled = false
    var cyclingEnabled = false
    var minimumMovementDuration: TimeInterval = 120
    var manualStopCooldown: TimeInterval = 30 * 60
    var restartCooldown: TimeInterval = 5 * 60
    var stationaryAutoStopEnabled = false
    var stationaryDuration: TimeInterval = 10 * 60
}

/// Local-only persistence. No permissions, sensors or tracking are activated by this store.
struct SmartStartStateStore {
    let defaults: UserDefaults

    var settings: SmartStartSettings {
        get {
            guard let data = defaults.data(forKey: "smartStartSettings"),
                  let value = try? JSONDecoder().decode(SmartStartSettings.self, from: data) else {
                return SmartStartSettings()
            }
            // Legacy/corrupt unsupported payloads fail closed, never silently
            // clamp a requested duration into a different enabled behavior.
            guard (try? value.validate()) != nil else { return SmartStartSettings() }
            return value
        }
    }

    /// Throws before writing. Callers must surface rejection to the opt-in UI.
    func saveSettings(_ settings: SmartStartSettings) throws {
        try settings.validate()
        defaults.set(try JSONEncoder().encode(settings), forKey: "smartStartSettings")
    }

    var trackingStartTime: Date? { defaults.object(forKey: "trackingStartTime") as? Date }
    var trackingStartSource: TrackingStartSource? {
        defaults.string(forKey: "trackingStartSource").flatMap(TrackingStartSource.init(rawValue:))
    }
    var lastManualStopAt: Date? { defaults.object(forKey: "lastManualStopAt") as? Date }
    var lastAutoStartAt: Date? { defaults.object(forKey: "lastAutoStartAt") as? Date }

    /// Called only for a newly accepted session, never for relaunch/permission reattachment.
    func beginSession(source: TrackingStartSource, at date: Date) {
        saveSessionMetadata(source: source, at: date)
        if source.isAutomatic { defaults.set(date, forKey: "lastAutoStartAt") }
    }

    /// Reconciliation/reattachment may recover an older session date. This is not a new start.
    func saveSessionMetadata(source: TrackingStartSource, at date: Date) {
        defaults.set(date, forKey: "trackingStartTime")
        defaults.set(source.rawValue, forKey: "trackingStartSource")
    }

    func endSession(manually: Bool, at date: Date) {
        defaults.removeObject(forKey: "trackingStartTime")
        defaults.removeObject(forKey: "trackingStartSource")
        if manually { defaults.set(date, forKey: "lastManualStopAt") }
    }
}
