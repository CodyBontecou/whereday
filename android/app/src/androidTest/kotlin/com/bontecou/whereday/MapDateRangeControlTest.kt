package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.filters.SdkSuppress
import android.os.LocaleList
import com.bontecou.whereday.domain.HistoryRange
import org.junit.*
import org.junit.Assert.*
import java.time.*

/** Drives the range calendar's real controls through cases from ios-custom-date-edits.json. */
class MapDateRangeControlTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    private val now=Instant.parse("2026-11-04T15:30:12.345Z")
    private val window=HistoryRange(Instant.parse("2026-10-10T18:12:34.125Z"),Instant.parse("2026-10-12T20:34:56.876Z"))
    private var result:MapSelection?=null

    private class Shown(val draft:MapRangeDraft,val zone:ZoneId,val recorded:Set<LocalDate>)
    private var shown by mutableStateOf<Shown?>(null)
    private var started=false

    /** Each call opens a fresh sheet; content is set once per test as Compose requires. */
    private fun show(zone:ZoneId=ZoneOffset.UTC,selection:MapSelection=MapSelection("Custom",outingRange=window),recorded:Set<LocalDate> = emptySet(),tester:StateRestorationTester?=null) {
        val next=Shown(MapRangeDraft.open(selection,now,zone),zone,recorded)
        if(started) compose.runOnIdle {shown=next}
        else {
            shown=next;started=true
            val content:@Composable ()->Unit={MaterialTheme {shown?.let {sheet->key(sheet) {MapRangeSheet(sheet.draft,sheet.recorded,{now},sheet.zone,{}) {result=it}}}}}
            if(tester!=null) tester.setContent(content) else compose.setContent(content)
        }
        compose.onNodeWithText("Show days").assertIsDisplayed()
    }
    private fun day(date:String)=compose.onNodeWithTag("map-range-calendar-day-$date").performScrollTo().performClick()
    private fun apply(title:String):MapSelection {
        compose.onNodeWithTag("map-range-show").assertTextEquals(title).performClick()
        return compose.runOnIdle {result!!}
    }
    private fun range(from:String,until:String)=HistoryRange(Instant.parse(from),Instant.parse(until))

    @Test fun anUnchangedWindowAppliesItsWholeDays() {
        show();compose.onNodeWithTag("map-range-text").assertTextEquals("Oct 10 – Oct 12")
        assertEquals(range("2026-10-10T00:00:00Z","2026-10-13T00:00:00Z"),apply("Show 3 days").range(now,ZoneOffset.UTC))
    }
    @Test fun aSecondTapCompletesTheRangeInEitherOrderAndAThirdStartsAgain() {
        show();day("2026-10-13");day("2026-10-11")
        compose.onNodeWithTag("map-range-calendar-day-2026-10-12").assertIsSelected()
        compose.onNodeWithTag("map-range-text").assertTextEquals("Oct 11 – Oct 13")
        day("2026-10-05")
        compose.onNodeWithTag("map-range-calendar-day-2026-10-12").assertIsNotSelected()
        assertEquals(range("2026-10-05T00:00:00Z","2026-10-06T00:00:00Z"),apply("Show 1 day").range(now,ZoneOffset.UTC))
    }
    @Test fun aRangeAcrossMonthsUsesTheMonthButtonsAndSurvivesRestoration() {
        val tester=StateRestorationTester(compose)
        show(tester=tester)
        compose.onNodeWithTag("map-range-month").assertTextEquals("October 2026")
        compose.onNodeWithContentDescription("Previous month").performClick()
        compose.onNodeWithTag("map-range-month").assertTextEquals("September 2026")
        day("2026-09-28")
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("map-range-month").assertTextEquals("September 2026")
        compose.onNodeWithContentDescription("Next month").performClick();day("2026-10-03")
        assertEquals(range("2026-09-28T00:00:00Z","2026-10-04T00:00:00Z"),apply("Show 6 days").range(now,ZoneOffset.UTC))
    }
    @Test fun daysFollowTheZonesCalendarIncludingADayWithoutMidnight() {
        show(ZoneId.of("America/New_York"),MapSelection("Custom",outingRange=range("2026-03-01T07:30:34.125Z","2026-03-10T19:20:56.876Z")))
        day("2026-03-08")
        assertEquals(range("2026-03-08T05:00:00Z","2026-03-09T04:00:00Z"),apply("Show 1 day").range(now,ZoneOffset.UTC))
        val santiago=ZoneId.of("America/Santiago");result=null
        show(santiago,MapSelection("Custom",outingRange=range("2026-09-01T12:00:00Z","2026-09-10T12:00:00.001Z")))
        day("2026-09-06")
        // iOS adds one calendar day to the 01:00 start, so the day ends at the next day's 01:00.
        assertEquals(range("2026-09-06T04:00:00Z","2026-09-07T04:00:00Z"),apply("Show 1 day").range(now,santiago))
    }
    @Test fun presetChipsAndResetFollowTheClock() {
        show();compose.onNodeWithTag("map-range-preset-This month").performScrollTo().performClick()
        compose.onNodeWithTag("map-range-preset-This month").assertIsSelected()
        compose.onNodeWithTag("map-range-month").assertTextEquals("November 2026")
        assertEquals(MapSelection("This month"),apply("Show 4 days"))
        result=null;show();compose.onNodeWithTag("map-range-reset").performClick()
        compose.onNodeWithTag("map-range-preset-Today").assertIsSelected()
        assertEquals(MapSelection("Today"),apply("Show 1 day"))
        result=null;show();compose.onNodeWithTag("map-range-preset-All").performScrollTo().performClick()
        compose.onNodeWithTag("map-range-text").assertTextEquals("All time")
        assertEquals(MapSelection("All"),apply("Show 1 day"))
    }
    @Test fun tappingASpanThatMatchesAPresetSelectsItAndOtherSpansEndingTodayStopAtNow() {
        show(selection=MapSelection("Today"));day("2026-11-01");day("2026-11-04")
        compose.onNodeWithTag("map-range-preset-This month").assertIsSelected()
        assertEquals(MapSelection("This month"),apply("Show 4 days"))
        result=null;show(selection=MapSelection("Today"));day("2026-11-03");day("2026-11-04")
        compose.onNodeWithTag("map-range-preset-This month").assertIsNotSelected()
        assertEquals(range("2026-11-03T00:00:00Z","2026-11-04T15:30:12.346Z"),apply("Show 2 days").range(now,ZoneOffset.UTC))
    }
    @Test fun futureDaysAndMonthsCannotBeChosenAndRecordedDaysAreAnnounced() {
        show(selection=MapSelection("Today"),recorded=setOf(LocalDate.of(2026,11,2),LocalDate.of(2026,10,1)))
        compose.onNodeWithTag("map-range-calendar-day-2026-11-05").assertIsNotEnabled()
        compose.onNodeWithContentDescription("Next month").assertIsNotEnabled()
        compose.onNodeWithTag("map-range-calendar-day-2026-11-02").assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Has visits"))
            .assertContentDescriptionEquals("Monday, November 2, 2026")
        compose.onNodeWithTag("map-range-preset-All").performScrollTo().performClick()
        compose.onNodeWithTag("map-range-show").assertTextEquals("Show 35 days")
    }
    @Test @SdkSuppress(minSdkVersion=33)
    fun spanishLabelsUseTheActualAppLocale() {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        val previous=target.getSystemService(android.app.LocaleManager::class.java).applicationLocales
        try {
            applyQaAppLocales(compose,LocaleList.forLanguageTags("es"))
            val draft=MapRangeDraft.open(MapSelection("Custom",outingRange=window),now,ZoneOffset.UTC)
            compose.setContent {MaterialTheme {MapRangeSheet(draft,emptySet(),{now},ZoneOffset.UTC,{}) {result=it}}}
            compose.onNodeWithText("Mostrar días").assertIsDisplayed()
            compose.onNodeWithText("Este mes").assertExists()
            compose.onNodeWithTag("map-range-show").assertTextEquals("Mostrar 3 días")
            compose.onNodeWithTag("map-range-calendar-day-2026-10-11").performScrollTo().performClick()
            compose.onNodeWithTag("map-range-show").assertTextEquals("Mostrar 1 día").performClick()
            compose.runOnIdle {assertEquals("Custom",result!!.preset)}
        } finally {applyQaAppLocales(compose,previous)}
    }
}
