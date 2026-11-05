package com.bontecou.whereday.platform

import android.content.Context
import android.location.Address
import android.location.Geocoder
import android.os.Build
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** System address search is best-effort; this does not pretend to be a nearby-POI catalog. */
class SystemPlaceLookup(context:Context):PlaceLookupProvider {
    private val context=context.applicationContext
    override suspend fun search(query:String,language:String)=addresses(language,query,null).map(::record)
    override suspend fun reverse(coordinate:Coordinate,language:String)=addresses(language,null,coordinate).map(::record)
    private suspend fun addresses(language:String,query:String?,coordinate:Coordinate?):List<Address> = suspendCancellableCoroutine {continuation->
        if(!Geocoder.isPresent()) {continuation.resumeWithException(IOException("This device has no geocoding provider."));return@suspendCancellableCoroutine}
        // Geocoder has no request-cancellation API. Bound outstanding native callbacks even if a provider never replies.
        if(outstanding.incrementAndGet()>4) {outstanding.decrementAndGet();continuation.resumeWithException(IOException("The geocoding provider is busy. Try again later."));return@suspendCancellableCoroutine}
        val finished=AtomicBoolean(false)
        fun complete(values:List<Address>?,failure:Boolean=false) {
            if(!finished.compareAndSet(false,true)) return
            outstanding.decrementAndGet()
            if(failure) continuation.resumeWithException(IOException("The geocoding service is unavailable."))
            else continuation.resume(values.orEmpty().take(10))
        }
        if(!continuation.isActive) {complete(null,true);return@suspendCancellableCoroutine}
        val geocoder=Geocoder(context,Locale.forLanguageTag(language))
        if(Build.VERSION.SDK_INT>=33) {
            val listener=object:Geocoder.GeocodeListener {
                override fun onGeocode(addresses:MutableList<Address>) {complete(addresses)}
                override fun onError(errorMessage:String?) {complete(null,true)}
            }
            try {if(coordinate!=null) geocoder.getFromLocation(coordinate.latitude,coordinate.longitude,1,listener)
                else geocoder.getFromLocationName(requireNotNull(query),10,listener)
            } catch(_:Exception) {complete(null,true)}
        } else {
            // One daemon worker and one queued call: a stuck legacy Binder never creates unbounded workers/queueing.
            val task=java.util.concurrent.FutureTask {
                try {
                    @Suppress("DEPRECATION")
                    val result=if(coordinate!=null) geocoder.getFromLocation(coordinate.latitude,coordinate.longitude,1)
                        else geocoder.getFromLocationName(requireNotNull(query),10)
                    complete(result)
                } catch(_:Exception) {complete(null,true)}
            }
            continuation.invokeOnCancellation {
                task.cancel(false);legacy.remove(task);complete(null,true)
                // A running Binder can continue; the fixed executor bounds it without creating more workers.
            }
            try {legacy.execute(task)} catch(_:Exception) {complete(null,true)}
        }
    }
    companion object {
        private val outstanding=AtomicInteger(0)
        private val legacy=ThreadPoolExecutor(1,1,0,TimeUnit.MILLISECONDS,ArrayBlockingQueue(1),{r->Thread(r,"place-geocoder").apply {isDaemon=true}},ThreadPoolExecutor.AbortPolicy())
        internal fun record(address:Address):PlaceLookupRecord {
            val line=(0..address.maxAddressLineIndex.coerceAtMost(8)).mapNotNull {address.getAddressLine(it)?.trim()?.takeIf(String::isNotEmpty)}.distinct().joinToString(", ").ifBlank {null}
            val name=address.featureName?.trim()?.takeIf(String::isNotEmpty) ?: line
            return PlaceLookupRecord(name,line,if(address.hasLatitude()) address.latitude else null,if(address.hasLongitude()) address.longitude else null)
        }
    }
}
