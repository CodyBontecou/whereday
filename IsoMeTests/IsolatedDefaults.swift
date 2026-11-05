import XCTest
@testable import IsoMe

extension XCTestCase {
    /// A throwaway UserDefaults suite for this test, removed when the test ends.
    ///
    /// LocationManager persists `isTrackingEnabled`, `activeRecordingSessionID`, `stopAfterHours`,
    /// `distanceFilter` and `isLiveActivityEnabled`. Writing those to `.standard` from a test leaks into
    /// the host app: on its next launch it resumes tracking and records real points, or hands a test
    /// manager a recording session that only exists in the app's store.
    @MainActor
    func makeIsolatedDefaults() -> UserDefaults {
        let suiteName = "IsoMeTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        defaults.removePersistentDomain(forName: suiteName)
        addTeardownBlock {
            UserDefaults().removePersistentDomain(forName: suiteName)
        }
        return defaults
    }

    /// A LocationManager that keeps its state in its own suite and never replaces `LocationManager.shared`.
    @MainActor
    func makeIsolatedLocationManager(defaults: UserDefaults? = nil) -> LocationManager {
        LocationManager(defaults: defaults ?? makeIsolatedDefaults(), registersAsShared: false)
    }
}
