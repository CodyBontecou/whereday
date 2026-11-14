package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.time.*
import java.util.Locale

class TimelineStatisticsFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var graph:AppGraph
    private lateinit var model:JournalViewModel
    private val models=ViewModelStore()
    private val day=LocalDate.of(2026,11,1)
    private val start=day.atStartOfDay().toInstant(ZoneOffset.UTC)
    @Before fun seed():Unit=runBlocking {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName=="com.bontecou.whereday.mapqa")
        graph=AppGraph.from(target);graph.journalClear.clear().join()
        graph.preferences.update {it.copy(onboarded=true,inferredEnabled=false,networkGeocoding=false)}
        compose.activityRule.scenario.onActivity {
            model=JournalViewModel(graph,Clock.fixed(start.plusSeconds(43200),ZoneOffset.UTC),{ZoneOffset.UTC})
            models.put("timeline",model);model.selectTimelineDay(day)
        }
    }
    @After fun close():Unit=runBlocking {
        compose.activityRule.scenario.onActivity {models.clear()};graph.journalClear.clear().join()
    }
    private fun show()=compose.setContent {MaterialTheme {TimelineScreen(graph,model,true,{})}}
    private fun ready()=compose.waitUntil(10_000) {compose.onAllNodesWithTag("timeline-summary").fetchSemanticsNodes().isNotEmpty()}
    @Test fun dayTotalSumsFullOutingsWithoutTheGapBetweenRecordings():Unit=runBlocking {
        val a=TrackPoint("a",Coordinate(18.0,-66.0),start.plusSeconds(100),5.0)
        val b=a.copy(id="b",coordinate=Coordinate(18.01,-66.0),time=start.plusSeconds(200))
        val c=a.copy(id="c",coordinate=Coordinate(40.0,-66.0),time=start.plusSeconds(10000))
        val d=c.copy(id="d",coordinate=Coordinate(40.01,-66.0),time=start.plusSeconds(10100))
        graph.history.dao.put(SessionRow(RecordingSession("first",a.time,b.time,name="First recording")))
        graph.history.dao.put(SessionRow(RecordingSession("second",c.time,d.time,name="Second recording")))
        graph.history.dao.putPoints(listOf(a,b,c,d).map {PointRow(it,null)})
        show();ready()
        val total=a.coordinate.distanceTo(b.coordinate)+c.coordinate.distanceTo(d.coordinate)
        compose.onNodeWithTag("timeline-summary").assertTextContains(distanceText(total,true,Locale.US),substring=true)
        val raw=graph.history.readWindow(HistoryRange.day(day,ZoneOffset.UTC)).summary.distanceMeters
        assertTrue(raw>total*100)
    }
    @Test fun inferredChunkRetainsYesterdayPointsAndMinimumQualificationInTimelineAndDetail():Unit=runBlocking {
        graph.preferences.update {it.copy(inferredEnabled=true,inferredMinimumPoints=2)}
        val a=TrackPoint("before-midnight",Coordinate(18.0,-66.0),start.minusSeconds(60),5.0)
        val b=a.copy(id="after-midnight",coordinate=Coordinate(18.01,-66.0),time=start.plusSeconds(60))
        graph.history.dao.putPoints(listOf(PointRow(a,null),PointRow(b,null)))
        show();ready()
        compose.onNodeWithText("Inferred outing").assertExists()
        val distance=distanceText(a.coordinate.distanceTo(b.coordinate),true,Locale.US)
        compose.onNodeWithTag("timeline-summary").assertTextContains(distance,substring=true)
        compose.onNodeWithText("Inferred outing").performScrollTo().performClick()
        compose.waitUntil(10_000) {compose.onAllNodesWithTag("outing-point-summary").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("outing-point-summary").assertTextContains("2 points",substring=true)
        compose.onNodeWithText(distance).assertExists()
        assertEquals(2L,graph.history.dao.totalPointCount())
    }
}
