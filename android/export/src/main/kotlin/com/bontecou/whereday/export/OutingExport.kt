package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import com.google.gson.GsonBuilder
import com.google.gson.stream.JsonWriter
import java.io.File
import java.io.Writer
import java.time.*
import java.time.format.DateTimeFormatterBuilder
import java.time.format.FormatStyle
import java.util.Locale
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Complete outing statistics; route records are reread from the same snapshot, never map samples. */
data class ExportableOuting(
    val id:String,val title:String,val sequenceNumber:Int,val startedAt:Instant,val endedAt:Instant?,
    val effectiveEndAt:Instant,val durationSeconds:Double,val distanceMeters:Double,
    val averageSpeedMetersPerSecond:Double?,val pointCount:Long,val outlierCount:Long,val visitCount:Long,
    val isInferred:Boolean,val isActive:Boolean,val notes:String?,val start:Coordinate?,val end:Coordinate?,
    val titleIsDefault:Boolean=false,
)

/** iOS uses the last route fix for an open outing's effective end, and all fixes if all are outliers. */
class OutingExportSummaryBuilder {
    private var first:TrackPoint?=null
    private var last:TrackPoint?=null
    private var lastGood:TrackPoint?=null
    private var allDistance=0.0
    private var goodDistance=0.0
    private var count=0L
    private var outliers=0L
    fun add(point:TrackPoint) {
        require(last?.let {point.time>=it.time}!=false)
        last?.let {allDistance+=it.coordinate.distanceTo(point.coordinate)}
        if(!point.outlier) {lastGood?.let {goodDistance+=it.coordinate.distanceTo(point.coordinate)};lastGood=point} else outliers++
        if(first==null) first=point
        last=point;count++
    }
    fun build(session:RecordingSession,sequence:Int,inferred:Boolean,now:Instant,visitCount:Long):ExportableOuting {
        require(sequence>0)
        val end=maxOf(session.startedAt,session.endedAt ?: last?.time ?: now)
        val duration=Duration.between(session.startedAt,end).toMillis()/1000.0
        val distance=if(lastGood==null) allDistance else goodDistance
        return ExportableOuting(session.id,session.name?.trim()?.takeIf {it.isNotEmpty()} ?: "Outing $sequence",sequence,
            session.startedAt,session.endedAt,end,duration,distance,distance.takeIf {it>0 && duration>0}?.div(duration),
            count,outliers,visitCount,inferred,!inferred && session.endedAt==null,session.notes?.trim()?.takeIf {it.isNotEmpty()},first?.coordinate,last?.coordinate,session.name?.trim().isNullOrEmpty())
    }
}

/** Summary formats follow the iOS outing contract; route formats intentionally contain points only. */
object StreamingOutingExporter {
    private val iso=DateTimeFormatterBuilder().appendInstant(3).toFormatter(Locale.US)
    private fun Instant.stamp()=iso.format(this)
    private fun number(value:Double,places:Int)=XmlExportValues.number(value,places)
    suspend fun write(writer:Writer,outing:ExportableOuting,source:ExportRecordSource,format:ExportFormat,
        options:ExportOptions=ExportOptions(),generatedAt:Instant=Instant.now(),scratchDirectory:File?=null,locale:Locale=Locale.getDefault()):ExportCounts =
        write(writer,listOf(outing),source,format,options,generatedAt,scratchDirectory,locale)

