package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.data.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.time.Instant
import java.util.Locale

class MapPointCallerUnitsTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var graph:AppGraph
    private val metric=mutableStateOf(true)
    private val at=Instant.parse("2026-11-01T12:00:00Z")
    private val first=TrackPoint("unit-first",Coordinate(18.0,-66.0),at,8.5,35.0,2.5)
    private val points=listOf(first,first.copy(id="unit-last",coordinate=Coordinate(18.001,-66.001),time=at.plusSeconds(60)))
    private val session=RecordingSession("unit-session",at,at.plusSeconds(60),name="Unit outing")
    @Before fun setup()=runBlocking {
        val target=InstrumentationRegistry.getInstrumentation().targetContext;check(target.packageName=="com.bontecou.whereday.mapqa")
        graph=AppGraph.from(target);graph.journalClear.clear().join()
        graph.preferences.update {it.copy(networkGeocoding=false)}
        graph.history.dao.put(SessionRow(session));graph.history.dao.putPoints(points.map {PointRow(it,session.id)})
        Unit
    }
    @After fun cleanup()=runBlocking {if(::graph.isInitialized) graph.journalClear.clear().join();Unit}
    private fun inspectAndChangeUnits() {
        val label=compose.activity.getString(R.string.map_point_browse)
        compose.waitUntil(10000) {compose.onAllNodesWithTag("map-point-browse").fetchSemanticsNodes().size==1 || compose.onAllNodes(hasTestTag("journal-native-map") and SemanticsMatcher.keyIsDefined(SemanticsActions.CustomActions)).fetchSemanticsNodes().isNotEmpty()}
        // Framed maps show a Browse points capsule; the full-bleed replay map offers the same chooser as an accessibility action.
        if(compose.onAllNodesWithTag("map-point-browse").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag("map-point-browse").performScrollTo().performClick()
        else compose.onNodeWithTag("journal-native-map").performMapAction(label)
        compose.onNodeWithTag("map-point-choice-${first.id}").performScrollTo().performClick()
        // The replay map's navy tooltip opens the full point inspector.
        if(compose.onAllNodesWithTag("map-point-tooltip").fetchSemanticsNodes().isNotEmpty()) compose.onNodeWithTag("map-point-tooltip").performClick()
        val locale=compose.activity.resources.configuration.locales[0]
        compose.onNodeWithTag("map-point-speed").assertTextContains(String.format(locale,"%.1f km/h",9.0),substring=true)
        compose.runOnIdle {metric.value=false}
        compose.onNodeWithTag("map-point-speed").assertTextContains(String.format(locale,"%.1f mph",2.5*2.23693629),substring=true)
        compose.onNodeWithTag("map-point-accuracy").assertTextContains("28 ft",substring=true)
        runBlocking {assertEquals(first,graph.history.dao.exportFirstPoints(at,at.plusSeconds(61),null).first().value);assertEquals(2L,graph.history.dao.totalPointCount())}
    }
    @Test fun fallbackRecordingMapUpdatesTheSelectedPointWhenUnitsChange() {
        compose.setContent {MaterialTheme {Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {RecordingScreen(points,emptyList(),PointSummary(count=2),metric.value)}}}
        inspectAndChangeUnits()
    }
    @Test fun outingMapUpdatesTheSelectedPointWhenUnitsChange() {
        compose.setContent {MaterialTheme {OutingDetailScreen(graph,session.id,metric.value,{},{})}}
        inspectAndChangeUnits()
    }
    @Test fun replayMapUpdatesTheSelectedPointWhenUnitsChange() {
        compose.setContent {MaterialTheme {RouteReplayButton(graph,ReplayRequest.Outing(session.id),metric.value,true)}}
        compose.onNodeWithText(compose.activity.getString(R.string.map_replay)).performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithTag("replay-progress").fetchSemanticsNodes().size==1}
        inspectAndChangeUnits()
    }
}
