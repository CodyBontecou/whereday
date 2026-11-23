package com.bontecou.whereday.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import java.time.Duration
import java.time.LocalTime
import java.time.ZonedDateTime

/** Onboarding's "Send me tonight's page": one local notification at the next 9 PM. Generic text only,
 *  never a place or coordinate, and nothing leaves the phone. WorkManager keeps it without an exact-alarm grant. */
object NightlyPageReminder {
    private const val WORK="nightly-page"
    private const val CHANNEL="nightly-page"
    /** The launcher shortcut action that opens the day's timeline (LauncherAction.timeline in the app). */
    const val OPEN_ACTION="com.bontecou.whereday.shortcut.TIMELINE"
    val at:LocalTime=LocalTime.of(21,0)
    /** From this time on, tonight's page is too close to schedule and moves to tomorrow. */
    val cutoff:LocalTime=LocalTime.of(20,30)

    /** Today's 9 PM before 20:30, otherwise tomorrow's, in the zone of [now] (DST gaps resolve forward). */
    fun nextFire(now:ZonedDateTime):ZonedDateTime {
        val date=if(now.toLocalTime().isBefore(cutoff)) now.toLocalDate() else now.toLocalDate().plusDays(1)
        return date.atTime(at).atZone(now.zone)
    }
    fun tomorrow(now:ZonedDateTime)=nextFire(now).toLocalDate()!=now.toLocalDate()

    fun allowed(context:Context)=NotificationManagerCompat.from(context).areNotificationsEnabled()

    /** Replaces any earlier request. Skips (returns null) while notifications are not permitted. */
    fun schedule(context:Context,now:ZonedDateTime=ZonedDateTime.now()):ZonedDateTime? {
        if(!allowed(context)) return null
        val fire=nextFire(now)
        val delay=Duration.between(now.toInstant(),fire.toInstant()).coerceAtLeast(Duration.ZERO)
        val request=OneTimeWorkRequestBuilder<NightlyPageWork>().setInitialDelay(delay).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(WORK,ExistingWorkPolicy.REPLACE,request)
        return fire
    }
    fun cancel(context:Context) {WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK)}

    internal fun post(context:Context) {
        if(!allowed(context)) return
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,context.getString(R.string.nightly_page_channel),NotificationManager.IMPORTANCE_DEFAULT))
        // The explicit launch component with the timeline action opens Today's timeline, as the launcher shortcut does.
        val open=context.packageManager.getLaunchIntentForPackage(context.packageName)?.setAction(OPEN_ACTION)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?.let {PendingIntent.getActivity(context,4,it,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)}
        val body=context.getString(R.string.nightly_page_body)
        try {
            manager.notify(CHANNEL.hashCode(),NotificationCompat.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_today)
                .setContentTitle(context.getString(R.string.nightly_page_title)).setContentText(body)
                .setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build())
        } catch(_:SecurityException) {} // Revoked between the check and the post.
    }
}

class NightlyPageWork(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        NightlyPageReminder.post(applicationContext)
        return Result.success()
    }
}
