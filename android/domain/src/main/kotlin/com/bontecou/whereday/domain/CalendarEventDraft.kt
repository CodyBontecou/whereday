package com.bontecou.whereday.domain

import java.time.Instant
import java.util.Locale

/** A snapshot for the user's external calendar editor. No journal or calendar writes. */
data class CalendarEventDraft(val title:String,val startedAt:Instant,val endedAt:Instant,val location:String?) {
    init { require(endedAt>=startedAt) }
    companion object {
        fun visit(visit:Visit,unknownTitle:String,now:Instant):CalendarEventDraft = CalendarEventDraft(
            visit.name?.trim()?.takeIf {it.isNotEmpty()} ?: visit.address ?: unknownTitle,
            visit.arrivedAt,(visit.departedAt ?: now).coerceAtLeast(visit.arrivedAt),
            listOf(visit.address,visit.name).firstNotNullOfOrNull {it?.trim()?.takeIf(String::isNotEmpty)} ?: coordinate(visit.coordinate))

        fun outing(session:RecordingSession,firstPoint:Coordinate?,defaultTitle:String,now:Instant):CalendarEventDraft = CalendarEventDraft(
            session.name?.trim()?.takeIf {it.isNotEmpty()} ?: defaultTitle,
            session.startedAt,(session.endedAt ?: now).coerceAtLeast(session.startedAt),firstPoint?.let(::coordinate))

        private fun coordinate(value:Coordinate)=String.format(Locale.US,"%.6f, %.6f",value.latitude,value.longitude)
    }
}
