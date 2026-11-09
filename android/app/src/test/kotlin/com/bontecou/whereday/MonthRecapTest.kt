package com.bontecou.whereday

import com.bontecou.whereday.domain.Coordinate
import com.bontecou.whereday.domain.Visit
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

class MonthRecapTest {
    private val zone=ZoneId.of("America/Puerto_Rico")
    private val month=YearMonth.of(2026,11)
    private fun at(value:String,zone:ZoneId=this.zone)=java.time.LocalDateTime.parse(value).atZone(zone).toInstant()
    private fun visit(id:String,start:String,end:String?=null,name:String=id)=
        Visit(id,Coordinate(0.0,0.0),at(start),end?.let {at(it)},name=name)
    private fun recap(visits:List<Visit>,now:Instant=at("2026-11-07T16:30"),points:Set<Int> = emptySet())=
        MonthRecap.of(month,zone,visits,emptyList(),points,emptySet(),now)

    @Test fun multiDayVisitCountsEveryOccupiedDayAlongsidePointOnlyDays() {
        val value=recap(listOf(visit("overnight","2026-11-04T15:21","2026-11-06T16:21")),points=setOf(6))
        assertEquals(4,value.recordedDays)
        assertEquals(listOf(1,1,1),value.placesPerDay.subList(3,6))
        assertEquals(1,value.visitCount)
        assertTrue(value.arcs.subList(3,6).all {it.size==1})
    }

    @Test fun midnightDepartureDoesNotCountTheFollowingDay() {
        val value=recap(listOf(visit("overnight","2026-11-04T23:00","2026-11-06T00:00")))
        assertEquals(2,value.recordedDays)
        assertEquals(listOf(1,1,0),value.placesPerDay.subList(3,6))
        assertTrue(value.arcs[5].isEmpty())
    }

    @Test fun openVisitCountsOnlyPositiveOverlapThroughNow() {
        val value=recap(listOf(visit("open","2026-11-04T23:00")),now=at("2026-11-06T00:00"))
        assertEquals(2,value.recordedDays)
        assertEquals(listOf(1,1,0),value.placesPerDay.subList(3,6))
        assertTrue(value.arcs[5].isEmpty())
    }

    @Test fun visitsAreClippedToTheMonthAndPlacesAreDistinctWithinEachDay() {
        val value=recap(listOf(
            visit("before","2026-10-30T10:00","2026-11-02T10:00",name="Home"),
            visit("again","2026-11-02T12:00","2026-11-03T10:00",name="Home"),
            visit("end","2026-11-30T10:00","2026-12-02T10:00",name="Away"),
        ),now=at("2026-12-03T00:00"))
        assertEquals(4,value.recordedDays)
        assertEquals(listOf(1,1,1),value.placesPerDay.take(3))
        assertEquals(1,value.placesPerDay.last())
        assertEquals(LocalDate.of(2026,11,1),value.biggestDay)
    }

    @Test fun fullCivilDaysFillTheirDialsAcrossBothDaylightSavingTransitions() {
        val dstZone=ZoneId.of("America/New_York")
        for(day in listOf(LocalDate.of(2026,3,8),LocalDate.of(2026,11,1))) {
            val start=day.atStartOfDay(dstZone).toInstant()
            val end=day.plusDays(1).atStartOfDay(dstZone).toInstant()
            val value=MonthRecap.of(YearMonth.from(day),dstZone,
                listOf(Visit("dst",Coordinate(0.0,0.0),start,end,name="Home")),
                emptyList(),emptySet(),emptySet(),end)
            val arc=value.arcs[day.dayOfMonth-1].single()
            assertEquals(1,value.recordedDays)
            assertEquals(0f,arc.start,0.00001f)
            assertEquals("Full civil day on $day",1f,arc.end,0.00001f)
        }
    }
}
