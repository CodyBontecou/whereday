package com.bontecou.whereday.domain

import java.time.DayOfWeek
import java.time.Instant
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId
import java.time.ZonedDateTime

/** How often the evening recap notification is sent (Settings › Reminders › Day recap). The keys are stored. */
enum class RecapFrequency(val key:String) {
    off("off"),
    /** Every evening. */
    daily("daily"),
    /** Monday to Friday evenings. */
    weekdays("weekdays"),
    /** One "Your week" recap on Sunday evening. */
    weekly("weekly");
    companion object {
        fun fromKey(key:String?):RecapFrequency?=entries.firstOrNull {it.key==key}
        /** Onboarding's "Evening recap" switch: on is Every evening, off is Off. */
        fun fromToggle(on:Boolean)=if(on) daily else off
        /** The first stored value. The one-shot "tonight's page" from the first Page One builds kept no setting, only a pending
         *  notification: when one is still pending it becomes Every evening, otherwise recaps start Off. */
        fun migrated(stored:RecapFrequency?,legacyPagePending:Boolean):RecapFrequency=stored ?: if(legacyPagePending) daily else off
    }
}

/** Settings › Reminders. [minuteOfDay] is local wall-clock time; 21:00 by default. */
data class RecapSettings(val frequency:RecapFrequency=RecapFrequency.off,val minuteOfDay:Int=DEFAULT_MINUTE,val skipQuietDays:Boolean=true,val placeNames:Boolean=false) {
    init {require(minuteOfDay in 0 until 24*60)}
    val time:LocalTime get()=LocalTime.of(minuteOfDay/60,minuteOfDay%60)
    val enabled:Boolean get()=frequency!=RecapFrequency.off
    /** True for 8, 9 or 10 PM; anything else is Custom. */
    val preset:Boolean get()=minuteOfDay in PRESETS
    companion object {
        const val DEFAULT_MINUTE=21*60
        val PRESETS=listOf(20*60,21*60,22*60)
    }
}

/** When recaps fire and which stretch of time each one covers. All times are local wall-clock times in the zone of `now`. */
object RecapSchedule {
    fun firesOn(frequency:RecapFrequency,date:LocalDate):Boolean=when(frequency) {
        RecapFrequency.off->false
        RecapFrequency.daily->true
        RecapFrequency.weekdays->date.dayOfWeek!=DayOfWeek.SATURDAY && date.dayOfWeek!=DayOfWeek.SUNDAY
        RecapFrequency.weekly->date.dayOfWeek==DayOfWeek.SUNDAY
    }

    /** The first recap strictly after [now], or null when recaps are off. A time inside a daylight-saving gap moves forward
     *  by the gap (02:30 becomes 03:30); in an overlap the earlier offset is used. */
    fun next(settings:RecapSettings,now:ZonedDateTime):ZonedDateTime? {
        if(!settings.enabled) return null
        var date=now.toLocalDate()
        repeat(8) {
            val at=ZonedDateTime.of(date,settings.time,now.zone)
            if(firesOn(settings.frequency,date) && at.isAfter(now)) return at
            date=date.plusDays(1)
        }
        return null
    }

    /** True when the next recap is today rather than a later day. */
    fun today(settings:RecapSettings,now:ZonedDateTime)=next(settings,now)?.toLocalDate()==now.toLocalDate()

    /** The stretch a recap firing at [fire] reports on: that day from midnight, or for the weekly recap the seven days
     *  ending on that Sunday (Monday 00:00 onward). It ends at the fire time. */
    fun period(frequency:RecapFrequency,fire:ZonedDateTime):RecapPeriod {
        val first=if(frequency==RecapFrequency.weekly) fire.toLocalDate().minusDays(6) else fire.toLocalDate()
        return RecapPeriod(first,fire.toLocalDate(),first.atStartOfDay(fire.zone).toInstant(),fire.toInstant(),fire.zone)
    }
}

/** [zone] is the one the days are civil days in, as Today's are. */
data class RecapPeriod(val firstDay:LocalDate,val lastDay:LocalDate,val from:Instant,val until:Instant,val zone:ZoneId) {
    val range:HistoryRange get()=HistoryRange(from,maxOf(until,from.plusMillis(1)))
    val days:List<LocalDate> get()=generateSequence(firstDay) {it.plusDays(1)}.takeWhile {it<=lastDay}.toList()
    /** Each day of the period as Today shows it at the recap time ([until]): its stays, clipped to that day. Points are left
     *  out; the distance comes from the period's point summary (see [RecapFacts.distanceMeters]). */
    fun stories(visits:List<Visit>):List<DayStory> =days.map {DayStory.build(it,zone,visits,emptyList(),until,0.0)}
}

