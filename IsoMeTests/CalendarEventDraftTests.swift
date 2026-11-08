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

    func testVisitAndOutingNotesPreserveMultilineContentWithoutSaving() {
        let notes = "  Lunch with Johnny\n\nDiscussed the next trip.\n "
        let visit = Visit(latitude: 0, longitude: 0, arrivedAt: start, notes: notes)
        let stored = RecordingSession(startedAt: start, notes: notes)
        let drafts = [CalendarEventDraft(visit: visit), CalendarEventDraft(session: summary(stored: stored))]

        // A draft is a snapshot, not a live link to the source notes.
        visit.notes = "Updated later"
        stored.notes = "Updated later"
        for draft in drafts {
            let event = draft.makeEvent(in: EKEventStore())
            XCTAssertEqual(draft.notes, notes)
            XCTAssertEqual(event.notes, notes)
            XCTAssertTrue(event.isNew)
            XCTAssertNil(event.eventIdentifier)
        }
        XCTAssertEqual(visit.notes, "Updated later")
        XCTAssertEqual(stored.notes, "Updated later")
    }

    func testReviewNotesStartWithBylineAndKeepOwnNotes() {
        let plain = Visit(latitude: 0, longitude: 0, arrivedAt: start, departedAt: start.addingTimeInterval(50 * 60))
        let plainNotes = CalendarEventDraft(visit: plain).reviewNotes
        XCTAssertTrue(plainNotes.hasPrefix("Logged by Whereday · "))
        XCTAssertFalse(plainNotes.contains("\n"))

        let noted = Visit(latitude: 0, longitude: 0, arrivedAt: start, departedAt: start.addingTimeInterval(600),
                          notes: "Lunch with Johnny")
        let draft = CalendarEventDraft(visit: noted)
        XCTAssertTrue(draft.reviewNotes.hasPrefix("Logged by Whereday · "))
        XCTAssertTrue(draft.reviewNotes.hasSuffix("\n\nLunch with Johnny"))
        // The draft itself still carries only the visit's notes.
        XCTAssertEqual(draft.notes, "Lunch with Johnny")
    }

    func testNilEmptyAndWhitespaceOnlyNotesAreOmitted() {
        let blankNotes: [String?] = [nil, "", " \t\n\r "]
        for notes in blankNotes {
            let visit = Visit(latitude: 0, longitude: 0, arrivedAt: start, notes: notes)
            let stored = RecordingSession(startedAt: start, notes: notes)
            let drafts = [CalendarEventDraft(visit: visit), CalendarEventDraft(session: summary(stored: stored))]
            for draft in drafts {
                XCTAssertNil(draft.notes)
                XCTAssertNil(draft.makeEvent(in: EKEventStore()).notes)
            }
        }
        XCTAssertNil(CalendarEventDraft(session: summary(inferred: true)).notes)
    }

    func testDetailScreenTextOverridesStoredNotesIncludingClearedText() {
        let visit = Visit(latitude: 0, longitude: 0, arrivedAt: start, notes: "Old visit notes")
        let stored = RecordingSession(startedAt: start, notes: "Old outing notes")
        let session = summary(stored: stored)

        // The detail screens pass the TextField state, even before focus loss saves it.
        for text in ["New notes\nStill editing", "", " \n\t"] {
            let expected: String? = text.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty ? nil : text
            let drafts = [CalendarEventDraft(visit: visit, notes: text),
                          CalendarEventDraft(session: session, notes: text)]
            for draft in drafts {
                XCTAssertEqual(draft.makeEvent(in: EKEventStore()).notes, expected)
            }
        }
        XCTAssertEqual(visit.notes, "Old visit notes")
        XCTAssertEqual(stored.notes, "Old outing notes")
    }

    func testVisitEditorValuesOverrideStoredTitleTimesAndNotesWithoutMutatingVisit() {
        let originalEnd = start.addingTimeInterval(3600)
        let visit = Visit(latitude: 0, longitude: 0, arrivedAt: start, departedAt: originalEnd,
                          customName: "Original", notes: "Stored notes")
        let editedStart = start.addingTimeInterval(86400)
        let editedEnd = editedStart.addingTimeInterval(26 * 3600)
        let draft = CalendarEventDraft(visit: visit, name: "  Edited title  ", arrivedAt: editedStart,
                                      departedAt: editedEnd, notes: "Draft notes")
        let event = draft.makeEvent(in: EKEventStore())
        XCTAssertEqual(event.title, "Edited title")
        XCTAssertEqual(event.startDate, editedStart)
        XCTAssertEqual(event.endDate, editedEnd)
        XCTAssertEqual(event.notes, "Draft notes")
        XCTAssertEqual(visit.customName, "Original")
        XCTAssertEqual(visit.arrivedAt, start)
        XCTAssertEqual(visit.departedAt, originalEnd)
        XCTAssertEqual(visit.notes, "Stored notes")
    }

    func testVisitEditorStillHereClearsStoredDepartureAndBlankTitleFallsBack() {
        let visit = Visit(latitude: 0, longitude: 0, arrivedAt: start,
                          departedAt: start.addingTimeInterval(600), customName: "Original")
        let now = start.addingTimeInterval(3 * 3600)
        let editedStart = start.addingTimeInterval(3600)
        let draft = CalendarEventDraft(visit: visit, name: " \n", arrivedAt: editedStart,
                                      departedAt: nil, now: now, notes: "")
        XCTAssertEqual(draft.title, "Original")
        XCTAssertEqual(draft.startDate, editedStart)
        XCTAssertEqual(draft.endDate, now)
        XCTAssertNil(draft.notes)
        XCTAssertEqual(visit.departedAt, start.addingTimeInterval(600))
    }

    private func summary(stored: RecordingSession? = nil, end: Date? = nil,
                         points: [LocationPoint] = [], inferred: Bool = false,
                         active: Bool = false) -> RecordingSessionSummary {
        RecordingSessionSummary(id: "test", storedSession: stored, sequenceNumber: 1,
                                startedAt: start, endedAt: end, points: points,
                                isInferred: inferred, isActive: active, now: start)
    }
}
