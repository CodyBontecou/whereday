package com.bontecou.whereday.platform

import android.content.ClipData
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.core.content.FileProvider
import androidx.documentfile.provider.DocumentFile
import com.bontecou.whereday.billing.Capability
import com.bontecou.whereday.data.*
import com.bontecou.whereday.export.*
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.Writer
import java.security.MessageDigest
import java.time.Instant
import java.time.LocalDate
import java.util.TreeSet
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class ExportFileRequest(val formats:Set<ExportFormat>,val options:ExportOptions=ExportOptions(),val filenamePattern:String=FilenamePlanner.defaultPattern,val splitByDay:Boolean=false,val sessionId:String?=null,val generatedAt:Instant?=null,val outings:Boolean=false,val inference:InferenceRules?=InferenceRules()) {
    init {require(formats.isNotEmpty());require(!outings || sessionId==null) {"Use an individual outing request for a session-specific export."}}
}
class PreparedExportFile internal constructor(val file:File,val relativePath:String,val format:ExportFormat,val counts:ExportCounts,val bytes:Long,val sha256:String)
class PreparedExport internal constructor(val id:String,val files:List<PreparedExportFile>)
data class DestinationFingerprint(val bytes:Long,val sha256:String)
class PreparedScheduledUpdate internal constructor(val prepared:PreparedExport,val destination:Uri,val expectedExisting:DestinationFingerprint)
data class ScheduledDestination(val parent:Uri,val name:String,val existing:Uri?,val fingerprint:DestinationFingerprint?)

