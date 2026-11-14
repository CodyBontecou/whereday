package com.bontecou.whereday.platform

import android.location.Address
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bontecou.whereday.domain.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.util.Locale

@RunWith(AndroidJUnit4::class)
class SystemPlaceLookupTest {
    @Test fun nativeAddressesPreserveCoordinatesAndLocalizedMetadataWithoutHistoryAccess() {
        val address=Address(Locale.FRENCH).apply {
            latitude=48.8584;longitude=2.2945;featureName="  Tour Eiffel  "
            setAddressLine(0,"  5 Avenue Anatole France  ");setAddressLine(1,"75007 Paris");setAddressLine(2,"75007 Paris")
        }
        val result=placeLookupResults(listOf(SystemPlaceLookup.record(address))).single()
        assertEquals("Tour Eiffel",result.name);assertEquals("5 Avenue Anatole France, 75007 Paris",result.address)
        assertEquals(Coordinate(48.8584,2.2945),result.coordinate)
    }
    @Test fun missingNativeCoordinatesAndBlankMetadataNeverBecomeSearchResults() {
        val noCoordinate=Address(Locale.ROOT).apply {featureName="Incomplete"}
        val blank=Address(Locale.ROOT).apply {latitude=0.0;longitude=0.0;featureName="  "}
        assertTrue(placeLookupResults(listOf(SystemPlaceLookup.record(noCoordinate),SystemPlaceLookup.record(blank))).isEmpty())
    }
    @Test fun addressFallbackAndBoundsHandleSparseProviderRecords() {
        val address=Address(Locale.ROOT).apply {latitude=-90.0;longitude=180.0;setAddressLine(0," South Pole ")}
        val result=placeLookupResults(listOf(SystemPlaceLookup.record(address))).single()
        assertEquals("South Pole",result.name);assertEquals(Coordinate(-90.0,180.0),result.coordinate)
        address.clearLongitude()
        assertTrue(placeLookupResults(listOf(SystemPlaceLookup.record(address))).isEmpty())
    }
}
