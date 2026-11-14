package com.bontecou.whereday

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.KeyboardArrowRight
import androidx.compose.material.icons.outlined.CalendarMonth
import androidx.compose.material.icons.outlined.Download
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.*
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import kotlin.math.cos
import kotlin.math.sin
import kotlinx.coroutines.delay

/** How much of the day Whereday can keep, adapted from iOS location authorization to Android's recording modes. */
internal enum class TodayListening { full, idle, limited, off;
    companion object {
        fun of(access: RecordingAccessState?, recording: Boolean, visitsOnly: Boolean) = when {
            access?.location == LocationPrecision.none -> off
            access?.location == LocationPrecision.approximate -> limited
            recording || visitsOnly -> full
            else -> idle
        }
    }
}

/** Live inputs for Today, independent of the database so previews and tests can supply them directly. */
internal data class TodayInputs(val now: Instant, val zone: ZoneId, val day: TodayDay, val distance: Double, val points: Long,
    val firstRecorded: Instant?, val listening: TodayListening, val recordingSince: Instant?, val lastDay: Pair<LocalDate, List<TodayDay.Stay>>? = null,
    /** True once a saved place is Home: days start there and Away counts time anywhere else. */
    val hasHome: Boolean = false)

@Composable internal fun TodayScreen(graph: AppGraph, metric: Boolean, access: RecordingAccessState?, onReplay: () -> Unit, onPickDay: () -> Unit,
    onOpenVisit: (Visit) -> Unit, onImportStarted: () -> Unit, onAccess: (TodayListening) -> Unit, onMoments: () -> Unit, onLogStop: () -> Unit, onRecord: () -> Unit = {},
    onSaveHome: (Coordinate?) -> Unit = {}, onAccessOptions: (PermissionAccessKind) -> Unit = {}) {
    val zone = remember { ZoneId.systemDefault() }
    var now by remember { mutableStateOf(Instant.now()) }
    LaunchedEffect(Unit) { while(true) { delay(60_000 - System.currentTimeMillis() % 60_000); now = Instant.now() } }
    val today = now.atZone(zone).toLocalDate()
    val window by remember(today) { graph.history.observeWindow(HistoryRange.day(today, zone), includeOutliers = false) }.collectAsStateWithLifecycle(null)
    val firstRecorded by remember { graph.history.dao.observeFirstRecorded() }.collectAsStateWithLifecycle(null)
    val recording by graph.history.dao.observeRecording().collectAsStateWithLifecycle(null)
    val places by graph.history.dao.observePlaces().collectAsStateWithLifecycle(emptyList())
    // As on iOS, the first saved place marked Home is the one days start at.
    val home = places.firstOrNull { it.value.kind == SavedPlaceKind.home }?.value
    val active by RecordingRuntime.active.collectAsStateWithLifecycle()
    val visitsOnly by VisitMonitoringRuntime.state.collectAsStateWithLifecycle()
    val listening = TodayListening.of(access, active, visitsOnly == VisitMonitoringMode.balanced || visitsOnly == VisitMonitoringMode.route)
    val unnamed = stringResource(R.string.timeline_visit)
    val context = LocalContext.current
    val importer = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if(uri != null) { graph.historyImports.select(AndroidHistoryImportInput(context, uri), locale = AndroidHistoryLocale.capture(context)); onImportStarted() }
    }
    val current = window
    val name: (Visit) -> String = { it.name?.trim()?.takeIf(String::isNotEmpty) ?: it.address?.trim()?.takeIf(String::isNotEmpty) ?: unnamed }
    // Previous day stands in when location is off and nothing was kept today.
    val earlierDay = remember(today) { if(today.year > 1) today.minusDays(1) else today }
    val previous by remember(today, listening) {
        graph.history.observeWindow(HistoryRange.days(earlierDay.minusDays(29), earlierDay, zone), includeOutliers = false)
    }.collectAsStateWithLifecycle(null)
    if(current == null) { Box(Modifier.fillMaxSize().background(LocalWheredayInk.current.surface)); return }
    val day = remember(current, now, home) {
        TodayDay.build(current.visits, current.points, now, zone, name, home, marksUnrecordedGaps = listening == TodayListening.idle || listening == TodayListening.limited)
    }
    val lastDay = remember(previous, now) { previous?.let { lastRecordedDay(it.visits, now, zone, name) } }
    TodayContent(TodayInputs(now, zone, day, current.summary.distanceMeters, current.summary.count, firstRecorded, listening,
        recording?.sessionId?.takeIf { active }?.let { id -> current.sessions.firstOrNull { it.id == id }?.startedAt }, lastDay, home != null), metric, onReplay, onPickDay, onOpenVisit,
        onImport = { importer.launch(arrayOf("application/json", "text/*", "application/octet-stream")) }, onAccess = onAccess, onMoments = onMoments, onLogStop = onLogStop, recordingActive = active, onRecord = onRecord,
        onSaveHome = { onSaveHome(current.points.lastOrNull { !it.outlier }?.coordinate) },
        accessNotice = access?.let { state -> { TodayRecordingNotice(state, onAccessOptions) } })
}

