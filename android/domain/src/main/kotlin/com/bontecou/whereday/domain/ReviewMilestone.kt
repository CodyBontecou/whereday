package com.bontecou.whereday.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate

/** A finished task that may lead to a review request. Mirrors iOS `AppReviewPromptSuccess`: only work the person
 * chose to do and saw succeed qualifies; backups, scheduled or background work never do. */
enum class ReviewSuccess(val qualifies:Boolean) {
    countryDaysExport(true),fileExport(true),outingExport(true),historyImport(true),purchase(true),
    /** The journal backup (Settings, and the step before Clear All Data). */
    backup(false),
    scheduledExport(false)
}

/** Same rule as iOS: use on two civil days, then a qualifying success; at most once per app version and 120 days apart.
 * Only a capped day count and the last use day are kept, never a growing history, rating or export contents. */
data class ReviewMilestone(val useDays:Int=0,val lastUseDay:LocalDate?=null,val requestedVersion:String?=null,val requestedAt:Instant?=null) {
    init {require(useDays in 0..maxUseDays && (useDays==0)==(lastUseDay==null))}
    fun used(day:LocalDate)=when {
        lastUseDay==null->copy(useDays=1,lastUseDay=day)
        day.isAfter(lastUseDay)->copy(useDays=minOf(useDays+1,maxUseDays),lastUseDay=day)
        else->this // Same day, or the clock moved back: never double-counted.
    }
    fun eligible(version:String,now:Instant)=useDays>=requiredUseDays && requestedVersion!=version &&
        (requestedAt==null || !now.isBefore(requestedAt.plus(cooldown)))
    fun claim(version:String,now:Instant)=if(eligible(version,now)) copy(requestedVersion=version,requestedAt=now) else this
    /** Clearing the journal erases use metadata but keeps when the last request was made. */
    fun cleared()=ReviewMilestone(requestedVersion=requestedVersion,requestedAt=requestedAt)
    companion object {
        const val requiredUseDays=2
        const val maxUseDays=30
        val cooldown:Duration=Duration.ofDays(120)
        /** Version 1 kept up to two dates and a single-shot flag. A consumed attempt starts the cooldown at migration. */
        fun migrated(days:Set<LocalDate>,requested:Boolean,now:Instant)=
            ReviewMilestone(days.size.coerceAtMost(maxUseDays),days.maxOrNull(),requestedAt=if(requested) now else null)
    }
}
