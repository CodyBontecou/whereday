package com.bontecou.whereday.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

/** Map-first home: route colouring, day story, multi-day aggregation and replay playback. */
class MapFirstHomeTest {
    private val zone=ZoneOffset.UTC
    private val day=LocalDate.of(2026,11,1)
    private fun at(hour:Int,minute:Int=0)=day.atTime(hour,minute).toInstant(zone)
    private fun point(id:String,time:Instant,longitude:Double,outlier:Boolean=false)=TrackPoint(id,Coordinate(37.0,longitude),time,5.0,outlier=outlier)
    private fun visit(id:String,name:String?,from:Instant,until:Instant?,longitude:Double=0.0)=Visit(id,Coordinate(37.0,longitude),from,until,name=name)

    private val home=visit("home","Home",at(0),at(8,12),0.0)
    private val ferry=visit("ferry","Ferry Building",at(8,40),at(9,25),0.02)
    private val office=visit("office","Mission Office",at(9,50),null,0.03)

    @Test fun routeLegsTakeTheInkOfTheVisitTheyLeadTo() {
        val path=listOf(point("a",at(8,12),0.0),point("b",at(8,20),0.01),point("c",at(8,40),0.02),point("d",at(9,30),0.025),point("e",at(9,50),0.03))
        val legs=RouteLegs.build(listOf(path),listOf(office,home,ferry))
        // Home(0) → Ferry(1) → Office(2), in order of first arrival whatever the input order.
        assertEquals(listOf("Home","Ferry Building","Mission Office"),legs.map {it.destination})
        assertEquals(listOf(0,1,2),legs.map {it.ink})
        // Legs share boundary fixes so the drawn line is continuous.
        legs.zipWithNext().forEach {(a,b)->assertEquals(a.points.last(),b.points.first())}
        assertEquals(listOf("b","c","d"),legs[1].points.map {it.id})
    }

    @Test fun pathAfterTheLastStayHasNoDestinationInk() {
        val done=visit("v","Cafe",at(10),at(11))
        val legs=RouteLegs.build(listOf(listOf(point("a",at(11,30),0.0),point("b",at(11,40),0.01))),listOf(done))
        assertEquals(1,legs.size);assertNull(legs.single().destination);assertNull(legs.single().ink)
        assertEquals(0,RouteLegs.inkAt(at(9),listOf(done)))
        assertNull(RouteLegs.inkAt(at(12),listOf(done)))
    }

    @Test fun visitsWithTheSameNameShareOneInk() {
        val again=visit("office-2","Mission Office",at(13,50),null,0.03)
        val first=visit("office-1","Mission Office",at(9,50),at(12,30),0.03)
        val tartine=visit("tartine","Tartine",at(12,45),at(13,35),0.04)
        val inks=PlaceInks.index(listOf(again,tartine,first))
        assertEquals(mapOf("Mission Office" to 0,"Tartine" to 1),inks)
    }

    @Test fun timeChipsMarkOnlyTheMainLegs() {
        val short=RouteLeg("a",0,listOf(point("1",at(8),0.0),point("2",at(8,1),0.0001)))
        val long=RouteLeg("b",1,listOf(point("3",at(9),0.0),point("4",at(9,10),0.05)))
        assertEquals(listOf(long),RouteLegs.chipLegs(listOf(short,long)))
    }

    @Test fun dayStoryClipsStaysAndMeasuresMovesBetweenThem() {
        val points=listOf(point("a",at(8,12),0.0),point("b",at(8,30),0.01),point("c",at(8,40),0.02),point("x",at(8,35),5.0,outlier=true))
        val story=DayStory.build(day,zone,listOf(ferry,home,office),points,at(14))
        assertEquals(listOf("home","ferry","office"),story.stays.map {it.id})
        assertEquals("office",story.currentStay?.id)
        assertEquals(3,story.placeCount)
        val move=(story.entries[1] as DayEntry.Move).move
        assertEquals(Duration.ofMinutes(28),move.duration)
        assertTrue(move.distanceMeters>1700 && move.distanceMeters<1800)
        assertEquals(TravelMode.walk,move.mode)
        // Away: 14:00 since midnight, minus 8h12 + 45 + 4h10 at stays.
        assertEquals(Duration.ofMinutes(14*60-(8*60+12)-45-(4*60+10)),story.awayDuration)
        assertEquals(1.0,story.segments.last().end*24/14,0.001)
    }

    @Test fun pastDayHasNoCurrentStay() {
        val story=DayStory.build(day,zone,listOf(office),emptyList(),at(0).plus(Duration.ofDays(2)))
        assertNull(story.currentStay);assertEquals(1.0,story.segments.single().end,0.0)
    }

    @Test fun travelModeFollowsTheIosThresholds() {
        assertNull(TravelMode.infer(0.2));assertEquals(TravelMode.walk,TravelMode.infer(1.2));assertEquals(TravelMode.run,TravelMode.infer(3.0))
        assertEquals(TravelMode.bike,TravelMode.infer(5.0));assertEquals(TravelMode.drive,TravelMode.infer(20.0));assertEquals(TravelMode.fly,TravelMode.infer(200.0))
    }

