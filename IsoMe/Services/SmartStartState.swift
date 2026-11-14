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
        TrackingSessionLease.clear(in: defaults)
        if manually { defaults.set(date, forKey: "lastManualStopAt") }
    }
}

/// A Core Location activity hint, not an inferred activity or a sampling-preference edit.
enum TrackingActivityProfile: String, Codable, Equatable, CaseIterable {
    case walkingRunning, cycling, driving
}

/// Explicit options for one newly accepted recording. Omission preserves the user's defaults.
struct TrackingSessionOptions: Codable, Equatable {
    enum ValidationError: Error { case invalidDuration }
    let durationSeconds: TimeInterval?
    let activityProfile: TrackingActivityProfile?

    init(durationMinutes: Double? = nil, activityProfile: TrackingActivityProfile? = nil) throws {
        durationSeconds = durationMinutes.map { $0 * 60 }
        self.activityProfile = activityProfile
        try validate()
    }

    private init(activityProfile: TrackingActivityProfile?) {
        durationSeconds = nil
        self.activityProfile = activityProfile
    }

    func validate() throws {
        if let durationSeconds {
            guard durationSeconds.isFinite, (60...86_400).contains(durationSeconds) else {
                throw ValidationError.invalidDuration
            }
        }
    }

    var isEmpty: Bool { durationSeconds == nil && activityProfile == nil }
    func withoutDuration() -> Self { Self(activityProfile: activityProfile) }
}

/// A local lease bound to the actual recording UUID. Its absolute deadline never restarts on relaunch.
/// Nothing here changes global Stop After, route sampling, Smart Start opt-in or public backup data.
struct TrackingSessionLease: Codable, Equatable {
    static let defaultsKey = "activeTrackingSessionOptions"
    enum ValidationError: Error { case invalidRecord }
    let version: Int
    let sessionID: UUID
    let issuedAt: Date
    var options: TrackingSessionOptions

    init(sessionID: UUID, issuedAt: Date, options: TrackingSessionOptions) {
        version = 1
        self.sessionID = sessionID
        self.issuedAt = issuedAt
        self.options = options
    }

    var deadline: Date? { options.durationSeconds.map { issuedAt.addingTimeInterval($0) } }

    static func read(from defaults: UserDefaults) throws -> Self? {
        guard let object = defaults.object(forKey: defaultsKey) else { return nil }
        guard let data = object as? Data, data.count <= 2048 else { throw ValidationError.invalidRecord }
        let lease = try JSONDecoder().decode(Self.self, from: data)
        try lease.validate()
        return lease
    }

    func save(in defaults: UserDefaults) throws {
        try validate()
        defaults.set(try JSONEncoder().encode(self), forKey: Self.defaultsKey)
    }

    static func clear(in defaults: UserDefaults) { defaults.removeObject(forKey: defaultsKey) }

    private func validate() throws {
        try options.validate()
        guard version == 1, !options.isEmpty,
              issuedAt.timeIntervalSinceReferenceDate.isFinite,
              issuedAt >= .distantPast, issuedAt <= .distantFuture else {
            throw ValidationError.invalidRecord
        }
    }
}
