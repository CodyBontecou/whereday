package com.bontecou.whereday

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import com.bontecou.whereday.domain.Confirmation
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable internal fun timelineRange(start:Instant,end:Instant?,openLabel:Int):String {
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val zone=ZoneId.systemDefault()
    val formatter=remember(locale,zone) {DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,FormatStyle.SHORT).withLocale(locale).withZone(zone)}
    return stringResource(R.string.timeline_time_range,formatter.format(start),end?.let(formatter::format) ?: stringResource(openLabel))
}

@Composable internal fun confirmationLabel(value:Confirmation)=stringResource(when(value) {
    Confirmation.unconfirmed->R.string.timeline_unconfirmed
    Confirmation.confirmed->R.string.timeline_confirmed
    Confirmation.corrected->R.string.timeline_corrected
})

/** The clock format people chose: the system 12/24-hour setting in the UI locale ("11:47 AM" / "11:47"), as the time pickers use. */
@Composable internal fun rememberClockFormatter():DateTimeFormatter {
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val clock24=android.text.format.DateFormat.is24HourFormat(LocalContext.current)
    val zone=ZoneId.systemDefault()
    return remember(locale,zone,clock24) {
        DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale,if(clock24) "Hm" else "hm"),locale).withZone(zone)
    }
}

/** The mono clock shown in the journal gutter, as in iOS TimelineRowFormat.clock. */
@Composable internal fun journalClock(instant:Instant):String=rememberClockFormatter().format(instant)

/**
 * A gutter wide enough for any time [formatter] can print in [style] at the current font scale, so "11:47 AM" neither wraps
 * nor clips. Samples cover one- and two-digit hours on both halves of the day; [minimum] keeps the column from collapsing.
 */
@Composable internal fun clockColumnWidth(formatter:DateTimeFormatter,style:TextStyle,minimum:Dp=0.dp):Dp {
    val measurer=rememberTextMeasurer()
    val density=LocalDensity.current
    val samples=remember(formatter) {
        val zone=formatter.zone ?: ZoneId.systemDefault()
        listOf(0 to 0,8 to 58,10 to 48,12 to 58,20 to 8,22 to 58,23 to 58).map {(hour,minute)->formatter.format(java.time.LocalDate.of(2024,1,1).atTime(hour,minute).atZone(zone))}
    }
    val widest=remember(samples,style,density,measurer) {samples.maxOf {measurer.measure(it,style,softWrap=false,maxLines=1).size.width}}
    return maxOf(minimum,with(density) {widest.toDp()}+2.dp)
}

/** How long a stay lasted, as in iOS TimelineRowFormat.duration. */
@Composable internal fun journalDuration(start:Instant,end:Instant):String {
    val minutes=java.time.Duration.between(start,end).toMinutes().coerceAtLeast(0)
    return if(minutes<60) stringResource(R.string.timeline_redesign_minutes,minutes.toInt())
    else stringResource(R.string.timeline_redesign_hours,(minutes/60).toInt(),"%02d".format((minutes%60).toInt()))
}

/** Stable per-place inks for one day: each distinct place takes the next ink in turn (iOS placeInks(for:)). */
internal fun <T> journalInkIndex(names:List<T>):Map<T,Int> {
    val inks=LinkedHashMap<T,Int>()
    names.forEach {if(it !in inks) inks[it]=inks.size}
    return inks
}
