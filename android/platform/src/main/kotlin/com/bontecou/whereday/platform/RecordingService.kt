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
import com.bontecou.whereday.domain.RecordingInterruption
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
        graph.journalClear // Every service entry initializes receipt recovery; Stop never waits on it.
        updateChannel()
    }
    private fun updateChannel() {getSystemService(NotificationManager::class.java).createNotificationChannel(RecordingNotification.channel(this,CHANNEL))}
    override fun onConfigurationChanged(newConfig:android.content.res.Configuration) {super.onConfigurationChanged(newConfig);updateChannel()}
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
        val admission=graph.journalClear.state.value
        if(admission.blocked || !intent.hasExtra(CLEAR_GENERATION) || intent.getStringExtra(CLEAR_GENERATION)!=admission.generation) {
            acknowledge(RESULT_FAILED)
            if(!RecordingRuntime.active.value) stopSelf(startId)
            return START_NOT_STICKY
        }
        try {startForeground(NOTIFICATION,notification(getString(R.string.notification_recording_preparing),preparing=true))}
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
                startForeground(NOTIFICATION,notification(getString(R.string.notification_recording_preparing),preparing=true))
                ownStart=true
                val epoch=++generation
                val settings=graph.preferences.settings.first()
                val currentAdmission=graph.journalClear.state.value
                if(currentAdmission.blocked || intent.getStringExtra(CLEAR_GENERATION)!=currentAdmission.generation) throw JournalClearPending()
                // Runtime absence requires an explicit new session, even when an abandoned
                // requested row still owns an open session. The active-runtime branch above
                // remains idempotent for repeated Start actions.
                graph.history.markInterrupted(RecordingInterruption.processInterrupted.code)
                val recording=graph.history.start(settings.stopAfterHours,locationBootCount(this@RecordingService))
                startedAt=recording.sessionId?.let {graph.history.dao.session(it)?.value?.startedAt}
                metric=settings.metric
                source.start(settings.distanceFilter,onPoints={points ->scope.launch {commands.withLock {
                    if(epoch!=generation) return@withLock
                    try {graph.history.ingest(points)}
                    catch(e:CancellationException) {throw e}
                    catch(_:Exception) {finish(RecordingInterruption.historyUnavailable,startId)}
                }}},onError={scope.launch {commands.withLock {if(epoch==generation) finish(RecordingInterruption.locationUnavailable,startId)}}})
                if(ticket!=null && !RecordingStartTickets.valid(ticket)) throw CancellationException()
                RecordingRuntime.update(true)
                refreshWidgets()
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
                            commands.withLock {if(epoch==generation) finish(if(!allowed) RecordingInterruption.permissionRemoved else null,startId)};break
                        }
                        val elapsed=startedAt?.let {Duration.between(it,Instant.now()).seconds.coerceAtLeast(0)} ?: 0
                        val remaining=state.deadline?.let {Duration.between(Instant.now(),it)}
                        metric=graph.preferences.settings.first().metric
                        val count=state.sessionId?.let {graph.history.dao.sessionPointCount(it)} ?: 0L
                        val detail=RecordingNotification.detail(this@RecordingService,Duration.ofSeconds(elapsed),count,summary?.distanceMeters,metric,remaining)
                        // The open, named stay heads the notification, as on the Notification board.
                        val stay=try {
                            val now=Instant.now()
                            graph.history.dao.rangeVisits(now.minus(Duration.ofDays(2)),now.plusSeconds(1)).map {it.value}
                                .filter {it.departedAt==null && !it.name.isNullOrBlank()}.maxByOrNull {it.arrivedAt}
                                ?.let {RecordingNotification.Stay(it.name!!,it.arrivedAt)}
                        } catch(e:CancellationException) {throw e} catch(_:Exception) {null}
                        if(ContextCompat.checkSelfPermission(this@RecordingService,Manifest.permission.POST_NOTIFICATIONS)==PackageManager.PERMISSION_GRANTED || android.os.Build.VERSION.SDK_INT<33)
                            getSystemService(NotificationManager::class.java).notify(NOTIFICATION,notification(detail,stay=stay))
                        delay(5_000)
                    }
                }
                acknowledge(RESULT_STARTED)
            } catch(e:CancellationException) {
                try {if(ownStart) withContext(NonCancellable) {finish(RecordingInterruption.startInterrupted,startId)}}
                finally {acknowledge(RESULT_FAILED)}
                throw e
            } catch(_:Exception) {
                ActivityLog.process.record(AppEvent.recordingStartFailed)
                try {if(ownStart) finish(RecordingInterruption.startFailed,startId)}
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
    private suspend fun finish(reason:RecordingInterruption?,startId:Int) {
        val wasActive=RecordingRuntime.active.value
        generation++;source.stop()
        graph.webhookController.permissionsChanged()
        summaryJob?.cancel();summaryJob=null;summary=null
        val previousTicker=ticker;ticker=null
        try {
            graph.history.stop(reason?.code)
            if(wasActive) ActivityLog.process.record(if(reason==null) AppEvent.recordingStopped else AppEvent.recordingInterrupted)
        } finally {
            RecordingRuntime.update(false);startedAt=null
            refreshWidgets()
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            previousTicker?.cancel()
        }
    }
    /** Home-screen widgets live in the app module; a package-scoped broadcast asks them to re-render. */
    private fun refreshWidgets() {runCatching {sendBroadcast(Intent(WIDGET_REFRESH).setPackage(packageName))}}
    private fun notification(detail:String,preparing:Boolean=false,stay:RecordingNotification.Stay?=null):Notification=RecordingNotification.build(this,CHANNEL,detail,preparing,startedAt,stay)
    override fun onDestroy() {if(RecordingRuntime.active.value) ActivityLog.process.record(AppEvent.recordingInterrupted);generation++;source.stop();scope.cancel();RecordingRuntime.update(false);super.onDestroy()}
    override fun onBind(intent:Intent?):IBinder?=null
    companion object {
        const val STOP="com.bontecou.whereday.STOP"
        const val WIDGET_REFRESH="com.bontecou.whereday.widgets.REFRESH"
        private const val START="com.bontecou.whereday.START"
        private const val REPLY="recording.reply"
        private const val TICKET="recording.ticket"
        private const val CLEAR_GENERATION="recording.clear-generation"
        private const val OWNED_TICKET="recording.owned-ticket"
        internal const val RESULT_STARTED=1
        internal const val RESULT_STOPPED=2
        internal const val RESULT_FAILED=3
        private const val CHANNEL="recording"
        private const val NOTIFICATION=40
        private data class ExpectedGeneration(val value:String?)
        fun start(context:Context,reply:ResultReceiver?=null,ticket:String?=null)=startChecked(context,reply,ticket,null)
        /** Delayed user actions must retain their reviewed generation, including the initial null. */
        fun startForGeneration(context:Context,expectedGeneration:String?,reply:ResultReceiver?=null,ticket:String?=null)=startChecked(context,reply,ticket,ExpectedGeneration(expectedGeneration))
        private fun startChecked(context:Context,reply:ResultReceiver?,ticket:String?,expected:ExpectedGeneration?) {
            if(ticket!=null && !RecordingStartTickets.valid(ticket)) {reply?.send(RESULT_FAILED,null);return}
            val allowed=ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
                ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
            if(!allowed) {reply?.send(RESULT_FAILED,null);if(reply==null) throw RecordingNeedsLocation();return}
            val maintenance=AppGraph.from(context).journalClear.state.value
            if(maintenance.blocked || (expected!=null && expected.value!=maintenance.generation)) {reply?.send(RESULT_FAILED,null);if(reply==null) throw JournalClearPending();return}
            val id=ticket ?: RecordingStartTickets.create()
            try {ContextCompat.startForegroundService(context,Intent(context,RecordingService::class.java).setAction(START).apply {
                putExtra(REPLY,reply);putExtra(TICKET,id);putExtra(OWNED_TICKET,ticket==null);putExtra(CLEAR_GENERATION,maintenance.generation)
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
            try {
                val graph=AppGraph.from(context);graph.journalClear.awaitReady()
                graph.reconcileLocationServicesSafely()
            }
            catch(error:CancellationException) {throw error}
            catch(_:JournalClearPending) { /* The journal remains fenced until its deletion recovers. */ }
            // An uncaught failure here would crash the process on every boot.
            catch(_:Exception) {}
            finally {pending.finish()}
        }
    }
}
