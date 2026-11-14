package com.bontecou.whereday.platform

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import com.bontecou.whereday.geography.GeographicTimeZoneIndex
import java.io.IOException
import java.util.Locale

/** Geographic time-zone boundaries are local; only country lookup uses the system backend. */
class GeographicCountryLookup(context: Context): CountryLookup {
    private val geocoder=Geocoder(context.applicationContext,Locale.ROOT)
    private val timeZones=geographicTimeZones(context)
    override suspend fun prepare() { timeZones.prepare() }
    override suspend fun finish() { timeZones.release() }
    override suspend fun lookup(coordinate: Coordinate): CountryLocation? {
        if(!Geocoder.isPresent()) throw IOException("No geocoder is available")
        val addresses=withTimeout(15_000) { addresses(coordinate) }
        currentCoroutineContext().ensureActive()
        val code=CountryRegions.normalize(addresses.firstOrNull()?.countryCode) ?: return null
        val zone=timeZones.query(coordinate) ?: return null
        return CountryLocation(code,zone)
    }
    private suspend fun addresses(coordinate:Coordinate):List<Address> = if(Build.VERSION.SDK_INT>=33) {
        GeocoderRequests.shared.callback {complete,fail->
            geocoder.getFromLocation(coordinate.latitude,coordinate.longitude,1,object:Geocoder.GeocodeListener {
                override fun onGeocode(addresses:MutableList<Address>) {complete(addresses)}
                override fun onError(errorMessage:String?) {fail()}
            })
        }
    } else GeocoderRequests.shared.blocking {
        @Suppress("DEPRECATION")
        geocoder.getFromLocation(coordinate.latitude,coordinate.longitude,1).orEmpty()
    }
    companion object {
        const val VERSION="timeshape-2026b.29-polygons-v1-system-country-v1"
    }
}

/** Reads signed, bundled boundary assets; no network or location permission is involved. */
fun geographicTimeZones(context: Context)=GeographicTimeZoneIndex(open={ path -> context.applicationContext.assets.open("timezones/$path") })
