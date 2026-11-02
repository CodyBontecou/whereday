package com.bontecou.whereday.platform

import android.content.Context
import android.content.Intent
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import androidx.work.*
import androidx.work.testing.*
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.*
import java.util.UUID

class ScheduledExportWorkTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val tree=DocumentsContract.buildTreeDocumentUri("com.bontecou.whereday.export-test-documents","root")
    private val day=Instant.parse("2026-10-27T00:00:00Z")
    private inner class Fixture(val db:HistoryDatabase) {
        @Volatile var now=day.plusSeconds(8*3600)
        @Volatile var zone:ZoneId=ZoneOffset.UTC
        val files=ExportFiles(context,db) {}
        val coordinator=ScheduledExportCoordinator(context,files,{ScheduledExportInputs(tree.toString(),"track-{date}")},{},{now},{zone})
        lateinit var scheduler:ScheduledExportWorkScheduler
        lateinit var work:WorkManager
        fun initialized()=this::scheduler.isInitialized
        val factory=object:WorkerFactory() {
            override fun createWorker(appContext:Context,workerClassName:String,workerParameters:WorkerParameters):ListenableWorker? =
                if(workerClassName==ScheduledExportWorker::class.java.name) ScheduledExportWorker(appContext,workerParameters,coordinator,scheduler) else null
        }
        val config=AutomaticExportSettings(true,ExportSchedule(9,0,3),ExportFormat.json,DataKind.points,ScheduledFileMode.append)
        fun infos()=work.getWorkInfosByTag(ScheduledExportWorkScheduler.TAG).get()
        fun active()=infos().filter {!it.state.isFinished}
        suspend fun await(id:UUID,state:WorkInfo.State):WorkInfo=withTimeout(20_000) {
            var info=work.getWorkInfoById(id).get()
            while(info?.state!=state) {delay(25);info=work.getWorkInfoById(id).get()}
            info
        }
    }
    private suspend fun fixture(block:suspend (Fixture)->Unit) {
        check(context.packageName=="com.bontecou.whereday.platform.test")
        val name="work-synthetic-${UUID.randomUUID()}.db";val db=HistoryDatabase.create(context,name);val f=Fixture(db)
        try {
            ExportTestDocuments.rejectWrites.set(false)
            context.grantUriPermission(context.packageName,tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            val configuration=Configuration.Builder().setExecutor(SynchronousExecutor()).setWorkerFactory(f.factory).build()
            WorkManagerTestInitHelper.initializeTestWorkManager(context,configuration)
            f.work=WorkManager.getInstance(context)
            f.scheduler=ScheduledExportWorkScheduler(f.work,f.coordinator,{f.now},{f.zone})
            f.coordinator.clear();f.files.clearCache()
            db.history().putPoints(listOf(0L,9*3600L,12*3600L).mapIndexed {i,time ->PointRow(TrackPoint(id="synthetic-$i",coordinate=Coordinate(18.0,-66.0),time=day.plusSeconds(time),accuracy=5.0),null)})
            block(f)
        } finally {
            ExportTestDocuments.rejectWrites.set(false)
            if(f.initialized()) f.scheduler.cancelAll()
            f.coordinator.clear();f.files.clearCache();WorkManagerTestInitHelper.closeWorkDatabase()
            db.close();context.deleteDatabase(name)
            context.revokeUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            File(context.cacheDir,"synthetic-export-documents").deleteRecursively()
        }
    }
    @Test fun queuedFieldEditsComposeAgainstLatestDurableSettingsAndKeepInvocationOrder()=runBlocking {
        fixture {f->
            f.coordinator.configure(f.config.copy(enabled=false))
            val controller=AutomaticExportController(f.coordinator,f.scheduler)
            try {
                withTimeout(5000) {while(!controller.state.value.loaded || controller.state.value.busy) delay(10)}
                val onMain=java.util.concurrent.atomic.AtomicBoolean()
                InstrumentationRegistry.getInstrumentation().runOnMainSync {
                    controller.updateSettings {onMain.set(android.os.Looper.myLooper()==android.os.Looper.getMainLooper());it.copy(schedule=it.schedule.copy(intervalHours=23))}
                    controller.updateSettings {it.copy(format=ExportFormat.csv)}
                    controller.updateSettings {it.copy(kind=DataKind.visits)}
                    controller.updateSettings {it.copy(schedule=it.schedule.copy(intervalHours=1))}
                }
                withTimeout(5000) {while(controller.state.value.state.settings.let {it.schedule.intervalHours!=1 || it.format!=ExportFormat.csv || it.kind!=DataKind.visits} || controller.state.value.busy) delay(10)}
                val saved=f.coordinator.snapshot().settings
                assertFalse("A field transformation ran on the main thread",onMain.get());assertEquals(ExportSchedule(9,0,1),saved.schedule);assertEquals(ExportFormat.csv,saved.format);assertEquals(DataKind.visits,saved.kind);assertFalse(saved.enabled)
                assertEquals(AutomaticExportActionNotice.settingsSaved,controller.state.value.notice);assertNull(controller.state.value.failure);assertTrue(f.active().isEmpty())
            } finally {controller.close()}
        }
    }
    @Test fun controllerNeverDisplaysArbitraryActionExceptionText()=runBlocking {
        fixture {f->
            val controller=AutomaticExportController(f.coordinator,f.scheduler)
            try {
                withTimeout(5000) {while(!controller.state.value.loaded || controller.state.value.busy) delay(10)}
                controller.updateSettings {throw IllegalStateException("content://private-token/location")}
                withTimeout(5000) {while(controller.state.value.failure==null || controller.state.value.busy) delay(10)}
                assertEquals(AutomaticExportFailure.execution,controller.state.value.failure)
                assertFalse(controller.state.value.error!!.contains("private-token"));assertNull(controller.state.value.notice)
                assertEquals(AutomaticExportState(),f.coordinator.snapshot());assertTrue(f.active().isEmpty())
            } finally {controller.close()}
        }
    }
    @Test fun unreadableSettingsRequireExplicitResetAndUseASafeFailureIdentity()=runBlocking {
        fixture {f->
            File(context.filesDir,"automatic-exports/state.json").writeText("{private-token")
            val controller=AutomaticExportController(f.coordinator,f.scheduler)
            try {
                withTimeout(5000) {while(controller.state.value.failure==null || controller.state.value.busy) delay(10)}
                assertFalse(controller.state.value.loaded);assertEquals(AutomaticExportFailure.settingsUnavailable,controller.state.value.failure)
                assertFalse(controller.state.value.error!!.contains("private-token"));assertTrue(f.active().isEmpty())
                controller.reset()
                withTimeout(5000) {while(!controller.state.value.loaded || controller.state.value.busy) delay(10)}
                assertNull(controller.state.value.failure);assertEquals(AutomaticExportActionNotice.reset,controller.state.value.notice)
                assertEquals(AutomaticExportState(),f.coordinator.snapshot())
            } finally {controller.close()}
        }
    }
    @Test fun verifiedAccessDenialIsTypedAndNeverEnablesTheSchedule()=runBlocking {
        fixture {f->
            val denied=ScheduledExportCoordinator(context,f.files,{error("Inputs must not be requested")},{throw com.bontecou.whereday.billing.VerifiedAccessRequired()})
            val controller=AutomaticExportController(denied,f.scheduler)
            try {
                withTimeout(5000) {while(!controller.state.value.loaded || controller.state.value.busy) delay(10)}
                controller.setEnabled(true)
                withTimeout(5000) {while(controller.state.value.failure==null || controller.state.value.busy) delay(10)}
                assertEquals(AutomaticExportFailure.purchase,controller.state.value.failure);assertNull(controller.state.value.notice)
                assertFalse(controller.state.value.state.settings.enabled);assertEquals(AutomaticExportState(),denied.snapshot());assertTrue(f.active().isEmpty())
            } finally {controller.close()}
        }
    }
    @Test fun editsAcrossIndependentControllersCannotLoseOneAnother()=runBlocking {
        fixture {f->
            f.coordinator.configure(f.config.copy(enabled=false,schedule=ExportSchedule(9,0,0)))
            val controllers=(1..16).map {AutomaticExportController(f.coordinator,f.scheduler)}
            try {
                withTimeout(10000) {while(controllers.any {!it.state.value.loaded || it.state.value.busy}) delay(10)}
                controllers.forEach {controller->controller.updateSettings {it.copy(schedule=it.schedule.copy(intervalHours=it.schedule.intervalHours+1))}}
                withTimeout(10000) {while(controllers.any {it.state.value.notice!=AutomaticExportActionNotice.settingsSaved || it.state.value.busy}) delay(10)}
                assertEquals(16,f.coordinator.snapshot().settings.schedule.intervalHours)
                assertTrue(f.active().isEmpty())
            } finally {controllers.forEach {it.close()}}
        }
    }
    @Test fun uniqueDelayedWorkRunsAndSchedulesSuccessorWithoutCancelingItself()=runBlocking {
        fixture {f ->
            f.coordinator.configure(f.config)
            assertEquals(day.plusSeconds(9*3600),f.scheduler.reconcile())
            val first=f.active().single();assertEquals(3_600_000L,first.initialDelayMillis)
            f.scheduler.reconcile();assertEquals(first.id,f.active().single().id)
            f.now=day.plusSeconds(9*3600+60)
            WorkManagerTestInitHelper.getTestDriver(context)!!.setInitialDelayMet(first.id)
            f.await(first.id,WorkInfo.State.SUCCEEDED)
            assertEquals(f.now,f.coordinator.snapshot().lastSuccess)
            val next=f.active().single();assertNotEquals(first.id,next.id)
            assertTrue(next.tags.any {it.contains((day.plusSeconds(12*3600)).epochSecond.toString())})
            assertEquals(WorkInfo.State.SUCCEEDED,f.work.getWorkInfoById(first.id).get()!!.state)
        }
    }
    @Test fun workerFailureRequestsRetryAndExistingWorkKeepsItsIdentity()=runBlocking {
        fixture {f ->
            f.coordinator.configure(f.config);f.scheduler.reconcile();val first=f.active().single()
            f.now=day.plusSeconds(9*3600+60);ExportTestDocuments.rejectWrites.set(true)
            val direct=TestListenableWorkerBuilder.from(context,ScheduledExportWorker::class.java).setWorkerFactory(f.factory).build()
            assertEquals(ListenableWorker.Result.retry(),direct.doWork())
            val failed=f.coordinator.snapshot();assertNotNull(failed.pending);assertNull(failed.lastSuccess);assertNull(failed.appendCursor)
            f.scheduler.reconcile();assertEquals(first.id,f.active().single().id)
            ExportTestDocuments.rejectWrites.set(false)
            WorkManagerTestInitHelper.getTestDriver(context)!!.setInitialDelayMet(first.id)
            f.await(first.id,WorkInfo.State.SUCCEEDED)
            assertNull(f.coordinator.snapshot().pending);assertEquals(f.now,f.coordinator.snapshot().lastSuccess)
        }
    }
    @Test fun changingZoneReplacesFutureWorkAndDisablingCancelsIt()=runBlocking {
        fixture {f ->
            f.coordinator.configure(f.config.copy(schedule=ExportSchedule(9)))
            f.scheduler.reconcile();val original=f.active().single()
            f.zone=ZoneId.of("America/Puerto_Rico")
            assertEquals(day.plusSeconds(13*3600),f.scheduler.reconcile())
            f.await(original.id,WorkInfo.State.CANCELLED)
            assertEquals(18_000_000L,f.active().single().initialDelayMillis)
            f.coordinator.configure(f.coordinator.snapshot().settings.copy(enabled=false));f.scheduler.reconcile()
            assertTrue(f.active().isEmpty());assertNull(f.coordinator.snapshot().lastSuccess)
        }
    }
    @Test fun foregroundCatchUpUsesSameCoordinatorAndClearCancelsQueuedWork()=runBlocking {
        fixture {f ->
            f.coordinator.configure(f.config);f.now=day.plusSeconds(9*3600+60)
            val controller=AutomaticExportController(f.coordinator,f.scheduler)
            try {
                controller.foreground();controller.foreground()
                withTimeout(20_000) {while(controller.state.value.state.lastSuccess!=f.now || controller.state.value.busy) delay(25)}
                assertEquals(1,f.active().size)
                val opens=ExportTestDocuments.outputOpens.get()
                controller.foreground()
                withTimeout(20_000) {while(controller.state.value.busy) delay(25)}
                assertEquals(opens,ExportTestDocuments.outputOpens.get())
                controller.clearAll()
                assertTrue(f.active().isEmpty());assertEquals(AutomaticExportState(),f.coordinator.snapshot())
            } finally {controller.close()}
        }
    }
}
