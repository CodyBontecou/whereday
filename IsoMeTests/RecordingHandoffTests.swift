import SwiftData
import XCTest
@testable import IsoMe

/// #173: one stored copy of time recorded on both Apple Watch and iPhone, with
/// no loss when the iPhone leaves or dies mid-recording.
@MainActor
final class RecordingHandoffTests: XCTestCase {
    private let t0 = Date(timeIntervalSince1970: 1_800_000_000)
    private let sessionID = UUID()
    private let policy = RecordingHandoffPolicy()

    private func point(_ offset: TimeInterval, latitude: Double = 35, accuracy: Double = 5) -> WatchLocationSyncPoint {
        WatchLocationSyncPoint(id: UUID(), sessionID: sessionID, latitude: latitude, longitude: 139,
                               timestamp: t0.addingTimeInterval(offset), altitude: nil, speed: nil,
                               horizontalAccuracy: accuracy)
    }

    private func lease(_ coveredOffset: TimeInterval, isRecording: Bool = true) -> PhoneRecordingLease {
        PhoneRecordingLease(isRecording: isRecording, sessionID: sessionID,
                            coveredUntil: t0.addingTimeInterval(coveredOffset))
    }

    private func isolatedStore() -> RecordingHandoffStore {
        RecordingHandoffStore(defaults: UserDefaults(suiteName: "recording-handoff-test.\(UUID())")!)
    }

    // MARK: - Hand-off state

    func testWatchStandsByOnlyWhileReachablePhoneHoldsFreshRecordingLease() {
        let received = t0
        XCTAssertEqual(policy.role(isRecording: false, isPhoneReachable: true, lease: lease(0),
                                   leaseReceivedAt: received, now: t0), .idle)
        XCTAssertEqual(policy.role(isRecording: true, isPhoneReachable: true, lease: lease(0),
                                   leaseReceivedAt: received, now: t0.addingTimeInterval(60)), .standingBy)
        // Left the phone behind: record at once, whatever the lease says.
        XCTAssertEqual(policy.role(isRecording: true, isPhoneReachable: false, lease: lease(0),
                                   leaseReceivedAt: received, now: t0.addingTimeInterval(60)), .recording)
        // Phone in range but not recording (stopped, no permission, no access).
        XCTAssertEqual(policy.role(isRecording: true, isPhoneReachable: true, lease: lease(0, isRecording: false),
                                   leaseReceivedAt: received, now: t0), .recording)
        // Phone stopped answering (dead, app killed): the lease lapses.
        XCTAssertEqual(policy.role(isRecording: true, isPhoneReachable: true, lease: lease(0),
                                   leaseReceivedAt: received, now: t0.addingTimeInterval(policy.leaseLifetime + 1)),
                       .recording)
        XCTAssertEqual(policy.role(isRecording: true, isPhoneReachable: true, lease: nil,
                                   leaseReceivedAt: nil, now: t0), .recording)
        // A Watch clock that jumped backwards doesn't extend trust.
        XCTAssertEqual(policy.role(isRecording: true, isPhoneReachable: true, lease: lease(0),
                                   leaseReceivedAt: t0.addingTimeInterval(600), now: t0), .recording)
    }

    func testLeaseRefreshCadence() {
        XCTAssertTrue(policy.needsLeaseRefresh(leaseReceivedAt: nil, now: t0))
        XCTAssertFalse(policy.needsLeaseRefresh(leaseReceivedAt: t0, now: t0.addingTimeInterval(30)))
        XCTAssertTrue(policy.needsLeaseRefresh(leaseReceivedAt: t0, now: t0.addingTimeInterval(policy.refreshInterval)))
        XCTAssertTrue(policy.needsLeaseRefresh(leaseReceivedAt: t0.addingTimeInterval(10), now: t0))
        // Refreshing well before expiry keeps standby continuous.
        XCTAssertLessThan(policy.refreshInterval * 2, policy.leaseLifetime)
    }