/** The most recent earlier day with any stays, newest stay first. */
internal fun lastRecordedDay(visits: List<Visit>, now: Instant, zone: ZoneId, name: (Visit) -> String): Pair<LocalDate, List<TodayDay.Stay>>? {
    val startOfToday = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
    val earlier = visits.filter { it.arrivedAt < startOfToday }
    val latest = earlier.maxByOrNull { it.arrivedAt } ?: return null
    val day = latest.arrivedAt.atZone(zone).toLocalDate()
    val inks = LinkedHashMap<String, Int>()
    val stays = earlier.filter { it.arrivedAt.atZone(zone).toLocalDate() == day }.sortedBy { it.arrivedAt }.mapNotNull { visit ->
        val end = minOf(visit.departedAt ?: now, now)
        if(end <= visit.arrivedAt) null else name(visit).let { label ->
            TodayDay.Stay(visit, label, visit.arrivedAt, end, false, inks.getOrPut(label) { inks.size % TodayDay.INKS })
        }
    }
    return if(stays.isEmpty()) null else day to stays.reversed()
}

@Composable internal fun TodayContent(inputs: TodayInputs, metric: Boolean, onReplay: () -> Unit, onPickDay: () -> Unit, onOpenVisit: (Visit) -> Unit,
    onImport: () -> Unit, onAccess: (TodayListening) -> Unit, onMoments: () -> Unit, onLogStop: () -> Unit, modifier: Modifier = Modifier,
    recordingActive: Boolean = false, onRecord: (() -> Unit)? = null, onSaveHome: () -> Unit = {}, accessNotice: (@Composable () -> Unit)? = null) {
    val ink = LocalWheredayInk.current
    val large = androidx.compose.ui.platform.LocalDensity.current.fontScale >= 1.6f
    val day = inputs.day
    Column(modifier.fillMaxSize().background(ink.surface).verticalScroll(rememberScrollState()).padding(bottom = 24.dp).semantics { testTagsAsResourceId = true }.testTag("today-screen")) {
        TodayHeader(inputs, large, onPickDay, onMoments)
        // Android routes are user-started, so Today keeps the recording control within reach.
        onRecord?.let { record -> TEActionButton(stringResource(if(recordingActive) R.string.shortcut_stop else R.string.shortcut_start), record,
            Modifier.padding(start = 16.dp, end = 16.dp, top = 14.dp).testTag("today-record"), if(recordingActive) TEEmphasis.destructive else TEEmphasis.primary) }
        accessNotice?.invoke()
        if(inputs.listening != TodayListening.full) TodayAccessCard(inputs.listening, onAccess, Modifier.padding(top = 18.dp))
        when {
            large -> {
                LargeTextOverview(inputs, metric, onReplay, Modifier.padding(top = 18.dp))
                DaySoFar(inputs, metric, true, onOpenVisit, onLogStop, Modifier.padding(top = 24.dp))
            }
            day.stays.isEmpty() && inputs.listening == TodayListening.full -> BlankPage(inputs, onImport, onSaveHome, Modifier.padding(top = 26.dp))
            else -> {
                if(inputs.listening != TodayListening.off) Overview(inputs, metric, onReplay, Modifier.padding(top = 18.dp, bottom = 8.dp))
                day.currentStay?.let { CurrentPlaceCard(it, onOpenVisit, Modifier.padding(top = 6.dp)) }
                DaySoFar(inputs, metric, false, onOpenVisit, onLogStop, Modifier.padding(top = 20.dp))
            }
        }
    }
}

