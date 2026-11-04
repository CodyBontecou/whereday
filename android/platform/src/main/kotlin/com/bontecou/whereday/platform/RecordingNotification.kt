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
    fun channel(context:Context,id:String)=android.app.NotificationChannel(id,context.getString(R.string.notification_recording_channel),android.app.NotificationManager.IMPORTANCE_LOW)
    fun detail(context:Context,elapsed:Duration,points:Long,distanceMeters:Double?,metric:Boolean,remaining:Duration?,locale:Locale=context.resources.configuration.locales[0]):String {
        val seconds=elapsed.seconds.coerceAtLeast(0)
        val integers=NumberFormat.getIntegerInstance(locale)
        val distance=distanceMeters?.takeIf {it.isFinite() && it>=0}?.let {
            val format=NumberFormat.getNumberInstance(locale).apply {minimumFractionDigits=2;maximumFractionDigits=2}
            format.format(it/if(metric) 1000.0 else 1609.344)+if(metric) " km" else " mi"
        } ?: context.getString(R.string.notification_recording_distance_pending)
        val duration=context.getString(R.string.notification_recording_duration,integers.format(seconds/3600),integers.format((seconds/60)%60))
        val count=points.coerceAtLeast(0)
        val pointText=context.resources.getQuantityString(R.plurals.notification_recording_points,if(count==1L) 1 else 2,integers.format(count))
        val detail=context.getString(R.string.notification_recording_detail,distance,duration,pointText)
        return remaining?.let {
            val minutes=it.toMinutes().coerceAtLeast(0)
            val countdown=context.resources.getQuantityString(R.plurals.notification_recording_remaining,if(minutes==1L) 1 else 2,integers.format(minutes))
            context.getString(R.string.notification_recording_countdown_detail,detail,countdown)
        } ?: detail
    }
    /** The current stay for the ongoing notification's title ("At Mission Office since 13:50"). */
    data class Stay(val name:String,val since:java.time.Instant)
    private fun clock(instant:java.time.Instant)=java.time.format.DateTimeFormatter.ofPattern("HH:mm").format(instant.atZone(java.time.ZoneId.systemDefault()))
    /** The Material 3 ongoing notification (the Android stand-in for the iOS Live Activity): where you are, since when,
     *  the day's figures, and Stop recording / Log a stop. Names and places stay out of the lock-screen public version. */
    fun build(context:Context,channel:String,detail:String,preparing:Boolean=false,startedAt:java.time.Instant?=null,stay:Stay?=null):Notification {
        val open=context.packageManager.getLaunchIntentForPackage(context.packageName)
        val openAction=open?.let {PendingIntent.getActivity(context,0,it,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)}
        val stop=PendingIntent.getService(context,1,Intent(context,RecordingService::class.java).setAction(RecordingService.STOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val logStop=PendingIntent.getActivity(context,2,Intent(Intent.ACTION_VIEW,android.net.Uri.parse("whereday://log-visit")).setPackage(context.packageName)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP),PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun builder()=NotificationCompat.Builder(context,channel).setSmallIcon(android.R.drawable.ic_menu_mylocation)
            .setColor(0xFFC2462A.toInt())
            .setContentIntent(openAction).setOngoing(true).setOnlyAlertOnce(true).setCategory(NotificationCompat.CATEGORY_SERVICE)
            .addAction(android.R.drawable.ic_media_pause,context.getString(if(preparing) R.string.notification_recording_stop else R.string.notification_recording_stop_full),stop)
            .apply {if(!preparing) addAction(android.R.drawable.ic_input_add,context.getString(R.string.notification_recording_log_stop),logStop)}
            .apply {if(startedAt!=null && !preparing) setSubText(context.getString(R.string.notification_recording_since,clock(startedAt))).setWhen(startedAt.toEpochMilli()).setShowWhen(false)}
        val public=builder().setContentTitle(context.getString(R.string.notification_recording_brand)).setContentText(context.getString(if(preparing) R.string.notification_recording_preparing else R.string.notification_recording_public)).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build()
        val title=when {
            preparing->context.getString(R.string.notification_recording_brand)
            stay!=null->context.getString(R.string.notification_recording_at,stay.name,clock(stay.since))
            else->context.getString(R.string.notification_recording_title)
        }
        return builder().setContentTitle(title).setContentText(detail)
            .setStyle(NotificationCompat.BigTextStyle().bigText(detail)).setVisibility(NotificationCompat.VISIBILITY_PRIVATE).setPublicVersion(public).build()
    }
}
