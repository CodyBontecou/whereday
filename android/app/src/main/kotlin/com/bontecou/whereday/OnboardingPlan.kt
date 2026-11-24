package com.bontecou.whereday

import com.bontecou.whereday.export.ExportFormat
import com.bontecou.whereday.platform.UsageStep
import java.time.LocalDate

/** The ten Page One steps in order. Their names are stable test and analytics identities, not copy. */
internal enum class OnboardingStep {
    cover,intent,scrub,pen,perm,stamp,backfill,glance,export,tonight;

    /** The reviewed analytics vocabulary stays at five steps: the new teaching pages report as features. */
    val usageStep:UsageStep get()=when(this) {
        cover->UsageStep.welcome
        perm->UsageStep.permissions
        glance->UsageStep.notifications
        tonight->UsageStep.ready
        intent,scrub,pen,stamp,backfill,export->UsageStep.features
    }
    companion object {
        fun at(index:Int)=entries[index.coerceIn(0,entries.lastIndex)]
    }
}

/** "What should it remember for you?" Keys are persisted in AppSettings.onboardingIntents for a later checklist. */
internal enum class OnboardingIntent(val key:String) {
    days("days"),countries("countries"),routes("routes"),notes("notes");
    companion object {
        /** Remember my days is preselected, as in the design. */
        val DEFAULT:Set<OnboardingIntent> = setOf(days)
        /** Null means the step was never answered; unknown keys from a later version are dropped. */
        fun decode(keys:Set<String>?):Set<OnboardingIntent> = if(keys==null) DEFAULT else entries.filter {it.key in keys}.toSet()
        fun encode(intents:Set<OnboardingIntent>):Set<String> = intents.mapTo(sortedSetOf()) {it.key}
    }
}

/** The export step's sample formats. */
internal enum class OnboardingFormat(val key:String,val exportFormat:ExportFormat) {
    markdown("markdown",ExportFormat.markdown),gpx("gpx",ExportFormat.gpx),csv("csv",ExportFormat.csv),json("json",ExportFormat.json);
    companion object {
        /** Notes win, then routes, then countries; otherwise Markdown. */
        fun preferred(intents:Set<OnboardingIntent>)=when {
            OnboardingIntent.notes in intents->markdown
            OnboardingIntent.routes in intents->gpx
            OnboardingIntent.countries in intents->csv
            else->markdown
        }
        /** Only the notes intent changes the saved export format, and only to Markdown. */
        fun savedDefault(intents:Set<OnboardingIntent>):ExportFormat?=if(OnboardingIntent.notes in intents) ExportFormat.markdown else null
    }
}

/** Synthetic "how to read a page" day. Hours are fractions of 24; [ink] indexes wd.placeInks, null for travel. */
internal data class SampleStop(val start:Float,val end:Float,val name:Int,val detail:Int,val ink:Int?)

internal object OnboardingSampleDay {
    val stops=listOf(
        SampleStop(0f,8.5f,R.string.onboarding_sample_home,R.string.onboarding_sample_home_morning,0),
        SampleStop(8.5f,8.83f,R.string.onboarding_sample_walking,R.string.onboarding_sample_walk_one,null),
        SampleStop(8.83f,9.75f,R.string.onboarding_sample_cafe,R.string.onboarding_sample_cafe_detail,1),
        SampleStop(9.75f,10f,R.string.onboarding_sample_walking,R.string.onboarding_sample_walk_two,null),
        SampleStop(10f,17.5f,R.string.onboarding_sample_office,R.string.onboarding_sample_office_detail,2),
        SampleStop(17.5f,18f,R.string.onboarding_sample_cycling,R.string.onboarding_sample_cycle,null),
        SampleStop(18f,19.25f,R.string.onboarding_sample_park,R.string.onboarding_sample_park_detail,3),
        SampleStop(19.25f,19.67f,R.string.onboarding_sample_walking,R.string.onboarding_sample_walk_three,null),
        SampleStop(19.67f,24f,R.string.onboarding_sample_home,R.string.onboarding_sample_home_night,0),
    )
    const val START_HOUR=9.25f
    const val LAST_HOUR=23.75f
    fun at(hour:Float)=stops.firstOrNull {hour>=it.start && hour<it.end} ?: stops.first()
}

/** The backfill heatmap: 12 weeks of days ending today, oldest first, filled column by column. */
internal object OnboardingHeatmap {
    const val DAYS=84
    fun days(today:LocalDate):List<LocalDate> = (DAYS-1 downTo 0).map {today.minusDays(it.toLong())}
    /** 0 = none, 1 = one photo, 2 = two to four, 3 = five or more. */
    fun level(photos:Int)=when {photos<=0->0;photos==1->1;photos<5->2;else->3}
    /** Days before today with at least one located photo. */
    fun foundDays(counts:Map<LocalDate,Int>,today:LocalDate)=days(today).count {it!=today && (counts[it] ?: 0)>0}
}

/** Place name chips on the stamp step. [other] saves nothing: "Somewhere else" is not a name. */
internal enum class OnboardingPlaceName(val key:String,val label:Int) {
    home("home",R.string.onboarding_place_home),work("work",R.string.onboarding_place_work),cafe("cafe",R.string.onboarding_place_cafe),
    gym("gym",R.string.onboarding_place_gym),other("other",R.string.onboarding_place_other);
    val saves get()=this!=other
    companion object {fun fromKey(key:String?)=entries.firstOrNull {it.key==key}}
}
