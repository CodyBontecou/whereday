import Foundation
import SwiftData

/// The app's one SwiftData container, shared by the UI and in-app intents.
///
/// Two containers on the same SQLite file in one process (the UI's and the
/// Live Activity / Shortcuts intents') each run their own coordinator and
/// history tracking against the same store, so they now share this one.
///
/// Opening the store never traps. A store that can't be opened used to hit a
/// `fatalError` on every launch, which only a reinstall (and losing every
/// recording) cleared. Now the app shows `StoreUnavailableView` instead and
/// leaves the file on disk alone, so the next launch can try again.
@MainActor
enum AppModelStore {
    static let schema = Schema([
        Visit.self,
        LocationPoint.self,
        RecordingSession.self,
        PhotoMoment.self,
        SavedPlace.self,
        CountryStay.self,
        Trip.self,
        Vehicle.self
    ])

    struct OpenResult {
        let container: ModelContainer
        /// Why the on-disk store couldn't be opened; nil when it opened normally.
        /// When set, `container` is an empty in-memory stand-in.
        let failure: String?
    }

    private static let opened: OpenResult = open(configuration: defaultConfiguration())

    static var shared: ModelContainer { opened.container }

    /// Nil when the saved data opened normally.
    static var openFailure: String? { opened.failure }

    static func open(configuration: ModelConfiguration) -> OpenResult {
        var lastError: Error?
        // A second attempt covers one-off failures such as a busy file at launch.
        for _ in 0..<2 {
            do {
                return OpenResult(
                    container: try ModelContainer(for: schema, configurations: [configuration]),
                    failure: nil
                )
            } catch {
                lastError = error
            }
        }

        let failure = lastError.map { String(describing: $0) } ?? "unknown error"
        do {
            let memory = ModelConfiguration(schema: schema, isStoredInMemoryOnly: true, allowsSave: true)
            return OpenResult(container: try ModelContainer(for: schema, configurations: [memory]), failure: failure)
        } catch {
            // An in-memory store with the app's own schema has no file to fail on.
            fatalError("Could not create an in-memory ModelContainer: \(error)")
        }
    }

    private static func defaultConfiguration() -> ModelConfiguration {
        #if DEBUG && targetEnvironment(simulator)
        if ProcessInfo.processInfo.arguments.contains("--watch-sync-replay") {
            let directory = URL.documentsDirectory.appendingPathComponent("WatchSyncReplay")
            try? FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
            return ModelConfiguration(schema: schema, url: directory.appendingPathComponent("phone.store"))
        }
        #endif
        return ModelConfiguration(schema: schema, isStoredInMemoryOnly: false, allowsSave: true)
    }
}
