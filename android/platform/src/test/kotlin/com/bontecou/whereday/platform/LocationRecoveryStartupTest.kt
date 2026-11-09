package com.bontecou.whereday.platform

import android.provider.Settings
import androidx.sqlite.driver.AndroidSQLiteDriver
import androidx.work.Configuration
import androidx.work.WorkManager
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.Instant

/** Host-only Room/SQLite regression; no Activity, service, provider or broadcast is launched. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [35], manifest = Config.NONE)
class LocationRecoveryStartupTest {
    @Test fun processStartupRecoversBothModesWithoutWaitingForBootBroadcastOrActivity() = runBlocking {
        val context = RuntimeEnvironment.getApplication()
        WorkManager.initialize(context, Configuration.Builder().build())
        val seedDatabase = HistoryDatabase.create(context, name = "recovery.db", driver = AndroidSQLiteDriver())
        val seedHistory = HistoryRepository(seedDatabase)
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 3)
        val recording = seedHistory.start(0, startedBootCount = 3)
        val monitoring = seedHistory.startVisitMonitoring(startedBootCount = 3)
        val point = TrackPoint("preserved", Coordinate(0.0, 0.0), Instant.EPOCH, 5.0)
        seedHistory.dao.put(PointRow(point, null))
        val arrival = Instant.now().minusSeconds(600)
        val lastObservation = arrival.plusSeconds(300)
        val visit = Visit(id = "open-before-reboot", coordinate = Coordinate(0.0, 0.0), arrivedAt = arrival)
        seedHistory.dao.put(VisitRow(visit))
        seedHistory.dao.put(DwellRow(state = DwellState(anchor = visit.coordinate,
            candidateSince = arrival, lastTime = lastObservation, visitId = visit.id)))
        seedDatabase.close()
        // Reopen durable history with fresh repository/graph state, as a new process does.
        val database = HistoryDatabase.create(context, name = "recovery.db", driver = AndroidSQLiteDriver())
        val history = HistoryRepository(database)
        Settings.Global.putInt(context.contentResolver, Settings.Global.BOOT_COUNT, 4)
        assertFalse(RecordingRuntime.active.value)
        assertEquals(VisitMonitoringMode.off, VisitMonitoringRuntime.state.value)
        val graph = AppGraph.from(context) {database}
        try {
            graph.journalClear.awaitReady()
            // Start no Activity and deliver no synthetic broadcast: graph startup must own
            // stale durable intent even when another Android component creates the process.
            withTimeout(2_000) {
                while (history.dao.recording()?.requested == true || history.dao.visitMonitoring()?.requested == true)
                    delay(10)
            }
            assertEquals(RecordingInterruption.phoneRestarted.code, history.dao.recording()!!.interruption)
            assertEquals("visits.restarted", history.dao.visitMonitoring()!!.interruption)
            assertEquals(point, history.dao.point(point.id)!!.value)
            assertEquals(0L, history.dao.openSessionCount())
            assertEquals(history.dao.session(recording.sessionId!!)!!.value.startedAt,
                history.dao.session(recording.sessionId!!)!!.value.endedAt)
            assertNotNull(monitoring.generation)
            assertEquals(lastObservation.toEpochMilli(), history.dao.visit(visit.id)!!.value.departedAt!!.toEpochMilli())
            assertNull(history.dao.dwell()!!.state.visitId)
            assertNull(history.dao.visitMonitoring()!!.recent.time)

            // A delayed boot/lifecycle check must not interrupt fresh acknowledged owners
            // or either queued start ticket after initial recovery has completed.
            history.start(0, startedBootCount = 4)
            history.startVisitMonitoring(startedBootCount = 4)
            val freshRoute = history.dao.recording()!!
            val freshVisits = history.dao.visitMonitoring()!!
            RecordingRuntime.update(true)
            VisitMonitoringRuntime.update(VisitMonitoringMode.route)
            graph.reconcileLocationServices()
            assertEquals(freshRoute, history.dao.recording())
            assertEquals(freshVisits, history.dao.visitMonitoring())
            RecordingRuntime.update(false)
            VisitMonitoringRuntime.update(VisitMonitoringMode.off)
            val routeTicket = RecordingStartTickets.create()
            val visitTicket = VisitStartTickets.create()
            try {
                graph.reconcileLocationServices()
                assertEquals(freshRoute, history.dao.recording())
                assertEquals(freshVisits, history.dao.visitMonitoring())
            } finally {
                RecordingStartTickets.release(routeTicket, cancel = true)
                VisitStartTickets.release(visitTicket, cancel = true)
            }
            graph.reconcileLocationServices()
            assertFalse(history.dao.recording()!!.requested)
            assertFalse(history.dao.visitMonitoring()!!.requested)
            assertEquals(RecordingInterruption.processInterrupted.code, history.dao.recording()!!.interruption)
            assertEquals("visits.interrupted", history.dao.visitMonitoring()!!.interruption)
            assertFalse(RecordingRuntime.active.value)
            assertEquals(VisitMonitoringMode.off, VisitMonitoringRuntime.state.value)
        } finally {
            RecordingRuntime.update(false)
            VisitMonitoringRuntime.update(VisitMonitoringMode.off)
            graph.locationRecovery.close()
            graph.journalClear.close()
            graph.reviewMilestones.close()
            database.close()
        }
    }
}
