package com.bontecou.whereday

import java.time.*

internal enum class VisitTimestampPart { date,time }
internal enum class VisitTimestampProblem { invalid,gap,unsupported }
internal data class VisitTimestampOption(val instant:Instant,val offset:ZoneOffset)
internal data class VisitTimestampOptions(val values:List<VisitTimestampOption>,val problem:VisitTimestampProblem?=null) {
    fun selected(preferred:String):VisitTimestampOption?=values.singleOrNull() ?: values.firstOrNull {it.offset.id==preferred}
}

internal fun parseVisitClock(hour:String,minute:String,clock24:Boolean,afternoon:Boolean):Pair<Int,Int>? {
    fun number(text:String)=text.takeIf {it.length in 1..2 && it.all(Char::isDigit)}?.toIntOrNull()
    val h=number(hour) ?: return null
    val m=number(minute)?.takeIf {it in 0..59} ?: return null
    if(clock24) return h.takeIf {it in 0..23}?.let {it to m}
    return h.takeIf {it in 1..12}?.let {(it%12+if(afternoon) 12 else 0) to m}
}

/** The original instant and zone belong to the open dialog, independent of later clock/zone changes. */
internal data class VisitTimestampDraft(val original:Instant,val zone:ZoneId,val part:VisitTimestampPart) {
    val local:ZonedDateTime get()=original.atZone(zone)
    fun date(day:LocalDate?)=day?.let {resolve(it.atTime(local.toLocalTime()))}
        ?: VisitTimestampOptions(emptyList(),VisitTimestampProblem.invalid)
    fun time(hour:Int,minute:Int):VisitTimestampOptions = if(hour !in 0..23 || minute !in 0..59)
        VisitTimestampOptions(emptyList(),VisitTimestampProblem.invalid)
        else resolve(local.toLocalDateTime().withHour(hour).withMinute(minute))
    private fun resolve(value:LocalDateTime):VisitTimestampOptions = try {
        val offsets=zone.rules.getValidOffsets(value)
        if(offsets.isEmpty()) VisitTimestampOptions(emptyList(),VisitTimestampProblem.gap)
        else {
            val choices=offsets.mapNotNull {offset->
                val instant=value.toInstant(offset)
                try {instant.toEpochMilli();VisitTimestampOption(instant,offset)} catch(_:ArithmeticException) {null}
            }.sortedBy {it.instant}
            VisitTimestampOptions(choices,VisitTimestampProblem.unsupported.takeIf {choices.isEmpty()})
        }
    } catch(_:DateTimeException) {VisitTimestampOptions(emptyList(),VisitTimestampProblem.unsupported)}
}
