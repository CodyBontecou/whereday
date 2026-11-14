package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import android.os.LocaleList
import com.bontecou.whereday.domain.HistoryRange
import org.junit.*
import org.junit.Assert.*
import java.time.*

class MapDateRangeControlTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    private val zone=ZoneOffset.UTC
    private val first=Instant.parse("2026-10-10T18:12:34.125Z")
    private val last=Instant.parse("2026-10-12T20:34:56.875Z")
    private fun keyboard() {
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(androidx.test.espresso.action.ViewActions.closeSoftKeyboard())
    }
    private fun text(tag:String,value:String) {compose.onNodeWithTag(tag).performTextReplacement(value);keyboard()}
    @Test fun anEditedDateRoundTripRetainsMinutePrecisionAcrossRestoration() {
        val draft=MapDateDraft(HistoryRange(first,last.plusMillis(1)),zone)
        var result:MapSelection?=null
        val tester=StateRestorationTester(compose)
        tester.setContent {MaterialTheme {CustomMapDateRange(draft,{}) {result=it}}}
        compose.onNodeWithText("Enter ISO dates").performClick()
        text("date-range-from","2026-10-11");text("date-range-from","2026-10-10")
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Apply").assertIsEnabled().performClick()
        compose.runOnIdle {
            val range=result!!.range(Instant.EPOCH,zone)
            assertEquals(Instant.parse("2026-10-10T18:12:00Z"),range.from);assertEquals(last.plusMillis(1),range.until)
        }
    }
    @Test fun aGapAdjustedClockSurvivesRestorationAndTheNextDayEdit() {
        val ny=ZoneId.of("America/New_York")
        val end=Instant.parse("2026-03-10T19:20:56.875Z")
        val draft=MapDateDraft(HistoryRange(Instant.parse("2026-03-01T07:30:34.125Z"),end.plusMillis(1)),ny)
        var result:MapSelection?=null
        val tester=StateRestorationTester(compose)
        tester.setContent {MaterialTheme {CustomMapDateRange(draft,{}) {result=it}}}
        compose.onNodeWithText("Enter ISO dates").performClick();text("date-range-from","2026-03-08")
        tester.emulateSavedInstanceStateRestore();text("date-range-from","2026-03-09")
        compose.onNodeWithText("Apply").assertIsEnabled().performClick()
        compose.runOnIdle {
            val range=result!!.range(Instant.EPOCH,ny)
            assertEquals(Instant.parse("2026-03-09T07:30:00Z"),range.from);assertEquals(end.plusMillis(1),range.until)
        }
    }
    @Test fun retainedTimeOrderCannotBeForcedThroughApplyAndCanBeCorrected() {
        val draft=MapDateDraft(HistoryRange(Instant.parse("2026-10-10T20:00:00Z"),Instant.parse("2026-10-11T10:00:00.001Z")),zone)
        var result:MapSelection?=null
        compose.setContent {MaterialTheme {CustomMapDateRange(draft,{}) {result=it}}}
        compose.onNodeWithText("Enter ISO dates").performClick();text("date-range-from","2026-10-11")
        compose.onNodeWithText("The retained start time is after the end time. Choose different dates.").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Apply").assertIsNotEnabled().performSemanticsAction(SemanticsActions.OnClick)
        compose.runOnIdle {assertNull(result)}
        text("date-range-through","2026-10-12")
        compose.onNodeWithText("Apply").assertIsEnabled().performClick()
        compose.runOnIdle {assertEquals(Instant.parse("2026-10-12T10:00:00.001Z"),result!!.range(Instant.EPOCH,zone).until)}
    }
    @Test fun switchingBackToCalendarRetainsTheEditedInstantBounds() {
        val draft=MapDateDraft(HistoryRange(first,last.plusMillis(1)),zone)
        var result:MapSelection?=null
        compose.setContent {MaterialTheme {CustomMapDateRange(draft,{}) {result=it}}}
        compose.onNodeWithText("Enter ISO dates").performClick();text("date-range-from","2026-10-11")
        compose.onNodeWithText("Use calendar").performScrollTo().performClick()
        compose.onNodeWithText("Apply").assertIsEnabled().performClick()
        compose.runOnIdle {
            val range=result!!.range(Instant.EPOCH,zone)
            assertEquals(Instant.parse("2026-10-11T18:12:00Z"),range.from);assertEquals(last.plusMillis(1),range.until)
        }
    }
    @Test @SdkSuppress(minSdkVersion=33)
    fun spanishRetainedTimeValidationUsesTheActualAppLocale() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val previous=target.getSystemService(android.app.LocaleManager::class.java).applicationLocales
        try {
            applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
            val draft=MapDateDraft(HistoryRange(Instant.parse("2026-10-10T20:00:00Z"),Instant.parse("2026-10-11T10:00:00.001Z")),zone)
            var result:MapSelection?=null
            compose.setContent {MaterialTheme {CustomMapDateRange(draft,{}) {result=it}}}
            compose.onNodeWithText("Introducir fechas ISO").performClick();text("date-range-from","2026-10-11")
            compose.onNodeWithText("La hora inicial conservada es posterior a la hora final. Elige otras fechas.").performScrollTo().assertIsDisplayed()
            compose.onNodeWithText("Aplicar").assertIsNotEnabled().performSemanticsAction(SemanticsActions.OnClick)
            compose.runOnIdle {assertNull(result)}
            text("date-range-through","2026-10-12")
            compose.onNodeWithText("Aplicar").assertIsEnabled().performClick()
            compose.runOnIdle {assertNotNull(result)}
        } finally {applyQaAppLocales(compose,previous)}
    }

}
