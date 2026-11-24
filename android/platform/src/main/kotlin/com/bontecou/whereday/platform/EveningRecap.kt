package com.bontecou.whereday.platform

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.work.*
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime
import java.util.Locale
import java.util.concurrent.TimeUnit

/** Evening recap reminders (Settings › Reminders): a local notification on the chosen evenings with that day's (or, on
 *  Sunday, that week's) real counts, worked out from the journal when it fires. Nothing leaves the phone. One WorkManager
 *  job is pending at a time; each run posts or skips, then queues the next occurrence. No exact-alarm permission, so
 *  Android may deliver it a few minutes late. Tapping it opens Today's timeline, as the launcher shortcut does. */
object EveningRecap {
    internal const val WORK="evening-recap"
    /** PR #166's one-shot "tonight's page"; cancelled by the migration. */
    internal const val LEGACY_WORK="nightly-page"
    /** The channel #166 created as "Tonight's page", now named "Evening recap", so a channel someone silenced stays silenced. */
    const val CHANNEL="nightly-page"
    /** The launcher shortcut action that opens the day's timeline (LauncherAction.timeline in the app). */
    const val OPEN_ACTION="com.bontecou.whereday.shortcut.TIMELINE"
    internal const val FIRE="fire"
    internal const val QA="qa"
    internal const val QA_FREQUENCY="qa_frequency"
    private const val QA_WORK="evening-recap-qa"
    private const val STORE="evening-recap"
    private const val FOREGROUND="last_foreground"
    private const val RESULT="last_result"
    private const val ONBOARDING_DAY="onboarding_day"

    // MARK: Notification permission

