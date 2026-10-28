package com.bontecou.whereday.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import androidx.core.content.ContextCompat
import com.bontecou.whereday.domain.*
import com.google.android.gms.location.*
import com.google.android.gms.tasks.CancellationTokenSource
import com.google.android.gms.tasks.CancellationToken
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** One foreground request, separate from the recording subscription. No geocoding or journal writes. */
class FusedCurrentVisitLocationSource internal constructor(
    private val permitted:()->Boolean,
    private val request:(CurrentLocationRequest,CancellationToken)->Task<Location>
):CurrentVisitLocationSource {
    constructor(context:Context):this({
        ContextCompat.checkSelfPermission(context.applicationContext,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(context.applicationContext,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
    },{request,token->LocationServices.getFusedLocationProviderClient(context.applicationContext).getCurrentLocation(request,token)})
    override suspend fun locate():CurrentVisitLocation?=withTimeout(35_000) {
        if(!permitted())
            throw SecurityException("Allow location access to find your current place.")
        suspendCancellableCoroutine { continuation->
            val cancellation=CancellationTokenSource()
            continuation.invokeOnCancellation {cancellation.cancel()}
            try {
                val configuration=CurrentLocationRequest.Builder().setPriority(Priority.PRIORITY_HIGH_ACCURACY)
                    .setMaxUpdateAgeMillis(0).setDurationMillis(30_000).build()
                request(configuration,cancellation.token)
                    .addOnSuccessListener {location->if(continuation.isActive) continuation.resume(location?.let(::currentVisitLocation))}
                    .addOnFailureListener {error->if(continuation.isActive) continuation.resumeWithException(error)}
                    .addOnCanceledListener {continuation.cancel()}
            } catch(error:Exception) {if(continuation.isActive) continuation.resumeWithException(error)}
        }
    }
}

internal fun currentVisitLocation(location:Location):CurrentVisitLocation?=runCatching {
    if(!location.hasAccuracy()) return null
    CurrentVisitLocation(Coordinate(location.latitude,location.longitude),location.accuracy.toDouble(),location.elapsedRealtimeNanos/1_000_000)
}.getOrNull()
