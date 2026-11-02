package com.bontecou.whereday.platform

import com.bontecou.whereday.data.WebhookQueueTransition
import com.bontecou.whereday.export.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Credentials in this in-memory state are redacted by WebhookConfiguration.toString; never save it in UI state. */
data class WebhookUiState(val loaded:Boolean=false,val state:WebhookState?=null,val busy:Boolean=false,
    val nextAttempt:Instant?=null,val failure:WebhookFailure?=null,val feedback:WebhookFeedback?=null,val backgroundPaused:Boolean=false) {
    val error:String? get()=failure?.diagnostic
    val message:String? get()=feedback?.diagnostic
}

/** Application-owned observation and user actions; opening a screen never enables a webhook. */
class WebhookController internal constructor(private val coordinator:WebhookCoordinator,private val scheduler:WebhookWorkScheduler,
    changes:Flow<Unit>,outlierChanges:Flow<Boolean>) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val actions=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val serial=Mutex()
    private val paused=AtomicBoolean(false)
    private val foregroundRetry=AtomicBoolean(false)
    private val commandRevision=AtomicLong()
    private val wakes=Channel<Unit>(Channel.CONFLATED)
    private val mutable=MutableStateFlow(WebhookUiState())
    val state=mutable.asStateFlow()
    @Volatile private var includeOutliers:Boolean?=null
    private var pump:Job?=null
    init {
        scope.launch {try {changes.collect {wakes.trySend(Unit)}} catch(e:CancellationException) {throw e} catch(e:Exception) {mutable.update {it.copy(failure=failure(e))}}}
        scope.launch {try {scheduler.changes().collect {wakes.trySend(Unit)}} catch(e:CancellationException) {throw e} catch(e:Exception) {mutable.update {it.copy(failure=failure(e))}}}
        scope.launch {try {outlierChanges.distinctUntilChanged().collect {includeOutliers=it;wakes.trySend(Unit)}} catch(e:CancellationException) {throw e} catch(e:Exception) {mutable.update {it.copy(failure=WebhookFailure.preferences)}}}
        startPump();refresh()
    }
    private fun startPump() {pump=scope.launch {
        for(signal in wakes) {
            if(paused.get() || includeOutliers==null) continue
            val foreground=foregroundRetry.getAndSet(false)
            serial.withLock {operation(false,foreground) {
                if(foreground) coordinator.permissionsChanged()
                coordinator.updateOutlierPolicy(checkNotNull(includeOutliers))
                null
            }}
        }
    }}
    fun refresh() {wakes.trySend(Unit)}
    fun foreground() {foregroundRetry.set(true);refresh()}
    fun permissionsChanged()=action(before={coordinator.permissionsChanged()}) {null}
    fun configure(settings:WebhookSettings,credentials:WebhookCredentials,transition:WebhookQueueTransition=WebhookQueueTransition.requireEmpty,expectedRevision:String?=null)=action {
        coordinator.configure(settings,credentials,checkNotNull(includeOutliers),transition,expectedRevision);WebhookFeedback(WebhookNotice.saved)
    }
    fun disable() {commandRevision.incrementAndGet();action(before={coordinator.disable();scheduler.cancelAll()}) {WebhookFeedback(WebhookNotice.disabled)}}
    fun reset() {commandRevision.incrementAndGet();action(before={coordinator.reset();scheduler.cancelAll()}) {WebhookFeedback(WebhookNotice.reset)}}
    fun testConnection()=action {
        val result=coordinator.testConnection()
        if(result.successful) WebhookFeedback(WebhookNotice.connected,result.status)
        else if(result.status==0) WebhookFeedback(WebhookNotice.connectionFailed)
        else WebhookFeedback(WebhookNotice.connectionRejected,result.status)
    }
    fun sendHistory()=action {coordinator.enqueueHistory();WebhookFeedback(WebhookNotice.history)}
    fun flush()=action {coordinator.flush();WebhookFeedback(WebhookNotice.flushed)}
    fun retry(batch:String)=action {coordinator.retry(batch);WebhookFeedback(WebhookNotice.retry)}
    fun discard(batch:String)=action {coordinator.discard(batch);WebhookFeedback(WebhookNotice.discarded)}
    private fun action(before:suspend()->Unit={},block:suspend()->WebhookFeedback?) {
        if(paused.get()) return
        val revision=commandRevision.get()
        actions.launch {
            if(paused.get() || commandRevision.get()!=revision) return@launch
            try {
                // Disable/reset can cancel active HTTP before waiting for another UI action's mutex.
                before()
                serial.withLock {if(commandRevision.get()==revision && !paused.get()) operation(true,true,block)}
            } catch(e:CancellationException) {throw e}
            catch(e:Exception) {mutable.update {it.copy(busy=false,failure=failure(e),feedback=null)}}
        }
    }
    private suspend fun operation(busy:Boolean,retryFailed:Boolean=false,block:suspend()->WebhookFeedback?) {
        if(busy) mutable.update {it.copy(busy=true,failure=null,feedback=null)}
        try {
            val feedback=block()
            val saved=coordinator.state()
            val next=scheduler.reconcile(retryFailed=retryFailed)
            val blocked=next==null && scheduler.paused()
            mutable.update {WebhookUiState(true,saved,false,next,failure=if(blocked) WebhookFailure.backgroundPaused else null,feedback=feedback ?: if(busy) null else it.feedback,backgroundPaused=blocked)}
        } catch(e:CancellationException) {throw e}
        catch(e:Exception) {mutable.update {it.copy(busy=false,failure=failure(e),feedback=null)}}
        finally {if(busy) mutable.update {it.copy(busy=false)}}
    }
    private fun failure(error:Exception):WebhookFailure=when(error) {
        is WebhookConfigurationChanged->WebhookFailure.changed
        is WebhookSettingsUnavailable->WebhookFailure.unavailable
        is IllegalArgumentException->WebhookFailure.fields
        else->WebhookFailure.work
    }
    /** Stop/join all controller work before clearing under the coordinator's delivery lock. */
    suspend fun clearAll(clear:suspend()->Unit) {
        paused.set(true);commandRevision.incrementAndGet()
        try {
            pump?.cancelAndJoin()
            actions.coroutineContext.job.children.toList().forEach {it.cancel()}
            actions.coroutineContext.job.children.toList().joinAll()
            serial.withLock {
                coordinator.clearAll(clear);scheduler.cancelAll();mutable.value=WebhookUiState(loaded=true)
            }
        } finally {paused.set(false);startPump();refresh()}
    }
    internal suspend fun close() {scope.coroutineContext.job.cancelAndJoin();actions.coroutineContext.job.cancelAndJoin()}
}
