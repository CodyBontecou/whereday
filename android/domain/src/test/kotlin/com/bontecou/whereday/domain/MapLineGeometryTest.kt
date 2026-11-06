package com.bontecou.whereday.domain

import org.junit.Assert.*
import org.junit.Test

class MapLineGeometryTest {
    @Test fun emptySingleAndRepeatedPositionsHaveNoLine() {
        val point=Coordinate(0.0,12.0)
        for(input in listOf(emptyList(),listOf(point),listOf(point,point),listOf(Coordinate(0.0,180.0),Coordinate(0.0,-180.0)))) assertTrue(MapLineGeometry.splitAtDateLine(input).isEmpty())
    }
    @Test fun ordinaryLineKeepsItsCoordinatesAndOrder() {
        val input=listOf(Coordinate(48.8,2.2),Coordinate(48.9,2.4))
        assertEquals(listOf(input),MapLineGeometry.splitAtDateLine(input))
    }
    @Test fun crossingsInBothDirectionsMeetAtTheSameProjectedLatitude() {
        val input=listOf(Coordinate(10.0,179.0),Coordinate(20.0,-179.0))
        val pieces=MapLineGeometry.splitAtDateLine(input)
        assertEquals(2,pieces.size)
        assertEquals(input.first(),pieces.first().first());assertEquals(input.last(),pieces.last().last())
        assertEquals(180.0,pieces.first().last().longitude,0.0)
        assertEquals(-180.0,pieces.last().first().longitude,0.0)
        assertEquals(pieces.first().last().latitude,pieces.last().first().latitude,0.0)
        assertTrue(pieces.first().last().latitude in 15.0..15.1)
        assertEquals(pieces.map {it.reversed()}.reversed(),MapLineGeometry.splitAtDateLine(input.reversed()))
    }
    @Test fun exactBoundaryFixesDoNotCreateLongOrDegenerateLines() {
        val input=listOf(Coordinate(0.0,179.0),Coordinate(0.0,-180.0),Coordinate(0.0,-179.0),Coordinate(0.0,180.0),Coordinate(0.0,179.0))
        val pieces=MapLineGeometry.splitAtDateLine(input)
        assertEquals(3,pieces.size)
        assertEquals(listOf(Coordinate(0.0,179.0),Coordinate(0.0,180.0)),pieces.first())
        for(piece in pieces) {
            assertTrue(piece.size>=2)
            assertTrue(piece.zipWithNext().all {(a,b)->kotlin.math.abs(a.longitude-b.longitude)<=180.0})
        }
    }
    @Test fun projectedPolarCutsStayFiniteAndLeaveRawFixesUntouched() {
        val input=listOf(Coordinate(90.0,179.0),Coordinate(-90.0,-179.0))
        val pieces=MapLineGeometry.splitAtDateLine(input)
        assertTrue(pieces.flatten().all {it.latitude.isFinite() && it.latitude in -MapCameraBounds.MAX_LATITUDE..MapCameraBounds.MAX_LATITUDE})
        assertEquals(90.0,input.first().latitude,0.0)
        assertEquals(-90.0,input.last().latitude,0.0)
    }
}