/** All external delivery passes the same paid gate, including delivery after picker return. */
class ExportFiles(private val context:Context,private val database:HistoryDatabase,private val authorize:suspend (Capability)->Unit,private val onFileExported:()->Unit) {
    constructor(context:Context,database:HistoryDatabase,authorize:suspend(Capability)->Unit):this(context,database,authorize,{})
    private fun recordFileExport() {runCatching {onFileExported()}}
    private val lock=Mutex()
    private val generation=AtomicLong()
    private val root=File(context.cacheDir,"exports")
    private val journal=ExportJournal(context,root)
    private val scheduledWriter=ScheduledDocumentWriter(context,authorize)
    fun invalidateScheduledDelivery() {scheduledWriter.invalidate()}
    suspend fun resolveScheduledDestination(plan:ScheduledExportPlan,tree:Uri):ScheduledDestination=withContext(Dispatchers.IO) {
        authorize(Capability.scheduledExport);authorize(Capability.fileExport)
        val epoch=generation.get()
        lock.withLock {
            checkCurrent(epoch)
            var parent=DocumentFile.fromTreeUri(context,tree) ?: error("Choose an export folder first.")
            check(parent.isDirectory && parent.canWrite()) {"The export folder is unavailable or read-only."}
            val path=FilenamePlanner.path(plan.filenamePattern,plan.format,plan.filenameDate.atZone(plan.options.zone).toLocalDate(),type=if(plan.outings) "outings" else plan.options.kind.name,now=plan.filenameDate,zone=plan.options.zone)
            val parts=path.split('/')
            for(part in parts.dropLast(1)) {
                checkCurrent(epoch)
                val next=parent.findFile(part) ?: parent.createDirectory(part) ?: error("The export subfolder could not be created.")
                check(next.isDirectory) {"A document uses the export subfolder name."};parent=next
            }
            val existing=parent.findFile(parts.last())
            check(existing==null || existing.isFile) {"A folder uses the export filename."}
            val fingerprint=existing?.let {file ->context.contentResolver.openInputStream(file.uri)?.use {input ->
                val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024);var bytes=0L
                while(true) {checkCurrent(epoch);val n=input.read(buffer);if(n<0) break;bytes+=n;hash.update(buffer,0,n)}
                DestinationFingerprint(bytes,hex(hash.digest()))
            } ?: error("The existing export could not be read.")}
            ScheduledDestination(parent.uri,parts.last(),existing?.uri,fingerprint)
        }
    }

    suspend fun startScheduledDelivery(id:String,prepared:PreparedExport,parent:Uri,existing:Uri?=null,expected:DestinationFingerprint?=null):ScheduledDeliveryState=withContext(Dispatchers.IO) {
        val epoch=generation.get()
        lock.withLock {
            checkCurrent(epoch)
            val file=prepared.files.single();validate(file)
            scheduledWriter.start(id,file,parent,existing=existing,expected=expected)
        }
    }
    suspend fun resumeScheduledDelivery(id:String):ScheduledDeliveryState=lock.withLock {scheduledWriter.resume(id)}
    suspend fun startScheduledMergedDelivery(id:String,update:PreparedScheduledUpdate,parent:Uri):ScheduledDeliveryState =
        startScheduledDelivery(id,update.prepared,parent,update.destination,update.expectedExisting)
    suspend fun scheduledDeliveryState(id:String):ScheduledDeliveryState?=lock.withLock {scheduledWriter.state(id)}
    suspend fun cancelScheduledDelivery(id:String)=lock.withLock {scheduledWriter.cancel(id)}
    suspend fun clearScheduledDeliveries()=lock.withLock {scheduledWriter.clear()}

    suspend fun prepare(request:ExportFileRequest,progress:(ExportCounts)->Unit={}):PreparedExport=withContext(Dispatchers.IO) {
        if(request.outings) return@withContext prepareOutings(request,progress)
        val displayLocale=AndroidHistoryLocale.capture(context)
        authorize(Capability.fileExport)
        val epoch=generation.get()
        lock.withLock {
            checkCurrent(epoch)
            root.mkdirs()
            cleanupExpiredLocked()
            val id=UUID.randomUUID().toString();val directory=File(root,id)
            check(directory.mkdir()) {"Not enough storage to prepare export"}
            try {
                val generated=request.generatedAt ?: Instant.now();val used=mutableSetOf<String>();val files=mutableListOf<PreparedExportFile>()
                database.readSnapshot { dao ->
                    val source=SnapshotExportSource(dao,request.options,request.sessionId) {checkGeneration(epoch)}
                    val days=TreeSet<LocalDate>()
                    if(request.splitByDay) {
                        if(request.options.kind!=DataKind.points) source.visits { if(request.options.accepts(it)) days+=it.arrivedAt.atZone(request.options.zone).toLocalDate() }
                        if(request.options.kind!=DataKind.visits) source.points { if(request.options.accepts(it)) days+=it.time.atZone(request.options.zone).toLocalDate() }
                    } else days+=generated.atZone(request.options.zone).toLocalDate()
                    for(day in days) {
                        val options=if(request.splitByDay) request.options.copy(from=maxOf(request.options.from,day.atStartOfDay(request.options.zone).toInstant()),until=minOf(request.options.until,day.plusDays(1).atStartOfDay(request.options.zone).toInstant().minusNanos(1))) else request.options
                        for(format in request.formats.sortedBy { it.ordinal }) {
                            checkCurrent(epoch)
                            val path=uniquePath(FilenamePlanner.path(request.filenamePattern,format,day,type=options.kind.name,now=if(request.splitByDay) day.atStartOfDay(options.zone).toInstant() else generated,zone=options.zone),used)
                            val file=File(directory,path);check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                            val counts=file.outputStream().bufferedWriter(Charsets.UTF_8).use { writer ->
                                StreamingHistoryExporter.write(writer,format,SnapshotExportSource(dao,options,request.sessionId) {checkGeneration(epoch)},options,generated,scratchDirectory=directory,locale=displayLocale) {
                                    checkGeneration(epoch);progress(it)
                                }
                            }
                            files+=PreparedExportFile(file,path,format,counts,file.length(),digest(file))
                        }
                    }
                }
                checkCurrent(epoch);authorize(Capability.fileExport);checkCurrent(epoch)
                PreparedExport(id,files).also {journal.save(it)}
            } catch(e:Throwable) { directory.deleteRecursively();throw e }
        }
    }

    suspend fun prepareCountryDays(bounds:CountryDayBounds):PreparedExport = prepareGenerated(ExportFormat.csv,"iso-me-country-days.csv") {writer,checkpoint,_ ->
        val now=Instant.now()
        database.readSnapshot {dao ->
            val accumulator=CountryDayAccumulator(now,bounds)
            val source=SnapshotExportSource(dao,ExportOptions(from=Instant.ofEpochMilli(Long.MIN_VALUE),until=now),onRead=checkpoint)
            source.visits {accumulator.add(it)};source.points {accumulator.add(it)}
            dao.stays().forEach {checkpoint();accumulator.add(it.value)}
            writeCountryDaysCsv(writer,accumulator.days(),checkpoint)
        }
        ExportCounts(0,0) // Country-date rows are neither GPS fixes nor visits.
    }

    /** Membership is a closed time window, including imported fixes with no session foreign key. */
    suspend fun prepareOuting(id:String,format:ExportFormat,options:ExportOptions=ExportOptions(),
        filenamePattern:String=FilenamePlanner.defaultPattern,sequence:Int=1,inferred:InferredOutingHeader?=null,
        generatedAt:Instant=Instant.now()):PreparedExport {
        val displayLocale=AndroidHistoryLocale.capture(context)
        require(inferred==null || inferred.id==id)
        var relativePath="outing.${format.extension}"
        return prepareGenerated(format,relativePath,finalPath={relativePath}) {writer,checkpoint,directory ->
            database.readSnapshot {dao ->
                val outing=SnapshotOutingExport(dao,generatedAt,checkpoint).individual(id,sequence,inferred,options)
                relativePath=OutingFilenamePlanner.path(filenamePattern,format,outing.summary,options,mutableSetOf())
                StreamingOutingExporter.write(writer,outing.summary,outing.source,format,options,generatedAt,scratchDirectory=directory,locale=displayLocale)
            }
        }
    }

    private suspend fun prepareOutings(request:ExportFileRequest,progress:(ExportCounts)->Unit):PreparedExport {
        val displayLocale=AndroidHistoryLocale.capture(context)
        authorize(Capability.fileExport)
        val epoch=generation.get();val coroutine=currentCoroutineContext()
        return lock.withLock {
            checkCurrent(epoch);check(root.mkdirs() || root.isDirectory);cleanupExpiredLocked()
            val id=UUID.randomUUID().toString();val directory=File(root,id);check(directory.mkdir()) {"Not enough storage to prepare export"}
            try {
                val generated=request.generatedAt ?: Instant.now();val used=mutableSetOf<String>();val files=mutableListOf<PreparedExportFile>();val formatNames=mutableMapOf<ExportFormat,MutableSet<String>>()
                val checkpoint={coroutine.ensureActive();checkGeneration(epoch)}
                database.readSnapshot {dao ->
                    val snapshot=SnapshotOutingExport(dao,generated,checkpoint)
                    val selected=snapshot.selected(request.options,request.inference,request.splitByDay)
                    val groups=if(request.splitByDay) selected.map {listOf(it)} else listOf(selected)
                    for(group in groups) {
                        val source=snapshot.union(group)
                        for(format in request.formats.sortedBy {it.ordinal}) {
                            checkpoint()
                            val planned=if(request.splitByDay) OutingFilenamePlanner.path(request.filenamePattern,format,group.single().summary,request.options,formatNames.getOrPut(format) {mutableSetOf()})
                                else FilenamePlanner.path(request.filenamePattern,format,generated.atZone(request.options.zone).toLocalDate(),type="outings",now=generated,zone=request.options.zone)
                            val path=OutingFilenamePlanner.acrossFormats(planned,format,used)
                            val file=File(directory,path);check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                            val counts=file.bufferedWriter(Charsets.UTF_8).use {writer ->
                                StreamingOutingExporter.write(writer,group.map {it.summary},source,format,request.options,generated,scratchDirectory=directory,locale=displayLocale)
                            }
                            files+=PreparedExportFile(file,path,format,counts,file.length(),digest(file));progress(counts)
                        }
                    }
                }
                checkCurrent(epoch);authorize(Capability.fileExport);checkCurrent(epoch)
                PreparedExport(id,files).also {journal.save(it)}
            } catch(e:Throwable) {directory.deleteRecursively();throw e}
        }
    }

    private suspend fun prepareGenerated(format:ExportFormat,path:String,finalPath:()->String={path},write:suspend (Writer,()->Unit,File)->ExportCounts):PreparedExport=withContext(Dispatchers.IO) {
        authorize(Capability.fileExport)
        val epoch=generation.get();val coroutine=currentCoroutineContext()
        lock.withLock {
            checkCurrent(epoch);check(root.mkdirs() || root.isDirectory);cleanupExpiredLocked()
            val id=UUID.randomUUID().toString();val directory=File(root,id);check(directory.mkdir()) {"Not enough storage to prepare export"}
            try {
                val file=File(directory,path)
                require(file.canonicalPath.startsWith(directory.canonicalPath+File.separator))
                check(file.parentFile!!.mkdirs() || file.parentFile!!.isDirectory)
                val checkpoint={coroutine.ensureActive();checkGeneration(epoch)}
                val counts=file.bufferedWriter(Charsets.UTF_8).use {write(it,checkpoint,directory)}
                checkCurrent(epoch);authorize(Capability.fileExport);checkCurrent(epoch)
                val relative=finalPath();val delivered=File(directory,relative)
                require(delivered.canonicalPath.startsWith(directory.canonicalPath+File.separator)) {"Unsafe export path"}
                if(delivered!=file) {
                    check(delivered.parentFile!!.mkdirs() || delivered.parentFile!!.isDirectory)
                    check(file.renameTo(delivered)) {"Prepared export could not be named."}
                }
                val fingerprint=digest(delivered);checkCurrent(epoch)
                PreparedExport(id,listOf(PreparedExportFile(delivered,relative,format,counts,delivered.length(),fingerprint))).also {journal.save(it)}
            } catch(e:Throwable) {directory.deleteRecursively();throw e}
        }
    }

    /** Stage exactly the captured scheduled window, independent of manual split/format preferences. */
    suspend fun prepareScheduled(plan:ScheduledExportPlan):PreparedExport {
        authorize(Capability.scheduledExport)
        val prepared=prepare(ExportFileRequest(setOf(plan.format),plan.options,plan.filenamePattern,generatedAt=plan.filenameDate,outings=plan.outings,inference=plan.inference))
        try {authorize(Capability.scheduledExport);currentCoroutineContext().ensureActive();return prepared}
        catch(e:Throwable) {withContext(NonCancellable) {discard(prepared)};throw e}
    }

    /** Read an existing document and stage a merged replacement privately; never truncate a destination here.
     * The replacement writer must compare expectedExisting immediately before changing that document.
     */
    suspend fun mergeScheduled(plan:ScheduledExportPlan,delta:PreparedExport,existing:Uri):PreparedScheduledUpdate=withContext(Dispatchers.IO) {
        authorize(Capability.scheduledExport);authorize(Capability.fileExport)
        require(plan.usesDeltaWindow) {"This format requires a complete-day rewrite"}
        require(existing.scheme=="content")
        val epoch=generation.get()
        lock.withLock {
            checkCurrent(epoch)
            val input=delta.files.single();validate(input)
            require(input.format==plan.format)
            check(digest(input.file)==input.sha256) {"Prepared export changed. Prepare it again."}
            val id=UUID.randomUUID().toString();val directory=File(root,id)
            check(directory.mkdir()) {"Not enough storage to merge export"}
            try {
                val old=File(directory,".existing")
                context.contentResolver.openInputStream(existing)?.use {source ->old.outputStream().use {out ->
                    val buffer=ByteArray(64*1024)
                    while(true) {checkCurrent(epoch);val n=source.read(buffer);if(n<0) break;out.write(buffer,0,n)}
                }} ?: error("The existing export could not be read. Choose the folder again.")
                val fingerprint=DestinationFingerprint(old.length(),digest(old))
                val output=File(directory,input.relativePath)
                require(output.canonicalPath.startsWith(directory.canonicalPath+File.separator)) {"Unsafe export path"}
                check(output.parentFile!!.mkdirs() || output.parentFile!!.isDirectory)
                DiskMergeIdentityStore(File(directory,".identities.db")).use {identities ->
                    output.bufferedWriter(Charsets.UTF_8).use {destination ->
                        val guarded=object:Writer() {
                            override fun write(chars:CharArray,offset:Int,length:Int) {checkGeneration(epoch);destination.write(chars,offset,length)}
                            override fun flush() {checkGeneration(epoch);destination.flush()}
                            override fun close() {destination.close()}
                        }
                        ScheduledExportMerger.merge(plan.format,old,input.file,guarded,identities)
                    }
                }
                check(old.delete()) {"Could not clean up private merge staging"}
                val result=PreparedExport(id,listOf(PreparedExportFile(output,input.relativePath,input.format,input.counts,output.length(),digest(output))))
                checkCurrent(epoch);authorize(Capability.scheduledExport);authorize(Capability.fileExport);checkCurrent(epoch)
                journal.save(result)
                PreparedScheduledUpdate(result,existing,fingerprint)
            } catch(e:Throwable) {directory.deleteRecursively();throw e}
        }
    }

    suspend fun pickerState():ExportPickerState?=withContext(Dispatchers.IO) {lock.withLock {journal.current()}}

    suspend fun beginPicker(prepared:PreparedExport):ExportPickerState=withContext(Dispatchers.IO) {
        authorize(Capability.fileExport)
        lock.withLock {
            check(journal.current()?.active!=true) {"Finish or cancel the pending export first."}
            val file=prepared.files.singleOrNull() ?: throw ExportDeliveryException(ExportDeliveryNotice.multipleFiles)
            validate(file);if(digest(file.file)!=file.sha256) throw ExportDeliveryException(ExportDeliveryNotice.preparedUnavailable)
            ExportPickerState(prepared.id,file.file.name,file.format.mime,ExportPickerPhase.awaitingDestination).also {journal.begin(it)}
        }
    }

    suspend fun pickerIntent(id:String):Intent=withContext(Dispatchers.IO) {
        authorize(Capability.fileExport)
        lock.withLock {
            val state=journal.picker(id) ?: error("Saved export request is unavailable.")
            check(state.active)
            val file=journal.prepared(id).files.single();validate(file)
            if(digest(file.file)!=file.sha256) throw ExportDeliveryException(ExportDeliveryNotice.preparedUnavailable)
            releaseGrant(state)
            journal.put(state.copy(phase=ExportPickerPhase.awaitingDestination,destination=null,persistedGrant=false,error=null,notice=null))
            Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
                addCategory(Intent.CATEGORY_OPENABLE);type=file.format.mime
                putExtra(Intent.EXTRA_TITLE,file.file.name)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
            }
        }
    }

    /** Called only for this app's CREATE_DOCUMENT launcher. Persist the returned URI before any write. */
    suspend fun pickerResult(id:String?,uri:Uri?,flags:Int):ExportPickerState?=withContext(Dispatchers.IO) {
        val epoch=generation.get()
        lock.withLock {
            // A restored ActivityResult can arrive before Compose has loaded its request state.
            val resolved=id ?: uri?.let {value ->journal.receipts().firstOrNull {it.phase==ExportPickerPhase.completed && it.destination==value.toString()}?.id}
                ?: journal.current()?.id ?: error("No saved export request was found. No file was written.")
            val state=journal.picker(resolved) ?: error("Export request was cleared. No file was written.")
            check(state.phase!=ExportPickerPhase.canceled) {"Export request was canceled. No file was written."}
            if(state.phase==ExportPickerPhase.completed) {
                require(uri==null || state.destination==uri.toString()) {"This export request already completed."}
                return@withLock state
            }
            if(uri==null) {cancelPickerLocked(state);return@withLock null}
            require(state.phase==ExportPickerPhase.awaitingDestination) {"This picker result has already been received. Resume the saved request."}
            require(uri.scheme=="content")
            val access=flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            val canPersist=flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION!=0 && access!=0
            var writing=state.copy(phase=ExportPickerPhase.writing,destination=uri.toString(),persistedGrant=canPersist,error=null,notice=null)
            journal.put(writing)
            if(canPersist && runCatching {context.contentResolver.takePersistableUriPermission(uri,access);true}.getOrDefault(false)==false) {
                writing=writing.copy(persistedGrant=false);journal.put(writing)
            }
            finishPickerLocked(writing,epoch,false)
        }
    }

    suspend fun resumePicker(id:String):ExportPickerState=withContext(Dispatchers.IO) {
        val epoch=generation.get()
        lock.withLock {
            val state=journal.picker(id) ?: error("Saved export request is unavailable.")
            if(state.phase==ExportPickerPhase.completed) return@withLock state
            check(state.active) {"Export request was canceled. No file was written."}
            require(state.destination!=null) {"Choose a destination to continue this export."}
            finishPickerLocked(state,epoch,true)
        }
    }

    private suspend fun finishPickerLocked(state:ExportPickerState,epoch:Long,resuming:Boolean):ExportPickerState {
        val completed:ExportPickerState
        try {
            authorize(Capability.fileExport);checkCurrent(epoch)
            val file=journal.prepared(state.id).files.single();validate(file)
            if(digest(file.file)!=file.sha256) throw ExportDeliveryException(ExportDeliveryNotice.preparedUnavailable)
            val uri=Uri.parse(requireNotNull(state.destination))
            var alreadyWritten=false
            if(resuming) {
                val existing=context.contentResolver.openInputStream(uri)?.use {input ->file.file.inputStream().use {expected ->
                    val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024);val expectedBuffer=ByteArray(buffer.size);var count=0L
                    while(true) {
                        checkCurrent(epoch);val n=input.read(buffer);if(n<0) break;count+=n
                        if(count>file.bytes) throw ExportDeliveryException(ExportDeliveryNotice.destinationChanged)
                        var offset=0
                        while(offset<n) {val read=expected.read(expectedBuffer,offset,n-offset);check(read>0);offset+=read}
                        if((0 until n).any {buffer[it]!=expectedBuffer[it]}) throw ExportDeliveryException(ExportDeliveryNotice.destinationChanged)
                        hash.update(buffer,0,n)
                    }
                    count==file.bytes && hex(hash.digest())==file.sha256
                }
                } ?: throw ExportDeliveryException(ExportDeliveryNotice.destinationUnavailable)
                alreadyWritten=existing
            }
            journal.put(state.copy(phase=ExportPickerPhase.writing,error=null,notice=null))
            if(!alreadyWritten) writeNew(file,uri,epoch)
            checkCurrent(epoch)
            completed=state.copy(phase=ExportPickerPhase.completed,error=null,notice=null)
            journal.put(completed) // Commit receipt only after verified bytes; duplicate callbacks never truncate.
        } catch(e:Throwable) {
            withContext(NonCancellable+Dispatchers.IO) {runCatching {journal.put(state.copy(phase=ExportPickerPhase.failed,error=if(e is CancellationException) "Saving was interrupted. Resume or choose another destination." else "Saving could not finish. Resume or choose another destination; check the prior destination for an incomplete file.",notice=exportDeliveryNotice(e,ExportDeliveryNotice.failed)))}}
            throw e
        }
        releaseGrant(completed)
        runCatching {journal.put(completed.copy(persistedGrant=false));removePreparedLocked(state.id)}
        recordFileExport()
        return completed.copy(persistedGrant=false)
    }

    suspend fun cancelPicker(id:String)=withContext(Dispatchers.IO) {lock.withLock {journal.picker(id)?.let {cancelPickerLocked(it)}}}
    private fun cancelPickerLocked(state:ExportPickerState) {
        // External documents, including completed receipts, remain the user's files.
        releaseGrant(state);journal.cancel(state);removePreparedLocked(state.id)
    }
    private fun releaseGrant(state:ExportPickerState) {
        if(state.persistedGrant && state.destination!=null) runCatching {context.contentResolver.releasePersistableUriPermission(Uri.parse(state.destination),Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)}
    }

    suspend fun preview(format:ExportFormat,options:ExportOptions,outings:Boolean=false,inference:InferenceRules?=InferenceRules()):String=withContext(Dispatchers.IO) {
        val epoch=generation.get()
        val output=StringBuilder()
        val displayLocale=AndroidHistoryLocale.capture(context)
        val scratch=if(format in setOf(ExportFormat.kml,ExportFormat.markdown)) File(root,".${format.name}-preview").also {checkGeneration(epoch);check(it.mkdirs() || it.isDirectory)} else null
        val writer=object:Writer() {
            override fun write(buffer:CharArray,offset:Int,length:Int) {
                checkGeneration(epoch)
                val remaining=100_000-output.length
                output.append(buffer,offset,minOf(length,remaining))
                if(length>remaining) throw PreviewFull()
            }
            override fun flush() {};override fun close() {}
        }
        try {
            database.readSnapshot { dao ->
                val snapshot=if(outings) SnapshotOutingExport(dao,Instant.now()) {checkGeneration(epoch)} else null
                val selected=snapshot?.selected(options,inference,split=false,limit=100)
                val source=if(selected!=null) snapshot.union(selected) else SnapshotExportSource(dao,options) {checkGeneration(epoch)}
                val bounded=object:ExportRecordSource {
                    override suspend fun visits(consume:suspend (com.bontecou.whereday.domain.Visit)->Unit) {
                        var count=0
                        try {source.visits {if(outings || options.accepts(it)) {consume(it);if(++count>=2500) throw PreviewFull()}}} catch(_:PreviewFull) {if(output.length>=100_000) throw PreviewFull()}
                    }
                    override suspend fun points(consume:suspend (com.bontecou.whereday.domain.TrackPoint)->Unit) {
                        var count=0
                        try {source.points {if(outings || options.accepts(it)) {consume(it);if(++count>=2500) throw PreviewFull()}}} catch(_:PreviewFull) {if(output.length>=100_000) throw PreviewFull()}
                    }
                }
                if(selected!=null) StreamingOutingExporter.write(writer,selected.map {it.summary},bounded,format,options,scratchDirectory=scratch,locale=displayLocale)
                else StreamingHistoryExporter.write(writer,format,bounded,options,scratchDirectory=scratch,locale=displayLocale)
            }
        } catch(_:PreviewFull) { /* The UI deliberately displays a bounded, possibly truncated preview. */ }
        output.toString()
    }

    suspend fun share(prepared:PreparedExport):Intent=withContext(Dispatchers.IO) {
        authorize(Capability.fileExport)
        lock.withLock {
            require(prepared.files.isNotEmpty()) {"No records match the selected filters"}
            val uris=prepared.files.map { validate(it);check(digest(it.file)==it.sha256) {"Prepared export changed. Prepare it again."};FileProvider.getUriForFile(context,"${context.packageName}.exports",it.file) }
            val type=prepared.files.map { it.format.mime }.distinct().singleOrNull() ?: "*/*"
            Intent(if(uris.size==1) Intent.ACTION_SEND else Intent.ACTION_SEND_MULTIPLE).apply {
                this.type=type
                if(uris.size==1) putExtra(Intent.EXTRA_STREAM,uris.single()) else putParcelableArrayListExtra(Intent.EXTRA_STREAM,ArrayList(uris))
                clipData=ClipData.newUri(context.contentResolver,"Whereday export",uris.first()).apply {uris.drop(1).forEach {addItem(ClipData.Item(it))}}
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }

    /** Internal consumers get verified bytes under the same delivery lock and paid gate as sharing. */
    internal suspend fun readPrepared(prepared:PreparedExport,consume:suspend (PreparedExportFile,java.io.InputStream)->Unit)=withContext(Dispatchers.IO) {
        authorize(Capability.fileExport)
        val epoch=generation.get()
        lock.withLock {
            checkCurrent(epoch)
            require(prepared.files.isNotEmpty()) {"No records match the selected filters"}
            for(file in prepared.files) {
                validate(file);if(digest(file.file)!=file.sha256) throw ExportDeliveryException(ExportDeliveryNotice.preparedUnavailable)
                checkCurrent(epoch)
                file.file.inputStream().use {consume(file,it)}
            }
            checkCurrent(epoch);authorize(Capability.fileExport);checkCurrent(epoch)
        }
    }

    /** URI must be newly created by ACTION_CREATE_DOCUMENT, never an arbitrary existing document. */
    suspend fun saveNewDocument(file:PreparedExportFile,uri:Uri)=withContext(Dispatchers.IO) {
        try {
            authorize(Capability.fileExport)
            val epoch=generation.get()
            lock.withLock {checkCurrent(epoch);validate(file);writeNew(file,uri,epoch)}
        } catch(e:Throwable) {
            withContext(NonCancellable+Dispatchers.IO) {runCatching {DocumentsContract.deleteDocument(context.contentResolver,uri)}}
            throw e
        }
        recordFileExport()
    }

    /** Manual exports keep both files on collision. Existing user documents are never truncated. */
    suspend fun saveToFolder(prepared:PreparedExport,tree:Uri):List<Uri> =withContext(Dispatchers.IO) {
        authorize(Capability.fileExport)
        val epoch=generation.get()
        lock.withLock {
            checkCurrent(epoch)
            require(prepared.files.isNotEmpty()) {"No records match the selected filters"}
            val folder=DocumentFile.fromTreeUri(context,tree) ?: error("Choose an export folder again")
            check(folder.isDirectory && folder.canWrite()) {"Export folder is unavailable or read-only. Choose it again in Settings."}
            val completed=mutableListOf<Uri>()
            for(file in prepared.files) {
                checkCurrent(epoch);validate(file)
                var parent=folder
                val parts=file.relativePath.split('/')
                for(part in parts.dropLast(1)) {
                    val child=parent.findFile(part) ?: parent.createDirectory(part) ?: error("Could not create export subfolder")
                    check(child.isDirectory) {"A file already uses the export subfolder name"};parent=child
                }
                val used=parent.listFiles().mapNotNull {it.name}.toMutableSet()
                val name=uniquePath(parts.last(),used)
                val created=parent.createFile(file.format.mime,name) ?: error("Could not create export file")
                writeNew(file,created.uri,epoch);completed+=created.uri
            }
            completed
        }.also {recordFileExport()}
    }

    private suspend fun writeNew(file:PreparedExportFile,uri:Uri,epoch:Long) {
        try {
            require(uri.scheme=="content") {"Choose a document provider"}
            context.contentResolver.openOutputStream(uri,"wt")?.use { output ->
                file.file.inputStream().use { input ->
                    val buffer=ByteArray(64*1024)
                    while(true) {checkCurrent(epoch);val count=input.read(buffer);if(count<0) break;output.write(buffer,0,count)}
                };output.flush()
            } ?: error("The destination could not be opened")
            checkCurrent(epoch)
            val verified=context.contentResolver.openInputStream(uri)?.use {input ->
                val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024);var bytes=0L
                while(true) {checkCurrent(epoch);val count=input.read(buffer);if(count<0) break;bytes+=count;check(bytes<=file.bytes) {"Destination content did not match"};hash.update(buffer,0,count)}
                bytes==file.bytes && hex(hash.digest())==file.sha256
            } ?: false
            check(verified) {"Export verification failed. The incomplete destination was not marked successful."}
        } catch(e:Throwable) {
            withContext(NonCancellable+Dispatchers.IO) {
                runCatching { DocumentsContract.deleteDocument(context.contentResolver,uri) }
            }
            throw e
        }
    }

    private fun removePreparedLocked(id:String) {File(root,id).takeIf {it.canonicalFile.parentFile==root.canonicalFile}?.deleteRecursively();journal.removeBundle(id)}
    private fun cleanupExpiredLocked() {
        val pending=journal.current()?.takeIf {it.active}?.id
        root.listFiles().orEmpty().filter {it.name!=pending && it.lastModified()<System.currentTimeMillis()-86_400_000}.forEach {directory ->
            directory.walkTopDown().filter {it.isFile}.forEach {file ->runCatching {
                context.revokeUriPermission(FileProvider.getUriForFile(context,"${context.packageName}.exports",file),Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }}
            directory.deleteRecursively();runCatching {journal.removeBundle(directory.name)}
        }
    }
    suspend fun discard(prepared:PreparedExport)=withContext(Dispatchers.IO) {lock.withLock {removePreparedLocked(prepared.id)}}
    suspend fun clearCache() {
        generation.incrementAndGet()
        scheduledWriter.invalidate()
        withContext(Dispatchers.IO) {lock.withLock {
            if(root.isDirectory) root.walkTopDown().filter {it.isFile}.forEach { file ->
                runCatching {context.revokeUriPermission(FileProvider.getUriForFile(context,"${context.packageName}.exports",file),Intent.FLAG_GRANT_READ_URI_PERMISSION)}
            }
            journal.receipts().forEach(::releaseGrant)
            journal.clear();root.deleteRecursively();scheduledWriter.clear();Unit
        }}
    }
    private fun validate(file:PreparedExportFile) {require(file.file.canonicalPath.startsWith(root.canonicalPath+File.separator) && file.file.isFile && file.file.length()==file.bytes) {"Prepared export expired. Prepare it again."}}
    private fun checkGeneration(epoch:Long) {if(epoch!=generation.get()) throw CancellationException("Journal data was cleared")}
    private suspend fun checkCurrent(epoch:Long) {currentCoroutineContext().ensureActive();checkGeneration(epoch)}
    private suspend fun digest(file:File):String=file.inputStream().use {input ->
        val hash=MessageDigest.getInstance("SHA-256");val buffer=ByteArray(64*1024)
        while(true) {currentCoroutineContext().ensureActive();val count=input.read(buffer);if(count<0) break;hash.update(buffer,0,count)}
        hex(hash.digest())
    }
    private fun hex(bytes:ByteArray)=bytes.joinToString("") {"%02x".format(it.toInt() and 255)}
    private fun uniquePath(path:String,used:MutableSet<String>):String {
        if(used.add(path)) return path
        val extension=path.substringAfterLast('.',"");val base=if(extension.isEmpty()) path else path.dropLast(extension.length+1)
        var suffix=2
        while(true) {val candidate="$base ($suffix)"+(if(extension.isEmpty()) "" else ".$extension");if(used.add(candidate)) return candidate;suffix++}
    }
    private class PreviewFull:RuntimeException()
}
