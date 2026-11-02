package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.runtime.*
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.SemanticsActions
import android.os.LocaleList
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.TimeZone

class VisitTimestampControlTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var previousZone:TimeZone
    private val zone=ZoneId.of("America/New_York")
    @Before fun guard() {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")
        previousZone=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone(zone))
        if(InstrumentationRegistry.getArguments().getString("require24Hour")=="true")
            check(android.text.format.DateFormat.is24HourFormat(compose.activity)) {"The QA phone is not in required 24-hour mode"}
    }
    @After fun restore() {TimeZone.setDefault(previousZone)}
    private fun date(value:Instant)=value.atZone(zone).format(DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(Locale.US))
    @Test fun historicalDateCanBeOpenedAndAppliedWithoutChangingItsTimestamp() {
        val original=Instant.parse("1850-12-31T18:12:34.125Z")
        var result:Instant?=null
        compose.setContent {MaterialTheme {VisitDateTimeControl("Arrived",original) {result=it}}}
        compose.onNodeWithText(date(original)).performClick()
        compose.onNodeWithText("December 1850").assertIsDisplayed();capture("historical-date")
        compose.onNodeWithText("Apply date").assertIsEnabled().performClick()
        assertEquals(original,result)
    }
    @Test fun calendarWeekdayLabelsDoNotOverlapTheSelectableFirstWeek() {
        val original=Instant.parse("1850-12-31T18:12:34.125Z");val result=show(original)
        compose.onNodeWithTag("visit-timestamp-date").performClick()
        val weekday=compose.onNodeWithContentDescription("Sunday").getUnclippedBoundsInRoot()
        val first=compose.onNodeWithText("Sunday, December 1, 1850").getUnclippedBoundsInRoot()
        assertTrue("First calendar day overlaps weekday labels: day=$first, weekday=$weekday",first.top.value>=weekday.bottom.value-0.5f)
        compose.onNodeWithText("Sunday, December 1, 1850").assertIsDisplayed().performClick()
        compose.onNodeWithText("Dec 1, 1850").assertIsDisplayed()
        compose.onNodeWithTag("visit-timestamp-date-scroll").performTouchInput {swipeUp()}
        compose.onNodeWithText("Tuesday, December 31, 1850").assertIsDisplayed().performClick()
        compose.onNodeWithText("Apply date").assertIsEnabled().performClick();assertEquals(original,result())
    }
    @Test fun unchangedDateRetainsTheSecondOccurrenceOfAFallOverlap() {
        val original=Instant.parse("2026-11-01T06:30:45.125Z")
        var result:Instant?=null
        compose.setContent {MaterialTheme {VisitDateTimeControl("Arrived",original) {result=it}}}
        compose.onNodeWithText(date(original)).performClick()
        compose.onNodeWithText("November 2026").assertIsDisplayed()
        compose.onNodeWithText("Second occurrence · UTC-05:00").performScrollTo().assertIsSelected();capture("date-overlap")
        compose.onNodeWithText("Apply date").assertIsEnabled().performClick()
        assertEquals(original,result)
    }
    @Test fun unchangedTimeRetainsTheSecondOccurrenceAndSubMinutePrecision() {
        val original=Instant.parse("2026-11-01T06:30:45.125Z")
        var result:Instant?=null
        compose.setContent {MaterialTheme {VisitDateTimeControl("Arrived",original) {result=it}}}
        compose.onNodeWithTag("visit-timestamp-time").performClick()
        compose.onNodeWithText("Second occurrence · UTC-05:00").performScrollTo().assertIsSelected();capture("time-overlap")
        compose.onNodeWithText("Apply time").assertIsEnabled().performClick()
        assertEquals(original,result)
    }
    private fun show(value:Instant):()->Instant? {
        var result:Instant?=null
        compose.setContent {MaterialTheme {VisitDateTimeControl("Arrived",value) {result=it}}}
        return {result}
    }
    private fun enterTime() {
        compose.onNodeWithTag("visit-timestamp-time").performClick()
        if(compose.onAllNodesWithText("Enter time").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithText("Enter time").performScrollTo().performClick()
    }
    private fun hideKeyboard() {
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(androidx.test.espresso.action.ViewActions.closeSoftKeyboard())
    }
    @Test fun invalidAndMissingClockFieldsCannotApplyOrSwitchToClock() {
        val result=show(Instant.parse("2026-03-08T07:30:45.125Z"));enterTime()
        for(value in listOf("","99","x","001")) {
            compose.onNodeWithTag("visit-timestamp-hour").performTextReplacement(value);hideKeyboard()
            compose.onNodeWithText("Apply time").assertIsNotEnabled();assertNull(result())
            val action=compose.onNodeWithText("Apply time").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.runOnIdle {action()};assertNull(result())
            if(compose.onAllNodesWithText("Use clock").fetchSemanticsNodes().isNotEmpty()) {
                compose.onNodeWithText("Use clock").performScrollTo().assertIsNotEnabled()
                val clockAction=compose.onNodeWithText("Use clock").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
                compose.runOnIdle {clockAction()};compose.onNodeWithTag("visit-timestamp-hour").assertExists()
            }
        }
        compose.onNodeWithTag("visit-timestamp-hour").performTextReplacement("3")
        compose.onNodeWithTag("visit-timestamp-minute").performTextReplacement("");hideKeyboard()
        compose.onNodeWithText("Apply time").assertIsNotEnabled();assertNull(result())
        compose.onNodeWithTag("visit-timestamp-minute").performTextReplacement("15");hideKeyboard()
        compose.onNodeWithText("Apply time").assertIsEnabled().performClick()
        assertEquals(Instant.parse("2026-03-08T07:15:45.125Z"),result())
    }
    @Test fun nonexistentSpringTimeCannotApplyAndCanBeCorrected() {
        val result=show(Instant.parse("2026-03-08T07:30:45.125Z"));enterTime()
        compose.onNodeWithTag("visit-timestamp-hour").performTextReplacement("2");hideKeyboard()
        compose.onNodeWithText("Apply time").assertIsNotEnabled()
        compose.onNodeWithText("This local date and time did not occur in America/New_York. Choose another date or time.").performScrollTo().assertIsDisplayed()
        capture("gap")
        compose.onNodeWithTag("visit-timestamp-hour").performScrollTo().performTextReplacement("3");hideKeyboard()
        compose.onNodeWithText("Apply time").performClick()
        assertEquals(Instant.parse("2026-03-08T07:30:45.125Z"),result())
    }
    @Test fun nonexistentDateAndMalformedIsoCannotApply() {
        val result=show(Instant.parse("2026-03-07T07:30:45.125Z"))
        compose.onNodeWithTag("visit-timestamp-date").performClick()
        compose.onNodeWithText("Enter ISO dates").performScrollTo().assertIsDisplayed().performClick()
        for(value in listOf("2026-03-08","2026-02-30","bad","+999999999-12-31")) {
            compose.onNodeWithTag("visit-timestamp-date-input").performTextReplacement(value);hideKeyboard()
            compose.onNodeWithText("Apply date").assertIsNotEnabled();assertNull(result())
            val action=compose.onNodeWithText("Apply date").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
            compose.runOnIdle {action()};assertNull(result())
        }
        compose.onNodeWithTag("visit-timestamp-date-input").performTextReplacement("2026-03-09");hideKeyboard()
        compose.onNodeWithText("Apply date").assertIsEnabled().performClick()
        assertEquals(Instant.parse("2026-03-09T06:30:45.125Z"),result())
    }
    @Test fun eitherFallOccurrenceCanBeSelectedWithoutLosingPrecision() {
        val result=show(Instant.parse("2026-11-01T06:30:45.125Z"))
        compose.onNodeWithTag("visit-timestamp-time").performClick()
        compose.onNodeWithText("First occurrence · UTC-04:00").performScrollTo().performClick()
        compose.onNodeWithText("Apply time").performClick()
        assertEquals(Instant.parse("2026-11-01T05:30:45.125Z"),result())
    }
    @Test fun dateDialogKeepsItsSnapshotZoneAndOriginalInstantAcrossRestoration() {
        val original=Instant.parse("2026-11-01T06:30:45.125Z")
        val tester=StateRestorationTester(compose);var result:Instant?=null
        tester.setContent {MaterialTheme {VisitDateTimeControl("Arrived",original) {result=it}}}
        compose.onNodeWithTag("visit-timestamp-date").performClick()
        TimeZone.setDefault(TimeZone.getTimeZone("UTC"));tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Time zone: America/New_York").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Apply date").performClick();assertEquals(original,result)
    }
    @Test fun hourMinuteAndOffsetChoiceSurviveRestoration() {
        val original=Instant.parse("2026-11-01T06:30:45.125Z")
        val tester=StateRestorationTester(compose);var result:Instant?=null
        tester.setContent {MaterialTheme {VisitDateTimeControl("Arrived",original) {result=it}}}
        enterTime()
        compose.onNodeWithTag("visit-timestamp-minute").performTextReplacement("45");hideKeyboard()
        compose.onNodeWithText("First occurrence · UTC-04:00").performScrollTo().performClick()
        tester.emulateSavedInstanceStateRestore()
        capture("restored-time")
        compose.onNodeWithText("Apply time").performClick();assertEquals(Instant.parse("2026-11-01T05:45:45.125Z"),result)
    }
    @Test fun externalValueChangesDismissTheDraftWithoutOverwritingTheReplacement() {
        val original=Instant.parse("2026-11-01T06:30:45.125Z")
        var value by mutableStateOf(original);var writes=0
        compose.setContent {MaterialTheme {VisitDateTimeControl("Arrived",value) {value=it;writes++}}}
        compose.onNodeWithTag("visit-timestamp-time").performClick()
        compose.runOnIdle {value=original.plusSeconds(7200)}
        compose.onNodeWithText("Apply time").assertDoesNotExist()
        assertEquals(original.plusSeconds(7200),value);assertEquals(0,writes)
    }
    @Test fun yearZeroUsesIsoAndAppliesTheExactOriginalInstant() {
        val original=LocalDate.of(0,12,30).atTime(12,34,56,125_000_000).atZone(zone).toInstant()
        val result=show(original)
        compose.onNodeWithTag("visit-timestamp-date").performClick()
        compose.onNodeWithTag("visit-timestamp-date-input").assertTextContains("0000-12-30")
        capture("year-zero")
        compose.onNodeWithText("Apply date").performClick();assertEquals(original,result())
    }
    @Test fun displayedTimeFollowsThePhonesClockPreference() {
        val value=Instant.parse("2026-10-31T18:12:34.125Z")
        show(value)
        val expected=if(android.text.format.DateFormat.is24HourFormat(compose.activity)) "14:12" else "2:12 PM"
        compose.onNodeWithTag("visit-timestamp-time").assertTextEquals(expected).assertContentDescriptionEquals("Arrived time: $expected")
    }
    @Test fun midnightInputUsesThePhonesClockConvention() {
        val result=show(Instant.parse("2026-10-31T16:34:45.125Z"));enterTime()
        val clock24=android.text.format.DateFormat.is24HourFormat(compose.activity)
        compose.onNodeWithTag("visit-timestamp-hour").performTextReplacement(if(clock24) "00" else "12");hideKeyboard()
        if(!clock24) compose.onNodeWithText("AM").performScrollTo().performClick()
        compose.onNodeWithText("Apply time").performClick();assertEquals(Instant.parse("2026-10-31T04:34:45.125Z"),result())
    }
    @Test fun cancelDiscardsClockEditsBeforeReopening() {
        val original=Instant.parse("2026-11-01T06:30:45.125Z")
        val result=show(original);enterTime()
        compose.onNodeWithTag("visit-timestamp-hour").performTextReplacement("4");hideKeyboard()
        compose.onNodeWithText("Cancel").performClick();assertNull(result())
        enterTime();compose.onNodeWithTag("visit-timestamp-hour").assertTextContains("01")
        compose.onNodeWithText("Apply time").performClick();assertEquals(original,result())
    }
    @Test fun disablingTheOwnerDismissesWithoutApplying() {
        var enabled by mutableStateOf(true);var writes=0
        compose.setContent {MaterialTheme {VisitDateTimeControl("Arrived",Instant.parse("2026-11-01T06:30:45.125Z"),enabled=enabled) {writes++}}}
        compose.onNodeWithTag("visit-timestamp-date").performClick()
        compose.runOnIdle {enabled=false}
        compose.onNodeWithText("Apply date").assertDoesNotExist();assertEquals(0,writes)
    }
    @Test @SdkSuppress(minSdkVersion=33)
    fun actualSpanishLocaleLabelsTheRepeatedTimeAndKeepsTheInstant() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val previous=target.getSystemService(android.app.LocaleManager::class.java).applicationLocales
        try {
            applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
            val original=Instant.parse("2026-11-01T06:30:45.125Z");var result:Instant?=null
            compose.setContent {MaterialTheme {VisitDateTimeControl(stringResource(R.string.visit_arrived),original) {result=it}}}
            compose.onNodeWithTag("visit-timestamp-time").performClick()
            compose.onNodeWithText("Llegada · hora").assertIsDisplayed()
            compose.onNodeWithText("Segunda repetición · UTC-05:00").performScrollTo().assertIsSelected();capture("spanish-time")
            compose.onNodeWithText("Aplicar hora").assertIsDisplayed().performClick();assertEquals(original,result)
        } finally {applyQaAppLocales(compose,previous)}
    }
    private fun capture(name:String) {
        val arguments=InstrumentationRegistry.getArguments()
        if(arguments.getString("captureVisitTimestamps")!="true") return
        compose.waitForIdle()
        val label=arguments.getString("captureLabel") ?: "normal";check(label in listOf("normal","large"))
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-visit-timestamp-$name-$label-20261031.png")
            put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
        }
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {stream->
            var previous:android.graphics.Bitmap?=null;var stable=0
            val deadline=android.os.SystemClock.elapsedRealtime()+5000
            try {
                while(android.os.SystemClock.elapsedRealtime()<deadline) {
                    val current=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
                    stable=if(previous?.sameAs(current)==true) stable+1 else 0
                    previous?.recycle();previous=current
                    if(stable>=2) {check(current.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream));return}
                    android.os.SystemClock.sleep(100)
                }
                error("Visit timestamp dialog did not settle before capture")
            } finally {previous?.recycle()}
        }
    }
}
