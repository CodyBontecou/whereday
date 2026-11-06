package com.bontecou.whereday

import com.bontecou.whereday.domain.*
import org.junit.Test
import org.junit.Assert.*
import java.time.*
import java.util.Locale

class MapPointInspectionTest {
    @Test fun targetsUseNearestCenterStableTiesAnd48dpBounds() {
        val centers=listOf(0.0 to 0.0,20.0 to 0.0)
        assertEquals(1,pointHitIndex(14.0,0.0,centers));assertEquals(0,pointHitIndex(10.0,0.0,centers))
        assertEquals(0,pointHitIndex(-24.0,24.0,centers));assertNull(pointHitIndex(-24.001,0.0,centers))
        assertNull(pointHitIndex(Double.NaN,0.0,centers))
        assertEquals(1,pointHitIndex(0.0,0.0,listOf(Double.POSITIVE_INFINITY to 0.0,0.0 to 0.0)))
        assertNull(pointHitIndex(0.0,0.0,emptyList()))
    }
    @Test fun distanceThresholdsUnitsAndSignedRoundingFollowThePointFields() {
        assertEquals("9 m",pointDistance(8.5,true,Locale.US));assertEquals("-9 m",pointDistance(-8.5,true,Locale.US))
        assertEquals("999 m",pointDistance(999.0,true,Locale.US));assertEquals("1 km",pointDistance(1000.0,true,Locale.US))
        assertEquals("33 ft",pointDistance(10.0,false,Locale.US));assertEquals("0.1 mi",pointDistance(160.9344,false,Locale.US))
        assertEquals("1,5 km",pointDistance(1500.0,true,Locale.forLanguageTag("es")))
    }
    @Test fun timestampRetainsSecondsAndPhoneZoneInsteadOfPointGeographicZone() {
        val point=TrackPoint(coordinate=Coordinate(0.0,0.0),time=Instant.parse("2026-11-01T06:30:21Z"),accuracy=5.0,timeZone="Asia/Tokyo")
        val ny=pointTimestamp(point,Locale.US,ZoneId.of("America/New_York"))
        assertTrue(ny,ny.contains("1:30:21"));assertNotEquals(ny,pointTimestamp(point,Locale.US,ZoneOffset.UTC))
    }
}
