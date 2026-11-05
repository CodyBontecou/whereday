package com.bontecou.whereday.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.TimeUnit

/** One future occurrence (or one pending retry), independent of the worker that is finishing now. */
class ScheduledExportWorkScheduler internal constructor(
    private val work:WorkManager,private val coordinator:ScheduledExportCoordinator,
    private val clock:()->Instant={Instant.now()},private val zone:()->ZoneId={ZoneId.systemDefault()},
) {
    companion object {
        const val TAG="whereday-automatic-export"
        private const val KEY="whereday-export-key:"
        private val lock=Mutex()
    }
    suspend fun reconcile(currentWorker:UUID?=null):Instant?=withContext(Dispatchers.IO) {
        lock.withLock {
            val state=coordinator.snapshot()
            val infos=work.getWorkInfosByTag(TAG).get()
            if(!state.settings.enabled) {
                infos.filter {!it.state.isFinished && it.id!=currentWorker}.forEach {work.cancelWorkById(it.id).result.get()}
                return@withLock null
            }
            // Preserve the backoff/attempt count of an existing retry instead of resetting it on app open.
            if(state.pending!=null && infos.any {!it.state.isFinished && it.id!=currentWorker}) return@withLock null
            val now=clock()
            val next=if(state.pending!=null) now.plusSeconds(30) else state.settings.schedule.next(now,zone())
            val name=state.pending?.let {"whereday-export-retry-${it.id}"} ?: "whereday-export-${next.epochSecond}"
            val tag=KEY+name
            for(info in infos) {
                if(!info.state.isFinished && info.id!=currentWorker && tag !in info.tags && info.state!=WorkInfo.State.RUNNING) work.cancelWorkById(info.id).result.get()
            }
            val request=OneTimeWorkRequestBuilder<ScheduledExportWorker>()
                .addTag(TAG).addTag(tag)
                .setInitialDelay(maxOf(0,Duration.between(now,next).toMillis()),TimeUnit.MILLISECONDS)
                .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
                .build()
            work.enqueueUniqueWork(name,ExistingWorkPolicy.KEEP,request).result.get()
            next
        }
    }
    suspend fun cancelAll()=withContext(Dispatchers.IO) {lock.withLock {work.cancelAllWorkByTag(TAG).result.get();Unit}}
}

class ScheduledExportWorker internal constructor(context:Context,parameters:WorkerParameters,
    private val injectedCoordinator:ScheduledExportCoordinator?,private val injectedScheduler:ScheduledExportWorkScheduler?):CoroutineWorker(context,parameters) {
    constructor(context:Context,parameters:WorkerParameters):this(context,parameters,null,null)
    override suspend fun doWork():Result {
        val graph=if(injectedCoordinator==null) AppGraph.from(applicationContext) else null
        val coordinator=injectedCoordinator ?: requireNotNull(graph).automaticExports
        val scheduler=injectedScheduler ?: requireNotNull(graph).automaticExportWork
        return try {
            graph?.journalClear?.awaitReady()
            val outcome=coordinator.runIfDue()
            if(outcome==AutomaticRunOutcome.failed) Result.retry()
            else {scheduler.reconcile(id);graph?.automaticExportController?.refresh();Result.success()}
        } catch(e:Exception) {
            if(e is CancellationException) throw e
            // Preserve private failure state; persistent retries never turn a failed write into success.
            Result.retry()
        }
    }
}

/** Time/zone changes and boot only enqueue reconciliation; no export or foreground service starts here. */
class ScheduledExportTimeReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending=goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try {
                WorkManager.getInstance(context).enqueueUniqueWork("whereday-export-reconcile",ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<ScheduledExportReconcileWorker>().addTag(ScheduledExportWorkScheduler.TAG).build()).result.get(7,TimeUnit.SECONDS)
            } catch(_:Exception) { /* App resume and existing persisted work remain recovery paths. */ }
            finally {pending.finish()}
        }
    }
}
class ScheduledExportReconcileWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result=try {
        val graph=AppGraph.from(applicationContext);graph.journalClear.awaitReady();graph.automaticExportWork.reconcile(id);Result.success()
    } catch(e:Exception) {if(e is CancellationException) throw e;Result.retry()}
}
