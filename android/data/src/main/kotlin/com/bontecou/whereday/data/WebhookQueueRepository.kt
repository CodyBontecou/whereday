package com.bontecou.whereday.data

import com.bontecou.whereday.domain.*
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.conflate
import kotlinx.coroutines.flow.map
import java.time.Clock
import java.time.Instant
import java.util.UUID

enum class WebhookQueueTransition { requireEmpty, retain, discard }
class WebhookQueueChanged:IllegalStateException("Webhook configuration changed. Old queued work was canceled.")
/** queuedPoints counts live buffered snapshots; manual file counts are carried by their batch rows. */
data class WebhookQueueSnapshot(val policy:WebhookPolicyRow?,val queuedPoints:Long,val batches:Long,val oldestUnclaimedAt:Instant?,val head:WebhookBatchRow?)
data class WebhookAttempt(val batch:WebhookBatchRow,val token:String)

/** Contains no endpoint or credentials. Every mutating operation fences its configuration revision. */
class WebhookQueueRepository(private val database:HistoryDatabase,private val clock:Clock=Clock.systemUTC()) {
    private val dao=database.webhooks()
    private fun uuid(value:String) {require(runCatching {UUID.fromString(value).toString()==value}.getOrDefault(false)) {"Invalid webhook identity."}}
    suspend fun configure(revision:String,enabled:Boolean,capture:Boolean,includeOutliers:Boolean,transition:WebhookQueueTransition=WebhookQueueTransition.requireEmpty)=database.writeTransaction {
        uuid(revision);require(!capture || enabled)
        val old=dao.policy()
        if(old?.revision==revision) {
            check(old.enabled==enabled && old.capture==capture && old.includeOutliers==includeOutliers) {"Use a new webhook revision when settings change."}
        } else {
            val change=if(!enabled) WebhookQueueTransition.discard else transition
            when(change) {
                WebhookQueueTransition.requireEmpty->check(dao.totalPoints()==0L && dao.totalBatches()==0L && dao.legacyCount()==0L) {"Choose whether to retain or discard pending webhook data before changing settings."}
                WebhookQueueTransition.discard->{dao.clearBatches();dao.clearPoints();dao.clearLegacy()}
                WebhookQueueTransition.retain->{
                    check(old!=null && dao.legacyCount()==0L && dao.totalPoints()==dao.pointCount(old.revision) && dao.totalBatches()==dao.batchCount(old.revision)) {"Pending webhook data needs review before transfer."}
                    dao.transferPoints(old.revision,revision);dao.transferBatches(old.revision,revision)
                    if(!includeOutliers) dao.removePendingOutliers(revision)
                }
            }
            dao.put(WebhookPolicyRow(revision=revision,enabled=enabled,capture=capture,includeOutliers=includeOutliers,createdAt=clock.instant(),lastSuccess=old?.lastSuccess,lastStatus=old?.lastStatus))
        }
    }
    suspend fun updateOutlierPolicy(revision:String,include:Boolean)=database.writeTransaction {
        val old=active(revision)
        if(!include) dao.removePendingOutliers(revision)
        dao.put(old.copy(includeOutliers=include))
    }
    suspend fun disable()=configure(UUID.randomUUID().toString(),false,false,false)
    private suspend fun active(revision:String):WebhookPolicyRow {
        val policy=dao.policy()
        if(policy?.revision!=revision || !policy.enabled) throw WebhookQueueChanged()
        return policy
    }
    fun observe()=database.invalidationTracker.createFlow("webhook_policy","webhook_points","webhook_batches").conflate().map {snapshot()}
    suspend fun snapshot():WebhookQueueSnapshot=database.readSnapshot {
        val policy=dao.policy();val revision=policy?.revision
        WebhookQueueSnapshot(policy,revision?.let {dao.pointCount(it)} ?: 0,revision?.let {dao.batchCount(it)} ?: 0,revision?.let {dao.oldestQueuedAt(it)},revision?.let {dao.head(it)})
    }
    /** Resumes the existing head first. A new claim freezes at most 500 accepted point snapshots. */
    suspend fun claim(revision:String,limit:Int=WebhookDeliveryRules.maxBatchPoints,minimum:Int=1,until:Instant?=null):WebhookBatchRow?=database.writeTransaction {
        active(revision);require(limit in 1..WebhookDeliveryRules.maxBatchPoints && minimum in 1..limit)
        dao.head(revision)?.let {return@writeTransaction it}
        if(dao.unclaimedCount(revision,until)<minimum) return@writeTransaction null
        val points=dao.unclaimed(revision,until,limit)
        if(points.size<minimum) return@writeTransaction null
        val batch=WebhookBatchRow(UUID.randomUUID().toString(),revision,clock.instant(),points.size.toLong())
        dao.put(batch)
        check(dao.assign(revision,points.map {it.value.id},batch.id)==points.size)
        batch
    }
    /** Freezes a complete time/explicit-flush window while retaining bounded 500-row reads. */
    suspend fun claimWindow(revision:String,until:Instant,requested:Boolean=false):WebhookBatchRow?=database.writeTransaction {
        active(revision)
        if(requested) dao.requestFlush(revision)
        val count=dao.unclaimedCount(revision,until)
        if(count==0L) return@writeTransaction dao.head(revision)
        val batch=WebhookBatchRow(UUID.randomUUID().toString(),revision,nextBatchAt(revision),count,kind=if(requested) "flush" else "points")
        dao.put(batch);dao.assignWindow(revision,until,batch.id);check(dao.batchPointCount(batch.id)==count)
        batch
    }
    /** Safe only after acquiring the exclusive OS delivery lock, so no older call can still run. */
    suspend fun recoverUnderExclusiveDeliveryLock(revision:String)=database.writeTransaction {
        val policy=active(revision)
        if(dao.recoverLeases(revision,clock.instant().plusSeconds(1),WebhookDeliveryRules.maxAttempts)>0) dao.put(policy.copy(lastStatus=0))
    }
    suspend fun stopBeforeAttempt(revision:String,batch:String)=database.writeTransaction {
        active(revision)
        val old=dao.batch(batch)?.takeIf {it.revision==revision} ?: throw WebhookQueueChanged()
        check(old.attemptToken==null);dao.put(old.copy(stopped=true,nextAttempt=null,lastStatus=0))
    }
    suspend fun forEachPoint(revision:String,batch:String,consume:suspend(TrackPoint)->Unit)=database.readSnapshot {
        active(revision)
        val row=dao.batch(batch) ?: throw WebhookQueueChanged()
        if(row.revision!=revision) throw WebhookQueueChanged()
        check(row.kind in setOf("points","flush")) {"Manual webhook payloads use their frozen file."}
        var seen=0L;var page=dao.firstBatchPage(batch)
        while(page.isNotEmpty()) {
            currentCoroutineContext().ensureActive()
            page.forEach {consume(it.value);seen++}
            val last=page.last().value;page=dao.nextBatchPage(batch,last.time,last.id)
        }
        check(seen==row.pointCount) {"Webhook batch is incomplete."}
    }
    /** Manual history payloads are already frozen; no historical point is added to live capture. */
    suspend fun enqueueManual(revision:String,bodyId:String,format:String,bytes:Long,sha256:String,points:Long,visits:Long):WebhookBatchRow=database.writeTransaction {
        active(revision);validateBody(bodyId,format,bytes,sha256)
        require(points>=0 && visits>=0 && (points>0 || visits>0)) {"Choose nonempty webhook data."}
        val old=dao.batch(bodyId)
        if(old!=null) {
            check(old.revision==revision && old.kind=="manual" && old.bodyId==bodyId && old.bodyFormat==format && old.bodyBytes==bytes && old.bodySha256==sha256 && old.pointCount==points && old.visitCount==visits) {"A frozen webhook body cannot change."}
            old
        } else WebhookBatchRow(bodyId,revision,nextBatchAt(revision),points,kind="manual",visitCount=visits,bodyId=bodyId,bodyFormat=format,bodyBytes=bytes,bodySha256=sha256).also {dao.put(it)}
    }
    private suspend fun nextBatchAt(revision:String):Instant=maxOf(clock.instant(),dao.latestBatchAt(revision)?.plusMillis(1) ?: clock.instant())
    private fun validateBody(bodyId:String,format:String,bytes:Long,sha256:String) {
        uuid(bodyId)
        require(format in setOf("json","csv","markdown","geojson","gpx","kml","owntracks","overland") && bytes>0 && sha256.matches(Regex("[0-9a-f]{64}"))) {"Invalid webhook payload metadata."}
    }
    /** File preparation must finish before an attempt is reservable. Frozen metadata cannot change. */
    suspend fun freeze(revision:String,batch:String,bodyId:String,format:String,bytes:Long,sha256:String):WebhookBatchRow=database.writeTransaction {
        active(revision);validateBody(bodyId,format,bytes,sha256)
        val old=dao.batch(batch)?.takeIf {it.revision==revision} ?: throw WebhookQueueChanged()
        if(old.bodyId!=null) {
            check(old.bodyId==bodyId && old.bodyFormat==format && old.bodyBytes==bytes && old.bodySha256==sha256) {"A frozen webhook body cannot change."}
            old
        } else {
            check(old.attemptToken==null && old.attempts==0)
            old.copy(bodyId=bodyId,bodyFormat=format,bodyBytes=bytes,bodySha256=sha256).also {dao.put(it)}
        }
    }
    /** The caller must also hold its process/file delivery lock and cancel stale in-flight calls. */
    suspend fun beginAttempt(revision:String,batch:String):WebhookAttempt?=database.writeTransaction {
        active(revision)
        val old=dao.batch(batch)?.takeIf {it.revision==revision} ?: return@writeTransaction null
        val now=clock.instant()
        if(old.bodyId==null || old.stopped || old.nextAttempt?.let {it>now}==true || old.leaseUntil?.let {it>now}==true) return@writeTransaction null
        if(old.attempts>=WebhookDeliveryRules.maxAttempts) {
            dao.put(old.copy(stopped=true,attemptToken=null,leaseUntil=null,nextAttempt=null,lastStatus=old.lastStatus ?: 0));return@writeTransaction null
        }
        val token=UUID.randomUUID().toString()
        val row=old.copy(attempts=old.attempts+1,attemptToken=token,leaseUntil=now.plusSeconds(WebhookDeliveryRules.leaseSeconds),nextAttempt=null)
        dao.put(row);WebhookAttempt(row,token)
    }
    /** Success removes only this batch. Retry/stop retain the same membership and body identity. */
    suspend fun finishAttempt(revision:String,batch:String,token:String,status:Int,retryAt:Instant?=null):Boolean=database.writeTransaction {
        require(status==0 || status in 100..999)
        val policy=dao.policy()
        if(policy?.revision!=revision || !policy.enabled) return@writeTransaction false
        val row=dao.batch(batch)
        if(row?.revision!=revision || row.attemptToken!=token) return@writeTransaction false
        if(status in 200..299) {
            require(retryAt==null)
            dao.deleteBatch(batch)
            dao.put(policy.copy(lastSuccess=clock.instant(),lastStatus=status))
        } else {
            require(retryAt==null || WebhookDeliveryRules.retryable(status))
            val retry=retryAt?.takeIf {row.attempts<WebhookDeliveryRules.maxAttempts}
            dao.put(row.copy(attemptToken=null,leaseUntil=null,lastStatus=status,nextAttempt=retry?.let {maxOf(it,clock.instant())},stopped=retry==null))
            dao.put(policy.copy(lastStatus=status))
        }
        true
    }
    suspend fun retryStopped(revision:String,batch:String)=database.writeTransaction {
        active(revision)
        val old=dao.batch(batch)?.takeIf {it.revision==revision} ?: throw WebhookQueueChanged()
        check(old.stopped && old.attemptToken==null)
        dao.put(old.copy(stopped=false,attempts=0,nextAttempt=clock.instant(),lastStatus=null))
    }
    suspend fun discard(revision:String,batch:String)=database.writeTransaction {
        active(revision)
        val row=dao.batch(batch)?.takeIf {it.revision==revision} ?: throw WebhookQueueChanged()
        check(row.attemptToken==null || row.leaseUntil?.let {it<=clock.instant()}==true) {"Cancel active webhook delivery first."}
        dao.deleteBatch(row.id)
    }
}
