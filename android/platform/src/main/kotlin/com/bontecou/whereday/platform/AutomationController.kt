package com.bontecou.whereday.platform

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

data class AutomationUiState(val loaded:Boolean=false,val installed:AutomationIdentity?=null,val approval:AutomationApproval?=null,
    val busy:Boolean=false,val error:String?=null,val message:String?=null)

/** Explicit consent writes survive navigation; clear/revoke fences and joins older queued consent. */
class AutomationController(private val access:AutomationAccess,private val results:AutomationResults,private val clientPackage:String=AUTOMATE) {
    private val actions=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val controls=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val serial=Mutex();private val clearing=Mutex();private val monitor=Any()
    private val paused=AtomicBoolean();private val revision=AtomicLong()
    private val mutable=MutableStateFlow(AutomationUiState())
    val state=mutable.asStateFlow()
    init {reload()}
    fun reload() {
        val epoch=revision.get()
        if(paused.get() || mutable.value.busy) return
        actions.launch {serial.withLock {if(!paused.get() && epoch==revision.get()) load()}}
    }
    fun approve(identity:AutomationIdentity,scopes:Set<AutomationScope>) {
        val reviewed=identity.copy(signers=identity.signers.toSet());val chosen=scopes.toSet()
        val epoch=synchronized(monitor) {
            if(paused.get() || mutable.value.busy || !mutable.value.loaded || chosen.isEmpty()) return
            mutable.update {it.copy(busy=true,error=null,message=null)};revision.get()
        }
        actions.launch {serial.withLock {
            if(paused.get() || epoch!=revision.get()) return@withLock
            try {
                currentCoroutineContext().ensureActive()
                access.approve(reviewed,chosen)
                load("Automation access saved.")
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {mutable.update {it.copy(busy=false,error="Access could not be saved. Review the installed app and try again.")}}
        }}
    }
    fun disable() {controls.launch {
        try {clearAll()}
        catch(e:CancellationException) {throw e}
        catch(_:Exception) {mutable.update {it.copy(busy=false,error="Automation access could not be completely disabled. Try again.")}}
    }}
    suspend fun clearAll()=clearing.withLock {
        synchronized(monitor) {paused.set(true);revision.incrementAndGet();mutable.update {it.copy(busy=true,error=null,message=null)}}
        try {
            val pending=actions.coroutineContext.job.children.toList();pending.forEach {it.cancel()};pending.joinAll()
            serial.withLock {access.revoke();results.clear();load("Automation access disabled.")}
        } finally {paused.set(false);mutable.update {it.copy(busy=false)}}
    }
    private suspend fun load(message:String?=null) {
        try {mutable.value=AutomationUiState(true,access.installed(clientPackage),access.approval(),message=message)}
        catch(e:CancellationException) {throw e}
        catch(_:Exception) {mutable.value=AutomationUiState(true,access.installed(clientPackage),error="Saved automation access could not be opened. Disable access to reset it.")}
    }
    internal fun close() {actions.cancel();controls.cancel()}
    companion object {const val AUTOMATE="com.llamalab.automate"}
}
