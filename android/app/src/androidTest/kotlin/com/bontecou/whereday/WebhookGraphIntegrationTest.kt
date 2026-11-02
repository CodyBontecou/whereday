package com.bontecou.whereday

import androidx.test.ext.junit.rules.ActivityScenarioRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.WorkManager
import com.bontecou.whereday.data.PointRow
import com.bontecou.whereday.domain.Coordinate
import com.bontecou.whereday.domain.TrackPoint
import com.bontecou.whereday.platform.AppGraph
import com.bontecou.whereday.platform.WebhookWorkScheduler
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Actual application/lifecycle graph, exclusively in the disposable mapQa application. */
@RunWith(AndroidJUnit4::class)
class WebhookGraphIntegrationTest {
    @get:Rule val activity=ActivityScenarioRule(MainActivity::class.java)
    @Test fun freshLaunchStaysDisabledAndTheGraphClearPathLeavesNoResumableWebhookWork():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.mapqa")
        val graph=AppGraph.from(context)
        withTimeout(15_000) {while(!graph.webhookController.state.value.loaded) delay(25)}
        val initial=graph.webhooks.state()
        assertFalse(initial.configuration.settings.enabled);assertEquals("",initial.configuration.settings.endpoint)
        assertNull(initial.queue.policy);assertEquals(0L,initial.queue.queuedPoints)
        assertTrue(WorkManager.getInstance(context).getWorkInfosByTag(WebhookWorkScheduler.TAG).get().none {!it.state.isFinished})
        graph.database.history().putPoints(listOf(PointRow(TrackPoint(id="webhook-graph-synthetic",coordinate=Coordinate(0.0,0.0),time=Instant.parse("2026-10-27T12:00:00Z"),accuracy=5.0),null)))
        assertEquals(1L,graph.database.history().totalPointCount())
        graph.webhookController.clearAll {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
        assertEquals(0L,graph.database.history().totalPointCount())
        val cleared=graph.webhooks.state()
        assertEquals("",cleared.configuration.revision);assertFalse(cleared.configuration.settings.enabled);assertNull(cleared.queue.policy)
        graph.webhookController.foreground()
        withTimeout(15_000) {while(graph.webhookController.state.value.state?.configuration?.revision!="") delay(25)}
        assertTrue(WorkManager.getInstance(context).getWorkInfosByTag(WebhookWorkScheduler.TAG).get().none {!it.state.isFinished})
    }
}
