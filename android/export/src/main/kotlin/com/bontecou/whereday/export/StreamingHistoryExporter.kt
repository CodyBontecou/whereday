package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import com.google.gson.GsonBuilder
import com.google.gson.stream.JsonWriter
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import java.io.File
import java.io.Writer
import java.time.Instant
import java.util.Locale

/** Ordered, repeatable cold reads from the same snapshot. A source must not retain all records. */
interface ExportRecordSource {
    suspend fun visits(consume:suspend (Visit)->Unit)
    suspend fun points(consume:suspend (TrackPoint)->Unit)
}
data class ExportCounts(val visits:Long,val points:Long)

object StreamingHistoryExporter {
    private val gson=GsonBuilder().disableHtmlEscaping().create()
    suspend fun write(writer:Writer,format:ExportFormat,source:ExportRecordSource,o:ExportOptions=ExportOptions(),generatedAt:Instant=Instant.now(),scratchDirectory:File?=null,locale:Locale=Locale.getDefault(),progress:(ExportCounts)->Unit={}):ExportCounts {
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
        fun jsonWriter()=JsonWriter(writer).apply { setIndent("  ");isHtmlSafe=false;serializeNulls=false }
        fun JsonWriter.record(value:Any) { gson.toJson(value,value.javaClass,this) }
        fun xml(value:String)=HistoryExporter.xml(value)
        val actual=if(format in listOf(ExportFormat.owntracks,ExportFormat.overland) && o.kind==DataKind.visits) ExportFormat.json else format
        when(actual) {
            ExportFormat.json -> {
                val json=jsonWriter();json.beginObject().name("exportDate").value(XmlExportValues.time.format(generatedAt))
                if(o.kind!=DataKind.points) {
                    json.name("visits").beginArray();visits {json.record(HistoryExporter.visitMap(it,o));visited()};json.endArray()
                    if(o.kind==DataKind.all) json.name("totalVisits").value(visitCount)
                }
                if(o.kind!=DataKind.visits) {
                    var first:Instant?=null;var last:Instant?=null
                    json.name("points").beginArray();points {p->json.record(HistoryExporter.pointMap(p,o));if(first==null) first=p.time;last=p.time;pointed()}
                    json.endArray().name("totalPoints").value(pointCount)
                    if(first!=null) {json.name("dateRange");json.record(HistoryTableRows.dateRange(first!!,last!!))}
                }
                json.endObject();json.flush()
            }
            ExportFormat.csv -> {
                if(o.kind==DataKind.all) {
                    var count=0L;visits {count++}
                    writer.append("# Whereday Combined Export\n# Generated: ${XmlExportValues.time.format(generatedAt)}\n\n# VISITS ($count)\n")
                }
                if(o.kind!=DataKind.points) {row(HistoryTableRows.visitHeader(o));visits {row(HistoryTableRows.visit(it,o));visited()}}
                if(o.kind==DataKind.all) {var count=0L;points {count++};writer.append("\n# LOCATION POINTS ($count)\n")}
                if(o.kind!=DataKind.visits) {row(HistoryTableRows.pointHeader(o));points {row(HistoryTableRows.point(it,o));pointed()}}
            }
            ExportFormat.markdown -> {
                val context=currentCoroutineContext()
                MarkdownDaySpool(scratchDirectory) {context.ensureActive()}.use {spool->
                    val staged=MarkdownHistoryWriter(spool.writer,o,generatedAt,locale)
                    var visitDay:java.time.LocalDate?=null
                    visits {v->
                        val day=staged.day(v.arrivedAt)
                        if(day!=visitDay) {
                            if(visitDay!=null) {staged.endDay();spool.endDay()}
                            visitDay=day;staged.visitDay(v.arrivedAt)
                        }
                        staged.visit(v);visited()
                    }
                    if(visitDay!=null) {staged.endDay();spool.endDay()}
                    var first:Instant?=null;var last:Instant?=null;var count=0L
                    points {p->if(first==null) first=p.time;last=p.time;count++}
                    val markdown=MarkdownHistoryWriter(writer,o,generatedAt,locale)
                    if(o.kind==DataKind.all) markdown.combined(visitCount,count)
                    if(o.kind!=DataKind.points) {markdown.visits(visitCount);spool.replay(writer)}
                    if(o.kind==DataKind.all) markdown.between()
                    if(o.kind!=DataKind.visits) {
                        markdown.points(count,first,last)
                        var pointDay:java.time.LocalDate?=null
                        points {p->
                            val day=markdown.day(p.time)
                            if(day!=pointDay) {if(pointDay!=null) markdown.endDay();pointDay=day;markdown.pointDay(p.time)}
                            markdown.point(p);pointed()
                        }
                        if(pointDay!=null) markdown.endDay()
                    }
                }
            }
            ExportFormat.geojson,ExportFormat.overland,ExportFormat.owntracks -> {
                val json=jsonWriter()
                when(actual) {
                    ExportFormat.geojson -> json.beginObject().name("type").value("FeatureCollection").name("generator").value("Whereday").name("exportDate").value(XmlExportValues.time.format(generatedAt)).name("features").beginArray()
                    ExportFormat.overland -> json.beginObject().name("locations").beginArray()
                    else -> json.beginArray()
                }
                if(actual==ExportFormat.geojson) visits {v->if(o.visitCoordinates) {json.record(JsonLocationFormats.geoVisit(v,o));visited()}}
                var current:Map<String,Any>?=null
                points {p->
                    val record=when(actual) {
                        ExportFormat.geojson->JsonLocationFormats.geoPoint(p,o)
                        ExportFormat.overland->JsonLocationFormats.overland(p,o)
                        else->JsonLocationFormats.ownTracks(p,o)
                    }
                    json.record(record);if(actual==ExportFormat.overland) current=record;pointed()
                }
                json.endArray()
                if(actual==ExportFormat.overland) current?.let {json.name("current");json.record(it)}
                if(actual!=ExportFormat.owntracks) json.endObject();json.flush()
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
