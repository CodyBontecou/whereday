package com.bontecou.whereday.platform

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.work.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit

/** Only revision UUIDs and opaque plan hashes enter WorkManager's database. Payloads stay private. */
class WebhookWorkScheduler internal constructor(private val work:WorkManager,private val coordinator:WebhookCoordinator,
    private val now:()->Instant={Instant.now()}) {
    companion object {
        const val TAG="whereday-webhook-delivery"
        internal const val REVISION="webhook-revision"
        private const val PLAN="webhook-plan:"
        private val serial=Mutex()
    }
    private val activeQuery=WorkQuery.Builder.fromTags(listOf(TAG)).addStates(listOf(WorkInfo.State.ENQUEUED,WorkInfo.State.RUNNING,WorkInfo.State.BLOCKED)).build()
    private fun planQuery(tag:String)=WorkQuery.Builder.fromTags(listOf(tag)).addStates(listOf(WorkInfo.State.ENQUEUED,WorkInfo.State.RUNNING,WorkInfo.State.BLOCKED,WorkInfo.State.FAILED)).build()
    /** Retained failed infrastructure/access work is resumed by an explicit foreground/user retry. */
    suspend fun reconcile(currentWorker:UUID?=null,retryFailed:Boolean=false):Instant?=withContext(Dispatchers.IO) {serial.withLock {
        val plan=coordinator.nextWork()
        val infos=work.getWorkInfos(activeQuery).get()
        val tag=plan?.let {PLAN+it.key}
        for(info in infos) if(info.id!=currentWorker && !info.state.isFinished && info.state!=WorkInfo.State.RUNNING && (tag==null || tag !in info.tags)) work.cancelWorkById(info.id).result.get()
        if(plan==null) return@withLock null
        val same=infos.filter {tag in it.tags && it.id!=currentWorker}
        if(same.any {!it.state.isFinished}) return@withLock plan.at
        if(!retryFailed && currentWorker==null && work.getWorkInfos(planQuery(checkNotNull(tag))).get().any {it.state==WorkInfo.State.FAILED}) return@withLock null
        // When a clock change makes a running worker early, a distinct successor must survive KEEP.
        val continuation=if(infos.any {it.id==currentWorker && tag in it.tags}) "-${UUID.randomUUID()}" else ""
        val request=OneTimeWorkRequestBuilder<WebhookWorker>()
            .addTag(TAG).addTag(checkNotNull(tag))
            .setInputData(workDataOf(REVISION to plan.revision))
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setInitialDelay(maxOf(0,Duration.between(now(),plan.at).toMillis()),TimeUnit.MILLISECONDS)
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS)
            .keepResultsForAtLeast(7,TimeUnit.DAYS)
            .build()
        work.enqueueUniqueWork("whereday-webhook-${plan.key}$continuation",ExistingWorkPolicy.KEEP,request).result.get()
        plan.at
    }}
    internal fun changes()=work.getWorkInfosFlow(activeQuery).map {Unit}
    internal suspend fun paused():Boolean=withContext(Dispatchers.IO) {
        val plan=coordinator.nextWork() ?: return@withContext false
        val same=work.getWorkInfos(planQuery(PLAN+plan.key)).get()
        same.none {!it.state.isFinished} && same.any {it.state==WorkInfo.State.FAILED}
    }
    suspend fun cancelAll()=withContext(Dispatchers.IO) {serial.withLock {work.cancelAllWorkByTag(TAG).result.get();Unit}}
}

class WebhookWorker internal constructor(context:Context,parameters:WorkerParameters,
    private val injectedCoordinator:WebhookCoordinator?,private val injectedScheduler:WebhookWorkScheduler?):CoroutineWorker(context,parameters) {
    constructor(context:Context,parameters:WorkerParameters):this(context,parameters,null,null)
    override suspend fun doWork():Result {
        val revision=inputData.getString(WebhookWorkScheduler.REVISION) ?: return Result.failure()
        if(runCatching {UUID.fromString(revision).toString()!=revision}.getOrDefault(true)) return Result.failure()
        val graph=if(injectedCoordinator==null) AppGraph.from(applicationContext) else null
        val coordinator=injectedCoordinator ?: requireNotNull(graph).webhooks
        val scheduler=injectedScheduler ?: requireNotNull(graph).webhookWork
        return try {
            graph?.journalClear?.awaitReady()
            coordinator.runNext(revision)
            scheduler.reconcile(id)
            Result.success()
        } catch(e:CancellationException) {throw e}
        catch(_:Exception) {
            // HTTP retry state lives in Room. These bounded retries cover only access/storage failures.
            if(runAttemptCount<2) Result.retry() else Result.failure()
        }
    }
}

/** Boot/time/package events only schedule reconciliation. They never perform HTTP in a receiver. */
class WebhookTimeReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending=goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try {
                WorkManager.getInstance(context).enqueueUniqueWork("whereday-webhook-reconcile",ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<WebhookReconcileWorker>().build()).result.get(7,TimeUnit.SECONDS)
            } catch(_:Exception) { /* Persisted delivery work and app foreground remain recovery paths. */ }
            finally {pending.finish()}
        }
    }
}
class WebhookReconcileWorker(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result=try {val graph=AppGraph.from(applicationContext);graph.journalClear.awaitReady();graph.webhookWork.reconcile();Result.success()}
    catch(e:CancellationException) {throw e}
    catch(_:Exception) {if(runAttemptCount<2) Result.retry() else Result.failure()}
}
