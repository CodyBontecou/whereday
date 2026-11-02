package com.bontecou.whereday.domain

import org.junit.Assert.*
import org.junit.Test

class MapCameraBoundsTest {
    @Test fun emptyInputHasNoCameraChange() {assertNull(MapCameraBounds.enclosing(emptyList()))}
    @Test fun ordinaryCoordinatesKeepTheirExtent() {
        assertEquals(MapCameraBounds(48.8,2.2,48.9,2.4),MapCameraBounds.enclosing(listOf(Coordinate(48.8,2.4),Coordinate(48.9,2.2))))
    }
    @Test fun dateLineCrossingUsesTheShortArcRegardlessOfInputOrder() {
        val input=listOf(Coordinate(10.0,179.8),Coordinate(10.1,-179.7),Coordinate(10.2,179.9))
        val expected=MapCameraBounds(10.0,-180.2,10.2,-179.7)
        assertEquals(expected,MapCameraBounds.enclosing(input))
        assertEquals(expected,MapCameraBounds.enclosing(input.reversed()+input))
    }
    @Test fun equivalentDateLineLongitudesAndDuplicateFixesAreOnePosition() {
        val bounds=MapCameraBounds.enclosing(listOf(Coordinate(0.0,180.0),Coordinate(0.0,-180.0)))!!
        assertTrue(bounds.isPoint);assertEquals(-180.0,bounds.west,0.0)
    }
    @Test fun northAndSouthPolesAreClampedOnlyForPresentation() {
        val input=listOf(Coordinate(-90.0,12.0),Coordinate(90.0,12.0))
        val bounds=MapCameraBounds.enclosing(input)!!
        assertEquals(-MapCameraBounds.MAX_LATITUDE,bounds.south,0.0)
        assertEquals(MapCameraBounds.MAX_LATITUDE,bounds.north,0.0)
        assertEquals(-90.0,input.first().latitude,0.0);assertEquals(90.0,input.last().latitude,0.0)
        assertTrue(MapCameraBounds.enclosing(listOf(Coordinate(90.0,12.0),Coordinate(89.0,12.0)))!!.isPoint)
    }
    @Test fun equalLargestGapsPreferTheOrdinaryNonWrappingExtent() {
        assertEquals(MapCameraBounds(0.0,-90.0,0.0,90.0),MapCameraBounds.enclosing(listOf(Coordinate(0.0,90.0),Coordinate(0.0,-90.0))))
    }
    @Test fun worldwideAndBoundedLargeInputsRetainEveryCoordinateInTheirUnwrappedExtent() {
        val inputs=listOf(
            (0 until 360).map {Coordinate((it%170-85).toDouble(),it-180.0)},
            (0 until 2500).map {Coordinate(10.0+it/25000.0,if(it%2==0) 179.8 else -179.7)},
        )
        for(input in inputs) {
            val bounds=MapCameraBounds.enclosing(input)!!
            assertTrue(bounds.east-bounds.west<360.0)
            for(point in input) {
                val unwrapped=listOf(point.longitude-360.0,point.longitude,point.longitude+360.0)
                assertTrue(unwrapped.any {it>=bounds.west-1e-9 && it<=bounds.east+1e-9})
                assertTrue(point.latitude in bounds.south..bounds.north)
            }
        }
    }
}
