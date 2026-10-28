package com.bontecou.whereday.platform

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import androidx.core.content.ContextCompat
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*

@RunWith(AndroidJUnit4::class)
class RecordingCommandsTest {
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    @Before fun isolated() {check(context.packageName=="com.bontecou.whereday.platform.test")}
    @Test fun persistedRequestedStateAloneNeverCreatesAnActiveDuration()=runBlocking {
        val db=HistoryDatabase.create(context,null)
        val at=Instant.parse("2026-10-28T12:00:00Z")
        val history=HistoryRepository(db,Clock.fixed(at,ZoneOffset.UTC))
        val commands=RecordingCommands(context,history,Clock.fixed(at.plusSeconds(365),ZoneOffset.UTC))
        try {
            history.start(0);RecordingRuntime.update(false)
            assertEquals(AutomationRecordingStatus(false,0),commands.status())
            RecordingRuntime.update(true)
            assertEquals(AutomationRecordingStatus(true,365),commands.status())
            history.stop()
            assertEquals(AutomationRecordingStatus(false,0),commands.status())
        } finally {RecordingRuntime.update(false);db.close()}
    }
    @Test fun acknowledgedStopActuallyClosesTheIsolatedSessionBeforeReturning():Unit=runBlocking {
        val graph=AppGraph.from(context)
        assertFalse(RecordingRuntime.active.value);assertEquals(0L,graph.history.dao.openSessionCount())
        val session=graph.history.start(0).sessionId!!
        try {
            graph.recordingCommands.stop()
            assertFalse(graph.history.dao.recording()!!.requested)
            assertNotNull(graph.history.dao.session(session)!!.value.endedAt)
            assertEquals(AutomationRecordingStatus(false,0),graph.recordingCommands.status())
        } finally {graph.history.stop();graph.history.deleteSession(session)}
    }
    @Test fun canceledStartTicketCannotCreateAServiceRecordingOrPersistANewSession():Unit=runBlocking {
        val graph=AppGraph.from(context);assertFalse(RecordingRuntime.active.value)
        val ticket=RecordingStartTickets.create();RecordingStartTickets.release(ticket,cancel=true)
        val reply=CompletableDeferred<Int>()
        withContext(Dispatchers.Main) {RecordingService.start(context,object:ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode:Int,resultData:Bundle?) {reply.complete(resultCode)}
        },ticket)}
        assertEquals(RecordingService.RESULT_FAILED,withTimeout(10_000) {reply.await()})
        assertFalse(RecordingRuntime.active.value);assertEquals(0L,graph.history.dao.openSessionCount())
    }
    private class GuardActivity(context:Context,private val focused:Boolean):Activity() {
        init {attachBaseContext(context)}
        override fun hasWindowFocus()=focused
    }
    @Test fun foregroundAndExistingPermissionAreRequiredWithoutOpeningAPermissionDialog():Unit=runBlocking {
        val db=HistoryDatabase.create(context,null)
        try {
            val commands=RecordingCommands(context,HistoryRepository(db))
            var hidden:Activity?=null;var visible:Activity?=null
            instrumentation.runOnMainSync {hidden=GuardActivity(context,false);visible=GuardActivity(context,true)}
            try {commands.startVisible(hidden!!);fail("Hidden start succeeded")} catch(_:RecordingNeedsForeground) { }
            assertEquals(PackageManager.PERMISSION_DENIED,ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION))
            assertEquals(PackageManager.PERMISSION_DENIED,ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION))
            try {commands.startVisible(visible!!);fail("Permission-free start succeeded")} catch(_:RecordingNeedsLocation) { }
            assertFalse(RecordingStartTickets.pending());assertFalse(RecordingRuntime.active.value)
            assertEquals(0L,db.history().openSessionCount())
        } finally {db.close()}
    }
    @Test fun releasingAStartTicketCancelsItsAttachedOperationAndPreventsReuse()=runBlocking {
        val id=RecordingStartTickets.create();val operation=Job()
        assertTrue(RecordingStartTickets.attach(id,operation));assertTrue(RecordingStartTickets.valid(id))
        assertTrue(AppGraph.from(context).recordingCommands.preparing)
        RecordingStartTickets.release(id,cancel=true)
        assertTrue(operation.isCancelled);assertFalse(RecordingStartTickets.valid(id))
        assertFalse(AppGraph.from(context).recordingCommands.preparing)
        assertFalse(RecordingStartTickets.attach(id,Job().apply {cancel()}))
    }
}
