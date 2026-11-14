package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.VisitRow
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.time.*
import java.util.TimeZone

class VisitTimestampEditorIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var graph:AppGraph
    private lateinit var previousZone:TimeZone
    @Before fun setup()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.mapqa")
        previousZone=TimeZone.getDefault();TimeZone.setDefault(TimeZone.getTimeZone("America/New_York"))
        graph=AppGraph.from(context);graph.journalClear.awaitReady();graph.journalClear.clear().join()
    }
    @After fun cleanup()=runBlocking {
        try {if(::graph.isInitialized) graph.journalClear.clear().join()}
        finally {TimeZone.setDefault(previousZone)}
    }
    private fun control(owner:String,part:String)=compose.onNode(hasTestTag("visit-timestamp-$part") and hasAnyAncestor(hasTestTag("visit-$owner-time")))
    private suspend fun exercise(original:Visit,chooseEarlier:Boolean=false) {
        graph.history.dao.put(VisitRow(original))
        var dismissed by mutableStateOf(false)
        compose.setContent {MaterialTheme {
            if(!dismissed) VisitEditor(graph,original,{dismissed=true},{value,reuse,remember,overlap->graph.history.saveVisit(value,original,reuse,remember,overlap)},null)
        }}
        for(owner in listOf("arrival","departure")) {
            control(owner,"date").performScrollTo().performClick()
            compose.onNodeWithText("Apply date").assertIsEnabled().performClick()
            control(owner,"time").performScrollTo().performClick()
            if(chooseEarlier && owner=="arrival") compose.onNodeWithText("First occurrence · UTC-04:00").performScrollTo().performClick()
            compose.onNodeWithText("Apply time").assertIsEnabled().performClick()
        }
        assertEquals(original,graph.history.dao.visit(original.id)!!.value)
        compose.onNodeWithTag("visit-notes").performScrollTo().performTextReplacement("Verified timestamp QA")
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot())
            .inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog())
            .perform(androidx.test.espresso.action.ViewActions.closeSoftKeyboard())
        compose.onNodeWithText("Save").performClick()
        compose.waitUntil(10000) {dismissed}
        val saved=graph.history.dao.visit(original.id)!!.value
        assertEquals(if(chooseEarlier) original.arrivedAt.minusSeconds(3600) else original.arrivedAt,saved.arrivedAt)
        assertEquals(original.departedAt,saved.departedAt)
        assertEquals(original.departureInferred,saved.departureInferred)
        assertEquals(original.source,saved.source);assertEquals(original.confirmation,saved.confirmation)
        assertEquals(original.coordinate,saved.coordinate);assertEquals("Verified timestamp QA",saved.notes)
        assertEquals(1L,graph.history.dao.totalVisitCount());assertFalse(graph.history.dao.recording()?.requested==true)
    }
    @Test fun savingAnHistoricalVisitDoesNotAlterItsExactArrivalOrDeparture()=runBlocking {
        val at=Instant.parse("1850-12-31T18:12:34.125Z")
        exercise(Visit("historical",Coordinate(40.0,-74.0),at,at.plusSeconds(3600),name="Historical QA",departureInferred=true))
    }
    @Test fun savingAnOverlapVisitKeepsBothOriginalOccurrencesAndPrecision()=runBlocking {
        val at=Instant.parse("2025-11-02T06:30:45.125Z")
        exercise(Visit("overlap",Coordinate(40.0,-74.0),at,at.plusSeconds(900),name="Overlap QA",departureInferred=true))
    }
    @Test fun choosingTheEarlierOccurrenceCommitsOnlyTheIntendedArrivalChange()=runBlocking {
        val at=Instant.parse("2025-11-02T06:30:45.125Z")
        exercise(Visit("changed",Coordinate(40.0,-74.0),at,at.plusSeconds(900),name="Changed QA",departureInferred=true),true)
    }
}
