package com.bontecou.whereday

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class MapSelectionTest {
    private val now=Instant.parse("2026-10-31T18:12:34.125Z")
    private val zone=ZoneOffset.UTC
    @Test fun sevenDaysIsTodayAndTheSixWholeDaysBeforeIt() {
        val range=MapSelection("7 days").range(now,zone)
        assertEquals(Instant.parse("2026-10-25T00:00:00Z"),range.from)
        assertTrue(range.contains(Instant.parse("2026-10-25T00:00:00Z")));assertFalse(range.contains(Instant.parse("2026-10-24T23:59:59.999Z")))
        assertTrue(range.contains(now));assertFalse(range.contains(now.plusMillis(1)))
    }
    @Test fun thirtyDaysIsTodayAndTheTwentyNineWholeDaysBeforeIt() {
        val range=MapSelection("30 days").range(now,zone)
        assertEquals(Instant.parse("2026-10-02T00:00:00Z"),range.from)
        assertTrue(range.contains(Instant.parse("2026-10-02T00:00:00Z")));assertFalse(range.contains(Instant.parse("2026-10-01T23:59:59.999Z")))
        assertTrue(range.contains(now));assertFalse(range.contains(now.plusMillis(1)))
    }
    @Test fun todayAndAllExcludeFutureHistory() {
        for(preset in listOf("Today","All")) {
            val range=MapSelection(preset).range(now,zone)
            assertTrue(range.contains(now));assertFalse(range.contains(now.plusSeconds(1)))
        }
    }
}
