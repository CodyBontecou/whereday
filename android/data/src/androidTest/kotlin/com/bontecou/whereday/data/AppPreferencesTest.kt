package com.bontecou.whereday.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppPreferencesTest {
    @Test fun concurrentUnrelatedChangesCannotReenableNetworkGeocoding()=runBlocking {
        val preferences=AppPreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val original=preferences.settings.first()
        try {
            preferences.update { it.copy(networkGeocoding=true,metric=true) }
            coroutineScope {
                launch(Dispatchers.Default) { preferences.update { it.copy(networkGeocoding=false) } }
                repeat(25) { index -> launch(Dispatchers.Default) { preferences.update { it.copy(metric=false,distanceFilter=if(index%2==0) 25 else 50) } } }
            }
            val saved=preferences.settings.first()
            assertFalse(saved.networkGeocoding); assertFalse(saved.metric)
            preferences.update { it.copy(appearance="dark") }
            assertFalse(preferences.settings.first().networkGeocoding)
        } finally { preferences.update { original } }
    }

    @Test fun eveningRecapDefaultsOffUntilSetAndPersists()=runBlocking {
        val preferences=AppPreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val original=preferences.settings.first()
        try {
            preferences.update {it.copy(recapFrequency=null)}
            val fresh=preferences.settings.first()
            assertNull(fresh.recapFrequency);assertEquals(com.bontecou.whereday.domain.RecapSettings(),fresh.recap)
            val chosen=com.bontecou.whereday.domain.RecapSettings(com.bontecou.whereday.domain.RecapFrequency.weekly,22*60+30,skipQuietDays=false,placeNames=true)
            preferences.update {it.withRecap(chosen)}
            val saved=preferences.settings.first()
            assertEquals("weekly",saved.recapFrequency);assertEquals(chosen,saved.recap)
            try {preferences.update {it.copy(recapFrequency="nightly")};fail("Unknown frequency saved")} catch(_:IllegalArgumentException) { }
            try {preferences.update {it.copy(recapMinute=24*60)};fail("Out-of-range time saved")} catch(_:IllegalArgumentException) { }
            assertEquals(chosen,preferences.settings.first().recap)
        } finally {preferences.update {original}}
    }

    @Test fun routeSamplingDefaultsToDistanceAndPersists()=runBlocking {
        val preferences=AppPreferences(InstrumentationRegistry.getInstrumentation().targetContext)
        val original=preferences.settings.first()
        try {
            assertEquals(com.bontecou.whereday.domain.RouteSamplingMode.distance.key,AppSettings().routeSamplingMode)
            assertEquals(120,AppSettings().routeSampleIntervalSeconds)
            preferences.update {it.copy(routeSamplingMode="distance_and_time",routeSampleIntervalSeconds=300,distanceFilter=25)}
            val policy=preferences.settings.first().routeSampling
            assertEquals(com.bontecou.whereday.domain.RouteSamplingPolicy(com.bontecou.whereday.domain.RouteSamplingMode.distanceAndTime,25,300),policy)
            try {preferences.update {it.copy(routeSampleIntervalSeconds=45)};fail("Unsupported interval saved")} catch(_:IllegalArgumentException) { }
            try {preferences.update {it.copy(routeSamplingMode="hourly")};fail("Unknown mode saved")} catch(_:IllegalArgumentException) { }
            assertEquals(300,preferences.settings.first().routeSampleIntervalSeconds)
        } finally { preferences.update { original } }
    }
}
