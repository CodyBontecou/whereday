package com.bontecou.whereday

import androidx.lifecycle.compose.collectAsStateWithLifecycle

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.clipRect
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.repeatOnLifecycle
import com.bontecou.whereday.data.CountryDayReport
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import java.text.NumberFormat
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.TextStyle
import java.util.Locale
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext

/** Country inks from the iOS `CountryAtlas.inks`; the first belongs to Home's country (or the all-time leader). */
private val atlasLight = listOf(0xFF1E2B45, 0xFFC2462A, 0xFFB7862B, 0xFF5E7F5A, 0xFF7A86A8, 0xFF7D4E6B, 0xFF3E7C7D, 0xFF8A5A3B, 0xFF6E7232, 0xFFB0546A, 0xFF2F5D4E, 0xFF4B4F8F).map { Color(it) }
private val atlasNight = listOf(0xFFC9D3EC, 0xFFF0805F, 0xFFE0B565, 0xFF9DC097, 0xFF8E9BC2, 0xFFC99BB9, 0xFF86C3C1, 0xFFD3A27E, 0xFFC4C77F, 0xFFE995A8, 0xFF7FB6A1, 0xFFA2A6E2).map { Color(it) }
@Composable private fun atlasInk(index: Int) = (if(LocalWheredayInk.current.dark) atlasNight else atlasLight)[Math.floorMod(index, CountryAtlas.INKS)]

/** Atlas: a year of days coloured by country, the countries list and the way into Country days. */
/** One civil day's visits in order and distance moved, for the two-pane selected-day card. */
internal data class AtlasDayDetail(val day: LocalDate, val visitNames: List<String>, val distanceMeters: Double)

@Composable internal fun AtlasScreen(graph: AppGraph, metric: Boolean = true) {
    var countryRoute by rememberSaveable { mutableStateOf<String?>(null) }
    var openCountryDays by rememberSaveable { mutableStateOf(false) }
    var recapOpen by rememberSaveable { mutableStateOf(false) }
    var selectedDay by rememberSaveable { mutableStateOf<String?>(null) }
    if(recapOpen) { MonthRecapScreen(graph, metric) { recapOpen = false }; return }
    if(openCountryDays || countryRoute != null) {
        CountryDaysScreen(graph, initialCountry = countryRoute, backLabel = R.string.atlas_back) { openCountryDays = false; countryRoute = null }
        return
    }
    var report by remember { mutableStateOf<CountryDayReport?>(null) }
    var failure by remember { mutableStateOf(false) }
    var retry by remember { mutableIntStateOf(0) }
    var today by remember { mutableStateOf(LocalDate.now()) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(lifecycle) { lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) { while(true) { today = LocalDate.now(); delay(30_000) } } }
    LaunchedEffect(retry, lifecycle) {
        failure = false
        lifecycle.repeatOnLifecycle(Lifecycle.State.STARTED) {
            try { withContext(Dispatchers.IO) { graph.history.observeCountryDays(CountryDayPeriod.all.bounds(today)).collect { report = it } } }
            catch(e: Exception) { if(e is CancellationException) throw e; failure = true }
        }
    }
    val zone = remember { java.time.ZoneId.systemDefault() }
    val shownDay = selectedDay?.let(LocalDate::parse) ?: today
    val unnamed = stringResource(R.string.timeline_visit)
    val window by remember(shownDay) { graph.history.observeWindow(HistoryRange.day(shownDay, zone), includeOutliers = false) }.collectAsStateWithLifecycle(null)
    val detail = window?.takeIf { it.range == HistoryRange.day(shownDay, zone) }?.let { current ->
        val names = mutableListOf<String>()
        current.visits.sortedBy { it.arrivedAt }.forEach { visit ->
            val label = visit.name?.trim()?.takeIf(String::isNotEmpty) ?: visit.address?.trim()?.takeIf(String::isNotEmpty) ?: unnamed
            if(names.lastOrNull() != label) names += label
        }
        AtlasDayDetail(shownDay, names.take(4), current.summary.distanceMeters)
    }
    AtlasContent(report?.days, today, failure, { retry++ }, onCountry = { countryRoute = it }, onCountryDays = { openCountryDays = true },
        onRecap = { recapOpen = true }, selectedDay = selectedDay?.let(LocalDate::parse), onSelectDay = { selectedDay = it.toString() }, dayDetail = detail, metric = metric)
}

