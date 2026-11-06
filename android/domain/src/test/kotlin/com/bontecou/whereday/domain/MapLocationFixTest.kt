package com.bontecou.whereday.domain

import org.junit.Assert.*
import org.junit.Test

class MapLocationFixTest {
    private val fix=MapLocationFix(Coordinate(18.0,-66.0),5.0,1000)
    @Test fun monotonicAgeRejectsStaleFutureAndInvalidFixes() {
        assertTrue(fix.usable(31000));assertFalse(fix.usable(31001));assertFalse(fix.usable(999))
        assertFalse(fix.copy(elapsedMillis=-1).usable(1000))
        for(value in listOf(Double.NaN,Double.POSITIVE_INFINITY,-1.0,100001.0)) assertFalse(fix.copy(accuracyMeters=value).usable(1000))
    }
    @Test fun approximatePositionsRemainUsableAndDoNotLookLikePreciseStreetLocations() {
        assertTrue(fix.copy(accuracyMeters=20000.0).usable(1000))
        assertTrue(fix.maximumZoom>fix.copy(accuracyMeters=20000.0).maximumZoom)
        assertTrue(fix.copy(accuracyMeters=20000.0).maximumZoom<10.0)
    }
    @Test fun zoomIsFiniteAndBoundedAtPolesAndForZeroAccuracy() {
        for(latitude in listOf(-90.0,0.0,90.0)) for(accuracy in listOf(0.0,5.0,100000.0)) {
            val zoom=fix.copy(coordinate=Coordinate(latitude,0.0),accuracyMeters=accuracy).maximumZoom
            assertTrue(zoom.isFinite() && zoom in 3.0..16.0)
        }
    }
}
