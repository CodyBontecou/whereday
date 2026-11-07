package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import java.time.*
import java.util.UUID
import java.util.Locale

data class ImportResult(val visits: List<Visit>, val points: List<TrackPoint>, val errors: List<String> = emptyList(), val rejected:Int=errors.size,val source:HistoryImportSource=HistoryImportSource.whereday,val unsupported:Int=0)

object HistoryImporter {
    fun detect(text:String,checkpoint:()->Unit={}):ExportFormat {
        val value=text.trimStart().removePrefix("\uFEFF").trimStart()
        if((value.startsWith('{') || value.startsWith('['))) return ExportFormat.json
        val first=value.lineSequence().firstOrNull {checkpoint();it.isNotBlank() && !it.startsWith('#')}.orEmpty()
        if(runCatching {CsvReader.read(first,checkpoint).firstOrNull()?.firstOrNull()}.getOrNull() in setOf("arrived_at","timestamp")) return ExportFormat.csv
        return if(value.lineSequence().any {line->
            checkpoint();val trimmed=line.trim()
            trimmed.startsWith("- **Arrived:**") || (trimmed.startsWith('|') && (MarkdownHistoryReader.cells(trimmed,checkpoint).firstOrNull() in setOf("Arrived","Time") || MarkdownHistoryReader.cells(trimmed,checkpoint).firstOrNull()?.matches(Regex("[0-9]{1,2}:[0-9]{2}.*"))==true))
        }) ExportFormat.markdown else ExportFormat.csv
    }
    /** Detects from a repeatable, caller-owned reader without retaining a complete file. */
    fun detect(reader:java.io.Reader,checkpoint:()->Unit={}):ExportFormat {
        val prefix=java.io.PushbackReader(ImportCheckpointReader(reader,checkpoint).buffered(),1)
        var bom=false
        while(true) {
            val next=prefix.read();if(next<0) return ExportFormat.csv
            if(next.toChar().isWhitespace()) continue
            if(next=='\uFEFF'.code && !bom) {bom=true;continue}
            if(next=='{'.code || next=='['.code) return ExportFormat.json
            prefix.unread(next);break
        }
        for(line in ImportLineReader(prefix).buffered().lineSequence()) {
            checkpoint();val value=line.trim()
            if(value.isBlank() || value.startsWith('#')) continue
            val first=runCatching {CsvReader.read(value,checkpoint).firstOrNull()?.firstOrNull()}.getOrElse {
                if(it is java.util.concurrent.CancellationException || it is ImportRecordTooLarge) throw it
                null
            }
            if(first in setOf("arrived_at","timestamp")) return ExportFormat.csv
            if(value.startsWith("- **Arrived:**")) return ExportFormat.markdown
            if(value.startsWith('|')) {
                val firstCell=MarkdownHistoryReader.cells(value,checkpoint).firstOrNull()
                if(firstCell in setOf("Arrived","Time") || firstCell?.matches(Regex("[0-9]{1,2}:[0-9]{2}.*"))==true) return ExportFormat.markdown
            }
        }
        return ExportFormat.csv
    }
    fun parse(text: String, format: ExportFormat,locale:Locale=Locale.getDefault(),zone:ZoneId=ZoneId.systemDefault(),checkpoint:()->Unit={}): ImportResult {
        val visits=mutableListOf<Visit>();val points=mutableListOf<TrackPoint>()
        val scan=scanSource({text.reader()},format,locale,zone,checkpoint,visits::add,points::add)
        return ImportResult(if(scan.source==HistoryImportSource.whereday) visits else visits.sortedBy {it.arrivedAt},if(scan.source==HistoryImportSource.whereday) points else points.sortedBy {it.time},scan.errors,scan.rejected,scan.source,scan.unsupported)
    }
    /** Source detection uses a separate caller-owned reader so Google root priority does not
     * depend on JSON property order. Scratch must be a fresh, private, disposable file. */
    fun scanSource(open:()->java.io.Reader,format:ExportFormat,locale:Locale=Locale.getDefault(),zone:ZoneId=ZoneId.systemDefault(),
        checkpoint:()->Unit={},onVisit:(Visit)->Unit={},onPoint:(TrackPoint)->Unit={},
        scratch:()->java.io.File={java.io.File.createTempFile("whereday-google-row-",".json")},onSource:(HistoryImportSource)->Unit={},onIdentity:(Boolean)->Unit={}):ImportScan {
        if(format!=ExportFormat.json) {onSource(HistoryImportSource.whereday);return open().use {scanRecords(it,format,locale,zone,checkpoint,onVisit,onPoint,onIdentity)}}
        val source=open().use {GoogleTimelineReader.source(it,checkpoint)};onSource(source)
        if(source==HistoryImportSource.whereday) return open().use {scanRecords(it,format,locale,zone,checkpoint,onVisit,onPoint,onIdentity)}
        val output=ImportScanCollector({onIdentity(false);onVisit(it)},{onIdentity(false);onPoint(it)})
        open().use {GoogleTimelineReader.scan(it,source,output,checkpoint,scratch)}
        checkpoint();return output.finish().copy(source=source)
    }
    /** Consumes records incrementally without retaining the document or previous records.
     * The caller owns the reader and must stage emissions until the complete scan succeeds.
     * Sink failures propagate; they must never be mistaken for invalid source rows.
     */
    fun scan(reader:java.io.Reader,format:ExportFormat,locale:Locale=Locale.getDefault(),zone:ZoneId=ZoneId.systemDefault(),
        checkpoint:()->Unit={},onVisit:(Visit)->Unit={},onPoint:(TrackPoint)->Unit={}):ImportScan = scanRecords(reader,format,locale,zone,checkpoint,onVisit,onPoint) {}
    private fun scanRecords(reader:java.io.Reader,format:ExportFormat,locale:Locale,zone:ZoneId,checkpoint:()->Unit,
        onVisit:(Visit)->Unit,onPoint:(TrackPoint)->Unit,onIdentity:(Boolean)->Unit):ImportScan {
        val output=ImportScanCollector(onVisit,onPoint)
        fun visitRow(fields:Map<String,String>,record:HistoryImportRecordKind,number:Int) {
            val value=output.decode(record,number) {VisitAuditImport.apply(visit(fields,format==ExportFormat.json),fields["visit_metadata"])} ?: return
            onIdentity(!fields["id"].isNullOrEmpty());output.visit(value)
        }
        fun pointRow(fields:Map<String,String>,record:HistoryImportRecordKind,number:Int) {
            val value=output.decode(record,number) {point(fields)} ?: return
            onIdentity(!fields["id"].isNullOrEmpty());output.point(value)
        }
        val checked=ImportCheckpointReader(reader,checkpoint)
        when(format) {
            ExportFormat.json->StreamingHistoryReader.json(checked,jsonKeys,checkpoint) {kind,index,fields,issue->
                val record=if(kind=="visits") HistoryImportRecordKind.visit else HistoryImportRecordKind.point
                if(issue!=null) output.error(record,index,issue)
                else if(kind=="visits") visitRow(fields,record,index) else pointRow(fields,record,index)
            }
            ExportFormat.csv->{
                var headers:List<String>?=null
                CsvReader.scan(checked,checkpoint) row@ {index,row->
                    if(row.all {it.isBlank()} || row.firstOrNull()?.startsWith('#')==true) return@row
                    if(row.firstOrNull() in listOf("arrived_at","timestamp")) {headers=row;return@row}
                    val columns=headers ?: return@row
                    if(row.size!=columns.size) output.error(HistoryImportRecordKind.row,index,HistoryImportProblem.columnCount,"Column count does not match header")
                    else if(columns.distinct().size!=columns.size) output.error(HistoryImportRecordKind.row,index,HistoryImportProblem.duplicateColumns,"Duplicate CSV columns")
                    else {
                        val fields=columns.zip(row).toMap()
                        if("arrived_at" in fields) visitRow(fields,HistoryImportRecordKind.row,index) else pointRow(fields,HistoryImportRecordKind.row,index)
                    }
                }
            }
            ExportFormat.markdown->MarkdownHistoryReader(locale,zone).scan(checked,
                {fields,line->visitRow(fields,HistoryImportRecordKind.line,line)},{fields,line->pointRow(fields,HistoryImportRecordKind.line,line)},
                {line,failure->output.error(HistoryImportRecordKind.line,line,failure)},checkpoint)
            else->error("Only JSON, CSV and Markdown history files can be imported")
        }
        checkpoint()
        return output.finish()
    }
    // Ignore future structured fields without letting an object/array masquerade as a known
    // scalar. JSON null is absence; literal text such as "null" is still user data.
    private val jsonKeys=setOf("id","latitude","longitude","arrivedAt","arrived_at","departedAt","departed_at",
        "locationName","location_name","address","notes","source","confirmationStatus","confirmation_status",
        "originalLatitude","originalLongitude","originalLocationName","original_location_name","originalAddress","original_address",
        "detectedLatitude","detectedLongitude","detectedLocationName","detectedAddress","confirmedAt","updatedAt","placeSource","placeDistanceMeters",
        "timestamp","timestampUnix","timestamp_unix","horizontalAccuracy","horizontal_accuracy","altitude","speed","isOutlier","is_outlier")
    private fun Map<String,String>.field(vararg names:String): String? = names.firstNotNullOfOrNull { this[it]?.takeIf { it.isNotEmpty() } }
    private fun invalid(problem:HistoryImportProblem,message:String):Nothing=throw InvalidHistoryRecord(problem,message)
    private fun Map<String,String>.instant(vararg names:String) = field(*names)?.let { Instant.parse(it) }
    private fun Map<String,String>.number(vararg names:String) = field(*names)?.toDouble()?.also { if(!it.isFinite()) invalid(HistoryImportProblem.invalidNumber,"Invalid number") }
    private fun coordinate(data:Map<String,String>,lat:String="latitude",lon:String="longitude"):Coordinate {
        val latitude=data.number(lat) ?: invalid(HistoryImportProblem.missingCoordinate,"Missing latitude")
        val longitude=data.number(lon) ?: invalid(HistoryImportProblem.missingCoordinate,"Missing longitude")
        if(latitude !in -90.0..90.0) invalid(HistoryImportProblem.latitude,"Latitude must be from -90 to 90")
        if(longitude !in -180.0..180.0) invalid(HistoryImportProblem.longitude,"Longitude must be from -180 to 180")
        return Coordinate(latitude,longitude)
    }
    private fun identity(d:Map<String,String>, type:String):String {
        d.field("id")?.let { return runCatching {UUID.fromString(it).toString()}.getOrElse {invalid(HistoryImportProblem.invalidIdentifier,"Invalid identifier")} }
        val instant=if(type=="point") pointTime(d) else d.instant("arrivedAt","arrived_at") ?: invalid(HistoryImportProblem.missingTime,"Missing arrival")
        val departure=if(type=="visit") ":${d.instant("departedAt","departed_at") ?: "open"}" else ""
        return UUID.nameUUIDFromBytes("$type:$instant:${coordinate(d)}$departure".toByteArray(Charsets.UTF_8)).toString()
    }
    private fun visit(d:Map<String,String>,preserveEmptyText:Boolean=false):Visit {
        fun text(vararg names:String)=if(preserveEmptyText) names.firstNotNullOfOrNull {d[it]} else d.field(*names)
        val id=identity(d,"visit");val coordinate=coordinate(d)
        val arrivedAt=d.instant("arrivedAt","arrived_at") ?: invalid(HistoryImportProblem.missingTime,"Missing arrival")
        val departedAt=d.instant("departedAt","departed_at")
        if(departedAt!=null && departedAt<arrivedAt) invalid(HistoryImportProblem.departureBeforeArrival,"Departure is before arrival")
        return Visit(
        id=id, coordinate=coordinate, arrivedAt=arrivedAt, departedAt=departedAt,
        name=text("locationName","location_name"), address=text("address"), notes=text("notes"),
        source=d.field("source")?.let { runCatching { VisitSource.valueOf(it) }.getOrDefault(VisitSource.imported) } ?: VisitSource.imported,
        confirmation=d.field("confirmationStatus","confirmation_status")?.let { runCatching { Confirmation.valueOf(it) }.getOrDefault(Confirmation.unconfirmed) } ?: Confirmation.unconfirmed,
        originalCoordinate=if(d.number("originalLatitude") != null && d.number("originalLongitude") != null) coordinate(d,"originalLatitude","originalLongitude") else null,
        originalName=text("originalLocationName","original_location_name"), originalAddress=text("originalAddress","original_address"),
        detectedCoordinate=if(d.number("detectedLatitude") != null && d.number("detectedLongitude") != null) coordinate(d,"detectedLatitude","detectedLongitude") else null,
        detectedName=text("detectedLocationName"),detectedAddress=text("detectedAddress"),confirmedAt=d.instant("confirmedAt"),updatedAt=d.instant("updatedAt"),
        placeSource=text("placeSource"),placeDistanceMeters=d.number("placeDistanceMeters"),
    )
    }
    private fun pointTime(d:Map<String,String>):Instant {
        d.field("timestamp")?.let {runCatching {Instant.parse(it)}.getOrNull()?.let {time->return time}}
        val seconds=d.field("timestampUnix","timestamp_unix")?.toBigDecimal() ?: invalid(HistoryImportProblem.missingTime,"Missing or invalid timestamp")
        if(!(seconds>=java.math.BigDecimal.valueOf(Instant.MIN.epochSecond) &&
            seconds<java.math.BigDecimal.valueOf(Instant.MAX.epochSecond).add(java.math.BigDecimal.ONE))) invalid(HistoryImportProblem.invalidTime,"Timestamp outside supported range")
        // Avoid expanding a tiny scientific-notation value into an enormous power of ten.
        if(seconds.abs()<java.math.BigDecimal("0.000000001")) return Instant.EPOCH
        val whole=seconds.toBigInteger().longValueExact()
        val nanos=seconds.remainder(java.math.BigDecimal.ONE).movePointRight(9).toLong()
        return Instant.ofEpochSecond(whole,nanos)
    }
    private fun point(d:Map<String,String>):TrackPoint = TrackPoint(id=identity(d,"point"),coordinate=coordinate(d), time=pointTime(d),
        accuracy=d.number("horizontalAccuracy","horizontal_accuracy") ?: 0.0, altitude=d.number("altitude"),speed=d.number("speed"),outlier=d.field("isOutlier","is_outlier")?.lowercase() in listOf("true","yes","1"))
}
