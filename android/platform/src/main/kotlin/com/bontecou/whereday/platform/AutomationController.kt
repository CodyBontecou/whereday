package com.bontecou.whereday.platform

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

enum class AutomationFailure(val diagnostic:String) {
    save("Access could not be saved. Review the installed app and try again."),
    disable("Automation access could not be completely disabled. Try again."),
    load("Saved automation access could not be opened. Disable access to reset it."),
    install("The app store could not be opened. Install Automate and return to check the installed app.")
}
enum class AutomationNotice(val diagnostic:String) {saved("Automation access saved."),disabled("Automation access disabled.")}
data class AutomationUiState(val loaded:Boolean=false,val installed:AutomationIdentity?=null,val approval:AutomationApproval?=null,
    val busy:Boolean=false,val failure:AutomationFailure?=null,val notice:AutomationNotice?=null,
    val consentRevision:Long=0,val resetRevision:Long=0) {
    val error:String? get()=failure?.diagnostic
    val message:String? get()=notice?.diagnostic
}

/** Explicit consent writes survive navigation; clear/revoke fences and joins older queued consent. */
class AutomationController(private val access:AutomationAccess,private val results:AutomationResults,private val clientPackage:String=AUTOMATE) {
    private val actions=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val controls=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val serial=Mutex();private val clearing=Mutex();private val monitor=Any()
    private val paused=AtomicBoolean()
    private val revision=AtomicLong();private val resets=AtomicLong()
    private val mutable=MutableStateFlow(AutomationUiState())
    val state=mutable.asStateFlow()
    init {reload()}
    fun reload() {
        val epoch=revision.get()
        if(paused.get() || mutable.value.busy) return
        actions.launch {serial.withLock {if(!paused.get() && epoch==revision.get()) load(epoch=epoch)}}
    }
    fun approve(identity:AutomationIdentity,scopes:Set<AutomationScope>) {
        val reviewed=identity.copy(signers=identity.signers.toSet());val chosen=scopes.toSet()
        val epoch=synchronized(monitor) {
            if(paused.get() || mutable.value.busy || !mutable.value.loaded || chosen.isEmpty()) return
            val next=revision.incrementAndGet()
            mutable.update {it.copy(busy=true,failure=null,notice=null,consentRevision=next)};next
        }
        actions.launch {serial.withLock {
            if(paused.get() || epoch!=revision.get()) return@withLock
            try {
                currentCoroutineContext().ensureActive()
                access.approve(reviewed,chosen)
                load(AutomationNotice.saved,epoch)
            } catch(error:CancellationException) {throw error}
            catch(_:Exception) {synchronized(monitor) {if(!paused.get() && epoch==revision.get()) mutable.update {it.copy(busy=false,failure=AutomationFailure.save,notice=null)}}}
        }}
    }
    fun disable() {controls.launch {
        try {clearAll()}
        catch(error:CancellationException) {throw error}
        catch(_:Exception) { /* clearAll publishes its typed failure before propagating. */ }
    }}
    suspend fun clearAll()=clearing.withLock {
        val epoch=synchronized(monitor) {
            paused.set(true);val next=revision.incrementAndGet();val reset=resets.incrementAndGet()
            mutable.update {it.copy(busy=true,failure=null,notice=null,consentRevision=next,resetRevision=reset)};next
        }
        try {
            val pending=actions.coroutineContext.job.children.toList();pending.forEach {it.cancel()};pending.joinAll()
            serial.withLock {access.revoke();results.clear();load(AutomationNotice.disabled,epoch,allowPaused=true)}
        } catch(error:Exception) {
            synchronized(monitor) {if(epoch==revision.get()) mutable.update {it.copy(failure=AutomationFailure.disable,notice=null)}}
            throw error
        } finally {synchronized(monitor) {paused.set(false);if(epoch==revision.get()) mutable.update {it.copy(busy=false)}}}
    }
    fun installUnavailable() {mutable.update {if(it.failure in setOf(AutomationFailure.save,AutomationFailure.disable,AutomationFailure.load)) it else it.copy(failure=AutomationFailure.install)}}
    private suspend fun load(notice:AutomationNotice?=null,epoch:Long=revision.get(),allowPaused:Boolean=false) {
        val snapshot=try {
            val approval=access.approval();val installed=access.installed(clientPackage)
            AutomationUiState(true,installed,approval,notice=notice,consentRevision=epoch,resetRevision=resets.get())
        } catch(error:CancellationException) {throw error}
        catch(_:Exception) {AutomationUiState(true,runCatching {access.installed(clientPackage)}.getOrNull(),failure=AutomationFailure.load,consentRevision=epoch,resetRevision=resets.get())}
        currentCoroutineContext().ensureActive()
        synchronized(monitor) {if(epoch==revision.get() && (allowPaused || !paused.get())) mutable.value=snapshot}
    }
    internal suspend fun close() {actions.coroutineContext.job.cancelAndJoin();controls.coroutineContext.job.cancelAndJoin()}
    companion object {const val AUTOMATE="com.llamalab.automate"}
}
