package com.bontecou.whereday.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.bontecou.whereday.domain.*
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

interface LocationSource {
    /** Returns only after the provider accepts registration. Cancellation removes even late registrations. */
    suspend fun start(distanceMeters:Int,onPoints:(List<TrackPoint>)->Unit,onError:(Exception)->Unit)
    fun stop()
}

class FusedLocationSource internal constructor(private val allowed:()->Boolean,
    private val register:(LocationRequest,LocationCallback)->Task<Void>,private val remove:(LocationCallback)->Unit):LocationSource {
    constructor(context:Context):this({
        ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
    },{request,receiver ->LocationServices.getFusedLocationProviderClient(context).requestLocationUpdates(request,receiver,Looper.getMainLooper())},
        {receiver ->LocationServices.getFusedLocationProviderClient(context).removeLocationUpdates(receiver);Unit})
    @Volatile private var callback:LocationCallback?=null
    override suspend fun start(distanceMeters:Int,onPoints:(List<TrackPoint>)->Unit,onError:(Exception)->Unit) {
        stop()
        if(!allowed()) throw SecurityException("Location permission is required")
        val receiver=object:LocationCallback() {
            override fun onLocationResult(result:LocationResult) {
                if(callback!==this) return
                onPoints(result.locations.mapNotNull {location ->runCatching {
                    TrackPoint(coordinate=Coordinate(location.latitude,location.longitude),time=Instant.ofEpochMilli(location.time),
                        accuracy=if(location.hasAccuracy()) location.accuracy.toDouble() else Double.POSITIVE_INFINITY,
                        altitude=location.altitude.takeIf {location.hasAltitude()},speed=location.speed.toDouble().takeIf {location.hasSpeed() && it>=0})
                }.getOrNull()})
            }
        }
        callback=receiver
        val request=LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY,5_000L)
            .setMinUpdateIntervalMillis(1_000L).setMinUpdateDistanceMeters(distanceMeters.toFloat())
            .setMaxUpdateDelayMillis(10_000L).setWaitForAccurateLocation(false).build()
        withTimeout(10_000) {suspendCancellableCoroutine {continuation ->
            continuation.invokeOnCancellation {
                if(callback===receiver) callback=null
                runCatching {remove(receiver)}
            }
            try {
                register(request,receiver).addOnSuccessListener {
                    if(callback!==receiver || !continuation.isActive) {
                        runCatching {remove(receiver)};continuation.cancel()
                    } else continuation.resume(Unit)
                }.addOnFailureListener {error ->
                    if(callback===receiver) callback=null
                    runCatching {remove(receiver)}
                    if(continuation.isActive) continuation.resumeWithException(error)
                }.addOnCanceledListener {continuation.cancel()}
            } catch(error:Exception) {
                if(callback===receiver) callback=null
                runCatching {remove(receiver)}
                if(continuation.isActive) continuation.resumeWithException(error)
            }
        }}
    }
    override fun stop() {val old=callback;callback=null;old?.let {runCatching {remove(it)}}}
}
