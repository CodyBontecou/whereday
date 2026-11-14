package com.bontecou.whereday.platform

import android.os.Process
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.time.Instant

/** Host harness runs three separate instrumentation processes, force-stopping this disposable package between them. */
class JournalClearProcessRecoveryTest {
    @Test fun separateProcessRecovery():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.platform.test")
        val phase=InstrumentationRegistry.getArguments().getString("clearRecoveryPhase")
        require(phase in setOf("seed","recover","completed"))
        val proof=File(context.noBackupFilesDir,"qa-clear-proof")
        val receipts=AndroidJournalClearReceiptStore(context)
        when(phase) {
            "seed"->{
                check(!proof.exists());val graph=AppGraph.from(context);graph.journalClear.awaitReady()
                graph.history.dao.put(PointRow(TrackPoint("process-clear-old",Coordinate(0.0,0.0),Instant.now(),5.0),null))
                graph.automationAccess.approve(requireNotNull(graph.automationAccess.installed(context.packageName)),setOf(AutomationScope.summary))
                val entered=CompletableDeferred<Unit>();val canceling=CompletableDeferred<Unit>();val neverRelease=CompletableDeferred<Unit>()
                // Intentionally detached: the host terminates this process at the actual pipeline's join checkpoint.
                CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {runCatching {
                    graph.automationResults.create(context.packageName,AutomationScope.summary,"process-clear-partial.json","application/json") {
                        it.write("partial".toByteArray());entered.complete(Unit)
                        try {awaitCancellation()} finally {withContext(NonCancellable) {canceling.complete(Unit);neverRelease.await()}}
                    }
                }}
                withTimeout(15000) {entered.await()};graph.journalClear.clear();withTimeout(15000) {canceling.await()}
                assertTrue(graph.journalClear.state.value.blocked);assertFalse(requireNotNull(receipts.read()).complete)
                assertEquals(1L,graph.history.dao.totalPointCount())
                proof.writeText("seed:${Process.myPid()}\n")
            }
            "recover"->{
                val prior=proof.readLines().single();assertEquals("seed",prior.substringBefore(':'));assertNotEquals(prior.substringAfter(':'),Process.myPid().toString())
                val pending=requireNotNull(receipts.read());assertFalse(pending.complete)
                val graph=AppGraph.from(context);withTimeout(30000) {graph.journalClear.awaitReady()}
                assertEquals(JournalClearReceipt(pending.id,true),receipts.read());assertEquals(0L,graph.history.dao.totalPointCount())
                assertNull(graph.automationAccess.approval());assertFalse(RecordingRuntime.active.value)
                graph.history.dao.put(PointRow(TrackPoint("process-clear-new",Coordinate(0.0,0.0),Instant.now(),5.0),null))
                proof.appendText("recover:${Process.myPid()}\n")
            }
            "completed"->{
                val prior=proof.readLines();assertEquals(2,prior.size);assertNotEquals(prior.last().substringAfter(':'),Process.myPid().toString())
                val completed=requireNotNull(receipts.read());assertTrue(completed.complete)
                val graph=AppGraph.from(context);withTimeout(30000) {graph.journalClear.awaitReady()}
                assertEquals(JournalClearPhase.idle,graph.journalClear.state.value.phase)
                assertEquals(1L,graph.history.dao.totalPointCount());assertEquals("process-clear-new",graph.history.dao.latestPoints().single().value.id)
                assertEquals(completed,receipts.read());proof.appendText("completed:${Process.myPid()}\n")
                graph.history.dao.deletePointsById(listOf("process-clear-new"))
            }
        }
    }
}
