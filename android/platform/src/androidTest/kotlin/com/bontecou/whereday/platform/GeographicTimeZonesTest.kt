package com.bontecou.whereday.platform

import android.os.Debug
import android.os.SystemClock
import android.util.Log
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.Coordinate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class GeographicTimeZonesTest {
    @Test fun systemCountryAndBundledZoneResolvePublicNewYorkFixture() = runBlocking {
        val lookup=GeographicCountryLookup(InstrumentationRegistry.getInstrumentation().targetContext)
        try {
            lookup.prepare()
            val result=lookup.lookup(Coordinate(40.7128,-74.0060))
            assertNotNull("System geocoder did not resolve the public fixture",result)
            assertEquals("US",result!!.countryCode)
            assertEquals("America/New_York",result.timeZone)
        } finally { lookup.finish() }
    }
    @Test fun bundledBoundariesResolveMultipleCountriesAndSameCountryTimeZonesOffline() = runBlocking {
        val timeZones=geographicTimeZones(InstrumentationRegistry.getInstrumentation().targetContext)
        val started=SystemClock.elapsedRealtime()
        timeZones.prepare()
        val elapsed=SystemClock.elapsedRealtime()-started
        val fixtures=listOf(
            Coordinate(40.7128,-74.0060) to "America/New_York",
            Coordinate(33.4484,-112.0740) to "America/Phoenix",
            Coordinate(18.4655,-66.1057) to "America/Puerto_Rico",
            Coordinate(1.3521,103.8198) to "Asia/Singapore",
            Coordinate(-33.8688,151.2093) to "Australia/Sydney",
            Coordinate(55.7558,37.6173) to "Europe/Moscow",
            Coordinate(64.2008,-149.4937) to "America/Anchorage",
            Coordinate(49.8951,-97.1384) to "America/Winnipeg",
        )
        fixtures.forEach { (coordinate,zone) ->
            val start=SystemClock.elapsedRealtime()
            assertEquals(zone,timeZones.query(coordinate))
            assertTrue(timeZones.metrics.cachedGeometryBytes<=4L*1024*1024)
            Log.i("WheredayQa","timezone_fixture=$zone query_ms=${SystemClock.elapsedRealtime()-start} candidates=${timeZones.metrics.candidates} loaded_bytes=${timeZones.metrics.loadedPolygonBytes} cached_geometry_bytes=${timeZones.metrics.cachedGeometryBytes}")
        }
        val memory=Debug.MemoryInfo().also(Debug::getMemoryInfo)
        val runtime=Runtime.getRuntime()
        Log.i("WheredayQa","timezone_init_ms=$elapsed total_pss_kb=${memory.totalPss} java_heap_used_bytes=${runtime.totalMemory()-runtime.freeMemory()} java_heap_max_bytes=${runtime.maxMemory()}")
        timeZones.release()
        Unit
    }
}
