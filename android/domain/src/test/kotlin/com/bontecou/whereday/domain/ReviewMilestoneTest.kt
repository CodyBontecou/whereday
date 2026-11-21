package com.bontecou.whereday.domain

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class ReviewMilestoneTest {
    private val day=LocalDate.of(2026,10,28)
    private val now=Instant.parse("2026-10-29T12:00:00Z")
    @Test fun oneDayAndRepeatedLaunchesNeverQualify() {
        val state=ReviewMilestone().used(day).used(day).used(day)
        assertEquals(1,state.useDays);assertFalse(state.eligible("1.0",now))
    }
    @Test fun twoDistinctDaysQualifyOncePerVersion() {
        val used=ReviewMilestone().used(day).used(day.plusDays(1));assertTrue(used.eligible("1.0",now))
        val claimed=used.claim("1.0",now)
        assertEquals("1.0",claimed.requestedVersion);assertEquals(now,claimed.requestedAt)
        assertFalse(claimed.eligible("1.0",now.plus(Duration.ofDays(400))))
        assertEquals(claimed,claimed.claim("1.0",now.plus(Duration.ofDays(400))))
    }
    @Test fun aNewVersionWaitsOneHundredTwentyDays() {
        val claimed=ReviewMilestone().used(day).used(day.plusDays(1)).claim("1.0",now)
        assertFalse(claimed.eligible("1.1",now.plus(ReviewMilestone.cooldown).minusSeconds(1)))
        assertTrue(claimed.eligible("1.1",now.plus(ReviewMilestone.cooldown)))
    }
    @Test fun storedDaysAreACappedCountAndTheLastDay() {
        var state=ReviewMilestone()
        repeat(1000) {state=state.used(day.plusDays(it.toLong()))}
        assertEquals(ReviewMilestone.maxUseDays,state.useDays);assertEquals(day.plusDays(999),state.lastUseDay)
        assertEquals(state,state.used(day),"an earlier day (clock moved back) is ignored")
    }
    @Test fun clearErasesUseButKeepsTheLastRequest() {
        val state=ReviewMilestone().used(day).used(day.plusDays(1)).claim("1.0",now)
        assertEquals(ReviewMilestone(requestedVersion="1.0",requestedAt=now),state.cleared())
        assertEquals(ReviewMilestone(),ReviewMilestone().used(day).cleared())
    }
    @Test fun versionOneMigrationStartsTheCooldownOnlyForSomeoneAlreadyAsked() {
        val asked=ReviewMilestone.migrated(setOf(day,day.plusDays(1)),true,now)
        assertEquals(ReviewMilestone(2,day.plusDays(1),null,now),asked)
        assertFalse(asked.eligible("2.0",now.plus(Duration.ofDays(119))));assertTrue(asked.eligible("2.0",now.plus(Duration.ofDays(120))))
        val never=ReviewMilestone.migrated(setOf(day,day.plusDays(1)),false,now)
        assertNull(never.requestedAt);assertTrue(never.eligible("2.0",now))
    }
    @Test fun onlyChosenSuccessesQualify() {
        assertEquals(setOf(ReviewSuccess.backup,ReviewSuccess.scheduledExport),ReviewSuccess.entries.filterNot {it.qualifies}.toSet())
    }
    @Test fun distinctCivilDatesMatchMidnightAndDSTRatherThanElapsedTwentyFourHours() {
        val zone=ZoneId.of("America/New_York")
        val before=Instant.parse("2026-03-08T04:59:00Z").atZone(zone).toLocalDate()
        val after=Instant.parse("2026-03-08T05:01:00Z").atZone(zone).toLocalDate()
        assertTrue(ReviewMilestone().used(before).used(after).eligible("1.0",now))
    }
    private fun assertEquals(expected:Any?,actual:Any?,message:String)=org.junit.Assert.assertEquals(message,expected,actual)
}