    func testTakeoverPromotesStandbyFixesAfterPhoneCoverageInOrder() {
        let buffer = [point(300), point(0), point(100), point(250), point(200)]
        let promoted = policy.promotableStandbyPoints(buffer, phoneCoveredUntil: t0.addingTimeInterval(240))
        XCTAssertEqual(promoted.map(\.timestamp), [250, 300].map { t0.addingTimeInterval($0) })
        // No lease ever arrived: keep everything.
        XCTAssertEqual(policy.promotableStandbyPoints(buffer, phoneCoveredUntil: nil).count, 5)
        XCTAssertEqual(policy.promotableStandbyPoints(buffer, phoneCoveredUntil: nil).first?.timestamp, t0)
    }

    func testStandbyBufferKeepsOnlyRecentWindowAndCap() {
        var buffer = WatchStandbyBuffer()
        buffer.append(point(0))
        buffer.append(point(WatchStandbyBuffer.window + 10))
        XCTAssertEqual(buffer.points.count, 1)
        for index in 0..<(WatchStandbyBuffer.maxCount + 20) {
            buffer.append(point(WatchStandbyBuffer.window + 20 + Double(index)))
        }
        XCTAssertEqual(buffer.points.count, WatchStandbyBuffer.maxCount)
        XCTAssertEqual(buffer.points.last?.timestamp, t0.addingTimeInterval(WatchStandbyBuffer.window + 20 + Double(WatchStandbyBuffer.maxCount + 19)))
    }

    /// Acceptance: the phone dies mid-recording; the Watch data for that span survives.
    func testPhoneDyingWhileWatchStandsByLeavesNoGap() throws {
        var queue = WatchLocationSyncState(deviceID: UUID())
        queue.activeSessionID = sessionID
        for offset in stride(from: 0.0, through: 900, by: 60) { queue.standby.append(point(offset)) }
        XCTAssertNil(queue.makePayload(), "standby fixes are never sent by themselves")

        // Last lease attested coverage to t0+300; then the phone went dark.
        let promoted = queue.promoteStandby(phoneCoveredUntil: t0.addingTimeInterval(300))
        XCTAssertTrue(queue.standby.points.isEmpty)
        XCTAssertEqual(promoted, queue.points.count)
        XCTAssertEqual(queue.points.first?.timestamp, t0.addingTimeInterval(300))
        XCTAssertEqual(queue.points.last?.timestamp, t0.addingTimeInterval(900))
        XCTAssertEqual(queue.visits.count, 1)

        // The buffer and promotions survive a Watch relaunch.
        var relaunched = queue
        relaunched.standby.append(point(960))
        let decoded = try JSONDecoder().decode(WatchLocationSyncState.self, from: JSONEncoder().encode(relaunched))
        XCTAssertEqual(decoded.standby, relaunched.standby)
        XCTAssertEqual(decoded.points, relaunched.points)
    }

    func testQueueFromBeforeHandoffDecodesWithEmptyStandby() throws {
        let legacy = #"{"deviceID":"\#(UUID().uuidString)","nextSequence":3,"visitRecordingVersion":1}"#
        let decoded = try JSONDecoder().decode(WatchLocationSyncState.self, from: Data(legacy.utf8))
        XCTAssertTrue(decoded.standby.points.isEmpty)
        XCTAssertEqual(decoded.nextSequence, 3)
    }

    // MARK: - Phone coverage

    func testCoverageBridgesOnlyWhileOpenAndWithinGap() {
        var coverage = RecordingCoverage()
        coverage.extend(to: t0)
        coverage.extend(to: t0.addingTimeInterval(600))
        XCTAssertTrue(coverage.covers(t0.addingTimeInterval(300)))
        XCTAssertFalse(coverage.covers(t0.addingTimeInterval(601)))

        // Process restart / stop: the dead span is never claimed.
        coverage.close()
        coverage.extend(to: t0.addingTimeInterval(1200))
        XCTAssertFalse(coverage.covers(t0.addingTimeInterval(900)))
        XCTAssertEqual(coverage.intervals.count, 2)

        // A long silence isn't bridged even while open.
        coverage.extend(to: t0.addingTimeInterval(1200 + RecordingCoverage.maxBridgedGap + 1))
        XCTAssertFalse(coverage.covers(t0.addingTimeInterval(1300)))
        XCTAssertEqual(coverage.intervals.count, 3)

        // A late fix inside a known span changes nothing and doesn't reopen it.
        coverage.close()
        coverage.extend(to: t0.addingTimeInterval(300))
        XCTAssertFalse(coverage.isOpen)
        XCTAssertEqual(coverage.intervals.count, 3)

        coverage.prune(now: t0.addingTimeInterval(RecordingCoverage.retention + 1000))
        XCTAssertEqual(coverage.intervals.count, 2)
    }

