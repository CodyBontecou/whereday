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
    private val now=Instant.parse("2026-11-04T15:30:12.345Z")
    @Test fun openingOnAPresetKeepsItAndAnOutingOpensOnItsWholeDays() {
        val zone=ZoneOffset.UTC;val today=LocalDate.of(2026,11,4)
        for(preset in MapRangeDraft.presets) {
            val draft=MapRangeDraft.open(MapSelection(preset),now,zone)
            assertEquals(preset,draft.preset)
            assertEquals(MapSelection(preset),draft.apply(now,zone))
            assertEquals(today.takeUnless {preset=="Yesterday"} ?: today.minusDays(1),draft.upper)
        }
        assertEquals(today,MapRangeDraft.open(MapSelection("All"),now,zone).lower)
        val outing=MapSelection("Outing",outingRange=HistoryRange(Instant.parse("2026-10-10T18:12:34Z"),Instant.parse("2026-10-12T00:00:00Z")))
        val draft=MapRangeDraft.open(outing,now,zone)
        assertEquals(listOf(null,LocalDate.of(2026,10,10),LocalDate.of(2026,10,11)),listOf(draft.preset,draft.lower,draft.upper))
        assertEquals(HistoryRange(Instant.parse("2026-10-10T00:00:00Z"),Instant.parse("2026-10-12T00:00:00Z")),draft.apply(now,zone).range(now,zone))
    }
    @Test fun aWindowEndingInTheFutureOpensOnToday() {
        val zone=ZoneOffset.UTC
        val draft=MapRangeDraft.open(MapSelection("Custom",outingRange=HistoryRange(now.minusSeconds(86_400),now.plusSeconds(864_000))),now,zone)
        assertEquals(LocalDate.of(2026,11,4),draft.upper);assertEquals(YearMonth.of(2026,11),draft.month)
        assertFalse(draft.canShowNextMonth(now,zone));assertTrue(draft.showMonth(-1).canShowNextMonth(now,zone))
    }
    @Test fun allTimeCountsEveryDaySinceTheFirstRecordedDay() {
        val zone=ZoneOffset.UTC;val draft=MapRangeDraft.open(MapSelection("Today"),now,zone).select("All",now,zone)
        assertEquals(1,draft.applyDayCount(null,now,zone))
        assertEquals(1,draft.applyDayCount(LocalDate.of(2026,11,4),now,zone))
        assertEquals(35,draft.applyDayCount(LocalDate.of(2026,10,1),now,zone))
        assertEquals(1,draft.select("Today",now,zone).applyDayCount(LocalDate.of(2026,10,1),now,zone))
    }
    @Test fun aCustomSpanEndingTodayStopsAtNow() {
        val zone=ZoneId.of("America/New_York")
        val draft=MapRangeDraft.open(MapSelection("Today"),now,zone).tap(LocalDate.of(2026,11,2),now,zone).tap(LocalDate.of(2026,11,4),now,zone)
        assertNull(draft.preset)
        assertEquals(HistoryRange(Instant.parse("2026-11-02T05:00:00Z"),now.plusMillis(1)),draft.apply(now,zone).range(now,zone))
        assertEquals(3,draft.dayCount)
    }
}