    fun allowed(context:Context):Boolean {
        if(!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false
        val channel=context.getSystemService(NotificationManager::class.java)?.getNotificationChannel(CHANNEL)
        return channel==null || channel.importance!=NotificationManager.IMPORTANCE_NONE
    }

    // MARK: "The app was already open that evening"

    /** Called when the journal comes to the foreground and when it leaves it, so a long visit spanning 18:00 counts. */
    fun markForeground(context:Context,now:Instant=Instant.now()) {
        store(context).edit().putLong(FOREGROUND,now.toEpochMilli()).apply()
    }
    fun lastForeground(context:Context):Instant?=store(context).getLong(FOREGROUND,0L).takeIf {it>0}?.let(Instant::ofEpochMilli)

    /** Onboarding turned recaps on [now]. That evening's opens (finishing onboarding is one) don't skip the first recap the
     *  switch promised "starting tonight"; every other skip rule still applies ([RecapRules.openedThisEvening]). */
    fun markOnboardingEvening(context:Context,now:ZonedDateTime=ZonedDateTime.now()) {
        store(context).edit().putString(ONBOARDING_DAY,now.toLocalDate().toString()).apply()
    }
    fun onboardingEvening(context:Context):LocalDate?=store(context).getString(ONBOARDING_DAY,null)?.let {runCatching {LocalDate.parse(it)}.getOrNull()}

    // MARK: Scheduling

    /** Migrates the first stored value, cancels #166's one-shot page and (re)schedules from the current settings.
     *  For app start, settings changes, boot, and time or time-zone changes. Returns the next fire time, or null when off. */
    suspend fun reconcile(context:Context,graph:AppGraph,now:ZonedDateTime=ZonedDateTime.now()):ZonedDateTime? {
        val manager=WorkManager.getInstance(context.applicationContext)
        if(graph.preferences.settings.first().recapFrequency==null) {
            val legacy=withContext(Dispatchers.IO) {runCatching {manager.getWorkInfosForUniqueWork(LEGACY_WORK).get(5,TimeUnit.SECONDS)}.getOrDefault(emptyList())}
            val pending=legacy.any {it.state==WorkInfo.State.ENQUEUED || it.state==WorkInfo.State.BLOCKED}
            graph.preferences.update {if(it.recapFrequency==null) it.copy(recapFrequency=RecapFrequency.migrated(null,pending).key) else it}
        }
        manager.cancelUniqueWork(LEGACY_WORK)
        return schedule(context,graph.preferences.settings.first().recap,now)
    }

    /** Replaces the pending recap with the next one for [settings], or cancels it when recaps are off. */
    fun schedule(context:Context,settings:RecapSettings,now:ZonedDateTime=ZonedDateTime.now()):ZonedDateTime?=enqueue(context,settings,now,ExistingWorkPolicy.REPLACE)

    private fun enqueue(context:Context,settings:RecapSettings,now:ZonedDateTime,policy:ExistingWorkPolicy):ZonedDateTime? {
        val manager=WorkManager.getInstance(context.applicationContext)
        val fire=RecapSchedule.next(settings,now) ?: run {manager.cancelUniqueWork(WORK);return null}
        val delay=Duration.between(now.toInstant(),fire.toInstant()).coerceAtLeast(Duration.ZERO)
        val request=OneTimeWorkRequestBuilder<EveningRecapWork>().setInitialDelay(delay).addTag(WORK)
            .setInputData(workDataOf(FIRE to fire.toInstant().toEpochMilli())).build()
        manager.enqueueUniqueWork(WORK,policy,request)
        return fire
    }

    /** From inside a running recap: REPLACE would cancel the job that is queueing it, so the next one is appended. */
    internal fun scheduleAfterRun(context:Context,settings:RecapSettings,now:ZonedDateTime)=enqueue(context,settings,now,ExistingWorkPolicy.APPEND_OR_REPLACE)

    /** mapQa only: runs the real recap job now with [frequency] (any day of the week), ignoring the foreground rule and
     *  queueing nothing afterwards. The result is in [lastResult]. */
    fun runNowForQa(context:Context,frequency:RecapFrequency?) {
        check(context.packageName.endsWith(".mapqa")) {"The immediate recap is for the disposable QA package only"}
        val data=workDataOf(FIRE to System.currentTimeMillis(),QA to true,QA_FREQUENCY to frequency?.key)
        WorkManager.getInstance(context.applicationContext).enqueueUniqueWork(QA_WORK,ExistingWorkPolicy.REPLACE,
            OneTimeWorkRequestBuilder<EveningRecapWork>().setInputData(data).build())
    }

    // MARK: Running

    /** What one run did, kept for diagnosis and the QA trigger: `sent`, a [RecapSkip] name, `blocked` or `failed`. */
    data class RunResult(val outcome:String,val title:String?=null,val body:String?=null,val at:Instant=Instant.now())

    fun lastResult(context:Context):RunResult?=store(context).getString(RESULT,null)?.split('\u001f')?.takeIf {it.size==4}?.let {
        RunResult(it[0],it[1].ifEmpty {null},it[2].ifEmpty {null},Instant.ofEpochMilli(it[3].toLongOrNull() ?: 0))
    }
    internal fun record(context:Context,result:RunResult) {
        store(context).edit().putString(RESULT,listOf(result.outcome,result.title.orEmpty(),result.body.orEmpty(),result.at.toEpochMilli().toString()).joinToString("\u001f")).apply()
    }

    /** The journal's facts for [period]: places and names, distance, recordings and, for a week, Country Days. */
    suspend fun facts(graph:AppGraph,period:RecapPeriod,weekly:Boolean):RecapFacts=withContext(Dispatchers.IO) {
        val window=graph.history.readWindow(period.range,displayLimit=2,includeOutliers=false)
        val earlier=graph.history.dao.rangeVisits(HistoryRange.all.from,period.from).map {it.value}
        val countries=if(!weekly) null else try {
            RecapFacts.countries(graph.history.readCountryDays(CountryDayBounds(period.firstDay,period.lastDay)).days,period)
        } catch(e:CancellationException) {throw e} catch(_:Exception) {null}
        RecapFacts.of(period,window.visits,earlier,window.summary.distanceMeters,window.summary.count,window.sessions.size,countries)
    }

    /** Works out the recap for [fire] and posts it, or says why not. */
    internal suspend fun deliver(context:Context,graph:AppGraph,settings:RecapSettings,fire:ZonedDateTime,qa:Boolean):RunResult {
        if(!settings.enabled || (!qa && !RecapSchedule.firesOn(settings.frequency,fire.toLocalDate()))) return RunResult(RecapSkip.off.name)
        val weekly=settings.frequency==RecapFrequency.weekly
        val facts=facts(graph,RecapSchedule.period(settings.frequency,fire),weekly)
        val access=graph.purchases.hasAccess(com.bontecou.whereday.billing.Capability.recording)
        val skip=RecapRules.skip(settings,fire,facts,access,if(qa) null else lastForeground(context),anyDay=qa,onboardingDay=onboardingEvening(context))
        if(skip!=null) return RunResult(skip.name)
        val content=RecapContent.of(facts,weekly,settings.placeNames)
        val metric=graph.preferences.settings.first().metric
        val title=RecapText.title(context,content);val body=RecapText.body(context,content,metric)
        if(!allowed(context)) return RunResult("blocked",title,body)
        post(context,title,body)
        return RunResult("sent",title,body)
    }

    private fun post(context:Context,title:String,body:String) {
        val manager=context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(NotificationChannel(CHANNEL,context.getString(R.string.recap_channel),NotificationManager.IMPORTANCE_DEFAULT))
        // The explicit launch component with the timeline action opens Today's timeline, as the launcher shortcut does.
        val open=context.packageManager.getLaunchIntentForPackage(context.packageName)?.setAction(OPEN_ACTION)
            ?.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            ?.let {PendingIntent.getActivity(context,4,it,PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)}
        try {
            // Counts only unless Show place names is on, which is the lock-screen opt-in.
            manager.notify(CHANNEL.hashCode(),NotificationCompat.Builder(context,CHANNEL).setSmallIcon(android.R.drawable.ic_menu_today)
                .setContentTitle(title).setContentText(body).setStyle(NotificationCompat.BigTextStyle().bigText(body))
                .setContentIntent(open).setAutoCancel(true).setVisibility(NotificationCompat.VISIBILITY_PUBLIC).build())
        } catch(_:SecurityException) {} // Revoked between the check and the post.
    }

    private fun store(context:Context)=context.applicationContext.getSharedPreferences(STORE,Context.MODE_PRIVATE)
}

/** The recap's wording. Every string comes from resources, so it follows the app language; numbers use its locale. */
object RecapText {
    fun title(context:Context,content:RecapContent):String=context.getString(if(content.weekly) R.string.recap_weekly_title else R.string.recap_daily_title)

