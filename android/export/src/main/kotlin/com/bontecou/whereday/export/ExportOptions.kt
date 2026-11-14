package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import java.time.*
import java.time.format.DateTimeFormatter
import java.util.Locale

enum class ExportFormat(val extension: String, val mime: String) {
    json("json", "application/json"), csv("csv", "text/csv"), markdown("md", "text/markdown"),
    geojson("geojson", "application/geo+json"), gpx("gpx", "application/gpx+xml"), kml("kml", "application/vnd.google-earth.kml+xml"),
    owntracks("json", "application/json"), overland("json", "application/json")
}
enum class DataKind { visits, points, all }
data class ExportOptions(
    val kind: DataKind = DataKind.all, val from: Instant = allDatesStart, val until: Instant = Instant.MAX,
    val zone: ZoneId = ZoneId.systemDefault(), val startTime: LocalTime? = null, val endTime: LocalTime? = null,
    val completedOnly: Boolean = false, val minimumVisitMinutes: Long = 0,
    val excludeOutliers: Boolean = false, val maximumAccuracy: Double? = null,
    val visitCoordinates: Boolean = true, val visitName: Boolean = true, val visitAddress: Boolean = true,
    val visitNotes: Boolean = true, val visitDuration: Boolean = true,
    val pointAltitude: Boolean = true, val pointSpeed: Boolean = true, val pointAccuracy: Boolean = true, val pointOutlier: Boolean = true,
) {
    companion object {val allDatesStart:Instant=Instant.ofEpochMilli(Long.MIN_VALUE)}
    init { require(from <= until); require((startTime == null) == (endTime == null)); require(minimumVisitMinutes >= 0) }
    fun includes(instant: Instant): Boolean {
        if (instant < from || instant > until) return false
        val start = startTime ?: return true
        val end = endTime!!
        val time = instant.atZone(zone).toLocalTime()
        return if (start <= end) time in start..end else time >= start || time <= end
    }
    fun accepts(visit: Visit)=kind!=DataKind.points && includes(visit.arrivedAt) && (!completedOnly || visit.departedAt!=null) &&
        (minimumVisitMinutes==0L || visit.departedAt?.let { Duration.between(visit.arrivedAt,it).toMinutes()>=minimumVisitMinutes }==true)
    fun accepts(point: TrackPoint)=kind!=DataKind.visits && includes(point.time) && (!excludeOutliers || !point.outlier) && (maximumAccuracy==null || point.accuracy<=maximumAccuracy)
    fun visits(values: List<Visit>)=values.filter(::accepts).sortedBy { it.arrivedAt }
    fun points(values: List<TrackPoint>)=values.filter(::accepts).sortedBy { it.time }
}

object FilenamePlanner {
    const val readablePattern="Whereday - {day} {date} - {type}"
    const val compactPattern="isome_{type}_{datetime}"
    const val datedFoldersPattern="{year}/{year}-{month}/Daily Track - {date}"
    const val defaultPattern=readablePattern
    fun path(pattern: String, format: ExportFormat, date: LocalDate, type: String = "all", title: String = "", now: Instant? = null,zone:ZoneId=ZoneId.systemDefault()): String {
        val resolvedPattern=pattern.trim().ifEmpty {defaultPattern}
        require(!resolvedPattern.startsWith('/') && !resolvedPattern.startsWith('\\')) { "Choose a relative filename" }
        val resolvedTitle=title.trim().ifEmpty {type}
        val resolvedInstant=now ?: date.atStartOfDay(zone).toInstant()
        val tokens = mapOf("date" to date.toString(), "year" to date.year.toString(), "month" to String.format(Locale.US,"%02d",date.monthValue),
            "dayNumber" to String.format(Locale.US,"%02d",date.dayOfMonth), "weekday" to date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.US),
            "monthName" to date.month.getDisplayName(java.time.format.TextStyle.FULL, Locale.US), "quarter" to "Q${(date.monthValue - 1) / 3 + 1}",
            "datetime" to DateTimeFormatter.ofPattern("yyyy-MM-dd_HH-mm-ss",Locale.US).withZone(zone).format(resolvedInstant),
            "time" to DateTimeFormatter.ofPattern("HH-mm-ss",Locale.US).withZone(zone).format(resolvedInstant), "day" to date.dayOfWeek.getDisplayName(java.time.format.TextStyle.FULL, Locale.US),
            "type" to type, "title" to resolvedTitle, "name" to resolvedTitle, "format" to if(format==ExportFormat.markdown) "md" else format.name)
        // Sanitize token values before expansion: a title cannot introduce directory traversal.
        val expanded = Regex("\\{([^}]+)\\}").replace(resolvedPattern) { match -> sanitize(tokens[match.groupValues[1]] ?: error("Unknown filename token ${match.value}")) }
        val completed=if(expanded.endsWith('/')) expanded+sanitize(type)+".${format.extension}" else expanded
        val components = completed.split('/').toMutableList()
        require(components.all { it.isNotBlank() && it != "." && it != ".." && '\\' !in it }) { "Unsafe export path" }
        if(components.last().equals(".${format.extension}",true)) components[components.lastIndex]=sanitize(type)+".${format.extension}"
        val result = components.joinToString("/") { sanitize(it) }
        return if (result.endsWith(".${format.extension}", true)) result else "$result.${format.extension}"
    }
    fun dayStable(pattern: String): String {
        val source=if(pattern.isBlank()) defaultPattern else pattern
        val collapsed = source.replace("{datetime}", "{date}").replace("{time}", "{date}")
        if(collapsed.contains("{date}")) return collapsed
        val directory=collapsed.substringBeforeLast('/',"").let {if(it.isEmpty()) "" else "$it/"}
        val basename=collapsed.substringAfterLast('/');val dot=basename.lastIndexOf('.')
        return directory+when {
            basename.isEmpty()->"{date} - {type}"
            dot>0->basename.substring(0,dot)+" - {date}"+basename.substring(dot)
            else->"$basename - {date}"
        }
    }
    private fun sanitize(value: String): String {
        val safe=value.replace(Regex("[\\x00-\\x1f\\x7f<>:\"/\\\\|?*]"),"-").trimStart('.').trim().trimEnd('.')
        val result=StringBuilder();var bytes=0
        val codepoints=safe.codePoints().iterator()
        while(codepoints.hasNext()) {
            val part=String(Character.toChars(codepoints.nextInt()));val length=part.toByteArray(Charsets.UTF_8).size
            if(bytes+length>180) break
            result.append(part);bytes+=length
        }
        return result.toString().ifBlank {"Whereday-export"}
    }
}
