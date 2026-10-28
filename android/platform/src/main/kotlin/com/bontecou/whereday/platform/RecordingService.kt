package com.bontecou.whereday.platform

import android.Manifest
import android.app.*
import android.content.*
import android.content.pm.PackageManager
import android.os.IBinder
import android.os.ResultReceiver
import androidx.core.content.ContextCompat
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.conflate
import com.bontecou.whereday.domain.PointSummary
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.time.Duration
import java.util.concurrent.ConcurrentHashMap

class RecordingService : Service() {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.Main.immediate)
    private val commands=Mutex()
    private val starts=ConcurrentHashMap.newKeySet<Job>()
    private lateinit var source:LocationSource
    private val graph by lazy {AppGraph.from(this)}
    private var generation=0L
    private var ticker:Job?=null
    private var summaryJob:Job?=null
    private var summary:PointSummary?=null
    private var metric=true
    private var startedAt:Instant?=null
    override fun onCreate() {
        super.onCreate();source=FusedLocationSource(this)
        getSystemService(NotificationManager::class.java).createNotificationChannel(NotificationChannel(CHANNEL,"Location recording",NotificationManager.IMPORTANCE_LOW))
    }
    override fun onStartCommand(intent:Intent?,flags:Int,startId:Int):Int {
        @Suppress("DEPRECATION")
        val reply=intent?.getParcelableExtra<ResultReceiver>(REPLY)
        val ticket=intent?.getStringExtra(TICKET)
        val ownedTicket=intent?.getBooleanExtra(OWNED_TICKET,false)==true
        fun acknowledge(code:Int) {
            if(ownedTicket && ticket!=null) RecordingStartTickets.release(ticket,cancel=false)
            runCatching {reply?.send(code,null)}
        }
        if(intent?.action==STOP) {
            starts.toList().forEach {it.cancel()}
            scope.launch {commands.withLock {
                try {finish(null,startId);acknowledge(RESULT_STOPPED)}
                catch(e:CancellationException) {throw e}
                catch(_:Exception) {acknowledge(RESULT_FAILED)}
            }}
            return START_NOT_STICKY
        }
        if(intent?.action!=START) {
            acknowledge(RESULT_FAILED)
            if(!RecordingRuntime.active.value) stopSelf(startId)
            return START_NOT_STICKY
        }
        try {startForeground(NOTIFICATION,notification("Preparing location recording"))}
        catch(_:RuntimeException) {
            ActivityLog.process.record(AppEvent.recordingStartFailed)
            // There is no legal foreground location service after a permission/start rejection.
            // A matching-startId stop can leave a newer queued start's FGS deadline outstanding.
            starts.toList().forEach {it.cancel()}
            source.stop();RecordingRuntime.update(false)
            acknowledge(RESULT_FAILED)
            stopSelf();stopService(Intent(this,RecordingService::class.java))
            return START_NOT_STICKY
        }
        if(ticket!=null && !RecordingStartTickets.valid(ticket)) {
            acknowledge(RESULT_FAILED)
            if(!RecordingRuntime.active.value) {stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId)}
            return START_NOT_STICKY
        }
        val job=scope.launch(start=CoroutineStart.LAZY) {commands.withLock {
            var ownStart=false
            try {
                if(ticket!=null && !RecordingStartTickets.valid(ticket)) throw CancellationException()
                val existing=graph.history.dao.recording()
                if(RecordingRuntime.active.value && existing?.requested==true && existing.sessionId?.let {id ->graph.history.dao.session(id)?.value?.let {it.endedAt==null}}==true) {
                    acknowledge(RESULT_STARTED);return@withLock
                }
                // A preceding stop may have removed the first foreground notification while queued.
                startForeground(NOTIFICATION,notification("Preparing location recording"))
                ownStart=true
                val epoch=++generation
                val settings=graph.preferences.settings.first()
                val recording=graph.history.start(settings.stopAfterHours)
                startedAt=recording.sessionId?.let {graph.history.dao.session(it)?.value?.startedAt}
                metric=settings.metric
                source.start(settings.distanceFilter,onPoints={points ->scope.launch {commands.withLock {
                    if(epoch!=generation) return@withLock
                    try {graph.history.ingest(points)}
                    catch(e:CancellationException) {throw e}
                    catch(_:Exception) {finish("Recording stopped because history could not be saved.",startId)}
                }}},onError={scope.launch {commands.withLock {if(epoch==generation) finish("Location unavailable. Check location permission and try again.",startId)}}})
                if(ticket!=null && !RecordingStartTickets.valid(ticket)) throw CancellationException()
                RecordingRuntime.update(true)
                ActivityLog.process.record(AppEvent.recordingStarted)
                summaryJob?.cancel();summary=null
                val sessionId=requireNotNull(recording.sessionId)
                summaryJob=scope.launch {
                    graph.database.invalidationTracker.createFlow("points").conflate().collect {
                        try {val value=graph.history.sessionSummary(sessionId);if(epoch==generation) summary=value}
                        catch(e:CancellationException) {throw e}
                        catch(_:Exception) {if(epoch==generation) summary=null}
                        delay(5_000)
                    }
                }
                ticker?.cancel();ticker=scope.launch {
                    while(isActive && epoch==generation) {
                        val state=graph.history.dao.recording()
                        val allowed=ContextCompat.checkSelfPermission(this@RecordingService,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
                            ContextCompat.checkSelfPermission(this@RecordingService,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
                        if(!allowed || state?.requested!=true || state.deadline?.let {Instant.now()>=it}==true) {
                            commands.withLock {if(epoch==generation) finish(if(!allowed) "Location permission was removed." else null,startId)};break
                        }
                        val elapsed=startedAt?.let {Duration.between(it,Instant.now()).seconds.coerceAtLeast(0)} ?: 0
                        val remaining=state.deadline?.let {Duration.between(Instant.now(),it)}
                        metric=graph.preferences.settings.first().metric
                        val count=state.sessionId?.let {graph.history.dao.sessionPointCount(it)} ?: 0L
                        val detail=RecordingNotification.detail(Duration.ofSeconds(elapsed),count,summary?.distanceMeters,metric,remaining)
                        if(ContextCompat.checkSelfPermission(this@RecordingService,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT<33)
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification(detail))
                        delay(5_000)
                    }
                }
                acknowledge(RESULT_STARTED)
            } catch(e:CancellationException) {
                try {if(ownStart) withContext(NonCancellable) {finish("Recording start was interrupted.",startId)}}
                finally {acknowledge(RESULT_FAILED)}
                throw e
            } catch(_:Exception) {
                ActivityLog.process.record(AppEvent.recordingStartFailed)
                try {if(ownStart) finish("Recording could not start. Please try again.",startId)}
                finally {acknowledge(RESULT_FAILED)}
            }
        }}
        if(ticket!=null && !RecordingStartTickets.attach(ticket,job)) {job.cancel();acknowledge(RESULT_FAILED);if(!RecordingRuntime.active.value) stopSelf(startId);return START_NOT_STICKY}
        starts+=job
        job.invokeOnCompletion {
            starts.remove(job)
            if(job.isCancelled) acknowledge(RESULT_FAILED)
            scope.launch {commands.withLock {
                if(starts.isEmpty() && !RecordingRuntime.active.value) {
                    stopForeground(STOP_FOREGROUND_REMOVE);stopSelf(startId)
                }
            }}
        }
        job.start()
        return START_NOT_STICKY
    }
    private suspend fun finish(reason:String?,startId:Int) {
        val wasActive=RecordingRuntime.active.value
        generation++;source.stop()
        graph.webhookController.permissionsChanged()
        summaryJob?.cancel();summaryJob=null;summary=null
        val previousTicker=ticker;ticker=null
        try {
            graph.history.stop(reason)
            if(wasActive) ActivityLog.process.record(if(reason==null) AppEvent.recordingStopped else AppEvent.recordingInterrupted)
        } finally {
            RecordingRuntime.update(false);startedAt=null
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            previousTicker?.cancel()
        }
    }
    private fun notification(detail:String):Notification=RecordingNotification.build(this,CHANNEL,detail)
    override fun onDestroy() {if(RecordingRuntime.active.value) ActivityLog.process.record(AppEvent.recordingInterrupted);generation++;source.stop();scope.cancel();RecordingRuntime.update(false);super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
    companion object {
        const val STOP="com.bontecou.whereday.STOP"
        private const val START="com.bontecou.whereday.START"
        private const val REPLY="recording.reply"
        private const val TICKET="recording.ticket"
        private const val OWNED_TICKET="recording.owned-ticket"
        internal const val RESULT_STARTED=1
        internal const val RESULT_STOPPED=2
        internal const val RESULT_FAILED=3
        private const val CHANNEL="recording"
        private const val NOTIFICATION=40
        fun start(context:Context,reply:ResultReceiver?=null,ticket:String?=null) {
            if(ticket!=null && !RecordingStartTickets.valid(ticket)) {reply?.send(RESULT_FAILED,null);return}
            val allowed=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
            if(!allowed) {reply?.send(RESULT_FAILED,null);if(reply==null) throw RecordingNeedsLocation();return}
            val id=ticket ?: RecordingStartTickets.create()
            try {ContextCompat.startForegroundService(context,Intent(context,RecordingService::class.java).setAction(START).apply {
                putExtra(REPLY,reply);putExtra(TICKET,id);putExtra(OWNED_TICKET,ticket==null)
            })} catch(error:Exception) {if(ticket==null) RecordingStartTickets.release(id,cancel=true);throw error}
        }
        fun stop(context:Context,reply:ResultReceiver?=null) {context.startService(Intent(context,RecordingService::class.java).setAction(STOP).putExtra(REPLY,reply))}
    }
}

class RecordingRecoveryReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action!=Intent.ACTION_BOOT_COMPLETED) return
        val pending=goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {AppGraph.from(context).history.markInterrupted("Your phone restarted. Open Whereday to resume recording.")}
            finally {pending.finish()}
        }
    }
}
