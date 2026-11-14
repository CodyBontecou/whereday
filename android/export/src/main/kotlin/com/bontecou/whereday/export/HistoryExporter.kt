package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import com.google.gson.GsonBuilder
import java.io.Writer
import java.time.Instant
import java.util.Locale

object HistoryExporter {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    fun render(format: ExportFormat, visits: List<Visit>, points: List<TrackPoint>, options: ExportOptions = ExportOptions(), generatedAt: Instant = Instant.now(), locale:Locale=Locale.getDefault()): String = buildString {
        val writer = object : Writer() { override fun write(cbuf: CharArray, off: Int, len: Int) { append(cbuf, off, off + len) }; override fun flush() {}; override fun close() {} }
        write(writer, format, options.visits(visits), options.points(points), options, generatedAt,locale)
    }
    /** Callers may supply a streaming Writer; point batches are added by the file orchestrator. */
    fun write(writer: Writer, format: ExportFormat, visits: List<Visit>, points: List<TrackPoint>, o: ExportOptions, generatedAt: Instant,locale:Locale=Locale.getDefault()) {
        val actual = if (format in listOf(ExportFormat.owntracks, ExportFormat.overland) && o.kind == DataKind.visits) ExportFormat.json else format
        when (actual) {
            ExportFormat.json -> {
                val ordered=points.sortedBy {it.time}
                val data=linkedMapOf<String,Any>("exportDate" to XmlExportValues.time.format(generatedAt))
                if(o.kind!=DataKind.points) {data["visits"]=visits.map {visitMap(it,o)};if(o.kind==DataKind.all) data["totalVisits"]=visits.size}
                if(o.kind!=DataKind.visits) {
                    data["points"]=ordered.map {pointMap(it,o)};data["totalPoints"]=ordered.size
                    if(ordered.isNotEmpty()) data["dateRange"]=HistoryTableRows.dateRange(ordered.first().time,ordered.last().time)
                }
                gson.toJson(data,writer)
            }
            ExportFormat.csv -> {
                if(o.kind==DataKind.all) writer.append("# Whereday Combined Export\n# Generated: ${XmlExportValues.time.format(generatedAt)}\n\n# VISITS (${visits.size})\n")
                if(o.kind!=DataKind.points) {writer.csv(HistoryTableRows.visitHeader(o));visits.forEach {writer.csv(HistoryTableRows.visit(it,o))}}
                if(o.kind==DataKind.all) writer.append("\n# LOCATION POINTS (${points.size})\n")
                if(o.kind!=DataKind.visits) {writer.csv(HistoryTableRows.pointHeader(o));points.forEach {writer.csv(HistoryTableRows.point(it,o))}}
            }
            ExportFormat.markdown -> MarkdownHistoryWriter.write(writer,visits,points,o,generatedAt,locale)
            ExportFormat.geojson -> gson.toJson(mapOf("type" to "FeatureCollection","generator" to "Whereday","exportDate" to XmlExportValues.time.format(generatedAt),
                "features" to visits.filter {o.visitCoordinates}.map {JsonLocationFormats.geoVisit(it,o)}+points.map {JsonLocationFormats.geoPoint(it,o)}),writer)
            ExportFormat.owntracks -> gson.toJson(points.map {JsonLocationFormats.ownTracks(it,o)},writer)
            ExportFormat.overland -> {
                val features=points.map {JsonLocationFormats.overland(it,o)}
                gson.toJson(linkedMapOf<String,Any>("locations" to features).apply {features.lastOrNull()?.let {put("current",it)}},writer)
            }
            ExportFormat.gpx -> {
                val gpx=GpxWriter(writer,o,generatedAt)
                visits.forEach {gpx.visit(it)}
                points.forEach {gpx.point(it)}
                gpx.finish()
            }
            ExportFormat.kml -> {
                val kml=KmlWriter(writer,o,generatedAt)
                visits.sortedBy {it.arrivedAt}.forEach {kml.visit(it)}
                val ordered=points.sortedBy {it.time}
                var start=0
                while(start<ordered.size) {
                    var end=start+1
                    while(end<ordered.size && XmlExportValues.swiftDateSeconds(ordered[end].time)-XmlExportValues.swiftDateSeconds(ordered[end-1].time)<=600.0) end++
                    val session=ordered.subList(start,end)
                    kml.session(session.size.toLong(),session.first(),session.last()) {consume->session.forEach(consume)}
                    start=end
                }
                kml.finish()
            }
        }
    }
    internal fun visitMap(v:Visit,o:ExportOptions):Map<String,Any> = linkedMapOf<String,Any>("arrivedAt" to XmlExportValues.time.format(v.arrivedAt)).apply {
        v.departedAt?.let {put("departedAt",XmlExportValues.time.format(it));if(o.visitDuration) put("durationMinutes",(XmlExportValues.swiftDateSeconds(it)-XmlExportValues.swiftDateSeconds(v.arrivedAt))/60.0)}
        if(o.visitCoordinates) {put("latitude",v.coordinate.latitude);put("longitude",v.coordinate.longitude)}
        if(o.visitName) v.name?.let {put("locationName",it)};if(o.visitAddress) v.address?.let {put("address",it)};if(o.visitNotes) v.notes?.let {put("notes",it)}
        putAll(VisitAudit.values(v,o))
    }
    internal fun pointMap(p:TrackPoint,o:ExportOptions):Map<String,Any> = linkedMapOf<String,Any>("latitude" to p.coordinate.latitude,"longitude" to p.coordinate.longitude,"timestamp" to XmlExportValues.time.format(p.time),"timestampUnix" to XmlExportValues.unixSeconds(p.time)).apply {
        if(o.pointAltitude) p.altitude?.let {put("altitude",it)};if(o.pointSpeed) p.speed?.let {put("speed",it)};if(o.pointAccuracy) put("horizontalAccuracy",p.accuracy);if(o.pointOutlier) put("isOutlier",p.outlier)
    }
    internal fun feature(c: Coordinate, properties: Map<String,Any?>, altitude: Double?) = mapOf("type" to "Feature","geometry" to mapOf("type" to "Point","coordinates" to if(altitude == null) listOf(c.longitude,c.latitude) else listOf(c.longitude,c.latitude,altitude)),"properties" to properties)
    internal fun Writer.csv(values: List<Any?>) { append(values.joinToString(",") { value -> val text = value?.toString().orEmpty(); if(text.any { it in ",\"\r\n" }) "\"${text.replace("\"","\"\"")}\"" else text }); append('\n') }
    internal fun xml(value: String) = value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")
}
