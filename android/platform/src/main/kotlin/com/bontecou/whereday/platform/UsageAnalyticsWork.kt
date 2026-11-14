package com.bontecou.whereday.platform

import android.content.Context
import androidx.work.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.TimeUnit

/** Fixed job names only; identifiers, event data and receipts remain in the private consent file. */
class UsageAnalyticsWorkScheduler(private val work:WorkManager,private val core:UsageAnalyticsCore):UsageAnalyticsJobs {
    private val serial=Mutex()
    companion object {const val TAG="whereday-usage-analytics";const val IMMEDIATE="whereday-usage-send";const val PERIODIC="whereday-usage-recovery"}
    override suspend fun reconcile()=withContext(Dispatchers.IO) {serial.withLock {
        // Re-read authority inside the scheduler lock. A stale event callback cannot re-enable work.
        val state=core.state.value
        if(!state.loaded || !state.enabled || !state.available || state.error==UsageAnalyticsError.storage) {
            work.cancelAllWorkByTag(TAG).result.get(10,TimeUnit.SECONDS)
        } else {
            val constraints=Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            // Periodic recovery covers a new event racing a finishing unique one-time worker.
            work.enqueueUniquePeriodicWork(PERIODIC,ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UsageAnalyticsWorker>(15,TimeUnit.MINUTES).addTag(TAG).setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()).result.get(10,TimeUnit.SECONDS)
            if(state.pending>0) work.enqueueUniqueWork(IMMEDIATE,ExistingWorkPolicy.KEEP,
                OneTimeWorkRequestBuilder<UsageAnalyticsWorker>().addTag(TAG).setConstraints(constraints)
                    .setBackoffCriteria(BackoffPolicy.EXPONENTIAL,30,TimeUnit.SECONDS).build()).result.get(10,TimeUnit.SECONDS)
        }
        Unit
    }}
    override suspend fun cancelAll()=withContext(Dispatchers.IO) {serial.withLock {work.cancelAllWorkByTag(TAG).result.get(10,TimeUnit.SECONDS);Unit}}
}

class UsageAnalyticsWorker internal constructor(context:Context,parameters:WorkerParameters,private val injected:UsageAnalyticsController?):CoroutineWorker(context,parameters) {
    constructor(context:Context,parameters:WorkerParameters):this(context,parameters,null)
    override suspend fun doWork():Result=try {
        val graph=if(injected==null) AppGraph.from(applicationContext) else null
        graph?.journalClear?.awaitReady()
        when((injected ?: requireNotNull(graph).usageAnalytics).drain()) {
            UsageDrainResult.idle,UsageDrainResult.disabled->Result.success()
            UsageDrainResult.retry->Result.retry()
            UsageDrainResult.rejected->Result.failure()
        }
    } catch(error:CancellationException) {throw error}
    catch(_:Exception) {if(runAttemptCount<3) Result.retry() else Result.failure()}
}
