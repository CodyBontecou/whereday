package com.bontecou.whereday.platform

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import androidx.work.*
import java.time.Duration
import java.time.Instant

/** The Day 5 notice for a free week, two days before Google Play starts charging. Mirrors the iOS reminder. */
object TrialReminder {
    private const val WORK="trial-reminder"
    private const val CHANNEL="trial-reminder"
    internal const val PRICE="price"
    internal const val YEARLY="yearly"
    val leadTime:Duration=Duration.ofDays(2)

    fun fireAt(trialEnds:Instant):Instant=trialEnds.minus(leadTime)

    fun schedule(context:Context,trialEnds:Instant,formattedPrice:String,yearly:Boolean,now:Instant=Instant.now()) {
        val delay=Duration.between(now,fireAt(trialEnds))
        if(delay.isNegative) return
        val request=OneTimeWorkRequestBuilder<TrialReminderWork>().setInitialDelay(delay)
            .setInputData(workDataOf(PRICE to formattedPrice,YEARLY to yearly)).build()
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(WORK,ExistingWorkPolicy.REPLACE,request)
    }
    fun cancel(context:Context) {WorkManager.getInstance(context.applicationContext).cancelUniqueWork(WORK)}

    internal fun body(context:Context,price:String,yearly:Boolean)=
        context.getString(if(yearly) R.string.trial_reminder_body_yearly else R.string.trial_reminder_body_weekly,price)

    internal fun post(context:Context,price:String,yearly:Boolean) {
        if(ContextCompat.checkSelfPermission(context,Manifest.permission.POST_NOTIFICATIONS)!=PackageManager.PERMISSION_GRANTED) return
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,context.getString(R.string.trial_reminder_channel),NotificationManager.IMPORTANCE_DEFAULT))
        val open=context.packageManager.getLaunchIntentForPackage(context.packageName)
            ?.let {PendingIntent.getActivity(context,3,it,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)}
        val body=body(context,price,yearly)
        manager.notify(CHANNEL.hashCode(),NotificationCompat.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_popup_reminder)
            .setContentTitle(context.getString(R.string.trial_reminder_title)).setContentText(body)
            .setStyle(NotificationCompat.BigTextStyle().bigText(body)).setContentIntent(open).setAutoCancel(true).build())
    }
}

class TrialReminderWork(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        val price=inputData.getString(TrialReminder.PRICE) ?: return Result.success()
        TrialReminder.post(applicationContext,price,inputData.getBoolean(TrialReminder.YEARLY,false))
        return Result.success()
    }
}
