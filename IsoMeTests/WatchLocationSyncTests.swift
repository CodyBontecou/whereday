import CoreLocation
import XCTest
#if canImport(IsoMe)
@testable import IsoMe
#else
@testable import WatchSyncTesting
#endif

final class WatchLocationSyncTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_800_000_000)
    private let sessionID = UUID()

    func testRecordedPlacesSurviveRelaunchAndEncodeForPhone() throws {
        var state = WatchLocationSyncState(deviceID: UUID())
        XCTAssertTrue(state.recordPoint(point(seconds: 0)))
        XCTAssertFalse(state.recordPoint(point(seconds: 10, latitude: 35.00001)))
        XCTAssertTrue(state.recordPoint(point(seconds: 60, latitude: 35.002)))
        let restored = try JSONDecoder().decode(
            WatchLocationSyncState.self, from: JSONEncoder().encode(state)
        )
        let payload = WatchLocationSyncPayload(
            deviceID: restored.deviceID, sequence: 1, isTrackingEnabled: true,
            activeSessionID: sessionID, sessions: [], points: restored.points,
            visits: restored.visits
        )
        let received = try JSONDecoder().decode(
            WatchLocationSyncPayload.self, from: JSONEncoder().encode(payload)
        )
        XCTAssertEqual(received.visits.count, 2)
        XCTAssertEqual(received.visits[0].arrivedAt, start)
        XCTAssertEqual(received.visits[0].departedAt, start.addingTimeInterval(60))
        XCTAssertNil(received.visits[1].departedAt)
        XCTAssertEqual(received.points.count, 3)
    }

    func testArrivalAckDoesNotDiscardDepartureRecordedInFlight() throws {
        var state = WatchLocationSyncState(deviceID: UUID())
        state.recordPoint(point(seconds: 0))
        let arrival = transfer(state, sequence: 1)
        state.pendingTransfers.append(arrival)
        state.recordPoint(point(seconds: 60, latitude: 35.002))
        let closedVisit = state.visits[0]
        state.acknowledge(arrival)
        XCTAssertEqual(state.visits.count, 2)
        XCTAssertEqual(state.visits[0], closedVisit)
        XCTAssertEqual(state.points.count, 1)

        // Retry after a process restart and acknowledge the departure snapshot.
        state = try JSONDecoder().decode(WatchLocationSyncState.self, from: JSONEncoder().encode(state))
        let departure = transfer(state, sequence: 2)
        state.pendingTransfers.append(departure)
        state.acknowledge(departure)
        XCTAssertEqual(state.visits.count, 1)
        XCTAssertNil(state.visits[0].departedAt)
        XCTAssertTrue(state.points.isEmpty)
    }

    func testStopClosesVisitAndOldSessionAckPreservesEndUpdate() {
        var state = WatchLocationSyncState(deviceID: UUID())
        state.sessions = [.init(id: sessionID, startedAt: start, endedAt: nil)]
        state.recordPoint(point(seconds: 0))
        let arrival = transfer(state, sequence: 1)
        state.endVisit(at: start.addingTimeInterval(30))
        state.sessions[0].endedAt = start.addingTimeInterval(30)
        state.acknowledge(arrival)
        XCTAssertEqual(state.visits[0].departedAt, start.addingTimeInterval(30))
        XCTAssertEqual(state.sessions.count, 1)
        state.acknowledge(transfer(state, sequence: 2))
        XCTAssertTrue(state.visits.isEmpty)
        XCTAssertTrue(state.sessions.isEmpty)
    }

    func testOutOfOrderAcknowledgementsKeepOtherTransferSnapshots() {
        var state = WatchLocationSyncState(deviceID: UUID())
        state.recordPoint(point(seconds: 0))
        let first = transfer(state, sequence: 1)
        state.recordPoint(point(seconds: 60, latitude: 35.002))
        let second = transfer(state, sequence: 2)
        state.pendingTransfers = [first, second]
        state.acknowledge(second)
        XCTAssertEqual(state.pendingTransfers.map(\.sequence), [1])
        state.acknowledge(first)
        XCTAssertTrue(state.pendingTransfers.isEmpty)
        XCTAssertEqual(state.visits.count, 1)
        XCTAssertEqual(state.lastAckedSequence, 2)
    }

    func testLegacyQueueDecodesAndResendsWithoutDeletingUnacknowledgedVisits() throws {
        let json = """
        {"deviceID":"00000000-0000-0000-0000-000000000001",
         "pendingTransfers":[{"sequence":1,"sessionIDs":[],"pointIDs":[],"visitIDs":[],"createdAt":0}]}
        """
        var state = try JSONDecoder().decode(WatchLocationSyncState.self, from: Data(json.utf8))
        state.recordPoint(point(seconds: 0))
        state.endVisit(at: start.addingTimeInterval(10))
        state.acknowledge(state.pendingTransfers[0])
        XCTAssertEqual(state.visits.count, 1)
        XCTAssertEqual(state.points.count, 1)
    }

    func testOlderLocationCannotCloseVisitBeforeArrival() {
        var state = WatchLocationSyncState(deviceID: UUID())
        state.recordPoint(point(seconds: 60))
        XCTAssertFalse(state.recordPoint(point(seconds: 0, latitude: 35.002)))
        XCTAssertEqual(state.visits.count, 1)
        XCTAssertNil(state.visits[0].departedAt)
    }

    func testUpgradeRecoversVisitsFromLegacyQueuedPointsOnce() throws {
        var state = WatchLocationSyncState(deviceID: UUID())
        state.points = [point(seconds: 0), point(seconds: 60, latitude: 35.002)]
        var json = try XCTUnwrap(JSONSerialization.jsonObject(with: JSONEncoder().encode(state)) as? [String: Any])
        json.removeValue(forKey: "visitRecordingVersion")
        let recovered = try JSONDecoder().decode(
            WatchLocationSyncState.self, from: JSONSerialization.data(withJSONObject: json)
        )
        XCTAssertEqual(recovered.visits.count, 2)
        XCTAssertEqual(recovered.points.count, 2)
        XCTAssertEqual(recovered.visits[0].departedAt, start.addingTimeInterval(60))
        XCTAssertEqual(recovered.visits[1].departedAt, start.addingTimeInterval(60))
        let restored = try JSONDecoder().decode(WatchLocationSyncState.self, from: JSONEncoder().encode(recovered))
        XCTAssertEqual(restored.visits, recovered.visits)
    }

    func testModernQueueDoesNotRecreateVisitsFromRemainingBatchedPoints() throws {
        var state = WatchLocationSyncState(deviceID: UUID())
        state.recordPoint(point(seconds: 0))
        state.endVisit(at: start.addingTimeInterval(60))
        var firstBatch = transfer(state, sequence: 1)
        // Simulate a payload whose visit is included but whose point is in a later batch.
        firstBatch.pointIDs = []
        state.acknowledge(firstBatch)
        XCTAssertTrue(state.visits.isEmpty)
        XCTAssertEqual(state.points.count, 1)
        let restored = try JSONDecoder().decode(WatchLocationSyncState.self, from: JSONEncoder().encode(state))
        XCTAssertTrue(restored.visits.isEmpty)
    }

    private func point(seconds: TimeInterval, latitude: Double = 35) -> WatchLocationSyncPoint {
        WatchLocationSyncPoint(
            id: UUID(), sessionID: sessionID, latitude: latitude, longitude: 139,
            timestamp: start.addingTimeInterval(seconds), altitude: nil, speed: nil,
            horizontalAccuracy: 5
        )
    }

    private func transfer(_ state: WatchLocationSyncState, sequence: Int) -> WatchLocationPendingTransfer {
        WatchLocationPendingTransfer(
            sequence: sequence, sessionIDs: Set(state.sessions.map(\.id)),
            pointIDs: Set(state.points.map(\.id)), visitIDs: Set(state.visits.map(\.id)),
            createdAt: start, visits: state.visits,
            endedSessionIDs: Set(state.sessions.filter { $0.endedAt != nil }.map(\.id))
        )
    }
}

