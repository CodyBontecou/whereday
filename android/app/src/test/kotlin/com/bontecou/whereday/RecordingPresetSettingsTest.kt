package com.bontecou.whereday

import com.bontecou.whereday.data.AppSettings
import com.bontecou.whereday.domain.RecordingPreset
import com.bontecou.whereday.domain.RouteSamplingMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class RecordingPresetSettingsTest {
    @Test fun applyingWritesThePresetAndLeavesTimeSampling() {
        val tuned=AppSettings(distanceFilter=200,stopAfterHours=8,routeSamplingMode=RouteSamplingMode.time.key,routeSampleIntervalSeconds=30)
        RecordingPreset.entries.forEach {preset->
            val applied=tuned.applying(preset)
            assertEquals(RouteSamplingMode.distance.key,applied.routeSamplingMode)
            assertEquals(preset.distanceMeters,applied.distanceFilter)
            assertEquals(preset.stopAfterHours,applied.stopAfterHours)
            assertEquals(30,applied.routeSampleIntervalSeconds)
            assertEquals(preset,applied.recordingPreset)
        }
    }

    @Test fun tunedSettingsAreCustom() {
        assertNull(AppSettings(distanceFilter=25,stopAfterHours=4).recordingPreset)
        assertNull(AppSettings(distanceFilter=25,routeSamplingMode=RouteSamplingMode.distanceAndTime.key).recordingPreset)
        assertNull(AppSettings().recordingPreset)
    }

    @Test fun onboardingPreselectsRoutesOnFreshInstallAndKeepsCustomOnReplay() {
        assertEquals(RecordingPreset.routes,AppSettings().onboardingPreset)
        assertEquals(RecordingPreset.everyStep,AppSettings(onboarded=false,distanceFilter=5,stopAfterHours=2).onboardingPreset)
        assertNull(AppSettings(distanceFilter=50).onboardingPreset)
        assertNull(AppSettings(routeSamplingMode=RouteSamplingMode.time.key).onboardingPreset)
    }
}