    fun body(context:Context,content:RecapContent,metric:Boolean,locale:Locale=context.resources.configuration.locales[0]):String {
        val resources=context.resources
        val integers=NumberFormat.getIntegerInstance(locale)
        val distance=distance(content.distanceMeters,metric,locale)
        val countries=content.countries?.let {resources.getQuantityString(R.plurals.recap_countries,it,integers.format(it))}
        if(content.named) {
            val names=names(context,content,integers)
            return if(countries!=null) context.getString(R.string.recap_named_countries,names,distance,countries) else context.getString(R.string.recap_named,names,distance)
        }
        val places=resources.getQuantityString(R.plurals.recap_places,content.placeCount,integers.format(content.placeCount))
        return when {
            !content.weekly->context.getString(R.string.recap_daily_body,places,distance)
            countries!=null->context.getString(R.string.recap_weekly_body,places,distance,countries)
            else->context.getString(R.string.recap_weekly_body_no_countries,places,distance)
        }
    }

    /** "Home", "Home and Café", "Home, Café and Park", "Home, Café, Park and 2 more". */
    private fun names(context:Context,content:RecapContent,integers:NumberFormat):String {
        val items=content.names+listOfNotNull(content.more.takeIf {it>0}?.let {context.resources.getQuantityString(R.plurals.recap_names_more,it,integers.format(it))})
        return when(items.size) {
            1->items[0]
            2->context.getString(R.string.recap_names_two,items[0],items[1])
            3->context.getString(R.string.recap_names_three,items[0],items[1],items[2])
            else->context.getString(R.string.recap_names_four,items[0],items[1],items[2],items[3])
        }
    }

