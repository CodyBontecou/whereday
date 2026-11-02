package com.bontecou.whereday.platform

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.core.content.ContextCompat
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*

class AutomationLocationUnavailable:IllegalStateException("A fresh accurate check-in location is unavailable.")

/** One bounded foreground fix, without permission prompts, recording or geocoding. */
class AutomationVisitLocation internal constructor(
    private val visible:()->Boolean,private val permitted:()->Boolean,
    private val source:CurrentVisitLocationSource,private val elapsed:()->Long=SystemClock::elapsedRealtime
) {
    constructor(activity:Activity):this(
        {!activity.isFinishing && !activity.isDestroyed && activity.hasWindowFocus()},
        {ContextCompat.checkSelfPermission(activity,Manifest.permission.ACCESS_COARSE_LOCATION)==PackageManager.PERMISSION_GRANTED ||
            ContextCompat.checkSelfPermission(activity,Manifest.permission.ACCESS_FINE_LOCATION)==PackageManager.PERMISSION_GRANTED},
        FusedCurrentVisitLocationSource(activity.applicationContext))
    suspend fun locate():Coordinate {
        suspend fun eligible()=withContext(Dispatchers.Main.immediate) {
            if(!visible()) throw RecordingNeedsForeground()
            if(!permitted()) throw RecordingNeedsLocation()
        }
        eligible()
        val fix=try {withTimeoutOrNull(12_000) {source.locate()} ?: throw AutomationLocationUnavailable()}
        catch(error:CancellationException) {throw error}
        catch(_:SecurityException) {throw RecordingNeedsLocation()}
        catch(_:Exception) {throw AutomationLocationUnavailable()}
        eligible()
        val now=elapsed()
        if(!fix.isUsable(now) || now-fix.elapsedMillis>30_000) throw AutomationLocationUnavailable()
        return fix.coordinate
    }
}
