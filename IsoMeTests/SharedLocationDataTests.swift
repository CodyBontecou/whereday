import XCTest
@testable import IsoMe

final class SharedLocationDataTests: XCTestCase {

    // MARK: - Backward-compatible decoding

    /// Data encoded BEFORE the usesMetricDistanceUnits field was added must
    /// still decode successfully.  A failure here means existing users will
    /// crash on launch because SharedLocationData.load() feeds a JSONDecoder.
    func testDecodesLegacyPayloadWithoutMetricField() throws {
        // Simulate the JSON that was persisted before the new field existed.
        let legacyJSON = """
        {
            "isTrackingEnabled": true,
            "todayVisitsCount": 3,
            "todayDistanceMeters": 1200.0,
            "todayPointsCount": 10
        }
        """.data(using: .utf8)!

        let decoded = try JSONDecoder().decode(SharedLocationData.self, from: legacyJSON)

        XCTAssertTrue(decoded.isTrackingEnabled)
        XCTAssertNil(decoded.usesMetricDistanceUnits,
                     "Missing key should decode as nil, not crash")
    }

    /// Data encoded with the OLD continuous-tracking fields must still decode.
    /// Extra unknown keys should be silently ignored.
    func testDecodesPayloadWithRemovedContinuousFields() throws {
        let legacyJSON = """
        {
            "isTrackingEnabled": true,
            "isContinuousTrackingEnabled": true,
            "todayVisitsCount": 3,
            "todayDistanceMeters": 1200.0,
            "todayPointsCount": 10,
            "continuousTrackingStartTime": -1000,
            "continuousTrackingAutoOffHours": 2.0
        }
        """.data(using: .utf8)!

        let decoded = try JSONDecoder().decode(SharedLocationData.self, from: legacyJSON)
        XCTAssertTrue(decoded.isTrackingEnabled)
        XCTAssertEqual(decoded.todayVisitsCount, 3)
    }

    /// Round-trip: encode and decode the new struct.
    func testRoundTrip() throws {
        let original = SharedLocationData(
            isTrackingEnabled: true,
            currentLocationName: "Coffee Shop",
            currentAddress: "123 Main St",
            lastLatitude: 37.7749,
            lastLongitude: -122.4194,
            lastUpdateTime: Date(),
            todayVisitsCount: 5,
            todayDistanceMeters: 3200,
            todayPointsCount: 42,
            trackingStartTime: Date(),
            stopAfterHours: 2.0,
            usesMetricDistanceUnits: false
        )

        let data = try JSONEncoder().encode(original)
        let decoded = try JSONDecoder().decode(SharedLocationData.self, from: data)

        XCTAssertEqual(decoded.usesMetricDistanceUnits, false)
        XCTAssertEqual(decoded.todayVisitsCount, 5)
        XCTAssertEqual(decoded.stopAfterHours, 2.0)
    }

    // MARK: - Tracking status (2-state)

    func testTrackingStatusOn() {
        var d = SharedLocationData.empty
        d.isTrackingEnabled = true
        XCTAssertEqual(d.trackingStatus, "Tracking")
    }

    func testTrackingStatusOff() {
        let d = SharedLocationData.empty
        XCTAssertEqual(d.trackingStatus, "Off")
    }

    // MARK: - Formatted distance (metric vs US standard)

    func testFormattedDistanceMetricShortDistance() {
        var d = SharedLocationData.empty
        d.usesMetricDistanceUnits = true
        d.todayDistanceMeters = 500
        XCTAssertEqual(d.formattedDistance, "500 m")
    }

    func testFormattedDistanceMetricLongDistance() {
        var d = SharedLocationData.empty
        d.usesMetricDistanceUnits = true
        d.todayDistanceMeters = 2500
        XCTAssertEqual(d.formattedDistance, "2.5 km")
    }

    func testFormattedDistanceImperialShortDistance() {
        var d = SharedLocationData.empty
        d.usesMetricDistanceUnits = false
        d.todayDistanceMeters = 30 // ~98 ft
        // 30 * 3.28084 ≈ 98 ft, miles = 30/1609.344 ≈ 0.019 < 0.1
        XCTAssertEqual(d.formattedDistance, "98 ft")
    }

    func testFormattedDistanceImperialLongDistance() {
        var d = SharedLocationData.empty
        d.usesMetricDistanceUnits = false
        d.todayDistanceMeters = 5000 // ~3.1 mi
        XCTAssertEqual(d.formattedDistance, "3.1 mi")
    }

    func testFormattedDistanceDefaultsToMetricWhenNil() {
        var d = SharedLocationData.empty
        d.usesMetricDistanceUnits = nil
        d.todayDistanceMeters = 500
        XCTAssertEqual(d.formattedDistance, "500 m",
                       "nil should fall back to metric")
    }
}