    suspend fun write(writer:Writer,outings:List<ExportableOuting>,source:ExportRecordSource,format:ExportFormat,
        options:ExportOptions=ExportOptions(),generatedAt:Instant=Instant.now(),scratchDirectory:File?=null,locale:Locale=Locale.getDefault()):ExportCounts {
        val ordered=outings.sortedWith(compareBy({it.startedAt},{it.id})).map {if(it.titleIsDefault) it.copy(title="Outing ${integer(it.sequenceNumber.toLong(),locale)}") else it}
        if(format !in setOf(ExportFormat.json,ExportFormat.csv,ExportFormat.markdown)) {
            return StreamingHistoryExporter.write(writer,format,source,options.copy(kind=DataKind.points,
                from=Instant.ofEpochMilli(Long.MIN_VALUE),until=Instant.ofEpochMilli(Long.MAX_VALUE),startTime=null,endTime=null,
                excludeOutliers=false,maximumAccuracy=null),generatedAt,scratchDirectory,locale)
        }
        when(format) {
            ExportFormat.json -> {
                val json=JsonWriter(writer).apply {setIndent("  ");isHtmlSafe=false;serializeNulls=false}
                val gson=GsonBuilder().disableHtmlEscaping().create()
                json.beginObject().name("exportDate").value(generatedAt.stamp()).name("totalOutings").value(ordered.size).name("outings").beginArray()
                ordered.forEach {outing ->currentCoroutineContext().ensureActive();val fields=fields(outing);gson.toJson(fields,fields.javaClass,json)}
                json.endArray().endObject();json.flush()
            }
            ExportFormat.csv -> {
                fun row(fields:List<Any?>) {writer.append(fields.joinToString(",") {value ->
                    val text=value?.toString().orEmpty()
                    if(text.any {it==',' || it=='"' || it=='\n' || it=='\r'}) "\"${text.replace("\"","\"\"")}\"" else text
                }).append('\n')}
                row(listOf("id","title","sequence_number","started_at","ended_at","effective_end_at","duration_seconds","distance_meters","average_speed_mps","point_count","outlier_count","visit_count","is_inferred","is_active","notes","start_latitude","start_longitude","end_latitude","end_longitude"))
                ordered.forEach {with(it) {
                    currentCoroutineContext().ensureActive()
                    row(listOf(id,title,sequenceNumber,startedAt.stamp(),endedAt?.stamp(),effectiveEndAt.stamp(),number(durationSeconds,0),number(distanceMeters,2),averageSpeedMetersPerSecond?.let {number(it,3)},pointCount,outlierCount,visitCount,isInferred,isActive,notes,start?.latitude,start?.longitude,end?.latitude,end?.longitude))
                }}
            }
            ExportFormat.markdown -> if(ordered.size==1) markdown(writer,ordered.single(),source,options,locale) else markdownTable(writer,ordered,options,generatedAt,locale)
            else -> error("Unsupported summary format")
        }
        writer.flush()
        return ExportCounts(ordered.sumOf {it.visitCount},ordered.sumOf {it.pointCount})
    }

    private fun fields(o:ExportableOuting)=with(o) {
        linkedMapOf<String,Any?>("id" to id,"title" to title,"sequenceNumber" to sequenceNumber,
            "startedAt" to startedAt.stamp(),"endedAt" to endedAt?.stamp(),"effectiveEndAt" to effectiveEndAt.stamp(),
            "durationSeconds" to durationSeconds,"distanceMeters" to distanceMeters,"averageSpeedMetersPerSecond" to averageSpeedMetersPerSecond,
            "pointCount" to pointCount,"outlierCount" to outlierCount,"visitCount" to visitCount,"isInferred" to isInferred,"isActive" to isActive,
            "notes" to notes,"startLatitude" to start?.latitude,"startLongitude" to start?.longitude,"endLatitude" to end?.latitude,"endLongitude" to end?.longitude)
    }
    private fun duration(value:Double,locale:Locale):String {
        val seconds=java.math.BigDecimal(value).setScale(0,java.math.RoundingMode.HALF_UP).longValueExact().coerceAtLeast(0);val h=seconds/3600;val m=seconds%3600/60
        return if(h>0) "${integer(h,locale)}h"+(if(m>0) " ${integer(m,locale)}m" else "") else if(m>0) "${integer(m,locale)}m" else "${integer(seconds,locale)}s"
    }
    private fun integer(value:Long,locale:Locale)=java.text.NumberFormat.getIntegerInstance(locale).format(value)
    private fun tableCell(value:String?)=MarkdownHistoryWriter.cell(value)
    private suspend fun markdownTable(w:Writer,outings:List<ExportableOuting>,options:ExportOptions,generatedAt:Instant,locale:Locale) {
        val dates=HistoryCalendarFormats(locale,options.zone)
        fun date(value:Instant)=dates.dateTime(value,FormatStyle.MEDIUM,FormatStyle.SHORT)
        w.append("# Whereday Outings Export\n\n**Export Date:** ${dates.dateTime(generatedAt,FormatStyle.LONG,FormatStyle.SHORT)}\n\n**Total Outings:** ${outings.size}\n\n---\n\n")
        w.append("| Start | End | Title | Duration | Distance | Points | Visits | Notes |\n|------|-----|-------|----------|----------|--------|--------|-------|\n")
        outings.forEach {o ->
            currentCoroutineContext().ensureActive()
            val cells=listOf(date(o.startedAt),o.endedAt?.let(::date) ?: if(o.isActive) "now" else date(o.effectiveEndAt),
                tableCell(o.title),duration(o.durationSeconds,locale),"${number(o.distanceMeters,0)} m",o.pointCount.toString(),o.visitCount.toString(),tableCell(o.notes))
            w.append("| ").append(cells.joinToString(" | ")).append(" |\n")
        }
    }

    private suspend fun markdown(w:Writer,o:ExportableOuting,source:ExportRecordSource,options:ExportOptions,locale:Locale) {
        fun quoted(value:String)="\""+value.replace("\\","\\\\").replace("\"","\\\"").replace("\n","\\n")+"\""
        fun yaml(key:String,value:String?) {
            val v=value?.replace("\r\n","\n")?.replace('\r','\n')
            w.append("$key: ")
            when {v.isNullOrEmpty()->w.append("null\n");'\n' in v->w.append("|-\n").append(v.split('\n').joinToString("\n") {"  $it"}).append('\n');else->w.append(quoted(v)).append('\n')}
        }
        fun numeric(key:String,value:Double?,places:Int) {w.append("$key: ${value?.let {number(it,places)} ?: "null"}\n")}
        fun cell(value:Any?)=MarkdownHistoryWriter.cell(value?.toString())
        fun row(values:List<Any?>) {w.append("| ").append(values.joinToString(" | ") {cell(it)}).append(" |\n")}
        fun header(values:List<String>) {row(values);w.append("|").append(values.joinToString("|") {"------"}).append("|\n")}
        val dates=HistoryCalendarFormats(locale,options.zone)
        fun date(value:Instant)=dates.dateTime(value,FormatStyle.MEDIUM,FormatStyle.SHORT)
        w.append("---\n");yaml("type","outing");yaml("app","Whereday");yaml("id",o.id);yaml("title",o.title)
        w.append("sequence: ${o.sequenceNumber}\n");yaml("start",o.startedAt.stamp());yaml("end",o.endedAt?.stamp());yaml("effective_end",o.effectiveEndAt.stamp())
        numeric("duration_seconds",o.durationSeconds,0);numeric("distance_meters",o.distanceMeters,2);numeric("average_speed_mps",o.averageSpeedMetersPerSecond,3)
        w.append("point_count: ${o.pointCount}\noutlier_count: ${o.outlierCount}\nvisit_count: ${o.visitCount}\nis_inferred: ${o.isInferred}\nis_active: ${o.isActive}\n")
        yaml("source",if(o.isInferred) "inferred" else "recorded");yaml("notes",o.notes)
        numeric("start_latitude",o.start?.latitude,6);numeric("start_longitude",o.start?.longitude,6);numeric("end_latitude",o.end?.latitude,6);numeric("end_longitude",o.end?.longitude,6)
        if(o.visitCount==0L) w.append("visits: []\n") else {w.append("visits:\n");source.visits {w.append("  - ${quoted(it.name ?: it.address ?: "Unknown Location")}\n")}}
        w.append("---\n\n# ${o.title}\n\n## Summary\n\n| Field | Value |\n|------|-------|\n")
        row(listOf("Start",date(o.startedAt)));row(listOf("End",o.endedAt?.let(::date) ?: if(o.isActive) "Now" else date(o.effectiveEndAt)))
        row(listOf("Duration",duration(o.durationSeconds,locale)));row(listOf("Distance","${number(o.distanceMeters,0)} m"));row(listOf("GPS points",o.pointCount));row(listOf("Visits",o.visitCount));row(listOf("Source",if(o.isInferred) "Inferred" else "Recorded"));w.append('\n')
        o.notes?.let {w.append("## Notes\n\n$it\n\n")}
        if(o.visitCount>0) {
            w.append("## Visits\n\n")
            header(buildList {addAll(listOf("Arrived","Departed"));if(options.visitDuration) add("Duration");if(options.visitCoordinates) addAll(listOf("Lat","Lon"));if(options.visitName) add("Location");if(options.visitAddress) add("Address");if(options.visitNotes) add("Notes");add("Visit Metadata")})
            source.visits {v ->row(buildList {add(date(v.arrivedAt));add(v.departedAt?.let(::date));if(options.visitDuration) add(v.departedAt?.let {"${number((XmlExportValues.swiftDateSeconds(it)-XmlExportValues.swiftDateSeconds(v.arrivedAt))/60.0,1)} min"});if(options.visitCoordinates) addAll(listOf(number(v.coordinate.latitude,6),number(v.coordinate.longitude,6)));if(options.visitName) add(v.name);if(options.visitAddress) add(v.address);if(options.visitNotes) add(v.notes);add(VisitAudit.json(v,options))})};w.append('\n')
        }
        if(o.pointCount>0) {
            w.append("## Route Points\n\n")
            header(buildList {addAll(listOf("Time","Lat","Lon"));if(options.pointSpeed) add("Speed");if(options.pointAltitude) add("Altitude");if(options.pointAccuracy) add("Accuracy");if(options.pointOutlier) add("Outlier")})
            source.points {p ->row(buildList {add(date(p.time));addAll(listOf(number(p.coordinate.latitude,6),number(p.coordinate.longitude,6)));if(options.pointSpeed) add(p.speed?.let {"${number(it,1)} m/s"});if(options.pointAltitude) add(p.altitude?.let {"${number(it,1)} m"});if(options.pointAccuracy) add("${number(p.accuracy,1)} m");if(options.pointOutlier) add(if(p.outlier) "yes" else "-")})};w.append('\n')
        }
    }
}
