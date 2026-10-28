package com.bontecou.whereday.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ExportScheduleTest {
    private val zone=ZoneId.of("America/Los_Angeles")
    private fun date(value:String)=OffsetDateTime.parse(value).toInstant()

    @Test fun dailyAndIntervalOccurrencesMatchActualSwiftGeneratedGoldens() {
        val resource=requireNotNull(javaClass.getResourceAsStream("/ios-export-schedule.tsv"))
        var count=0
        resource.bufferedReader().useLines {lines ->
            lines.filter {it.isNotBlank() && !it.startsWith('#') && !it.startsWith("zone\t")}.forEach {line ->
                val fields=line.split('\t')
                val z=ZoneId.of(fields[0]);val now=Instant.parse(fields[1])
                val schedule=ExportSchedule(fields[2].toInt(),fields[3].toInt(),fields[4].toInt())
                assertEquals("next: $line",Instant.parse(fields[5]),schedule.next(now,z))
                assertEquals("latest: $line",Instant.parse(fields[6]),schedule.latest(now,z))
                count++
            }
        }
        assertEquals(5880,count)
    }
    @Test fun latestCurrentDayOnlySkipsBacklogAndCompletedOccurrences() {
        val schedule=ExportSchedule(9)
        val before=date("2026-05-04T08:00:00-07:00")
        assertEquals(date("2026-05-03T09:00:00-07:00"),schedule.latest(before,zone))
        assertNull(schedule.due(before,zone,null))
        val now=date("2026-05-04T18:00:00-07:00")
        val fire=date("2026-05-04T09:00:00-07:00")
        assertEquals(fire,schedule.due(now,zone,date("2026-04-01T00:00:00Z")))
        assertNull(schedule.due(now,zone,fire))
        assertNull(schedule.due(now,zone,now))
        assertEquals(fire,schedule.due(fire,zone,null))
    }
    @Test fun intervalTailAfterMidnightIsDueWithoutReplayingEarlierSlots() {
        val schedule=ExportSchedule(9,0,3)
        val now=date("2026-05-05T01:00:00-07:00")
        assertEquals(date("2026-05-05T00:00:00-07:00"),schedule.due(now,zone,null))
        assertNull(schedule.due(now,zone,now))
        assertEquals(date("2026-05-05T18:00:00-07:00"),schedule.due(date("2026-05-05T20:59:00-07:00"),zone,now))
    }
    @Test fun timeZoneChangeUsesCurrentLocalDateAndInstantsForCompletion() {
        val schedule=ExportSchedule(21)
        val now=Instant.parse("2026-05-05T05:00:00Z")
        assertEquals(Instant.parse("2026-05-05T04:00:00Z"),schedule.due(now,zone,null))
        assertNull(schedule.due(now,ZoneId.of("America/Puerto_Rico"),null))
        assertNull(schedule.due(now,zone,now.plusSeconds(3600)))
    }
}
