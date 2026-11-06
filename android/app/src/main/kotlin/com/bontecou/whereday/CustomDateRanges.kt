package com.bontecou.whereday

import com.bontecou.whereday.domain.HistoryRange
import com.bontecou.whereday.domain.mapPresetRange
import java.time.*
import java.time.temporal.ChronoUnit

internal enum class CustomDateError { invalid, order, future, skipped, unsupported, retainedTimes }

internal fun customDateError(first:LocalDate?,last:LocalDate?,latest:LocalDate?,zone:ZoneId?):CustomDateError? {
    if(first==null || last==null) return CustomDateError.invalid
    if(first>last) return CustomDateError.order
    if(latest!=null && last>latest) return CustomDateError.future
    return try {
        val from=first.atStartOfDay(zone ?: ZoneOffset.UTC).toInstant()
        val until=last.plusDays(1).atStartOfDay(zone ?: ZoneOffset.UTC).toInstant()
        from.toEpochMilli();until.toEpochMilli()
        if(from>=until) CustomDateError.skipped else null
    } catch(_:DateTimeException) {CustomDateError.unsupported}
      catch(_:ArithmeticException) {CustomDateError.unsupported}
}

/** The iOS 2.0 map calendar works in whole days: the first tap anchors a new range, the second
 * closes it, and a span that matches a preset (Today, Yesterday, This month) becomes that preset. */
internal data class MapRangeDraft(
    val preset:String?,val lower:LocalDate,val upper:LocalDate,val month:YearMonth,val anchor:LocalDate?=null
) {
    val dayCount:Int get()=ChronoUnit.DAYS.between(lower,upper).toInt()+1

    fun select(option:String,now:Instant,zone:ZoneId):MapRangeDraft {
        val range=mapPresetRange(option,now,zone)
        val last=range.until.minusNanos(1).atZone(zone).toLocalDate()
        return MapRangeDraft(option,if(option=="All") last else range.from.atZone(zone).toLocalDate(),last,YearMonth.from(last))
    }

    fun tap(day:LocalDate,now:Instant,zone:ZoneId):MapRangeDraft {
        val next=if(anchor!=null) copy(lower=minOf(anchor,day),upper=maxOf(anchor,day),anchor=null) else copy(lower=day,upper=day,anchor=day)
        val today=now.atZone(zone).toLocalDate()
        val detected=when {
            next.lower==next.upper && next.lower==today->"Today"
            next.lower==next.upper && next.lower==today.minusDays(1)->"Yesterday"
            next.lower==today.withDayOfMonth(1) && next.upper==today->"This month"
            else->null
        }
        return next.copy(preset=detected)
    }

    fun showMonth(delta:Long)=copy(month=month.plusMonths(delta))
    fun canShowNextMonth(now:Instant,zone:ZoneId)=month.plusMonths(1).atDay(1)<=now.atZone(zone).toLocalDate()

    /** The apply button's count: the highlighted span, or for All every day since the first record. */
    fun applyDayCount(firstRecorded:LocalDate?,now:Instant,zone:ZoneId):Int {
        if(preset!="All") return dayCount
        val today=now.atZone(zone).toLocalDate()
        return if(firstRecorded==null || firstRecorded>=today) 1 else ChronoUnit.DAYS.between(firstRecorded,today).toInt()+1
    }

    /** Presets keep following the clock. A custom span runs from the start of its first day to the
     * same clock time one calendar day after the start of its last day (Swift's day addition), never past now. */
    fun apply(now:Instant,zone:ZoneId):MapSelection {
        preset?.let {return MapSelection(it)}
        val from=lower.atStartOfDay(zone).toInstant()
        val end=upper.atStartOfDay(zone).plusDays(1).toInstant()
        val until=maxOf(minOf(end,now.truncatedTo(ChronoUnit.MILLIS).plusMillis(1)),from.plusMillis(1))
        return MapSelection("Custom",lower,upper,HistoryRange(from,until))
    }

    companion object {
        val presets=listOf("Today","Yesterday","7 days","This month","30 days","All")

        /** Opens on the active preset, or on the whole days the current window covers up to now. */
        fun open(selection:MapSelection,now:Instant,zone:ZoneId):MapRangeDraft {
            val active=selection.preset.takeIf {selection.outingRange==null && it in presets}
            val range=selection.range(now,zone)
            val last=minOf(range.until.minusNanos(1),now).atZone(zone).toLocalDate()
            val first=if(active=="All") last else minOf(range.from.atZone(zone).toLocalDate(),last)
            return MapRangeDraft(active,first,last,YearMonth.from(last))
        }
    }
}
