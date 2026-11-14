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
