package com.bontecou.whereday.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingPresetTest {
    @Test fun presetsMatchIosValues() {
        assertEquals(listOf(100 to 0,25 to 0,5 to 2),RecordingPreset.entries.map {it.distanceMeters to it.stopAfterHours})
        RecordingPreset.entries.forEach {assertEquals(RouteSamplingMode.distance,it.samplingMode)}
        assertEquals(RecordingPreset.routes,RecordingPreset.DEFAULT)
    }

    @Test fun eachPresetMatchesItsOwnSettings() {
        RecordingPreset.entries.forEach {
            assertEquals(it,RecordingPreset.matching(it.samplingMode,it.distanceMeters,it.stopAfterHours))
        }
    }

    @Test fun customDistanceIsNotRoundedToANearbyPreset() {
        // iOS once bucketed by distance alone (>=100 places, >=25 routes); ba63da5b requires an exact match.
        assertNull(RecordingPreset.matching(RouteSamplingMode.distance,200,0))
        assertNull(RecordingPreset.matching(RouteSamplingMode.distance,50,0))
        assertNull(RecordingPreset.matching(RouteSamplingMode.distance,10,2))
    }

    @Test fun customAutoStopIsCustom() {
        assertNull(RecordingPreset.matching(RouteSamplingMode.distance,100,4))
        assertNull(RecordingPreset.matching(RouteSamplingMode.distance,25,2))
        assertNull(RecordingPreset.matching(RouteSamplingMode.distance,5,0))
    }

    @Test fun timeBasedSamplingIsCustom() {
        RecordingPreset.entries.forEach {
            assertNull(RecordingPreset.matching(RouteSamplingMode.time,it.distanceMeters,it.stopAfterHours))
            assertNull(RecordingPreset.matching(RouteSamplingMode.distanceAndTime,it.distanceMeters,it.stopAfterHours))
        }
    }

    @Test fun onboardingPreselectsRoutesOnlyForUntouchedDefaults() {
        assertEquals(RecordingPreset.routes,RecordingPreset.onboardingChoice(RouteSamplingMode.distance,5,0,untouchedDefaults=true))
        assertNull(RecordingPreset.onboardingChoice(RouteSamplingMode.distance,5,0,untouchedDefaults=false))
        assertNull(RecordingPreset.onboardingChoice(RouteSamplingMode.time,25,0,untouchedDefaults=false))
        assertEquals(RecordingPreset.everyStep,RecordingPreset.onboardingChoice(RouteSamplingMode.distance,5,2,untouchedDefaults=false))
        assertEquals(RecordingPreset.places,RecordingPreset.onboardingChoice(RouteSamplingMode.distance,100,0,untouchedDefaults=true))
    }

    @Test fun keysRoundTrip() {
        RecordingPreset.entries.forEach {assertEquals(it,RecordingPreset.fromKey(it.key))}
        assertNull(RecordingPreset.fromKey(null))
        assertNull(RecordingPreset.fromKey("custom"))
    }
}
