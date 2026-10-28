package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import com.google.gson.GsonBuilder
import com.google.gson.stream.JsonWriter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.Writer
import java.time.Duration
import java.time.Instant

/** Ordered, repeatable cold reads from the same snapshot. A source must not retain all records. */
interface ExportRecordSource {
    suspend fun visits(consume:suspend (Visit)->Unit)
    suspend fun points(consume:suspend (TrackPoint)->Unit)
}
data class ExportCounts(val visits:Long,val points:Long)

object StreamingHistoryExporter {
    private val gson=GsonBuilder().disableHtmlEscaping().create()
    suspend fun write(writer:Writer,format:ExportFormat,source:ExportRecordSource,o:ExportOptions=ExportOptions(),generatedAt:Instant=Instant.now(),scratchDirectory:File?=null,progress:(ExportCounts)->Unit={}):ExportCounts {
        var visitCount=0L;var pointCount=0L
        suspend fun visits(consume:suspend (Visit)->Unit) {
            if(o.kind==DataKind.points) return
            source.visits { v -> currentCoroutineContext().ensureActive();if(o.accepts(v)) consume(v) }
        }
        suspend fun points(consume:suspend (TrackPoint)->Unit) {
            if(o.kind==DataKind.visits) return
            source.points { p -> currentCoroutineContext().ensureActive();if(o.accepts(p)) consume(p) }
        }
        fun visited() { visitCount++;if(visitCount%500==0L) progress(ExportCounts(visitCount,pointCount)) }
        fun pointed() { pointCount++;if(pointCount%500==0L) progress(ExportCounts(visitCount,pointCount)) }
        fun row(values:List<Any?>) { with(HistoryExporter) { writer.csv(values) } }
        fun tableRow(values:List<Any?>) { writer.append("| ").append(values.joinToString(" | ") { it?.toString()?.replace("\\","\\\\")?.replace("|","\\|")?.replace("\n","<br>")?.replace("\r","").orEmpty() }).append(" |\n") }
        fun tableHeader(values:List<String>) { tableRow(values);tableRow(values.map { "---" }) }
        fun jsonWriter()=JsonWriter(writer).apply { setIndent("  ");isHtmlSafe=false;serializeNulls=false }
        fun JsonWriter.record(value:Any) { gson.toJson(value,value.javaClass,this) }
        fun xml(value:String)=HistoryExporter.xml(value)
        val actual=if(format in listOf(ExportFormat.owntracks,ExportFormat.overland) && o.kind==DataKind.visits) ExportFormat.json else format
        when(actual) {
            ExportFormat.json -> {
                val json=jsonWriter();json.beginObject().name("exportDate").value(generatedAt.toString())
                if(o.kind!=DataKind.points) {
                    json.name("visits").beginArray();visits { json.record(HistoryExporter.visitMap(it,o));visited() };json.endArray().name("totalVisits").value(visitCount)
                }
                if(o.kind!=DataKind.visits) {
                    json.name("points").beginArray();points { json.record(HistoryExporter.pointMap(it,o));pointed() };json.endArray().name("totalPoints").value(pointCount)
                }
                json.endObject();json.flush()
            }
            ExportFormat.csv -> {
                if(o.kind==DataKind.all) {
                    var count=0L;visits { count++ }
                    writer.append("# Whereday Combined Export\n# Generated: $generatedAt\n\n# VISITS ($count)\n")
                }
                if(o.kind!=DataKind.points) {
                    row(buildList { addAll(listOf("arrived_at","departed_at"));if(o.visitDuration) add("duration_minutes");if(o.visitCoordinates) addAll(listOf("latitude","longitude"));if(o.visitName) add("location_name");if(o.visitAddress) add("address");if(o.visitNotes) add("notes");addAll(listOf("source","confirmation_status"));if(o.visitName) add("original_location_name");if(o.visitAddress) add("original_address") })
                    visits { v -> row(buildList { add(v.arrivedAt);add(v.departedAt);if(o.visitDuration) add(v.departedAt?.let { Duration.between(v.arrivedAt,it).seconds/60.0 });if(o.visitCoordinates) addAll(listOf(v.coordinate.latitude,v.coordinate.longitude));if(o.visitName) add(v.name);if(o.visitAddress) add(v.address);if(o.visitNotes) add(v.notes);addAll(listOf(v.source.name,v.confirmation.name));if(o.visitName) add(v.originalName);if(o.visitAddress) add(v.originalAddress) });visited() }
                }
                if(o.kind==DataKind.all) { var count=0L;points { count++ };writer.append("\n# LOCATION POINTS ($count)\n") }
                if(o.kind!=DataKind.visits) {
                    row(buildList { addAll(listOf("timestamp","timestamp_unix","latitude","longitude"));if(o.pointAltitude) add("altitude");if(o.pointSpeed) add("speed");if(o.pointAccuracy) add("horizontal_accuracy");if(o.pointOutlier) add("is_outlier") })
                    points { p -> row(buildList { addAll(listOf(p.time,p.time.toEpochMilli()/1000.0,p.coordinate.latitude,p.coordinate.longitude));if(o.pointAltitude) add(p.altitude);if(o.pointSpeed) add(p.speed);if(o.pointAccuracy) add(p.accuracy);if(o.pointOutlier) add(p.outlier) });pointed() }
                }
            }
            ExportFormat.markdown -> {
                writer.append("# Whereday Complete Export\n\n**Export Date:** $generatedAt\n\n")
                if(o.kind!=DataKind.points) {
                    writer.append("## Visits\n\n")
                    tableHeader(listOf("Arrived","Departed")+(if(o.visitDuration) listOf("Duration") else emptyList())+(if(o.visitCoordinates) listOf("Lat","Lon") else emptyList())+(if(o.visitName) listOf("Location") else emptyList())+(if(o.visitAddress) listOf("Address") else emptyList())+(if(o.visitNotes) listOf("Notes") else emptyList())+listOf("Source","Status"))
                    visits { v -> tableRow(buildList { add(v.arrivedAt);add(v.departedAt);if(o.visitDuration) add(v.departedAt?.let { "${Duration.between(v.arrivedAt,it).toMinutes()} min" });if(o.visitCoordinates) addAll(listOf(v.coordinate.latitude,v.coordinate.longitude));if(o.visitName) add(v.name);if(o.visitAddress) add(v.address);if(o.visitNotes) add(v.notes);addAll(listOf(v.source.name,v.confirmation.name)) });visited() }
                }
                if(o.kind!=DataKind.visits) {
                    writer.append("\n## Location Points\n\n")
                    tableHeader(listOf("Time","Lat","Lon")+(if(o.pointSpeed) listOf("Speed") else emptyList())+(if(o.pointAltitude) listOf("Altitude") else emptyList())+(if(o.pointAccuracy) listOf("Accuracy") else emptyList())+(if(o.pointOutlier) listOf("Outlier") else emptyList()))
                    points { p -> tableRow(buildList { add(p.time);addAll(listOf(p.coordinate.latitude,p.coordinate.longitude));if(o.pointSpeed) add(p.speed?.let { "$it m/s" });if(o.pointAltitude) add(p.altitude?.let { "$it m" });if(o.pointAccuracy) add("${p.accuracy} m");if(o.pointOutlier) add(if(p.outlier) "Yes" else "No") });pointed() }
                }
            }
            ExportFormat.geojson,ExportFormat.overland,ExportFormat.owntracks -> {
                val json=jsonWriter()
                when(actual) {
                    ExportFormat.geojson -> json.beginObject().name("type").value("FeatureCollection").name("features").beginArray()
                    ExportFormat.overland -> json.beginObject().name("locations").beginArray()
                    else -> json.beginArray()
                }
                if(actual==ExportFormat.geojson) visits { v -> if(o.visitCoordinates) { json.record(HistoryExporter.feature(v.coordinate,HistoryExporter.visitMap(v,o),null));visited() } }
                points { p ->
                    json.record(when(actual) {
                        ExportFormat.geojson -> HistoryExporter.feature(p.coordinate,HistoryExporter.pointMap(p,o),p.altitude.takeIf { o.pointAltitude })
                        ExportFormat.overland -> HistoryExporter.feature(p.coordinate,mapOf("timestamp" to p.time.toString(),"horizontal_accuracy" to p.accuracy.takeIf { o.pointAccuracy },"speed" to p.speed.takeIf { o.pointSpeed }),p.altitude.takeIf { o.pointAltitude })
                        else -> linkedMapOf<String,Any?>("_type" to "location","lat" to p.coordinate.latitude,"lon" to p.coordinate.longitude,"tst" to p.time.epochSecond,"tid" to "IM").apply {if(o.pointAccuracy) put("acc",p.accuracy);if(o.pointAltitude) put("alt",p.altitude);if(o.pointSpeed) put("vel",p.speed?.times(3.6))}
                    });pointed()
                }
                json.endArray();if(actual!=ExportFormat.owntracks) json.endObject();json.flush()
            }
            ExportFormat.gpx -> {
                val gpx=GpxWriter(writer,o,generatedAt)
                visits {if(gpx.visit(it)) visited()}
                points {gpx.point(it);pointed()}
                gpx.finish()
            }
            ExportFormat.kml -> {
                val kml=KmlWriter(writer,o,generatedAt)
                visits {if(kml.visit(it)) visited()}
                val context=currentCoroutineContext()
                KmlSessionSpool(kml,scratchDirectory) {context.ensureActive()}.use {spool->
                    points {spool.append(it);pointed()}
                    spool.finishSession()
                }
                kml.finish()
            }
        }
        currentCoroutineContext().ensureActive();writer.flush()
        return ExportCounts(visitCount,pointCount).also(progress)
    }
}
