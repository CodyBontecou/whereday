package com.bontecou.whereday.platform

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import java.time.Instant
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test

/** Run alone in a fresh platform.test process, with a synthetic provider and journal only. */
class RoadDisplayGraphTest {
    @Test fun journalClearWaitsForRoadWorkerThenResetsConsentAndErasesSyntheticHistory():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.platform.test")
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val graph=AppGraph(context,RoadDirections {a,b,_->withContext(NonCancellable) {entered.complete(Unit);release.await()};RoadDirectionsResult(a.distanceTo(b)*1.3,listOf(a,b))})
        var pending:Deferred<RoadDisplayPlan>?=null
        try {
            withTimeout(30000) {graph.journalClear.awaitReady()}
            graph.preferences.update {it.copy(roadDisplayEnabled=true,networkGeocoding=true)}
            withTimeout(5000) {graph.roadDisplay.state.first {!it.loading && !it.policyUnavailable && it.enabled}}
            val points=listOf(TrackPoint("road-graph-a",Coordinate(0.0,0.0),Instant.EPOCH,5.0),TrackPoint("road-graph-b",Coordinate(0.0,.01),Instant.EPOCH.plusSeconds(600),5.0))
            points.forEach {graph.history.dao.put(PointRow(it,null))}
            val session=graph.roadDisplay.createSession();session.foreground()
            pending=async {session.prepare(points)};withTimeout(5000) {entered.await()}
            val clear=graph.journalClear.clear()
            withTimeout(5000) {graph.roadDisplay.state.first {it.clearing}}
            assertFalse(clear.isCompleted);assertEquals(2L,graph.history.dao.totalPointCount())
            assertTrue(graph.preferences.settings.first().roadDisplayEnabled)
            release.complete(Unit);withTimeout(30000) {clear.join()};pending.join()
            assertTrue(pending.isCancelled);assertEquals(JournalClearPhase.complete,graph.journalClear.state.value.phase)
            assertEquals(0L,graph.history.dao.totalPointCount());assertFalse(graph.preferences.settings.first().roadDisplayEnabled)
            session.close()
        } finally {
            release.complete(Unit);pending?.join();graph.roadDisplay.close();graph.journalClear.close();graph.database.close()
        }
    }
}
