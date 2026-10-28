package com.bontecou.whereday.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Duration

class RouteReplayTest {
    @Test fun emptyAndSinglePointIndicesAndInvalidProgressMatchSwift() {
        assertNull(RouteReplay.index(.5,0));assertNull(RouteReplay.index(.5,-1))
        for(value in listOf(-1.0,0.0,.5,1.0,Double.NaN,Double.POSITIVE_INFINITY)) assertEquals(0L,RouteReplay.index(value,1))
        assertEquals(0.0,RouteReplay.clampedProgress(Double.NEGATIVE_INFINITY),0.0)
    }
    @Test fun halfStepsRoundAwayFromZeroAndEndpointsClamp() {
        assertEquals(1L,RouteReplay.index(.5,2));assertEquals(2L,RouteReplay.index(.5,4))
        assertEquals(0L,RouteReplay.index(-1.0,9));assertEquals(8L,RouteReplay.index(9.0,9))
        assertEquals(0L,RouteReplay.index(Double.NaN,9));assertEquals(4L,RouteReplay.index(.5,9))
    }
    @Test fun progressAndPlaybackKeepLongRoutesBoundedToIosTickPacing() {
        for(count in listOf(2L,180L,181L,182L,2500L,1_000_000L)) {
            var index=0L;var ticks=0
            while(index<count-1) {index=RouteReplay.next(index,count);ticks++;assertTrue(ticks<=180)}
            assertEquals(count-1,index);assertEquals(1.0,RouteReplay.progress(index,count),0.0)
            assertEquals(index,RouteReplay.index(RouteReplay.progress(index,count),count))
        }
        assertEquals(1L,RouteReplay.stepSize(181));assertEquals(2L,RouteReplay.stepSize(182))
        assertEquals(0.0,RouteReplay.progress(-1,9),0.0);assertEquals(1.0,RouteReplay.progress(999,9),0.0)
    }
    @Test fun elapsedFormattingMatchesRoundedIosHoursAndMinutes() {
        assertEquals("0:00",RouteReplay.formattedDuration(Duration.ofSeconds(-1)))
        assertEquals("0:00",RouteReplay.formattedDuration(Duration.ofMillis(499)))
        assertEquals("0:01",RouteReplay.formattedDuration(Duration.ofMillis(500)))
        assertEquals("1:00",RouteReplay.formattedDuration(Duration.ofMillis(59_500)))
        assertEquals("1:00:00",RouteReplay.formattedDuration(Duration.ofSeconds(3600)))
        assertEquals("25:01:02",RouteReplay.formattedDuration(Duration.ofSeconds(90062)))
    }
}
