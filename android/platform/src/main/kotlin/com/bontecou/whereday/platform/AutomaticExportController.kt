package com.bontecou.whereday.platform

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

data class AutomaticExportUiState(val loaded:Boolean=false,val state:AutomaticExportState=AutomaticExportState(),
    val busy:Boolean=false,val nextAttempt:Instant?=null,val failure:AutomaticExportFailure?=null,val notice:AutomaticExportActionNotice?=null) {
    val error:String? get()=failure?.diagnostic
    val message:String? get()=notice?.diagnostic
}

/** Application-owned actions survive screen disposal; busy work can be invalidated before cancellation queues. */
class AutomaticExportController(private val coordinator:ScheduledExportCoordinator,private val scheduler:ScheduledExportWorkScheduler) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val serial=Mutex()
    private val submissions=Any()
    private var lastAction:Job?=null
    private var clearing=false
    private val foregroundQueued=AtomicBoolean(false)
    private val mutable=MutableStateFlow(AutomaticExportUiState())
    val state=mutable.asStateFlow()
    init {refresh()}
    fun refresh()=action {null}
    fun foreground() {
        if(!foregroundQueued.compareAndSet(false,true)) return
        action(after={foregroundQueued.set(false)}) {coordinator.runIfDue();null}
    }
    fun configure(settings:AutomaticExportSettings) {
        if(!settings.enabled) coordinator.invalidate()
        action {coordinator.configure(settings);AutomaticExportActionNotice.settingsSaved}
    }
    /** Compose field edits against the latest durable state under the same action lock. */
    fun updateSettings(change:(AutomaticExportSettings)->AutomaticExportSettings)=action {
        coordinator.updateSettings(change);AutomaticExportActionNotice.settingsSaved
    }
    fun setEnabled(value:Boolean) {
        if(!value) coordinator.invalidate()
        updateSettings {it.copy(enabled=value)}
    }
    fun runNow()=action {if(coordinator.runNow()==AutomaticRunOutcome.exported) AutomaticExportActionNotice.verified else null}
    fun retry()=action {if(coordinator.retryPending()==AutomaticRunOutcome.exported) AutomaticExportActionNotice.verified else null}
    fun cancelPending() {coordinator.invalidate();action {coordinator.cancelPending();AutomaticExportActionNotice.canceled}}
    fun reset() {coordinator.invalidate();action {coordinator.clear();scheduler.cancelAll();AutomaticExportActionNotice.reset}}
    private fun action(after:()->Unit={},block:suspend ()->AutomaticExportActionNotice?) {
        synchronized(submissions) {
        if(clearing || !scope.isActive) {after();return}
        val previous=lastAction
        lastAction=scope.launch {
            try {
                previous?.join()
                serial.withLock {
                    mutable.update {it.copy(busy=true,failure=null,notice=null)}
                    try {
                        val notice=block()
                        val saved=coordinator.snapshot();val next=scheduler.reconcile()
                        mutable.value=AutomaticExportUiState(true,saved,false,next,notice=notice)
                    } catch(e:Exception) {
                        withContext(NonCancellable) {
                            val saved=runCatching {coordinator.snapshot()}.getOrNull()
                            mutable.value=AutomaticExportUiState(saved!=null,saved ?: mutable.value.state,false,failure=when {e is CancellationException->AutomaticExportFailure.interrupted;saved==null->AutomaticExportFailure.settingsUnavailable;e is com.bontecou.whereday.billing.VerifiedAccessRequired->AutomaticExportFailure.purchase;e is AutomaticExportPendingChange->AutomaticExportFailure.pendingChange;else->AutomaticExportFailure.execution})
                        }
                        if(e is CancellationException) throw e
                    }
                }
            } finally {after()}
        }
        }
    }
    suspend fun clearAll() {
        coordinator.invalidate()
        synchronized(submissions) {clearing=true;scope.coroutineContext.cancelChildren();lastAction=null}
        try {serial.withLock {coordinator.clear();scheduler.cancelAll();foregroundQueued.set(false);mutable.value=AutomaticExportUiState(true)}}
        finally {synchronized(submissions) {clearing=false}}
    }
    internal fun close() {scope.cancel()}
}
