package com.bontecou.whereday.domain

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class ReviewMilestoneTest {
    private val day=LocalDate.of(2026,10,28)
    @Test fun oneDayAndRepeatedLaunchesOrExportsNeverQualify() {
        val state=ReviewMilestone().used(day).used(day).fileExported(day)
        assertEquals(setOf(day),state.days);assertFalse(state.eligible)
    }
    @Test fun twoDistinctDaysNeedACompletedFileExportAndOnlyOneClaim() {
        val used=ReviewMilestone().used(day).used(day.plusDays(1));assertFalse(used.eligible)
        val eligible=used.fileExported(day.plusDays(1));assertTrue(eligible.eligible)
        val claimed=eligible.claim();assertTrue(claimed.requested);assertFalse(claimed.eligible);assertEquals(claimed,claimed.claim())
    }
    @Test fun onlyTwoDatesAreRetainedAndAnEarlierExportCanQualifyOnNextUseDay() {
        var state=ReviewMilestone().fileExported(day)
        repeat(1000) {state=state.used(day.plusDays(it.toLong()))}
        assertEquals(2,state.days.size);assertTrue(state.eligible)
    }
    @Test fun clearErasesUseAndExportMetadataWhilePreservingAnAlreadyConsumedAttempt() {
        val state=ReviewMilestone(setOf(day,day.plusDays(1)),true,true)
        assertEquals(ReviewMilestone(requested=true),state.cleared())
        assertEquals(ReviewMilestone(),state.copy(requested=false).cleared())
    }
    @Test fun distinctCivilDatesMatchMidnightAndDSTRatherThanElapsedTwentyFourHours() {
        val zone=ZoneId.of("America/New_York")
        val before=Instant.parse("2026-03-08T04:59:00Z").atZone(zone).toLocalDate()
        val after=Instant.parse("2026-03-08T05:01:00Z").atZone(zone).toLocalDate()
        assertTrue(ReviewMilestone().fileExported(before).used(after).eligible)
    }
}