@MainActor
final class AppReviewPromptCoordinatorTests: XCTestCase {
    private typealias Key = AppReviewPromptCoordinator.DefaultsKey
    private var defaults: UserDefaults!
    private var suiteName: String!
    private var calendar: Calendar!
    private var clock: Date!
    private var screenIsClear = true
    private var requestCount = 0

    override func setUp() {
        super.setUp()
        suiteName = "AppReviewPromptCoordinatorTests.\(UUID().uuidString)"
        defaults = UserDefaults(suiteName: suiteName)!
        calendar = Calendar(identifier: .gregorian)
        calendar.timeZone = TimeZone(secondsFromGMT: 0)!
        clock = date(day: 1, hour: 9)
        screenIsClear = true
        requestCount = 0
    }

    override func tearDown() {
        defaults.removePersistentDomain(forName: suiteName)
        defaults = nil
        suiteName = nil
        calendar = nil
        clock = nil
        super.tearDown()
    }

    func testAppUseAloneNeverRequestsAReview() {
        let coordinator = makeCoordinator()

        use(coordinator, day: 1)
        use(coordinator, day: 2)
        use(coordinator, day: 3)

        XCTAssertEqual(coordinator.recordedUseDayCount, 3)
        XCTAssertEqual(requestCount, 0)
    }

    func testQualifyingSuccessOnSecondUseDayRequestsAtTheSuccessMoment() {
        let coordinator = makeCoordinator()
        use(coordinator, day: 1)
        succeed(coordinator, .countryDaysExport, day: 1)
        XCTAssertEqual(requestCount, 0, "one day of use isn't enough")

        use(coordinator, day: 2)
        XCTAssertEqual(requestCount, 0, "resume on day two must not prompt")

        succeed(coordinator, .countryDaysExport, day: 2)
        XCTAssertEqual(requestCount, 1)
        XCTAssertEqual(coordinator.lastRequestedVersion, "1.0")
        XCTAssertEqual(coordinator.lastRequestDate, clock)
    }

    func testEveryQualifyingSourceCanRequest() {
        for source in AppReviewPromptSuccess.allCases where source.qualifies {
            defaults.removePersistentDomain(forName: suiteName)
            requestCount = 0
            let coordinator = makeCoordinator()
            use(coordinator, day: 1)
            succeed(coordinator, source, day: 2)
            XCTAssertEqual(requestCount, 1, "\(source) should request")
        }
    }

    func testExcludedSourcesNeitherRequestNorCountAUseDay() {
        XCTAssertFalse(AppReviewPromptSuccess.backupBeforeClear.qualifies)
        XCTAssertFalse(AppReviewPromptSuccess.scheduledExport.qualifies)
        let coordinator = makeCoordinator()
        use(coordinator, day: 1)

        succeed(coordinator, .backupBeforeClear, day: 2)
        succeed(coordinator, .scheduledExport, day: 2)

        XCTAssertEqual(coordinator.recordedUseDayCount, 1)
        XCTAssertEqual(requestCount, 0)
        XCTAssertNil(coordinator.lastRequestDate)
    }

    func testBlockedScreenSkipsWithoutRecordingAndALaterSuccessCanAsk() {
        let coordinator = makeCoordinator()
        use(coordinator, day: 1)

        screenIsClear = false
        succeed(coordinator, .folderExport, day: 2)
        XCTAssertEqual(requestCount, 0)
        XCTAssertNil(coordinator.lastRequestDate)

        screenIsClear = true
        use(coordinator, day: 2)
        XCTAssertEqual(requestCount, 0, "a skipped request isn't retried on resume")

        succeed(coordinator, .folderExport, day: 2)
        XCTAssertEqual(requestCount, 1)
    }

    func testUnavailableSceneDoesNotRecordAnAttempt() {
        let coordinator = makeCoordinator(requestReview: { false })
        use(coordinator, day: 1)
        succeed(coordinator, .shareExport, day: 2)
        XCTAssertNil(coordinator.lastRequestDate)
        XCTAssertNil(coordinator.lastRequestedVersion)
    }

    func testAtMostOncePerVersion() {
        let coordinator = makeCoordinator()
        use(coordinator, day: 1)
        succeed(coordinator, .shareExport, day: 2)
        XCTAssertEqual(requestCount, 1)

        clock = clock.addingTimeInterval(200 * 86_400)
        succeed(coordinator, .shareExport, day: clock)
        XCTAssertEqual(requestCount, 1, "same version never asks twice, even after the cooldown")
    }

