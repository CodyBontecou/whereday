package com.bontecou.whereday.domain

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import java.time.Duration

/** Map presets match Swift Calendar day arithmetic. Windows select millisecond database
 * instants, translating Swift's closed upper bound into an exclusive next millisecond. */
fun mapPresetRange(preset:String,reference:Instant,zone:ZoneId):HistoryRange {
    val day=reference.atZone(zone).toLocalDate()
    val start=day.atStartOfDay(zone).toInstant()
    val until=reference.truncatedTo(ChronoUnit.MILLIS).plusMillis(1)
    fun daysAgo(from:Instant,days:Int):Instant {
        val elapsed=from.minus(Duration.ofDays(days.toLong()))
        // Calendar day addition counts real days across date-line jumps; preserve
        // the clock adjustment from smaller offset changes (DST/half-hour DST).
        val delta=zone.rules.getOffset(from).totalSeconds-zone.rules.getOffset(elapsed).totalSeconds
        val clockDelta=delta%86_400
        val value=elapsed.plusSeconds(clockDelta.toLong())
        val floor=value.truncatedTo(ChronoUnit.MILLIS)
        return if(value==floor) value else floor.plusMillis(1)
    }
    return when(preset) {
        "Yesterday"->HistoryRange(daysAgo(start,1),start)
        // 7D is today plus the six days before it: seven whole calendar days (the map-first home's "7 DAYS").
        "7 days"->HistoryRange(daysAgo(start,6),until)
        "This month"->HistoryRange(day.withDayOfMonth(1).atStartOfDay(zone).toInstant(),until)
        "30 days"->HistoryRange(daysAgo(reference,30),until)
        "All"->HistoryRange(Instant.ofEpochMilli(-62_135_769_600_000L),until)
        else->HistoryRange(start,until)
    }
}