@Composable private fun TodayHeader(inputs: TodayInputs, large: Boolean, onPickDay: () -> Unit, onMoments: () -> Unit) {
    val ink = LocalWheredayInk.current
    val locale = Locale.getDefault()
    val date = inputs.now.atZone(inputs.zone)
    val weekday = DateTimeFormatter.ofPattern("EEE", locale).format(date)
    val monthDay = DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd"), locale).format(date)
    // Day N counts from the first recorded day; a journal with nothing in it yet is on Day 1.
    val dayNumber = inputs.firstRecorded?.let { maxOf(1L, Duration.between(it.atZone(inputs.zone).toLocalDate().atStartOfDay(), date.toLocalDate().atStartOfDay()).toDays() + 1) } ?: 1L
    val caption = if(large) stringResource(R.string.today_caption_short, weekday, monthDay) else stringResource(R.string.today_caption, weekday, monthDay, NumberFormat.getIntegerInstance(locale).format(dayNumber))
    Row(Modifier.fillMaxWidth().padding(start = 24.dp, end = 24.dp, top = 12.dp), verticalAlignment = Alignment.Bottom) {
        Column(Modifier.weight(1f)) {
            Text(caption.uppercase(locale), style = WheredayType.mono(11, tracking = 1.5f), color = ink.textMuted)
            Text(stringResource(R.string.today_title), Modifier.semantics { heading() }, style = WheredayType.largeTitle, color = ink.textPrimary)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val momentsHint = stringResource(R.string.today_moments_hint)
            RoundIconButton(Icons.Outlined.PhotoLibrary, stringResource(R.string.today_moments), onMoments, Modifier.semantics { stateDescription = momentsHint }.testTag("today-moments"))
            RoundIconButton(Icons.Outlined.CalendarMonth, stringResource(R.string.today_pick_day), onPickDay, Modifier.testTag("today-pick-day"))
        }
    }
}

@Composable private fun RoundIconButton(icon: androidx.compose.ui.graphics.vector.ImageVector, label: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    val ink = LocalWheredayInk.current
    Box(modifier.size(48.dp).background(ink.card, CircleShape).border(1.dp, ink.border, CircleShape).clickable(onClickLabel = label, role = Role.Button, onClick = onClick)
        .semantics { contentDescription = label }, contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = ink.textPrimary)
    }
}

@Composable private fun TodayAccessCard(listening: TodayListening, onAccess: (TodayListening) -> Unit, modifier: Modifier = Modifier) {
    val ink = LocalWheredayInk.current
    val (kicker, title, body, action) = when(listening) {
        TodayListening.off -> listOf(R.string.today_access_off_kicker, R.string.today_access_off_title, R.string.today_access_off_body, R.string.today_access_allow)
        TodayListening.limited -> listOf(R.string.today_access_limited_kicker, R.string.today_access_limited_title, R.string.today_access_limited_body, R.string.today_access_settings)
        else -> listOf(R.string.today_access_idle_kicker, R.string.today_access_idle_title, R.string.today_access_idle_body, R.string.today_access_visits_only)
    }
    Column(modifier.padding(horizontal = 16.dp).fillMaxWidth().background(ink.card, RoundedCornerShape(18.dp)).border(1.5.dp, ink.placeInk(1), RoundedCornerShape(18.dp))
        .padding(18.dp).testTag("today-access"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Icon(Icons.Outlined.Shield, null, tint = ink.placeInk(1))
            Text(stringResource(kicker), style = WheredayType.mono(11, tracking = 1.2f), color = ink.inkRust)
        }
        Text(stringResource(title), style = WheredayType.title2, color = ink.textPrimary)
        Text(stringResource(body), style = MaterialTheme.typography.bodyMedium, color = ink.textMuted)
        TEActionButton(stringResource(action), { onAccess(listening) }, Modifier.padding(top = 4.dp).testTag("today-access-action"), TEEmphasis.primary)
    }
}

