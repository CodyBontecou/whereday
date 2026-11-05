package com.bontecou.whereday.platform

import android.app.*
import android.content.*
import android.location.LocationManager
import android.os.IBinder
import android.os.ResultReceiver
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.core.location.LocationManagerCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.ConcurrentHashMap

enum class VisitMonitoringMode { off,preparing,balanced,route }
object VisitMonitoringRuntime {
    private val mutable=MutableStateFlow(VisitMonitoringMode.off)
    val state=mutable.asStateFlow()
    internal fun update(mode:VisitMonitoringMode) {mutable.value=mode}
}

/** Remains a user-started location FGS during route handoff, so balanced updates can resume
 * without an impermissible background service restart. It never requests background location. */
class VisitMonitoringService:Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val commands=Mutex()
    private val starts=ConcurrentHashMap.newKeySet<Job>()
    private val graph by lazy {AppGraph.from(this)}
    private lateinit var source:LocationSource
    private var epoch=0L
    private var lease:String?=null
    private var watcher:Job?=null
    private var ticker:Job?=null
    override fun onCreate() {
        super.onCreate();source=FusedLocationSource(this,LocationSampling.visitsOnly)
        graph.journalClear
        getSystemService(NotificationManager::class.java).createNotificationChannel(
            NotificationChannel(CHANNEL,getString(R.string.visits_notification_title),NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        @Suppress("DEPRECATION") val reply=intent?.getParcelableExtra<ResultReceiver>(REPLY)
        fun acknowledge(code:Int) {runCatching {reply?.send(code,null)}}
        if(intent?.action==STOP) {
            starts.toList().forEach {it.cancel()}
            watcher?.cancel() // Also cancel a pending provider registration during route handoff.
            scope.launch {commands.withLock {
                try {finish(null,startId);acknowledge(STOPPED)}
                catch(e:CancellationException) {throw e}
                catch(_:Exception) {acknowledge(FAILED)}
            }}
            return START_NOT_STICKY
        }
        val ticket=intent?.getStringExtra(TICKET)
        val admission=graph.journalClear.state.value
        if(intent?.action!=START || ticket==null || !VisitStartTickets.valid(ticket) || admission.blocked ||
            !intent.hasExtra(GENERATION) || intent.getStringExtra(GENERATION)!=admission.generation) {
            acknowledge(FAILED)
            if(lease==null) stopSelf(startId)
            return START_NOT_STICKY
        }
        try {startForeground(NOTIFICATION,notification(VisitMonitoringMode.preparing))}
        catch(_:RuntimeException) {
            starts.toList().forEach {it.cancel()};source.stop();VisitMonitoringRuntime.update(VisitMonitoringMode.off)
            acknowledge(FAILED);stopSelf();return START_NOT_STICKY
        }
        val job=scope.launch(start=CoroutineStart.LAZY) {commands.withLock {
            var ownsStart=false
            try {
                if(!VisitStartTickets.valid(ticket)) throw CancellationException()
                val current=graph.journalClear.state.value
                if(current.blocked || intent.getStringExtra(GENERATION)!=current.generation) throw JournalClearPending()
                if(lease!=null && graph.history.dao.visitMonitoring()?.generation==lease) {acknowledge(STARTED);return@withLock}
                ownsStart=true
                startForeground(NOTIFICATION,notification(VisitMonitoringMode.preparing))
                VisitMonitoringRuntime.update(VisitMonitoringMode.preparing)
                // Old requested intent after process death is interrupted, never silently resumed.
                graph.history.interruptVisitMonitoring("visits.interrupted")
                lease=requireNotNull(graph.history.startVisitMonitoring(locationBootCount(this@VisitMonitoringService)).generation)
                val owner=++epoch
                synchronizeSource(owner,startId)
                if(!VisitStartTickets.valid(ticket)) throw CancellationException()
                watcher?.cancel();watcher=scope.launch {
                    RecordingRuntime.active.collect {commands.withLock {
                        if(owner==epoch) try {synchronizeSource(owner,startId)}
                        catch(e:CancellationException) {throw e}
                        catch(_:Exception) {finish("visits.location",startId)}
                    }}
                }
                ticker?.cancel();ticker=scope.launch {
                    while(isActive && owner==epoch) {
                        delay(5_000)
                        commands.withLock {
                            if(owner!=epoch) return@withLock
                            try {
                                if(!RecordingRuntime.active.value && !graph.recordingCommands.preparing)
                                    graph.history.markInterrupted(com.bontecou.whereday.domain.RecordingInterruption.processInterrupted.code)
                                val state=graph.history.dao.visitMonitoring()
                                if(state?.requested!=true || state.generation!=lease || graph.journalClear.state.value.blocked ||
                                    !VisitMonitoringCommands.hasLocation(this@VisitMonitoringService) ||
                                    !LocationManagerCompat.isLocationEnabled(getSystemService(LocationManager::class.java))) finish("visits.location",startId)
                            } catch(e:CancellationException) {throw e}
                            catch(_:Exception) {finish("visits.storage",startId)}
                        }
                    }
                }
                acknowledge(STARTED)
            } catch(e:CancellationException) {
                try {if(ownsStart) withContext(NonCancellable) {finish("visits.interrupted",startId)}} finally {acknowledge(FAILED)}
                throw e
            } catch(_:Exception) {
                try {if(ownsStart) finish("visits.start",startId)} finally {acknowledge(FAILED)}
            }
        }}
        if(!VisitStartTickets.attach(ticket,job)) {
            job.cancel();acknowledge(FAILED)
            if(lease==null) {stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId)}
            return START_NOT_STICKY
        }
        starts+=job
        job.invokeOnCompletion {
            starts.remove(job)
            if(job.isCancelled) acknowledge(FAILED)
            scope.launch {commands.withLock {if(starts.isEmpty() && lease==null) {stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId)}}}
        }
        job.start()
        return START_NOT_STICKY
    }
    private suspend fun synchronizeSource(owner:Long,startId:Int) {
        check(!graph.journalClear.state.value.blocked)
        if(!RecordingRuntime.active.value && !graph.recordingCommands.preparing)
            graph.history.markInterrupted(com.bontecou.whereday.domain.RecordingInterruption.processInterrupted.code)
        val mode=if(RecordingRuntime.active.value) VisitMonitoringMode.route else VisitMonitoringMode.balanced
        if(VisitMonitoringRuntime.state.value==mode) return
        source.stop()
        val token=requireNotNull(lease)
        if(mode==VisitMonitoringMode.balanced) {
            VisitMonitoringRuntime.update(VisitMonitoringMode.preparing)
            publish(VisitMonitoringMode.preparing)
            source.start(0,{points->scope.launch {commands.withLock {
            if(owner==epoch && lease==token && !graph.journalClear.state.value.blocked) try {graph.history.ingestVisits(token,points)}
            catch(e:CancellationException) {throw e}
            catch(_:Exception) {finish("visits.storage",startId)}
            }}},{scope.launch {commands.withLock {if(owner==epoch) finish("visits.location",startId)}}})
        }
        VisitMonitoringRuntime.update(mode)
        publish(mode)
    }
    private fun publish(mode:VisitMonitoringMode) {
        if(android.os.Build.VERSION.SDK_INT<33 || ContextCompat.checkSelfPermission(this,android.Manifest.permission.POST_NOTIFICATIONS)==android.content.pm.PackageManager.PERMISSION_GRANTED)
            getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification(mode))
    }
    private suspend fun finish(reason:String?,startId:Int) {
        epoch++;source.stop()
        val token=lease;lease=null
        val previousWatcher=watcher;watcher=null
        val previousTicker=ticker;ticker=null
        try {
            if(token!=null) graph.history.stopVisitMonitoring(token,reason)
            else graph.history.dao.visitMonitoring()?.generation?.let {graph.history.stopVisitMonitoring(it,reason)}
        } finally {
            VisitMonitoringRuntime.update(VisitMonitoringMode.off)
            stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId)
            previousWatcher?.cancel();previousTicker?.cancel()
        }
    }
    override fun onDestroy() {epoch++;source.stop();scope.cancel();VisitMonitoringRuntime.update(VisitMonitoringMode.off);super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
    private fun notification(mode:VisitMonitoringMode)=VisitMonitoringNotification.build(this,mode)
    internal companion object {
        const val STOP="com.bontecou.whereday.VISITS_STOP"
        private const val START="com.bontecou.whereday.VISITS_START"
        private const val REPLY="visits.reply"
        private const val TICKET="visits.ticket"
        private const val GENERATION="visits.journal-generation"
        const val STARTED=1
        const val STOPPED=2
        const val FAILED=3
        const val CHANNEL="visits-only"
        const val NOTIFICATION=41
        fun start(context:Context,generation:String?,reply:ResultReceiver,ticket:String) {
            val current=AppGraph.from(context).journalClear.state.value
            if(current.blocked || current.generation!=generation || !VisitStartTickets.valid(ticket)) {reply.send(FAILED,null);return}
            ContextCompat.startForegroundService(context,Intent(context,VisitMonitoringService::class.java).setAction(START)
                .putExtra(REPLY,reply).putExtra(TICKET,ticket).putExtra(GENERATION,generation))
        }
        fun stop(context:Context,reply:ResultReceiver?=null) {
            context.startService(Intent(context,VisitMonitoringService::class.java).setAction(STOP).putExtra(REPLY,reply))
        }
    }
}

internal object VisitMonitoringNotification {
    fun build(context:Context,mode:VisitMonitoringMode):Notification {
        val stop=PendingIntent.getService(context,41,Intent(context,VisitMonitoringService::class.java).setAction(VisitMonitoringService.STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val open=context.packageManager.getLaunchIntentForPackage(context.packageName)?.let {
            PendingIntent.getActivity(context,41,it,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        }
        return NotificationCompat.Builder(context,VisitMonitoringService.CHANNEL).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentTitle(context.getString(R.string.visits_notification_title))
            .setContentText(context.getString(when(mode) {
                VisitMonitoringMode.preparing->R.string.visits_notification_preparing
                VisitMonitoringMode.route->R.string.visits_notification_route
                else->R.string.visits_notification_active
            })).setContentIntent(open).setOngoing(true).setOnlyAlertOnce(true)
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause,context.getString(R.string.visits_notification_stop),stop).build()
    }
}
