package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import java.time.*
import java.time.format.*

class OutingMapFocusTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var graph:AppGraph
    private lateinit var model:JournalViewModel
    private val models=ViewModelStore()
    private val start=Instant.parse("2026-11-01T00:00:00Z")
    private var now=start.plusSeconds(3600)
    private val clock=object:Clock() {
        override fun getZone()=ZoneOffset.UTC
        override fun withZone(zone:ZoneId)=this
        override fun instant()=now
    }
    private val session=RecordingSession("focus-open",start,name="Captured open outing",notes="Preserve original")
    private val a=TrackPoint("focus-a",Coordinate(18.0,-66.0),start.plusSeconds(120),5.0)
    private val b=a.copy(id="focus-b",coordinate=Coordinate(18.01,-66.0),time=start.plusSeconds(180))
    @Before fun seed():Unit=runBlocking {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName=="com.bontecou.whereday.mapqa")
        graph=AppGraph.from(target);graph.journalClear.clear().join()
        graph.preferences.update {it.copy(onboarded=true,inferredEnabled=false,networkGeocoding=false,automaticPhotoSync=false)}
        graph.history.dao.put(SessionRow(session));graph.history.dao.putPoints(listOf(PointRow(a,null),PointRow(b,null)))
        compose.activityRule.scenario.onActivity {model=JournalViewModel(graph,clock,{ZoneOffset.UTC});models.put("map-focus",model)}
    }
    @After fun close():Unit=runBlocking {
        compose.activityRule.scenario.onActivity {models.clear()};graph.journalClear.clear().join()
    }
    private fun ready()=compose.waitUntil(10_000) {compose.onAllNodesWithTag("outing-show-map").fetchSemanticsNodes().isNotEmpty()}
    private fun click()=compose.onNodeWithTag("outing-show-map").performScrollTo().performClick()
    @Test fun openFocusUsesCapturedLastFixAndFreshRequestsKeepTheSameContentScope():Unit=runBlocking {
        val targets=mutableListOf<OutingMapTarget>()
        compose.setContent {MaterialTheme {OutingDetailScreen(graph,session.id,true,{},{targets.add(it);model.showOutingOnMap(it)})}}
        ready();click()
        val first=compose.runOnIdle {targets.single()}
        assertEquals(session,first.session);assertNull(first.session.endedAt)
        val range=HistoryRange(start,b.time.plusMillis(1));assertEquals(range,first.range)
        assertEquals(range,withTimeout(10_000) {model.mapWindow.filterIsInstance<WindowState.Ready>().first()}.window.range)
        val scope=model.mapSelection.value.contentScope()
        click();compose.runOnIdle {assertEquals(2,targets.size);assertNotEquals(first.requestId,targets.last().requestId)}
        assertEquals(scope,model.mapSelection.value.contentScope())
        now=start.plusSeconds(7200)
        graph.history.dao.putPoints(listOf(PointRow(b.copy(id="outside-captured",time=b.time.plusSeconds(1)),null)))
        val window=withTimeout(10_000) {model.mapWindow.filterIsInstance<WindowState.Ready>().first()}.window
        assertEquals(range,window.range);assertEquals(listOf(a.id,b.id),window.points.map {it.id})
        assertEquals(session,graph.history.dao.session(session.id)!!.value)
    }
    @Test fun activeInferredFocusUsesItsLastFixWithoutCreatingAStoredSession():Unit=runBlocking {
        graph.history.dao.deleteSession(session.id)
        val header=InferredOutingHeader("inferred:${a.id}",a.time,b.time,PointSummary(2,0,1.0,a.time,b.time),active=true)
        var target:OutingMapTarget?=null
        compose.setContent {MaterialTheme {OutingDetailScreen(graph,header.id,true,{},{target=it},header)}}
        ready();click()
        compose.runOnIdle {assertEquals(HistoryRange(a.time,b.time.plusMillis(1)),target?.range);assertNull(target?.session?.endedAt)}
        assertTrue(graph.history.dao.allSessions().isEmpty());assertEquals(2L,graph.history.dao.totalPointCount())
    }
    @Test fun reversedTimestampDisplayAndMapTargetUseTheClampedEndWithoutRepairingStorage():Unit=runBlocking {
        val reversed=session.copy(startedAt=start.plusSeconds(500),endedAt=start.plusSeconds(400))
        graph.history.dao.put(SessionRow(reversed));var target:OutingMapTarget?=null
        compose.setContent {MaterialTheme {OutingDetailScreen(graph,reversed.id,true,{},{target=it})}}
        ready()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val stamp=DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,FormatStyle.SHORT).withLocale(context.resources.configuration.locales[0]).withZone(ZoneId.systemDefault()).format(reversed.startedAt)
        compose.onNodeWithTag("outing-time-range").assertTextEquals(context.getString(R.string.timeline_time_range,stamp,stamp))
        click();compose.runOnIdle {assertEquals(reversed,target?.session);assertEquals(HistoryRange(reversed.startedAt,reversed.startedAt.plusMillis(1)),target?.range)}
        assertEquals(reversed,graph.history.dao.session(reversed.id)!!.value)
    }
    @Test fun stoppedAndDisposedMapActionsCannotNavigateAndResumeAllowsAFreshAction() {
        val targets=mutableListOf<OutingMapTarget>();var showing by mutableStateOf(true)
        compose.setContent {MaterialTheme {if(showing) OutingDetailScreen(graph,session.id,true,{},{targets.add(it)})}}
        ready();compose.onNodeWithTag("outing-show-map").performScrollTo()
        val old=compose.onNodeWithTag("outing-show-map").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {old();assertTrue(targets.isEmpty())}
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);compose.waitForIdle()
        val current=compose.onNodeWithTag("outing-show-map").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        click();compose.runOnIdle {assertEquals(1,targets.size);showing=false};compose.waitForIdle()
        compose.runOnIdle {current();assertEquals(1,targets.size)}
    }
    @Test fun anOpenEditorBlocksMapNavigationAndCancelAllowsAFreshAction() {
        val targets=mutableListOf<OutingMapTarget>()
        compose.setContent {MaterialTheme {OutingDetailScreen(graph,session.id,true,{},{targets.add(it)})}}
        ready();compose.onNodeWithTag("outing-show-map").performScrollTo()
        val old=compose.onNodeWithTag("outing-show-map").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.onNodeWithText("Edit outing").performScrollTo().performClick()
        compose.runOnIdle {old();assertTrue(targets.isEmpty())}
        compose.onNodeWithText("Cancel").performClick();compose.waitForIdle()
        click();compose.runOnIdle {assertEquals(1,targets.size)}
    }
}
