package com.bontecou.whereday.platform

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import java.time.Duration
import java.text.NumberFormat
import java.util.Locale

internal object RecordingNotification {
    fun detail(elapsed:Duration,points:Long,distanceMeters:Double?,metric:Boolean,remaining:Duration?,locale:Locale=Locale.getDefault()):String {
        val seconds=elapsed.seconds.coerceAtLeast(0)
        val distance=distanceMeters?.takeIf {it.isFinite() && it>=0}?.let {
            val format=NumberFormat.getNumberInstance(locale).apply {minimumFractionDigits=2;maximumFractionDigits=2}
            format.format(it/if(metric) 1000.0 else 1609.344)+if(metric) " km" else " mi"
        } ?: "Distance updating"
        val countdown=remaining?.let {" · ${it.toMinutes().coerceAtLeast(0)} min left"}.orEmpty()
        return "$distance · ${seconds/3600}h ${(seconds/60)%60}m · ${points.coerceAtLeast(0)} points$countdown"
    }
    fun build(context:Context,channel:String,detail:String):Notification {
        val open=context.packageManager.getLaunchIntentForPackage(context.packageName)
        val openAction=open?.let {PendingIntent.getActivity(context,0,it,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)}
        val stop=PendingIntent.getService(context,1,Intent(context,RecordingService::class.java).setAction(RecordingService.STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun builder()=NotificationCompat.Builder(context,channel).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setContentIntent(openAction).setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause,"Stop",stop)
        val public=builder().setContentTitle("Whereday").setContentText("Location recording is active").setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build()
        return builder().setContentTitle("Whereday is recording").setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail)).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).build()
    }
}
