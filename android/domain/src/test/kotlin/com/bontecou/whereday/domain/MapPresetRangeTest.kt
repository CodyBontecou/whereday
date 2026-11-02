package com.bontecou.whereday.domain

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class MapPresetRangeTest {
    @Test fun actualSwiftMapPresetsMatchAcrossNineTimeZones() {
        val root=javaClass.getResourceAsStream("/ios-map-dates.json")!!.reader().use {JsonParser.parseReader(it).asJsonObject}
        val cases=root.getAsJsonArray("cases");assertEquals(540,cases.size())
        val keys=mapOf("today" to "Today","yesterday" to "Yesterday","sevenDays" to "7 days","thirtyDays" to "30 days","all" to "All")
        val mismatches=mutableListOf<String>()
        for(value in cases) {
            val case=value.asJsonObject
            val range=mapPresetRange(keys.getValue(case.get("preset").asString),Instant.parse(case.get("reference").asString),ZoneId.of(case.get("zone").asString))
            if(case.get("fromMillis").asLong!=range.from.toEpochMilli() || case.get("untilMillis").asLong!=range.until.toEpochMilli())
                mismatches+="$case actual=$range"
        }
        assertTrue("${mismatches.size} mismatches: ${mismatches.take(10).joinToString("\n")}",mismatches.isEmpty())
    }
    @Test fun currentMillisecondIsIncludedButFutureHistoryIsExcluded() {
        for(reference in listOf(Instant.parse("1965-07-12T00:00:00Z"),Instant.parse("2026-10-31T18:12:34.125456Z"))) {
            for(preset in listOf("Today","7 days","30 days","All")) {
                val range=mapPresetRange(preset,reference,ZoneOffset.UTC)
                assertTrue(range.contains(Instant.ofEpochMilli(reference.toEpochMilli())))
                assertFalse(range.contains(Instant.ofEpochMilli(reference.toEpochMilli()+1)))
            }
        }
    }
}
