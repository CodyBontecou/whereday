package com.bontecou.whereday.platform

import android.app.Activity
import android.content.Context
import android.os.*
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

class VisitMonitoringCommandsTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    @Before fun isolated() {check(context.packageName=="com.bontecou.whereday.platform.test")}
    private class GuardActivity(context:Context,private val focused:Boolean):Activity() {
        init {attachBaseContext(context)}
        override fun hasWindowFocus()=focused
    }
    @Test fun visibleActivityAndExistingLocationPermissionAreRequiredAndClearBlocksFirst()=runBlocking {
        val db=HistoryDatabase.create(context,null)
        try {
            val history=HistoryRepository(db);val commands=VisitMonitoringCommands(context,history)
            var visible:Activity?=null;var hidden:Activity?=null
            instrumentation.runOnMainSync {visible=GuardActivity(context,true);hidden=GuardActivity(context,false)}
            try {commands.startVisible(hidden!!,null);fail("Hidden start succeeded")} catch(_:RecordingNeedsForeground) {}
            assertFalse(VisitMonitoringCommands.hasLocation(context))
            try {commands.startVisible(visible!!,null);fail("Unpermitted start succeeded")} catch(_:RecordingNeedsLocation) {}
            try {VisitMonitoringCommands(context,history,{false}).startVisible(visible!!,null);fail("Clear barrier bypassed")} catch(_:JournalClearPending) {}
            assertFalse(commands.preparing);assertNull(history.dao.visitMonitoring())
        } finally {db.close()}
    }
    @Test fun persistedRequestCannotMasqueradeAsRuntimeAndReconcilePreservesActiveOrPreparingOwner()=runBlocking {
        val db=HistoryDatabase.create(context,null)
        val history=HistoryRepository(db);val commands=VisitMonitoringCommands(context,history)
        val ticket=VisitStartTickets.create()
        try {
            history.startVisitMonitoring()
            commands.reconcile("synthetic interruption")
            assertTrue(history.dao.visitMonitoring()!!.requested)
            VisitStartTickets.release(ticket,false)
            VisitMonitoringRuntime.update(VisitMonitoringMode.balanced)
            commands.reconcile("synthetic interruption")
            assertTrue(history.dao.visitMonitoring()!!.requested)
            VisitMonitoringRuntime.update(VisitMonitoringMode.off)
            commands.reconcile("synthetic interruption")
            assertFalse(history.dao.visitMonitoring()!!.requested)
            assertEquals("synthetic interruption",history.dao.visitMonitoring()!!.interruption)
        } finally {VisitStartTickets.release(ticket,true);VisitMonitoringRuntime.update(VisitMonitoringMode.off);db.close()}
    }
    @Test fun cancellationTicketsAreIndependentOfRecordingAndCannotBeReattached() {
        val id=VisitStartTickets.create();val work=Job()
        try {
            assertTrue(VisitStartTickets.attach(id,work));assertTrue(VisitStartTickets.pending())
            assertFalse(RecordingStartTickets.pending())
            VisitStartTickets.release(id,true)
            assertTrue(work.isCancelled);assertFalse(VisitStartTickets.valid(id))
            assertFalse(VisitStartTickets.attach(id,Job().apply {cancel()}))
        } finally {VisitStartTickets.release(id,true)}
    }
    @Test fun canceledAndStaleGenerationRequestsCannotStartAService()=runBlocking {
        val graph=AppGraph.from(context);graph.journalClear.awaitReady()
        val initial=graph.journalClear.state.value.generation
        graph.journalClear.clear().join()
        assertFalse(graph.journalClear.state.value.blocked)
        suspend fun rejected(generation:String?,ticket:String) {
            val response=CompletableDeferred<Int>()
            withContext(Dispatchers.Main) {VisitMonitoringService.start(context,generation,object:ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode:Int,resultData:Bundle?) {response.complete(resultCode)}
            },ticket)}
            assertEquals(VisitMonitoringService.FAILED,withTimeout(5000) {response.await()})
        }
        val old=VisitStartTickets.create()
        try {rejected(initial,old)} finally {VisitStartTickets.release(old,true)}
        val canceled=VisitStartTickets.create();VisitStartTickets.release(canceled,true)
        rejected(graph.journalClear.state.value.generation,canceled)
        assertEquals(VisitMonitoringMode.off,VisitMonitoringRuntime.state.value)
        assertNull(graph.history.dao.visitMonitoring())
    }
    @Test fun acknowledgedStopClearsPersistedIntentWithoutStartingLocation()=runBlocking {
        val graph=AppGraph.from(context)
        graph.history.startVisitMonitoring()
        graph.visitMonitoringCommands.stop()
        assertFalse(graph.history.dao.visitMonitoring()!!.requested)
        assertEquals(VisitMonitoringMode.off,VisitMonitoringRuntime.state.value)
        assertFalse(graph.visitMonitoringCommands.preparing)
    }
}
