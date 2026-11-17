import XCTest
import SwiftData
@testable import IsoMe

/// An outing's travel mode: picked by hand, applied from the Settings default, or inferred from GPS.
@MainActor
final class TravelModeTests: XCTestCase {
    private let base = Date(timeIntervalSince1970: 1_800_000_000)

    private func makeContainer(url: URL? = nil) throws -> ModelContainer {
        let configuration = url.map { ModelConfiguration(schema: AppModelStore.schema, url: $0) }
            ?? ModelConfiguration(schema: AppModelStore.schema, isStoredInMemoryOnly: true)
        return try ModelContainer(for: AppModelStore.schema, configurations: [configuration])
    }

    /// Points due east at a steady speed, one every `interval` seconds.
    private func straightPoints(count: Int, metersPerSecond: Double, interval: TimeInterval = 10, start: Date? = nil) -> [LocationPoint] {
        let start = start ?? base
        let metersPerDegree = 111_320.0
        return (0..<count).map { index in
            let meters = Double(index) * metersPerSecond * interval
            return LocationPoint(latitude: 0, longitude: meters / metersPerDegree,
                                 timestamp: start.addingTimeInterval(Double(index) * interval),
                                 horizontalAccuracy: 5)
        }
    }

    private func summary(for session: RecordingSession?, points: [LocationPoint]) -> RecordingSessionSummary {
        RecordingSessionSummary(
            id: "test", storedSession: session, sequenceNumber: 1,
            startedAt: points.first?.timestamp ?? base, endedAt: points.last?.timestamp ?? base,
            points: points, isInferred: session == nil, isActive: false,
            now: (points.last?.timestamp ?? base).addingTimeInterval(60)
        )
    }

    // MARK: - Editing

    func testManualModeOverridesInferredModeInTitleAndSummary() {
        let points = straightPoints(count: 60, metersPerSecond: 1.4)
        let session = RecordingSession(startedAt: points[0].timestamp, endedAt: points.last!.timestamp)
        XCTAssertEqual(summary(for: session, points: points).travelMode, .walk)

        session.setManualTravelMode(.drive)

        let edited = summary(for: session, points: points)
        XCTAssertEqual(edited.travelMode, .drive)
        XCTAssertEqual(edited.inferredTravelMode, .walk)
        XCTAssertTrue(edited.hasManualTravelMode)
        XCTAssertTrue(edited.automaticTitle(visits: []).hasSuffix(RecordingSessionSummary.travelNoun(for: .drive)))
    }

    func testClearingManualModeReturnsToAutomatic() {
        let session = RecordingSession(startedAt: base)
        session.setManualTravelMode(.bike)
        session.setManualTravelMode(nil)
        XCTAssertNil(session.storedTravelMode)
        XCTAssertNil(session.travelModeSource)
        XCTAssertNil(session.travelModeSourceRawValue)
    }