    @Test fun multiDayAggregationCountsVisitDaysPerPlaceAndDistancePerDay() {
        val accumulator=DayDistanceAccumulator(zone)
        val d1=day;val d2=day.plusDays(1)
        listOf(point("a",at(9),0.0),point("b",at(10),0.01),point("o",at(10,30),3.0,outlier=true),
            point("c",d2.atTime(9,0).toInstant(zone),0.01),point("d",d2.atTime(9,30).toInstant(zone),0.03)).forEach(accumulator::add)
        val distances=accumulator.distances()
        assertEquals(Coordinate(37.0,0.0).distanceTo(Coordinate(37.0,0.01)),distances.getValue(d1),0.001)
        // The overnight leg counts toward the day it ends on, and outliers never add distance.
        assertEquals(Coordinate(37.0,0.01).distanceTo(Coordinate(37.0,0.03)),distances.getValue(d2),0.001)
        assertEquals(3L,accumulator.counts().getValue(d1))

        val visits=listOf(visit("1","Cafe",at(9),at(10)),visit("2","Cafe",at(12),at(13)),
            visit("3","Cafe",d2.atTime(8,0).toInstant(zone),d2.atTime(9,0).toInstant(zone)),visit("4","Park",d2.atTime(11,0).toInstant(zone),d2.atTime(12,0).toInstant(zone)))
        val range=HistoryRange.days(d1,d2.plusDays(1),zone)
        val summary=MapRangeAggregation.summarize(range,zone,d2.plusDays(1).atTime(12,0).toInstant(zone),visits,distances,accumulator.counts())
        assertEquals(listOf(d1,d2,d2.plusDays(1)),summary.days.map {it.day})
        val cafe=summary.places.first {it.key=="Cafe"}
        assertEquals(2,cafe.days);assertEquals(3,cafe.visits);assertEquals(0,cafe.ink)
        assertEquals(1,summary.places.first {it.key=="Park"}.days)
        assertEquals(listOf(2,2,0),summary.days.map {it.visitCount})
        assertEquals(listOf(1,2,0),summary.days.map {it.placeCount})
        assertEquals(4,summary.visitCount)
        assertEquals(d2,summary.latestActiveDay)
        assertEquals(distances.values.sum(),summary.distanceMeters,0.0001)
    }

    @Test fun overnightStaysCountForEveryDayTheyCover() {
        val night=visit("n","Hotel",at(22),at(8).plus(Duration.ofDays(1)))
        val summary=MapRangeAggregation.summarize(HistoryRange.days(day,day.plusDays(1),zone),zone,at(23).plus(Duration.ofDays(1)),listOf(night),emptyMap())
        assertEquals(2,summary.places.single().days)
    }

    @Test fun dayPathsAreCappedButKeepTheirEnds() {
        val many=(0 until 1000).map {point("p$it",at(0).plusSeconds(it*60L),it/1000.0)}
        val capped=MapRangeAggregation.dayPaths(many,zone,limit=100)
        assertEquals(100,capped.getValue(day).size)
        assertEquals("p0",capped.getValue(day).first().id);assertEquals("p999",capped.getValue(day).last().id)
    }

    @Test fun allTimeRangesListAtMostFourHundredDays() {
        val now=at(12)
        val days=MapRangeAggregation.days(HistoryRange(Instant.EPOCH,now),zone,now,Instant.EPOCH)
        assertEquals(400,days.size);assertEquals(day,days.last())
    }

    @Test fun replayAdvancesByItsSpeedAndStopsAtTheEnd() {
        var playback=ReplayPlayback(count=1801).toggle()
        assertTrue(playback.playing)
        assertEquals(10L,playback.stepSize())
        playback=playback.tick();assertEquals(10L,playback.index)
        assertEquals(100L,playback.withSpeed(ReplaySpeed.fast).stepSize())
        assertEquals(600L,playback.withSpeed(ReplaySpeed.fastest).stepSize())
        repeat(5) {playback=playback.withSpeed(ReplaySpeed.fastest).tick()}
        assertEquals(1800L,playback.index);assertFalse(playback.playing);assertTrue(playback.atEnd)
        // Playing again at the end starts over.
        val again=playback.toggle();assertEquals(0L,again.index);assertTrue(again.playing)
    }

    @Test fun replayScrubbingPausesAndReduceMotionJumps() {
        val playing=ReplayPlayback(count=11).toggle()
        val scrubbed=playing.scrub(0.5);assertEquals(5L,scrubbed.index);assertFalse(scrubbed.playing)
        assertEquals(0.5,scrubbed.progress,0.0)
        val jumped=ReplayPlayback(count=11).toggle(reduceMotion=true)
        assertEquals(10L,jumped.index);assertFalse(jumped.playing)
        assertEquals(3L,ReplayPlayback(index=9,count=20).withCount(4).index)
        assertFalse(ReplayPlayback(count=1).toggle().playing)
        assertEquals(ReplaySpeed.fast,ReplaySpeed.restore("fast"));assertEquals(ReplaySpeed.normal,ReplaySpeed.restore(null))
    }

    @Test fun replaySpeedUsesGpsSpeedOrTheNeighbourPace() {
        val a=point("a",at(9),0.0);val b=point("b",at(9,1),0.001)
        assertEquals(3.0,ReplayPlayback.movementSpeed(a.copy(speed=3.0),b)!!,0.0)
        assertEquals(a.coordinate.distanceTo(b.coordinate)/60,ReplayPlayback.movementSpeed(a,b)!!,0.0001)
        assertNull(ReplayPlayback.movementSpeed(a,point("c",at(10),0.001)))
    }
}
