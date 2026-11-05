package com.bontecou.whereday.platform

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Looper
import androidx.core.content.ContextCompat
import com.bontecou.whereday.domain.*
import com.google.android.gms.location.*
import com.google.android.gms.tasks.Task
import kotlinx.coroutines.channels.awaitClose
import kotlinx.coroutines.flow.*

fun interface MapLocationSource {fun updates():Flow<MapLocationFix>}

/** Separate from the recording service. Collection is owned by the visible map lifecycle. */
class FusedMapLocationSource internal constructor(private val allowed:()->Boolean,
    private val register:(LocationRequest,LocationCallback)->Task<Void>,private val remove:(LocationCallback)->Unit):MapLocationSource {
    constructor(context:Context):this({mapLocationAllowed(context.applicationContext)},
        {request,receiver->
            // Permission can be revoked after allowed() and before Play services registers.
            try {LocationServices.getFusedLocationProviderClient(context.applicationContext).requestLocationUpdates(request,receiver,Looper.getMainLooper())}
            catch(error:SecurityException) {com.google.android.gms.tasks.Tasks.forException<Void>(error)}
        },
        {receiver->LocationServices.getFusedLocationProviderClient(context.applicationContext).removeLocationUpdates(receiver);Unit})
    override fun updates():Flow<MapLocationFix> = callbackFlow {
        if(!allowed()) {close(SecurityException("Location access is required."));return@callbackFlow}
        val receiver=object:LocationCallback() {
            override fun onLocationResult(result:LocationResult) {
                if(isClosedForSend) return
                result.locations.mapNotNull {location->runCatching {
                    if(!location.hasAccuracy()) null else MapLocationFix(Coordinate(location.latitude,location.longitude),location.accuracy.toDouble(),location.elapsedRealtimeNanos/1_000_000)
                }.getOrNull()}.maxByOrNull {it.elapsedMillis}?.let {trySend(it)}
            }
        }
        try {
            val request=LocationRequest.Builder(Priority.PRIORITY_BALANCED_POWER_ACCURACY,5_000)
                .setMinUpdateIntervalMillis(2_000).setMinUpdateDistanceMeters(0f).setMaxUpdateDelayMillis(0).build()
            register(request,receiver).addOnSuccessListener {if(isClosedForSend) runCatching {remove(receiver)}}
                .addOnFailureListener {close(it)}.addOnCanceledListener {close()}
        } catch(error:Exception) {close(error)}
        awaitClose {runCatching {remove(receiver)}}
    }.conflate()
}
fun mapLocationAllowed(context:Context)=
    ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
        ContextCompat.checkSelfPermission(context,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED
