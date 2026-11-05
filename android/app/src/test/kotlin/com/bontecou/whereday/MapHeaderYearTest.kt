package com.bontecou.whereday

import org.junit.Assert.*
import org.junit.Test
import java.time.LocalDate

class MapHeaderYearTest {
    private val today=LocalDate.of(2026,11,5)

    @Test fun rangesWithinThisYearOmitTheYear() {
        assertFalse(showsYear(LocalDate.of(2026,11,1),today,today))
        assertFalse(showsYear(today,today,today))
    }
    @Test fun rangesCrossingYearsNameIt() {
        // All time from Oct 2 last year read "Oct 2 – Nov 5 · 400 days" without it.
        assertTrue(showsYear(LocalDate.of(2025,10,2),today,today))
    }
    @Test fun rangesInAnEarlierYearNameIt() {
        assertTrue(showsYear(LocalDate.of(2025,3,1),LocalDate.of(2025,3,9),today))
    }
}
