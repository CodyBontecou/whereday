package com.bontecou.whereday.platform

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.util.UUID

enum class JournalClearPhase {idle,checking,clearing,complete,failed}
data class JournalClearState(val phase:JournalClearPhase=JournalClearPhase.idle,val attempt:Long=0,val restricted:Boolean=false,val needsApproval:Boolean=false,val generation:String?=null) {
    val busy:Boolean get()=phase==JournalClearPhase.checking || phase==JournalClearPhase.clearing
    val blocked:Boolean get()=busy || restricted
}

/** A durable consent receipt brackets the complete existing pipeline; completion is never inferred. */
class JournalClearController private constructor(private val clearJournal:suspend()->Unit,private val onFailure:()->Unit,private val receipts:JournalClearReceiptStore?) {
    constructor(clearJournal:suspend()->Unit,onFailure:()->Unit={}):this(clearJournal,onFailure,null)
    internal constructor(receipts:JournalClearReceiptStore,clearJournal:suspend()->Unit,onFailure:()->Unit={}):this(clearJournal,onFailure,receipts)
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val monitor=Any()
    private var active:Job?=null
    private val mutable=MutableStateFlow(if(receipts==null) JournalClearState() else JournalClearState(JournalClearPhase.checking,restricted=true))
    val state=mutable.asStateFlow()
    init {
        if(receipts!=null) synchronized(monitor) {
            scope.launch(start=CoroutineStart.LAZY) {
                try {
                    val receipt=receipts.read()
                    if(receipt!=null && !receipt.complete) {
                        mutable.value=JournalClearState(JournalClearPhase.clearing,1,restricted=true,generation=receipt.id)
                        perform(1,receipt.id)
                    } else mutable.value=JournalClearState(generation=receipt?.id)
                } catch(error:CancellationException) {mutable.value=JournalClearState(JournalClearPhase.failed,mutable.value.attempt,restricted=true,generation=mutable.value.generation);throw error}
                catch(_:Exception) {runCatching(onFailure);mutable.value=JournalClearState(JournalClearPhase.failed,mutable.value.attempt,restricted=true,needsApproval=true,generation=mutable.value.generation)}
                finally {synchronized(monitor) {if(mutable.value.attempt<=1 && !mutable.value.busy) active=null}}
            }.also {active=it;it.start()}
        }
    }
    suspend fun awaitReady() {val value=state.first {!it.busy};if(value.blocked) throw JournalClearPending()}
    fun clear():Job=synchronized(monitor) {
        active?.takeIf {mutable.value.busy}?.let {return@synchronized it}
        check(scope.isActive)
        val attempt=mutable.value.attempt+1
        mutable.value=JournalClearState(JournalClearPhase.clearing,attempt,restricted=receipts!=null,generation=mutable.value.generation)
        scope.launch(start=CoroutineStart.LAZY) {
            try {
                // Only an explicit new clear can replace an unreadable receipt.
                val pending=runCatching {receipts?.read()}.getOrNull()?.takeUnless {it.complete}
                perform(attempt,pending?.id)
            } finally {synchronized(monitor) {if(mutable.value.attempt==attempt) active=null}}
        }.also {active=it;it.start()}
    }
    private suspend fun perform(attempt:Long,pending:String?) {
        try {
            val id=pending ?: UUID.randomUUID().toString().also {receipts?.begin(it)}
            val generation=id.takeIf {receipts!=null}
            if(generation!=null) mutable.value=mutable.value.copy(generation=generation)
            clearJournal()
            receipts?.complete(id)
            mutable.value=JournalClearState(JournalClearPhase.complete,attempt,generation=generation)
        } catch(error:CancellationException) {mutable.value=JournalClearState(JournalClearPhase.failed,attempt,restricted=receipts!=null,generation=mutable.value.generation);throw error}
        catch(_:Exception) {runCatching(onFailure);mutable.value=JournalClearState(JournalClearPhase.failed,attempt,restricted=receipts!=null,generation=mutable.value.generation)}
    }
    fun dismiss(attempt:Long)=synchronized(monitor) {
        if(!mutable.value.blocked && mutable.value.attempt==attempt) mutable.value=JournalClearState(attempt=attempt,generation=mutable.value.generation)
    }
    internal suspend fun close() {scope.coroutineContext.job.cancelAndJoin()}
}
