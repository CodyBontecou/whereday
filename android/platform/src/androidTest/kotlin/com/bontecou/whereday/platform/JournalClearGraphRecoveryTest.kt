package com.bontecou.whereday.platform

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.time.Instant
import java.util.UUID

/** Run this class alone in a fresh platform.test instrumentation process; seeds no owner data. */
class JournalClearGraphRecoveryTest {
    @Test fun coldGraphResumesAnAuthorizedPendingClearAndRetainsItsCompletedReceipt():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.platform.test")
        val database=HistoryDatabase.open(context)
        val session=RecordingSession(startedAt=Instant.now().minusSeconds(60))
        database.history().put(SessionRow(session))
        database.history().put(RecordingRow(requested=true,sessionId=session.id))
        database.history().put(VisitMonitoringRow(requested=true,generation=UUID.randomUUID().toString(),startedAt=session.startedAt))
        database.history().put(PointRow(TrackPoint("clear-recovery-synthetic",Coordinate(0.0,0.0),session.startedAt.plusSeconds(10),5.0),session.id))
        val access=AutomationAccess(context)
        access.approve(requireNotNull(access.installed(context.packageName)),setOf(AutomationScope.summary))
        val receipts=AndroidJournalClearReceiptStore(context);val id=UUID.randomUUID().toString();receipts.begin(id)
        val graph=AppGraph.from(context)
        withTimeout(30000) {graph.journalClear.awaitReady()}
        assertEquals(JournalClearPhase.complete,graph.journalClear.state.value.phase)
        assertEquals(JournalClearReceipt(id,true),receipts.read())
        assertEquals(0L,graph.history.dao.totalPointCount());assertEquals(0L,graph.history.dao.openSessionCount())
        assertNull(graph.history.dao.visitMonitoring());assertEquals(VisitMonitoringMode.off,VisitMonitoringRuntime.state.value)
        assertNull(graph.automationAccess.approval());assertFalse(RecordingRuntime.active.value)
        assertFalse(graph.journalClear.state.value.blocked)
        val later=TrackPoint("after-completed-clear",Coordinate(0.0,0.0),Instant.now(),5.0)
        graph.history.dao.put(PointRow(later,null))
        graph.journalClear.awaitReady()
        assertEquals(1L,graph.history.dao.totalPointCount())
        assertEquals(JournalClearReceipt(id,true),AndroidJournalClearReceiptStore(context).read())
        graph.history.dao.deletePointsById(listOf(later.id))
    }
}
