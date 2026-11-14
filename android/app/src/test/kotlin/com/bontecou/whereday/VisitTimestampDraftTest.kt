package com.bontecou.whereday

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class VisitTimestampDraftTest {
    private val ny=ZoneId.of("America/New_York")
    private val later=Instant.parse("2026-11-01T06:30:45.125Z")
    @Test fun unchangedDateAndTimeRetainTheOriginalOccurrenceAndFullPrecision() {
        for(value in listOf(later,later.minusSeconds(3600),Instant.parse("1850-12-31T18:12:34.125Z"))) {
            val draft=VisitTimestampDraft(value,ny,VisitTimestampPart.time);val local=draft.local
            assertEquals(value,draft.time(local.hour,local.minute).selected(local.offset.id)!!.instant)
            assertEquals(value,draft.date(local.toLocalDate()).selected(local.offset.id)!!.instant)
        }
    }
    @Test fun overlapOffersBothActualInstantsInChronologicalOrder() {
        val choices=VisitTimestampDraft(later,ny,VisitTimestampPart.time).time(1,30)
        assertNull(choices.problem)
        assertEquals(listOf(later.minusSeconds(3600),later),choices.values.map {it.instant})
        assertEquals(listOf("-04:00","-05:00"),choices.values.map {it.offset.id})
        assertNull(choices.selected("+02:00"))
    }
    @Test fun springGapsCannotSilentlyShiftForward() {
        val draft=VisitTimestampDraft(Instant.parse("2026-03-08T07:30:45.125Z"),ny,VisitTimestampPart.time)
        val gap=draft.time(2,30)
        assertTrue(gap.values.isEmpty());assertEquals(VisitTimestampProblem.gap,gap.problem)
        assertEquals(Instant.parse("2026-03-08T07:15:45.125Z"),draft.time(3,15).selected("-05:00")!!.instant)
    }
    @Test fun dateChangePreservesClockPrecisionAndAdoptsTheValidNewOffset() {
        val draft=VisitTimestampDraft(Instant.parse("2026-03-07T07:30:45.125Z"),ny,VisitTimestampPart.date)
        assertEquals(VisitTimestampProblem.gap,draft.date(LocalDate.of(2026,3,8)).problem)
        assertEquals(Instant.parse("2026-03-09T06:30:45.125Z"),draft.date(LocalDate.of(2026,3,9)).selected("-05:00")!!.instant)
    }
    @Test fun wholeSkippedDatesAndNonHourTransitionsUseTheirRealOffsets() {
        val apia=VisitTimestampDraft(Instant.parse("2011-12-29T12:00:00Z"),ZoneId.of("Pacific/Apia"),VisitTimestampPart.date)
        assertEquals(VisitTimestampProblem.gap,apia.date(LocalDate.of(2011,12,30)).problem)
        val lordHowe=VisitTimestampDraft(Instant.parse("2026-04-04T15:15:21.123Z"),ZoneId.of("Australia/Lord_Howe"),VisitTimestampPart.time)
        val choices=lordHowe.time(1,45)
        assertEquals(2,choices.values.size)
        assertEquals(Duration.ofMinutes(30),Duration.between(choices.values[0].instant,choices.values[1].instant))
        assertEquals(lordHowe.original,choices.selected(lordHowe.local.offset.id)!!.instant)
    }
    @Test fun invalidDatesAndOutOfStorageRangeAreRejectedWithoutThrowing() {
        val draft=VisitTimestampDraft(later,ny,VisitTimestampPart.date)
        assertEquals(VisitTimestampProblem.invalid,draft.date(null).problem)
        assertEquals(VisitTimestampProblem.invalid,draft.time(24,0).problem)
        assertEquals(VisitTimestampProblem.invalid,draft.time(0,-1).problem)
        assertEquals(VisitTimestampProblem.unsupported,draft.date(LocalDate.MAX).problem)
        assertEquals(VisitTimestampProblem.unsupported,draft.date(LocalDate.MIN).problem)
    }
    @Test fun yearZeroAndExpandedYearsRemainExactWithinStorageBounds() {
        val draft=VisitTimestampDraft(Instant.parse("2026-10-31T18:12:34.125Z"),ZoneOffset.UTC,VisitTimestampPart.date)
        for(day in listOf(LocalDate.of(0,12,30),LocalDate.of(-1,1,1),LocalDate.of(10000,1,1))) {
            val selected=draft.date(day).values.single().instant
            assertEquals(day.atTime(18,12,34,125_000_000).toInstant(ZoneOffset.UTC),selected)
        }
    }
    @Test fun editingMinutesRetainsSecondsAndFractionalPrecision() {
        val original=Instant.parse("2026-10-31T18:12:34.123456789Z")
        val draft=VisitTimestampDraft(original,ZoneOffset.UTC,VisitTimestampPart.time)
        assertEquals(Instant.parse("2026-10-31T19:15:34.123456789Z"),draft.time(19,15).values.single().instant)
    }
    @Test fun clockInputRequiresCompleteValidFieldsAndConvertsNoonAndMidnight() {
        assertEquals(0 to 30,parseVisitClock("12","30",false,false))
        assertEquals(12 to 30,parseVisitClock("12","30",false,true))
        assertEquals(23 to 59,parseVisitClock("11","59",false,true))
        assertEquals(0 to 0,parseVisitClock("00","00",true,false))
        for((h,m) in listOf("" to "30","1" to "","24" to "00","23" to "60","-1" to "00","001" to "0","x" to "10"))
            assertNull("$h:$m",parseVisitClock(h,m,true,false))
        assertNull(parseVisitClock("00","30",false,false));assertNull(parseVisitClock("13","30",false,true))
    }
}