    func testNewVersionWaitsForTheCooldown() {
        let first = makeCoordinator(version: "1.0")
        use(first, day: 1)
        succeed(first, .shareExport, day: 2)
        XCTAssertEqual(requestCount, 1)
        let askedAt = clock!

        let second = makeCoordinator(version: "1.1")
        clock = askedAt.addingTimeInterval(AppReviewPromptCoordinator.cooldown - 60)
        succeed(second, .shareExport, day: clock)
        XCTAssertEqual(requestCount, 1, "inside 120 days")

        clock = askedAt.addingTimeInterval(AppReviewPromptCoordinator.cooldown)
        succeed(second, .shareExport, day: clock)
        XCTAssertEqual(requestCount, 2)
        XCTAssertEqual(second.lastRequestedVersion, "1.1")
    }

    func testLegacyAskedUserStartsTheCooldownAtMigration() {
        defaults.set(true, forKey: Key.legacyRequested)
        defaults.set(true, forKey: Key.legacyCompletedFileExport)
        defaults.set(["2026-05-01", "2026-05-02"], forKey: Key.usedDayIDs)

        let coordinator = makeCoordinator()
        let migratedAt = clock!

        XCTAssertEqual(coordinator.lastRequestDate, migratedAt)
        XCTAssertNil(coordinator.lastRequestedVersion)
        XCTAssertNil(defaults.object(forKey: Key.legacyRequested))
        XCTAssertNil(defaults.object(forKey: Key.legacyCompletedFileExport))

        succeed(coordinator, .shareExport, day: 2)
        XCTAssertEqual(requestCount, 0)

        clock = migratedAt.addingTimeInterval(AppReviewPromptCoordinator.cooldown)
        let later = makeCoordinator()
        XCTAssertEqual(later.lastRequestDate, migratedAt, "migration runs once")
        succeed(later, .shareExport, day: clock)
        XCTAssertEqual(requestCount, 1)
    }

    func testLegacyUserWhoWasNeverAskedIsNotPutInCooldown() {
        defaults.set(true, forKey: Key.legacyCompletedFileExport)
        let coordinator = makeCoordinator()
        XCTAssertNil(coordinator.lastRequestDate)
        use(coordinator, day: 1)
        succeed(coordinator, .shareExport, day: 2)
        XCTAssertEqual(requestCount, 1)
    }

    func testStoredUseDaysAreCapped() {
        let coordinator = makeCoordinator()
        for offset in 0..<100 {
            coordinator.recordAppUse(on: date(day: 1, hour: 9).addingTimeInterval(Double(offset) * 86_400))
        }
        XCTAssertEqual(coordinator.recordedUseDayCount, AppReviewPromptCoordinator.maxStoredUseDays)
        let stored = defaults.stringArray(forKey: Key.usedDayIDs) ?? []
        XCTAssertEqual(stored.last, "2026-09-08", "the most recent days are kept")

        defaults.set((0..<60).map { String(format: "2025-%02d-%02d", $0 / 28 + 1, $0 % 28 + 1) }, forKey: Key.usedDayIDs)
        XCTAssertEqual(makeCoordinator().recordedUseDayCount, AppReviewPromptCoordinator.maxStoredUseDays,
                       "an oversized legacy list is trimmed at migration")
    }

    func testMultipleEventsOnSameDayOnlyCountAsOneUseDay() {
        let coordinator = makeCoordinator()
        use(coordinator, day: 1)
        succeed(coordinator, .countryDaysExport, day: 1)
        coordinator.recordAppUse(on: date(day: 1, hour: 20))

        XCTAssertEqual(coordinator.recordedUseDayCount, 1)
        XCTAssertEqual(requestCount, 0)
    }

    private func makeCoordinator(
        version: String = "1.0",
        requestReview: (@MainActor () -> Bool)? = nil
    ) -> AppReviewPromptCoordinator {
        AppReviewPromptCoordinator(
            defaults: defaults,
            calendar: calendar,
            appVersion: version,
            now: { [unowned self] in self.clock },
            reviewRequestDelay: 0,
            canPresent: { [unowned self] in self.screenIsClear },
            requestReview: requestReview ?? { [unowned self] in self.requestCount += 1; return true }
        )
    }

    private func use(_ coordinator: AppReviewPromptCoordinator, day: Int) {
        clock = date(day: day, hour: 9)
        coordinator.recordAppUse()
    }

    private func succeed(_ coordinator: AppReviewPromptCoordinator, _ source: AppReviewPromptSuccess, day: Int) {
        clock = date(day: day, hour: 10)
        coordinator.recordSuccess(source)
    }

    private func succeed(_ coordinator: AppReviewPromptCoordinator, _ source: AppReviewPromptSuccess, day: Date) {
        clock = day
        coordinator.recordSuccess(source)
    }

    private func date(day: Int, hour: Int) -> Date {
        calendar.date(from: DateComponents(year: 2026, month: 6, day: day, hour: hour))!
    }
}