/** A 24-hour ring, midnight at the top, with each stay drawn as an arc in its place's ink. */
@Composable internal fun DayDial(segments: List<TodayDay.Segment>, now: Instant, startOfDay: Instant, zone: ZoneId, modifier: Modifier = Modifier,
    recordingSince: Instant? = null, captions: DialCaptions = DialCaptions.full) {
    val ink = LocalWheredayInk.current
    val fraction = TodayDay.dayFraction(now, startOfDay)
    val since = recordingSince?.let { TodayDay.dayFraction(maxOf(it, startOfDay), startOfDay) }
    BoxWithConstraints(modifier.clearAndSetSemantics {}, contentAlignment = Alignment.Center) {
        val size = minOf(maxWidth, maxHeight)
        val density = LocalDensity.current
        Canvas(Modifier.size(size)) {
            val side = this.size.minDimension
            val line = side * 0.07f
            val inset = side * 0.06f
            val radius = side / 2 - inset
            val arcSize = Size(radius * 2, radius * 2)
            val topLeft = Offset(center.x - radius, center.y - radius)
            drawCircle(ink.dialTrack, radius, center, style = Stroke(line))
            segments.forEach { segment ->
                val sweep = ((segment.end - segment.start) * 360).toFloat()
                if(sweep > 0) drawArc(ink.placeInk(segment.ink), (segment.start * 360 - 90).toFloat(), sweep, false, topLeft, arcSize, style = Stroke(line, cap = StrokeCap.Butt))
            }
            if(since != null && fraction > since) drawArc(ink.textPrimary.copy(alpha = 0.5f), (since * 360 - 90).toFloat(), ((fraction - since) * 360).toFloat(), false, topLeft, arcSize,
                style = Stroke(line, cap = StrokeCap.Round, pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, line * 1.4f))))
            repeat(4) { quarter ->
                val angle = Math.toRadians(quarter * 90.0 - 90)
                val outer = side / 2 - side * 0.01f
                val inner = outer - side * 0.03f
                drawLine(ink.dialTick, Offset(center.x + cos(angle).toFloat() * inner, center.y + sin(angle).toFloat() * inner),
                    Offset(center.x + cos(angle).toFloat() * outer, center.y + sin(angle).toFloat() * outer), strokeWidth = with(density) { 1.dp.toPx() })
            }
            val angle = Math.toRadians(fraction * 360 - 90)
            val marker = side * 0.09f
            val markerCenter = Offset(center.x + cos(angle).toFloat() * radius, center.y + sin(angle).toFloat() * radius)
            drawCircle(ink.surface, marker / 2, markerCenter)
            val ring = with(density) { 2.5.dp.toPx() }
            drawCircle(ink.inkRust, marker / 2 - ring / 2, markerCenter, style = Stroke(ring))
            drawCircle(ink.inkRust, marker * 0.19f, markerCenter)
        }
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            if(captions != DialCaptions.timeOnly || recordingSince != null)
                Text(stringResource(if(recordingSince == null) R.string.today_now else R.string.today_listening_since), style = WheredayType.mono(11, tracking = 1f), color = ink.textMuted)
            // Sized to the ring, not the font scale; the dial is hidden from accessibility and gives way at large sizes.
            val timeSize = with(density) { (size * if(captions == DialCaptions.timeOnly) 0.21f else 0.19f).toSp() }
            Text(DateTimeFormatter.ofPattern("HH:mm").format((recordingSince ?: now).atZone(zone)), style = WheredayType.editorial(17).copy(fontSize = timeSize, lineHeight = timeSize * 1.1f), color = ink.textPrimary)
            if(recordingSince == null && captions == DialCaptions.full) Text(stringResource(R.string.today_tap_replay), style = WheredayType.mono(11), color = ink.inkRust)
        }
    }
}
internal enum class DialCaptions { full, nowOnly, timeOnly }

@Composable private fun dialDescription(day: TodayDay, zone: ZoneId): String {
    if(day.stays.isEmpty()) return stringResource(R.string.today_dial_empty)
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
    val parts = day.stays.map { stay ->
        if(stay.visit == null && !stay.current) stringResource(R.string.today_dial_until, stay.name, time.format(stay.end.atZone(zone)))
        else stringResource(R.string.today_dial_from, stay.name, time.format(stay.start.atZone(zone)))
    }
    return stringResource(R.string.today_dial, parts.joinToString(", "))
}

