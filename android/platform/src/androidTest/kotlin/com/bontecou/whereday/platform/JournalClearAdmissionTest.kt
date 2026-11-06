package com.bontecou.whereday.platform

import android.app.Activity
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.workDataOf
import kotlinx.coroutines.*
import org.junit.Test
import org.junit.Assert.*
import java.util.UUID

/** Actual worker implementations with the production graph; no injected coordinator bypasses its gate. */
class JournalClearAdmissionTest {
    @Test fun workersAndRecordingStartStayBlockedWhileAnAcceptedClearJoinsAnOlderWrite():Unit=runBlocking {supervisorScope {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        check(context.packageName=="com.bontecou.whereday.platform.test")
        val graph=AppGraph.from(context);graph.journalClear.awaitReady()
        val release=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>();val canceling=CompletableDeferred<Unit>()
        graph.automationAccess.approve(requireNotNull(graph.automationAccess.installed(context.packageName)),setOf(AutomationScope.summary))
        val writer=async {runCatching {graph.automationResults.create(context.packageName,AutomationScope.summary,"clear-admission.json","application/json") {
            it.write("partial".toByteArray());entered.complete(Unit)
            try {awaitCancellation()} finally {withContext(NonCancellable) {canceling.complete(Unit);release.await()}}
        }}}
        var operation:Job?=null;val readers=mutableListOf<Job>()
        try {
            withTimeout(15000) {entered.await()};operation=graph.journalClear.clear();withTimeout(15000) {canceling.await()}
            assertTrue(graph.journalClear.state.value.blocked)
            val analytics=TestListenableWorkerBuilder.from(context,UsageAnalyticsWorker::class.java).build()
            val exports=TestListenableWorkerBuilder.from(context,ScheduledExportWorker::class.java).build()
            val webhook=TestListenableWorkerBuilder.from(context,WebhookWorker::class.java).setInputData(workDataOf(WebhookWorkScheduler.REVISION to UUID.randomUUID().toString())).build()
            for(worker in listOf(analytics,exports,webhook)) {
                val reader=async(start=CoroutineStart.UNDISPATCHED) {worker.doWork()};readers+=reader
                assertFalse("A real worker passed the clear barrier",reader.isCompleted)
            }
            var activity:Activity?=null
            instrumentation.runOnMainSync {activity=object:Activity() {init {attachBaseContext(context)}}}
            try {graph.recordingCommands.startVisible(activity!!);fail("Recording start passed the clear barrier")} catch(_:JournalClearPending) {}
            for(reader in readers) reader.cancelAndJoin()
            assertTrue(graph.journalClear.state.value.busy);assertSame(operation,graph.journalClear.clear())
            release.complete(Unit);withTimeout(30000) {operation.join();writer.await()}
            assertFalse(graph.journalClear.state.value.blocked);assertNull(graph.automationAccess.approval())
            assertFalse(RecordingRuntime.active.value)
        } finally {
            readers.forEach {it.cancel()};release.complete(Unit);writer.cancelAndJoin()
            operation?.join();graph.automationAccess.revoke();graph.automationResults.clear()
        }
    }}
}
