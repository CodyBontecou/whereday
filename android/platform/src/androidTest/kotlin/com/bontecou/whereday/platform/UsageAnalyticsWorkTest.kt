package com.bontecou.whereday.platform

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UsageAnalyticsWorkTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private class Memory:UsageAnalyticsStore {
        var value=UsageConsentState()
        override suspend fun read()=value
        override suspend fun write(value:UsageConsentState) {this.value=value}
    }
    private suspend fun fixture(block:suspend(UsageAnalyticsCore,UsageAnalyticsController,UsageAnalyticsWorkScheduler,WorkManager,List<Data>)->Unit) {
        check(context.packageName=="com.bontecou.whereday.platform.test")
        lateinit var controller:UsageAnalyticsController
        val inputs=java.util.concurrent.CopyOnWriteArrayList<Data>()
        val factory=object:WorkerFactory() {
            override fun createWorker(appContext:Context,workerClassName:String,workerParameters:WorkerParameters):ListenableWorker? =
                if(workerClassName==UsageAnalyticsWorker::class.java.name) {inputs+=workerParameters.inputData;UsageAnalyticsWorker(appContext,workerParameters,controller)} else null
        }
        WorkManagerTestInitHelper.initializeTestWorkManager(context,Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(factory).build())
        val work=WorkManager.getInstance(context)
        val core=UsageAnalyticsCore(Memory(),UsageAnalyticsTransport {UsageSendResult.accepted}) {UsageMetadata("0.1.0","1")}
        val scheduler=UsageAnalyticsWorkScheduler(work,core)
        controller=UsageAnalyticsController(core,scheduler)
        try {core.load();block(core,controller,scheduler,work,inputs)} finally {
            controller.clearAll();controller.close();WorkManagerTestInitHelper.closeWorkDatabase()
        }
    }
    @Test fun consentCreatesOnlyConstrainedEmptyInputJobsAndOptOutCancelsThem():Unit=runBlocking {fixture {core,controller,scheduler,work,inputs->
        scheduler.reconcile()
        assertTrue(work.getWorkInfosByTag(UsageAnalyticsWorkScheduler.TAG).get().isEmpty())
        controller.setEnabled(true)!!.join()
        core.record(UsageEvent(UsageEventName.stepViewed,step=UsageStep.welcome),core.captureConsent())
        scheduler.reconcile();scheduler.reconcile()
        val active=work.getWorkInfosByTag(UsageAnalyticsWorkScheduler.TAG).get().filter {!it.state.isFinished}
        assertEquals(2,active.size)
        active.forEach {assertEquals(NetworkType.CONNECTED,it.constraints.requiredNetworkType)}
        val immediate=work.getWorkInfosForUniqueWork(UsageAnalyticsWorkScheduler.IMMEDIATE).get().single()
        WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(immediate.id)
        withTimeout(5_000) {while(core.state.value.pending!=0) delay(10)}
        assertEquals(1,inputs.size);assertTrue(inputs.single().keyValueMap.isEmpty())
        controller.setEnabled(false)!!.join()
        assertFalse(core.state.value.enabled)
        assertTrue(work.getWorkInfosByTag(UsageAnalyticsWorkScheduler.TAG).get().all {it.state.isFinished})
    }}
    @Test fun workerBoundsEachRunAndDisabledWorkerDoesNoDelivery():Unit=runBlocking {fixture {core,controller,_,_,_->
        controller.setEnabled(true)!!.join()
        repeat(11) {core.record(UsageEvent(UsageEventName.stepViewed,step=UsageStep.features),core.captureConsent())}
        // Build directly: no WorkManager queue or network constraint is bypassed in production.
        val factory=object:WorkerFactory() {
            override fun createWorker(appContext:Context,workerClassName:String,workerParameters:WorkerParameters):ListenableWorker? =
                UsageAnalyticsWorker(appContext,workerParameters,controller)
        }
        fun worker()=TestListenableWorkerBuilder.from(context,UsageAnalyticsWorker::class.java).setWorkerFactory(factory).build()
        assertEquals(ListenableWorker.Result.retry(),worker().doWork())
        assertEquals(1,core.state.value.pending)
        controller.setEnabled(false)!!.join()
        assertEquals(ListenableWorker.Result.success(),worker().doWork())
        assertEquals(0,core.state.value.pending)
    }}
}
