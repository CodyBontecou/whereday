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
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class WebhookControllerTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private inner class Fixture {
        val root=File(context.cacheDir,"webhook-controller-${UUID.randomUUID()}")
        val alias="webhook_controller_test_${UUID.randomUUID()}"
        val db=HistoryDatabase.create(context,null)
        val store=WebhookSettingsStore(context,root,alias)
        val queue=WebhookQueueRepository(db)
        val history=HistoryRepository(db)
        @Volatile var failWorker=false
        @Volatile var permission=true
        val coordinator=WebhookCoordinator(db,root,store,{},{permission})
        lateinit var work:WorkManager
        lateinit var scheduler:WebhookWorkScheduler
        lateinit var controller:WebhookController
        fun initialized()=this::controller.isInitialized
        val factory=object:WorkerFactory() {
            override fun createWorker(appContext:Context,workerClassName:String,workerParameters:WorkerParameters):ListenableWorker? =
                if(workerClassName==WebhookWorker::class.java.name) {
                    if(failWorker) object:CoroutineWorker(appContext,workerParameters) {override suspend fun doWork()=Result.failure()}
                    else WebhookWorker(appContext,workerParameters,coordinator,scheduler)
                } else null
        }
        fun start(outliers:Flow<Boolean> =flowOf(false)) {controller=WebhookController(coordinator,scheduler,queue.observe().map {Unit},outliers)}
        fun settings(server:Receiver,mode:WebhookMode=WebhookMode.batchCount)=WebhookSettings(enabled=true,endpoint=server.url,mode=mode,batchCount=5,allowInsecureHttp=true)
        fun active()=work.getWorkInfosByTag(WebhookWorkScheduler.TAG).get().filter {!it.state.isFinished}
        suspend fun until(test:()->Boolean)=withTimeout(15_000) {while(!test()) delay(25)}
        suspend fun record(n:Int=1,offset:Int=0) {
            history.start(0);val now=Instant.now()
            history.ingest((1..n).map {i->TrackPoint(id="controller-synthetic-${i+offset}",coordinate=Coordinate(0.0,0.0),time=now.plusMillis(i.toLong()),accuracy=5.0)})
        }
    }
    private suspend fun fixture(block:suspend(Fixture)->Unit) {
        check(context.packageName=="com.bontecou.whereday.platform.test")
        val f=Fixture()
        try {
            WorkManagerTestInitHelper.initializeTestWorkManager(context,Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(f.factory).build())
            f.work=WorkManager.getInstance(context);f.scheduler=WebhookWorkScheduler(f.work,f.coordinator)
            block(f)
        } finally {
            if(f.initialized()) f.controller.close()
            f.scheduler.cancelAll();f.coordinator.reset();WorkManagerTestInitHelper.closeWorkDatabase();f.db.close();f.root.deleteRecursively()
        }
    }
    @Test fun connectionFeedbackKeepsItsHttpStatusAcrossObservationRefreshes():Unit=runBlocking {fixture {f->
        Receiver().use {server->
            f.coordinator.configure(f.settings(server,WebhookMode.manual),WebhookCredentials());f.start();f.until {f.controller.state.value.loaded}
            f.controller.testConnection()
            f.until {f.controller.state.value.feedback?.notice==WebhookNotice.connected && !f.controller.state.value.busy}
            val feedback=f.controller.state.value.feedback!!
            assertEquals(204,feedback.httpStatus);assertEquals("Connected (HTTP 204).",feedback.diagnostic)
            f.controller.refresh();delay(100)
            assertEquals(feedback,f.controller.state.value.feedback);assertNull(f.controller.state.value.failure)
            assertEquals(1,server.requests.size);assertEquals(0L,f.db.history().totalPointCount());assertEquals(0L,f.queue.snapshot().batches)
        }
    }}
    @Test fun staleConfigurationFailureDoesNotEchoTheEndpointOrChangeSavedSettings():Unit=runBlocking {fixture {f->
        Receiver().use {server->
            val saved=f.coordinator.configure(f.settings(server,WebhookMode.manual),WebhookCredentials());f.start();f.until {f.controller.state.value.loaded}
            f.controller.configure(saved.settings.copy(endpoint="https://example.invalid/private-token"),WebhookCredentials(),expectedRevision="stale-revision")
            f.until {f.controller.state.value.failure==WebhookFailure.changed && !f.controller.state.value.busy}
            assertEquals(saved,f.coordinator.state().configuration);assertNull(f.controller.state.value.feedback)
            assertFalse(f.controller.state.value.error!!.contains("private-token"));assertTrue(server.requests.isEmpty())
        }
    }}
    @Test fun preferenceFailureHasASanitizedStableIdentity():Unit=runBlocking {fixture {f->
        f.start(flow {throw IllegalStateException("synthetic-private-token")})
        f.until {f.controller.state.value.failure==WebhookFailure.preferences}
        assertFalse(f.controller.state.value.loaded);assertNull(f.controller.state.value.feedback)
        assertFalse(f.controller.state.value.error!!.contains("synthetic-private-token"));assertTrue(f.active().isEmpty())
    }}
    @Test fun queueObservationSchedulesThresholdAndUpdatesStatusAfterWorkerSuccess():Unit=runBlocking {fixture {f->
        Receiver().use {server->
            f.start();f.until {f.controller.state.value.loaded};assertTrue(f.active().isEmpty())
            f.controller.configure(f.settings(server),WebhookCredentials())
            f.until {f.controller.state.value.state?.configuration?.settings?.enabled==true && !f.controller.state.value.busy}
            f.record(4);f.until {f.controller.state.value.state?.queue?.queuedPoints==4L};assertTrue(f.active().isEmpty())
            f.record(1,4);f.until {f.active().size==1};val request=f.active().single()
            WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(request.id)
            f.until {f.work.getWorkInfoById(request.id).get()?.state==WorkInfo.State.SUCCEEDED}
            f.until {f.controller.state.value.state?.queue?.queuedPoints==0L}
            assertEquals(1,server.requests.size);assertEquals(5L,f.db.history().totalPointCount());assertTrue(f.active().isEmpty())
        }
    }}
    @Test fun failedBackgroundWorkIsVisibleAndForegroundExplicitlyResumesIt():Unit=runBlocking {fixture {f->
        Receiver().use {server->
            f.coordinator.configure(f.settings(server),WebhookCredentials());f.record(5);f.failWorker=true;f.start()
            f.until {f.active().size==1};val first=f.active().single()
            WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(first.id)
            f.until {f.controller.state.value.backgroundPaused}
            assertNotNull(f.controller.state.value.error);assertEquals(WebhookFailure.backgroundPaused,f.controller.state.value.failure);assertTrue(f.active().isEmpty());assertTrue(server.requests.isEmpty())
            f.controller.refresh();delay(100);assertTrue(f.active().isEmpty())
            f.failWorker=false;f.controller.foreground();f.until {f.active().size==1}
            val retry=f.active().single();assertNotEquals(first.id,retry.id)
            WorkManagerTestInitHelper.getTestDriver(context)!!.setAllConstraintsMet(retry.id)
            f.until {f.work.getWorkInfoById(retry.id).get()?.state==WorkInfo.State.SUCCEEDED}
            f.until {f.controller.state.value.state?.queue?.queuedPoints==0L && !f.controller.state.value.backgroundPaused}
            assertNull(f.controller.state.value.error);assertEquals(1,server.requests.size)
        }
    }}
    @Test fun pendingOutliersAreNotDiscardedBeforeThePreferenceLoads():Unit=runBlocking {fixture {f->
        Receiver().use {server->
            val saved=f.coordinator.configure(f.settings(server),WebhookCredentials(),includeOutliers=true)
            f.db.webhooks().capture(WebhookPointRow(saved.revision,TrackPoint(id="pending-outlier",coordinate=Coordinate(0.0,0.0),time=Instant.now(),accuracy=5.0,outlier=true),Instant.now()))
            val preference=MutableSharedFlow<Boolean>(replay=1)
            f.start(preference);delay(100)
            assertFalse(f.controller.state.value.loaded);assertEquals(1L,f.queue.snapshot().queuedPoints)
            preference.emit(true);f.until {f.controller.state.value.loaded};assertEquals(1L,f.queue.snapshot().queuedPoints)
            preference.emit(false);f.until {f.controller.state.value.state?.queue?.queuedPoints==0L}
            assertFalse(f.queue.snapshot().policy!!.includeOutliers);assertTrue(server.requests.isEmpty())
        }
    }}
    @Test fun foregroundPermissionLossDisablesQueuedAutomaticWorkWithoutSending():Unit=runBlocking {fixture {f->
        Receiver().use {server->
            f.coordinator.configure(f.settings(server),WebhookCredentials());f.record(1);f.start();f.until {f.controller.state.value.loaded}
            f.permission=false;f.controller.foreground()
            f.until {f.controller.state.value.state?.configuration?.settings?.enabled==false}
            assertEquals(0L,f.queue.snapshot().queuedPoints);assertFalse(f.queue.snapshot().policy!!.capture)
            assertTrue(f.active().isEmpty());assertTrue(server.requests.isEmpty());assertEquals(1L,f.db.history().totalPointCount())
        }
    }}
    @Test fun clearCancelsAndJoinsAnActiveConnectionTestBeforeDeletingHistory():Unit=runBlocking {fixture {f->
        Receiver(stall=true).use {server->
            f.coordinator.configure(f.settings(server,WebhookMode.manual),WebhookCredentials());f.record(1);f.start();f.until {f.controller.state.value.loaded}
            f.controller.testConnection();withTimeout(5000) {server.received.await()}
            withTimeout(5000) {f.controller.clearAll {withContext(Dispatchers.IO) {f.db.clearAllTables()}};server.finished.await()}
            f.until {f.controller.state.value.state?.configuration?.revision==""}
            assertEquals(0L,f.db.history().totalPointCount());assertNull(f.queue.snapshot().policy);assertTrue(f.active().isEmpty())
            assertTrue(File(f.root,"payloads").listFiles().orEmpty().isEmpty());assertFalse(f.controller.state.value.busy)
        }
    }}
    @Test fun manualConnectionTestSurvivesALocationPermissionCheckAndDisableCanCancelIt():Unit=runBlocking {fixture {f->
        Receiver(stall=true).use {server->
            f.permission=false;f.coordinator.configure(f.settings(server,WebhookMode.manual),WebhookCredentials());f.start();f.until {f.controller.state.value.loaded}
            f.controller.testConnection();withTimeout(5000) {server.received.await()}
            f.coordinator.permissionsChanged();assertFalse(server.finished.isCompleted)
            f.controller.disable();withTimeout(5000) {server.finished.await()}
            f.until {f.controller.state.value.state?.configuration?.settings?.enabled==false && !f.controller.state.value.busy}
            assertTrue(f.active().isEmpty());assertEquals(1,server.requests.size)
        }
    }}
}
