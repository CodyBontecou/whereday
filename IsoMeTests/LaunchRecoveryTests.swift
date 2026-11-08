import XCTest
import SwiftData
@testable import IsoMe

/// Regression tests for the post-tracking launch loop: launches that keep dying
/// must recover without a reinstall and without losing saved data.
@MainActor
final class LaunchRecoveryTests: XCTestCase {
    private var suiteName: String!
    private var defaults: UserDefaults!

    override func setUp() {
        super.setUp()
        suiteName = "LaunchRecoveryTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        super.tearDown()
    }

    // MARK: - LaunchGuard

    func testOrdinaryLaunchesNeverPauseTracking() {
        defaults.set(true, forKey: LaunchGuard.trackingEnabledKey)

        for _ in 0..<5 {
            let decision = LaunchGuard.beginLaunch(defaults: defaults, isBackgroundLaunch: false)
            XCTAssertFalse(decision.pausedTracking)
            XCTAssertNil(decision.previousStage)
            LaunchGuard.confirmLaunch(defaults: defaults)
        }

        XCTAssertTrue(defaults.bool(forKey: LaunchGuard.trackingEnabledKey))
    }

    func testRepeatedUnfinishedLaunchesPauseTrackingOnce() {
        defaults.set(true, forKey: LaunchGuard.trackingEnabledKey)

        // Two launches die while resuming tracking.
        for _ in 0..<LaunchGuard.failureThreshold {
            XCTAssertFalse(LaunchGuard.beginLaunch(defaults: defaults, isBackgroundLaunch: false).pausedTracking)
            LaunchGuard.mark(.locationManager, defaults: defaults)
        }

        let recovery = LaunchGuard.beginLaunch(defaults: defaults, isBackgroundLaunch: false)
        XCTAssertTrue(recovery.pausedTracking)
        XCTAssertEqual(recovery.previousUnconfirmedLaunches, LaunchGuard.failureThreshold)
        XCTAssertEqual(recovery.previousStage, LaunchGuard.Stage.locationManager.rawValue)
        XCTAssertFalse(defaults.bool(forKey: LaunchGuard.trackingEnabledKey),
                       "The recovery launch must not resume tracking")

        XCTAssertTrue(LaunchGuard.consumePausedTrackingNotice(defaults: defaults))
        XCTAssertFalse(LaunchGuard.consumePausedTrackingNotice(defaults: defaults), "The notice shows once")

        // The recovery launch counts from one, so it can't trip the guard again on its own.
        let next = LaunchGuard.beginLaunch(defaults: defaults, isBackgroundLaunch: false)
        XCTAssertFalse(next.pausedTracking)
    }

    func testTrackingOffIsLeftAloneAndStillReportsLastStage() {
        defaults.set(false, forKey: LaunchGuard.trackingEnabledKey)
        LaunchGuard.beginLaunch(defaults: defaults, isBackgroundLaunch: false)
        LaunchGuard.mark(.viewModel, defaults: defaults)
        LaunchGuard.beginLaunch(defaults: defaults, isBackgroundLaunch: false)

        let decision = LaunchGuard.beginLaunch(defaults: defaults, isBackgroundLaunch: false)
        XCTAssertFalse(decision.pausedTracking)
        XCTAssertEqual(decision.previousStage, LaunchGuard.Stage.started.rawValue)
        XCTAssertFalse(LaunchGuard.consumePausedTrackingNotice(defaults: defaults))
    }

    func testBackgroundRelaunchesAreNotCounted() {
        defaults.set(true, forKey: LaunchGuard.trackingEnabledKey)

        // Location relaunches are suspended and ended by the system without ever settling.
        for _ in 0..<5 {
            XCTAssertFalse(LaunchGuard.beginLaunch(defaults: defaults, isBackgroundLaunch: true).pausedTracking)
        }

        XCTAssertFalse(LaunchGuard.beginLaunch(defaults: defaults, isBackgroundLaunch: false).pausedTracking)
        XCTAssertTrue(defaults.bool(forKey: LaunchGuard.trackingEnabledKey))
    }

    // MARK: - AppModelStore

    func testUnopenableStoreFallsBackWithoutTouchingTheFile() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("LaunchRecoveryTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let url = directory.appendingPathComponent("default.store")
        let original = Data("not a SQLite database".utf8)
        try original.write(to: url)

        let result = AppModelStore.open(
            configuration: ModelConfiguration(schema: AppModelStore.schema, url: url, allowsSave: true)
        )

        XCTAssertNotNil(result.failure, "An unreadable store must be reported, not trapped on")
        XCTAssertEqual(try Data(contentsOf: url), original, "The saved data on disk must be left untouched")

        // The stand-in container still works, so the app can show its recovery screen.
        let context = ModelContext(result.container)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<RecordingSession>()), 0)
    }

    func testReadableStoreOpensNormally() throws {
        let directory = FileManager.default.temporaryDirectory
            .appendingPathComponent("LaunchRecoveryTests-\(UUID().uuidString)", isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        defer { try? FileManager.default.removeItem(at: directory) }

        let configuration = ModelConfiguration(
            schema: AppModelStore.schema,
            url: directory.appendingPathComponent("default.store"),
            allowsSave: true
        )
        let result = AppModelStore.open(configuration: configuration)
        XCTAssertNil(result.failure)

        let context = ModelContext(result.container)
        context.insert(RecordingSession(startedAt: Date(), endedAt: Date()))
        try context.save()
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<RecordingSession>()), 1)
    }
}
