package com.bontecou.whereday

import com.bontecou.whereday.domain.HistoryRange
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class CustomDateRangesTest {
    private val apia=ZoneId.of("Pacific/Apia")
    private val skipped=LocalDate.of(2011,12,30)
    @Test fun aSkippedCivilDateCannotBecomeAHistoryWindow() {
        assertEquals(CustomDateError.skipped,customDateError(skipped,skipped,null,apia))
        assertThrows(IllegalArgumentException::class.java) {HistoryRange.days(skipped,skipped,apia)}
    }
    @Test fun rangesSpanningASkippedDateRemainValid() {
        for((first,last) in listOf(skipped.minusDays(1) to skipped,skipped to skipped.plusDays(1),skipped.minusDays(1) to skipped.plusDays(1))) {
            assertNull(customDateError(first,last,null,apia))
            assertTrue(HistoryRange.days(first,last,apia).from<HistoryRange.days(first,last,apia).until)
        }
    }
    @Test fun countryDatesDoNotInheritThePhonesSkippedDays() {
        assertNull(customDateError(skipped,skipped,null,null))
        assertNull(customDateError(skipped,skipped,null,ZoneOffset.UTC))
    }
    @Test fun malformedOrderFutureAndStorageOverflowCannotBeApplied() {
        assertEquals(CustomDateError.invalid,customDateError(null,skipped,null,apia))
        assertEquals(CustomDateError.order,customDateError(skipped.plusDays(1),skipped,null,apia))
        assertEquals(CustomDateError.future,customDateError(skipped,skipped.plusDays(1),skipped,apia))
        assertEquals(CustomDateError.unsupported,customDateError(LocalDate.MIN,LocalDate.MAX,null,apia))
    }
    @Test fun isoEraAndExpandedYearsRemainAvailableWithinTheStorageRange() {
        for(day in listOf(LocalDate.of(-1,12,31),LocalDate.of(0,12,30),LocalDate.of(1,1,1),LocalDate.of(9999,12,31),LocalDate.of(10000,1,1))) {
            assertNull(day.toString(),customDateError(day,day,null,ZoneOffset.UTC))
            val range=HistoryRange.days(day,day,ZoneOffset.UTC)
            assertEquals(day,Instant.ofEpochMilli(range.from.toEpochMilli()).atZone(ZoneOffset.UTC).toLocalDate())
        }
        val outside=Instant.ofEpochMilli(Long.MAX_VALUE).atZone(ZoneOffset.UTC).toLocalDate()
        assertEquals(CustomDateError.unsupported,customDateError(outside,outside,null,ZoneOffset.UTC))
    }
    @Test fun changingOneDateRetainsItsClockMinuteAndTheOtherExactInstant() {
        val start=Instant.parse("2026-10-10T18:12:34.125Z")
        val end=Instant.parse("2026-10-12T20:34:56.875Z")
        val draft=MapDateDraft(HistoryRange(start,end.plusMillis(1)),ZoneOffset.UTC)
        val changedStart=draft.apply(LocalDate.of(2026,10,11),draft.last).range(Instant.EPOCH,ZoneOffset.UTC)
        assertEquals(Instant.parse("2026-10-11T18:12:00Z"),changedStart.from)
        assertEquals(end.plusMillis(1),changedStart.until)
        val changedEnd=draft.apply(draft.first,LocalDate.of(2026,10,13)).range(Instant.EPOCH,ZoneOffset.UTC)
        assertEquals(start,changedEnd.from)
        assertEquals(Instant.parse("2026-10-13T20:34:00.001Z"),changedEnd.until)
    }

    @Test fun daylightSavingEditsRetainTheOriginalClockMinutes() {
        val zone=ZoneId.of("America/New_York")
        val draft=MapDateDraft(HistoryRange.days(LocalDate.of(2026,3,1),LocalDate.of(2026,3,2),zone),zone)
        val day=LocalDate.of(2026,3,8)
        val edited=draft.apply(day,day).range(Instant.EPOCH,zone)
        assertEquals(Instant.parse("2026-03-08T05:00:00Z"),edited.from)
        assertEquals(Instant.parse("2026-03-09T03:59:00.001Z"),edited.until)
        assertTrue(edited.contains(edited.until.minusMillis(1)));assertFalse(edited.contains(edited.until))
    }
    @Test fun unchangedPresetOrOutingKeepsItsExactBounds() {
        val now=Instant.parse("2026-10-31T18:12:34.125Z")
        val zone=ZoneId.of("Pacific/Honolulu")
        val selections=listOf("Today","Yesterday","7 days","30 days","All").map {MapSelection(it)}+
            MapSelection("Outing",outingRange=HistoryRange(now.minusSeconds(3600),now))
        for(selection in selections) {
            val initial=selection.range(now,zone);val draft=MapDateDraft(initial,zone)
            assertEquals(initial,draft.apply(draft.first,draft.last).range(now.plusSeconds(3600),zone))
        }
    }
    @Test fun retainedClockOrderIsCheckedAfterDateEdits() {
        val range=HistoryRange(Instant.parse("2026-10-10T20:00:00Z"),Instant.parse("2026-10-11T10:00:00.001Z"))
        val draft=MapDateDraft(range,ZoneOffset.UTC)
        val day=LocalDate.of(2026,10,11)
        assertEquals(CustomDateError.retainedTimes,draft.error(day,day))
        assertThrows(IllegalArgumentException::class.java) {draft.apply(day,day)}
        assertNull(draft.error(day,day.plusDays(1)))
    }
    @Test fun aHalfOpenMidnightEndSeedsThePreviousCivilDay() {
        val first=LocalDate.of(1850,12,31);val last=LocalDate.of(1851,1,2)
        val draft=MapDateDraft(HistoryRange.days(first,last,ZoneOffset.UTC),ZoneOffset.UTC)
        assertEquals(first,draft.first);assertEquals(last,draft.last)
    }
}
