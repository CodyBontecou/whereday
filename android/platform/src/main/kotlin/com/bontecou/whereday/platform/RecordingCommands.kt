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
import com.bontecou.whereday.data.HistoryRepository
import com.bontecou.whereday.data.readSnapshot
import kotlinx.coroutines.*
import java.time.Clock
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class RecordingNeedsForeground:IllegalStateException("Open Whereday to start recording.")
class RecordingNeedsLocation:SecurityException("Allow location in Whereday before starting recording.")
class RecordingCommandFailed:IllegalStateException("The recording command could not be completed. Check Whereday's current recording status.")

/** Process-local cancellation tickets for acknowledged starts. A canceled queued start cannot run later. */
internal object RecordingStartTickets {
    private class Ticket {var valid=true;var job:Job?=null}
    private val tickets=ConcurrentHashMap<String,Ticket>()
    fun create():String=UUID.randomUUID().toString().also {tickets[it]=Ticket()}
    fun attach(id:String,job:Job):Boolean {
        val ticket=tickets[id] ?: return false
        return synchronized(ticket) {if(!ticket.valid) false else {ticket.job=job;true}}
    }
    fun valid(id:String)=tickets[id]?.let {synchronized(it) {it.valid}} ?: false
    fun release(id:String,cancel:Boolean) {tickets.remove(id)?.let {ticket ->synchronized(ticket) {
        ticket.valid=false;if(cancel) ticket.job?.cancel()
    }}}
    fun pending()=tickets.isNotEmpty()
}

/** The service owns recording; this facade waits for an acknowledgment, never a stored toggle alone. */
/** [access] throws [com.bontecou.whereday.billing.VerifiedAccessRequired] when tracking needs a subscription or Lifetime first. */
class RecordingCommands(private val context:Context,private val history:HistoryRepository,private val clock:Clock=Clock.systemUTC(),private val canStart:()->Boolean={true},
    private val access:suspend ()->Unit={}) {
    val preparing:Boolean get()=RecordingStartTickets.pending()
    suspend fun status():AutomationRecordingStatus=history.database.readSnapshot {dao ->
        val state=dao.recording()
        val session=state?.sessionId?.let {dao.session(it)?.value}
        val active=RecordingRuntime.active.value && state?.requested==true && session!=null && session.endedAt==null
        AutomationRecordingStatus(active,if(active) Duration.between(session!!.startedAt,clock.instant()).seconds.coerceAtLeast(0) else 0)
    }
    fun bind(activity:Activity):AutomationRecording=object:AutomationRecording {
        override suspend fun status()=this@RecordingCommands.status()
        override suspend fun start()=startVisible(activity)
        override suspend fun stop()=this@RecordingCommands.stop()
    }
    suspend fun startVisible(activity:Activity) {
        if(!canStart()) throw JournalClearPending()
        access()
        val ticket=RecordingStartTickets.create();var acknowledged=false
        try {
            val code=receipt {reply ->
                if(activity.applicationContext.packageName!=context.packageName || activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) throw RecordingNeedsForeground()
                val hasLocation=ContextCompat.checkSelfPermission(activity,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
                    ContextCompat.checkSelfPermission(activity,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
                if(!hasLocation) throw RecordingNeedsLocation()
                RecordingService.start(activity,reply,ticket)
            }
            if(code!=RecordingService.RESULT_STARTED) throw RecordingCommandFailed()
            acknowledged=true
        } finally {RecordingStartTickets.release(ticket,cancel=!acknowledged)}
    }
    suspend fun stop() {
        val code=try {receipt {RecordingService.stop(context,it)}}
        catch(error:CancellationException) {throw error}
        catch(error:IllegalStateException) {
            // An idle background process may not start even a stop-only service. There is no
            // runtime service or queued start to stop in this narrowly checked fallback.
            if(RecordingRuntime.active.value || RecordingStartTickets.pending()) throw error
            history.stop();return
        }
        if(code!=RecordingService.RESULT_STOPPED) throw RecordingCommandFailed()
    }
    private suspend fun receipt(send:(ResultReceiver)->Unit):Int=withTimeout(15_000) {
        val response=CompletableDeferred<Int>(currentCoroutineContext()[Job])
        withContext(Dispatchers.Main.immediate) {
            send(object:ResultReceiver(Handler(Looper.getMainLooper())) {
                override fun onReceiveResult(resultCode:Int,resultData:Bundle?) {response.complete(resultCode)}
            })
        }
        response.await()
    }
}
