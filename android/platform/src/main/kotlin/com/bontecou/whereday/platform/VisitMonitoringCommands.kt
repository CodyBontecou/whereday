package com.bontecou.whereday.platform

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.os.*
import androidx.core.content.ContextCompat
import com.bontecou.whereday.data.HistoryRepository
import kotlinx.coroutines.*
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

internal object VisitStartTickets {
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

/** Visible user actions start monitoring. Permission reviews never enqueue a future start. */
class VisitMonitoringCommands(private val context:Context,private val history:HistoryRepository,
    private val canStart:()->Boolean={true}) {
    val preparing get()=VisitStartTickets.pending()
    suspend fun startVisible(activity:Activity,reviewedGeneration:String?) {
        if(!canStart()) throw JournalClearPending()
        val ticket=VisitStartTickets.create();var acknowledged=false
        try {
            val code=receipt {reply->
                if(activity.applicationContext.packageName!=context.packageName || activity.isFinishing || activity.isDestroyed || !activity.hasWindowFocus()) throw RecordingNeedsForeground()
                if(!hasLocation(activity)) throw RecordingNeedsLocation()
                VisitMonitoringService.start(activity,reviewedGeneration,reply,ticket)
            }
            if(code!=VisitMonitoringService.STARTED) throw RecordingCommandFailed()
            acknowledged=true
        } finally {VisitStartTickets.release(ticket,cancel=!acknowledged)}
    }
    suspend fun stop() {
        val code=try {receipt {VisitMonitoringService.stop(context,it)}}
        catch(error:CancellationException) {throw error}
        catch(error:IllegalStateException) {
            if(VisitMonitoringRuntime.state.value!=VisitMonitoringMode.off || preparing) throw error
            history.dao.visitMonitoring()?.generation?.let {history.stopVisitMonitoring(it)}
            return
        }
        if(code!=VisitMonitoringService.STOPPED) throw RecordingCommandFailed()
    }
    suspend fun reconcile(reason:String,expected:com.bontecou.whereday.data.VisitMonitoringRow?=null) {
        if(VisitMonitoringRuntime.state.value==VisitMonitoringMode.off && !preparing) history.interruptVisitMonitoring(reason,expected)
    }
    private suspend fun receipt(send:(ResultReceiver)->Unit):Int=withTimeout(15_000) {
        val response=CompletableDeferred<Int>(currentCoroutineContext()[Job])
        withContext(Dispatchers.Main.immediate) {send(object:ResultReceiver(Handler(Looper.getMainLooper())) {
            override fun onReceiveResult(resultCode:Int,resultData:Bundle?) {response.complete(resultCode)}
        })}
        response.await()
    }
    internal companion object {
        fun hasLocation(context:Context)=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
    }
}
