package com.bontecou.whereday.platform

import com.bontecou.whereday.export.*
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.InputStream
import java.time.*
import java.util.Locale
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

interface HistoryImportInput {fun open():InputStream;fun cancel() {}}
data class HistoryImportSelection(val visits:Boolean=true,val points:Boolean=true)
enum class HistoryImportPhase {idle,reading,review,applying,canceling,complete,canceled,failed}
data class HistoryImportReview(val id:String,val format:ExportFormat,val visits:Int,val points:Int,val existing:Int,val repeated:Int,
    val rejected:Int,val errors:List<String>,val from:Instant?,val until:Instant?,val locale:String,val zone:String,val source:HistoryImportSource=HistoryImportSource.whereday,val unsupported:Int=0,val replaceable:Int=existing,val years:Map<Int,Int> = emptyMap())
data class HistoryImportState(val phase:HistoryImportPhase=HistoryImportPhase.idle,val review:HistoryImportReview?=null,
    val counts:HistoryImportCounts?=null,val error:String?=null) {
    val busy get()=phase in setOf(HistoryImportPhase.reading,HistoryImportPhase.applying,HistoryImportPhase.canceling)
}

/** Application-owned review backed by disposable private staging. Process death discards it; it never replays a commit.
 * Reading/parsing is cancellable. Writes use a cooperative token inside one transaction, so a
 * cancel arriving after its final check reports the actual committed result instead of a false rollback.
 */
