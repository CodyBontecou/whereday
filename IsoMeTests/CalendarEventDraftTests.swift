import EventKit
import XCTest
@testable import IsoMe

final class CalendarEventDraftTests: XCTestCase {
    private let start = Date(timeIntervalSince1970: 1_790_000_000)

    func testVisitPrefillsRenamedTitleFullDatesAndAddressWithoutSaving() {
        let end = start.addingTimeInterval(26 * 3600)
        let visit = Visit(latitude: 38.7223, longitude: -9.1393, arrivedAt: start,
                          departedAt: end, customName: "  Lunch with Johnny  ",
                          locationName: "Cafe", address: "123 Main Street")
        let store = EKEventStore()
        let event = CalendarEventDraft(visit: visit).makeEvent(in: store)

        XCTAssertEqual(event.title, "Lunch with Johnny")
        XCTAssertEqual(event.startDate, start)
        XCTAssertEqual(event.endDate, end)
        XCTAssertEqual(event.location, "123 Main Street")
        XCTAssertEqual(event.structuredLocation?.geoLocation?.coordinate.latitude, visit.latitude)
        XCTAssertEqual(event.structuredLocation?.geoLocation?.coordinate.longitude, visit.longitude)
        XCTAssertNil(event.eventIdentifier)
        XCTAssertTrue(event.isNew)
        XCTAssertEqual(visit.customName, "  Lunch with Johnny  ")
        XCTAssertEqual(visit.departedAt, end)
    }

    func testVisitLocationFallsBackToPlaceNameThenCoordinates() {
        let visit = Visit(latitude: 38.7223, longitude: -9.1393, arrivedAt: start,
                          locationName: "Cafe", address: "  ")
        XCTAssertEqual(CalendarEventDraft(visit: visit).location, "Cafe")
        visit.locationName = nil
        XCTAssertEqual(CalendarEventDraft(visit: visit).location, "38.722300, -9.139300")
    }

    func testOngoingVisitSnapshotsNowAndDoesNotSetDeparture() {
        let visit = Visit(latitude: 0, longitude: 0, arrivedAt: start)
        let now = start.addingTimeInterval(600)
        XCTAssertEqual(CalendarEventDraft(visit: visit, now: now).endDate, now)
        XCTAssertNil(visit.departedAt)
        XCTAssertEqual(CalendarEventDraft(visit: visit, now: start.addingTimeInterval(-1)).endDate, start)
    }

    func testCompletedOutingUsesItsNameTimesAndStartLocation() {
        let end = start.addingTimeInterval(3600)
        let stored = RecordingSession(startedAt: start, endedAt: end, customName: "Morning walk")
        let point = LocationPoint(latitude: 38.7223, longitude: -9.1393, timestamp: start,
                                  horizontalAccuracy: 5)
        let session = summary(stored: stored, end: end, points: [point])
        let event = CalendarEventDraft(session: session).makeEvent(in: EKEventStore())
        XCTAssertEqual(event.title, "Morning walk")
        XCTAssertEqual(event.startDate, start)
        XCTAssertEqual(event.endDate, end)
        XCTAssertEqual(event.location, "38.722300, -9.139300")
        XCTAssertEqual(event.structuredLocation?.geoLocation?.coordinate.latitude, point.latitude)
        XCTAssertNil(event.eventIdentifier)
    }

    func testInferredOutingWithoutPointsKeepsDefaultNameAndHasNoInventedLocation() {
        let session = summary(end: start.addingTimeInterval(1200), inferred: true)
        let event = CalendarEventDraft(session: session).makeEvent(in: EKEventStore())
        XCTAssertEqual(event.title, session.title)
        XCTAssertEqual(event.endDate, session.effectiveEndDate)
        XCTAssertNil(event.location)
        XCTAssertNil(event.structuredLocation)
    }

    func testActiveOutingUsesCurrentTimeInsteadOfStaleLastPoint() {
        let point = LocationPoint(latitude: 0, longitude: 0, timestamp: start, horizontalAccuracy: 5)
        let session = summary(points: [point], active: true)
        let now = start.addingTimeInterval(600)
        XCTAssertEqual(CalendarEventDraft(session: session, now: now).endDate, now)
        XCTAssertNil(session.endedAt)
    }

    private func summary(stored: RecordingSession? = nil, end: Date? = nil,
                         points: [LocationPoint] = [], inferred: Bool = false,
                         active: Bool = false) -> RecordingSessionSummary {
        RecordingSessionSummary(id: "test", storedSession: stored, sequenceNumber: 1,
                                startedAt: start, endedAt: end, points: points,
                                isInferred: inferred, isActive: active, now: start)
    }
}
