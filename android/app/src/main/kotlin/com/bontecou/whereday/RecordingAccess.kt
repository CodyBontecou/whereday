package com.bontecou.whereday

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

internal enum class LocationPrecision {none,approximate,precise}
internal enum class PermissionAccessKind {location,precise,notifications}
internal enum class PermissionAccessFailure {request,settings}
internal data class RecordingAccessState(val location:LocationPrecision,val notifications:Boolean,val locationDeclined:Boolean=false,val failure:PermissionAccessFailure?=null)
internal data class PermissionReview(val id:Long,val generation:String?,val foreground:Long,val kind:PermissionAccessKind,val requestAllowed:Boolean,val start:Boolean=false,val onboarding:Boolean=false)
internal data class PendingPermission(val generation:String?,val foreground:Long,val start:Boolean=false,val precise:Boolean=false) {
    fun current(generation:String?,foreground:Long,blocked:Boolean)=!blocked && this.generation==generation && this.foreground==foreground
}
internal fun recordingAccess(context:Context):RecordingAccessState {
    fun granted(permission:String)=ContextCompat.checkSelfPermission(context,permission)==PackageManager.PERMISSION_GRANTED
    val location=when {granted(Manifest.permission.ACCESS_FINE_LOCATION)->LocationPrecision.precise;granted(Manifest.permission.ACCESS_COARSE_LOCATION)->LocationPrecision.approximate;else->LocationPrecision.none}
    return RecordingAccessState(location,NotificationManagerCompat.from(context).areNotificationsEnabled())
}
