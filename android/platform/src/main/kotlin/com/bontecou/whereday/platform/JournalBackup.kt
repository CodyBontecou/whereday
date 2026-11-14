package com.bontecou.whereday.platform

import com.bontecou.whereday.data.*
import com.bontecou.whereday.export.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

/** Streams the stored journal into backup records inside one read snapshot. */
internal class SnapshotBackupSource(private val snapshot:JournalBackupSnapshot,private val exportedAtMillis:Long):BackupRecordSource {
    override suspend fun records(collection:BackupCollection,consume:suspend (BackupRecord)->Unit)=when(collection) {
        BackupCollection.visits->snapshot.visits {consume(WheredayBackupMapping.record(it))}
        BackupCollection.points->snapshot.points {consume(WheredayBackupMapping.record(it))}
        BackupCollection.recordingSessions->snapshot.sessions {consume(WheredayBackupMapping.record(it))}
        BackupCollection.savedPlaces->snapshot.places {consume(WheredayBackupMapping.record(it,exportedAtMillis))}
        BackupCollection.photoMoments->snapshot.photos {consume(WheredayBackupMapping.record(it.moment,it.lastSyncedAt))}
        BackupCollection.countryStays->snapshot.stays {consume(WheredayBackupMapping.record(it))}
    }
}

/** What a selected backup would add. Counts are advisory: the restore rechecks identifiers in its own transaction. */
data class BackupRestoreTally(val counts:Map<BackupCollection,Int>,val added:Int,val existing:Int,val unsupported:Int,val exportedAt:Instant)

interface BackupRestoreTarget {
    suspend fun inspect(files:List<File>,check:()->Unit):BackupRestoreTally
    suspend fun restore(files:List<File>,check:()->Unit):BackupRestoreTally
}

/**
 * iOS restore semantics (WheredayBackupService.pending/restore): records whose identifiers already exist are skipped;
 * nothing stored is replaced or deleted. The whole set is validated before anything is written, and the write is a
 * single Room transaction that a failure or cancellation rolls back, so a partial restore is never committed.
 */
class DatabaseBackupRestoreTarget(private val store:JournalBackupStore):BackupRestoreTarget {
    override suspend fun inspect(files:List<File>,check:()->Unit)=store.lookup {lookup->run(files,check,lookup,null)}
    override suspend fun restore(files:List<File>,check:()->Unit):BackupRestoreTally {
        // Validate first, outside the writer, so a damaged set never holds the journal's write lock.
        inspect(files,check)
        return store.restore {writer->run(files,check,writer,writer)}
    }
    private suspend fun run(files:List<File>,check:()->Unit,lookup:JournalBackupLookup,writer:JournalBackupWriter?):BackupRestoreTally {
        val pass=RestorePass(lookup,writer)
        val summary=WheredayBackupReader.inspect(files.map {file->{file.inputStream()}},check) {pass.add(it)}
        pass.finish()
        return BackupRestoreTally(summary.counts,pass.added,pass.existing,pass.unsupported,WheredayBackupMapping.instant(summary.exportedAtMillis))
    }

    private class Pending(val kind:JournalRecordKind,val keys:List<String>,val value:Any)
    private class RestorePass(private val lookup:JournalBackupLookup,private val writer:JournalBackupWriter?) {
        var added=0;var existing=0;var unsupported=0
        private val pending=JournalRecordKind.entries.associateWith {ArrayList<Pending>(500)}
        // Two backup identifiers can name the same Android photo reference; only the first is a new match.
        private val photoUris=HashSet<String>()
        suspend fun add(record:BackupRecord) {
            val item=when(record) {
                is BackupVisitRecord->Pending(JournalRecordKind.visit,WheredayBackupMapping.storedIdForms(record.id),WheredayBackupMapping.visit(record))
                is BackupPointRecord->Pending(JournalRecordKind.point,WheredayBackupMapping.storedIdForms(record.id),WheredayBackupMapping.point(record))
                is BackupSessionRecord->Pending(JournalRecordKind.session,WheredayBackupMapping.storedIdForms(record.id),WheredayBackupMapping.session(record))
                is BackupSavedPlaceRecord->WheredayBackupMapping.place(record)?.let {Pending(JournalRecordKind.place,WheredayBackupMapping.storedIdForms(record.id),it)}
                is BackupCountryStayRecord->WheredayBackupMapping.stay(record)?.let {Pending(JournalRecordKind.stay,WheredayBackupMapping.storedIdForms(record.id),it)}
                is BackupPhotoRecord->WheredayBackupMapping.photo(record)?.let {Pending(JournalRecordKind.photo,listOf(it.moment.uri),StoredPhotoMoment(it.moment,it.lastSyncedAt))}
            }
            if(item==null) {unsupported++;return}
            val queue=pending.getValue(item.kind);queue+=item
            if(queue.size>=500) flush(item.kind)
        }
        suspend fun finish() {JournalRecordKind.entries.forEach {flush(it)}}
        private suspend fun flush(kind:JournalRecordKind) {
            val queue=pending.getValue(kind);if(queue.isEmpty()) return
            val stored=lookup.existing(kind,queue.flatMap {it.keys})
            val fresh=queue.filter {item->
                val new=item.keys.none {it in stored} && (kind!=JournalRecordKind.photo || photoUris.add(item.keys.single()))
                if(new) added++ else existing++
                new
            }
            queue.clear()
            if(writer==null || fresh.isEmpty()) return
            @Suppress("UNCHECKED_CAST") when(kind) {
                JournalRecordKind.visit->writer.visits(fresh.map {it.value as com.bontecou.whereday.domain.Visit})
                JournalRecordKind.point->writer.points(fresh.map {it.value as com.bontecou.whereday.domain.TrackPoint})
                JournalRecordKind.session->writer.sessions(fresh.map {it.value as com.bontecou.whereday.domain.RecordingSession})
                JournalRecordKind.place->writer.places(fresh.map {it.value as com.bontecou.whereday.domain.SavedPlace})
                JournalRecordKind.stay->writer.stays(fresh.map {it.value as com.bontecou.whereday.domain.CountryStay})
                JournalRecordKind.photo->writer.photos(fresh.map {it.value as StoredPhotoMoment})
            }
        }
    }
}

