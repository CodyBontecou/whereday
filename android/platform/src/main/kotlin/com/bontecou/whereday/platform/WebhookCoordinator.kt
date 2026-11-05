package com.bontecou.whereday.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import com.bontecou.whereday.billing.Capability
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.nio.channels.OverlappingFileLockException
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

sealed interface WebhookRun {
    data object Disabled:WebhookRun
    data class Waiting(val until:Instant?=null):WebhookRun
    data object NeedsAttention:WebhookRun
    data class Delivered(val status:Int):WebhookRun
}
class WebhookConfigurationChanged:IllegalStateException("Webhook settings changed. Reload them before saving.")
data class WebhookState(val configuration:WebhookConfiguration,val queue:WebhookQueueSnapshot)
/** Opaque scheduling identity; never contains an endpoint, credential or raw history identifier. */
data class WebhookWorkPlan(val revision:String,val key:String,val at:Instant)

/** All configuration and egress share a process mutex and an OS lock. No worker may bypass this owner. */
class WebhookCoordinator internal constructor(
    private val database:HistoryDatabase,private val directory:File,private val store:WebhookSettingsStore,
    private val authorize:suspend(Capability)->Unit,private val canRecord:()->Boolean,
    private val clock:Clock=Clock.systemUTC(),private val delivery:WebhookDelivery=WebhookDelivery(authorize),
    private val displayLocale:()->java.util.Locale={java.util.Locale.getDefault()},
    private val checkpoint:suspend(String)->Unit={},
) {
    constructor(context:Context,database:HistoryDatabase,authorize:suspend(Capability)->Unit):this(
        database,File(context.filesDir,"webhooks"),WebhookSettingsStore(context),authorize,{
            listOf(Manifest.permission.ACCESS_FINE_LOCATION,Manifest.permission.ACCESS_COARSE_LOCATION).any {
                ContextCompat.checkSelfPermission(context,it)==PackageManager.PERMISSION_GRANTED
            }
        },displayLocale={AndroidHistoryLocale.capture(context)})
    private val queue=WebhookQueueRepository(database,clock)
    private val payloads=File(directory,"payloads")
    private val shared=owners.getOrPut(directory.canonicalPath) {Owner()}
    private class Owner {
        val mutex=Mutex();val epoch=AtomicLong();val inhibited=AtomicBoolean()
        val deliveries=ConcurrentHashMap.newKeySet<Job>()
    }
    private suspend fun <T> exclusive(block:suspend()->T):T=withContext(Dispatchers.IO) {
        shared.mutex.withLock {
            check(directory.mkdirs() || directory.isDirectory)
            RandomAccessFile(File(directory,"delivery.lock"),"rw").channel.use {channel->
                var lock:java.nio.channels.FileLock?=null
                while(lock==null) {
                    currentCoroutineContext().ensureActive()
                    lock=try {channel.tryLock()} catch(_:OverlappingFileLockException) {null}
                    if(lock==null) delay(25)
                }
                try {currentCoroutineContext().ensureActive();block()} finally {lock.release()}
            }
        }
    }
    private suspend fun <T> mutation(block:suspend()->T):T {
        val ticket=synchronized(shared) {shared.inhibited.set(true);shared.epoch.incrementAndGet()}
        val caller=currentCoroutineContext()[Job]
        shared.deliveries.toList().filter {it!==caller}.forEach {it.cancel(CancellationException("Webhook settings changed."))}
        shared.deliveries.toList().filter {it!==caller}.joinAll()
        return exclusive {
            try {checkCurrent(ticket);block().also {checkCurrent(ticket)}}
            finally {synchronized(shared) {if(shared.epoch.get()==ticket) shared.inhibited.set(false)}}
        }
    }
    private fun checkCurrent(ticket:Long) {if(shared.epoch.get()!=ticket) throw CancellationException("Webhook settings changed.")}
    private suspend fun <T> sending(block:suspend(Long)->T):T=withContext(Dispatchers.IO) {
        val ticket=shared.epoch.get();val job=currentCoroutineContext().job
        shared.deliveries.add(job)
        try {exclusive {
            job.ensureActive();checkCurrent(ticket)
            check(!shared.inhibited.get()) {"Save or reset webhook settings before sending."}
            block(ticket)
        }} finally {shared.deliveries.remove(job)}
    }
    private fun consistent(config:WebhookConfiguration,snapshot:WebhookQueueSnapshot) {
        val policy=snapshot.policy
        val unconfigured=config.revision.isEmpty() && (policy==null || !policy.enabled) && snapshot.queuedPoints==0L && snapshot.batches==0L
        check(unconfigured || (policy?.revision==config.revision && policy.enabled==config.settings.enabled && policy.capture==(config.settings.enabled && config.settings.mode!=WebhookMode.manual))) {"Webhook settings and queued work need review. Delivery remains stopped."}
    }
    /** The encrypted journal is written first; Room is applied idempotently, then the journal commits. */
    private suspend fun recover():WebhookConfiguration {
        val doc=store.document();val pending=doc.pending ?: return doc.active.also {consistent(it,queue.snapshot())}
        val next=pending.configuration;val snapshot=queue.snapshot()
        if(snapshot.policy?.revision!=next.revision) {
            consistent(doc.active,snapshot)
            check(doc.active.revision==pending.previousRevision)
            queue.configure(next.revision,next.settings.enabled,next.settings.enabled && next.settings.mode!=WebhookMode.manual,pending.includeOutliers,pending.transition)
            checkpoint("settings-room-applied")
        }
        consistent(next,queue.snapshot())
        return store.commit(next.revision)
    }
    suspend fun state():WebhookState=exclusive {val config=recover();WebhookState(config,queue.snapshot())}
    /** A read-only plan. A worker still rechecks revision, access and permissions under the delivery lock. */
    suspend fun nextWork():WebhookWorkPlan?=exclusive {
        val config=recover();if(!config.settings.enabled) return@exclusive null
        val snapshot=queue.snapshot();val revision=config.revision
        val head=if(config.settings.mode==WebhookMode.manual) database.webhooks().explicitHead(revision) else snapshot.head
        val now=clock.instant()
        val identity:String
        val at:Instant
        if(config.settings.mode!=WebhookMode.manual && !canRecord()) {identity="permission";at=now}
        else if(head!=null) {
            if(head.stopped) return@exclusive null
            identity="batch|${head.id}|${head.attempts}"
            at=if(head.attemptToken!=null) now else head.nextAttempt ?: now
        } else {
            val first=database.webhooks().unclaimed(revision,null,1).firstOrNull() ?: return@exclusive null
            when(config.settings.mode) {
                WebhookMode.manual->return@exclusive null
                WebhookMode.batchCount->if(database.webhooks().unclaimedCount(revision,null)<config.settings.batchCount) return@exclusive null
                else->Unit
            }
            at=if(config.settings.mode==WebhookMode.batchTime) batchDue(config,snapshot) ?: return@exclusive null else now
            identity="points|${first.value.id}|${if(config.settings.mode==WebhookMode.batchTime) at.toEpochMilli() else 0}"
        }
        val key=java.security.MessageDigest.getInstance("SHA-256").digest("$revision|$identity".toByteArray(Charsets.UTF_8)).joinToString("") {"%02x".format(it.toInt() and 255)}
        WebhookWorkPlan(revision,key,at)
    }
    private fun batchDue(config:WebhookConfiguration,snapshot:WebhookQueueSnapshot):Instant? {
        val interval=config.settings.batchMinutes*60L
        return snapshot.oldestUnclaimedAt?.let {oldest->
            val anchor=checkNotNull(snapshot.policy).createdAt
            val elapsed=java.time.Duration.between(anchor,oldest).seconds.coerceAtLeast(0)
            anchor.plusSeconds((elapsed/interval+1)*interval)
        }
    }
    suspend fun configure(settings:WebhookSettings,credentials:WebhookCredentials,includeOutliers:Boolean=false,transition:WebhookQueueTransition=WebhookQueueTransition.requireEmpty,expectedRevision:String?=null):WebhookConfiguration=mutation {
        val previous=recover()
        if(expectedRevision!=null && expectedRevision!=previous.revision) throw WebhookConfigurationChanged()
        if(settings.enabled) {
            authorize(Capability.webhook)
            check(settings.mode==WebhookMode.manual || canRecord()) {"Allow location access before enabling automatic webhooks."}
        }
        if(transition==WebhookQueueTransition.retain) check(previous.settings.format==settings.format) {"Discard pending webhook data before changing its format."}
        val pending=store.prepare(previous.revision,settings,credentials,transition,includeOutliers)
        checkpoint("settings-prepared")
        try {recover().also {collectOrphans()}} catch(e:Exception) {
            // A committed Room change must keep its journal so the next process can finish it.
            withContext(NonCancellable) {if(queue.snapshot().policy?.revision!=pending.configuration.revision) store.abort(pending.configuration.revision)}
            throw e
        }
    }
    suspend fun disable():WebhookConfiguration=mutation {
        // Disabling must also work after a prepared change that could not transfer pending work.
        store.document().pending?.let {pending->
            if(queue.snapshot().policy?.revision==pending.configuration.revision) store.commit(pending.configuration.revision)
            else store.abort(pending.configuration.revision)
        }
        val previous=recover()
        store.prepare(previous.revision,previous.settings.copy(enabled=false),previous.credentials,WebhookQueueTransition.discard,false)
        recover().also {collectOrphans()}
    }
    /** Also works when the encryption key is lost; this never edits personal history. */
    suspend fun reset()=clearAll {}
    suspend fun clearAll(clear:suspend()->Unit)=mutation {queue.disable();store.clear();collectOrphans();clear()}
    suspend fun permissionsChanged() {
        if(canRecord()) return
        val document=store.document();val proposed=document.pending?.configuration ?: document.active
        if(!proposed.settings.enabled || proposed.settings.mode==WebhookMode.manual) return
        mutation {
            val config=recover()
            if(config.settings.enabled && config.settings.mode!=WebhookMode.manual && !canRecord()) {
                store.prepare(config.revision,config.settings.copy(enabled=false),config.credentials,WebhookQueueTransition.discard,false)
                recover();collectOrphans()
            }
        }
    }
    suspend fun updateOutlierPolicy(include:Boolean)=exclusive {
        val config=recover()
        val policy=queue.snapshot().policy
        if(config.settings.enabled && policy?.includeOutliers!=include) queue.updateOutlierPolicy(config.revision,include)
    }
    suspend fun retry(batch:String)=mutation {val config=recover();queue.recoverUnderExclusiveDeliveryLock(config.revision);queue.retryStopped(config.revision,batch)}
    suspend fun discard(batch:String)=mutation {val config=recover();queue.recoverUnderExclusiveDeliveryLock(config.revision);queue.discard(config.revision,batch);collectOrphans()}
    suspend fun flush()=mutation {
        val config=recover();check(config.settings.enabled);authorize(Capability.webhook)
        queue.claimWindow(config.revision,clock.instant(),requested=true)
    }
    /** Explicit history send takes one read snapshot and freezes its bytes before queueing it. */
    suspend fun enqueueHistory(options:ExportOptions=ExportOptions()):String=sending {ticket->
        val config=recover();check(config.settings.enabled);authorize(Capability.webhook)
        val payload=database.readSnapshot {dao->WebhookPayload.create(payloads,config.settings.format,SnapshotExportSource(dao,options),options,clock.instant(),displayLocale())}
        try {
            checkCurrent(ticket)
            queue.enqueueManual(config.revision,payload.id,payload.format.name,payload.bytes,payload.sha256,payload.counts.points,payload.counts.visits)
            payload.id
        } catch(e:Exception) {payload.file.delete();throw e}
    }
    /** An explicit test uses a synthetic point, without enabling capture or reading saved history. */
    suspend fun testConnection():WebhookHttpResult=sending {ticket->
        val saved=recover();authorize(Capability.webhook)
        val config=saved.copy(settings=saved.settings.copy(enabled=true))
        WebhookProtocol.request(config.settings,config.credentials,"connection-test-validation")
        val source=object:ExportRecordSource {
            override suspend fun visits(consume:suspend(Visit)->Unit) {}
            override suspend fun points(consume:suspend(TrackPoint)->Unit) {consume(TrackPoint(id="webhook-connection-test",coordinate=Coordinate(0.0,0.0),time=clock.instant(),accuracy=0.0))}
        }
        val payload=WebhookPayload.create(payloads,config.settings.format,source,ExportOptions(kind=DataKind.points,zone=ZoneOffset.UTC),clock.instant(),displayLocale())
        val job=currentCoroutineContext().job
        try {delivery.send(config,payload) {job.isActive && shared.epoch.get()==ticket && !shared.inhibited.get()}}
        finally {payload.file.delete()}
    }
    /** Processes one attempt. WorkManager/foreground controllers schedule the returned due time. */
    suspend fun runNext(expectedRevision:String?=null):WebhookRun=sending {ticket->
        var config=recover()
        if(expectedRevision!=null && expectedRevision!=config.revision) return@sending WebhookRun.Disabled
        if(!config.settings.enabled) return@sending WebhookRun.Disabled
        if(config.settings.mode!=WebhookMode.manual && !canRecord()) {
            store.prepare(config.revision,config.settings.copy(enabled=false),config.credentials,WebhookQueueTransition.discard,false)
            config=recover();collectOrphans();return@sending WebhookRun.Disabled
        }
        authorize(Capability.webhook)
        queue.recoverUnderExclusiveDeliveryLock(config.revision)
        collectOrphans()
        val snapshot=queue.snapshot()
        var batch=if(config.settings.mode==WebhookMode.manual) database.webhooks().explicitHead(config.revision) else snapshot.head
        if(batch==null) {
            batch=when(config.settings.mode) {
                WebhookMode.realtime->queue.claimWindow(config.revision,clock.instant())
                WebhookMode.batchCount->if(database.webhooks().unclaimedCount(config.revision,null)>=config.settings.batchCount) queue.claimWindow(config.revision,clock.instant()) else null
                WebhookMode.batchTime->{
                    val due=batchDue(config,snapshot)
                    if(due==null || due>clock.instant()) return@sending WebhookRun.Waiting(due)
                    queue.claimWindow(config.revision,clock.instant())
                }
                WebhookMode.manual->null
            }
        }
        if(batch==null) return@sending WebhookRun.Waiting()
        if(config.settings.mode==WebhookMode.manual && batch.kind=="points") return@sending WebhookRun.Waiting()
        if(batch.stopped) return@sending WebhookRun.NeedsAttention
        batch.nextAttempt?.takeIf {it>clock.instant()}?.let {return@sending WebhookRun.Waiting(it)}
        val job=currentCoroutineContext().job
        fun current():Boolean=job.isActive && shared.epoch.get()==ticket && !shared.inhibited.get()
        val payload=try {
            if(batch.bodyId!=null) WebhookPayload.restore(payloads,batch.bodyId!!,ExportFormat.valueOf(batch.bodyFormat!!),batch.bodyBytes!!,batch.bodySha256!!,ExportCounts(batch.visitCount,batch.pointCount)) {if(!current()) throw CancellationException()}
            else {
                val selected=batch
                val source=object:ExportRecordSource {
                    override suspend fun visits(consume:suspend(Visit)->Unit) {}
                    override suspend fun points(consume:suspend(TrackPoint)->Unit) {queue.forEachPoint(config.revision,selected.id,consume)}
                }
                WebhookPayload.create(payloads,config.settings.format,source,ExportOptions(kind=DataKind.points,from=Instant.ofEpochMilli(Long.MIN_VALUE),zone=ZoneOffset.UTC),batch.createdAt,displayLocale()).also {body->
                    checkCurrent(ticket)
                    queue.freeze(config.revision,batch.id,body.id,body.format.name,body.bytes,body.sha256)
                    checkpoint("body-frozen")
                }
            }
        } catch(e:CancellationException) {throw e} catch(_:Exception) {
            queue.stopBeforeAttempt(config.revision,batch.id);return@sending WebhookRun.NeedsAttention
        }
        val attempt=queue.beginAttempt(config.revision,batch.id) ?: return@sending WebhookRun.NeedsAttention
        try {
            val result=delivery.send(config,payload,::current)
            checkpoint("response-before-ack")
            val retry=WebhookRetryPolicy.decide(attempt.batch.attempts,result,clock.instant())
            check(queue.finishAttempt(config.revision,batch.id,attempt.token,result.status,(retry as? WebhookRetry.After)?.at))
            if(result.successful) {payload.file.delete();WebhookRun.Delivered(result.status)}
            else if(retry is WebhookRetry.After) WebhookRun.Waiting(retry.at) else WebhookRun.NeedsAttention
        } catch(e:CancellationException) {
            withContext(NonCancellable) {queue.finishAttempt(config.revision,batch.id,attempt.token,0,clock.instant().plusSeconds(1))}
            throw e
        }
    }
    private suspend fun collectOrphans() {
        if(!payloads.isDirectory) return
        java.nio.file.Files.newDirectoryStream(payloads.toPath()).use {paths->for(path in paths) {
            currentCoroutineContext().ensureActive()
            val file=path.toFile();val extension=file.extension;val id=file.nameWithoutExtension
            if(extension !in setOf("tmp","body") || runCatching {UUID.fromString(id).toString()!=id}.getOrDefault(true) || !file.isFile) continue
            if(extension=="tmp" || !database.webhooks().referencesBody(id)) check(file.delete()) {"Webhook temporary files could not be cleared."}
        }}
    }
    companion object {private val owners=ConcurrentHashMap<String,Owner>()}
}
