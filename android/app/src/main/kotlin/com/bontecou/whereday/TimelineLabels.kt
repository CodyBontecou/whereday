package com.bontecou.whereday

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
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

/** The mono clock shown in the journal gutter, as in iOS TimelineRowFormat.clock. */
@Composable internal fun journalClock(instant:Instant):String {
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val zone=ZoneId.systemDefault()
    val formatter=remember(locale,zone) {DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).withZone(zone)}
    return formatter.format(instant)
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