    /** "5.6 km", "0.4 mi", "1,520 km": one decimal under ten, whole numbers above, in the app's distance unit. */
    fun distance(meters:Double,metric:Boolean,locale:Locale):String {
        val value=(meters.takeIf {it.isFinite() && it>0} ?: 0.0)/if(metric) 1000.0 else 1609.344
        val format=NumberFormat.getNumberInstance(locale).apply {val digits=if(value<10) 1 else 0;minimumFractionDigits=digits;maximumFractionDigits=digits}
        return format.format(value)+if(metric) " km" else " mi"
    }
}

class EveningRecapWork(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result {
        val context=applicationContext
        val zone=ZoneId.systemDefault()
        val now=ZonedDateTime.now(zone)
        val fire=inputData.getLong(EveningRecap.FIRE,0L).takeIf {it>0}?.let {Instant.ofEpochMilli(it).atZone(zone)} ?: now
        val qa=inputData.getBoolean(EveningRecap.QA,false) && context.packageName.endsWith(".mapqa")
        val graph=AppGraph.from(context)
        val result=try {
            graph.journalClear.awaitReady()
            var settings=graph.preferences.settings.first().recap
            if(qa) RecapFrequency.fromKey(inputData.getString(EveningRecap.QA_FREQUENCY))?.let {settings=settings.copy(frequency=it)}
            EveningRecap.deliver(context,graph,settings,fire,qa=qa)
        } catch(e:CancellationException) {throw e} catch(_:Exception) {EveningRecap.RunResult("failed")}
        EveningRecap.record(context,result)
        // The next one is queued from the settings as they are now; never the evening that just ran.
        if(!qa) try {EveningRecap.scheduleAfterRun(context,graph.preferences.settings.first().recap,maxOf(now,fire.plusMinutes(1)))}
            catch(e:CancellationException) {throw e} catch(_:Exception) {}
        return Result.success()
    }
}

/** A changed clock or time zone moves the local recap time; WorkManager keeps the old delay, so the recap is rescheduled. */
class EveningRecapTimeReceiver:BroadcastReceiver() {
    override fun onReceive(context:Context,intent:Intent) {
        if(intent.action !in setOf(Intent.ACTION_BOOT_COMPLETED,Intent.ACTION_TIME_CHANGED,Intent.ACTION_TIMEZONE_CHANGED,Intent.ACTION_MY_PACKAGE_REPLACED)) return
        val pending=goAsync()
        CoroutineScope(SupervisorJob()+Dispatchers.IO).launch {
            try {
                WorkManager.getInstance(context).enqueueUniqueWork("evening-recap-reconcile",ExistingWorkPolicy.REPLACE,
                    OneTimeWorkRequestBuilder<EveningRecapReconcileWork>().build()).result.get(7,TimeUnit.SECONDS)
            } catch(_:Exception) {} // The next app start reconciles too.
            finally {pending.finish()}
        }
    }
}
class EveningRecapReconcileWork(context:Context,parameters:WorkerParameters):CoroutineWorker(context,parameters) {
    override suspend fun doWork():Result=try {EveningRecap.reconcile(applicationContext,AppGraph.from(applicationContext));Result.success()}
        catch(e:Exception) {if(e is CancellationException) throw e;Result.retry()}
}
