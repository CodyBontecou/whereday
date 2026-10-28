package com.bontecou.whereday.platform

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import java.util.concurrent.atomic.AtomicBoolean

data class AutomaticExportUiState(val loaded:Boolean=false,val state:AutomaticExportState=AutomaticExportState(),
    val busy:Boolean=false,val nextAttempt:Instant?=null,val error:String?=null,val message:String?=null)

/** Application-owned actions survive screen disposal; busy work can be invalidated before cancellation queues. */
class AutomaticExportController(private val coordinator:ScheduledExportCoordinator,private val scheduler:ScheduledExportWorkScheduler) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val serial=Mutex()
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
        action {coordinator.configure(settings);"Automatic export settings saved."}
    }
    fun runNow()=action {if(coordinator.runNow()==AutomaticRunOutcome.exported) "Export saved and verified." else null}
    fun retry()=action {if(coordinator.retryPending()==AutomaticRunOutcome.exported) "Export saved and verified." else null}
    fun cancelPending() {coordinator.invalidate();action {coordinator.cancelPending();"Pending export canceled. External files remain in their folder."}}
    fun reset() {coordinator.invalidate();action {coordinator.clear();scheduler.cancelAll();"Automatic export settings reset."}}
    private fun action(after:()->Unit={},block:suspend ()->String?) {
        scope.launch {
            try {
                serial.withLock {
                    mutable.update {it.copy(busy=true,error=null,message=null)}
                    try {
                        val message=block()
                        val saved=coordinator.snapshot();val next=scheduler.reconcile()
                        mutable.value=AutomaticExportUiState(true,saved,false,next,message=message)
                    } catch(e:Exception) {
                        withContext(NonCancellable) {
                            val saved=runCatching {coordinator.snapshot()}.getOrNull()
                            mutable.value=AutomaticExportUiState(saved!=null,saved ?: mutable.value.state,false,error=if(e is CancellationException) "Automatic export was interrupted. Resume or cancel its saved request." else e.message ?: "Automatic export settings could not be loaded.")
                        }
                        if(e is CancellationException) throw e
                    }
                }
            } finally {after()}
        }
    }
    suspend fun clearAll() {
        coordinator.invalidate();scope.coroutineContext.cancelChildren()
        serial.withLock {coordinator.clear();scheduler.cancelAll();foregroundQueued.set(false);mutable.value=AutomaticExportUiState(true)}
    }
    internal fun close() {scope.cancel()}
}
