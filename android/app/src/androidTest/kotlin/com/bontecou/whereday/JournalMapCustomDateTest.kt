package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.HistoryRange
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.time.*

class JournalMapCustomDateTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val models=ViewModelStore()
    private var now=Instant.parse("2026-10-10T18:12:34.125Z")
    private val zone=ZoneOffset.UTC
    private val clock=object:Clock() {
        override fun getZone():ZoneId=zone
        override fun withZone(zone:ZoneId):Clock=Clock.fixed(now,zone)
        override fun instant():Instant=now
    }
    private lateinit var model:JournalViewModel
    private lateinit var graph:AppGraph
    @Before fun setup()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.mapqa")
        graph=AppGraph.from(context);graph.journalClear.awaitReady()
        model=JournalViewModel(graph,clock,{zone});models.put("journal",model)
    }
    private fun show()=compose.setContent {MaterialTheme {JournalMapScreen(graph,model,false,null,true,{},false,{})}}
    private fun openCustom() {
        // The map's range chips end with a calendar button that opens the range calendar.
        compose.onNodeWithTag("map-range-custom").performClick()
        compose.onNodeWithText("Show days").assertIsDisplayed()
    }

    @Test fun theMonthPresetIsOfferedInTheRangeCalendar() {
        show()
        openCustom()
        compose.onNodeWithTag("map-range-preset-This month").performClick()
        compose.onNodeWithTag("map-range-apply").performClick()
        compose.runOnIdle {
            assertEquals(MapSelection("This month"),model.mapSelection.value)
            assertEquals(HistoryRange(Instant.parse("2026-10-01T00:00:00Z"),now.plusMillis(1)),model.mapSelection.value.range(now,zone))
        }
    }
    @Test fun anActivePresetOpensSelectedAndAppliesUnchanged() {
        show()
        for(preset in listOf("Today","Yesterday","7 days","This month","30 days","All")) {
            compose.runOnIdle {model.mapSelection.value=MapSelection(preset)}
            openCustom()
            compose.onNodeWithTag("map-range-preset-$preset").assertIsSelected()
            compose.onNodeWithTag("map-range-apply").performClick()
            compose.onNodeWithText("Show days").assertDoesNotExist()
            compose.runOnIdle {assertEquals(MapSelection(preset),model.mapSelection.value)}
        }
    }
    @Test fun anOutingOrCustomWindowReopensOnItsWholeDays() {
        show()
        val selections=listOf(
            MapSelection("Custom",LocalDate.of(1850,12,31),LocalDate.of(1851,1,2)) to HistoryRange.days(LocalDate.of(1850,12,31),LocalDate.of(1851,1,2),zone),
            MapSelection("Outing",outingRange=HistoryRange(Instant.parse("2026-10-08T18:12:34.125Z"),Instant.parse("2026-10-09T02:00:00Z")))
                to HistoryRange.days(LocalDate.of(2026,10,8),LocalDate.of(2026,10,9),zone))
        for((selection,expected) in selections) {
            compose.runOnIdle {model.mapSelection.value=selection}
            openCustom()
            compose.onNodeWithTag("map-range-apply").performClick()
            compose.runOnIdle {
                assertEquals("Custom",model.mapSelection.value.preset)
                assertEquals("Range for $selection",expected,model.mapSelection.value.range(now,zone))
            }
        }
    }
    @Test fun dismissingDiscardsTapsAndAnOpenDraftSurvivesRestorationAndClockAdvance() {
        model.mapSelection.value=MapSelection("7 days")
        val tester=StateRestorationTester(compose)
        tester.setContent {MaterialTheme {JournalMapScreen(graph,model,false,null,true,{},false,{})}}
        openCustom()
        compose.onNodeWithTag("map-range-calendar-day-2026-10-02").performScrollTo().performClick()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("Show days").assertDoesNotExist()
        compose.runOnIdle {assertEquals(MapSelection("7 days"),model.mapSelection.value)}
        openCustom()
        compose.onNodeWithTag("map-range-preset-7 days").assertIsSelected()
        compose.onNodeWithTag("map-range-calendar-day-2026-10-02").performScrollTo().performClick()
        compose.onNodeWithTag("map-range-calendar-day-2026-10-06").performScrollTo().performClick()
        compose.runOnIdle {now=now.plusSeconds(3600)}
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("map-range-show").assertTextEquals("Show 5 days").performClick()
        compose.runOnIdle {assertEquals(HistoryRange.days(LocalDate.of(2026,10,2),LocalDate.of(2026,10,6),zone),model.mapSelection.value.range(now,zone))}
    }
    @After fun close() {compose.runOnUiThread {models.clear()}}
}
