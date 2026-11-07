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
data class ImportStageRow(val kind:ImportRecordKind,val id:String,val payload:String,val baseline:String?=null,val explicitIdentity:Boolean=true) {
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
data class ImportStageDuplicates(val visits:Int=0,val points:Int=0)
data class ImportChronologyCursor(val kind:ImportRecordKind,val seconds:Long,val nanos:Int,val sequence:Long):Comparable<ImportChronologyCursor> {
    override fun compareTo(other:ImportChronologyCursor)=compareValuesBy(this,other,{it.kind.ordinal},{it.seconds},{it.nanos},{it.sequence})
}
data class ImportChronologyRow(val row:ImportStageRow,val cursor:ImportChronologyCursor)
data class HistoryImportInspection(val existing:Int,val repeated:ImportStageDuplicates=ImportStageDuplicates(),val replaceable:Int=existing)
interface HistoryImportStage:Closeable {
    var source:HistoryImportSource
    /** The full candidate stream is immutable after seal; preview never deletes candidates. */
    fun chronologicalPage(after:ImportChronologyCursor?,limit:Int=200):List<ImportChronologyRow>
    fun resetAdmission()
    fun admitPreview(rows:List<ImportStageRow>,check:()->Unit):ImportStageDuplicates

    /** false keeps the first same-identity record; conflicting supplied identities throw. */
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
        var visits=0;var points=0;var repeated=0;var explicitIdentity=false;var from:Instant?=null;var until:Instant?=null
        // Records per civil year in the import's zone, for the review's coverage chart.
        val years=java.util.TreeMap<Int,Int>()
        fun year(value:Instant)=runCatching {value.atZone(zone).year}.getOrNull()?.let {years.merge(it,1,Int::plus)}
        fun time(value:Instant?) {if(value!=null) {
            require(runCatching {value.toEpochMilli()}.isSuccess) {"This file contains a timestamp outside the supported history range."}
            from=from?.let {minOf(it,value)} ?: value;until=until?.let {maxOf(it,value)} ?: value
        }}
        val scanned=HistoryImporter.scanSource({reader()},format,locale,zone,check,onVisit={visit->
            check();time(visit.arrivedAt);time(visit.departedAt)
            for(value in listOf(visit.confirmedAt,visit.updatedAt)) require(value==null || runCatching {value.toEpochMilli()}.isSuccess) {"This file contains a timestamp outside the supported history range."}
            if(stage.add(ImportStageRow(ImportRecordKind.visit,visit.id,ImportStageCodec.gson.toJson(visit),explicitIdentity=explicitIdentity))) {visits=Math.addExact(visits,1);year(visit.arrivedAt)} else repeated=Math.addExact(repeated,1)
        },onPoint={point->
            check();time(point.time)
            if(stage.add(ImportStageRow(ImportRecordKind.point,point.id,ImportStageCodec.gson.toJson(point),explicitIdentity=explicitIdentity))) {points=Math.addExact(points,1);year(point.time)} else repeated=Math.addExact(repeated,1)
        },scratch={File.createTempFile("google-row-",".json",directory)},onSource={stage.source=it},onIdentity={explicitIdentity=it})
        check();stage.finishRecords()
        check(source.delete() || !source.exists()) {"Import staging cleanup failed"}
        return PreparedHistoryImport(format,visits,points,repeated,scanned.rejected,scanned.errors,from,until,scanned.source,scanned.unsupported,years.toMap(),scanned.issues)
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
data class PreparedHistoryImport(val format:ExportFormat,val visits:Int,val points:Int,val repeated:Int,val rejected:Int,val errors:List<String>,val from:Instant?,val until:Instant?,val source:HistoryImportSource=HistoryImportSource.whereday,val unsupported:Int=0,val years:Map<Int,Int> = emptyMap(),val issues:List<com.bontecou.whereday.export.HistoryImportIssue> = emptyList())

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

/** Selection is an ephemeral read view; checksum validation still covers the complete review. */
internal class SelectedHistoryImportStage(private val stage:HistoryImportStage,private val selection:HistoryImportSelection,private val checkpoint:()->Unit={}):HistoryImportStage by stage {
    override fun page(after:Pair<ImportRecordKind,String>?,limit:Int):List<ImportStageRow> {
        var cursor=after
        while(true) {
            checkpoint();val rows=stage.page(cursor,limit);if(rows.isEmpty()) return emptyList()
            val selected=rows.filter {if(it.kind==ImportRecordKind.visit) selection.visits else selection.points}
            if(selected.isNotEmpty()) return selected
            cursor=rows.last().let {it.kind to it.id}
        }
    }
    override fun chronologicalPage(after:ImportChronologyCursor?,limit:Int):List<ImportChronologyRow> {
        var cursor=after
        while(true) {
            checkpoint();val rows=stage.chronologicalPage(cursor,limit);if(rows.isEmpty()) return emptyList()
            val selected=rows.filter {if(it.row.kind==ImportRecordKind.visit) selection.visits else selection.points}
            if(selected.isNotEmpty()) return selected
            cursor=rows.last().cursor
        }
    }
    override fun close() {} // The original workspace owns its lifetime.
}

internal suspend fun HistoryImportStage.inspectTimeline(check:()->Unit,existing:suspend(ImportStageRow)->Boolean):HistoryImportInspection {
    resetAdmission();var found=0;var visits=0;var points=0
    chronologicalBatches(check) {ordered->
        val pending=mutableListOf<ImportStageRow>()
        for(item in ordered) {check();if(existing(item.row)) found++ else pending+=item.row}
        val duplicates=admitPreview(pending,check);visits+=duplicates.visits;points+=duplicates.points
    }
    return HistoryImportInspection(found,ImportStageDuplicates(visits,points))
}
internal inline fun HistoryImportStage.chronologicalBatches(check:()->Unit,consume:(List<ImportChronologyRow>)->Unit) {
    var after:ImportChronologyCursor?=null
    while(true) {check();val rows=chronologicalPage(after);if(rows.isEmpty()) break
        check(after==null || rows.first().cursor>after) {"Import chronology did not advance"}
        consume(rows);after=rows.last().cursor
    }
}
