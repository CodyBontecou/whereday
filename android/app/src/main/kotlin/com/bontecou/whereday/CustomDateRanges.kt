package com.bontecou.whereday

import com.bontecou.whereday.domain.HistoryRange
import java.time.*

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

/** The original window and each native date-binding edit have independent precision. */
internal data class MapDateDraft(
    val range:HistoryRange,val zone:ZoneId,
    val pendingFirst:Instant=range.from,
    val pendingLast:Instant=Instant.ofEpochMilli(range.until.minusNanos(1).toEpochMilli()),
    val selectedFirst:LocalDate=range.from.atZone(zone).toLocalDate(),
    val selectedLast:LocalDate=range.until.minusNanos(1).atZone(zone).toLocalDate(),
    val firstEdited:Boolean=false,val lastEdited:Boolean=false
) {
    val first:LocalDate get()=range.from.atZone(zone).toLocalDate()
    val last:LocalDate get()=range.until.minusNanos(1).atZone(zone).toLocalDate()

    /** UIKit's date-only binding retains the current hour/minute, resolves gaps forward
     * and repeated hours to the first occurrence, and clears seconds on an actual edit.
     * Use the previous edited instant so a gap adjustment survives the next day change. */
    fun edit(first:LocalDate?,last:LocalDate?):MapDateDraft {
        fun edited(date:LocalDate,original:Instant)=date.atTime(original.atZone(zone).toLocalTime()
            .withSecond(0).withNano(0)).atZone(zone).toInstant().also {it.toEpochMilli()}
        val changeFirst=first!=null && first!=selectedFirst
        val changeLast=last!=null && last!=selectedLast
        return copy(
            pendingFirst=if(changeFirst) edited(first!!,pendingFirst) else pendingFirst,
            pendingLast=if(changeLast) edited(last!!,pendingLast) else pendingLast,
            selectedFirst=first ?: selectedFirst,selectedLast=last ?: selectedLast,
            firstEdited=firstEdited || changeFirst,lastEdited=lastEdited || changeLast)
    }
    private fun resolved():HistoryRange {
        val until=if(lastEdited) pendingLast.plusMillis(1) else range.until
        val from=if(firstEdited) pendingFirst else range.from
        from.toEpochMilli();until.toEpochMilli()
        require(from<until) {"The retained start time must not be after the end time."}
        return HistoryRange(from,until)
    }
    fun error(first:LocalDate,last:LocalDate):CustomDateError? {
        customDateError(first,last,null,zone)?.let {return it}
        return try {edit(first,last).resolved();null}
        catch(_:DateTimeException) {CustomDateError.unsupported}
        catch(_:ArithmeticException) {CustomDateError.unsupported}
        catch(_:IllegalArgumentException) {CustomDateError.retainedTimes}
    }
    fun apply(first:LocalDate,last:LocalDate):MapSelection {
        require(error(first,last)==null)
        return MapSelection("Custom",first,last,edit(first,last).resolved())
    }
}
