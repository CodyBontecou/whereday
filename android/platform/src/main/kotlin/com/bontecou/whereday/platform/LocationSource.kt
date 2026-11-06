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
    suspend fun start(sampling:RouteSamplingPolicy,onPoints:(List<TrackPoint>)->Unit,onError:(Exception)->Unit)
    /** Distance-based recording, as before time sampling existed. */
    suspend fun start(distanceMeters:Int,onPoints:(List<TrackPoint>)->Unit,onError:(Exception)->Unit)=
        start(RouteSamplingPolicy(RouteSamplingMode.distance,distanceMeters),onPoints,onError)
    fun stop()
}

enum class LocationSampling { recording, visitsOnly }

class FusedLocationSource internal constructor(private val allowed:()->Boolean,
    private val register:(LocationRequest,LocationCallback)->Task<Void>,private val remove:(LocationCallback)->Unit,
    private val sampling:LocationSampling=LocationSampling.recording):LocationSource {
    constructor(context:Context,sampling:LocationSampling=LocationSampling.recording):this({
        ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED
    },{request,receiver ->LocationServices.getFusedLocationProviderClient(context).requestLocationUpdates(request,receiver,Looper.getMainLooper())},
        {receiver ->LocationServices.getFusedLocationProviderClient(context).removeLocationUpdates(receiver);Unit},sampling)
    @Volatile private var callback:LocationCallback?=null
    override suspend fun start(sampling:RouteSamplingPolicy,onPoints:(List<TrackPoint>)->Unit,onError:(Exception)->Unit) {
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
        // Dwell confirmation needs stationary samples. Route distance filtering would suppress
        // them; Visits only deliberately uses no minimum displacement and no high-accuracy GPS.
        // Time-based route sampling also asks for no minimum displacement (see RouteSamplingPolicy).
        val visits=this.sampling==LocationSampling.visitsOnly
        val route=sampling.locationRequest
        val request=LocationRequest.Builder(if(visits || !route.highAccuracy) Priority.PRIORITY_BALANCED_POWER_ACCURACY else Priority.PRIORITY_HIGH_ACCURACY,
            if(visits) 120_000L else route.intervalMillis)
            .setMinUpdateIntervalMillis(if(visits) 60_000L else route.minUpdateIntervalMillis)
            .setMinUpdateDistanceMeters(if(visits) 0f else route.minUpdateDistanceMeters)
            .setMaxUpdateDelayMillis(if(visits) 300_000L else route.maxUpdateDelayMillis).setWaitForAccurateLocation(false).build()
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