/** What a recap knows about its period, read from the journal when it runs. */
data class RecapFacts(
    /** Distinct places, counted by Today's own rule ([DayStory.places]): each stay Today shows on the period's days, keyed by
     *  [placeKey] (the name, or the visit itself when unnamed). A stay that began before the period (a night at home) counts;
     *  one that only touches its start, or lasts no time, does not, as on Today. For a week, the union of the seven days. */
    val placeCount:Int,
    /** Named places in order of first arrival, without repeats ([DayStory.placeNames]; for a week, day by day). */
    val names:List<String>,
    /** The point summary's path length over the period, the figure Today shows: for a day, Today's distance at the recap
     *  time; for a week, the map's 7-day total, where a leg across midnight counts too (so it can be a little more than the
     *  sum of seven Today figures, which each start fresh at midnight). */
    val distanceMeters:Double,
    /** Distinct countries from Country Days; null when unknown. */
    val countries:Int?=null,
    /** Any visit or location point in the period. */
    val recorded:Boolean,
    /** Any sign that tracking ran: a location point, a recording, or an automatically detected visit. */
    val tracked:Boolean,
    /** A visit in the period to a place never visited before it (see [RecapRules.NEW_PLACE_RADIUS_METERS]). */
    val newPlace:Boolean) {
    companion object {
        /** [visits] overlap the period; [earlier] are the visits that arrived before it; [points] and [recordings] count the period's fixes and recordings. */
        fun of(period:RecapPeriod,visits:List<Visit>,earlier:List<Visit>,distanceMeters:Double,points:Long,recordings:Int,countries:Int?=null):RecapFacts {
            // Today's derivation, day by day, so the recap never disagrees with what Today showed.
            val stays=period.stories(visits).flatMap {it.stays}
            val places=stays.map {placeKey(it.visit)}.distinct()
            val names=stays.mapNotNull {it.name}.distinct()
            val sorted=stays.map {it.visit}.distinctBy {it.id}
            val prior=earlier.filter {it.arrivedAt<period.from}.map {it.coordinate}
            val fresh=sorted.any {visit->visit.arrivedAt>=period.from && prior.none {it.distanceTo(visit.coordinate)<=RecapRules.NEW_PLACE_RADIUS_METERS}}
            return RecapFacts(places.size,names,distanceMeters.takeIf {it.isFinite() && it>0} ?: 0.0,countries,
                recorded=sorted.isNotEmpty() || points>0,tracked=points>0 || recordings>0 || sorted.any {it.source==VisitSource.automatic},newPlace=fresh)
        }
        /** Distinct countries on the period's days, from Country Days presences; null when none are known. */
        fun countries(presences:List<CountryPresence>,period:RecapPeriod):Int?=
            presences.filter {it.day>=period.firstDay && it.day<=period.lastDay}.map {it.countryCode}.distinct().size.takeIf {it>0}
    }
}

/** Why a recap was not sent, checked in this order. */
enum class RecapSkip {
    /** Recaps are off, or this day is not one of the chosen days. */
    off,
    /** No tracking access (a lapsed plan). Builds without Play billing always have access, as recording is free there. */
    noAccess,
    /** No visit and no location point in the period. */
    nothingRecorded,
    /** Only hand-added or imported visits: tracking never ran in the period. */
    trackingOff,
    /** Whereday was in the foreground that evening, from 18:00 until the recap time; not on the evening onboarding turned recaps on. */
    openedThisEvening,
    /** Skip quiet days is on and the period was quiet ([RecapRules.quiet]). */
    quiet,
}

object RecapRules {
    /** A visit farther than this from every earlier visit is a new place (the default saved-place radius). */
    const val NEW_PLACE_RADIUS_METERS=150.0
    /** Moving less than this is part of a quiet day. */
    const val QUIET_DISTANCE_METERS=1_000.0
    /** The evening starts here for "the app was already open that evening". */
    val EVENING_START:LocalTime=LocalTime.of(18,0)

    /** Quiet: no visit to a new place and under 1 km moved. Same rule for a week. */
    fun quiet(facts:RecapFacts)=!facts.newPlace && facts.distanceMeters<QUIET_DISTANCE_METERS

    /** True when [lastForeground] falls between 18:00 and [fire] on the fire day; a recap set before 18:00 never counts.
     *  [onboardingDay] is the local day onboarding turned recaps on: that evening is exempt, because the switch promised a
     *  recap "starting tonight" and finishing onboarding is itself an evening open (iOS `firstEveningDay`). */
    fun openedThisEvening(lastForeground:Instant?,fire:ZonedDateTime,onboardingDay:LocalDate?=null):Boolean {
        if(lastForeground==null || onboardingDay==fire.toLocalDate()) return false
        val start=ZonedDateTime.of(fire.toLocalDate(),EVENING_START,fire.zone).toInstant()
        return lastForeground>=start && lastForeground<fire.toInstant()
    }

    /** [anyDay] is for the QA trigger only: it runs a frequency's recap on a day that frequency would not fire. */
    fun skip(settings:RecapSettings,fire:ZonedDateTime,facts:RecapFacts,hasAccess:Boolean,lastForeground:Instant?,anyDay:Boolean=false,onboardingDay:LocalDate?=null):RecapSkip?=when {
        !settings.enabled || (!anyDay && !RecapSchedule.firesOn(settings.frequency,fire.toLocalDate()))->RecapSkip.off
        !hasAccess->RecapSkip.noAccess
        !facts.recorded->RecapSkip.nothingRecorded
        !facts.tracked->RecapSkip.trackingOff
        openedThisEvening(lastForeground,fire,onboardingDay)->RecapSkip.openedThisEvening
        settings.skipQuietDays && quiet(facts)->RecapSkip.quiet
        else->null
    }
}

/** The notification's facts, before wording: at most three names, then "and N more". */
data class RecapContent(val weekly:Boolean,val placeCount:Int,val names:List<String>,val more:Int,val distanceMeters:Double,val countries:Int?) {
    val named:Boolean get()=names.isNotEmpty()
    companion object {
        const val MAX_NAMES=3
        /** Names only with Show place names on and a named place to show; countries only for a week with more than one. */
        fun of(facts:RecapFacts,weekly:Boolean,placeNames:Boolean):RecapContent {
            val names=if(placeNames) facts.names.take(MAX_NAMES) else emptyList()
            return RecapContent(weekly,facts.placeCount,names,if(names.isEmpty()) 0 else (facts.placeCount-names.size).coerceAtLeast(0),
                facts.distanceMeters,facts.countries?.takeIf {weekly && it>1})
        }
    }
}
