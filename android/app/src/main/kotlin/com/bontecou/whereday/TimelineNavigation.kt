package com.bontecou.whereday

import com.bontecou.whereday.domain.*
import java.time.*

internal fun initialTimelineDay(selected:LocalDate,today:LocalDate,hasEvents:Boolean,latest:LocalDate?) =
    if(selected==today && !hasEvents && latest!=null) latest else selected

internal fun emptyTimelineTarget(selected:LocalDate,today:LocalDate,latest:LocalDate?) =
    latest?.takeIf {it!=selected} ?: today.takeIf {it!=selected}

internal fun timelineDayRange(day:LocalDate,zone:ZoneId)=HistoryRange.day(day,zone).also {it.from.toEpochMilli();it.until.toEpochMilli()}
internal fun adjacentTimelineDay(day:LocalDate,step:Int,zone:ZoneId):LocalDate? {
    require(step==1 || step == -1)
    var candidate=day
    while(true) {
        candidate=runCatching {candidate.plusDays(step.toLong())}.getOrNull() ?: return null
        val start=runCatching {candidate.atStartOfDay(zone)}.getOrNull() ?: return null
        if(start.toLocalDate()!=candidate) continue
        return runCatching {timelineDayRange(candidate,zone);candidate}.getOrNull()
    }
}

internal data class TimelineEntry(val id:String,val time:Instant,val visit:Visit?=null,val session:RecordingSession?=null,val inferred:InferredOutingHeader?=null)
internal fun timelineEntries(window:HistoryWindow):List<TimelineEntry> {
    val sequences=window.timeline?.outings?.associate {it.id to it.sequence}.orEmpty()
    return (window.visits.map {TimelineEntry(it.id,it.arrivedAt,visit=it)}+
        window.sessions.map {TimelineEntry(it.id,it.startedAt,session=it)}+
        window.inferred.map {TimelineEntry(it.id,it.startedAt,inferred=it)})
        .sortedWith(compareBy<TimelineEntry> {it.time}.thenBy {if(it.visit!=null) 0 else 1}.thenBy {if(it.visit!=null) 0 else sequences[it.id] ?: 0})
}
