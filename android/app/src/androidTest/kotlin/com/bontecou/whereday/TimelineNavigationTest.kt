package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.lifecycle.SavedStateHandle
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.flow.filterIsInstance
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.time.*

class TimelineNavigationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var graph:AppGraph
    private lateinit var model:JournalViewModel
    private val models=ViewModelStore()
    private val today=LocalDate.of(2026,11,2)
    private val day=today.minusDays(3)
    private val start=day.atTime(9,0).toInstant(ZoneOffset.UTC)
    @Before fun seed():Unit=runBlocking {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName=="com.bontecou.whereday.mapqa")
        graph=AppGraph.from(target);graph.journalClear.clear().join()
        graph.preferences.update {it.copy(onboarded=true,inferredEnabled=false,networkGeocoding=false)}
        graph.history.dao.put(VisitRow(Visit("timeline-first",Coordinate(18.0,-66.0),start,start.plusSeconds(60),name="Earlier timeline visit")))
        graph.history.dao.put(SessionRow(RecordingSession("timeline-session",start,start.plusSeconds(60),name="Tied timeline outing")))
        graph.history.dao.put(VisitRow(Visit("timeline-last",Coordinate(18.0,-66.0),start.plusSeconds(120),start.plusSeconds(180),name="Later timeline visit")))
        compose.activityRule.scenario.onActivity {
            model=JournalViewModel(graph,Clock.fixed(today.atTime(12,0).toInstant(ZoneOffset.UTC),ZoneOffset.UTC),{ZoneOffset.UTC})
            models.put("timeline",model)
        }
    }
    @After fun close():Unit=runBlocking {
        compose.activityRule.scenario.onActivity {models.clear()}
        graph.journalClear.clear().join()
    }
    private fun show()=compose.setContent {MaterialTheme {TimelineScreen(graph,model,true,{})}}
    private fun ready()=compose.waitUntil(10_000) {compose.onAllNodesWithTag("timeline-summary").fetchSemanticsNodes().isNotEmpty()}
    @Test fun emptyTodayInitiallyOpensTheLatestActivityDay() {
        show();ready()
        compose.runOnIdle {assertEquals(day,model.timelineDay.value)}
        compose.onNodeWithText("Earlier timeline visit").assertExists()
    }
    @Test fun openingAChooserBeforeTheFirstHistoryWindowConsumesInitialSelection():Unit=runBlocking {
        // No timeline subscriber has started: the first real Room result will find older history.
        val opening=withContext(Dispatchers.Main) {model.openTimelineDate(today)}
        val first=withTimeout(10_000) {model.timelineWindow.filterIsInstance<WindowState.Ready>().first()}
        assertEquals(today,opening.original);assertEquals(ZoneOffset.UTC,opening.zone)
        assertEquals(today,model.timelineDay.value)
        assertEquals(HistoryRange.day(today,ZoneOffset.UTC),first.window.range)
        assertEquals(start.plusSeconds(120),first.window.latestTimelineActivity)
        assertTrue(first.window.visits.isEmpty());assertTrue(first.window.sessions.isEmpty())
    }
    @Test fun eventsAreChronologicalWithVisitsBeforeTiedOutings() {
        model.timelineDay.value=day;show();ready()
        val earlier=compose.onNodeWithText("Earlier timeline visit").getUnclippedBoundsInRoot().top
        val outing=compose.onNodeWithText("Tied timeline outing").getUnclippedBoundsInRoot().top
        val later=compose.onNodeWithText("Later timeline visit").getUnclippedBoundsInRoot().top
        assertTrue("Visit must precede its tied outing",earlier<outing)
        assertTrue("Later events must follow earlier events",outing<later)
    }
    @Test fun emptyDayJumpAndTodayRespectExplicitChoicesAfterInitialization() {
        show();ready();compose.runOnIdle {assertEquals(day,model.timelineDay.value)}
        compose.onNodeWithTag("timeline-today").performClick();ready()
        compose.runOnIdle {assertEquals(today,model.timelineDay.value)}
        compose.onNodeWithTag("timeline-empty-jump").assertTextEquals("Jump to latest data").performClick();ready()
        compose.runOnIdle {assertEquals(day,model.timelineDay.value)}
        compose.onNodeWithTag("timeline-previous").performClick();ready()
        compose.onNodeWithTag("timeline-empty-jump").performClick();ready()
        compose.runOnIdle {assertEquals(day,model.timelineDay.value)}
    }
    @Test fun nextDayStopsAtTodayAndFutureDatesWithoutBlockingPreviousDay() {
        model.selectTimelineDay(today);show();ready()
        compose.onNodeWithTag("timeline-next").assertIsNotEnabled()
        compose.runOnIdle {model.selectTimelineDay(today.plusDays(1))};ready()
        compose.onNodeWithTag("timeline-next").assertIsNotEnabled()
        compose.onNodeWithTag("timeline-previous").performClick();ready()
        compose.runOnIdle {assertEquals(today,model.timelineDay.value)}
        compose.onNodeWithTag("timeline-previous").performClick();ready()
        compose.onNodeWithTag("timeline-next").assertIsEnabled().performClick();ready()
        compose.runOnIdle {assertEquals(today,model.timelineDay.value)}
    }
    @Test fun restoredDayIsNotOverriddenByInitialSelectionAndClearDropsIt() {
        val handle=SavedStateHandle(mapOf("timeline-v1-day" to day.minusDays(1).toString(),"timeline-v1-generation" to graph.journalClear.state.value.generation.orEmpty()))
        compose.activityRule.scenario.onActivity {
            model=JournalViewModel(graph,Clock.fixed(today.atTime(12,0).toInstant(ZoneOffset.UTC),ZoneOffset.UTC),{ZoneOffset.UTC},handle)
            models.put("timeline",model)
        }
        show();ready();compose.runOnIdle {assertEquals(day.minusDays(1),model.timelineDay.value)}
        compose.onNodeWithTag("timeline-empty-jump").performClick();ready()
        compose.runOnIdle {
            assertEquals(day.toString(),handle.get<String>("timeline-v1-day"))
            model.journalCleared("replacement-journal");assertEquals(today,model.timelineDay.value)
            assertEquals("replacement-journal",handle.get<String>("timeline-v1-generation"))
        }
    }
    @Test fun latestActivityUsesSessionEndsAndPointTimesButNotVisitDepartures():Unit=runBlocking {
        val departure=today.atTime(11,0).toInstant(ZoneOffset.UTC)
        graph.history.dao.put(VisitRow(Visit("long-visit",Coordinate(18.0,-66.0),start.minusSeconds(60),departure)))
        val end=start.plusSeconds(3600)
        graph.history.dao.put(SessionRow(RecordingSession("later-end",start,end)))
        assertEquals(end,graph.history.dao.latestTimelineActivity())
        val point=end.plusSeconds(60)
        graph.history.dao.putPoints(listOf(PointRow(TrackPoint("latest-point",Coordinate(18.0,-66.0),point,5.0,outlier=true),null)))
        val window=graph.history.readWindow(HistoryRange.day(day,ZoneOffset.UTC),includeTimelineActivity=true)
        assertEquals(point,window.latestTimelineActivity)
        assertNull(graph.history.readWindow(window.range).latestTimelineActivity)
        assertEquals(departure,graph.history.dao.visit("long-visit")!!.value.departedAt)
    }
}