@Composable private fun Overview(inputs: TodayInputs, metric: Boolean, onReplay: () -> Unit, modifier: Modifier = Modifier) {
    val ink = LocalWheredayInk.current
    val description = dialDescription(inputs.day, inputs.zone)
    val hint = stringResource(R.string.today_replay_hint)
    Row(modifier.fillMaxWidth().padding(horizontal = 24.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
        Box(Modifier.size(176.dp).clip(CircleShape).clickable(onClickLabel = hint, role = Role.Button, onClick = onReplay).semantics { contentDescription = description }.testTag("today-dial")) {
            DayDial(inputs.day.segments, inputs.now, startOfDay(inputs), inputs.zone, Modifier.fillMaxSize())
        }
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            Stat(todayDistance(inputs.distance, metric), stringResource(R.string.today_moved), "today-moved")
            Stat(inputs.day.away?.let { stayDuration(it) } ?: "—", stringResource(if(inputs.hasHome) R.string.today_away_home else R.string.today_away_places), "today-away")
            Stat(NumberFormat.getIntegerInstance().format(inputs.points), stringResource(R.string.today_points_kept), "today-points")
        }
    }
}

private fun startOfDay(inputs: TodayInputs) = inputs.now.atZone(inputs.zone).toLocalDate().atStartOfDay(inputs.zone).toInstant()

@Composable private fun Stat(value: String, label: String, tag: String) {
    val ink = LocalWheredayInk.current
    Column(Modifier.semantics(mergeDescendants = true) {}.testTag(tag)) {
        Text(value, style = WheredayType.title, color = ink.textPrimary)
        Text(label, style = WheredayType.mono(11, tracking = 1f), color = ink.textMuted)
    }
}

@Composable private fun LargeTextOverview(inputs: TodayInputs, metric: Boolean, onReplay: () -> Unit, modifier: Modifier = Modifier) {
    val ink = LocalWheredayInk.current
    Column(modifier.padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        inputs.day.currentStay?.let { CurrentPlaceCard(it, {}, Modifier, padded = false) }
        LargeStat(todayDistance(inputs.distance, metric), stringResource(R.string.today_moved_large))
        LargeStat(inputs.day.away?.let { stayDuration(it) } ?: "—", stringResource(if(inputs.hasHome) R.string.today_away_home_large else R.string.today_away_places_large))
        Row(Modifier.fillMaxWidth().heightIn(min = 64.dp).clip(RoundedCornerShape(50)).border(1.5.dp, ink.textPrimary, RoundedCornerShape(50))
            .clickable(role = Role.Button, onClick = onReplay).padding(horizontal = 20.dp, vertical = 14.dp).testTag("today-replay"), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.today_replay), Modifier.weight(1f), fontWeight = FontWeight.Medium, color = ink.textPrimary)
            Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = ink.textPrimary)
        }
    }
}

@Composable private fun LargeStat(value: String, label: String) {
    val ink = LocalWheredayInk.current
    Column(Modifier.fillMaxWidth().background(ink.card, RoundedCornerShape(18.dp)).border(1.dp, ink.border, RoundedCornerShape(18.dp))
        .padding(horizontal = 18.dp, vertical = 14.dp).semantics(mergeDescendants = true) {}) {
        Text(value, style = WheredayType.largeTitle, color = ink.textPrimary)
        Text(label, style = MaterialTheme.typography.bodyLarge, color = ink.textMuted)
    }
}

/** The inverted "you're at" card: navy on paper, amber at night. Rename opens the shared visit inspector. */
@Composable private fun CurrentPlaceCard(stay: TodayDay.Stay, onOpenVisit: (Visit) -> Unit, modifier: Modifier = Modifier, padded: Boolean = true) {
    val ink = LocalWheredayInk.current
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).format(stay.since.atZone(ZoneId.systemDefault()))
    Row(modifier.padding(horizontal = if(padded) 16.dp else 0.dp).fillMaxWidth().background(ink.inkCard, RoundedCornerShape(18.dp))
        .padding(horizontal = 18.dp, vertical = 16.dp).testTag("today-current"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        Box(Modifier.size(20.dp).background(ink.liveDot.copy(alpha = 0.25f), CircleShape), contentAlignment = Alignment.Center) {
            Box(Modifier.size(10.dp).background(ink.liveDot, CircleShape))
        }
        Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}) {
            Text(stringResource(R.string.today_youre_at, time), style = WheredayType.mono(11, tracking = 1.2f), color = ink.onInkCardMuted)
            Text(stay.name, style = WheredayType.title2, color = ink.onInkCard, maxLines = 2)
        }
        stay.visit?.let { visit ->
            OutlinedButton({ onOpenVisit(visit) }, Modifier.heightIn(min = 44.dp).testTag("today-rename"), shape = RoundedCornerShape(50),
                border = androidx.compose.foundation.BorderStroke(1.dp, ink.onInkCard.copy(alpha = 0.35f)), colors = ButtonDefaults.outlinedButtonColors(contentColor = ink.onInkCard)) {
                Text(stringResource(R.string.today_rename), fontWeight = FontWeight.Medium)
            }
        }
    }
}

