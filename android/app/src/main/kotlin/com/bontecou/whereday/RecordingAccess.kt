package com.bontecou.whereday

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

internal enum class LocationPrecision {none,approximate,precise}
internal enum class PermissionAccessKind {location,precise,notifications}
internal enum class PermissionAccessFailure {request,settings}
/** [locationDeclined]: this run's own request was just refused. [locationRefused] and [notificationsRefused]: Whereday asked and
 *  access is still off, on this run or an earlier one, including after "Don't allow" twice, once Android stops asking. */
internal data class RecordingAccessState(val location:LocationPrecision,val notifications:Boolean,val locationDeclined:Boolean=false,val failure:PermissionAccessFailure?=null,
    val locationRefused:Boolean=locationDeclined,val notificationsRefused:Boolean=false)

/** Remembers which runtime permissions Whereday has asked for, so a refusal still reads as one after the app restarts. */
internal class PermissionRequestLog(context:Context) {
    private val preferences=context.applicationContext.getSharedPreferences("permission-requests",Context.MODE_PRIVATE)
    fun asked(kind:PermissionAccessKind)=preferences.getBoolean(kind.name,false)
    fun record(kind:PermissionAccessKind) {if(!asked(kind)) preferences.edit().putBoolean(kind.name,true).apply()}
}
internal data class PermissionReview(val id:Long,val generation:String?,val foreground:Long,val kind:PermissionAccessKind,val requestAllowed:Boolean,val start:Boolean=false,val onboarding:Boolean=false)
internal data class PendingPermission(val generation:String?,val foreground:Long,val start:Boolean=false,val precise:Boolean=false) {
    fun current(generation:String?,foreground:Long,blocked:Boolean)=!blocked && this.generation==generation && this.foreground==foreground
}
internal fun recordingAccess(context:Context):RecordingAccessState {
    fun granted(permission:String)=ContextCompat.checkSelfPermission(context,permission)==PackageManager.PERMISSION_GRANTED
    val location=when {granted(Manifest.permission.ACCESS_FINE_LOCATION)->LocationPrecision.precise;granted(Manifest.permission.ACCESS_COARSE_LOCATION)->LocationPrecision.approximate;else->LocationPrecision.none}
    return RecordingAccessState(location,NotificationManagerCompat.from(context).areNotificationsEnabled())
}
