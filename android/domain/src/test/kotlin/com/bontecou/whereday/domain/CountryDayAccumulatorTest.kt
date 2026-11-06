package com.bontecou.whereday.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class CountryDayAccumulatorTest {
    private val now = Instant.parse("2026-10-26T12:00:00Z")
    private val coordinate = Coordinate(18.0, -66.0)
    @Test fun movedVisitAndUndoInvalidateGeographicMetadataButNameOnlyEditPreservesIt() {
        val visit=Visit(coordinate=coordinate,arrivedAt=now,countryCode="PR",timeZone="America/Puerto_Rico")
        assertEquals("PR",visit.correct(coordinate,"Cafe",null,now).countryCode)
        val moved=visit.correct(Coordinate(40.0,-74.0),"Cafe",null,now)
        assertNull(moved.countryCode); assertNull(moved.timeZone)
        val resolved=moved.copy(countryCode="US",timeZone="America/New_York")
        val undone=resolved.undoCorrection(now)
        assertEquals(coordinate,undone.coordinate)
        assertNull(undone.countryCode); assertNull(undone.timeZone)
    }
    @Test fun countryLocalDateCanDifferFromPhoneDateAndTravelCountsBothCountries() {
        val a = CountryDayAccumulator(now)
        a.add(TrackPoint(coordinate=coordinate,time=now,accuracy=5.0,countryCode="US",timeZone="Pacific/Honolulu"))
        a.add(TrackPoint(coordinate=coordinate,time=now,accuracy=5.0,countryCode="NZ",timeZone="Pacific/Auckland"))
        assertEquals(setOf("US" to LocalDate.parse("2026-10-26"), "NZ" to LocalDate.parse("2026-10-27")), a.days().map { it.countryCode to it.day }.toSet())
    }
    @Test fun repeatedPointsRemainOneDayAndUnresolvedOutliersAndFutureAreDistinct() {
        val a=CountryDayAccumulator(now)
        val p=TrackPoint(coordinate=coordinate,time=now,accuracy=5.0,countryCode="PR",timeZone="America/Puerto_Rico")
        repeat(100_000) { a.add(p) }
        a.add(p.copy(timeZone=null)); a.add(p.copy(timeZone="invalid")); a.add(p.copy(countryCode="ZZ"))
        a.add(p.copy(outlier=true,timeZone=null)); a.add(p.copy(time=now.plusSeconds(1),timeZone=null))
        assertEquals(1,a.days().size); assertEquals(3L,a.unresolvedPoints)
    }
    @Test fun boundedPeriodClipsVisitsAndManualStaysAndPreservesBothSources() {
        val day=LocalDate.parse("2026-10-26")
        val a=CountryDayAccumulator(now,CountryDayBounds(day,day))
        a.add(Visit(coordinate=coordinate,arrivedAt=now.minusSeconds(172800),departedAt=now,countryCode="PR",timeZone="America/Puerto_Rico"))
        a.add(CountryStay(countryCode="PR",firstDay=day.minusYears(5),lastDay=day.plusYears(5)))
        assertEquals(listOf(CountryPresence("PR",day,true,true)),a.days())
        assertEquals("country_code,local_date,source\r\nPR,2026-10-26,recorded+manual\r\n", countryDaysCsv(a.days()))
    }
    @Test fun periodsMatchIOSIncludingLeapYearAndFutureManualStays() {
        val today=LocalDate.parse("2024-03-01")
        assertEquals(LocalDate.parse("2023-03-03"),CountryDayPeriod.rolling.bounds(today).first)
        assertNull(CountryDayPeriod.rolling.bounds(today).last)
        assertEquals(LocalDate.parse("2024-12-31"),CountryDayPeriod.year.bounds(today).last)
        assertEquals(CountryDayBounds(),CountryDayPeriod.all.bounds(today))
    }
    @Test fun openAndInferredVisitsNeverFillGapsAndMidnightIsExclusiveAcrossDST() {
        val a=CountryDayAccumulator(now)
        val v=Visit(coordinate=coordinate,arrivedAt=Instant.parse("2026-03-07T17:00:00Z"),departedAt=Instant.parse("2026-03-09T04:00:00Z"),countryCode="US",timeZone="America/New_York")
        a.add(v)
        assertEquals(listOf(LocalDate.parse("2026-03-07"),LocalDate.parse("2026-03-08")),a.days().map { it.day })
        val b=CountryDayAccumulator(now)
        b.add(v.copy(departureInferred=true)); b.add(v.copy(departedAt=null))
        b.add(v.copy(timeZone=null))
        assertEquals(1,b.days().size); assertEquals(1L,b.unresolvedVisits)
    }
}
