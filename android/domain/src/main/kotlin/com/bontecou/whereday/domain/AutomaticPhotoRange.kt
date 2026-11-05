package com.bontecou.whereday.domain

import java.time.Instant
import java.time.ZoneId

/** iOS automatic range, translated from inclusive map dates to Android's half-open windows. */
object AutomaticPhotoRange {
    fun build(map:HistoryRange?,first:Instant?,last:Instant?,now:Instant,zone:ZoneId):HistoryRange {
        if(map==HistoryRange.all) return HistoryRange.all
        val dates=listOfNotNull(map?.from,map?.until?.minusNanos(1),first,last,now)
        val start=dates.min().atZone(zone).toLocalDate().atStartOfDay(zone).toInstant().coerceAtLeast(HistoryRange.all.from)
        val end=dates.max().atZone(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().coerceAtMost(HistoryRange.all.until)
        return HistoryRange(start,end)
    }
}
