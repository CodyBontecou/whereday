package com.bontecou.whereday

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import com.bontecou.whereday.data.PhotoLibraryEntry
import com.bontecou.whereday.platform.PhotoLibraryAccess
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

@Composable internal fun photoCaptureTime(value:Instant?):String {
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val zone=ZoneId.systemDefault()
    val format=remember(locale,zone) {DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,FormatStyle.SHORT).withLocale(locale).withZone(zone)}
    return value?.let(format::format) ?: stringResource(R.string.photo_capture_unavailable)
}

/** Wire keys and availability are unchanged; only the presentation is translated. */
@Composable internal fun photoSourceLabel(entry:PhotoLibraryEntry)=stringResource(
    if(!entry.asset.available) R.string.photo_unavailable_access else when(entry.coordinateSource) {
        "photo_gps"->R.string.photo_source_gps
        "inferred_route"->R.string.photo_source_route
        "inferred_visit"->R.string.photo_source_visit
        else->R.string.photo_source_none
    }
)

@Composable internal fun photoLibraryAccessLabel(access:PhotoLibraryAccess)=stringResource(when(access) {
    PhotoLibraryAccess.None->R.string.photo_access_none
    PhotoLibraryAccess.Partial->R.string.photo_access_partial
    PhotoLibraryAccess.Full->R.string.photo_access_full
})