@Composable private fun DaySoFar(inputs: TodayInputs, metric: Boolean, large: Boolean, onOpenVisit: (Visit) -> Unit, onLogStop: () -> Unit, modifier: Modifier = Modifier) {
    val ink = LocalWheredayInk.current
    val listening = inputs.listening
    val day = inputs.day
    val label = when(listening) { TodayListening.idle -> R.string.today_so_far_while_open; TodayListening.limited -> R.string.today_so_far_approximate; else -> R.string.today_so_far }
    Column(modifier.padding(horizontal = if(large) 20.dp else 24.dp).testTag("today-day-so-far")) {
        if(listening == TodayListening.off && day.entries.isEmpty()) {
            val last = inputs.lastDay
            if(last != null) {
                val yesterday = inputs.now.atZone(inputs.zone).toLocalDate().minusDays(1)
                SectionLabel(if(last.first == yesterday) stringResource(R.string.today_last_yesterday)
                    else stringResource(R.string.today_last_day, DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(Locale.getDefault(), "MMMd")).format(last.first).uppercase()), large)
                last.second.forEachIndexed { index, stay -> Box(Modifier.alpha(if(index == 0) 1f else 0.6f)) { StayRow(stay, large, metric, inputs.zone, onOpenVisit) } }
            } else {
                SectionLabel(stringResource(label), large)
                Text(stringResource(R.string.today_nothing_recorded), style = WheredayType.body, color = ink.textMuted)
            }
            LogStop(onLogStop)
            return@Column
        }
        SectionLabel(if(large) stringResource(R.string.today_so_far_large) else stringResource(label), large)
        if(day.entries.isEmpty()) Text(stringResource(R.string.today_no_places), style = WheredayType.body, color = ink.textMuted)
        day.entries.forEach { entry ->
            when(entry) {
                is TodayDay.Entry.StayEntry -> StayRow(entry.stay, large, metric, inputs.zone, onOpenVisit)
                is TodayDay.Entry.MoveEntry -> if(!large) MoveRow(moveLabel(entry.move, metric))
                is TodayDay.Entry.Unrecorded -> UnrecordedRow(entry.duration, large)
            }
        }
        if(listening != TodayListening.full) LogStop(onLogStop)
    }
}

@Composable private fun SectionLabel(text: String, large: Boolean) {
    Text(text, Modifier.padding(bottom = 10.dp).semantics { heading() }, style = if(large) WheredayType.mono(17, tracking = 0.5f) else WheredayType.mono(11, tracking = 1.5f), color = LocalWheredayInk.current.textMuted)
}

