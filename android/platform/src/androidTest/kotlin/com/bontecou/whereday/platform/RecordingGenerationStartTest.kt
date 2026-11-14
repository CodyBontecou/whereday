package com.bontecou.whereday.platform

import android.Manifest
import android.content.ComponentName
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.ResultReceiver
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*

/** Fresh platform.test process only. The wrapper captures dispatch; no location grant or GPS start. */
class RecordingGenerationStartTest {
    private class Capture(context:Context):ContextWrapper(context) {
        val starts=mutableListOf<Intent>()
        var beforeDispatch:(()->Unit)?=null
        override fun checkPermission(permission:String,pid:Int,uid:Int):Int=if(permission in setOf(Manifest.permission.ACCESS_COARSE_LOCATION,Manifest.permission.ACCESS_FINE_LOCATION)) PackageManager.PERMISSION_GRANTED else super.checkPermission(permission,pid,uid)
        override fun startForegroundService(service:Intent):ComponentName? {
            beforeDispatch?.invoke();starts+=Intent(service)
            return service.component
        }
    }
    @Test fun delayedStartsCannotRefreshAnOldGenerationAndAQueuedIntentKeepsItsCapturedGeneration():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.platform.test")
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION))
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
        val graph=AppGraph.from(context);graph.journalClear.awaitReady()
        val initial=graph.journalClear.state.value.generation;assertNull(initial)
        graph.journalClear.clear().join();assertEquals(JournalClearPhase.complete,graph.journalClear.state.value.phase)
        val generation=graph.journalClear.state.value.generation;assertNotNull(generation)
        val capture=Capture(context)
        val oldTicket=RecordingStartTickets.create()
        try {
            try {RecordingService.startForGeneration(capture,initial,ticket=oldTicket);fail("Old approval was refreshed after deletion")}
            catch(_:JournalClearPending) {}
            assertTrue(capture.starts.isEmpty())
        } finally {RecordingStartTickets.release(oldTicket,cancel=true)}
        val reply=CompletableDeferred<Int>()
        val ticket=RecordingStartTickets.create()
        try {
            capture.beforeDispatch={runBlocking {graph.journalClear.clear().join()}}
            RecordingService.startForGeneration(capture,generation,object:ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode:Int,resultData:Bundle?) {reply.complete(resultCode)}
            },ticket)
            assertEquals(JournalClearPhase.complete,graph.journalClear.state.value.phase)
            assertNotEquals(generation,graph.journalClear.state.value.generation)
            val intent=capture.starts.single()
            assertTrue(intent.hasExtra("recording.clear-generation"))
            assertEquals(generation,intent.getStringExtra("recording.clear-generation"))
            // The real service rejects that queued request before foreground/location startup.
            context.startService(intent)
            assertEquals(RecordingService.RESULT_FAILED,withTimeout(15000) {reply.await()})
            assertFalse(RecordingRuntime.active.value);assertEquals(0L,graph.history.dao.openSessionCount())
            capture.beforeDispatch=null;capture.starts.clear()
            RecordingService.startForGeneration(capture,graph.journalClear.state.value.generation,ticket=ticket)
            assertEquals(graph.journalClear.state.value.generation,capture.starts.single().getStringExtra("recording.clear-generation"))
        } finally {RecordingStartTickets.release(ticket,cancel=true)}
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION))
        assertFalse(RecordingRuntime.active.value)
    }
}
