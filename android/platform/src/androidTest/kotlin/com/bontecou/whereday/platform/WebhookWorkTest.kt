package com.bontecou.whereday.platform

import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.*
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.*
import com.bontecou.whereday.platform.WebhookDeliveryTest.Receiver
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.KeyStore
import java.time.*
import java.time.Clock
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

class WebhookWorkTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private class TestClock:Clock() {
        val start=Instant.parse("2026-10-27T12:00:00Z");@Volatile var now=start
        override fun instant()=now
        override fun getZone():ZoneId=ZoneOffset.UTC
        override fun withZone(zone:ZoneId):Clock=this
    }
    private inner class Fixture {
        val clock=TestClock()
        val root=File(context.cacheDir,"webhook-work-${UUID.randomUUID()}")
        val alias="webhook_work_test_${UUID.randomUUID()}"
        val db=HistoryDatabase.create(context,null)
        val history=HistoryRepository(db,clock)
        val queue=WebhookQueueRepository(db,clock)
        @Volatile var failWorker=false
        @Volatile var paid=true
        @Volatile var permission=true
        val coordinator=WebhookCoordinator(db,root,WebhookSettingsStore(context,root,alias),{check(paid)},{permission},clock)
        lateinit var scheduler:WebhookWorkScheduler
        lateinit var work:WorkManager
        val inputs=CopyOnWriteArrayList<Data>()
        val factory=object:WorkerFactory() {
            override fun createWorker(appContext:Context,workerClassName:String,workerParameters:WorkerParameters):ListenableWorker? =
                if(workerClassName==WebhookWorker::class.java.name) {
                    inputs+=workerParameters.inputData
                    if(failWorker) object:CoroutineWorker(appContext,workerParameters) {override suspend fun doWork()=Result.failure()}
                    else WebhookWorker(appContext,workerParameters,coordinator,scheduler)
                } else null
        }
        fun settings(server:Receiver,mode:WebhookMode=WebhookMode.realtime)=WebhookSettings(enabled=true,endpoint=server.url+"?synthetic-private-url-token",auth=WebhookAuth.bearer,allowInsecureHttp=true,mode=mode,batchCount=5,batchMinutes=1)
        suspend fun configure(server:Receiver,mode:WebhookMode=WebhookMode.realtime)=coordinator.configure(settings(server,mode),WebhookCredentials(value="synthetic-private-auth-token"))
        suspend fun record(count:Int=1,offset:Int=0) {
            history.start(0);clock.now=clock.now.plusSeconds(1)
            history.ingest((1..count).map {n->TrackPoint(id="private-point-${n+offset}",coordinate=Coordinate(0.0,0.0),time=clock.start.plusMillis((n+offset).toLong()),accuracy=5.0)})
        }
        fun initialized()=this::scheduler.isInitialized
        fun infos()=work.getWorkInfosByTag(WebhookWorkScheduler.TAG).get()
        fun active()=infos().filter {!it.state.isFinished}
        suspend fun await(id:UUID,state:WorkInfo.State):WorkInfo=withTimeout(15_000) {
            var info=work.getWorkInfoById(id).get()
            while(info?.state!=state) {delay(25);info=work.getWorkInfoById(id).get()}
            info
        }
        fun execute(info:WorkInfo) {
            val driver=WorkManagerTestInitHelper.getTestDriver(context)!!
            driver.setAllConstraintsMet(info.id)
            if(info.initialDelayMillis>0) driver.setInitialDelayMet(info.id)
        }
        fun direct(revision:String,count:Int=0)=TestListenableWorkerBuilder.from(context,WebhookWorker::class.java)
            .setWorkerFactory(factory).setInputData(workDataOf(WebhookWorkScheduler.REVISION to revision)).setRunAttemptCount(count).build()
    }
    private suspend fun fixture(block:suspend(Fixture)->Unit) {
        check(context.packageName=="com.bontecou.whereday.platform.test")
        val f=Fixture()
        try {
            WorkManagerTestInitHelper.initializeTestWorkManager(context,Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(f.factory).build())
            f.work=WorkManager.getInstance(context);f.scheduler=WebhookWorkScheduler(f.work,f.coordinator) {f.clock.now}
            block(f)
        } finally {
            if(f.initialized()) f.scheduler.cancelAll()
            f.coordinator.reset();WorkManagerTestInitHelper.closeWorkDatabase();f.db.close();f.root.deleteRecursively()
            KeyStore.getInstance("AndroidKeyStore").apply {load(null);if(containsAlias(f.alias)) deleteEntry(f.alias)}
        }
    }
    @Test fun defaultOffAndCountBelowThresholdScheduleNothing():Unit=runBlocking {fixture {f->
        assertNull(f.scheduler.reconcile());assertTrue(f.active().isEmpty())
        Receiver().use {server->
            f.configure(server,WebhookMode.batchCount);f.record(4)
            assertNull(f.scheduler.reconcile());assertTrue(server.requests.isEmpty());assertTrue(f.active().isEmpty())
        }
    }}
    @Test fun constrainedUniqueWorkPostsAndAcknowledgesWithoutSecretsInItsMetadata():Unit=runBlocking {fixture {f->
        Receiver().use {server->
            val saved=f.configure(server);f.record();f.scheduler.reconcile()
            val first=f.active().single();assertEquals(NetworkType.CONNECTED,first.constraints.requiredNetworkType)
            f.clock.now=f.clock.now.plusMillis(1);f.scheduler.reconcile();assertEquals(first.id,f.active().single().id)
            assertTrue(server.requests.isEmpty());f.execute(first);f.await(first.id,WorkInfo.State.SUCCEEDED)
            assertEquals(1,server.requests.size);assertEquals(0L,f.queue.snapshot().queuedPoints);assertTrue(f.active().isEmpty())
            assertEquals(mapOf(WebhookWorkScheduler.REVISION to saved.revision),f.inputs.single().keyValueMap)
            val tags=first.tags.joinToString();assertFalse(tags.contains("private"));assertFalse(tags.contains("127.0.0.1"))
            assertEquals(1L,f.db.history().totalPointCount())
        }
    }}
    @Test fun timeBatchRunsWhenItsInitialDelayIsMetAndThenLeavesNoIdleTimer():Unit=runBlocking {fixture {f->
        Receiver().use {server->
            f.configure(server,WebhookMode.batchTime);f.record()
            assertEquals(f.clock.start.plusSeconds(60),f.scheduler.reconcile())
            val first=f.active().single();assertEquals(59_000L,first.initialDelayMillis)
            f.clock.now=f.clock.start.plusSeconds(60);f.execute(first);f.await(first.id,WorkInfo.State.SUCCEEDED)
            assertEquals(1,server.requests.size);assertTrue(f.active().isEmpty())
        }
    }}
    @Test fun httpRetrySchedulesADistinctSuccessorWithTheSameFrozenBody():Unit=runBlocking {fixture {f->
        Receiver(listOf(503,204),retryAfter="5").use {server->
            f.configure(server);f.record();f.scheduler.reconcile();val first=f.active().single()
            f.execute(first);f.await(first.id,WorkInfo.State.SUCCEEDED)
            val next=f.active().single();assertNotEquals(first.id,next.id);assertEquals(5_000L,next.initialDelayMillis)
            val before=f.queue.snapshot().head!!;assertEquals(1,before.attempts)
            f.scheduler.reconcile();assertEquals(next.id,f.active().single().id)
            f.clock.now=f.clock.now.plusSeconds(5);f.execute(next);f.await(next.id,WorkInfo.State.SUCCEEDED)
            assertTrue(f.active().isEmpty());assertEquals(0L,f.queue.snapshot().batches)
            assertEquals(server.requests[0].sha256,server.requests[1].sha256)
            assertEquals(server.requests[0].headers["idempotency-key"],server.requests[1].headers["idempotency-key"])
        }
    }}
    @Test fun staleWorkerCannotSendTheNewConfigurationAndDisableCancelsPendingWork():Unit=runBlocking {fixture {f->
        Receiver().use {server->
            val old=f.configure(server);f.record();f.scheduler.reconcile();val pending=f.active().single()
            f.coordinator.disable();f.scheduler.reconcile();f.await(pending.id,WorkInfo.State.CANCELLED)
            val next=f.configure(server);assertNotEquals(old.revision,next.revision)
            assertEquals(ListenableWorker.Result.success(),f.direct(old.revision).doWork())
            assertTrue(server.requests.isEmpty());assertTrue(f.coordinator.state().configuration.settings.enabled)
            assertTrue(f.active().isEmpty())
        }
    }}
    @Test fun accessFailuresAreBoundedAndAnExplicitRetryCanResumeTheSavedWork():Unit=runBlocking {fixture {f->
        Receiver().use {server->
            val saved=f.configure(server);f.record();f.paid=false
            assertEquals(ListenableWorker.Result.retry(),f.direct(saved.revision).doWork())
            assertEquals(ListenableWorker.Result.failure(),f.direct(saved.revision,2).doWork())
            assertTrue(server.requests.isEmpty());assertEquals(1L,f.queue.snapshot().queuedPoints)
            assertNull(f.queue.snapshot().head)
            f.paid=true;f.scheduler.reconcile(retryFailed=true);val pending=f.active().single()
            f.execute(pending);f.await(pending.id,WorkInfo.State.SUCCEEDED);assertEquals(1,server.requests.size)
        }
    }}
    @Test fun failedWorkStaysPausedAcrossNewFixesUntilExplicitReconciliation():Unit=runBlocking {fixture {f->
        Receiver().use {server->
            f.configure(server);f.record();f.failWorker=true;f.scheduler.reconcile();val first=f.active().single()
            f.execute(first);f.await(first.id,WorkInfo.State.FAILED)
            assertNull(f.scheduler.reconcile());assertTrue(f.active().isEmpty())
            f.record(1,1);assertNull(f.scheduler.reconcile());assertTrue(f.active().isEmpty());assertTrue(server.requests.isEmpty())
            f.failWorker=false;f.scheduler.reconcile(retryFailed=true);val retry=f.active().single()
            assertNotEquals(first.id,retry.id);f.execute(retry);f.await(retry.id,WorkInfo.State.SUCCEEDED)
            assertEquals(1,server.requests.size);assertEquals(0L,f.queue.snapshot().queuedPoints);assertEquals(2L,f.db.history().totalPointCount())
        }
    }}
    @Test fun stoppedHttpFailureSchedulesNothingUntilExplicitRetry():Unit=runBlocking {fixture {f->
        Receiver(listOf(401,204)).use {server->
            f.configure(server);f.record();f.scheduler.reconcile();val first=f.active().single()
            f.execute(first);f.await(first.id,WorkInfo.State.SUCCEEDED)
            assertTrue(f.queue.snapshot().head!!.stopped);assertTrue(f.active().isEmpty())
            assertNull(f.scheduler.reconcile());assertTrue(f.active().isEmpty())
            f.coordinator.retry(f.queue.snapshot().head!!.id);f.scheduler.reconcile(retryFailed=true)
            val next=f.active().single();f.execute(next);f.await(next.id,WorkInfo.State.SUCCEEDED)
            assertEquals(server.requests[0].headers["idempotency-key"],server.requests[1].headers["idempotency-key"])
        }
    }}
    @Test fun earlyWorkerAfterClockRollbackSchedulesSuccessorWithoutCancelingItself():Unit=runBlocking {fixture {f->
        Receiver().use {server->
            f.configure(server,WebhookMode.batchTime);f.record();f.scheduler.reconcile();val first=f.active().single()
            f.clock.now=f.clock.start.minusSeconds(10);f.execute(first);f.await(first.id,WorkInfo.State.SUCCEEDED)
            assertTrue(server.requests.isEmpty());val next=f.active().single();assertNotEquals(first.id,next.id)
            assertEquals(70_000L,next.initialDelayMillis)
            f.clock.now=f.clock.start.plusSeconds(60);f.execute(next);f.await(next.id,WorkInfo.State.SUCCEEDED)
            assertEquals(1,server.requests.size);assertTrue(f.active().isEmpty())
        }
    }}
}