class HistoryImportController(private val target:StagedHistoryImportTarget,private val workspaces:HistoryImportWorkspaceFactory) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val monitor=Any();private val clears=kotlinx.coroutines.sync.Mutex()
    private var blocked=0;private var revision=0L;private var work:Job?=null
    private var cancelFlag=AtomicBoolean();private var input:HistoryImportInput?=null;private var stream:InputStream?=null
    private var workspace:HistoryImportWorkspace?=null
    private val cleanup=mutableSetOf<Job>()
    private val mutable=MutableStateFlow(HistoryImportState());val state=mutable.asStateFlow()
    fun select(source:HistoryImportInput,locale:Locale=Locale.getDefault(),zone:ZoneId=ZoneId.systemDefault()):Job?=synchronized(monitor) {
        if(blocked>0 || mutable.value.phase!=HistoryImportPhase.idle) return null
        val ticket=++revision;val flag=AtomicBoolean();cancelFlag=flag;input=source
        mutable.value=HistoryImportState(HistoryImportPhase.reading)
        scope.launch(start=CoroutineStart.LAZY) {
            var owned:HistoryImportWorkspace?=null;var retained=false
            try {
                val job=currentCoroutineContext().job
                val check={job.ensureActive();if(flag.get()) throw HistoryImportCanceled();Unit}
                owned=workspaces.create();check()
                synchronized(monitor) {if(ticket==revision && !flag.get()) workspace=owned}
                val opened=source.open()
                synchronized(monitor) {if(ticket==revision && !flag.get()) stream=opened}
                opened.use {owned.capture(it,check)}
                val parsed=owned.prepare(locale,zone,check)
                val inspected=target.inspectPlan(owned.stage,check);check();owned.stage.seal(check);check()
                val review=HistoryImportReview(UUID.randomUUID().toString(),parsed.format,parsed.visits-inspected.repeated.visits,parsed.points-inspected.repeated.points,inspected.existing,parsed.repeated+inspected.repeated.visits+inspected.repeated.points,
                    parsed.rejected,parsed.errors,parsed.from,parsed.until,locale.toLanguageTag(),zone.id,parsed.source,parsed.unsupported,inspected.replaceable,parsed.years)
                synchronized(monitor) {if(ticket==revision && !flag.get()) {retained=true;mutable.value=HistoryImportState(HistoryImportPhase.review,review)}}
            } catch(error:Exception) {failed(ticket,flag,error)}
            finally {
                if(!retained) retire(owned)
                synchronized(monitor) {if(ticket==revision) {stream=null;input=null;if(!retained) workspace=null}}
            }
        }.also {work=it;val owned=it;it.invokeOnCompletion {
            synchronized(monitor) {if(work===owned) {
                work=null
                if(ticket==revision && blocked==0 && mutable.value.phase==HistoryImportPhase.canceling) {
                    retire(workspace);workspace=null;mutable.value=mutable.value.copy(phase=HistoryImportPhase.canceled,error=null)
                }
            }}
        };it.start()}
    }
    fun commit(id:String,policy:HistoryImportPolicy,selection:HistoryImportSelection=HistoryImportSelection()):Job?=synchronized(monitor) {
        val current=mutable.value
        if(blocked>0 || current.phase!=HistoryImportPhase.review || current.error!=null || current.review?.id!=id) return null
        if((!selection.visits || current.review.visits==0) && (!selection.points || current.review.points==0)) return null
        if(policy==HistoryImportPolicy.replaceExisting && (current.review.source!=HistoryImportSource.whereday || current.review.replaceable==0)) return null
        val prepared=workspace ?: return null
        val ticket=revision;val flag=cancelFlag
        mutable.value=current.copy(phase=HistoryImportPhase.applying)
        scope.launch(start=CoroutineStart.LAZY) {
            try {
                val check={if(flag.get()) throw HistoryImportCanceled();Unit}
                val result=target.apply(SelectedHistoryImportStage(prepared.stage,selection,check),policy,check)
                synchronized(monitor) {if(ticket==revision) {workspace=null;mutable.value=current.copy(phase=HistoryImportPhase.complete,counts=result)}}
            } catch(error:Exception) {failed(ticket,flag,error)}
            finally {retire(prepared);synchronized(monitor) {if(workspace===prepared) workspace=null}}
        }.also {work=it;val owned=it;it.invokeOnCompletion {synchronized(monitor) {if(work===owned) work=null}};it.start()}
    }
    private fun retire(value:HistoryImportWorkspace?) {
        if(value==null) return
        synchronized(monitor) {
            val job=scope.launch(start=CoroutineStart.LAZY) {runCatching {value.close()}}
            cleanup+=job
            job.invokeOnCompletion {synchronized(monitor) {cleanup-=job}}
            job.start()
        }
    }
    private fun failed(ticket:Long,flag:AtomicBoolean,error:Exception)=synchronized(monitor) {
        if(ticket!=revision) return@synchronized
        val canceled=flag.get() || error is CancellationException || error is HistoryImportCanceled
        retire(workspace);workspace=null
        mutable.value=mutable.value.copy(phase=if(canceled) HistoryImportPhase.canceled else HistoryImportPhase.failed,
            error=if(canceled) null else when(error) {
                is HistoryImportChanged->"History changed after this review. Choose the file again and review the latest matches."
                is HistoryImportWriteFailed->error.message
                is java.nio.charset.CharacterCodingException->"Choose a UTF-8 history file."
                is ImportStagingWriteFailed->error.message
                is android.database.sqlite.SQLiteFullException->"Local import storage is full. Free some space and choose the file again."
                is android.database.sqlite.SQLiteException->"Local import storage could not be read or updated. Check available storage and try again."
                is IllegalArgumentException->error.message?.take(200) ?: "This file contains invalid history."
                else->"The history file could not be read or imported. Choose it again to retry."
            })
    }
    fun cancel()=synchronized(monitor) {
        // A reading-screen click can arrive just after parsing published its review.
        if(mutable.value.phase==HistoryImportPhase.review) {
            cancelFlag.set(true);retire(workspace);workspace=null
            mutable.value=mutable.value.copy(phase=HistoryImportPhase.canceled,error=null)
            return@synchronized
        }
        if(!mutable.value.busy) return@synchronized
        cancelFlag.set(true)
        val source=input;val opened=stream
        if(mutable.value.phase==HistoryImportPhase.reading) {
            // Providers may ignore close/cancellation. Reads have no write authority, so fence
            // and detach that operation rather than letting it hold review or clear hostage.
            work?.cancel();revision++;work=null;retire(workspace);workspace=null;input=null;stream=null
            mutable.value=HistoryImportState(HistoryImportPhase.canceled)
        } else {
            // Do not cancel an applying coroutine across the database's commit boundary.
            mutable.value=mutable.value.copy(phase=HistoryImportPhase.canceling)
        }
        scope.launch {runCatching {source?.cancel()};runCatching {opened?.close()}}
        Unit
    }
    fun discard()=synchronized(monitor) {
        if(blocked>0 || mutable.value.busy) return@synchronized
        revision++;retire(workspace);workspace=null;input=null;stream=null;work=null;mutable.value=HistoryImportState()
    }
    /** Keep new imports blocked through the caller's actual journal clear, not only queue cleanup. */
    suspend fun clearAll(clear:suspend()->Unit) {
        synchronized(monitor) {blocked++;cancel();mutable.value=mutable.value.copy(phase=HistoryImportPhase.canceling)}
        val operation=scope.async {
            clears.lock()
            try {
                val active=synchronized(monitor) {work};active?.join()
                val pending=synchronized(monitor) {cleanup.toList()};pending.joinAll();workspaces.reap()
                clear()
            } finally {
                synchronized(monitor) {revision++;retire(workspace);workspace=null;input=null;stream=null;work=null;blocked--;mutable.value=HistoryImportState(if(blocked>0) HistoryImportPhase.canceling else HistoryImportPhase.idle)}
                clears.unlock()
            }
        }
        withContext(NonCancellable) {operation.await()}
    }
    internal fun close() {synchronized(monitor) {workspace?.close();workspace=null};scope.cancel()}
}
