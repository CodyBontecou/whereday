package com.bontecou.whereday.data

import androidx.room.useWriterConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WebhookQueueTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db:HistoryDatabase
    private lateinit var history:HistoryRepository
    private lateinit var queue:WebhookQueueRepository
    private val clock=TestClock()
    private lateinit var revision:String
    @Before fun setup() {check(context.packageName=="com.bontecou.whereday.data.test");db=HistoryDatabase.create(context,null);history=HistoryRepository(db,clock);queue=WebhookQueueRepository(db,clock);revision=UUID.randomUUID().toString()}
    @After fun cleanup() {db.close()}
    private fun point(n:Int,coordinate:Coordinate=Coordinate(0.0,0.0))=TrackPoint(id="point-$n",coordinate=coordinate,time=clock.start.plusSeconds(n.toLong()),accuracy=5.0,altitude=10.0,speed=1.0,countryCode="GH",timeZone="Africa/Accra")
    private suspend fun enabled(include:Boolean=false) {queue.configure(revision,true,true,include);history.start(0);clock.now=clock.start.plusSeconds(10000)}
    private suspend fun captured(count:Int):WebhookBatchRow {enabled();history.ingest((1..count).map {point(it)});return queue.claim(revision)!!}
    private suspend fun frozen(batch:WebhookBatchRow):WebhookBatchRow=queue.freeze(revision,batch.id,UUID.randomUUID().toString(),"json",123,"a".repeat(64))
    private suspend fun values(batch:WebhookBatchRow):List<TrackPoint> =buildList {queue.forEachPoint(revision,batch.id) {add(it)}}
    @Test fun defaultOffAndManualDoNotCaptureImportsOrHistoricalPointsAndOnlyAcceptedNewFixesQueue():Unit=runBlocking {
        history.start(0);clock.now=clock.start.plusSeconds(10000);history.ingest(listOf(point(1)))
        assertEquals(0L,queue.snapshot().queuedPoints);assertNull(queue.snapshot().policy)
        queue.configure(revision,true,false,false);history.ingest(listOf(point(2)))
        assertEquals(0L,queue.snapshot().queuedPoints)
        revision=UUID.randomUUID().toString();queue.configure(revision,true,true,false)
        db.history().put(PointRow(point(3),null)) // Imported history is not a live accepted fix.
        history.ingest(listOf(point(3),point(4).copy(accuracy=200.0),point(5),point(5)))
        assertEquals(1L,queue.snapshot().queuedPoints)
        val batch=queue.claim(revision)!!;assertEquals(listOf(point(5)),values(batch))
        assertEquals(db.history().point("point-5")!!.value,values(batch).single())
    }
    @Test fun captureAndRouteStateCommitTogetherAndFailedCaptureCanBeRetried():Unit=runBlocking {
        enabled()
        db.useWriterConnection {it.usePrepared("CREATE TRIGGER queue_fail BEFORE INSERT ON webhook_points BEGIN SELECT RAISE(ABORT,'synthetic queue failure'); END") {statement->statement.step()}}
        try {history.ingest(listOf(point(1)));fail()} catch(_:androidx.sqlite.SQLiteException) {}
        assertNull(db.history().point("point-1"));assertNull(db.history().recording()!!.lastFix);assertEquals(0L,queue.snapshot().queuedPoints)
        db.useWriterConnection {it.usePrepared("DROP TRIGGER queue_fail") {statement->statement.step()}}
        history.ingest(listOf(point(1),point(1)))
        assertEquals(1L,queue.snapshot().queuedPoints);assertEquals(point(1).time,db.history().recording()!!.lastFix)
    }
    @Test fun claimThresholdCutoffAndBoundedMembershipRemainStableAfterHistoryEdits():Unit=runBlocking {
        enabled();history.ingest((1..1001).map {point(it)})
        assertNull(queue.claim(revision,limit=100,minimum=100,until=clock.start))
        val batch=queue.claim(revision)!!;assertEquals(500L,batch.pointCount)
        assertEquals(batch.id,queue.claim(revision,limit=5)!!.id)
        val before=values(batch)
        assertEquals(500,before.size);assertEquals(point(1),before.first());assertEquals(point(500),before.last())
        db.history().put(PointRow(point(1).copy(coordinate=Coordinate(1.0,1.0)),null))
        assertEquals(before,values(batch));assertEquals(1001L,queue.snapshot().queuedPoints)
        assertNull(queue.beginAttempt(revision,batch.id))
        val ready=frozen(batch);assertEquals(ready,queue.freeze(revision,batch.id,ready.bodyId!!,"json",123,"a".repeat(64)))
        try {queue.freeze(revision,batch.id,ready.bodyId!!,"csv",123,"a".repeat(64));fail()} catch(_:IllegalStateException) {}
        try {queue.freeze(revision,batch.id,"../outside","json",123,"a".repeat(64));fail()} catch(_:IllegalArgumentException) {}
        val attempt=queue.beginAttempt(revision,batch.id)!!
        assertTrue(queue.finishAttempt(revision,batch.id,attempt.token,204))
        assertEquals(501L,queue.snapshot().queuedPoints);assertEquals(1001L,db.history().totalPointCount())
        val next=queue.claim(revision,limit=100,minimum=100)!!;assertEquals(100L,next.pointCount);assertEquals(point(501),values(next).first())
    }
    @Test fun concurrentRepositoriesClaimAndReserveOnlyOneHeadAndLease():Unit=runBlocking {
        enabled();history.ingest((1..10).map {point(it)})
        val other=WebhookQueueRepository(db,clock)
        val claims=coroutineScope {(0..7).map {async(Dispatchers.Default) {if(it%2==0) queue.claim(revision)!! else other.claim(revision)!!}}.awaitAll()}
        assertEquals(1,claims.map {it.id}.distinct().size);assertEquals(1L,queue.snapshot().batches)
        frozen(claims.first())
        val attempts=coroutineScope {(0..7).map {async(Dispatchers.Default) {if(it%2==0) queue.beginAttempt(revision,claims.first().id) else other.beginAttempt(revision,claims.first().id)}}.awaitAll()}
        assertEquals(1,attempts.filterNotNull().size);assertEquals(1,db.webhooks().head(revision)!!.attempts)
    }
    @Test fun retriesKeepBodyIdentityAndOnlyMatchingTokensCanAcknowledge():Unit=runBlocking {
        val ready=frozen(captured(2));val body=ready.bodyId
        val first=queue.beginAttempt(revision,ready.id)!!
        assertFalse(queue.finishAttempt(revision,ready.id,"stale-token",204))
        assertTrue(queue.finishAttempt(revision,ready.id,first.token,503,clock.now.plusSeconds(30)))
        assertNull(queue.beginAttempt(revision,ready.id));clock.now=clock.now.plusSeconds(30)
        val second=queue.beginAttempt(revision,ready.id)!!;assertEquals(body,second.batch.bodyId);assertEquals(2,second.batch.attempts)
        assertFalse(queue.finishAttempt(revision,ready.id,first.token,204))
        assertTrue(queue.finishAttempt(revision,ready.id,second.token,429,clock.now.plusSeconds(1)));clock.now=clock.now.plusSeconds(1)
        val third=queue.beginAttempt(revision,ready.id)!!
        assertTrue(queue.finishAttempt(revision,ready.id,third.token,500,clock.now.plusSeconds(2)))
        val stopped=db.webhooks().batch(ready.id)!!;assertTrue(stopped.stopped);assertEquals(3,stopped.attempts);assertEquals(body,stopped.bodyId)
        assertEquals(2L,queue.snapshot().queuedPoints);assertNull(queue.beginAttempt(revision,ready.id))
        queue.retryStopped(revision,ready.id)
        val manual=queue.beginAttempt(revision,ready.id)!!;assertEquals(body,manual.batch.bodyId);assertEquals(1,manual.batch.attempts)
        assertTrue(queue.finishAttempt(revision,ready.id,manual.token,201));assertEquals(0L,queue.snapshot().queuedPoints)
        assertEquals(clock.now,queue.snapshot().policy!!.lastSuccess);assertEquals(2L,db.history().totalPointCount())
        history.ingest(listOf(point(2).copy(time=clock.start.plusSeconds(500))))
        assertEquals(0L,queue.snapshot().queuedPoints);assertEquals(2L,db.history().totalPointCount())
    }
    @Test fun expiredLeaseCanRecoverButLateCompletionCannotDeleteTheNewAttempt():Unit=runBlocking {
        val batch=frozen(captured(1));val old=queue.beginAttempt(revision,batch.id)!!
        clock.now=clock.now.plusSeconds(WebhookDeliveryRules.leaseSeconds-1);assertNull(queue.beginAttempt(revision,batch.id))
        clock.now=clock.now.plusSeconds(1);val recovered=WebhookQueueRepository(db,clock).beginAttempt(revision,batch.id)!!
        assertNotEquals(old.token,recovered.token);assertFalse(queue.finishAttempt(revision,batch.id,old.token,200))
        assertEquals(1L,queue.snapshot().queuedPoints);assertTrue(queue.finishAttempt(revision,batch.id,recovered.token,200))
    }
    @Test fun changingRevisionOrDisablingClearsQueuesAndRejectsLateWorkWithoutDeletingHistory():Unit=runBlocking {
        val batch=frozen(captured(2));val active=queue.beginAttempt(revision,batch.id)!!
        val old=revision;revision=UUID.randomUUID().toString();queue.configure(revision,true,true,false,WebhookQueueTransition.discard)
        assertEquals(0L,queue.snapshot().queuedPoints);assertEquals(0L,db.webhooks().totalPoints());assertEquals(0L,db.webhooks().totalBatches());assertFalse(queue.finishAttempt(old,batch.id,active.token,200))
        try {queue.claim(old);fail()} catch(_:WebhookQueueChanged) {}
        history.ingest(listOf(point(3)));assertEquals(1L,queue.snapshot().queuedPoints)
        queue.disable();history.ingest(listOf(point(4)))
        assertEquals(0L,queue.snapshot().queuedPoints);assertFalse(queue.snapshot().policy!!.enabled);assertEquals(4L,db.history().totalPointCount())
        try {queue.enqueueManual(old,UUID.randomUUID().toString(),"json",123,"b".repeat(64),1,0);fail()} catch(_:WebhookQueueChanged) {}
    }
    @Test fun outlierCorrectionsAffectOnlyUnclaimedSnapshotsAndFilterChangesNeverBackfillHistory():Unit=runBlocking {
        enabled(true)
        val a=point(1);val spike=point(100).copy(coordinate=Coordinate(0.0,0.002));val returned=point(200)
        history.ingest(listOf(a,spike));assertEquals(2L,queue.snapshot().queuedPoints)
        history.ingest(listOf(returned));assertTrue(db.history().point(spike.id)!!.value.outlier)
        queue.updateOutlierPolicy(revision,false);assertEquals(2L,queue.snapshot().queuedPoints)
        val batch=queue.claim(revision)!!;assertEquals(listOf(a,returned),values(batch))
        queue.updateOutlierPolicy(revision,true);assertEquals(2L,queue.snapshot().queuedPoints)
        // A later spike correction cannot change a claimed request's snapshots.
        val b=point(300).copy(coordinate=Coordinate(0.0,0.002));history.ingest(listOf(b))
        val attempt=queue.beginAttempt(revision,frozen(batch).id)!!;queue.finishAttempt(revision,batch.id,attempt.token,204)
        val claimed=queue.claim(revision)!!;val before=values(claimed)
        history.ingest(listOf(point(400)));assertTrue(db.history().point(b.id)!!.value.outlier)
        assertEquals(before,values(claimed));assertFalse(values(claimed).single().outlier)
    }
    @Test fun pendingChangesNeedAnExplicitChoiceAndRetentionKeepsBodyKeysWhileInvalidatingOldAttempts():Unit=runBlocking {
        val batch=frozen(captured(2));val oldAttempt=queue.beginAttempt(revision,batch.id)!!
        val old=revision;val next=UUID.randomUUID().toString()
        try {queue.configure(next,true,true,false);fail()} catch(_:IllegalStateException) {}
        assertEquals(old,queue.snapshot().policy!!.revision);assertEquals(2L,queue.snapshot().queuedPoints)
        queue.configure(next,true,true,false,WebhookQueueTransition.retain);revision=next
        val retained=queue.snapshot().head!!
        assertEquals(batch.bodyId,retained.bodyId);assertEquals(batch.bodySha256,retained.bodySha256);assertNull(retained.attemptToken)
        assertEquals(listOf(point(1),point(2)),values(retained))
        assertFalse(queue.finishAttempt(old,batch.id,oldAttempt.token,200))
        assertFalse(queue.finishAttempt(next,batch.id,oldAttempt.token,200))
        val attempt=queue.beginAttempt(next,batch.id)!!;assertEquals(2,attempt.batch.attempts)
        assertTrue(queue.finishAttempt(next,batch.id,attempt.token,200));assertEquals(0L,queue.snapshot().queuedPoints)
    }
    @Test fun excludedOutliersAreRemovedBeforeClaimAndImmediateBadFixesNeverQueue():Unit=runBlocking {
        enabled(false)
        val first=point(1);val spike=point(100).copy(coordinate=Coordinate(0.0,0.002));val returned=point(200)
        history.ingest(listOf(first,spike,returned))
        assertTrue(db.history().point(spike.id)!!.value.outlier)
        val batch=queue.claim(revision)!!;assertEquals(listOf(first,returned),values(batch))
        val immediate=point(201).copy(coordinate=Coordinate(1.0,1.0));history.ingest(listOf(immediate))
        assertTrue(db.history().point(immediate.id)!!.value.outlier);assertEquals(2L,queue.snapshot().queuedPoints);assertEquals(4L,db.history().totalPointCount())
    }
    @Test fun repeatedInterruptedLeasesStopAtThreeWithoutLosingTheFrozenPayload():Unit=runBlocking {
        val batch=frozen(captured(1));var lastToken=""
        repeat(WebhookDeliveryRules.maxAttempts) {index->
            val attempt=queue.beginAttempt(revision,batch.id)!!;lastToken=attempt.token;assertEquals(index+1,attempt.batch.attempts)
            clock.now=clock.now.plusSeconds(WebhookDeliveryRules.leaseSeconds)
        }
        assertNull(queue.beginAttempt(revision,batch.id));val stopped=db.webhooks().batch(batch.id)!!
        assertTrue(stopped.stopped);assertEquals(batch.bodyId,stopped.bodyId);assertEquals(1L,queue.snapshot().queuedPoints)
        assertFalse(queue.finishAttempt(revision,batch.id,lastToken,204))
        queue.retryStopped(revision,batch.id);assertEquals(1,queue.beginAttempt(revision,batch.id)!!.batch.attempts)
    }
    @Test fun manualPayloadsDoNotCaptureHistoryAndPermanentFailuresNeedExplicitRetryOrDiscard():Unit=runBlocking {
        queue.configure(revision,true,false,false)
        val id=UUID.randomUUID().toString()
        val batch=queue.enqueueManual(revision,id,"json",900,"b".repeat(64),1234,42)
        assertEquals(batch,queue.enqueueManual(revision,id,"json",900,"b".repeat(64),1234,42))
        assertEquals(1234L,batch.pointCount);assertEquals(42L,batch.visitCount);assertEquals(0L,queue.snapshot().queuedPoints)
        val attempt=queue.beginAttempt(revision,id)!!
        try {queue.discard(revision,id);fail()} catch(_:IllegalStateException) {}
        queue.finishAttempt(revision,id,attempt.token,401)
        assertTrue(db.webhooks().batch(id)!!.stopped);assertNull(queue.beginAttempt(revision,id))
        queue.discard(revision,id);assertEquals(0L,queue.snapshot().batches)
    }
    @Test fun failedClaimRollsBackMembershipAndHeadersAndClearCannotLeaveCapturingEnabled():Unit=runBlocking {
        enabled();history.ingest(listOf(point(1)))
        db.useWriterConnection {it.usePrepared("CREATE TRIGGER assign_fail BEFORE UPDATE OF batchId ON webhook_points BEGIN SELECT RAISE(ABORT,'synthetic assignment failure'); END") {s->s.step()}}
        try {queue.claim(revision);fail()} catch(_:androidx.sqlite.SQLiteException) {}
        assertEquals(0L,queue.snapshot().batches);assertEquals(1L,db.webhooks().unclaimedCount(revision,null))
        withContext(Dispatchers.IO) {db.clearAllTables()}
        assertNull(queue.snapshot().policy);assertEquals(0L,queue.snapshot().queuedPoints);assertEquals(0L,db.webhooks().totalPoints());assertEquals(0L,db.webhooks().totalBatches())
    }
    @Test fun disableWaitsForTheRecordingCommitAndReadersNeverSeeHalfACapture():Unit=runBlocking {
        db.close();val name="webhook-atomic-${UUID.randomUUID()}.db"
        try {
            db=HistoryDatabase.create(context,name);history=HistoryRepository(db,clock);queue=WebhookQueueRepository(db,clock);enabled()
            val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
            val reads=java.util.concurrent.atomic.AtomicInteger()
            clock.onRead={if(reads.incrementAndGet()==2) {entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS))}}
            coroutineScope {
                val ingest=async(Dispatchers.IO) {history.ingest(listOf(point(1)))}
                var disable:Deferred<Unit>?=null
                try {
                    assertTrue(withContext(Dispatchers.IO) {entered.await(5,java.util.concurrent.TimeUnit.SECONDS)})
                    val started=CompletableDeferred<Unit>()
                    disable=async(Dispatchers.Default) {started.complete(Unit);queue.disable()}
                    started.await()
                    val visible=withTimeout(5000) {db.readSnapshot {dao->dao.totalPointCount() to db.webhooks().pointCount(revision)}}
                    assertEquals(0L to 0L,visible);assertFalse(disable.isCompleted)
                    release.countDown();withTimeout(5000) {ingest.await();disable.await()}
                    assertEquals(1L,db.history().totalPointCount());assertEquals(0L,queue.snapshot().queuedPoints);assertEquals(0L,db.webhooks().totalPoints());assertEquals(0L,db.webhooks().totalBatches());assertFalse(queue.snapshot().policy!!.enabled)
                } finally {release.countDown();ingest.cancelAndJoin();disable?.cancelAndJoin()}
            }
        } finally {clock.onRead={};db.close();context.deleteDatabase(name);db=HistoryDatabase.create(context,null)}
    }
    @Test fun databaseRecreationPreservesCapturedMembershipPayloadAndRetryDeadline():Unit=runBlocking {
        db.close();val name="webhook-reopen-${UUID.randomUUID()}.db"
        try {
            db=HistoryDatabase.create(context,name);history=HistoryRepository(db,clock);queue=WebhookQueueRepository(db,clock)
            val ready=frozen(captured(3));val attempt=queue.beginAttempt(revision,ready.id)!!
            queue.finishAttempt(revision,ready.id,attempt.token,503,clock.now.plusSeconds(30))
            val expected=values(ready);db.close()
            db=HistoryDatabase.create(context,name);history=HistoryRepository(db,clock);queue=WebhookQueueRepository(db,clock)
            val loaded=queue.snapshot().head!!
            assertEquals(ready.bodyId,loaded.bodyId);assertEquals(ready.bodySha256,loaded.bodySha256);assertEquals(1,loaded.attempts)
            assertEquals(expected,values(loaded));assertNull(queue.beginAttempt(revision,loaded.id))
            clock.now=clock.now.plusSeconds(30);assertNotNull(queue.beginAttempt(revision,loaded.id))
        } finally {db.close();context.deleteDatabase(name);db=HistoryDatabase.create(context,null)}
    }
    @Test fun completeWindowClaimsMoreThanAPageAndFlushPreservesFifoWithAnExistingHead():Unit=runBlocking {
        enabled();history.ingest((1..1201).map {point(it)})
        val cutoff=clock.now
        clock.now=clock.now.plusSeconds(1);history.ingest(listOf(point(1202)))
        val window=queue.claimWindow(revision,cutoff)!!
        assertEquals(1201L,window.pointCount);assertEquals(1201,values(window).size)
        assertEquals(point(1201),values(window).last());assertEquals(1L,db.webhooks().unclaimedCount(revision,null))
        // Even a clock moving backward cannot put the newer explicit flush ahead of its old head.
        clock.now=cutoff
        val flushed=queue.claimWindow(revision,cutoff.plusSeconds(1),requested=true)!!
        assertEquals(1L,flushed.pointCount);assertTrue(flushed.createdAt>window.createdAt)
        assertEquals(window.id,queue.snapshot().head!!.id);assertEquals("flush",queue.snapshot().head!!.kind)
        assertEquals(listOf(point(1202)),values(flushed))
        assertEquals(1202L,db.history().totalPointCount())
    }
    @Test fun exclusiveRecoveryInvalidatesUnknownCompletionsAndStopsAtTheAttemptLimit():Unit=runBlocking {
        val batch=frozen(captured(1));val body=batch.bodyId
        repeat(3) {index->
            val attempt=queue.beginAttempt(revision,batch.id)!!
            queue.recoverUnderExclusiveDeliveryLock(revision)
            assertFalse(queue.finishAttempt(revision,batch.id,attempt.token,204))
            val recovered=queue.snapshot().head!!
            assertNull(recovered.attemptToken);assertNull(recovered.leaseUntil);assertEquals(body,recovered.bodyId)
            assertEquals(index==2,recovered.stopped)
            if(index<2) {assertEquals(clock.now.plusSeconds(1),recovered.nextAttempt);clock.now=clock.now.plusSeconds(1)}
        }
        assertNull(queue.beginAttempt(revision,batch.id));assertEquals(1L,queue.snapshot().queuedPoints)
    }
    @Test fun preparationFailureRetainsMembershipAndFrozenFileReferencesUntilExplicitDiscard():Unit=runBlocking {
        val batch=frozen(captured(2));assertTrue(db.webhooks().referencesBody(batch.bodyId!!))
        queue.stopBeforeAttempt(revision,batch.id)
        assertTrue(queue.snapshot().head!!.stopped);assertEquals(0,queue.snapshot().head!!.attempts)
        assertEquals(2,values(batch).size);assertNull(queue.beginAttempt(revision,batch.id))
        queue.discard(revision,batch.id);assertFalse(db.webhooks().referencesBody(batch.bodyId!!));assertEquals(2L,db.history().totalPointCount())
    }
    private class TestClock:Clock() {
        val start=Instant.parse("2026-10-27T12:00:00Z");@Volatile var now=start
        @Volatile var onRead:()->Unit={}
        override fun instant():Instant {onRead();return now}
        override fun getZone():ZoneId=ZoneOffset.UTC
        override fun withZone(zone:ZoneId):Clock=this
    }
}