enum class BackupRestorePhase {idle,reading,review,restoring,canceling,complete,canceled,failed}
/** Stable failure identities for localized text; [BackupProblem] names the format rejections iOS also reports. */
enum class BackupRestoreFailure {malformed,unsupportedVersion,duplicateIdentifiers,invalidRecords,incompleteSet,mixedSelection,fileTooLarge,tooManyRecords,storage,unreadable,journalBusy}
data class BackupRestoreReview(val id:String,val files:Int,val tally:BackupRestoreTally)
data class BackupRestoreState(val phase:BackupRestorePhase=BackupRestorePhase.idle,val review:BackupRestoreReview?=null,
    val result:BackupRestoreTally?=null,val failure:BackupRestoreFailure?=null) {
    val busy get()=phase==BackupRestorePhase.reading || phase==BackupRestorePhase.restoring || phase==BackupRestorePhase.canceling
}

/**
 * Detects a Whereday backup in the history-import selection and owns its review and restore. Anything else, chosen
 * alone, is handed unchanged to the ordinary history import. Selected documents are copied to private staging first
 * (at most one byte past the 50 MiB backup limit), so the review and the restore read the same bytes.
 */
class BackupRestoreController(private val target:BackupRestoreTarget,private val staging:File,private val canWrite:()->Boolean={true}) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val monitor=Any();private val clears=kotlinx.coroutines.sync.Mutex()
    private var blocked=0;private var revision=0L;private var work:Job?=null;private var directory:File?=null
    private var canceled=AtomicBoolean();private var selected:List<HistoryImportInput> = emptyList()
    private val mutable=MutableStateFlow(BackupRestoreState());val state=mutable.asStateFlow()

    /** [fallback] receives a single non-backup document after this controller has returned to idle. */
    fun select(inputs:List<HistoryImportInput>,fallback:(HistoryImportInput)->Unit):Job?=synchronized(monitor) {
        if(inputs.isEmpty() || blocked>0 || mutable.value.phase!=BackupRestorePhase.idle) return null
        val ticket=++revision;val flag=AtomicBoolean();canceled=flag;selected=inputs
        mutable.value=BackupRestoreState(BackupRestorePhase.reading)
        scope.launch(start=CoroutineStart.LAZY) {
            val owned=File(staging,UUID.randomUUID().toString());var retained=false;var handOff:HistoryImportInput?=null
            try {
                val job=currentCoroutineContext().job
                val guard={job.ensureActive();if(flag.get()) throw CancellationException("Restore canceled");Unit}
                // Only one review exists at a time; anything else under staging is left from an ended process.
                staging.listFiles()?.forEach {it.deleteRecursively()}
                check(owned.mkdirs()) {"Not enough storage to read the backup"}
                synchronized(monitor) {if(ticket==revision) directory=owned}
                // Recognise first (top-level names only), so another history file is never copied here.
                val backups=inputs.map {input->guard();input.open().use {WheredayBackupReader.isBackup(it)}}
                if(backups.none {it} && inputs.size==1) {handOff=inputs.single();return@launch}
                if(!backups.all {it}) throw WheredayBackupException(BackupProblem.mixedSelection)
                val staged=inputs.mapIndexed {index,input->
                    val file=File(owned,"part-$index.json")
                    if(!input.open().use {copyBounded(it,file,guard)}) throw WheredayBackupException(BackupProblem.fileTooLarge)
                    file
                }
                val tally=target.inspect(staged,guard);guard()
                synchronized(monitor) {
                    if(ticket==revision && !flag.get()) {retained=true;mutable.value=BackupRestoreState(BackupRestorePhase.review,BackupRestoreReview(UUID.randomUUID().toString(),inputs.size,tally))}
                }
            } catch(error:Throwable) {failed(ticket,flag,error)}
            finally {
                if(!retained) {owned.deleteRecursively();synchronized(monitor) {if(directory===owned) directory=null}}
                val single=handOff
                if(single!=null) {
                    synchronized(monitor) {if(ticket==revision && mutable.value.phase==BackupRestorePhase.reading) mutable.value=BackupRestoreState()}
                    if(!flag.get()) fallback(single)
                }
            }
        }.also {job->work=job;job.invokeOnCompletion {synchronized(monitor) {if(work===job) work=null}};job.start()}
    }

    fun restore(id:String):Job?=synchronized(monitor) {
        val current=mutable.value;val review=current.review
        if(blocked>0 || current.phase!=BackupRestorePhase.review || review?.id!=id || review.tally.added==0) return null
        val files=directory?.listFiles {file->file.name.endsWith(".json")}?.sortedBy {it.name} ?: return null
        if(!canWrite()) {mutable.value=current.copy(phase=BackupRestorePhase.failed,failure=BackupRestoreFailure.journalBusy);return null}
        val ticket=revision;val flag=canceled
        mutable.value=current.copy(phase=BackupRestorePhase.restoring)
        scope.launch(start=CoroutineStart.LAZY) {
            try {
                val result=target.restore(files) {if(flag.get()) throw CancellationException("Restore canceled")}
                synchronized(monitor) {if(ticket==revision) mutable.value=current.copy(phase=BackupRestorePhase.complete,result=result)}
            } catch(error:Throwable) {failed(ticket,flag,error)}
            finally {synchronized(monitor) {if(ticket==revision) {directory?.deleteRecursively();directory=null}}}
        }.also {job->work=job;job.invokeOnCompletion {synchronized(monitor) {if(work===job) work=null}};job.start()}
    }

    private fun failed(ticket:Long,flag:AtomicBoolean,error:Throwable)=synchronized(monitor) {
        if(ticket!=revision) return@synchronized
        val stopped=flag.get() || error is CancellationException
        mutable.value=mutable.value.copy(phase=if(stopped) BackupRestorePhase.canceled else BackupRestorePhase.failed,failure=if(stopped) null else failure(error))
    }

    fun cancel()=synchronized(monitor) {
        when(mutable.value.phase) {
            BackupRestorePhase.reading->{
                // Providers may ignore close; the signal releases a blocked read, and reads hold no write authority.
                canceled.set(true);work?.cancel();revision++;directory?.deleteRecursively();directory=null
                val inputs=selected;scope.launch {inputs.forEach {runCatching {it.cancel()}}}
                mutable.value=BackupRestoreState(BackupRestorePhase.canceled)
            }
            BackupRestorePhase.review->{canceled.set(true);directory?.deleteRecursively();directory=null;mutable.value=mutable.value.copy(phase=BackupRestorePhase.canceled)}
            // Never cancel across the commit boundary: the transaction checks this flag and rolls back.
            BackupRestorePhase.restoring->{canceled.set(true);mutable.value=mutable.value.copy(phase=BackupRestorePhase.canceling)}
            else->Unit
        }
    }
    fun discard()=synchronized(monitor) {
        if(blocked>0 || mutable.value.busy) return@synchronized
        revision++;directory?.deleteRecursively();directory=null;mutable.value=BackupRestoreState()
    }
    /** Clear All Data waits for an active restore (cancelled, so it rolls back) and blocks new ones throughout. */
    suspend fun clearAll(clear:suspend ()->Unit) {
        synchronized(monitor) {blocked++;cancel()}
        val operation=scope.async {
            clears.lock()
            try {
                synchronized(monitor) {work}?.join()
                clear()
            } finally {
                synchronized(monitor) {revision++;directory?.deleteRecursively();directory=null;work=null;blocked--;mutable.value=BackupRestoreState()}
                clears.unlock()
            }
        }
        withContext(NonCancellable) {operation.await()}
    }
    internal fun close() {scope.cancel()}

    companion object {
        /** Copy at most one byte past the backup limit; false means the document is larger than any backup file. */
        internal fun copyBounded(input:InputStream,file:File,check:()->Unit):Boolean {
            val limit=WheredayBackupFormat.maximumFileBytes+1;var total=0L;val buffer=ByteArray(64*1024)
            file.outputStream().use {out->
                while(total<limit) {
                    check()
                    val n=input.read(buffer,0,minOf(buffer.size.toLong(),limit-total).toInt());if(n<0) break
                    out.write(buffer,0,n);total+=n
                }
            }
            return total<limit
        }
        internal fun failure(error:Throwable):BackupRestoreFailure=when {
            error is WheredayBackupException->BackupRestoreFailure.valueOf(error.problem.name)
            error.javaClass.name.contains("SQLite") || error is android.database.SQLException->BackupRestoreFailure.storage
            error is IOException->BackupRestoreFailure.unreadable
            else->BackupRestoreFailure.unreadable
        }
    }
}
