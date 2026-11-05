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
    @Test fun openDraftKeepsItsTimeBoundsAfterSavedStateRestorationAndClockAdvance() {
        model.mapSelection.value=MapSelection("7 days")
        val expected=model.mapSelection.value.range(now,zone)
        val tester=StateRestorationTester(compose)
        tester.setContent {MaterialTheme {JournalMapScreen(graph,model,false,null,true,{},false,{})}}
        openCustom("7 days")
        compose.runOnIdle {now=now.plusSeconds(3600)}
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Apply").assertIsEnabled().performClick()
        compose.runOnIdle {assertEquals(expected,model.mapSelection.value.range(now,zone))}
    }
    @Test fun cancelDiscardsDateEditsAndReopeningUsesTheActiveSelection() {
        val selection=MapSelection("7 days");model.mapSelection.value=selection
        val expected=selection.range(now,zone)
        compose.setContent {MaterialTheme {JournalMapScreen(graph,model,false,null,true,{},false,{})}}
        openCustom("7 days");compose.onNodeWithText("Enter ISO dates").performClick()
        compose.onNodeWithTag("date-range-from").performTextReplacement("1850-12-31")
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(androidx.test.espresso.action.ViewActions.closeSoftKeyboard())
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {assertEquals(selection,model.mapSelection.value)}
        openCustom("7 days");compose.onNodeWithText("Apply").assertIsEnabled().performClick()
        compose.runOnIdle {assertEquals(expected,model.mapSelection.value.range(now,zone))}
    }
    @Test fun editingOneMapBoundRetainsTheOtherExactInstant() {
        val first=Instant.parse("2026-10-10T18:12:34.125Z")
        val last=Instant.parse("2026-10-12T20:34:56.875Z")
        compose.setContent {MaterialTheme {JournalMapScreen(graph,model,false,null,true,{},false,{})}}
        for(editFirst in listOf(true,false)) {
            compose.runOnIdle {model.mapSelection.value=MapSelection("Outing",outingRange=HistoryRange(first,last.plusMillis(1)))}
            openCustom("Outing");compose.onNodeWithText("Enter ISO dates").performClick()
            compose.onNodeWithTag(if(editFirst) "date-range-from" else "date-range-through")
                .performTextReplacement(if(editFirst) "2026-10-11" else "2026-10-13")
            androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
                .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
                .perform(androidx.test.espresso.action.ViewActions.closeSoftKeyboard())
            compose.onNodeWithText("Apply").assertIsEnabled().performClick()
            compose.runOnIdle {
                val actual=model.mapSelection.value.range(now,zone)
                assertEquals(if(editFirst) Instant.parse("2026-10-11T18:12:00Z") else first,actual.from)
                assertEquals(if(editFirst) last.plusMillis(1) else Instant.parse("2026-10-13T20:34:00.001Z"),actual.until)
            }
        }
    }

    private fun openCustom(label:String) {
        // The map's range chips end with a calendar button for a custom range.
        compose.onNodeWithTag("map-range-custom").performClick()
        compose.onNodeWithText("Choose dates").assertIsDisplayed()
    }
    @After fun close() {compose.runOnUiThread {models.clear()}}
    @Test fun openingCustomAndApplyingWithoutChangesKeepsEveryActiveRange() {
        compose.setContent {MaterialTheme {JournalMapScreen(graph,model,false,null,true,{},false,{})}}
        val selections=listOf("Today","Yesterday","7 days","30 days","All").map {MapSelection(it)}+
            MapSelection("Custom",LocalDate.of(1850,12,31),LocalDate.of(1851,1,2))+
            MapSelection("Outing",outingRange=HistoryRange(now.minusSeconds(86400),now.minusSeconds(80000)))
        for(selection in selections) {
            compose.runOnIdle {model.mapSelection.value=selection}
            val expected=selection.range(now,zone)
            compose.onNodeWithTag("map-range-custom").performClick()
            compose.onNodeWithText("Choose dates").assertIsDisplayed()
            compose.onNodeWithText("Apply").assertIsEnabled().performClick()
            compose.runOnIdle {
                assertEquals("Custom",model.mapSelection.value.preset)
                assertEquals("Range changed for $selection",expected,model.mapSelection.value.range(now,zone))
            }
        }
    }
    private fun mapLabel(preset:String)=preset
}