@Composable private fun LogStop(onLogStop: () -> Unit) {
    TextButton(onLogStop, Modifier.padding(top = 8.dp).heightIn(min = 44.dp).testTag("today-log-stop"), contentPadding = PaddingValues(0.dp)) {
        Text(stringResource(R.string.today_log_stop), color = LocalWheredayInk.current.inkRust, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable private fun StayRow(stay: TodayDay.Stay, large: Boolean, metric: Boolean, zone: ZoneId, onOpenVisit: (Visit) -> Unit) {
    val ink = LocalWheredayInk.current
    val duration = if(stay.current) stringResource(R.string.today_stay_now) else stayDuration(Duration.between(stay.start, stay.end))
    val clickable = stay.visit?.let { visit -> Modifier.clickable(role = Role.Button) { onOpenVisit(visit) } } ?: Modifier
    if(large) {
        val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault()).format(stay.start.atZone(zone))
        val meta = listOfNotNull(time, duration, stay.arrival?.let { arrivalPhrase(it, metric) }).joinToString(" · ")
        Column(clickable.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag("today-stay")) {
            Row(Modifier.padding(vertical = 12.dp), horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.padding(top = 10.dp).size(16.dp).background(ink.placeInk(stay.ink), CircleShape))
                Column { Text(stay.name, style = WheredayType.title, color = ink.textPrimary); Text(meta, style = WheredayType.mono(17), color = ink.textMuted) }
            }
            HorizontalDivider(color = ink.dialTrack)
        }
    } else Row(clickable.fillMaxWidth().heightIn(min = 46.dp).semantics(mergeDescendants = true) {}.testTag("today-stay"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(DateTimeFormatter.ofPattern("HH:mm").format(stay.start.atZone(zone)), Modifier.widthIn(min = 44.dp), style = WheredayType.caption, color = ink.textMuted)
        Box(Modifier.width(14.dp), contentAlignment = Alignment.Center) { Box(Modifier.size(12.dp).background(ink.placeInk(stay.ink), CircleShape)) }
        Text(stay.name, Modifier.weight(1f), style = WheredayType.title3, color = ink.textPrimary)
        Text(duration, style = WheredayType.caption, color = ink.textMuted)
    }
}

@Composable private fun MoveRow(label: String) {
    val ink = LocalWheredayInk.current
    Row(Modifier.heightIn(min = 26.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Spacer(Modifier.width(44.dp))
        Canvas(Modifier.width(14.dp).height(24.dp).clearAndSetSemantics {}) {
            drawLine(ink.dialTick, Offset(center.x, 0f), Offset(center.x, size.height), strokeWidth = 2.dp.toPx(), cap = StrokeCap.Round,
                pathEffect = PathEffect.dashPathEffect(floatArrayOf(0.1f, 4.dp.toPx())))
        }
        Text(label, style = WheredayType.caption2, color = ink.textMuted)
    }
}

@Composable private fun UnrecordedRow(duration: Duration, large: Boolean) {
    val ink = LocalWheredayInk.current
    Row(Modifier.fillMaxWidth().heightIn(min = 46.dp).alpha(0.6f).semantics(mergeDescendants = true) {}.testTag("today-unrecorded"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(if(large) 14.dp else 10.dp)) {
        if(!large) Text("—", Modifier.widthIn(min = 44.dp).clearAndSetSemantics {}, style = WheredayType.caption, color = ink.textMuted)
        Box(Modifier.size(if(large) 16.dp else 12.dp).background(ink.dialTrack, CircleShape))
        Text(stringResource(R.string.today_not_recorded), Modifier.weight(1f), style = if(large) WheredayType.title else WheredayType.title3, color = ink.textPrimary)
        Text(stayDuration(duration), style = if(large) WheredayType.mono(17) else WheredayType.caption, color = ink.textMuted)
    }
}

@Composable private fun BlankPage(inputs: TodayInputs, onImport: () -> Unit, onSaveHome: () -> Unit, modifier: Modifier = Modifier) {
    val ink = LocalWheredayInk.current
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(Locale.getDefault())
    val since = inputs.recordingSince?.let { maxOf(it, startOfDay(inputs)) }
    val description = since?.let { stringResource(R.string.today_dial_blank_since, time.format(it.atZone(inputs.zone))) } ?: stringResource(R.string.today_dial_blank)
    Column(modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(24.dp)) {
        Box(Modifier.size(220.dp).semantics { contentDescription = description }.testTag("today-blank-dial")) {
            DayDial(emptyList(), inputs.now, startOfDay(inputs), inputs.zone, Modifier.fillMaxSize(), recordingSince = since)
        }
        Column(Modifier.widthIn(max = 300.dp).padding(horizontal = 24.dp), horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.today_blank_title), style = WheredayType.title, color = ink.textPrimary, textAlign = TextAlign.Center)
            Text(stringResource(R.string.today_blank_body), style = MaterialTheme.typography.bodyMedium, color = ink.textMuted, textAlign = TextAlign.Center)
        }
        Column(Modifier.padding(horizontal = 16.dp).fillMaxWidth().background(ink.card, RoundedCornerShape(18.dp)).border(1.dp, ink.border, RoundedCornerShape(18.dp)).clip(RoundedCornerShape(18.dp))) {
            // With Home saved, each day opens on the stretch at Home ("Home until 8:12") and counts time away.
            if(!inputs.hasHome) {
                StartLink(Icons.Outlined.Home, stringResource(R.string.today_save_home), onSaveHome, "today-save-home")
                Box(Modifier.fillMaxWidth().height(1.dp).background(ink.dialTrack))
            }
            StartLink(Icons.Outlined.Download, stringResource(R.string.today_import_timeline), onImport, "today-import")
        }
    }
}

@Composable private fun StartLink(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String, onClick: () -> Unit, tag: String) {
    val ink = LocalWheredayInk.current
    Row(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = onClick).padding(horizontal = 16.dp).heightIn(min = 56.dp).testTag(tag),
        verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Icon(icon, null, tint = ink.placeInk(1))
        Text(text, Modifier.weight(1f).padding(vertical = 16.dp), style = MaterialTheme.typography.bodyMedium, color = ink.textPrimary)
        Icon(Icons.AutoMirrored.Outlined.KeyboardArrowRight, null, tint = ink.textMuted)
    }
}

/** iOS `TodayDay.formatDuration`: "45m", "2h 05m". */
@Composable internal fun stayDuration(duration: Duration): String {
    val minutes = maxOf(0L, duration.toMinutes())
    val number = NumberFormat.getIntegerInstance()
    return if(minutes < 60) stringResource(R.string.today_minutes, number.format(minutes))
        else stringResource(R.string.today_hours_minutes, number.format(minutes / 60), String.format(Locale.getDefault(), "%02d", minutes % 60))
}

@Composable private fun moveDuration(duration: Duration): String {
    val minutes = maxOf(0L, duration.toMinutes())
    return if(minutes < 60) stringResource(R.string.today_move_minutes, NumberFormat.getIntegerInstance().format(minutes))
        else stringResource(R.string.today_hours_minutes, NumberFormat.getIntegerInstance().format(minutes / 60), String.format(Locale.getDefault(), "%02d", minutes % 60))
}

/** "Bike · 3.4 km · 28 min" */
@Composable private fun moveLabel(move: TodayDay.Move, metric: Boolean): String = listOfNotNull(
    move.mode?.let { stringResource(modeLabel(it)) }, move.distance.takeIf { it > 0 }?.let { todayDistance(it, metric) }, moveDuration(move.duration)).joinToString(" · ")

private fun modeLabel(mode: TravelMode) = when(mode) {
    TravelMode.walk -> R.string.today_mode_walk; TravelMode.run -> R.string.today_mode_run; TravelMode.bike -> R.string.today_mode_bike
    TravelMode.drive -> R.string.today_mode_drive; TravelMode.transit -> R.string.today_mode_transit; TravelMode.fly -> R.string.today_mode_fly
}

@Composable private fun arrivalPhrase(move: TodayDay.Move, metric: Boolean): String? {
    if(move.distance <= 0) return null
    val distance = todayDistance(move.distance, metric)
    return stringResource(when(move.mode) {
        TravelMode.walk -> R.string.today_arrival_walk; TravelMode.run -> R.string.today_arrival_run; TravelMode.bike -> R.string.today_arrival_bike
        TravelMode.drive -> R.string.today_arrival_drive; TravelMode.transit -> R.string.today_arrival_transit; TravelMode.fly -> R.string.today_arrival_fly
        null -> R.string.today_arrival_unknown
    }, distance)
}

/** iOS `DistanceFormatter.format`: at most one fraction digit, whole metres/feet below a kilometre/tenth of a mile. */
internal fun todayDistance(meters: Double, metric: Boolean, locale: Locale = Locale.getDefault()): String {
    val one = NumberFormat.getNumberInstance(locale).apply { maximumFractionDigits = 1; minimumFractionDigits = 0 }
    val whole = NumberFormat.getIntegerInstance(locale)
    return if(metric) { if(meters >= 1000) "${one.format(meters / 1000)} km" else "${whole.format(Math.round(meters))} m" }
    else { val miles = meters / 1609.344; if(miles < 0.1) "${whole.format(Math.round(meters * 3.28084))} ft" else "${one.format(miles)} mi" }
}

/** Recording access follow-up under Today's record button: a refused or approximate grant and missing
 *  notifications, with their recovery options. Plain "off" is Today's own access card. */
@Composable private fun TodayRecordingNotice(state: RecordingAccessState, options: (PermissionAccessKind) -> Unit) {
    RecordingAccessNotice(state, options, location = state.locationDeclined || state.location == LocationPrecision.approximate)
}

