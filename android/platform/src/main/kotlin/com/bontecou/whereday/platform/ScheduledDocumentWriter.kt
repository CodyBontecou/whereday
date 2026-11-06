package com.bontecou.whereday.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import com.bontecou.whereday.billing.Capability
import com.google.gson.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

enum class ScheduledDeliveryPhase { prepared, backedUp, writing, verified, completed, canceled }
data class ScheduledDeliveryState(val id:String,val parent:String,val name:String,val mime:String,
    val payload:DestinationFingerprint,val original:DestinationFingerprint?,val destination:String?,
    val backup:String?,val phase:ScheduledDeliveryPhase,val error:String?=null) {
    val active get()=phase!=ScheduledDeliveryPhase.completed && phase!=ScheduledDeliveryPhase.canceled
}

/** Durable provider-independent replacement. The last good bytes are verified in a sibling backup
 * before an existing document is truncated. Receipts survive process death; payloads never share.
 */
internal class ScheduledDocumentWriter(
    private val context:Context,private val authorize:suspend (Capability)->Unit,
    private val checkpoint:(ScheduledDeliveryPhase)->Unit={},
) {
    private val root=File(context.filesDir,"scheduled-delivery")
    private val generation=AtomicLong()
    companion object {private val processLock=Mutex()}
    private fun directory(id:String):File {
        require(UUID.fromString(id).toString()==id)
        return File(root,id)
    }
    private suspend fun <T> locked(block:suspend ()->T):T=withContext(Dispatchers.IO) {
        processLock.withLock {
            check(root.mkdirs() || root.isDirectory)
            RandomAccessFile(File(root,"writer.lock"),"rw").use {file ->file.channel.lock().use {currentCoroutineContext().ensureActive();block()}}
        }
    }
    suspend fun state(id:String):ScheduledDeliveryState?=locked {read(id)}
    suspend fun start(id:String,file:PreparedExportFile,parent:Uri,name:String=file.file.name,
                      existing:Uri?=null,expected:DestinationFingerprint?=null):ScheduledDeliveryState {
        access()
        val epoch=generation.get()
        return locked {
            checkCurrent(epoch)
            require(parent.scheme=="content" && DocumentsContract.isTreeUri(parent) && DocumentsContract.getDocumentId(parent).isNotBlank()) {"Choose a writable export folder."}
            require(name.isNotBlank() && name!="." && name!=".." && name.none {it=='/' || it=='\\' || it<' '} && name.toByteArray(Charsets.UTF_8).size<=240)
            require((existing==null)==(expected==null))
            require(existing==null || existing.scheme=="content" && existing.authority==parent.authority)
            check(read(id)==null) {"This export update already exists. Resume its saved request."}
            val pending=root.listFiles().orEmpty().filter {it.isDirectory}.mapNotNull {read(it.name)}
            check(pending.none {it.active && it.parent==parent.toString() && it.name==name}) {"Finish the pending update for this file first."}
            check(find(parent,name)==existing) {"The destination changed. Review it before exporting again."}
            val folder=directory(id);check(folder.mkdir())
            try {
                val payload=File(folder,"payload")
                file.file.inputStream().use {input ->copyToFile(input,payload,epoch)}
                val payloadHash=fingerprint(payload,epoch)
                check(payloadHash==DestinationFingerprint(file.bytes,file.sha256)) {"Prepared export changed or expired."}
                val original=existing?.let {uri ->
                    val old=File(folder,"original")
                    context.contentResolver.openInputStream(uri)?.use {copyToFile(it,old,epoch)} ?: error("The existing export could not be read.")
                    fingerprint(old,epoch).also {check(it==expected) {"The existing export changed. No file was replaced."}}
                }
                checkCurrent(epoch);access()
                ScheduledDeliveryState(id,parent.toString(),name,file.format.mime,payloadHash,original,existing?.toString(),null,ScheduledDeliveryPhase.prepared).also(::put)
            } catch(e:Throwable) {folder.deleteRecursively();throw e}
        }
    }
    suspend fun resume(id:String):ScheduledDeliveryState {
        val epoch=generation.get()
        return locked {
            var state=read(id) ?: error("Saved export update is unavailable.")
            if(state.phase==ScheduledDeliveryPhase.completed) return@locked cleanupCompleted(state,epoch)
            check(state.active) {"Export update was canceled. No further file was written."}
            try {
                access();checkCurrent(epoch)
                val folder=directory(id);val payload=File(folder,"payload")
                check(fingerprint(payload,epoch)==state.payload) {"Saved export payload is unavailable or changed."}
                val parent=Uri.parse(state.parent)
                if(state.phase==ScheduledDeliveryPhase.prepared) {
                    if(state.original!=null) {
                        val original=File(folder,"original")
                        check(fingerprint(original,epoch)==state.original) {"The preserved original is unavailable or changed."}
                        if(state.backup==null) {
                            val backupName="Whereday backup - ${state.id}.${state.name.substringAfterLast('.',"backup")}"
                            check(find(parent,backupName)==null) {"A backup with this name already exists. Review the pending export."}
                            val uri=DocumentsContract.createDocument(context.contentResolver,parent,state.mime,backupName) ?: error("Could not preserve the prior export.")
                            state=state.copy(backup=uri.toString());put(state)
                        }
                        val backup=Uri.parse(state.backup)
                        // A prior attempt may have died while writing its newly created backup.
                        check(prefixOrSame(backup,original,epoch)) {"The backup changed. It was preserved for review."}
                        write(original,backup,state.original!!,epoch)
                    }
                    state=state.copy(phase=ScheduledDeliveryPhase.backedUp,error=null);put(state);checkpoint(state.phase)
                }
                if(state.phase==ScheduledDeliveryPhase.backedUp) {
                    access();checkCurrent(epoch)
                    if(state.original!=null) {
                        check(fingerprint(Uri.parse(state.backup),epoch)==state.original) {"The preserved backup changed. The destination was not replaced."}
                        check(find(parent,state.name)?.toString()==state.destination && fingerprint(Uri.parse(state.destination),epoch)==state.original) {"The destination changed. Its prior version is preserved in the backup."}
                    } else {
                        if(state.destination==null) {
                            check(find(parent,state.name)==null) {"A file now uses this name. It was not replaced."}
                            val uri=DocumentsContract.createDocument(context.contentResolver,parent,state.mime,state.name) ?: error("Could not create the export document.")
                            state=state.copy(destination=uri.toString());put(state)
                        } else check(find(parent,state.name)?.toString()==state.destination) {"The newly created document moved or changed."}
                    }
                    state=state.copy(phase=ScheduledDeliveryPhase.writing,error=null);put(state);checkpoint(state.phase)
                }
                if(state.phase==ScheduledDeliveryPhase.writing) {
                    access();checkCurrent(epoch)
                    val uri=Uri.parse(requireNotNull(state.destination))
                    check(find(parent,state.name)==uri) {"The destination moved or changed. The saved update was preserved."}
                    val current=fingerprint(uri,epoch)
                    if(current!=state.payload) {
                        if(state.original!=null) check(fingerprint(Uri.parse(state.backup),epoch)==state.original) {"The preserved backup changed. Resume was stopped."}
                        // Only the captured old document or our matching partial write may be resumed.
                        check(current==state.original || prefixOrSame(uri,payload,epoch)) {"The destination changed. Resume was stopped to preserve it."}
                        write(payload,uri,state.payload,epoch)
                    }
                    state=state.copy(phase=ScheduledDeliveryPhase.verified,error=null);put(state);checkpoint(state.phase)
                }
                if(state.phase==ScheduledDeliveryPhase.verified) {
                    checkCurrent(epoch)
                    check(fingerprint(Uri.parse(state.destination),epoch)==state.payload) {"The verified destination changed before completion."}
                    state=state.copy(phase=ScheduledDeliveryPhase.completed,error=null);put(state);checkpoint(state.phase)
                }
                // A committed receipt always wins over cleanup failure; duplicate resumes never rewrite.
                cleanupCompleted(state,epoch)
            } catch(e:Throwable) {
                withContext(NonCancellable) {runCatching {
                    val durable=read(id)
                    if(durable?.active==true) put(durable.copy(error="Export update was interrupted. Resume it or review its preserved backup."))
                }}
                throw e
            }
        }
    }
    suspend fun cancel(id:String)=locked {
        val state=read(id) ?: return@locked
        if(state.phase==ScheduledDeliveryPhase.completed) return@locked
        put(state.copy(phase=ScheduledDeliveryPhase.canceled,error=null))
        File(directory(id),"payload").delete();File(directory(id),"original").delete()
        // Existing destinations and externally preserved backups remain the user's files.
    }
    suspend fun clear() {
        invalidate()
        locked {root.listFiles().orEmpty().filter {it.name!="writer.lock"}.forEach {it.deleteRecursively()}}
    }
    fun invalidate() {generation.incrementAndGet()}
    private suspend fun cleanupCompleted(state:ScheduledDeliveryState,epoch:Long):ScheduledDeliveryState {
        var result=state
        if(state.backup!=null && runCatching {
            val uri=Uri.parse(state.backup)
            fingerprint(uri,epoch)==state.original && DocumentsContract.deleteDocument(context.contentResolver,uri)
        }.getOrDefault(false)) {
            result=state.copy(backup=null);runCatching {put(result)}
        }
        File(directory(state.id),"payload").delete();File(directory(state.id),"original").delete()
        return result
    }
    private suspend fun access() {authorize(Capability.scheduledExport);authorize(Capability.fileExport)}
    private suspend fun checkCurrent(epoch:Long) {currentCoroutineContext().ensureActive();checkGeneration(epoch)}
    private fun checkGeneration(epoch:Long) {if(epoch!=generation.get()) throw CancellationException("Journal data was cleared")}
    private suspend fun copyToFile(input:java.io.InputStream,file:File,epoch:Long) {
        java.io.FileOutputStream(file).use {out ->
            val buffer=ByteArray(64*1024)
            while(true) {checkCurrent(epoch);val n=input.read(buffer);if(n<0) break;out.write(buffer,0,n)}
            out.flush();out.fd.sync()
        }
    }
    private suspend fun fingerprint(file:File,epoch:Long)=file.inputStream().use {hash(it,epoch)}
    private suspend fun fingerprint(uri:Uri,epoch:Long)=context.contentResolver.openInputStream(uri)?.use {hash(it,epoch)} ?: error("Destination read access was lost.")
    private suspend fun hash(input:java.io.InputStream,epoch:Long):DestinationFingerprint {
        val digest=MessageDigest.getInstance("SHA-256");var count=0L;val buffer=ByteArray(64*1024)
        while(true) {checkCurrent(epoch);val n=input.read(buffer);if(n<0) break;count+=n;digest.update(buffer,0,n)}
        return DestinationFingerprint(count,digest.digest().joinToString("") {"%02x".format(it.toInt() and 255)})
    }
    private suspend fun prefixOrSame(uri:Uri,expected:File,epoch:Long):Boolean=context.contentResolver.openInputStream(uri)?.use {input ->expected.inputStream().use expectedSource@{source ->
        val buffer=ByteArray(64*1024);val other=ByteArray(buffer.size)
        while(true) {
            checkCurrent(epoch);val n=input.read(buffer);if(n<0) return@expectedSource true
            var offset=0
            while(offset<n) {val read=source.read(other,offset,n-offset);if(read<0) return@expectedSource false;offset+=read}
            if((0 until n).any {buffer[it]!=other[it]}) return@expectedSource false
        }
        @Suppress("UNREACHABLE_CODE") false
    }} ?: false
    private suspend fun write(file:File,uri:Uri,expected:DestinationFingerprint,epoch:Long) {
        checkCurrent(epoch)
        context.contentResolver.openOutputStream(uri,"wt")?.use {out ->file.inputStream().use {input ->
            val buffer=ByteArray(64*1024)
            while(true) {checkCurrent(epoch);val n=input.read(buffer);if(n<0) break;out.write(buffer,0,n)}
            out.flush()
        }} ?: error("Destination write access was lost. The preserved backup was kept.")
        check(fingerprint(uri,epoch)==expected) {"Destination verification failed. The preserved backup was kept."}
    }
    private fun find(parent:Uri,name:String):Uri? {
        val children=DocumentsContract.buildChildDocumentsUriUsingTree(parent,DocumentsContract.getDocumentId(parent))
        val matches=mutableListOf<Uri>()
        context.contentResolver.query(children,arrayOf(DocumentsContract.Document.COLUMN_DOCUMENT_ID,DocumentsContract.Document.COLUMN_DISPLAY_NAME),null,null,null)?.use {c ->
            while(c.moveToNext()) if(c.getString(1)==name) matches+=DocumentsContract.buildDocumentUriUsingTree(parent,c.getString(0))
        } ?: error("The export folder could not be read.")
        check(matches.size<=1) {"Multiple documents use this name. Review the export folder."}
        return matches.singleOrNull()
    }
    private fun put(s:ScheduledDeliveryState) {
        val json=JsonObject().apply {
            addProperty("version",1);addProperty("id",s.id);addProperty("parent",s.parent);addProperty("name",s.name);addProperty("mime",s.mime)
            addProperty("bytes",s.payload.bytes);addProperty("hash",s.payload.sha256)
            s.original?.let {addProperty("oldBytes",it.bytes);addProperty("oldHash",it.sha256)}
            s.destination?.let {addProperty("destination",it)};s.backup?.let {addProperty("backup",it)}
            addProperty("phase",s.phase.name);s.error?.let {addProperty("error",it)}
        }
        val atomic=AtomicFile(File(directory(s.id),"state.json"));val out=atomic.startWrite()
        try {out.write(json.toString().toByteArray(Charsets.UTF_8));atomic.finishWrite(out)} catch(e:Throwable) {atomic.failWrite(out);throw e}
    }
    private fun read(id:String):ScheduledDeliveryState? {
        val atomic=AtomicFile(File(directory(id),"state.json"))
        val input=try {atomic.openRead()} catch(_:java.io.FileNotFoundException) {return null}
        val json=input.use {val bytes=it.readBytesLimited(65536);JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject}
        fun text(key:String):String {val v=json[key];require(v?.isJsonPrimitive==true && v.asJsonPrimitive.isString);return v.asString}
        fun number(key:String):Long {val v=json[key];require(v?.isJsonPrimitive==true && v.asJsonPrimitive.isNumber);return requireNotNull(v.asString.toLongOrNull()).also {require(it>=0)}}
        fun fingerprint(bytes:String,hash:String)=DestinationFingerprint(number(bytes),text(hash).also {require(it.matches(Regex("[0-9a-f]{64}")))})
        require(number("version")==1L && text("id")==id)
        val parent=text("parent").also {require(Uri.parse(it).scheme=="content" && DocumentsContract.isTreeUri(Uri.parse(it)))}
        val name=text("name").also {require(it.isNotBlank() && it!="." && it!=".." && it.none {c ->c=='/' || c=='\\' || c<' '} && it.toByteArray(Charsets.UTF_8).size<=240)}
        fun uri(key:String)=json[key]?.let {text(key).also {require(Uri.parse(it).scheme=="content" && Uri.parse(it).authority==Uri.parse(parent).authority)}}
        val phase=ScheduledDeliveryPhase.valueOf(text("phase"));val destination=uri("destination")
        require(phase !in setOf(ScheduledDeliveryPhase.writing,ScheduledDeliveryPhase.verified,ScheduledDeliveryPhase.completed) || destination!=null)
        return ScheduledDeliveryState(id,parent,name,text("mime"),fingerprint("bytes","hash"),json["oldHash"]?.let {fingerprint("oldBytes","oldHash")},destination,uri("backup"),phase,json["error"]?.let {text("error")})
    }
    private fun java.io.InputStream.readBytesLimited(max:Int):ByteArray {
        val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
        while(true) {val n=read(buffer);if(n<0) break;require(output.size()+n<=max);output.write(buffer,0,n)}
        return output.toByteArray()
    }
}
