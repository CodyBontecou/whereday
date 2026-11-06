package com.bontecou.whereday.platform

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicLong

interface UsageAnalyticsJobs {suspend fun reconcile();suspend fun cancelAll()}
enum class UsageControlError {unavailable,storage,scheduling}
data class UsageControlState(val busy:Boolean=false,val saved:Boolean=false,val error:UsageControlError?=null)
class UsageActionPermit internal constructor(internal val revision:Long,internal val consent:UsageConsentPermit)
enum class UsageDrainResult {idle,retry,rejected,disabled}

/** Application-owned actions survive screen disposal. Only explicit UI confirmation calls setEnabled. */
class UsageAnalyticsController(val core:UsageAnalyticsCore,private val jobs:UsageAnalyticsJobs) {
    private val actions=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val controls=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val monitor=Any();private val changes=Mutex();private val clearing=Mutex()
    private val revision=AtomicLong()
    @Volatile private var paused=false
    private var clears=0
    private var changing:Job?=null
    private val mutable=MutableStateFlow(UsageControlState())
    val control=mutable.asStateFlow()
    val state get()=core.state
    init {foreground()}
    fun foreground() {
        val ticket=revision.get()
        if(paused) return
        actions.launch {
            try {core.load();if(!paused && ticket==revision.get()) jobs.reconcile()}
            catch(e:CancellationException) {throw e}
            catch(_:Exception) {if(!paused && ticket==revision.get()) mutable.update {it.copy(error=UsageControlError.scheduling)}}
        }
    }
    /** Capture before opening asynchronous system/store UI; never mint a fresh permit in its callback. */
    fun capture():UsageActionPermit? {
        val ticket=revision.get()
        if(paused) return null
        val permit=core.captureConsent() ?: return null
        return UsageActionPermit(ticket,permit)
    }
    fun track(event:()->UsageEvent):Job?=track(capture(),event=event)
    /** Null, revoked and different-owner attempts never evaluate their event factory. */
    fun track(permit:UsageActionPermit?,after:Job?=null,event:()->UsageEvent):Job? {
        if(permit==null || paused || permit.revision!=revision.get() || !core.permits(permit.consent)) return null
        val ticket=permit.revision
        val frozen=event()
        return actions.launch {
            after?.join()
            if(paused || ticket!=revision.get()) return@launch
            try {if(core.record(frozen,permit.consent) && !paused && ticket==revision.get()) jobs.reconcile()}
            catch(e:CancellationException) {throw e}
            catch(_:Exception) {if(!paused && ticket==revision.get()) mutable.update {it.copy(error=UsageControlError.scheduling)}}
        }
    }
    fun setEnabled(enabled:Boolean):Job?=synchronized(monitor) {
        if(paused) return null
        val ticket=revision.incrementAndGet()
        core.inhibit();changing?.cancel()
        mutable.value=UsageControlState(busy=true)
        controls.launch(start=CoroutineStart.LAZY) {changes.withLock {
            if(paused || ticket!=revision.get()) return@withLock
            try {
                core.setConsent(enabled)
                if(ticket==revision.get() && !paused) {jobs.reconcile();mutable.value=UsageControlState(saved=true)}
            } catch(e:CancellationException) {throw e}
            catch(_:UsageAnalyticsUnavailable) {if(ticket==revision.get()) mutable.value=UsageControlState(error=UsageControlError.unavailable)}
            catch(_:UsageAnalyticsSettingsUnavailable) {if(ticket==revision.get()) mutable.value=UsageControlState(error=UsageControlError.storage)}
            catch(_:Exception) {if(ticket==revision.get()) mutable.value=UsageControlState(error=UsageControlError.scheduling)}
            finally {if(ticket==revision.get()) mutable.update {it.copy(busy=false)}}
        }}.also {changing=it;it.start()}
    }
    /** Clear's owned operation completes even if the requesting Activity is recreated. */
    suspend fun clearAll() {
        synchronized(monitor) {clears++;paused=true;revision.incrementAndGet();core.inhibit();changing?.cancel();mutable.value=UsageControlState(busy=true)}
        val completion=controls.async {clearing.withLock {
            try {
                val old=actions.coroutineContext.job.children.toList();old.forEach {it.cancel()};old.joinAll()
                synchronized(monitor) {changing}?.cancelAndJoin()
                changes.withLock {core.setConsent(false);jobs.cancelAll();mutable.value=UsageControlState(saved=true)}
            } catch(e:CancellationException) {throw e}
            catch(error:Exception) {mutable.value=UsageControlState(error=UsageControlError.storage);throw error}
            finally {synchronized(monitor) {clears--;paused=clears>0;mutable.update {it.copy(busy=paused)}}}
        }}
        withContext(NonCancellable) {completion.await()}
    }
    suspend fun drain():UsageDrainResult {
        if(paused) return UsageDrainResult.disabled
        core.load()
        repeat(10) {
            if(paused || !core.state.value.enabled || !core.state.value.available || core.state.value.error==UsageAnalyticsError.storage) return UsageDrainResult.disabled
            when(core.sendNext()) {
                UsageSendResult.retry->return UsageDrainResult.retry
                UsageSendResult.rejected->return UsageDrainResult.rejected
                null->return UsageDrainResult.idle
                UsageSendResult.accepted->Unit
            }
        }
        return if(core.state.value.pending>0) UsageDrainResult.retry else UsageDrainResult.idle
    }
    internal fun close() {actions.cancel();controls.cancel();core.inhibit()}
}
