package com.bontecou.whereday.domain

import org.junit.Test
import org.junit.Assert.*
import java.time.Instant
import java.time.Duration

class RoadMapGeometryTest {
    private val points=(0..2).map {TrackPoint("p$it",Coordinate(48.85,2.29+it*.01),Instant.EPOCH.plusSeconds(it*600L),5.0)}
    private val bend=Coordinate(48.87,2.295)
    private fun route(source:List<TrackPoint> = points)=RoadRoute(RoadSnapping.fingerprint(source),source.size,listOf(RoadSegment(0,1,listOf(source[0].coordinate,bend,source[1].coordinate),true),RoadSegment(1,2,listOf(source[1].coordinate,source[2].coordinate),false)))
    private fun frame(current:TrackPoint,index:Long=9999)=ReplayFrame(index,10_000,current,listOf(points[0],current),Duration.ZERO,Duration.ZERO,0.0,0.0)
    private fun raw(source:List<TrackPoint> = points,layers:MapLayers=MapLayers(),replay:ReplayFrame?=null)=MapPresentation.build(source,emptyList(),RouteEndpoints.from(source),layers,replay).segments
    @Test fun sourceBoundRoadsKeepRawPresentationAndFitTheOffAxisDetour() {
        val found=RoadMapGeometry.build(points,raw(),MapLayers(),route())
        assertTrue(found.prepared);assertEquals(listOf(points.map {it.coordinate}),found.raw)
        assertEquals(listOf(listOf(points[0].coordinate,bend,points[1].coordinate)),found.roads);assertTrue(bend in found.fit)
        val hidden=RoadMapGeometry.build(points,raw(),MapLayers(),route(),straightSegments=false)
        assertTrue(hidden.raw.isEmpty());assertEquals(found.roads,hidden.roads)
    }
    @Test fun staleSourceAccuracyIdentityAndFilterChangesCannotRenderPreparedRoads() {
        val original=route()
        for(source in listOf(points.dropLast(1),points.mapIndexed {i,p->if(i==0) p.copy(accuracy=6.0) else p},points.mapIndexed {i,p->if(i==0) p.copy(id="replacement") else p},points.mapIndexed {i,p->if(i==1) p.copy(outlier=true) else p})) {
            val found=RoadMapGeometry.build(source,raw(source),MapLayers(),original,straightSegments=false)
            assertFalse(found.prepared);assertTrue(found.roads.isEmpty());assertEquals(raw(source).map {line->line.map {it.coordinate}},found.raw)
        }
    }
    @Test fun replayDoesNotRevealFutureRoadsWhenItsFullHistoryIndexExceedsTheMapSample() {
        val between=points[0].copy(id="between",time=Instant.EPOCH.plusSeconds(300),coordinate=Coordinate(48.85,2.295))
        val progress=frame(between)
        val partial=RoadMapGeometry.build(points,raw(replay=progress),MapLayers(),route(),progress,straightSegments=false)
        assertTrue(partial.prepared);assertTrue(partial.roads.isEmpty());assertTrue(partial.raw.isEmpty())
        val exact=frame(points[1]);assertEquals(1,RoadMapGeometry.build(points,raw(replay=exact),MapLayers(),route(),exact).roads.size)
        val equalTimeBefore=frame(points[1].copy(id="p0"))
        assertTrue(RoadMapGeometry.build(points,raw(replay=equalTimeBefore),MapLayers(),route(),equalTimeBefore).roads.isEmpty())
    }
    @Test fun layerOffOrMissingSnapsPreservesExpectedRawFallback() {
        val layers=MapLayers(path=false)
        val off=RoadMapGeometry.build(points,raw(),layers,route());assertTrue(off.raw.isEmpty());assertTrue(off.roads.isEmpty());assertTrue(off.fit.isEmpty())
        val absent=RoadMapGeometry.build(points,raw(),MapLayers(),null,straightSegments=false);assertFalse(absent.prepared);assertFalse(absent.raw.isEmpty())
        val rawOnly=RoadRoute(RoadSnapping.fingerprint(points),3,listOf(RoadSegment(0,2,points.map {it.coordinate},false)))
        assertFalse(RoadMapGeometry.build(points,raw(),MapLayers(),rawOnly,straightSegments=false).raw.isEmpty())
    }
    @Test fun malformedSpansAndHugeGeometryAreRejectedBeforeConsumption() {
        val huge=object:AbstractList<Coordinate>() {override val size=100001;override fun get(index:Int):Coordinate=error("Oversized geometry must not be read")}
        val bad=listOf(route().copy(segments=listOf(RoadSegment(0,3,listOf(points[0].coordinate,points.last().coordinate),true))),
            route().copy(segments=listOf(RoadSegment(0,1,huge,true))),
            route().copy(segments=listOf(RoadSegment(0,1,listOf(bend,points[1].coordinate),true))),
            route().copy(segments=listOf(RoadSegment(1,2,listOf(points[1].coordinate,points[2].coordinate),true))))
        bad.forEach {assertFalse(RoadMapGeometry.build(points,raw(),MapLayers(),it).prepared)}
    }
    @Test fun derivedRoadsUseTheSameDateLineCutAndCameraEnvelopeAsRawGeometry() {
        val source=listOf(points[0].copy(coordinate=Coordinate(10.0,179.8)),points[1].copy(coordinate=Coordinate(10.1,-179.7)))
        val coordinates=listOf(source[0].coordinate,Coordinate(10.2,180.0),source[1].coordinate)
        val road=RoadRoute(RoadSnapping.fingerprint(source),2,listOf(RoadSegment(0,1,coordinates,true)))
        val found=RoadMapGeometry.build(source,raw(source),MapLayers(),road)
        assertTrue(found.prepared)
        val cut=found.roads.flatMap(MapLineGeometry::splitAtDateLine)
        assertEquals(2,cut.size)
        cut.forEach {line->line.zipWithNext().forEach {(a,b)->assertTrue(kotlin.math.abs(a.longitude-b.longitude)<=180)}}
        val bounds=checkNotNull(MapCameraBounds.enclosing(found.fit));assertTrue(bounds.east-bounds.west<1.0)
    }
}