@Composable internal fun AtlasContent(days: List<CountryPresence>?, today: LocalDate, failure: Boolean, onRetry: () -> Unit, onCountry: (String) -> Unit, onCountryDays: () -> Unit,
    onRecap: () -> Unit = {}, selectedDay: LocalDate? = null, onSelectDay: (LocalDate) -> Unit = {}, dayDetail: AtlasDayDetail? = null, metric: Boolean = true) {
    val ink = LocalWheredayInk.current
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val number = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    var allYears by rememberSaveable { mutableStateOf(false) }
    var year by rememberSaveable { mutableIntStateOf(today.year) }
    val name: (String) -> String = { CountryRegions.displayName(it, locale) }
    val presences = days.orEmpty()
    val atlas = remember(presences, year, allYears, locale) { CountryAtlas(presences, if(allYears) null else year, name = name) }
    val years = remember(presences, today) { CountryAtlas.years(presences, today) }
    val large = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.6f
    BoxWithConstraints(Modifier.fillMaxSize().background(ink.surface).semantics { testTagsAsResourceId = true }.testTag("atlas-screen")) {
    val twoPane = maxWidth >= 600.dp
    val calendar: @Composable ColumnScope.() -> Unit = {
        // Header: kicker, year with stepper, Year/All range.
        val header: @Composable () -> Unit = {
            Column {
                Text(stringResource(R.string.atlas_kicker), style = WheredayType.mono(11, tracking = 1.5f), color = ink.textMuted)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(if(allYears) stringResource(R.string.atlas_all_years) else year.toString(),
                        Modifier.semantics { heading() }.testTag("atlas-year"), style = WheredayType.largeTitle, color = ink.textPrimary)
                    if(!allYears) {
                        val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                        val canBack = year > (years.firstOrNull() ?: year); val canForward = year < (years.lastOrNull() ?: year)
                        IconButton({ year-- }, enabled = canBack, modifier = Modifier.testTag("atlas-previous-year")) {
                            Icon(if(rtl) Icons.AutoMirrored.Outlined.KeyboardArrowRight else Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.atlas_previous_year), tint = ink.textMuted.copy(alpha = if(canBack) 1f else 0.35f))
                        }
                        IconButton({ year++ }, enabled = canForward, modifier = Modifier.testTag("atlas-next-year")) {
                            Icon(if(rtl) Icons.AutoMirrored.Outlined.KeyboardArrowLeft else Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.atlas_next_year), tint = ink.textMuted.copy(alpha = if(canForward) 1f else 0.35f))
                        }
                    }
                }
            }
        }
        val range: @Composable () -> Unit = {
            val label = stringResource(R.string.atlas_range)
            Row(Modifier.clip(RoundedCornerShape(50)).background(ink.card).border(1.dp, ink.border, RoundedCornerShape(50)).semantics { contentDescription = label; selectableGroup() }) {
                listOf(false to R.string.atlas_range_year, true to R.string.atlas_range_all).forEach { (value, title) ->
                    val selected = allYears == value
                    Box(Modifier.heightIn(min = 44.dp).background(if(selected) ink.accent else Color.Transparent)
                        .selectableRole(selected) { allYears = value }.padding(horizontal = 14.dp).testTag(if(value) "atlas-range-all" else "atlas-range-year"), contentAlignment = Alignment.Center) {
                        Text(stringResource(title), color = if(selected) ink.surface else ink.textPrimary, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.Medium)
                    }
                }
            }
        }
        if(large) Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) { header(); range() }
        else Row(Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp), verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(12.dp)) { Box(Modifier.weight(1f)) { header() }; range() }
        if(failure) Column(Modifier.padding(24.dp)) {
            Text(stringResource(R.string.atlas_load_error), color = MaterialTheme.colorScheme.error)
            TextButton(onRetry) { Text(stringResource(R.string.atlas_retry)) }
        }
        val gridYears = if(allYears) years.reversed() else listOf(year)
        gridYears.forEach { item ->
            Column(Modifier.padding(start = 24.dp, end = 24.dp, top = 18.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                if(allYears) Text(item.toString(), Modifier.semantics { heading() }, style = WheredayType.title3, color = ink.textPrimary)
                AtlasYearGrid(item, atlas, today, locale, number, if(twoPane) selectedDay ?: today else null, onTap = { day ->
                    if(twoPane) onSelectDay(day) else atlas.countriesByDay[day]?.firstOrNull()?.let { onCountry(it.countryCode) } ?: onCountryDays()
                })
            }
        }
        AtlasLegend(Modifier.padding(start = 56.dp, end = 24.dp, top = 8.dp))
    }
    val countries: @Composable ColumnScope.() -> Unit = {
        if(twoPane) {
            SelectedDayCard(selectedDay ?: today, atlas.countriesByDay[selectedDay ?: today].orEmpty(), dayDetail?.takeIf { it.day == (selectedDay ?: today) }, name, locale, metric,
                onOpen = { code -> onCountry(code) }, onAdd = onCountryDays, modifier = Modifier.padding(start = 16.dp, end = 16.dp, top = 16.dp))
            Text(stringResource(R.string.atlas_days_by_country), Modifier.padding(start = 24.dp, end = 24.dp, top = 20.dp).semantics { heading() }, style = WheredayType.mono(11, tracking = 1.5f), color = ink.textMuted)
        }
        if(days != null && atlas.summaries.isEmpty()) Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp).fillMaxWidth().background(ink.card, RoundedCornerShape(18.dp))
            .border(1.dp, ink.border, RoundedCornerShape(18.dp)).padding(16.dp).testTag("atlas-empty"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.atlas_empty_title), style = WheredayType.title3, color = ink.textPrimary)
            Text(stringResource(R.string.atlas_empty_body), style = MaterialTheme.typography.bodySmall, color = ink.textMuted)
            TEActionButton(stringResource(R.string.atlas_identify), onCountryDays)
        } else if(days != null) Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp).fillMaxWidth().background(ink.card, RoundedCornerShape(18.dp))
            .border(1.dp, ink.border, RoundedCornerShape(18.dp)).padding(horizontal = 16.dp).testTag("atlas-countries")) {
            atlas.summaries.forEachIndexed { index, summary ->
                CountryBarRow(name(summary.countryCode), pluralStringResource(R.plurals.atlas_country_days, summary.days.size, number.format(summary.days.size)),
                    summary.days.size / atlas.maximumDays.coerceAtLeast(1).toFloat(), atlasInk(atlas.ink(summary.countryCode))) { onCountry(summary.countryCode) }
                if(index < atlas.summaries.lastIndex) HorizontalDivider(color = ink.dialTrack)
            }
        }
        val gaps = remember(atlas, today) { atlas.gapCount(today) }
        Text(if(gaps == 0) stringResource(R.string.atlas_travel_note) else pluralStringResource(R.plurals.atlas_travel_gaps, gaps, number.format(gaps)),
            Modifier.padding(start = 24.dp, end = 24.dp, top = 12.dp).testTag("atlas-gaps"), style = WheredayType.caption2, color = ink.textMuted)
        val monthName = today.month.getDisplayName(TextStyle.FULL_STANDALONE, locale).replaceFirstChar { it.titlecase(locale) }
        Column(Modifier.padding(start = 16.dp, end = 16.dp, top = 18.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            TEActionButton(stringResource(R.string.atlas_recap, monthName), onRecap, Modifier.fillMaxWidth().testTag("atlas-recap"))
            TEActionButton(stringResource(R.string.atlas_country_days_action), onCountryDays, Modifier.fillMaxWidth().testTag("atlas-country-days"), TEEmphasis.primary)
        }
    }
    if(twoPane) Row(Modifier.fillMaxSize()) {
        Column(Modifier.weight(1f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp), content = calendar)
        Box(Modifier.width(1.dp).fillMaxHeight().background(ink.border))
        Column(Modifier.widthIn(max = 400.dp).weight(0.8f).fillMaxHeight().verticalScroll(rememberScrollState()).padding(bottom = 24.dp).testTag("atlas-side"), content = countries)
    } else Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(bottom = 24.dp)) { calendar(); countries() }
    }
}