    // MARK: - Merge

    func testOnlyWatchPointsInsidePhoneCoverageAreRedundant() {
        var coverage = RecordingCoverage()
        coverage.extend(to: t0.addingTimeInterval(100))
        coverage.extend(to: t0.addingTimeInterval(500))
        let points = [point(50), point(100), point(300), point(500), point(501)]
        let redundant = RecordingOverlapMerge.redundantPointIDs(points, coverage: coverage)
        XCTAssertEqual(redundant, Set([points[1].id, points[2].id, points[3].id]))
        XCTAssertTrue(RecordingOverlapMerge.redundantPointIDs(points, coverage: RecordingCoverage()).isEmpty)
    }

    func testDuplicateVisitNeedsSamePlaceOverlappingTimeAndAutomaticPhoneVisit() {
        let phone = RecordingMergeVisit(id: UUID(), latitude: 35, longitude: 139,
                                        arrivedAt: t0, departedAt: t0.addingTimeInterval(3600), isAutomatic: true)
        let nearOverlap = WatchLocationSyncVisit(id: UUID(), latitude: 35.0005, longitude: 139,
                                                 arrivedAt: t0.addingTimeInterval(120), departedAt: nil,
                                                 horizontalAccuracy: 10)
        XCTAssertEqual(RecordingOverlapMerge.duplicateVisitID(for: nearOverlap, among: [phone],
                                                              now: t0.addingTimeInterval(200)), phone.id)
        var touching = nearOverlap
        touching.arrivedAt = t0.addingTimeInterval(3600)
        XCTAssertNil(RecordingOverlapMerge.duplicateVisitID(for: touching, among: [phone], now: t0.addingTimeInterval(4000)))
        var far = nearOverlap
        far.latitude = 35.01
        XCTAssertNil(RecordingOverlapMerge.duplicateVisitID(for: far, among: [phone], now: t0.addingTimeInterval(200)))
        var manual = phone
        manual.isAutomatic = false
        XCTAssertNil(RecordingOverlapMerge.duplicateVisitID(for: nearOverlap, among: [manual], now: t0.addingTimeInterval(200)))
        var closer = phone
        closer.id = UUID()
        closer.latitude = 35.0005
        XCTAssertEqual(RecordingOverlapMerge.duplicateVisitID(for: nearOverlap, among: [phone, closer],
                                                              now: t0.addingTimeInterval(200)), closer.id)
    }

    func testWatchAdjustsPhoneVisitOnlyWherePhoneWasNotRecording() {
        var coverage = RecordingCoverage()
        coverage.extend(to: t0)
        coverage.extend(to: t0.addingTimeInterval(1800))
        let phone = RecordingMergeVisit(id: UUID(), latitude: 35, longitude: 139,
                                        arrivedAt: t0.addingTimeInterval(300), departedAt: nil, isAutomatic: true)
        var watch = WatchLocationSyncVisit(id: UUID(), latitude: 35, longitude: 139,
                                           arrivedAt: t0.addingTimeInterval(200), departedAt: t0.addingTimeInterval(1000),
                                           horizontalAccuracy: 10)
        let covered = RecordingOverlapMerge.mergedTimes(target: phone, watch: watch, coverage: coverage)
        XCTAssertEqual(covered.arrivedAt, phone.arrivedAt)
        XCTAssertNil(covered.departedAt, "the phone was there and still sees the stay open")

        // The phone died at t0+1800; the Watch saw the departure later.
        watch.departedAt = t0.addingTimeInterval(2400)
        let filled = RecordingOverlapMerge.mergedTimes(target: phone, watch: watch, coverage: coverage)
        XCTAssertEqual(filled.departedAt, t0.addingTimeInterval(2400))
    }

    // MARK: - iPhone import

