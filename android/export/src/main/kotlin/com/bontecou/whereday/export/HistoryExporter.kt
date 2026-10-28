package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import com.google.gson.GsonBuilder
import java.io.Writer
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter

object HistoryExporter {
    private val gson = GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create()
    fun render(format: ExportFormat, visits: List<Visit>, points: List<TrackPoint>, options: ExportOptions = ExportOptions(), generatedAt: Instant = Instant.now()): String = buildString {
        val writer = object : Writer() { override fun write(cbuf: CharArray, off: Int, len: Int) { append(cbuf, off, off + len) }; override fun flush() {}; override fun close() {} }
        write(writer, format, options.visits(visits), options.points(points), options, generatedAt)
    }
    /** Callers may supply a streaming Writer; point batches are added by the file orchestrator. */
    fun write(writer: Writer, format: ExportFormat, visits: List<Visit>, points: List<TrackPoint>, o: ExportOptions, generatedAt: Instant) {
        val actual = if (format in listOf(ExportFormat.owntracks, ExportFormat.overland) && o.kind == DataKind.visits) ExportFormat.json else format
        when (actual) {
            ExportFormat.json -> {
                val data = linkedMapOf<String, Any>("exportDate" to generatedAt.toString())
                if (o.kind != DataKind.points) { data["visits"] = visits.map { visitMap(it, o) }; data["totalVisits"] = visits.size }
                if (o.kind != DataKind.visits) { data["points"] = points.map { pointMap(it, o) }; data["totalPoints"] = points.size }
                gson.toJson(data, writer)
            }
            ExportFormat.csv -> {
                if (o.kind == DataKind.all) writer.append("# Whereday Combined Export\n# Generated: $generatedAt\n\n# VISITS (${visits.size})\n")
                if (o.kind != DataKind.points) {
                    val headers = buildList { addAll(listOf("arrived_at", "departed_at")); if(o.visitDuration) add("duration_minutes"); if(o.visitCoordinates) addAll(listOf("latitude", "longitude")); if(o.visitName) add("location_name"); if(o.visitAddress) add("address"); if(o.visitNotes) add("notes"); addAll(listOf("source", "confirmation_status")); if(o.visitName) add("original_location_name"); if(o.visitAddress) add("original_address") }
                    writer.csv(headers)
                    visits.forEach { v -> writer.csv(buildList { add(v.arrivedAt); add(v.departedAt); if(o.visitDuration) add(v.departedAt?.let { Duration.between(v.arrivedAt, it).seconds / 60.0 }); if(o.visitCoordinates) addAll(listOf(v.coordinate.latitude, v.coordinate.longitude)); if(o.visitName) add(v.name); if(o.visitAddress) add(v.address); if(o.visitNotes) add(v.notes); addAll(listOf(v.source.name, v.confirmation.name)); if(o.visitName) add(v.originalName); if(o.visitAddress) add(v.originalAddress) }) }
                }
                if (o.kind == DataKind.all) writer.append("\n# LOCATION POINTS (${points.size})\n")
                if (o.kind != DataKind.visits) {
                    writer.csv(buildList { addAll(listOf("timestamp", "timestamp_unix", "latitude", "longitude")); if(o.pointAltitude) add("altitude"); if(o.pointSpeed) add("speed"); if(o.pointAccuracy) add("horizontal_accuracy"); if(o.pointOutlier) add("is_outlier") })
                    points.forEach { p -> writer.csv(buildList { addAll(listOf(p.time, p.time.toEpochMilli() / 1000.0, p.coordinate.latitude, p.coordinate.longitude)); if(o.pointAltitude) add(p.altitude); if(o.pointSpeed) add(p.speed); if(o.pointAccuracy) add(p.accuracy); if(o.pointOutlier) add(p.outlier) }) }
                }
            }
            ExportFormat.markdown -> {
                writer.append("# Whereday Complete Export\n\n**Export Date:** $generatedAt\n\n")
                if (o.kind != DataKind.points) {
                    writer.append("## Visits\n\n")
                    table(writer, listOf("Arrived", "Departed") + (if(o.visitDuration) listOf("Duration") else emptyList()) + (if(o.visitCoordinates) listOf("Lat", "Lon") else emptyList()) + (if(o.visitName) listOf("Location") else emptyList()) + (if(o.visitAddress) listOf("Address") else emptyList()) + (if(o.visitNotes) listOf("Notes") else emptyList()) + listOf("Source", "Status"), visits.map { v -> buildList { add(v.arrivedAt); add(v.departedAt); if(o.visitDuration) add(v.departedAt?.let { "${Duration.between(v.arrivedAt, it).toMinutes()} min" }); if(o.visitCoordinates) addAll(listOf(v.coordinate.latitude, v.coordinate.longitude)); if(o.visitName) add(v.name); if(o.visitAddress) add(v.address); if(o.visitNotes) add(v.notes); addAll(listOf(v.source.name, v.confirmation.name)) } })
                }
                if (o.kind != DataKind.visits) {
                    writer.append("\n## Location Points\n\n")
                    table(writer, listOf("Time", "Lat", "Lon") + (if(o.pointSpeed) listOf("Speed") else emptyList()) + (if(o.pointAltitude) listOf("Altitude") else emptyList()) + (if(o.pointAccuracy) listOf("Accuracy") else emptyList()) + (if(o.pointOutlier) listOf("Outlier") else emptyList()), points.map { p -> buildList { add(p.time); addAll(listOf(p.coordinate.latitude,p.coordinate.longitude)); if(o.pointSpeed) add(p.speed?.let { "$it m/s" }); if(o.pointAltitude) add(p.altitude?.let { "$it m" }); if(o.pointAccuracy) add("${p.accuracy} m"); if(o.pointOutlier) add(if(p.outlier) "Yes" else "No") } })
                }
            }
            ExportFormat.geojson -> gson.toJson(mapOf("type" to "FeatureCollection", "features" to visits.filter { o.visitCoordinates }.map { v -> feature(v.coordinate, visitMap(v,o), null) } + points.map { p -> feature(p.coordinate, pointMap(p,o), p.altitude.takeIf { o.pointAltitude }) }), writer)
            ExportFormat.owntracks -> gson.toJson(points.map { p -> linkedMapOf<String,Any?>("_type" to "location", "lat" to p.coordinate.latitude, "lon" to p.coordinate.longitude, "tst" to p.time.epochSecond, "tid" to "IM").apply { if(o.pointAccuracy) put("acc",p.accuracy); if(o.pointAltitude) put("alt",p.altitude); if(o.pointSpeed) put("vel",p.speed?.times(3.6)) } }, writer)
            ExportFormat.overland -> gson.toJson(mapOf("locations" to points.map { p -> feature(p.coordinate, mapOf("timestamp" to p.time.toString(), "horizontal_accuracy" to p.accuracy.takeIf { o.pointAccuracy }, "speed" to p.speed.takeIf { o.pointSpeed }), p.altitude.takeIf { o.pointAltitude }) }), writer)
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
    internal fun visitMap(v: Visit, o: ExportOptions): Map<String,Any?> = linkedMapOf<String,Any?>("arrivedAt" to v.arrivedAt.toString(), "departedAt" to v.departedAt?.toString(), "source" to v.source.name, "confirmationStatus" to v.confirmation.name, "confirmedAt" to v.confirmedAt?.toString(), "updatedAt" to v.updatedAt?.toString(), "placeSource" to v.placeSource, "placeDistanceMeters" to v.placeDistanceMeters).apply {
        if(o.visitCoordinates) putAll(mapOf("latitude" to v.coordinate.latitude,"longitude" to v.coordinate.longitude,"originalLatitude" to v.originalCoordinate?.latitude,"originalLongitude" to v.originalCoordinate?.longitude,"detectedLatitude" to v.detectedCoordinate?.latitude,"detectedLongitude" to v.detectedCoordinate?.longitude))
        if(o.visitName) putAll(mapOf("locationName" to v.name,"originalLocationName" to v.originalName,"detectedLocationName" to v.detectedName))
        if(o.visitAddress) putAll(mapOf("address" to v.address,"originalAddress" to v.originalAddress,"detectedAddress" to v.detectedAddress))
        if(o.visitNotes) put("notes", v.notes)
        if(o.visitDuration) put("durationMinutes",v.departedAt?.let { Duration.between(v.arrivedAt,it).seconds/60.0 })
    }
    internal fun pointMap(p: TrackPoint,o: ExportOptions): Map<String,Any?> = linkedMapOf<String,Any?>("latitude" to p.coordinate.latitude,"longitude" to p.coordinate.longitude,"timestamp" to p.time.toString(),"timestampUnix" to p.time.toEpochMilli()/1000.0).apply {
        if(o.pointAltitude) put("altitude",p.altitude); if(o.pointSpeed) put("speed",p.speed); if(o.pointAccuracy) put("horizontalAccuracy",p.accuracy); if(o.pointOutlier) put("isOutlier",p.outlier)
    }
    internal fun feature(c: Coordinate, properties: Map<String,Any?>, altitude: Double?) = mapOf("type" to "Feature","geometry" to mapOf("type" to "Point","coordinates" to if(altitude == null) listOf(c.longitude,c.latitude) else listOf(c.longitude,c.latitude,altitude)),"properties" to properties)
    internal fun Writer.csv(values: List<Any?>) { append(values.joinToString(",") { value -> val text = value?.toString().orEmpty(); if(text.any { it in ",\"\r\n" }) "\"${text.replace("\"","\"\"")}\"" else text }); append('\n') }
    internal fun xml(value: String) = value.replace("&","&amp;").replace("<","&lt;").replace(">","&gt;").replace("\"","&quot;").replace("'","&apos;")
    private fun table(writer: Writer, headers: List<String>, rows: List<List<Any?>>) {
        fun row(values: List<Any?>) { writer.append("| ").append(values.joinToString(" | ") { it?.toString()?.replace("\\","\\\\")?.replace("|","\\|")?.replace("\n","<br>")?.replace("\r","").orEmpty() }).append(" |\n") }
        row(headers); row(headers.map { "---" }); rows.forEach(::row)
    }
}
