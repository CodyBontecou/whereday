import XCTest
final class PickerTests: XCTestCase {
    @MainActor private func edit(index: Int, day: String, expected: String?, arguments: [String] = [], nextMonth: Bool = false, returnDay: String? = nil) {
        let app = XCUIApplication(); app.launchArguments = arguments; app.launch()
        XCTAssertTrue(app.navigationBars["Filter by Date"].waitForExistence(timeout: 10))
        if index == 1 { app.collectionViews.firstMatch.swipeUp() }
        let picker = app.datePickers.element(boundBy: index)
        XCTAssertTrue(picker.exists); picker.tap()
        if nextMonth { app.buttons["DatePicker.NextMonth"].tap() }
        XCTAssertTrue(app.buttons[day].waitForExistence(timeout: 5)); app.buttons[day].tap()
        if let returnDay { app.buttons[returnDay].tap() }
        app.windows.firstMatch.coordinate(withNormalizedOffset: CGVector(dx: 0.5, dy: 0.1)).tap()
        XCTAssertTrue(app.buttons[day].waitForNonExistence(timeout: 5))
        XCTAssertTrue(app.buttons["Apply"].isHittable); app.buttons["Apply"].tap()
        XCTAssertTrue(app.navigationBars["Filter by Date"].waitForNonExistence(timeout: 5))
        print("EDITED_BOUND_RESULT=" + app.staticTexts["result"].label)
        if let expected { XCTAssertEqual(app.staticTexts["result"].label, expected) }
        let attachment = XCTAttachment(screenshot: app.screenshot()); attachment.name = "Applied bounds"; attachment.lifetime = .keepAlways; add(attachment)
    }
    @MainActor func testChangingStartRetainsHoursMinutesAndUnchangedEnd() {
        edit(index:0,day:"Sunday, October 11",expected:"1791742320.000|1791837296.875")
    }
    @MainActor func testChangingEndRetainsHoursMinutesAndUnchangedStart() {
        edit(index:1,day:"Tuesday, October 13",expected:"1791655954.125|1791923640.000")
    }
    @MainActor func testSelectingTheSameStartDayPreservesTheOriginalInstant() {
        edit(index:0,day:"Saturday, October 10",expected:"1791655954.125|1791837296.875")
    }
    @MainActor func testSpringGapObservation() {
        edit(index:0,day:"Sunday, March 8",expected:"1772955000.000|1773170456.875",arguments:["-zone","America/New_York","-first","2026-03-01T07:30:34.125Z","-last","2026-03-10T19:20:56.875Z"])
    }
    @MainActor func testRepeatedHourObservation() {
        edit(index:0,day:"Sunday, November 1",expected:"1793511000.000|1793733656.875",arguments:["-zone","America/New_York","-first","2026-10-25T05:30:34.125Z","-last","2026-11-03T19:20:56.875Z"],nextMonth:true)
    }

    @MainActor func testRepeatedHourFromStandardTimeObservation() {
        edit(index:0,day:"Sunday, November 1",expected:"1793511000.000|1793733656.875",arguments:["-zone","America/New_York","-first","2026-11-02T06:30:34.125Z","-last","2026-11-03T19:20:56.875Z"])
    }
    @MainActor func testHalfHourSpringGapObservation() {
        edit(index:0,day:"Sunday, October 4",expected:"1791042300.000|1791202856.875",arguments:["-zone","Australia/Lord_Howe","-first","2026-09-26T15:45:34.125Z","-last","2026-10-05T12:20:56.875Z"],nextMonth:true)
    }

    @MainActor func testChangingBackToOriginalDateKeepsTheEditedMinutePrecision() {
        edit(index:0,day:"Sunday, October 11",expected:"1791655920.000|1791837296.875",returnDay:"Saturday, October 10")
    }

    @MainActor func testGapAdjustmentSurvivesTheNextDateEdit() {
        edit(index:0,day:"Sunday, March 8",expected:"1773041400.000|1773170456.875",arguments:["-zone","America/New_York","-first","2026-03-01T07:30:34.125Z","-last","2026-03-10T19:20:56.875Z"],returnDay:"Monday, March 9")
    }

}