#if canImport(IsoMe)
import SwiftData

@MainActor
final class WatchLocationSyncImportTests: XCTestCase {
    func testWatchPlacesImportOnceAndDepartureRefreshesTimeline() throws {
        let schema = Schema([Visit.self, LocationPoint.self, RecordingSession.self,
                             PhotoMoment.self, SavedPlace.self, CountryStay.self])
        let container = try ModelContainer(for: schema, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let receiver = WatchLocationSyncReceiver()
        receiver.configure(modelContainer: container)
        var queue = WatchLocationSyncState(deviceID: UUID())
        let start = Date(timeIntervalSince1970: 1_800_000_000)
        let sessionID = UUID()
        queue.recordPoint(.init(id: UUID(), sessionID: sessionID, latitude: 35,
                                longitude: 139, timestamp: start, altitude: nil,
                                speed: nil, horizontalAccuracy: 5))
        func payload(sequence: Int, includePoints: Bool) -> WatchLocationSyncPayload {
            WatchLocationSyncPayload(deviceID: queue.deviceID, sequence: sequence,
                                     isTrackingEnabled: true, activeSessionID: sessionID,
                                     sessions: [], points: includePoints ? queue.points : [],
                                     visits: queue.visits)
        }
        let arrival = payload(sequence: 1, includePoints: true)
        XCTAssertTrue(receiver.importPayload(arrival).succeeded)
        XCTAssertEqual(receiver.importPayload(arrival).importedVisitCount, 0)
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<Visit>()), 1)
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<LocationPoint>()), 1)

        queue.endVisit(at: start.addingTimeInterval(60))
        let refreshed = expectation(forNotification: .watchLocationDataImported, object: nil)
        let departure = payload(sequence: 2, includePoints: false)
        XCTAssertTrue(receiver.importPayload(departure).succeeded)
        wait(for: [refreshed], timeout: 1)
        let visit = try XCTUnwrap(container.mainContext.fetch(FetchDescriptor<Visit>()).first)
        XCTAssertEqual(visit.id, queue.visits[0].id)
        XCTAssertEqual(visit.departedAt, start.addingTimeInterval(60))

        // A delayed arrival payload must not reopen a completed visit.
        XCTAssertTrue(receiver.importPayload(arrival).succeeded)
        XCTAssertEqual(visit.departedAt, start.addingTimeInterval(60))
        XCTAssertEqual(try container.mainContext.fetchCount(FetchDescriptor<Visit>()), 1)
    }
}
#endif
