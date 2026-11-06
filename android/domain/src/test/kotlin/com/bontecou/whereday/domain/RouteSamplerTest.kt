package com.bontecou.whereday.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class RouteSamplerTest {
    private val start=Instant.parse("2026-11-06T12:00:00Z")
    private val origin=Coordinate(37.0,-122.0)
    /** ~1.11 m of latitude per 0.00001°. */
    private fun north(meters:Double)=Coordinate(origin.latitude+meters/111_195.0,origin.longitude)
    private fun fix(seconds:Double,meters:Double=0.0,accuracy:Double=5.0)=
        TrackPoint(id="fix-$seconds-$meters",coordinate=north(meters),time=start.plusMillis((seconds*1000).toLong()),accuracy=accuracy)
    private fun sampler(mode:RouteSamplingMode,interval:Int=120,distance:Int=25)=RouteSampler(RouteSamplingPolicy(mode,distance,interval))
    private fun times(points:List<TrackPoint>)=points.map {(it.time.toEpochMilli()-start.toEpochMilli())/1000.0}

    // Policy

    @Test fun unknownStoredModeFallsBackToDistance() {
        assertEquals(RouteSamplingMode.distance,RouteSamplingMode.fromKey(null))
        assertEquals(RouteSamplingMode.distance,RouteSamplingMode.fromKey("hourly"))
        RouteSamplingMode.entries.forEach {assertEquals(it,RouteSamplingMode.fromKey(it.key))}
    }

    @Test fun distanceModeKeepsTheOriginalProviderRequest() {
        val request=RouteSamplingPolicy(RouteSamplingMode.distance,25,120).locationRequest
        assertEquals(RouteLocationRequest(true,5_000L,1_000L,25f,10_000L),request)
    }

    @Test fun timeModesAskForOneFixPerIntervalWithNoMinimumDistance() {
        for(mode in listOf(RouteSamplingMode.time,RouteSamplingMode.distanceAndTime)) {
            val request=RouteSamplingPolicy(mode,50,300).locationRequest
            assertEquals(300_000L,request.intervalMillis)
            assertEquals("No faster than the interval",300_000L,request.minUpdateIntervalMillis)
            assertEquals("Parked fixes must still reach visit detection",0f,request.minUpdateDistanceMeters)
            assertTrue(request.highAccuracy)
        }
    }

    @Test fun intervalOptionsMatchIos() {
        assertEquals(listOf(10,30,60,120,300),RouteSamplingPolicy.INTERVAL_OPTIONS)
        assertEquals(120,RouteSamplingPolicy().intervalSeconds)
    }

    @Test(expected=IllegalArgumentException::class) fun zeroIntervalIsRejected() {RouteSamplingPolicy(RouteSamplingMode.time,5,0)}

    // Distance mode

    @Test fun distanceModePassesEveryFixThrough() {
        val sampler=sampler(RouteSamplingMode.distance)
        val fixes=listOf(fix(0.0),fix(1.0),fix(2.0,accuracy=500.0))
        assertEquals("The provider's distance filter already chose these; quality is checked on ingest",fixes,sampler.process(fixes))
    }

    // Time mode

    @Test fun timeModeKeepsTheFirstFixThenOnePerInterval() {
        val sampler=sampler(RouteSamplingMode.time,interval=60)
        val kept=sampler.process((0..180 step 10).map {fix(it.toDouble())})
        assertEquals(listOf(0.0,60.0,120.0,180.0),times(kept))
    }

    @Test fun aFasterStreamDoesNotDriftShorterThanTheInterval() {
        // Another app can make the provider deliver every second. The slack may take a fix up to 10% early,
        // but the schedule stays anchored, so 10 s stays 10 s on average.
        val sampler=sampler(RouteSamplingMode.time,interval=10)
        val kept=sampler.process((0..60).map {fix(it.toDouble())})
        assertEquals(listOf(0.0,9.0,19.0,29.0,39.0,49.0,59.0),times(kept))
    }

    @Test fun timeModeKeepsFixesWhileStandingStill() {
        val sampler=sampler(RouteSamplingMode.time,interval=60)
        assertEquals(listOf(0.0,60.0,120.0),times(sampler.process(listOf(fix(0.0),fix(60.0),fix(120.0)))))
    }

    @Test fun slightlyEarlyFixesStillCountSoTheGapDoesNotDouble() {
        val sampler=sampler(RouteSamplingMode.time,interval=120)
        val kept=sampler.process(listOf(fix(0.0),fix(117.0),fix(236.0)))
        assertEquals(listOf(0.0,117.0,236.0),times(kept))
    }

    @Test fun fixesWellBeforeTheIntervalAreDropped() {
        val sampler=sampler(RouteSamplingMode.time,interval=120)
        assertEquals(listOf(0.0,120.0),times(sampler.process(listOf(fix(0.0),fix(40.0),fix(80.0),fix(120.0)))))
    }

    @Test fun shortIntervalsUseHalfASecondOfSlack() {
        val sampler=sampler(RouteSamplingMode.time,interval=1)
        assertEquals(listOf(0.0,0.6,1.6),times(sampler.process(listOf(fix(0.0),fix(0.4),fix(0.6),fix(1.0),fix(1.6)))))
    }

    @Test fun unusableAccuracyIsNeverKeptAndDoesNotStartTheClock() {
        val sampler=sampler(RouteSamplingMode.time,interval=60)
        val kept=sampler.process(listOf(fix(0.0,accuracy=250.0),fix(5.0,accuracy=Double.POSITIVE_INFINITY),fix(10.0),fix(40.0)))
        assertEquals(listOf(10.0),times(kept))
        assertEquals(start.plusSeconds(70),sampler.nextDue)
    }

    @Test fun batchesAreSampledInTimeOrder() {
        val sampler=sampler(RouteSamplingMode.time,interval=30)
        assertEquals(listOf(0.0,30.0,60.0),times(sampler.process(listOf(fix(60.0),fix(0.0),fix(45.0),fix(30.0)))))
    }

    @Test fun repeatedOrOlderFixesAreIgnored() {
        val sampler=sampler(RouteSamplingMode.time,interval=10)
        sampler.process(listOf(fix(0.0),fix(10.0)))
        assertTrue(sampler.process(listOf(fix(10.0),fix(5.0))).isEmpty())
    }

    @Test fun samplingCarriesAcrossDeliveries() {
        val sampler=sampler(RouteSamplingMode.time,interval=30)
        assertEquals(listOf(0.0),times(sampler.process(listOf(fix(0.0),fix(10.0)))))
        assertTrue(sampler.process(listOf(fix(20.0))).isEmpty())
        assertEquals(listOf(30.0),times(sampler.process(listOf(fix(30.0)))))
    }

    // Distance + time

    @Test fun distanceAndTimeSkipsDueFixesUntilYouHaveMoved() {
        val sampler=sampler(RouteSamplingMode.distanceAndTime,interval=60,distance=25)
        val kept=sampler.process(listOf(fix(0.0),fix(60.0,meters=10.0),fix(120.0,meters=20.0),fix(180.0,meters=30.0)))
        assertEquals(listOf(0.0,180.0),times(kept))
    }

    @Test fun distanceAndTimeStillWaitsForTheIntervalWhenMovingFast() {
        val sampler=sampler(RouteSamplingMode.distanceAndTime,interval=60,distance=25)
        val kept=sampler.process(listOf(fix(0.0),fix(10.0,meters=200.0),fix(30.0,meters=600.0),fix(60.0,meters=1200.0)))
        assertEquals(listOf(0.0,60.0),times(kept))
    }

    @Test fun distanceIsMeasuredFromTheLastKeptPoint() {
        val sampler=sampler(RouteSamplingMode.distanceAndTime,interval=10,distance=25)
        // Creeping 15 m per interval: no single step is far enough, but the total from the last kept point is.
        val kept=sampler.process(listOf(fix(0.0),fix(10.0,15.0),fix(20.0,30.0),fix(30.0,45.0),fix(40.0,60.0)))
        assertEquals(listOf(0.0,20.0,40.0),times(kept))
    }

    // Changing settings

    @Test fun shorteningTheIntervalBringsTheNextPointForward() {
        val sampler=sampler(RouteSamplingMode.time,interval=300)
        sampler.process(listOf(fix(0.0)))
        sampler.policy=RouteSamplingPolicy(RouteSamplingMode.time,25,30)
        assertEquals(listOf(30.0),times(sampler.process(listOf(fix(30.0)))))
    }

    @Test fun lengtheningTheIntervalPushesTheNextPointBack() {
        val sampler=sampler(RouteSamplingMode.time,interval=30)
        sampler.process(listOf(fix(0.0)))
        sampler.policy=RouteSamplingPolicy(RouteSamplingMode.time,25,120)
        assertTrue(sampler.process(listOf(fix(30.0),fix(60.0))).isEmpty())
        assertEquals(listOf(120.0),times(sampler.process(listOf(fix(120.0)))))
    }

    @Test fun resetStartsOver() {
        val sampler=sampler(RouteSamplingMode.time,interval=300)
        sampler.process(listOf(fix(0.0)))
        sampler.reset()
        assertNull(sampler.lastKept)
        assertEquals(listOf(10.0),times(sampler.process(listOf(fix(10.0)))))
    }
}
