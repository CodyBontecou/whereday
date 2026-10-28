package com.bontecou.whereday.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.*
import com.bontecou.whereday.platform.WebhookDeliveryTest.Receiver
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.time.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WebhookCoordinatorTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root:File
    private lateinit var alias:String
    private lateinit var db:HistoryDatabase
    private lateinit var history:HistoryRepository
    private lateinit var queue:WebhookQueueRepository
    private lateinit var store:WebhookSettingsStore
    private lateinit var coordinator:WebhookCoordinator
    private val clock=TestClock()
    private var paid=true
    private var permission=true
    @Before fun setup() {
        check(context.packageName=="com.bontecou.whereday.platform.test")
        root=File(context.cacheDir,"webhook-coordinator-${UUID.randomUUID()}")
        alias="whereday_webhook_coordinator_${UUID.randomUUID()}"
        db=HistoryDatabase.create(context,null);history=HistoryRepository(db,clock);queue=WebhookQueueRepository(db,clock)
        store=WebhookSettingsStore(context,root,alias);coordinator=owner()
    }
    private fun owner(checkpoint:suspend(String)->Unit={}):WebhookCoordinator=WebhookCoordinator(db,root,store,{check(paid) {"Synthetic purchase gate"}},{permission},clock,checkpoint=checkpoint)
    @After fun cleanup() {db.close();root.deleteRecursively();KeyStore.getInstance("AndroidKeyStore").apply {load(null);if(containsAlias(alias)) deleteEntry(alias)}}
    private fun settings(server:Receiver,mode:WebhookMode=WebhookMode.realtime)=WebhookSettings(enabled=true,endpoint=server.url,mode=mode,batchCount=5,batchMinutes=1,allowInsecureHttp=true)
    private suspend fun record(count:Int,offset:Int=0) {
        if(db.history().recording()?.requested!=true) history.start(0)
        clock.now=clock.start.plusSeconds(10000)
        history.ingest((1..count).map {n->TrackPoint(id="synthetic-${n+offset}",coordinate=Coordinate(0.0,0.0),time=clock.start.plusSeconds((n+offset).toLong()),accuracy=5.0)})
    }
    @Test fun acceptedRecordingIsStreamedAndAcknowledgedWithoutRemovingHistory():Unit=runBlocking {
        Receiver().use {server->
            assertEquals(WebhookRun.Disabled,coordinator.runNext());assertFalse(File(root,"settings.bin").exists())
            coordinator.configure(settings(server),WebhookCredentials());record(1201)
            assertEquals(1201L,queue.snapshot().queuedPoints)
            assertEquals(WebhookRun.Delivered(204),withTimeout(10000) {coordinator.runNext()})
            assertEquals(0L,queue.snapshot().queuedPoints);assertEquals(0L,queue.snapshot().batches)
            assertEquals(1201L,db.history().totalPointCount());assertEquals(1,server.requests.size)
            assertEquals(204,queue.snapshot().policy!!.lastStatus);assertEquals(clock.now,queue.snapshot().policy!!.lastSuccess)
            assertTrue(File(root,"payloads").listFiles().orEmpty().isEmpty())
        }
    }
    @Test fun countThresholdFlushesAllBufferedPointsAndTimeModeUsesItsConfiguredTimer():Unit=runBlocking {
        Receiver(listOf(204,204)).use {server->
            coordinator.configure(settings(server,WebhookMode.batchCount),WebhookCredentials());record(4)
            assertEquals(WebhookRun.Waiting(),coordinator.runNext());assertTrue(server.requests.isEmpty())
            record(3,4);assertEquals(WebhookRun.Delivered(204),coordinator.runNext())
            assertTrue(server.requests[0].prefix.contains("\"totalPoints\": 7"))
            coordinator.configure(settings(server,WebhookMode.batchTime),WebhookCredentials())
            val anchor=clock.now;record(1,7)
            assertEquals(WebhookRun.Waiting(anchor.plusSeconds(60)),coordinator.runNext())
            clock.now=anchor.plusSeconds(60);assertEquals(WebhookRun.Delivered(204),coordinator.runNext())
        }
    }
    @Test fun retriesAcrossRecreatedOwnersKeepExactBodyAndIdentityAfterHistoryChanges():Unit=runBlocking {persistentDatabase {
        Receiver(listOf(503,204),retryAfter="5").use {server->
            coordinator.configure(settings(server),WebhookCredentials());record(2)
            assertEquals(WebhookRun.Waiting(clock.now.plusSeconds(5)),coordinator.runNext())
            val head=queue.snapshot().head!!;assertEquals(1,head.attempts)
            db.history().put(PointRow(TrackPoint(id="synthetic-1",coordinate=Coordinate(1.0,1.0),time=clock.start.plusSeconds(1),accuracy=5.0),null))
            reopenDatabase();assertEquals(WebhookRun.Waiting(clock.now.plusSeconds(5)),coordinator.runNext())
            clock.now=clock.now.plusSeconds(5);assertEquals(WebhookRun.Delivered(204),coordinator.runNext())
            assertEquals(server.requests[0].sha256,server.requests[1].sha256)
            assertEquals(server.requests[0].headers["idempotency-key"],server.requests[1].headers["idempotency-key"])
        }
    }}
    private class AbruptTermination:Error()
    @Test fun processDeathAfterHttpSuccessReplaysTheSamePayloadWithAStaleAcknowledgmentFence():Unit=runBlocking {
        Receiver(listOf(204,204)).use {server->
            coordinator.configure(settings(server),WebhookCredentials());record(1)
            val crashing=owner {if(it=="response-before-ack") throw AbruptTermination()}
            try {crashing.runNext();fail()} catch(_:AbruptTermination) {}
            val interrupted=queue.snapshot().head!!;assertNotNull(interrupted.attemptToken)
            assertEquals(WebhookRun.Waiting(clock.now.plusSeconds(1)),owner().runNext())
            clock.now=clock.now.plusSeconds(1);assertEquals(WebhookRun.Delivered(204),owner().runNext())
            assertFalse(queue.finishAttempt(interrupted.revision,interrupted.id,interrupted.attemptToken!!,204))
            assertEquals(server.requests[0].sha256,server.requests[1].sha256)
            assertEquals(server.requests[0].headers["idempotency-key"],server.requests[1].headers["idempotency-key"])
        }
    }
    @Test fun preparedAndRoomCommittedSettingsRecoverBeforeAnySend():Unit=runBlocking {persistentDatabase {
        for(stage in listOf("settings-prepared","settings-room-applied")) Receiver().use {server->
            val crashing=owner {if(it==stage) throw AbruptTermination()}
            try {crashing.configure(settings(server),WebhookCredentials());fail()} catch(_:AbruptTermination) {}
            val pending=store.document().pending!!
            try {store.load();fail()} catch(_:IllegalStateException) {}
            reopenDatabase()
            val recovered=owner().state()
            assertEquals(pending.configuration,recovered.configuration);assertEquals(recovered.configuration.revision,recovered.queue.policy!!.revision)
            assertNull(store.document().pending);assertTrue(server.requests.isEmpty())
            owner().disable()
        }
    }}
    @Test fun authenticationRepairRetainsAStoppedBodyButFormatChangesRequireDiscard():Unit=runBlocking {
        Receiver(listOf(401,204)).use {server->
            coordinator.configure(settings(server).copy(auth=WebhookAuth.bearer),WebhookCredentials(value="old-synthetic"));record(1)
            assertEquals(WebhookRun.NeedsAttention,coordinator.runNext());val old=queue.snapshot().head!!
            try {coordinator.configure(settings(server),WebhookCredentials());fail()} catch(_:IllegalStateException) {}
            assertNull(store.document().pending)
            try {coordinator.configure(settings(server).copy(format=ExportFormat.csv),WebhookCredentials(),transition=WebhookQueueTransition.retain);fail()} catch(_:IllegalStateException) {}
            coordinator.configure(settings(server).copy(auth=WebhookAuth.bearer),WebhookCredentials(value="new-synthetic"),transition=WebhookQueueTransition.retain)
            assertEquals(old.bodyId,queue.snapshot().head!!.bodyId)
            coordinator.retry(old.id);assertEquals(WebhookRun.Delivered(204),coordinator.runNext())
            assertEquals("Bearer new-synthetic",server.requests[1].headers["authorization"])
            assertEquals(server.requests[0].sha256,server.requests[1].sha256)
            assertEquals(server.requests[0].headers["idempotency-key"],server.requests[1].headers["idempotency-key"])
        }
    }
    @Test fun disablingFromAnotherOwnerCancelsAndJoinsTheInflightRequest():Unit=runBlocking {
        Receiver(stall=true).use {server->
            coordinator.configure(settings(server),WebhookCredentials());record(1)
            val running=async {coordinator.runNext()}
            withTimeout(5000) {server.received.await()}
            withTimeout(5000) {owner().disable();running.join();server.finished.await()}
            assertTrue(running.isCancelled);assertFalse(owner().state().configuration.settings.enabled)
            assertEquals(0L,queue.snapshot().queuedPoints);assertEquals(0L,queue.snapshot().batches)
            assertTrue(File(root,"payloads").listFiles().orEmpty().isEmpty());assertEquals(1L,db.history().totalPointCount())
        }
    }
    @Test fun permissionRevocationCancelsAnInflightAutomaticRequestBeforeClearingItsQueue():Unit=runBlocking {
        Receiver(stall=true).use {server->
            coordinator.configure(settings(server),WebhookCredentials());record(1)
            val running=async {coordinator.runNext()}
            withTimeout(5000) {server.received.await()};permission=false
            withTimeout(5000) {owner().permissionsChanged();running.join();server.finished.await()}
            assertTrue(running.isCancelled);assertFalse(owner().state().configuration.settings.enabled)
            assertEquals(0L,queue.snapshot().queuedPoints);assertEquals(0L,queue.snapshot().batches)
            assertTrue(File(root,"payloads").listFiles().orEmpty().isEmpty());assertEquals(1L,db.history().totalPointCount())
        }
    }
    @Test fun staleEditorRevisionCannotOverwriteNewerSavedSettings():Unit=runBlocking {
        Receiver().use {server->
            val old=coordinator.configure(settings(server,WebhookMode.manual),WebhookCredentials())
            val latest=coordinator.configure(settings(server,WebhookMode.batchCount),WebhookCredentials(),expectedRevision=old.revision)
            try {coordinator.configure(old.settings,old.credentials,expectedRevision=old.revision);fail()} catch(_:WebhookConfigurationChanged) {}
            assertEquals(latest,coordinator.state().configuration);assertTrue(server.requests.isEmpty());assertNull(store.document().pending)
        }
    }
    @Test fun concurrentOwnersSerializeAndSendOneLogicalBatch():Unit=runBlocking {
        Receiver().use {server->
            coordinator.configure(settings(server),WebhookCredentials());record(1)
            val outcomes=coroutineScope {listOf(async {owner().runNext()},async {owner().runNext()}).awaitAll()}
            assertTrue(outcomes.contains(WebhookRun.Delivered(204)));assertTrue(outcomes.contains(WebhookRun.Waiting()))
            assertEquals(1,server.requests.size)
        }
    }
    @Test fun permissionLossDisablesCaptureAndPurchaseLossPreventsEgress():Unit=runBlocking {
        Receiver().use {server->
            coordinator.configure(settings(server),WebhookCredentials());record(1);paid=false
            try {coordinator.runNext();fail()} catch(_:IllegalStateException) {}
            assertTrue(server.requests.isEmpty());assertEquals(0L,queue.snapshot().batches)
            paid=true;permission=false;assertEquals(WebhookRun.Disabled,coordinator.runNext())
            assertEquals(0L,queue.snapshot().queuedPoints);assertFalse(queue.snapshot().policy!!.capture)
            assertTrue(server.requests.isEmpty());assertEquals(1L,db.history().totalPointCount())
        }
    }
    @Test fun manualHistoryRequiresExplicitEnqueueAndFlushIntentSurvivesModeChanges():Unit=runBlocking {
        Receiver(listOf(204,204)).use {server->
            coordinator.configure(settings(server,WebhookMode.batchCount),WebhookCredentials());record(2)
            coordinator.configure(settings(server,WebhookMode.manual),WebhookCredentials(),transition=WebhookQueueTransition.retain)
            assertEquals(WebhookRun.Waiting(),coordinator.runNext());assertTrue(server.requests.isEmpty())
            coordinator.flush();assertEquals("flush",queue.snapshot().head!!.kind)
            assertEquals(WebhookRun.Delivered(204),owner().runNext())
            record(1,2);assertEquals(0L,queue.snapshot().queuedPoints)
            coordinator.enqueueHistory();assertEquals("manual",queue.snapshot().head!!.kind)
            assertEquals(WebhookRun.Delivered(204),owner().runNext());assertEquals(3L,db.history().totalPointCount())
            assertTrue(server.requests[1].prefix.contains("\"totalPoints\": 3"))
        }
    }
    @Test fun corruptFrozenBodyStopsWithoutRebuildingAndResetCanEraseLostKeySettings():Unit=runBlocking {
        Receiver(listOf(503)).use {server->
            coordinator.configure(settings(server),WebhookCredentials());record(1);coordinator.runNext()
            val head=queue.snapshot().head!!;File(root,"payloads/${head.bodyId}.body").appendText("corrupt")
            clock.now=clock.now.plusSeconds(1);assertEquals(WebhookRun.NeedsAttention,coordinator.runNext())
            assertTrue(queue.snapshot().head!!.stopped);assertEquals(1,server.requests.size)
            KeyStore.getInstance("AndroidKeyStore").apply {load(null);deleteEntry(alias)}
            try {coordinator.runNext();fail()} catch(_:WebhookSettingsUnavailable) {}
            coordinator.reset();assertEquals(WebhookRun.Disabled,owner().runNext())
            assertTrue(File(root,"payloads").listFiles().orEmpty().isEmpty());assertEquals(1L,db.history().totalPointCount())
        }
    }
    @Test fun explicitManualHistoryCanSendWhileAnOlderAutomaticBatchIsPaused():Unit=runBlocking {
        Receiver(listOf(503,204)).use {server->
            coordinator.configure(settings(server),WebhookCredentials());record(1);coordinator.runNext()
            val old=queue.snapshot().head!!
            coordinator.configure(settings(server,WebhookMode.manual),WebhookCredentials(),transition=WebhookQueueTransition.retain)
            coordinator.enqueueHistory();assertEquals(WebhookRun.Delivered(204),owner().runNext())
            assertEquals(old.id,queue.snapshot().head!!.id);assertEquals(1L,queue.snapshot().queuedPoints)
            assertEquals(WebhookRun.Waiting(),owner().runNext())
            assertEquals(2,server.requests.size)
        }
    }
    @Test fun connectionTestSendsOnlyASyntheticPointAndDoesNotEnableTheSavedConfiguration():Unit=runBlocking {
        Receiver().use {server->
            coordinator.configure(settings(server).copy(enabled=false),WebhookCredentials());record(2)
            assertEquals(204,coordinator.testConnection().status)
            val state=coordinator.state();assertFalse(state.configuration.settings.enabled);assertFalse(state.queue.policy!!.capture)
            assertEquals(0L,state.queue.batches);assertEquals(0L,state.queue.queuedPoints)
            val body=server.requests.single().prefix
            assertTrue(body.contains("\"totalPoints\": 1"));assertFalse(body.contains("synthetic-1"));assertFalse(body.contains("visits"))
            assertTrue(File(root,"payloads").listFiles().orEmpty().isEmpty());assertEquals(2L,db.history().totalPointCount())
        }
    }
    @Test fun disablingAbandonsAPreparedChangeThatCannotTransferPendingData():Unit=runBlocking {
        Receiver().use {server->
            val saved=coordinator.configure(settings(server),WebhookCredentials());record(1)
            store.prepare(saved.revision,settings(server),WebhookCredentials(),WebhookQueueTransition.requireEmpty,false)
            assertFalse(owner().disable().settings.enabled)
            assertNull(store.document().pending);assertEquals(0L,queue.snapshot().queuedPoints);assertTrue(server.requests.isEmpty())
        }
    }
    @Test fun osFileLockWaitIsCancellableBeforeAnyPayloadOrRequestIsCreated():Unit=runBlocking {
        Receiver().use {server->
            coordinator.configure(settings(server),WebhookCredentials());record(1)
            java.io.RandomAccessFile(File(root,"delivery.lock"),"rw").channel.use {channel->channel.lock().use {
                val waiting=async {coordinator.runNext()}
                // Let the second handle enter tryLock while the independent OS handle owns it.
                delay(100);assertFalse(waiting.isCompleted)
                withTimeout(5000) {waiting.cancelAndJoin()}
                assertEquals(0L,queue.snapshot().batches);assertTrue(server.requests.isEmpty())
            }}
            assertEquals(WebhookRun.Delivered(204),coordinator.runNext())
        }
    }
    @Test fun orphanCleanupPreservesReferencedBodiesAndForeignFilesAndMismatchFailsClosed():Unit=runBlocking {
        Receiver(listOf(503)).use {server->
            coordinator.configure(settings(server),WebhookCredentials());record(1);coordinator.runNext()
            val directory=File(root,"payloads");val referenced=File(directory,"${queue.snapshot().head!!.bodyId}.body")
            val orphan=File(directory,"${UUID.randomUUID()}.body").apply {writeText("orphan")}
            val staging=File(directory,"${UUID.randomUUID()}.tmp").apply {writeText("staging")}
            val foreign=File(directory,"readme.txt").apply {writeText("foreign")}
            coordinator.runNext();assertTrue(referenced.exists());assertFalse(orphan.exists());assertFalse(staging.exists());assertTrue(foreign.exists())
            queue.disable()
            try {owner().runNext();fail()} catch(_:IllegalStateException) {}
            assertEquals(1,server.requests.size);coordinator.reset();assertFalse(referenced.exists());assertTrue(foreign.exists())
        }
    }
    private var databaseName:String?=null
    private fun reopenDatabase() {
        db.close();db=HistoryDatabase.create(context,checkNotNull(databaseName))
        history=HistoryRepository(db,clock);queue=WebhookQueueRepository(db,clock);coordinator=owner()
    }
    private suspend fun persistentDatabase(block:suspend()->Unit) {
        databaseName="webhook-coordinator-${UUID.randomUUID()}.db";reopenDatabase()
        try {block()} finally {
            db.close();context.deleteDatabase(databaseName!!);databaseName=null
            db=HistoryDatabase.create(context,null);history=HistoryRepository(db,clock);queue=WebhookQueueRepository(db,clock)
        }
    }
    private class TestClock:Clock() {
        val start=Instant.parse("2026-10-27T12:00:00Z");@Volatile var now=start
        override fun instant()=now
        override fun getZone():ZoneId=ZoneOffset.UTC
        override fun withZone(zone:ZoneId):Clock=this
    }
}
