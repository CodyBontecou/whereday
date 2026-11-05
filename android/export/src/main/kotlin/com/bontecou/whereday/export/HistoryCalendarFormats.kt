package com.bontecou.whereday.export

import java.time.ZoneId
import java.time.chrono.Chronology
import java.time.chrono.IsoChronology
import java.time.chrono.ThaiBuddhistChronology
import java.time.format.*
import java.util.Locale

/** Local display dates share a calendar and digit system on export and reimport.
 * The punctuation adaptations bridge the checked Foundation/JDK CLDR patterns; they do
 * not affect ISO timestamps, coordinates, audit metadata or numeric measurement columns.
 */
internal class HistoryCalendarFormats(val locale:Locale,private val zone:ZoneId) {
    private val chronology=when {
        locale.getUnicodeLocaleType("ca") in setOf("gregory","iso8601") -> IsoChronology.INSTANCE
        locale.language=="th" && locale.country=="TH" && locale.getUnicodeLocaleType("ca")==null -> ThaiBuddhistChronology.INSTANCE
        else -> Chronology.ofLocale(locale)
    }
    private val decimals=DecimalStyle.of(locale)
    fun pattern(date:FormatStyle?,time:FormatStyle?,foundation:Boolean=true):String {
        var pattern=DateTimeFormatterBuilder.getLocalizedDateTimePattern(date,time,chronology,locale)
        if(foundation && date==FormatStyle.FULL && time==null) {
            if(locale.language=="en" && locale.country=="GB" && chronology==IsoChronology.INSTANCE) pattern="EEEE, d MMMM y"
            if(locale.language=="ar") pattern=pattern.replace("MMMM y","MMMM، y")
            if(locale.language=="ja") pattern=pattern.replace("d日EEEE","d日 EEEE")
        }
        if(foundation && locale.language=="th" && time==FormatStyle.SHORT) pattern=pattern.removeSuffix(" 'น.'").removeSuffix(" น.")
        if(time!=null) pattern=hourCycle(pattern)
        return pattern
    }
    private fun hourCycle(pattern:String):String {
        val cycle=locale.getUnicodeLocaleType("hc") ?: return pattern
        val hour=when(cycle) {"h23"->"H";"h24"->"k";"h11"->"K";"h12"->"h";else->return pattern}
        val twelve=cycle in setOf("h11","h12")
        // Transform only field runs outside quoted literals. Preserve language-specific date text.
        var quoted=false;var index=0;var period=false
        val result=buildString {
            while(index<pattern.length) {
                val c=pattern[index]
                if(c=='\'') {append(c);quoted=!quoted;index++;continue}
                if(!quoted && c in "HhKkaB") {
                    val start=index
                    while(index<pattern.length && pattern[index]==c) index++
                    if(c in "HhKk") append(if(twelve) hour else hour.repeat(if(c in "Hk") index-start else 2))
                    else if(twelve) {append(c);period=true}
                } else {append(c);index++}
            }
        }.trimEnd()
        return if(twelve && !period) {
            if(locale.language in setOf("ja","zh","ko")) "a $result" else "$result a"
        } else result
    }
    fun formatter(date:FormatStyle?,time:FormatStyle?,foundation:Boolean=true):DateTimeFormatter=
        DateTimeFormatter.ofPattern(pattern(date,time,foundation),locale).withChronology(chronology).withDecimalStyle(decimals).withZone(zone)
    private val dates by lazy {FormatStyle.entries.associateWith {formatter(it,null)}}
    private val times by lazy {FormatStyle.entries.associateWith {formatter(null,it)}}
    private val dateTimes=mutableMapOf<Pair<FormatStyle,FormatStyle>,DateTimeFormatter>()
    fun date(at:java.time.Instant,style:FormatStyle)=dates.getValue(style).format(at)
    fun time(at:java.time.Instant,style:FormatStyle):String {
        val result=times.getValue(style).format(at)
        return when(locale.language) {
            "en" -> result.replace(englishPeriodSpace,"\u202f")
            "ar" -> result.replace(arabicPeriodSpace,"\u00a0")
            else -> result
        }
    }
    fun dateTime(at:java.time.Instant,dateStyle:FormatStyle,timeStyle:FormatStyle):String =when {
        locale.language=="en" -> date(at,dateStyle)+" at "+time(at,timeStyle)
        locale.language=="fr" -> date(at,dateStyle)+" à "+time(at,timeStyle)
        locale.language=="ar" -> date(at,dateStyle)+"، "+time(at,timeStyle)
        locale.language=="de" && dateStyle==FormatStyle.LONG -> date(at,dateStyle)+" um "+time(at,timeStyle)
        else -> dateTimes.getOrPut(dateStyle to timeStyle) {formatter(dateStyle,timeStyle)}.format(at)
    }
    companion object {
        private val englishPeriodSpace=Regex(" (?=[AaPp][Mm]$)")
        private val arabicPeriodSpace=Regex(" (?=[^ ]+$)")
    }
    fun parser(date:FormatStyle?,time:FormatStyle?,foundation:Boolean):DateTimeFormatter {
        val pattern=pattern(date,time,foundation).replace('\u00a0',' ').replace('\u202f',' ')
        return DateTimeFormatterBuilder().parseCaseInsensitive().appendPattern(pattern)
            .parseDefaulting(java.time.temporal.ChronoField.ERA,1).toFormatter(locale).withChronology(chronology).withDecimalStyle(decimals).withResolverStyle(ResolverStyle.STRICT)
    }
}
