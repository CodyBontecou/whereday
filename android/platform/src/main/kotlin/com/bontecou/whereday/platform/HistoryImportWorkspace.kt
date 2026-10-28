package com.bontecou.whereday.platform

import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.*
import com.google.gson.*
import java.io.*
import java.nio.charset.CodingErrorAction
import java.security.MessageDigest
import java.time.*
import java.util.Locale

enum class ImportRecordKind {visit,point}
data class ImportStageRow(val kind:ImportRecordKind,val id:String,val payload:String,val baseline:String?=null) {
    fun visit():Visit=ImportStageCodec.gson.fromJson(payload,Visit::class.java).also {require(kind==ImportRecordKind.visit && it.id==id)}
    fun point():TrackPoint=ImportStageCodec.gson.fromJson(payload,TrackPoint::class.java).also {require(kind==ImportRecordKind.point && it.id==id)}
}
internal object ImportStageCodec {
    val gson:Gson=GsonBuilder().serializeNulls().disableHtmlEscaping()
        .registerTypeAdapter(Instant::class.java,JsonSerializer<Instant> {value,_,_->JsonPrimitive(value.toString())})
        .registerTypeAdapter(Instant::class.java,JsonDeserializer<Instant> {value,_,_->Instant.parse(value.asString)}).create()
    fun hash(value:Any):String {
        val bytes=MessageDigest.getInstance("SHA-256").digest(gson.toJson(value).toByteArray(Charsets.UTF_8))
        val digits="0123456789abcdef"
        return buildString(64) {for(byte in bytes) {append(digits[(byte.toInt() ushr 4) and 15]);append(digits[byte.toInt() and 15])}}
    }
}
interface HistoryImportStage:Closeable {
    /** false means an identical repeated record; conflicting values throw. */
    fun add(row:ImportStageRow):Boolean
    fun finishRecords()
    fun page(after:Pair<ImportRecordKind,String>?,limit:Int=200):List<ImportStageRow>
    fun baselines(rows:List<ImportStageRow>)
    fun seal(check:()->Unit)
    fun verify(check:()->Unit)
}
fun interface HistoryImportWorkspaceFactory {fun create():HistoryImportWorkspace;fun reap() {}}
/** Private disposable input and indexed review. No URI retention or automatic replay after restart. */
class HistoryImportWorkspace internal constructor(private val directory:File,val stage:HistoryImportStage,
    private val sourceOutput:(File)->OutputStream={it.outputStream()},private val released:()->Unit):Closeable {
    private val source=File(directory,"source")
    @Volatile private var closed=false
    @Volatile private var output:OutputStream?=null
    fun capture(input:InputStream,check:()->Unit) {
        val file=synchronized(this) {
            kotlin.check(!closed)
            val opened=try {sourceOutput(source)} catch(error:IOException) {throw ImportStagingWriteFailed(error)}
            ImportStagingOutput(opened).also {output=it}
        }
        try {
            val decoded=InputStreamReader(input,Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT))
            // Close the underlying file even if the encoder's flush/close itself fails.
            file.use {sink->OutputStreamWriter(sink,Charsets.UTF_8).use {out->val characters=CharArray(8192)
                while(true) {check();val size=decoded.read(characters);if(size<0) break;check();kotlin.check(!closed);out.write(characters,0,size)}
            }}
        } finally {output=null}
        check();kotlin.check(!closed)
    }
    private fun reader()=InputStreamReader(source.inputStream(),Charsets.UTF_8.newDecoder()
        .onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)).buffered()
    fun prepare(locale:Locale,zone:ZoneId,check:()->Unit):PreparedHistoryImport {
        val format=reader().use {HistoryImporter.detect(it,check)}
        var visits=0;var points=0;var repeated=0;var from:Instant?=null;var until:Instant?=null
        fun time(value:Instant?) {if(value!=null) {
            require(runCatching {value.toEpochMilli()}.isSuccess) {"This file contains a timestamp outside the supported history range."}
            from=from?.let {minOf(it,value)} ?: value;until=until?.let {maxOf(it,value)} ?: value
        }}
        val scanned=reader().use {reader->HistoryImporter.scan(reader,format,locale,zone,check,onVisit={visit->
            check();time(visit.arrivedAt);time(visit.departedAt)
            for(value in listOf(visit.confirmedAt,visit.updatedAt)) require(value==null || runCatching {value.toEpochMilli()}.isSuccess) {"This file contains a timestamp outside the supported history range."}
            if(stage.add(ImportStageRow(ImportRecordKind.visit,visit.id,ImportStageCodec.gson.toJson(visit)))) visits=Math.addExact(visits,1) else repeated=Math.addExact(repeated,1)
        },onPoint={point->
            check();time(point.time)
            if(stage.add(ImportStageRow(ImportRecordKind.point,point.id,ImportStageCodec.gson.toJson(point)))) points=Math.addExact(points,1) else repeated=Math.addExact(repeated,1)
        })}
        check();stage.finishRecords();check(source.delete() || !source.exists()) {"Import staging cleanup failed"}
        return PreparedHistoryImport(format,visits,points,repeated,scanned.rejected,scanned.errors,from,until)
    }
    override fun close() {
        synchronized(this) {
            if(closed) return
            closed=true
            try {try {output?.close()} finally {stage.close()}} finally {
                try {check(directory.deleteRecursively() || !directory.exists()) {"Import staging cleanup failed"}} finally {released()}
            }
        }
    }
}
data class PreparedHistoryImport(val format:ExportFormat,val visits:Int,val points:Int,val repeated:Int,val rejected:Int,val errors:List<String>,val from:Instant?,val until:Instant?)

internal inline fun HistoryImportStage.batches(check:()->Unit,consume:(List<ImportStageRow>)->Unit) {
    var after:Pair<ImportRecordKind,String>?=null
    while(true) {check();val rows=page(after);if(rows.isEmpty()) break;consume(rows);after=rows.last().let {it.kind to it.id}}
}

class ImportStagingWriteFailed(cause:IOException):IOException("Local import storage could not be prepared. Free some space and choose the file again.",cause)
private class ImportStagingOutput(private val output:OutputStream):OutputStream() {
    private inline fun write(block:()->Unit) {try {block()} catch(error:IOException) {throw ImportStagingWriteFailed(error)}}
    override fun write(value:Int)=write {output.write(value)}
    override fun write(bytes:ByteArray,offset:Int,length:Int)=write {output.write(bytes,offset,length)}
    override fun flush()=write {output.flush()}
    override fun close()=write {output.close()}
}
