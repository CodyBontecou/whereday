import XCTest
import SwiftData
import CoreLocation
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
        session.savedFromInferredOuting = true
        let restored = WheredayBackup.RecordingSessionRecord(session).makeModel()
        XCTAssertEqual(restored.storedTravelMode, .fly)
        XCTAssertEqual(restored.travelModeSource, .defaultSetting)
        XCTAssertEqual(restored.savedFromInferredOuting, true)
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

    func testDeletingAnInferredOutingSavedForItsModeRemovesTheSavedSession() throws {
        let container = try makeContainer()
        let context = container.mainContext
        let viewModel = LocationViewModel(modelContext: context, locationManager: makeIsolatedLocationManager())
        let points = straightPoints(count: 30, metersPerSecond: 1.4)
        points.forEach(context.insert)
        try context.save()
        let inferred = summary(for: nil, points: points)

        // The detail screen saves the inferred outing when a mode is picked, then deletes it.
        let saved = try XCTUnwrap(viewModel.setTravelMode(.bike, for: inferred))
        viewModel.deleteRecordingSession(inferred.backed(by: saved))

        XCTAssertEqual(try context.fetchCount(FetchDescriptor<LocationPoint>()), 0)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<RecordingSession>()), 0,
                       "No empty outing is left behind with the mode but none of the points")
    }

    func testBackedSummaryKeepsTheOutingAndAddsTheSavedSession() throws {
        let points = straightPoints(count: 30, metersPerSecond: 1.4)
        let inferred = summary(for: nil, points: points)
        let saved = RecordingSession(startedAt: inferred.startedAt, endedAt: inferred.endedAt)
        let backed = inferred.backed(by: saved)
        XCTAssertTrue(backed.storedSession === saved)
        XCTAssertEqual(backed.id, inferred.id)
        XCTAssertEqual(backed.startedAt, inferred.startedAt)
        XCTAssertEqual(backed.endedAt, inferred.endedAt)
        XCTAssertEqual(backed.pointCount, inferred.pointCount)
        XCTAssertEqual(backed.isInferred, inferred.isInferred)
        XCTAssertEqual(backed.dayRange, inferred.dayRange)
    }

    func testLiveInferredOutingCannotBeEdited() {
        let points = straightPoints(count: 10, metersPerSecond: 1.4)
        let live = RecordingSessionSummary(id: "live", storedSession: nil, sequenceNumber: 1,
                                           startedAt: points[0].timestamp, endedAt: nil, points: points,
                                           isInferred: true, isActive: true, now: points.last!.timestamp)
        XCTAssertFalse(live.canEditTravelMode)
    }

    // MARK: - Live Activity

    func testLiveActivityShowsStoredModeOnlyWhileMoving() {
        let start = base
        let moving = (0..<5).map { index in
            CLLocation(coordinate: CLLocationCoordinate2D(latitude: 0, longitude: Double(index) * 0.0005),
                       altitude: 0, horizontalAccuracy: 5, verticalAccuracy: 5,
                       timestamp: start.addingTimeInterval(Double(index) * 10))
        }
        XCTAssertEqual(LiveActivityManager.liveTravelMode(for: moving, stored: nil), .bike)
        XCTAssertEqual(LiveActivityManager.liveTravelMode(for: moving, stored: .drive), .drive)
        let still = moving.map { CLLocation(coordinate: moving[0].coordinate, altitude: 0, horizontalAccuracy: 5,
                                            verticalAccuracy: 5, timestamp: $0.timestamp) }
        XCTAssertNil(LiveActivityManager.liveTravelMode(for: still, stored: .drive))
    }

    func testActiveRecordingCarriesDefaultModeForLiveActivity() throws {
        let defaults = makeIsolatedDefaults()
        defaults.set(TravelMode.transit.rawValue, forKey: TravelModePreference.storageKey)
        let manager = makeIsolatedLocationManager(defaults: defaults)
        let container = try makeContainer()
        manager.isTrackingEnabled = true
        manager.trackingStartTime = base
        manager.setModelContext(container.mainContext)
        XCTAssertEqual(manager.activeRecordingTravelMode, .transit)
    }

    // MARK: - Caching

    func testSummaryInfersItsModeOnceAcrossRendersAndCopies() {
        let outing = summary(for: nil, points: straightPoints(count: 120, metersPerSecond: 6))
        XCTAssertEqual(outing.inferredTravelModeComputations, 0)
        let copy = outing
        for _ in 0..<5 {
            _ = outing.automaticTitle(visits: [])
            _ = copy.travelMode
        }
        XCTAssertEqual(outing.inferredTravelMode, .bike)
        XCTAssertEqual(outing.inferredTravelModeComputations, 1)
    }

    func testLiveActivityModeIsFetchedOncePerRecordingAndRefreshedAfterAnEdit() throws {
        let defaults = makeIsolatedDefaults()
        defaults.set(TravelMode.walk.rawValue, forKey: TravelModePreference.storageKey)
        let manager = makeIsolatedLocationManager(defaults: defaults)
        let container = try makeContainer()
        manager.isTrackingEnabled = true
        manager.trackingStartTime = base
        manager.setModelContext(container.mainContext)

        for _ in 0..<10 { XCTAssertEqual(manager.activeRecordingTravelMode, .walk) }
        XCTAssertEqual(manager.activeRecordingTravelModeFetchCount, 1)

        let viewModel = LocationViewModel(modelContext: container.mainContext, locationManager: manager)
        let session = try XCTUnwrap(container.mainContext.fetch(FetchDescriptor<RecordingSession>()).first)
        viewModel.updateRecordingSessionTravelMode(session, mode: .bike)
        XCTAssertEqual(manager.activeRecordingTravelMode, .bike)
        XCTAssertEqual(manager.activeRecordingTravelModeFetchCount, 2)
    }

    // MARK: - Saving an inferred outing

    /// Saving the middle of three inferred outings leaves the other two exactly as they were.
    func testSavingAnInferredOutingLeavesItsNeighboursUnchanged() throws {
        let gap = RecordingSessionBuilder.defaultGapThreshold + 60
        let first = straightPoints(count: 20, metersPerSecond: 1.4)
        let middle = straightPoints(count: 20, metersPerSecond: 6, start: first.last!.timestamp.addingTimeInterval(gap))
        let last = straightPoints(count: 20, metersPerSecond: 15, start: middle.last!.timestamp.addingTimeInterval(gap))
        let points = first + middle + last
        let now = last.last!.timestamp.addingTimeInterval(3600)
        let before = RecordingSessionBuilder.summaries(storedSessions: [], points: points, activeTrackingStart: nil, now: now)
        XCTAssertEqual(before.count, 3)
        XCTAssertTrue(before.allSatisfy(\.isInferred))

        let container = try makeContainer()
        let viewModel = LocationViewModel(modelContext: container.mainContext, locationManager: makeIsolatedLocationManager())
        let saved = try XCTUnwrap(viewModel.setTravelMode(.transit, for: before[1]))

        let after = RecordingSessionBuilder.summaries(storedSessions: [saved], points: points, activeTrackingStart: nil, now: now)
        XCTAssertEqual(after.count, 3)
        for index in [0, 2] {
            XCTAssertTrue(after[index].isInferred)
            XCTAssertNil(after[index].storedSession)
            XCTAssertEqual(after[index].startedAt, before[index].startedAt)
            XCTAssertEqual(after[index].endedAt, before[index].endedAt)
            XCTAssertEqual(after[index].pointCount, before[index].pointCount)
            XCTAssertEqual(after[index].travelMode, before[index].travelMode)
        }
        XCTAssertEqual(after[1].pointCount, before[1].pointCount)
        XCTAssertEqual(after[1].startedAt, before[1].startedAt)
        XCTAssertEqual(after[1].endedAt, before[1].endedAt)
        XCTAssertEqual(after[1].travelMode, .transit)
        XCTAssertTrue(after[1].isInferred, "Saving only keeps the mode; the outing is still an inferred one")

        // Saved, it still exports as inferred, with the mode chosen for it.
        let csv = String(decoding: ExportService.exportOutingsToCSV(outings: [after[1]], visits: []), as: UTF8.self)
        let row = try XCTUnwrap(csv.split(separator: "\n").last)
        XCTAssertTrue(row.hasSuffix(",true,false,,\(after[1].startPoint!.latitude),\(after[1].startPoint!.longitude),\(after[1].endPoint!.latitude),\(after[1].endPoint!.longitude),transit,manual"), String(row))
        let markdown = String(decoding: ExportService.exportOutingsToMarkdown(outings: [after[1]], visits: []), as: UTF8.self)
        XCTAssertTrue(markdown.contains("is_inferred: true\n"))
        XCTAssertTrue(markdown.contains("source: \"inferred\"\n"))
    }

    // MARK: - Localization

    func testTravelModeStringsAreTranslatedInEveryShippedLocale() throws {
        let keys = ["Moving", "Other", "Automatic", "Automatic (%@)", "Transport Mode", "Transport mode", "MODE",
                    "Default transport mode",
                    "Automatic estimates the mode from GPS speed. Other choices apply to new outings; change any outing's mode from its details.",
                    "Names, modes and notes stay on-device with the recording session. A mode you pick here is never changed automatically.",
                    "Choosing a mode saves this inferred outing, so it keeps the mode and can be named.",
                    "This outing was auto-inferred from your GPS history using your inferred outing settings. New Shortcut start/stop recordings are saved exactly and can be named. Choosing a mode saves this outing so it keeps the mode."]
        for locale in ["ar", "bn", "es", "fr", "hi", "ja", "pl", "pt-BR", "ru", "zh-Hans"] {
            let url = Bundle.main.bundleURL.appendingPathComponent("\(locale).lproj")
            let bundle = try XCTUnwrap(Bundle(url: url), "Missing compiled locale: \(locale)")
            // Some translations are spelled like the English ("Train", "MODE" in French), so check that
            // each locale has an entry rather than that it differs.
            let missing = "\u{0}missing"
            XCTAssertEqual(bundle.localizedString(forKey: "No such TravelModeTests key", value: missing, table: nil), missing)
            for key in keys + TravelMode.allCases.flatMap({ [$0.label, $0.activeLabel] }) {
                XCTAssertNotEqual(bundle.localizedString(forKey: key, value: missing, table: nil), missing, "\(locale): \(key)")
            }
        }
    }

    // MARK: - Map status lookup cache

    func testMapLookupIsBuiltOncePerReloadAndSeesAnEdit() throws {
        let container = try makeContainer()
        let viewModel = LocationViewModel(modelContext: container.mainContext, locationManager: makeIsolatedLocationManager())
        let session = RecordingSession(startedAt: base, endedAt: base.addingTimeInterval(600))
        container.mainContext.insert(session)
        try container.mainContext.save()
        viewModel.loadRecordingSessions()

        // The map's status line and the day dial both read the view model's one lookup.
        let moment = base.addingTimeInterval(300)
        let builds = viewModel.storedTravelModeBuildCount
        for _ in 0..<20 {
            XCTAssertNil(viewModel.storedTravelModes.mode(from: moment, to: moment))
            _ = TodayDay(viewModel: viewModel, now: base.addingTimeInterval(3600))
        }
        XCTAssertEqual(viewModel.storedTravelModeBuildCount, builds + 1)

        viewModel.updateRecordingSessionTravelMode(session, mode: .drive)
        XCTAssertEqual(viewModel.storedTravelModes.mode(from: moment, to: moment), .drive)
        XCTAssertEqual(viewModel.storedTravelModeBuildCount, builds + 2)
    }

    /// A kept lookup still covers an outing that is recording, up to whatever moment is asked about.
    func testKeptLookupExtendsAnOpenOutingToTheMomentAsked() {
        let live = RecordingSession(startedAt: base)
        live.setManualTravelMode(.bike)
        let lookup = StoredTravelModeLookup(sessions: [live])
        let later = base.addingTimeInterval(3600)
        XCTAssertEqual(lookup.mode(from: later, to: later, now: later), .bike)
        XCTAssertNil(lookup.mode(from: later, to: later, now: base.addingTimeInterval(60)))
        XCTAssertNil(StoredTravelModeLookup(sessions: [live], now: base.addingTimeInterval(60)).mode(from: later, to: later, now: later),
                     "An explicit now closes the open outing there, as the day dial expects")
    }

    // MARK: - CSV column order

    /// Columns are only appended: every column an existing CSV consumer reads keeps its name and position.
    func testOutingCSVKeepsEveryExistingColumnInPlace() {
        let previous = ["id", "title", "sequence_number", "started_at", "ended_at", "effective_end_at",
                        "duration_seconds", "distance_meters", "average_speed_mps", "point_count", "outlier_count",
                        "visit_count", "is_inferred", "is_active", "notes", "start_latitude", "start_longitude",
                        "end_latitude", "end_longitude"]
        let csv = String(decoding: ExportService.exportOutingsToCSV(outings: [], visits: []), as: UTF8.self)
        let header = csv.split(separator: "\n").first.map { $0.split(separator: ",").map(String.init) } ?? []
        XCTAssertEqual(Array(header.prefix(previous.count)), previous)
        XCTAssertEqual(Array(header.dropFirst(previous.count)), ["travel_mode", "travel_mode_source"])
    }

    // MARK: - Returning a saved inferred outing to inference

    func testChoosingAutomaticReturnsASavedInferredOutingToInference() throws {
        let gap = RecordingSessionBuilder.defaultGapThreshold + 60
        let first = straightPoints(count: 20, metersPerSecond: 1.4)
        let second = straightPoints(count: 20, metersPerSecond: 6, start: first.last!.timestamp.addingTimeInterval(gap))
        let points = first + second
        let now = second.last!.timestamp.addingTimeInterval(3600)
        let container = try makeContainer()
        for point in points { container.mainContext.insert(point) }
        let viewModel = LocationViewModel(modelContext: container.mainContext, locationManager: makeIsolatedLocationManager())
        let before = RecordingSessionBuilder.summaries(storedSessions: [], points: points, activeTrackingStart: nil, now: now)

        let saved = try XCTUnwrap(viewModel.setTravelMode(.drive, for: before[1]))
        XCTAssertEqual(saved.savedFromInferredOuting, true)
        let edited = RecordingSessionBuilder.summaries(storedSessions: [saved], points: points, activeTrackingStart: nil, now: now)

        XCTAssertNil(viewModel.setTravelMode(nil, for: edited[1]))
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<RecordingSession>()), 0)
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<LocationPoint>()), points.count,
                       "Only the saved session goes; its points stay")
        let after = RecordingSessionBuilder.summaries(storedSessions: [], points: points, activeTrackingStart: nil, now: now)
        XCTAssertEqual(after.map(\.id), before.map(\.id))
        XCTAssertEqual(after.map(\.pointCount), before.map(\.pointCount))
        XCTAssertTrue(after.allSatisfy { $0.isInferred && $0.storedSession == nil })
    }

    func testAutomaticKeepsASavedInferredOutingThatWasNamed() throws {
        let points = straightPoints(count: 20, metersPerSecond: 6)
        let container = try makeContainer()
        let viewModel = LocationViewModel(modelContext: container.mainContext, locationManager: makeIsolatedLocationManager())
        let saved = try XCTUnwrap(viewModel.setTravelMode(.bike, for: summary(for: nil, points: points)))
        viewModel.updateRecordingSession(saved, customName: "Commute", notes: "")
        let named = RecordingSessionBuilder.summaries(storedSessions: [saved], points: points, activeTrackingStart: nil,
                                                      now: points.last!.timestamp.addingTimeInterval(60))

        XCTAssertTrue(viewModel.setTravelMode(nil, for: named[0]) === saved)
        XCTAssertNil(saved.storedTravelMode)
        XCTAssertEqual(saved.customName, "Commute")
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<RecordingSession>()), 1)
    }

    /// A recorded outing (not saved from inference) is never removed by choosing Automatic.
    func testAutomaticNeverRemovesARecordedOuting() throws {
        let container = try makeContainer()
        let viewModel = LocationViewModel(modelContext: container.mainContext, locationManager: makeIsolatedLocationManager())
        let points = straightPoints(count: 20, metersPerSecond: 6)
        let recorded = RecordingSession(startedAt: points[0].timestamp, endedAt: points.last!.timestamp)
        recorded.setManualTravelMode(.bike)
        container.mainContext.insert(recorded)
        try container.mainContext.save()
        XCTAssertTrue(viewModel.setTravelMode(nil, for: summary(for: recorded, points: points)) === recorded)
        XCTAssertNil(recorded.storedTravelMode)
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<RecordingSession>()), 1)
    }

    // MARK: - Markdown table column order

    /// The bulk table only appends Mode: every earlier column keeps its header and position.
    func testOutingMarkdownTableAppendsModeLast() throws {
        let points = straightPoints(count: 30, metersPerSecond: 1.4)
        let session = RecordingSession(startedAt: points[0].timestamp, endedAt: points.last!.timestamp)
        session.setManualTravelMode(.transit)
        let earlier = summary(for: nil, points: straightPoints(count: 5, metersPerSecond: 1.4, start: base.addingTimeInterval(-7200)))
        let markdown = String(decoding: ExportService.exportOutingsToMarkdown(outings: [summary(for: session, points: points), earlier], visits: []), as: UTF8.self)
        let lines = markdown.split(separator: "\n").map(String.init)
        let header = try XCTUnwrap(lines.first { $0.hasPrefix("| Start |") })
        XCTAssertEqual(header, "| Start | End | Title | Duration | Distance | Points | Visits | Notes | Mode |")
        let rows = lines.filter { $0.hasPrefix("| ") && $0 != header }
        XCTAssertEqual(rows.count, 2)
        XCTAssertTrue(rows.contains { $0.hasSuffix(" | \(TravelMode.transit.label) |") })
    }
}
