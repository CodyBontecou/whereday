package com.bontecou.whereday

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class MapSelectionTest {
    private val now=Instant.parse("2026-10-31T18:12:34.125Z")
    private val zone=ZoneOffset.UTC
    @Test fun sevenDaysRetainsTheFirstPartialDayFromTheSwiftWindow() {
        val range=MapSelection("7 days").range(now,zone)
        assertEquals(Instant.parse("2026-10-24T18:12:34.125Z"),range.from)
        assertTrue(range.contains(Instant.parse("2026-10-24T20:00:00Z")))
    }
    @Test fun thirtyDaysRetainsTheFirstPartialDayFromTheSwiftWindow() {
        val range=MapSelection("30 days").range(now,zone)
        assertEquals(Instant.parse("2026-10-01T18:12:34.125Z"),range.from)
    }
    @Test fun todayAndAllExcludeFutureHistory() {
        for(preset in listOf("Today","All")) {
            val range=MapSelection(preset).range(now,zone)
            assertTrue(range.contains(now));assertFalse(range.contains(now.plusSeconds(1)))
        }
    }
}