    func testViewModelEditPersistsAcrossStoreReopen() throws {
        let directory = FileManager.default.temporaryDirectory.appendingPathComponent(UUID().uuidString, isDirectory: true)
        try FileManager.default.createDirectory(at: directory, withIntermediateDirectories: true)
        addTeardownBlock { try? FileManager.default.removeItem(at: directory) }
        let url = directory.appendingPathComponent("TravelMode.store")
        let id = UUID()

        do {
            let container = try makeContainer(url: url)
            let session = RecordingSession(id: id, startedAt: base, endedAt: base.addingTimeInterval(600))
            container.mainContext.insert(session)
            try container.mainContext.save()
            let viewModel = LocationViewModel(
                modelContext: container.mainContext,
                locationManager: makeIsolatedLocationManager()
            )
            viewModel.updateRecordingSessionTravelMode(session, mode: .transit)
        }

        let reopened = try makeContainer(url: url)
        let descriptor = FetchDescriptor<RecordingSession>(predicate: #Predicate { $0.id == id })
        let restored = try XCTUnwrap(reopened.mainContext.fetch(descriptor).first)
        XCTAssertEqual(restored.storedTravelMode, .transit)
        XCTAssertEqual(restored.travelModeSource, .manual)
    }

    // MARK: - Default mode

    func testDefaultPreferenceIsAutomatic() {
        let defaults = makeIsolatedDefaults()
        XCTAssertEqual(TravelModePreference.stored(in: defaults), .automatic)
        XCTAssertNil(TravelModePreference.stored(in: defaults).mode)
        XCTAssertEqual(TravelModePreference(rawValue: "unknown"), .automatic)
        XCTAssertEqual(TravelModePreference(rawValue: TravelModePreference.fixed(.bike).rawValue), .fixed(.bike))
    }

    func testNewRecordingGetsDefaultModeWhenOneIsSet() throws {
        let defaults = makeIsolatedDefaults()
        defaults.set(TravelMode.drive.rawValue, forKey: TravelModePreference.storageKey)
        let manager = makeIsolatedLocationManager(defaults: defaults)
        let container = try makeContainer()

        manager.isTrackingEnabled = true
        manager.trackingStartTime = base
        manager.setModelContext(container.mainContext)

        let session = try XCTUnwrap(container.mainContext.fetch(FetchDescriptor<RecordingSession>()).first)
        XCTAssertEqual(session.storedTravelMode, .drive)
        XCTAssertEqual(session.travelModeSource, .defaultSetting)
        XCTAssertFalse(summary(for: session, points: straightPoints(count: 10, metersPerSecond: 1.4)).hasManualTravelMode)
    }

    func testNewRecordingStaysAutomaticByDefault() throws {
        let manager = makeIsolatedLocationManager()
        let container = try makeContainer()

        manager.isTrackingEnabled = true
        manager.trackingStartTime = base
        manager.setModelContext(container.mainContext)

        let session = try XCTUnwrap(container.mainContext.fetch(FetchDescriptor<RecordingSession>()).first)
        XCTAssertNil(session.storedTravelMode)
        XCTAssertNil(session.travelModeSource)
    }

    func testDefaultNeverOverwritesAStoredMode() {
        let session = RecordingSession(startedAt: base)
        session.setManualTravelMode(.walk)
        session.applyDefaultTravelMode(.fixed(.drive))
        XCTAssertEqual(session.storedTravelMode, .walk)
        XCTAssertEqual(session.travelModeSource, .manual)
    }

    func testReclassificationLeavesManualModeAlone() {
        let session = RecordingSession(startedAt: base)
        session.setManualTravelMode(.bike)
        // Any amount of new GPS data changes only the inferred mode.
        let drivePoints = straightPoints(count: 60, metersPerSecond: 20)
        let outing = summary(for: session, points: drivePoints)
        XCTAssertEqual(outing.inferredTravelMode, .drive)
        XCTAssertEqual(outing.travelMode, .bike)
    }

    // MARK: - Classification

    /// An outing that stays open after arrival used to average its distance over the whole span,
    /// so a drive followed by two hours parked read as a walk. Moving speed reads it as a drive.
    func testInferredModeIgnoresStoppedTimeAfterArrival() {
        let drive = straightPoints(count: 60, metersPerSecond: 15)
        let parkedStart = drive.last!.timestamp
        let parked = (1...24).map { index in
            LocationPoint(latitude: drive.last!.latitude, longitude: drive.last!.longitude,
                          timestamp: parkedStart.addingTimeInterval(Double(index) * 300), horizontalAccuracy: 5)
        }
        let outing = summary(for: nil, points: drive + parked)
        XCTAssertEqual(TravelMode.infer(averageSpeed: outing.averageSpeedMetersPerSecond ?? 0), .walk,
                       "The whole-span average is what used to name this outing")
        XCTAssertEqual(outing.inferredTravelMode, .drive)
    }

    /// A walk to the car then a fast drive averages out at cycling speed; the top speed rules a bike out.
    func testMixedWalkAndDriveIsNotABike() {
        let walk = straightPoints(count: 90, metersPerSecond: 1.4)
        let drive = straightPoints(count: 40, metersPerSecond: 16, start: walk.last!.timestamp.addingTimeInterval(10)).map { point in
            LocationPoint(latitude: point.latitude, longitude: point.longitude + walk.last!.longitude,
                          timestamp: point.timestamp, horizontalAccuracy: 5)
        }
        let metrics = RouteMetrics(points: walk + drive)
        XCTAssertEqual(TravelMode.infer(meters: metrics.distanceMeters, seconds: metrics.movingTime), .bike)
        XCTAssertEqual(metrics.inferredTravelMode, .drive)
    }

    func testSteadyRideStaysABike() {
        XCTAssertEqual(RouteMetrics(points: straightPoints(count: 120, metersPerSecond: 6)).inferredTravelMode, .bike)
    }

    func testOtherIsNeverInferred() {
        for speed in stride(from: 0.0, through: 200.0, by: 0.5) {
            XCTAssertNotEqual(TravelMode.infer(averageSpeed: speed), .other)
        }
    }

    // MARK: - Exports and backups

    func testOutingExportsCarryEditedMode() throws {
        let points = straightPoints(count: 30, metersPerSecond: 1.4)
        let session = RecordingSession(startedAt: points[0].timestamp, endedAt: points.last!.timestamp)
        session.setManualTravelMode(.transit)
        let outing = summary(for: session, points: points)

        let json = try JSONSerialization.jsonObject(with: ExportService.exportOutingsToJSON(outings: [outing], visits: [])) as? [String: Any]
        let exported = try XCTUnwrap((json?["outings"] as? [[String: Any]])?.first)
        XCTAssertEqual(exported["travelMode"] as? String, "transit")
        XCTAssertEqual(exported["travelModeSource"] as? String, "manual")

        let csv = String(decoding: ExportService.exportOutingsToCSV(outings: [outing], visits: []), as: UTF8.self)
        let lines = csv.split(separator: "\n")
        XCTAssertTrue(lines[0].hasSuffix("travel_mode,travel_mode_source"))
        XCTAssertTrue(lines[1].hasSuffix("transit,manual"))

        let inferred = summary(for: nil, points: points)
        let inferredCSV = String(decoding: ExportService.exportOutingsToCSV(outings: [inferred], visits: []), as: UTF8.self)
        XCTAssertTrue(inferredCSV.split(separator: "\n")[1].hasSuffix("walk,automatic"))
    }

    func testBackupRecordKeepsMode() {
        let session = RecordingSession(startedAt: base)
        session.applyDefaultTravelMode(.fixed(.fly))
        let restored = WheredayBackup.RecordingSessionRecord(session).makeModel()
        XCTAssertEqual(restored.storedTravelMode, .fly)
        XCTAssertEqual(restored.travelModeSource, .defaultSetting)
    }

    // MARK: - Top speed rule

    /// One GPS jump out and back touches at most two speed buckets; a walk stays a walk.
    func testSingleGPSJumpDoesNotMakeAWalkADrive() {
        var points = straightPoints(count: 180, metersPerSecond: 1.2)
        let jumped = points[90]
        points[90] = LocationPoint(latitude: jumped.latitude + 0.005, longitude: jumped.longitude,
                                   timestamp: jumped.timestamp, horizontalAccuracy: 5)
        let metrics = RouteMetrics(points: points)
        XCTAssertGreaterThan(metrics.topSpeedMetersPerSecond ?? 0, RouteMetrics.motorizedSpeedThreshold,
                             "The jump is fast enough to have tripped a top-speed-only rule")
        XCTAssertEqual(metrics.inferredTravelMode, .walk)
    }

    /// A brief fast descent on a long ride stays a ride.
    func testShortFastDescentKeepsABikeABike() {
        let ride = straightPoints(count: 360, metersPerSecond: 6)
        let descent = straightPoints(count: 10, metersPerSecond: 15, start: ride.last!.timestamp.addingTimeInterval(10)).map { point in
            LocationPoint(latitude: point.latitude, longitude: point.longitude + ride.last!.longitude,
                          timestamp: point.timestamp, horizontalAccuracy: 5)
        }
        XCTAssertEqual(RouteMetrics(points: ride + descent).inferredTravelMode, .bike)
    }

    // MARK: - Moves between stays and stored modes

    func testStoredModeLookupNeedsAnOutingCoveringMostOfTheStretch() {
        let drive = RecordingSession(startedAt: base, endedAt: base.addingTimeInterval(600))
        drive.setManualTravelMode(.drive)
        let automatic = RecordingSession(startedAt: base.addingTimeInterval(600), endedAt: base.addingTimeInterval(1200))
        let lookup = StoredTravelModeLookup(sessions: [drive, automatic])

        XCTAssertEqual(lookup.mode(from: base.addingTimeInterval(60), to: base.addingTimeInterval(500)), .drive)
        XCTAssertEqual(lookup.mode(from: base.addingTimeInterval(300), to: base.addingTimeInterval(800)), .drive)
        XCTAssertNil(lookup.mode(from: base.addingTimeInterval(500), to: base.addingTimeInterval(1200)),
                     "Less than half the stretch is covered by an outing with a stored mode")
        XCTAssertEqual(lookup.mode(from: base.addingTimeInterval(100), to: base.addingTimeInterval(100)), .drive)
    }

    /// A drive between two stays, then five minutes standing still before the next stay is detected,
    /// used to divide the distance by the whole gap and read as a run.
    func testDayMoveUsesMovingSpeedNotTheWholeGap() {
        let drive = straightPoints(count: 40, metersPerSecond: 9, start: base.addingTimeInterval(600))
        let arrival = drive.last!.timestamp.addingTimeInterval(20 * 60)
        let visits = [
            Visit(latitude: 0, longitude: 0, arrivedAt: base, departedAt: base.addingTimeInterval(600)),
            Visit(latitude: 0, longitude: drive.last!.longitude, arrivedAt: arrival, departedAt: arrival.addingTimeInterval(600))
        ]
        let day = TodayDay(visits: visits, points: drive, startOfDay: base, now: arrival.addingTimeInterval(600),
                           isCurrentVisit: { _ in false })
        let move = day.stays.last?.arrival
        XCTAssertEqual(TravelMode.infer(meters: move?.distance ?? 0, seconds: move?.duration ?? 1), .run)
        XCTAssertEqual(move?.mode, .drive)
    }

    func testDayMoveShowsTheModeStoredOnItsOuting() {
        let walk = straightPoints(count: 60, metersPerSecond: 1.4, start: base.addingTimeInterval(600))
        let arrival = walk.last!.timestamp.addingTimeInterval(10)
        let visits = [
            Visit(latitude: 0, longitude: 0, arrivedAt: base, departedAt: base.addingTimeInterval(600)),
            Visit(latitude: 0, longitude: walk.last!.longitude, arrivedAt: arrival, departedAt: arrival.addingTimeInterval(600))
        ]
        let session = RecordingSession(startedAt: base.addingTimeInterval(590), endedAt: arrival)
        session.setManualTravelMode(.transit)
        let day = TodayDay(visits: visits, points: walk, startOfDay: base, now: arrival.addingTimeInterval(600),
                           storedModes: StoredTravelModeLookup(sessions: [session]), isCurrentVisit: { _ in false })
        XCTAssertEqual(day.stays.last?.arrival?.mode, .transit)
    }

    // MARK: - Inferred outings

    func testChoosingAModeSavesAnInferredOuting() throws {
        let container = try makeContainer()
        let viewModel = LocationViewModel(modelContext: container.mainContext, locationManager: makeIsolatedLocationManager())
        let points = straightPoints(count: 30, metersPerSecond: 1.4)
        let inferred = summary(for: nil, points: points)
        XCTAssertTrue(inferred.canEditTravelMode)

        XCTAssertNil(viewModel.setTravelMode(nil, for: inferred), "Automatic on an unsaved outing saves nothing")
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<RecordingSession>()), 0)

        let saved = try XCTUnwrap(viewModel.setTravelMode(.bike, for: inferred))
        XCTAssertEqual(saved.startedAt, points.first!.timestamp)
        XCTAssertEqual(saved.endedAt, points.last!.timestamp)
        XCTAssertEqual(saved.storedTravelMode, .bike)
        XCTAssertEqual(saved.travelModeSource, .manual)

        // The rebuilt outing covers the same points and keeps the mode.
        let rebuilt = RecordingSessionBuilder.summaries(storedSessions: [saved], points: points, activeTrackingStart: nil)
        XCTAssertEqual(rebuilt.count, 1)
        XCTAssertEqual(rebuilt.first?.pointCount, points.count)
        XCTAssertEqual(rebuilt.first?.travelMode, .bike)
    }

    func testLiveInferredOutingCannotBeEdited() {
        let points = straightPoints(count: 10, metersPerSecond: 1.4)
        let live = RecordingSessionSummary(id: "live", storedSession: nil, sequenceNumber: 1,
                                           startedAt: points[0].timestamp, endedAt: nil, points: points,
                                           isInferred: true, isActive: true, now: points.last!.timestamp)
        XCTAssertFalse(live.canEditTravelMode)
    }
}
