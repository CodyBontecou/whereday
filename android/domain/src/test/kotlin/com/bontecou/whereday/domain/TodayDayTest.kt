package com.bontecou.whereday.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import org.junit.Assert.*
import org.junit.Test

class TodayDayTest {
    private val zone=ZoneId.of("America/New_York")
    private val midnight=Instant.parse("2026-11-02T05:00:00Z")
    private val now=midnight.plus(Duration.ofHours(15))
    private val cafe=Coordinate(40.7000,-74.0000)
    private val office=Coordinate(40.7300,-74.0000)
    private fun at(hours:Double)=midnight.plusSeconds((hours*3600).toLong())
    private fun point(hours:Double,c:Coordinate,outlier:Boolean=false)=TrackPoint(coordinate=c,time=at(hours),accuracy=5.0,outlier=outlier)
    private val name:(Visit)->String={it.name ?: "Visit"}

    @Test fun travelModeUsesTheIosSpeedBands() {
        assertNull(TravelMode.infer(0.3));assertNull(TravelMode.infer(Double.NaN));assertNull(TravelMode.infer(100.0,0.0))
        assertEquals(TravelMode.walk,TravelMode.infer(1.99));assertEquals(TravelMode.run,TravelMode.infer(2.0))
        assertEquals(TravelMode.bike,TravelMode.infer(3.4));assertEquals(TravelMode.drive,TravelMode.infer(8.0))
        assertEquals(TravelMode.transit,TravelMode.infer(45.0));assertEquals(TravelMode.fly,TravelMode.infer(70.0))
    }

    @Test fun staysClipToMidnightAndNowWithMovesBetweenThem() {
        val overnight=Visit(coordinate=cafe,arrivedAt=midnight.minus(Duration.ofHours(3)),departedAt=at(8.0),name="Cafe")
        val work=Visit(coordinate=office,arrivedAt=at(9.0),name="Office")
        val day=TodayDay.build(listOf(work,overnight),listOf(point(8.0,cafe),point(8.5,Coordinate(40.715,-74.0)),point(9.0,office)),now,zone,name)
        assertEquals(listOf("Cafe","Office"),day.stays.map {it.name})
        assertEquals(midnight,day.stays[0].start);assertEquals(now,day.stays[1].end);assertTrue(day.stays[1].current)
        assertEquals(listOf(0,1),day.stays.map {it.ink})
        val move=(day.entries[1] as TodayDay.Entry.MoveEntry).move
        assertEquals(Duration.ofHours(1),move.duration);assertTrue(move.distance>3000)
        assertEquals(TravelMode.walk,move.mode);assertEquals(move,day.stays[1].arrival)
        assertEquals(0.0,day.segments[0].start,1e-9);assertEquals(8/24.0,day.segments[0].end,1e-9)
        assertEquals(Duration.ofHours(1),day.away);assertEquals("Office",day.currentStay?.name)
    }

    @Test fun samePlaceNameReusesItsInkAndInksCycleThroughFour() {
        val visits=(0 until 6).map {Visit(coordinate=cafe,arrivedAt=at(it*2.0),departedAt=at(it*2.0+1),name=if(it==5) "P0" else "P$it")}
        assertEquals(listOf(0,1,2,3,0,0),TodayDay.build(visits,emptyList(),now,zone,name).stays.map {it.ink})
    }

    @Test fun longSilentGapsReadAsNotRecordedOnlyWhenListeningIsLimited() {
        val visits=listOf(Visit(coordinate=cafe,arrivedAt=at(1.0),departedAt=at(2.0),name="A"),Visit(coordinate=office,arrivedAt=at(4.0),departedAt=at(5.0),name="B"))
        val limited=TodayDay.build(visits,emptyList(),now,zone,name,marksUnrecordedGaps=true)
        assertEquals(listOf("stay-${visits[0].id}","gap-${visits[1].id}","stay-${visits[1].id}","gap-end"),limited.entries.map {it.id})
        assertEquals(Duration.ofHours(10),(limited.entries.last() as TodayDay.Entry.Unrecorded).duration)
        val always=TodayDay.build(visits,emptyList(),now,zone,name)
        assertNull((always.entries[1] as TodayDay.Entry.MoveEntry).move.mode)
        assertEquals(3,always.entries.size)
    }

    @Test fun outliersAreIgnoredAndAnEmptyDayHasNoAwayTime() {
        val empty=TodayDay.build(emptyList(),listOf(point(3.0,cafe,outlier=true)),now,zone,name)
        assertTrue(empty.stays.isEmpty());assertNull(empty.away)
        val pointsOnly=TodayDay.build(emptyList(),listOf(point(3.0,cafe)),now,zone,name)
        assertEquals(Duration.ofHours(12),pointsOnly.away)
    }

    @Test fun homeStartsTheDayAndOnlyTimeAwayFromHomeCounts() {
        val home=SavedPlace(name="Home",coordinate=cafe,radius=150.0)
        val work=Visit(coordinate=office,arrivedAt=at(9.0),departedAt=at(14.0),name="Office")
        val points=listOf(point(1.0,cafe),point(7.5,cafe),point(8.0,Coordinate(40.715,-74.0)),point(9.0,office))
        val day=TodayDay.build(listOf(work),points,now,zone,name,home=home)
        assertEquals(listOf("Home","Office"),day.stays.map {it.name});assertNull(day.stays[0].visit)
        assertEquals(midnight,day.stays[0].start);assertEquals(at(7.5),day.stays[0].end)
        assertEquals(listOf(0,1),day.stays.map {it.ink})
        // Span from midnight is 15 h; Home counts as present (7.5 h), Office does not.
        assertEquals(Duration.ofMinutes(450),day.away)
        val unnamedAtHome=Visit(coordinate=cafe,arrivedAt=at(16.0-2),departedAt=null)
        assertEquals("Home",TodayDay.build(listOf(unnamedAtHome),emptyList(),now,zone,name,home=home).stays.single().name)
    }
}