/** Two-pane: the picked day (today until one is tapped) with its countries, visits and distance. */
@Composable private fun SelectedDayCard(day: LocalDate, presences: List<CountryPresence>, detail: AtlasDayDetail?, name: (String) -> String, locale: Locale, metric: Boolean,
    onOpen: (String) -> Unit, onAdd: () -> Unit, modifier: Modifier) {
    val ink = LocalWheredayInk.current
    val date = java.time.format.DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "EEEMMMd"), locale).format(day).uppercase(locale)
    Column(modifier.fillMaxWidth().background(ink.inkCard, RoundedCornerShape(18.dp)).padding(18.dp).testTag("atlas-selected-day"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.atlas_selected, date), style = WheredayType.mono(11, tracking = 1.2f), color = ink.onInkCardMuted, modifier = Modifier.testTag("atlas-selected-kicker"))
        Text(if(presences.isEmpty()) stringResource(R.string.atlas_no_record) else listFormat(presences.map { name(it.countryCode) }, locale),
            style = WheredayType.title2, color = ink.onInkCard, modifier = Modifier.testTag("atlas-selected-title"))
        val route = detail?.let { listOfNotNull(it.visitNames.takeIf { names -> names.isNotEmpty() }?.joinToString(" → "),
            it.distanceMeters.takeIf { meters -> meters >= 50 }?.let { meters -> todayDistance(meters, metric, locale) }).joinToString(" · ") }?.takeIf { it.isNotEmpty() }
        route?.let { Text(it, style = MaterialTheme.typography.bodySmall, color = ink.onInkCardMuted, modifier = Modifier.testTag("atlas-selected-route")) }
        val country = presences.firstOrNull { it.recorded } ?: presences.firstOrNull()
        Box(Modifier.padding(top = 6.dp).heightIn(min = 44.dp).clip(RoundedCornerShape(50)).background(ink.onInkCard)
            .clickable(role = Role.Button) { if(country == null) onAdd() else onOpen(country.countryCode) }.padding(horizontal = 16.dp).testTag("atlas-selected-action"), contentAlignment = Alignment.Center) {
            Text(stringResource(if(country == null) R.string.atlas_add_stay else R.string.atlas_open_day), color = ink.inkCard, style = MaterialTheme.typography.labelLarge, fontWeight = FontWeight.SemiBold)
        }
    }
}