    private func container() throws -> ModelContainer {
        let schema = Schema([Visit.self, LocationPoint.self, RecordingSession.self,
                             PhotoMoment.self, SavedPlace.self, CountryStay.self])
        return try ModelContainer(for: schema, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
    }

    /// Acceptance: both devices record the same period → one set of points/visits.
    func testOverlappingWatchRecordingImportsOnceAndKeepsOnlyGapFill() throws {
        let container = try container()
        let context = container.mainContext
        let store = isolatedStore()
        let receiver = WatchLocationSyncReceiver()
        receiver.handoff = store
        receiver.configure(modelContainer: container, activatesConnectivity: false)

        // iPhone recorded t0…t0+600 at the place, then died.
        for offset in stride(from: 0.0, through: 600, by: 60) {
            context.insert(LocationPoint(latitude: 35, longitude: 139,
                                         timestamp: t0.addingTimeInterval(offset), horizontalAccuracy: 5))
            store.recordPhoneCoverage(at: t0.addingTimeInterval(offset))
        }
        let phoneVisit = Visit(latitude: 35, longitude: 139, arrivedAt: t0.addingTimeInterval(60))
        context.insert(phoneVisit)
        store.closePhoneCoverage()
        try context.save()

        // Watch recorded t0…t0+1200 too (hand-off didn't hold).
        var queue = WatchLocationSyncState(deviceID: UUID())
        for offset in stride(from: 0.0, through: 1200, by: 60) { queue.recordPoint(point(offset)) }
        queue.endVisit(at: t0.addingTimeInterval(1200))
        let payload = try XCTUnwrap(queue.makePayload())
        let ack = receiver.importPayload(payload)
        XCTAssertTrue(ack.succeeded)
        XCTAssertEqual(ack.mergedPointCount, 11)
        XCTAssertEqual(ack.importedPointCount, 10)
        XCTAssertEqual(ack.mergedVisitCount, 1)
        XCTAssertEqual(ack.importedVisitCount, 0)

        let points = try context.fetch(FetchDescriptor<LocationPoint>(sortBy: [SortDescriptor(\.timestamp)]))
        XCTAssertEqual(points.count, 21, "one point per minute over 20 minutes, no doubles")
        XCTAssertEqual(Set(points.map(\.timestamp)).count, points.count)
        let visits = try context.fetch(FetchDescriptor<Visit>())
        XCTAssertEqual(visits.count, 1)
        XCTAssertEqual(visits.first?.id, phoneVisit.id)
        XCTAssertEqual(visits.first?.arrivedAt, t0.addingTimeInterval(60), "the phone recorded t0 itself, so its arrival stands")
        XCTAssertEqual(visits.first?.departedAt, t0.addingTimeInterval(1200), "the Watch filled the departure the dead phone missed")

        // Re-delivery after a lost ack is still idempotent; no duplicate visit appears.
        let retry = receiver.importPayload(payload)
        XCTAssertTrue(retry.succeeded)
        XCTAssertEqual(retry.importedPointCount, 0)
        XCTAssertEqual(retry.importedVisitCount, 0)
        XCTAssertEqual(retry.mergedVisitCount, 0)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Visit>()), 1)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<LocationPoint>()), 21)
    }

    func testWatchOnlyStaysImportAsWatchVisitsAndNeverMergeIntoEachOther() throws {
        let container = try container()
        let receiver = WatchLocationSyncReceiver()
        receiver.handoff = isolatedStore()
        receiver.configure(modelContainer: container, activatesConnectivity: false)
        var queue = WatchLocationSyncState(deviceID: UUID())
        // Two back-to-back stays 120 m apart: within the duplicate distance, but distinct.
        queue.recordPoint(point(0, latitude: 35))
        queue.recordPoint(point(600, latitude: 35.00108))
        queue.endVisit(at: t0.addingTimeInterval(1200))
        let ack = receiver.importPayload(try XCTUnwrap(queue.makePayload()))
        XCTAssertEqual(ack.importedVisitCount, 2)
        XCTAssertEqual(ack.mergedVisitCount, 0)
        let visits = try container.mainContext.fetch(FetchDescriptor<Visit>())
        XCTAssertEqual(visits.count, 2)
        XCTAssertTrue(visits.allSatisfy { $0.recordedOnWatch == true })
    }

    func testDeletedMergeTargetIsNotRecreatedByLaterWatchUpdate() throws {
        let container = try container()
        let context = container.mainContext
        let store = isolatedStore()
        let receiver = WatchLocationSyncReceiver()
        receiver.handoff = store
        receiver.configure(modelContainer: container, activatesConnectivity: false)
        let phoneVisit = Visit(latitude: 35, longitude: 139, arrivedAt: t0, departedAt: t0.addingTimeInterval(3600))
        context.insert(phoneVisit)
        try context.save()
        var queue = WatchLocationSyncState(deviceID: UUID())
        queue.recordPoint(point(60))
        XCTAssertEqual(receiver.importPayload(try XCTUnwrap(queue.makePayload())).mergedVisitCount, 1)
        XCTAssertEqual(store.phoneVisitID(forWatchVisit: queue.visits[0].id), phoneVisit.id)

        context.delete(phoneVisit)
        try context.save()
        queue.endVisit(at: t0.addingTimeInterval(120))
        XCTAssertTrue(receiver.importPayload(try XCTUnwrap(queue.makePayload())).succeeded)
        XCTAssertEqual(try context.fetchCount(FetchDescriptor<Visit>()), 0)
    }

    // MARK: - iPhone store

    func testLeaseAttestsCoverageOnlyWhileRecordingAndReportsWatchRole() {
        let store = isolatedStore()
        let report = WatchHandoffReport(deviceID: UUID(), role: .standingBy, reportedAt: t0)
        let idle = store.lease(answering: report, isRecording: false, sessionID: nil, now: t0)
        XCTAssertFalse(idle.isRecording)
        XCTAssertFalse(store.coverage.covers(t0))
        let recording = store.lease(answering: report, isRecording: true, sessionID: sessionID, now: t0.addingTimeInterval(10))
        XCTAssertTrue(recording.isRecording)
        XCTAssertEqual(recording.coveredUntil, t0.addingTimeInterval(10))
        XCTAssertTrue(store.coverage.covers(t0.addingTimeInterval(10)))

        XCTAssertEqual(store.watchRole(now: t0.addingTimeInterval(20)), .standingBy)
        XCTAssertEqual(store.recordingDevicesDescription(phoneIsRecording: true, now: t0.addingTimeInterval(20)),
                       String(localized: "This iPhone · Apple Watch on standby"))
        // A Watch that stopped reporting isn't shown as standing by.
        XCTAssertNil(store.watchRole(now: t0.addingTimeInterval(store.policy.leaseLifetime + 20)))
        XCTAssertEqual(store.recordingDevicesDescription(phoneIsRecording: true,
                                                         now: t0.addingTimeInterval(store.policy.leaseLifetime + 20)),
                       String(localized: "This iPhone"))
        store.receive(WatchHandoffReport(deviceID: report.deviceID, role: .recording, reportedAt: t0), now: t0)
        XCTAssertEqual(store.recordingDevicesDescription(phoneIsRecording: false, now: t0),
                       String(localized: "Apple Watch"))
    }

    func testStorePersistsCoverageAndAliasesAcrossLaunches() {
        let defaults = UserDefaults(suiteName: "recording-handoff-test.\(UUID())")!
        let first = RecordingHandoffStore(defaults: defaults)
        first.recordPhoneCoverage(at: t0)
        first.recordPhoneCoverage(at: t0.addingTimeInterval(60))
        let watchID = UUID(), phoneID = UUID()
        first.setPhoneVisitID(phoneID, forWatchVisit: watchID)
        let second = RecordingHandoffStore(defaults: defaults)
        XCTAssertTrue(second.coverage.covers(t0.addingTimeInterval(30)))
        XCTAssertEqual(second.phoneVisitID(forWatchVisit: watchID), phoneID)
        // A relaunch closes coverage so the next fix can't bridge a dead span.
        second.closePhoneCoverage()
        second.recordPhoneCoverage(at: t0.addingTimeInterval(120))
        XCTAssertFalse(second.coverage.covers(t0.addingTimeInterval(90)))
    }

    func testAckDecodesFromPhonesWithoutMergeCounts() throws {
        let legacy = #"{"deviceID":"\#(UUID().uuidString)","sequence":4,"importedAt":0,"succeeded":true}"#
        let ack = try JSONDecoder().decode(WatchLocationSyncAck.self, from: Data(legacy.utf8))
        XCTAssertEqual(ack.mergedPointCount, 0)
        XCTAssertEqual(ack.mergedVisitCount, 0)
    }
}
