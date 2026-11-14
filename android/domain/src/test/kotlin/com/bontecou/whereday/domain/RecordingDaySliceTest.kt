package com.bontecou.whereday.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.*

/** Mirrors iOS 88039c65 LocationViewModelPointScalingTests timeline-day clipping cases. */
class RecordingDaySliceTest {
    private val zone=ZoneId.of("America/Puerto_Rico")
    private val dayStart=LocalDate.of(2026,10,1).atStartOfDay(zone).toInstant()
    private fun day(start:Instant=dayStart)=HistoryRange(start,start.plus(Duration.ofDays(1)))
    private fun visit(arrived:Instant,departed:Instant?)=Visit(coordinate=Coordinate(18.0,-67.0),arrivedAt=arrived,departedAt=departed)

    @Test fun thousandHourRecordingIsClippedToOneFullDayAndStoredSessionIsUnchanged() {
        val end=dayStart.plus(Duration.ofHours(1_000))
        val stored=RecordingSession("journal",dayStart,end,name="Continuous journal")
        val day=LocalDate.of(2026,10,21).let {HistoryRange.day(it,zone)}
        val slice=RecordingDayClip.clip(stored,day,now=end)!!
        assertEquals(day.from,slice.startedAt)
        assertEquals(day.until,slice.endedAt)
        assertEquals(Duration.ofDays(1),slice.duration)
        assertFalse(slice.live)
        assertTrue(slice.isPartial)
        // Map, replay and export windows stop before the following midnight.
        assertTrue(slice.lastInstant<day.until)
        assertEquals(day.until,slice.window.until)
        assertEquals(day.from,slice.window.from)
        assertFalse(slice.window.contains(day.until))
        assertEquals(day.until.minusMillis(1),slice.closedWindowSession.endedAt)
        assertEquals("Continuous journal",slice.presented.name)
        assertEquals(day.until,slice.presented.endedAt)
        assertEquals(dayStart,stored.startedAt);assertEquals(end,stored.endedAt)
        // The complete outing keeps its full range.
        assertEquals(HistoryRange(dayStart,end.plusMillis(1)),RecordingDayClip.range(stored,end))
        assertEquals(slice.window,RecordingDayClip.range(stored,end,day))
    }

    @Test fun halfOpenDaysExcludeRecordingsAndVisitsThatOnlyTouchMidnight() {
        val day=day();val end=day.until;val now=end.plusSeconds(7200)
        val previous=RecordingSession("previous",dayStart.minusSeconds(3600),dayStart)
        val current=RecordingSession("current",dayStart,end)
        val next=RecordingSession("next",end,end.plusSeconds(3600))
        assertNull(RecordingDayClip.clip(previous,day,now))
        assertNull(RecordingDayClip.clip(next,day,now))
        val slice=RecordingDayClip.clip(current,day,now)!!
        assertFalse("A recording that fills exactly its own day is not a partial slice",slice.isPartial)
        assertEquals(current,slice.presented)
        assertTrue(slice.window.contains(dayStart));assertTrue(slice.window.contains(dayStart.plusSeconds(60)))
        assertFalse(slice.window.contains(dayStart.minusSeconds(1)));assertFalse(slice.window.contains(end))
        val visits=listOf(visit(dayStart.minusSeconds(3600),dayStart),visit(dayStart,dayStart.plusSeconds(3600)),visit(end,null))
        assertEquals(listOf(dayStart),visits.filter(slice::overlapsDay).map {it.arrivedAt})
    }

    @Test fun historicalSliceOfOpenRecordingIsNotLiveButTodaysSliceIs() {
        val day=day();val end=day.until;val now=end.plusSeconds(7200)
        val stored=RecordingSession("open",dayStart.minusSeconds(3600))
        val daily=RecordingDayClip.clip(stored,day,now)!!
        assertFalse(daily.live)
        assertEquals(end,daily.endedAt)
        assertEquals(Duration.ofDays(1),daily.duration)
        assertEquals(end,daily.presented.endedAt)
        assertTrue(daily.isPartial)
        val live=RecordingDayClip.clip(stored,day(end),now)!!
        assertTrue(live.live)
        assertNull(live.presented.endedAt)
        assertNull(live.closedWindowSession.endedAt)
        assertEquals("A live day with no new fixes still has a bounded recording duration",Duration.ofSeconds(7200),live.duration)
        assertEquals(HistoryRange(end,now.plusMillis(1)),live.window)
        // A day after now has no movement yet.
        assertNull(RecordingDayClip.clip(stored,day(end.plus(Duration.ofDays(1))),now))
    }

    @Test fun ongoingRecordingStartedTodayIsNotPartial() {
        val day=day();val now=dayStart.plusSeconds(5*3600)
        val slice=RecordingDayClip.clip(RecordingSession("today",dayStart.plusSeconds(3600)),day,now)!!
        assertTrue(slice.live);assertFalse(slice.isPartial)
        assertEquals(Duration.ofHours(4),slice.duration)
    }

    @Test fun clippingRespectsDaylightSavingBoundaries() {
        val la=ZoneId.of("America/Los_Angeles")
        for((date,hours) in listOf(LocalDate.of(2026,3,8) to 23L,LocalDate.of(2026,11,1) to 25L)) {
            val day=HistoryRange.day(date,la)
            val slice=RecordingDayClip.clip(RecordingSession(startedAt=day.from.minusSeconds(3600),endedAt=day.until.plusSeconds(3600)),day,day.until.plusSeconds(3600))!!
            assertEquals(Duration.ofHours(hours),slice.duration)
            assertEquals(day.from,slice.startedAt)
            assertEquals(day.until,slice.endedAt)
        }
    }

    @Test fun zeroLengthSliceNeedsADailyFixInsideTheDay() {
        val day=day();val at=dayStart.plusSeconds(600)
        val instant=RecordingSession("instant",at,at)
        assertNotNull(RecordingDayClip.clip(instant,day,day.until,hasDailyPoints=true))
        assertNull(RecordingDayClip.clip(instant,day,day.until,hasDailyPoints=false))
        assertEquals(HistoryRange(at,at.plusMillis(1)),RecordingDayClip.clip(instant,day,day.until)!!.window)
        try {RecordingDayClip.range(RecordingSession("earlier",dayStart.minusSeconds(60),dayStart),dayStart,day);fail()} catch(_:IllegalStateException) {}
    }
}