/** "Portugal and Spain", in the reader's list style. */
private fun listFormat(items: List<String>, locale: Locale): String = android.icu.text.ListFormatter.getInstance(locale).format(items)

private fun Modifier.selectableRole(selected: Boolean, onClick: () -> Unit) = this.semantics { this.selected = selected }.clickable(role = Role.Tab, onClick = onClick)

/** Twelve rows of days. Each cell is filled with its country's ink; travel days split the cell. */
@Composable private fun AtlasYearGrid(year: Int, atlas: CountryAtlas, today: LocalDate, locale: Locale, number: NumberFormat, selected: LocalDate?, onTap: (LocalDate) -> Unit) {
    val ink = LocalWheredayInk.current
    val inks = (0 until CountryAtlas.INKS).map { atlasInk(it) }
    val gridLabel = stringResource(R.string.atlas_grid)
    Column(Modifier.semantics { contentDescription = gridLabel }.testTag("atlas-grid-$year"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        (1..12).forEach { month ->
            val month0 = YearMonth.of(year, month)
            val days = (1..month0.lengthOfMonth()).map { month0.atDay(it) }
            val summary = monthSummary(days, atlas, today, locale, number)
            val monthName = month0.month.getDisplayName(TextStyle.FULL_STANDALONE, locale)
            Row(Modifier.semantics(mergeDescendants = true) { contentDescription = monthName; stateDescription = summary }.testTag("atlas-month-$year-$month"), verticalAlignment = Alignment.CenterVertically) {
                Text(month0.month.getDisplayName(TextStyle.SHORT_STANDALONE, locale).uppercase(locale).take(4), Modifier.width(32.dp).clearAndSetSemantics {}, style = WheredayType.caption2, color = ink.textMuted, maxLines = 1)
                val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
                Canvas(Modifier.weight(1f).height(12.dp).pointerInput(days) {
                    detectTapGestures { offset ->
                        // Every month shares 31 slots, so short months leave trailing space.
                        val x = if(rtl) size.width - offset.x else offset.x
                        val slot = (x / ((size.width + 2.dp.toPx()) / 31)).toInt()
                        days.getOrNull(slot)?.takeIf { it <= today }?.let(onTap)
                    }
                }) {
                    val gap = 2.dp.toPx()
                    val width = (size.width - gap * 30) / 31
                    val corner = CornerRadius(2.dp.toPx())
                    days.forEachIndexed { index, day ->
                        val left = index * (width + gap)
                        val x = if(rtl) size.width - left - width else left
                        val topLeft = Offset(x, 0f); val cell = Size(width, size.height)
                        val presences = atlas.countriesByDay[day].orEmpty()
                        when {
                            day > today -> drawRoundRect(ink.dialTick.copy(alpha = 0.7f), topLeft + Offset(0.5f, 0.5f), Size(width - 1, size.height - 1), corner, Stroke(1.dp.toPx()))
                            presences.isEmpty() -> drawRoundRect(ink.dialTrack, topLeft, cell, corner)
                            else -> {
                                val recorded = presences.filter { it.recorded }
                                if(recorded.isEmpty()) {
                                    // Manual-only days are outlined so filled cells always mean a recording.
                                    drawRoundRect(ink.surface, topLeft, cell, corner)
                                    drawRoundRect(inks[atlas.ink(presences[0].countryCode)], topLeft + Offset(0.75f, 0.75f), Size(width - 1.5f, size.height - 1.5f), corner, Stroke(1.5.dp.toPx()))
                                } else clipRect(x, 0f, x + width, size.height) {
                                    val band = size.height / recorded.size
                                    recorded.forEachIndexed { i, presence -> drawRect(inks[atlas.ink(presence.countryCode)], Offset(x, band * i), Size(width, band)) }
                                }
                            }
                        }
                        // Two-pane: the picked day is ringed in rust.
                        if(day == selected) drawRoundRect(ink.inkRust, topLeft - Offset(1.dp.toPx(), 1.dp.toPx()), Size(width + 2.dp.toPx(), size.height + 2.dp.toPx()), corner, Stroke(1.5.dp.toPx()))
                    }
                }
            }
        }
    }
}

@Composable private fun monthSummary(days: List<LocalDate>, atlas: CountryAtlas, today: LocalDate, locale: Locale, number: NumberFormat): String {
    val counts = LinkedHashMap<String, Int>()
    var empty = 0
    days.filter { it <= today }.forEach { day ->
        val presences = atlas.countriesByDay[day].orEmpty()
        if(presences.isEmpty()) empty++
        presences.forEach { counts[it.countryCode] = (counts[it.countryCode] ?: 0) + 1 }
    }
    val parts = counts.entries.sortedByDescending { it.value }.map { (code, count) ->
        pluralStringResource(R.plurals.atlas_month_country, count, CountryRegions.displayName(code, locale), number.format(count))
    }.toMutableList()
    if(empty > 0) parts += pluralStringResource(R.plurals.atlas_month_empty, empty, number.format(empty))
    return if(parts.isEmpty()) stringResource(R.string.atlas_month_ahead) else parts.joinToString("; ")
}

@Composable private fun AtlasLegend(modifier: Modifier = Modifier) {
    val ink = LocalWheredayInk.current
    @OptIn(ExperimentalLayoutApi::class)
    FlowRow(modifier.clearAndSetSemantics {}, horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        @Composable fun item(label: Int, swatch: androidx.compose.ui.graphics.drawscope.DrawScope.() -> Unit) = Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(5.dp)) {
            Canvas(Modifier.size(8.dp), onDraw = swatch)
            Text(stringResource(label), style = WheredayType.caption2, color = ink.textMuted)
        }
        val corner = CornerRadius(2f * 2)
        item(R.string.atlas_legend_none) { drawRoundRect(ink.dialTrack, cornerRadius = corner) }
        item(R.string.atlas_legend_manual) { drawRoundRect(ink.accent, cornerRadius = corner, style = Stroke(1.5.dp.toPx())) }
        val first = atlasInk(0); val second = atlasInk(1)
        item(R.string.atlas_legend_travel) { drawRect(first, size = Size(size.width, size.height / 2)); drawRect(second, Offset(0f, size.height / 2), Size(size.width, size.height / 2)) }
        item(R.string.atlas_legend_ahead) { drawRoundRect(ink.dialTick, cornerRadius = corner, style = Stroke(1.dp.toPx())) }
    }
}

@Composable private fun CountryBarRow(name: String, count: String, fraction: Float, color: Color, onClick: () -> Unit) {
    val ink = LocalWheredayInk.current
    Column(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button, onClick = onClick).padding(vertical = 10.dp).semantics(mergeDescendants = true) {}.testTag("atlas-country"),
        verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(name, Modifier.weight(1f), style = WheredayType.title3, color = ink.textPrimary)
            Text(count, style = WheredayType.mono(13), color = ink.textPrimary)
        }
        Box(Modifier.fillMaxWidth().height(6.dp).clip(RoundedCornerShape(50)).background(ink.dialTrack).clearAndSetSemantics {}) {
            Box(Modifier.fillMaxWidth(fraction.coerceIn(0.02f, 1f)).fillMaxHeight().clip(RoundedCornerShape(50)).background(color))
        }
    }
}
