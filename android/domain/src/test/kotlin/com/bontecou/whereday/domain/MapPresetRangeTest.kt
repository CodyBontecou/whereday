package com.bontecou.whereday.domain

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class MapPresetRangeTest {
    @Test fun actualSwiftMapPresetsMatchAcrossNineTimeZones() {
        val root=javaClass.getResourceAsStream("/ios-map-dates.json")!!.reader().use {JsonParser.parseReader(it).asJsonObject}
        val cases=root.getAsJsonArray("cases");assertEquals(648,cases.size())
        val keys=mapOf("today" to "Today","yesterday" to "Yesterday","sevenDays" to "7 days","thisMonth" to "This month","thirtyDays" to "30 days","all" to "All")
        val mismatches=mutableListOf<String>()
        for(value in cases) {
            val case=value.asJsonObject
            // The fixture's sevenDays cases record 1.8's rolling 168 hours; 7D is now seven calendar days (tested below).
            if(case.get("preset").asString=="sevenDays") continue
            val range=mapPresetRange(keys.getValue(case.get("preset").asString),Instant.parse(case.get("reference").asString),ZoneId.of(case.get("zone").asString))
            if(case.get("fromMillis").asLong!=range.from.toEpochMilli() || case.get("untilMillis").asLong!=range.until.toEpochMilli())
                mismatches+="$case actual=$range"
        }
        assertTrue("${mismatches.size} mismatches: ${mismatches.take(10).joinToString("\n")}",mismatches.isEmpty())
    }
    @Test fun sevenDaysIsTodayAndTheSixCalendarDaysBeforeIt() {
        for(zone in listOf(ZoneOffset.UTC,ZoneId.of("America/New_York"),ZoneId.of("Pacific/Apia"),ZoneId.of("Australia/Lord_Howe"))) {
            for(reference in listOf(Instant.parse("2026-11-05T15:30:00Z"),Instant.parse("2026-03-10T04:00:00Z"),Instant.parse("2026-11-02T06:30:00Z"))) {
                val range=mapPresetRange("7 days",reference,zone)
                val today=reference.atZone(zone).toLocalDate()
                assertEquals("$zone $reference",today.minusDays(6).atStartOfDay(zone).toInstant(),range.from)
                assertEquals(reference.plusMillis(1),range.until)
                // The day before the first is excluded; every one of the seven days is included.
                assertFalse(range.contains(range.from.minusMillis(1)))
                assertEquals(7,generateSequence(today.minusDays(6)) {it.plusDays(1)}.takeWhile {it<=today}.count {range.contains(maxOf(it.atStartOfDay(zone).toInstant(),range.from))})
            }
        }
        // Across the spring-forward night the week still starts at local midnight six days back.
        val spring=mapPresetRange("7 days",Instant.parse("2026-03-10T15:00:00Z"),ZoneId.of("America/New_York"))
        assertEquals(Instant.parse("2026-03-04T05:00:00Z"),spring.from)
    }
    @Test fun currentMillisecondIsIncludedButFutureHistoryIsExcluded() {
        for(reference in listOf(Instant.parse("1965-07-12T00:00:00Z"),Instant.parse("2026-10-31T18:12:34.125456Z"))) {
            for(preset in listOf("Today","7 days","This month","30 days","All")) {
                val range=mapPresetRange(preset,reference,ZoneOffset.UTC)
                assertTrue(range.contains(Instant.ofEpochMilli(reference.toEpochMilli())))
                assertFalse(range.contains(Instant.ofEpochMilli(reference.toEpochMilli()+1)))
            }
        }
    }
}
