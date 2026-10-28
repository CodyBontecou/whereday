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
import java.util.concurrent.Executors
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

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
        val code=addresses.firstOrNull()?.countryCode?.uppercase(Locale.ROOT) ?: return null
        if(code !in Locale.getISOCountries()) return null
        val zone=timeZones.query(coordinate) ?: return null
        return CountryLocation(code,zone)
    }
    private suspend fun addresses(coordinate: Coordinate): List<Address> = suspendCancellableCoroutine { continuation ->
        fun complete(result: List<Address>) { if(continuation.isActive) continuation.resume(result) }
        fun fail() { if(continuation.isActive) continuation.resumeWithException(IOException("Geocoder unavailable")) }
        if(Build.VERSION.SDK_INT>=33) {
            try { geocoder.getFromLocation(coordinate.latitude,coordinate.longitude,1,object: Geocoder.GeocodeListener {
                override fun onGeocode(addresses: MutableList<Address>) { complete(addresses) }
                override fun onError(errorMessage: String?) { fail() }
            }) } catch(e:Exception) { fail() }
        } else {
            // Binder's legacy synchronous call is isolated from coroutine cancellation and UI threads.
            legacyExecutor.execute {
                try {
                    @Suppress("DEPRECATION")
                    val result=geocoder.getFromLocation(coordinate.latitude,coordinate.longitude,1).orEmpty()
                    complete(result)
                } catch(e:Exception) { fail() }
            }
        }
    }
    companion object {
        const val VERSION="timeshape-2026b.29-polygons-v1-system-country-v1"
        private val legacyExecutor by lazy { Executors.newSingleThreadExecutor { runnable -> Thread(runnable,"country-geocoder").apply { isDaemon=true } } }
    }
}

/** Reads signed, bundled boundary assets; no network or location permission is involved. */
fun geographicTimeZones(context: Context)=GeographicTimeZoneIndex(open={ path -> context.applicationContext.assets.open("timezones/$path") })
