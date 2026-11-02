package com.bontecou.whereday.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.Duration

class MapPresentationTest {
    private val time=Instant.parse("2026-10-27T10:00:00Z")
    private fun point(i:Int,bad:Boolean=false)=TrackPoint("point-$i",Coordinate(0.0,i*.0001),time.plusSeconds(i.toLong()),5.0,outlier=bad)
    @Test fun rawAndFilteredEndpointsStayDistinctAndSingleFixHasNoDuplicateEndFlag() {
        val points=listOf(point(0,true),point(1),point(2),point(3,true))
        val ends=RouteEndpoints.from(points)
        assertEquals(point(0,true) to point(3,true),ends.selected(true));assertEquals(point(1) to point(2),ends.selected())
        val single=listOf(point(1));val map=MapPresentation.build(single,emptyList(),RouteEndpoints.from(single),MapLayers())
        assertEquals(point(1),map.start);assertNull(map.end);assertTrue(map.segments.isEmpty())
    }
    @Test fun layerChoicesAreIndependentAndNeverMutateOriginalFixes() {
        val points=listOf(point(0),point(1,true),point(2));val visit=Visit("visit",Coordinate(0.0,0.0),time)
        val ends=RouteEndpoints.from(points)
        val hidden=MapPresentation.build(points,listOf(visit),ends,MapLayers(false,false,false,false))
        assertTrue(hidden.segments.isEmpty());assertTrue(hidden.points.isEmpty());assertTrue(hidden.visits.isEmpty());assertNull(hidden.start);assertNull(hidden.end)
        val shown=MapPresentation.build(points,listOf(visit),ends,MapLayers(outliers=true))
        assertEquals(points,shown.points);assertEquals(listOf(points),shown.segments);assertEquals(listOf(visit),shown.visits)
        val filtered=MapPresentation.build(points,listOf(visit),ends,MapLayers())
        assertEquals(listOf(point(0),point(2)),filtered.points);assertTrue(points[1].outlier)
    }
    @Test fun realGapsSplitThePathAndAllOutlierFallbackIsExplicit() {
        val points=listOf(point(0),point(1),point(2).copy(time=time.plusSeconds(900)),point(3).copy(time=time.plusSeconds(901)))
        assertEquals(listOf(points.take(2),points.takeLast(2)),MapPresentation.build(points,emptyList(),RouteEndpoints.from(points),MapLayers()).segments)
        val bad=listOf(point(0,true),point(1,true));val ends=RouteEndpoints.from(bad)
        assertTrue(MapPresentation.build(bad,emptyList(),ends,MapLayers()).points.isEmpty())
        val fallback=MapPresentation.build(bad,emptyList(),ends,MapLayers(),fallback=true)
        assertEquals(bad,fallback.points);assertEquals(bad.first(),fallback.start);assertEquals(bad.last(),fallback.end)
    }
    @Test fun replayDrawsOnlyItsPrefixButEndpointFlagsKeepTheSelectedRangeBounds() {
        val all=(0..9).map {point(it)};val prefix=all.take(4)
        val frame=ReplayFrame(3,10,all[3],prefix,Duration.ofSeconds(3),Duration.ofSeconds(9),1.0,3.0)
        val map=MapPresentation.build(all,emptyList(),RouteEndpoints.from(all),MapLayers(),frame)
        assertEquals(prefix,map.points);assertEquals(listOf(prefix),map.segments);assertEquals(all.first(),map.start);assertEquals(all.last(),map.end)
    }
}
