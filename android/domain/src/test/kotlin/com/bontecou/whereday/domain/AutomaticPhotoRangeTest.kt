package com.bontecou.whereday.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class AutomaticPhotoRangeTest {
    @Test fun historyMapAndNowUseCompleteCivilDaysAcrossDst() {
        val zone=ZoneId.of("America/New_York")
        val day=LocalDate.of(2026,3,8)
        val now=day.atTime(12,0).atZone(zone).toInstant()
        val result=AutomaticPhotoRange.build(HistoryRange.day(day,zone),null,null,now,zone)
        assertEquals(HistoryRange.day(day,zone),result);assertEquals(23,Duration.between(result.from,result.until).toHours())
        val extended=AutomaticPhotoRange.build(result,Instant.parse("2026-03-01T12:00:00Z"),Instant.parse("2026-03-15T12:00:00Z"),now,zone)
        assertEquals(HistoryRange.days(LocalDate.of(2026,3,1),LocalDate.of(2026,3,15),zone),extended)
    }
    @Test fun noHistoryUsesTodayAndAllRetainsRepresentableBounds() {
        val now=Instant.parse("2026-10-27T14:00:00Z");val zone=ZoneId.of("America/Puerto_Rico")
        assertEquals(HistoryRange.day(LocalDate.of(2026,10,27),zone),AutomaticPhotoRange.build(null,null,null,now,zone))
        assertEquals(HistoryRange.all,AutomaticPhotoRange.build(HistoryRange.all,null,null,now,zone))
    }
}
