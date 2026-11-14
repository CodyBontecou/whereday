package com.bontecou.whereday

import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.bontecou.whereday.data.SavedPlacesChanged
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch
import java.text.NumberFormat
import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.format.TextStyle
import java.time.temporal.WeekFields
import java.util.Locale

/** Saved-place actions the detail screen needs; the shell binds them to the repository. */
internal class PlaceDetailActions(
    val save: suspend (SavedPlace, SavedPlace) -> Unit,
    val mergePlan: suspend (kept: SavedPlace, removed: SavedPlace) -> SavedPlaceMergePlan,
    val merge: suspend (SavedPlaceMergePlan, kept: SavedPlace, removed: SavedPlace) -> Unit,
)

@Composable internal fun SavedPlaceKind.label(): String = stringResource(when(this) {
    SavedPlaceKind.home -> R.string.place_kind_home; SavedPlaceKind.work -> R.string.place_kind_work
    SavedPlaceKind.cafe -> R.string.place_kind_cafe; SavedPlaceKind.gym -> R.string.place_kind_gym
    SavedPlaceKind.other -> R.string.place_kind_other
})

/** "CAFÉ · SAVED PLACE", or "SAVED PLACE" before a kind is chosen. */
@Composable internal fun placeKicker(place: SavedPlace): String =
    place.kind?.let { stringResource(R.string.place_kicker_kind, it.label().uppercase()) } ?: stringResource(R.string.place_kicker)

/** The rhythm of one saved place: visits, typical stay, and when you tend to come here. */
@Composable internal fun PlaceDetailContent(
    place: SavedPlace?, places: List<SavedPlace>, visits: List<Visit>, now: Instant, zone: ZoneId, metric: Boolean,
    actions: PlaceDetailActions, back: () -> Unit, edit: (SavedPlace) -> Unit, openVisit: (Visit) -> Unit,
    /** Two-pane layouts show the detail beside the list, without its own back navigation. */
    embedded: Boolean = false,
) {
    val ink = LocalWheredayInk.current
    if(!embedded) BackHandler(onBack = back)
    if(place == null) {
        Column(Modifier.fillMaxSize().background(ink.surface).padding(24.dp).semantics { testTagsAsResourceId = true }.testTag("place-detail-removed"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            if(!embedded) TextButton(back) { Text(stringResource(R.string.place_back)) }
            Text(stringResource(R.string.place_removed_title), style = WheredayType.title2, color = ink.textPrimary)
            Text(stringResource(R.string.place_removed_body), style = WheredayType.body, color = ink.textMuted)
        }
        return
    }
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val large = LocalDensity.current.fontScale >= 1.6f
    val scope = rememberCoroutineScope()
    val matched = remember(places, visits) { PlaceVisitIndex(places, visits) }.visits(place)
    val rhythm = remember(matched, now, zone, locale) { PlaceRhythm(matched, now, zone, WeekFields.of(locale).firstDayOfWeek) }
    val placeInk = ink.placeInk(PlaceVisitIndex.ink(place, places))
    var renaming by rememberSaveable(place.id) { mutableStateOf(false) }
    var renameText by rememberSaveable(place.id) { mutableStateOf("") }
    var editingNote by rememberSaveable(place.id) { mutableStateOf(false) }
    var noteText by rememberSaveable(place.id) { mutableStateOf("") }
    var picking by rememberSaveable(place.id) { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    fun write(change: SavedPlace, done: () -> Unit) {
        if(busy) return
        busy = true
        scope.launch {
            try { actions.save(change, place); done() }
            catch(e: Exception) { if(e is CancellationException) throw e; error = if(e is SavedPlacesChanged) R.string.saved_changed else R.string.saved_editor_save_error }
            finally { busy = false }
        }
    }
    Column(Modifier.fillMaxSize().background(ink.surface).verticalScroll(rememberScrollState()).semantics { testTagsAsResourceId = true }.testTag("place-detail")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            if(!embedded) TextButton(back, Modifier.testTag("place-detail-back")) { Text(stringResource(R.string.place_back), color = ink.accent) }
            Spacer(Modifier.weight(1f))
            TextButton({ edit(place) }, Modifier.testTag("place-detail-edit")) { Text(stringResource(R.string.place_edit), color = ink.accent) }
        }
        PlaceMapPlate(place.name, place.radius, placeInk, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(196.dp))
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.place_confirmed), style = WheredayType.mono(11, FontWeight.Medium, 1.2f), color = ink.placeInk(3),
                    modifier = Modifier.background(ink.placeInk(3).copy(alpha = 0.16f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
                Text(placeKicker(place), style = WheredayType.mono(11, tracking = 1.2f), color = ink.textMuted, modifier = Modifier.testTag("place-detail-kicker"))
            }
            if(renaming) {
                OutlinedTextField(renameText, { renameText = it }, Modifier.fillMaxWidth().testTag("place-detail-rename-field"), enabled = !busy, singleLine = true,
                    textStyle = WheredayType.largeTitle, label = { Text(stringResource(R.string.place_name_label)) })
                Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    TextButton({
                        val name = renameText.trim()
                        if(name == place.name) renaming = false else write(place.copy(name = name)) { renaming = false }
                    }, Modifier.testTag("place-detail-rename-save"), enabled = renameText.isNotBlank() && !busy) { Text(stringResource(R.string.place_save_name), color = ink.inkRust) }
                    TextButton({ renaming = false }, enabled = !busy) { Text(stringResource(R.string.visit_cancel), color = ink.textMuted) }
                }
            } else Text(place.name, style = WheredayType.largeTitle, color = ink.textPrimary, modifier = Modifier.semantics { heading() }.testTag("place-detail-name"))
            place.address?.takeIf { it.isNotBlank() }?.let { Text(it, style = WheredayType.mono(12), color = ink.textMuted) }
        }
        PlaceStatStrip(listOf(
            NumberFormat.getIntegerInstance(locale).format(rhythm.visitCount) to stringResource(R.string.place_stat_visits),
            (rhythm.typicalStay?.let { placeDuration(it) } ?: "—") to stringResource(R.string.place_stat_typical),
            placeHours(rhythm.timeThisYear) to stringResource(R.string.place_stat_year, now.atZone(zone).year.toString()),
        ), large, Modifier.padding(horizontal = 24.dp, vertical = 12.dp).testTag("place-detail-stats"))
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.place_rhythm_title), style = WheredayType.kicker, color = ink.textMuted, modifier = Modifier.semantics { heading() })
            rhythm.summary?.let { Text(rhythmSummary(it), style = WheredayType.editorial(15).copy(fontStyle = FontStyle.Italic), color = ink.textMuted, modifier = Modifier.testTag("place-detail-rhythm-summary")) }
            if(rhythm.visitCount == 0) Text(stringResource(R.string.place_rhythm_empty), style = WheredayType.body, color = ink.textMuted)
            else PlaceRhythmGrid(rhythm, placeInk, locale)
        }
        RecentVisits(matched, now, zone, locale, openVisit)
        val note = place.notes?.trim().orEmpty()
        if(editingNote) Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.place_note), style = WheredayType.kicker, color = ink.textMuted)
            OutlinedTextField(noteText, { noteText = it }, Modifier.fillMaxWidth().testTag("place-detail-note-field"), enabled = !busy, minLines = 3, maxLines = 8,
                placeholder = { Text(stringResource(R.string.place_note_prompt)) }, label = { Text(stringResource(R.string.place_note_label)) })
            Row(horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                TextButton({ if(noteText == place.notes.orEmpty()) editingNote = false else write(place.copy(notes = noteText.takeIf { it.isNotBlank() })) { editingNote = false } },
                    Modifier.testTag("place-detail-note-save"), enabled = !busy) { Text(stringResource(R.string.place_save_note), color = ink.inkRust) }
                TextButton({ editingNote = false }, enabled = !busy) { Text(stringResource(R.string.visit_cancel), color = ink.textMuted) }
            }
        } else if(note.isNotEmpty()) Column(Modifier.fillMaxWidth().clickable(role = Role.Button, onClickLabel = stringResource(R.string.place_edit_note)) { noteText = place.notes.orEmpty(); editingNote = true }
            .padding(horizontal = 24.dp, vertical = 12.dp).testTag("place-detail-note"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.place_note), style = WheredayType.kicker, color = ink.textMuted)
            Text(note, style = WheredayType.body.copy(fontStyle = FontStyle.Italic), color = ink.textPrimary)
        }
        error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error, modifier = Modifier.padding(horizontal = 24.dp).testTag("place-detail-error")) }
        val buttons: @Composable (Modifier) -> Unit = { item ->
            TEActionButton(stringResource(R.string.place_rename), { renameText = place.name; renaming = true }, item.testTag("place-detail-rename"), enabled = !renaming && !busy)
            TEActionButton(stringResource(R.string.place_merge), { picking = true }, item.testTag("place-detail-merge"), enabled = places.size > 1 && !busy)
            TEActionButton(stringResource(if(note.isEmpty()) R.string.place_add_note else R.string.place_edit_note), { noteText = place.notes.orEmpty(); editingNote = true },
                item.testTag("place-detail-note-action"), TEEmphasis.primary, enabled = !editingNote && !busy)
        }
        if(large) Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { buttons(Modifier.fillMaxWidth()) }
        else Row(Modifier.padding(16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) { buttons(Modifier.weight(1f)) }
        Spacer(Modifier.height(24.dp))
    }
    if(picking) PlaceMergePicker(place, places, visits, metric, locale, actions, { picking = false })
}

@Composable private fun RecentVisits(matched: List<Visit>, now: Instant, zone: ZoneId, locale: Locale, openVisit: (Visit) -> Unit) {
    val ink = LocalWheredayInk.current
    if(matched.isNotEmpty()) Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp)) {
        Text(stringResource(R.string.place_recent), style = WheredayType.kicker, color = ink.textMuted, modifier = Modifier.padding(bottom = 4.dp).semantics { heading() })
        val recent = matched.sortedByDescending { it.arrivedAt }.take(5)
        recent.forEachIndexed { index, visit ->
            val open = visit.departedAt == null
            Row(Modifier.fillMaxWidth().heightIn(min = 44.dp).clickable(role = Role.Button) { openVisit(visit) }.padding(vertical = 9.dp).testTag("place-detail-recent"), verticalAlignment = Alignment.CenterVertically) {
                Text(visitRange(visit, now, zone, locale), style = WheredayType.body, color = ink.textPrimary, modifier = Modifier.weight(1f))
                Text(if(open) stringResource(R.string.place_now) else placeDuration(Duration.between(visit.arrivedAt, visit.departedAt)), style = WheredayType.mono(12), color = if(open) ink.inkRust else ink.textMuted)
            }
            if(index < recent.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(ink.dialTrack))
        }
    }
}

/** Picks the saved place to fold into this one, nearest first, then confirms what the merge changes. */
@Composable private fun PlaceMergePicker(place: SavedPlace, places: List<SavedPlace>, visits: List<Visit>, metric: Boolean, locale: Locale, actions: PlaceDetailActions, dismiss: () -> Unit) {
    val ink = LocalWheredayInk.current
    val scope = rememberCoroutineScope()
    var query by rememberSaveable { mutableStateOf("") }
    var pending by remember { mutableStateOf<Pair<SavedPlaceMergePlan, SavedPlace>?>(null) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<Int?>(null) }
    val index = remember(places, visits) { PlaceVisitIndex(places, visits) }
    val others = places.filter { it.id != place.id && (query.isBlank() || savedPlaceSearchKey(query.trim(), locale) in savedPlaceSearchKey(it.name, locale)) }
        .sortedBy { place.coordinate.distanceTo(it.coordinate) }
    WorkspaceAlertDialog(onDismissRequest = { if(!busy) dismiss() }, title = { Text(stringResource(R.string.place_merge_title, place.name)) }, text = {
        Column(Modifier.semantics { testTagsAsResourceId = true }.testTag("place-merge-picker"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(query, { query = it }, Modifier.fillMaxWidth().testTag("place-merge-search"), singleLine = true, label = { Text(stringResource(R.string.place_merge_search)) })
            if(others.isEmpty()) Text(stringResource(R.string.saved_no_match), color = ink.textMuted)
            LazyColumn(Modifier.heightIn(max = 320.dp)) {
                items(others, key = { it.id }) { other ->
                    val count = index.visits(other).size
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).clickable(enabled = !busy, role = Role.Button) {
                        busy = true; error = null
                        scope.launch {
                            try { pending = actions.mergePlan(place, other) to other }
                            catch(e: Exception) { if(e is CancellationException) throw e; error = R.string.place_merge_error }
                            finally { busy = false }
                        }
                    }.padding(vertical = 6.dp).testTag("place-merge-candidate"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        Box(Modifier.size(12.dp).background(ink.placeInk(PlaceVisitIndex.ink(other, places)), CircleShape))
                        Column(Modifier.weight(1f)) {
                            Text(other.name, style = WheredayType.title3, color = ink.textPrimary)
                            Text(stringResource(R.string.place_merge_distance, todayDistance(place.coordinate.distanceTo(other.coordinate), metric, locale)), style = WheredayType.mono(11), color = ink.textMuted)
                        }
                        Text(pluralStringResource(R.plurals.place_visit_count, count, count), style = WheredayType.mono(12), color = ink.textMuted)
                    }
                }
            }
            Text(stringResource(R.string.place_merge_footer, place.name), style = MaterialTheme.typography.bodySmall, color = ink.textMuted)
            error?.let { Text(stringResource(it), color = MaterialTheme.colorScheme.error) }
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        }
    }, confirmButton = {}, dismissButton = { TextButton(dismiss, enabled = !busy) { Text(stringResource(R.string.visit_cancel)) } })
    pending?.let { (plan, removed) ->
        WorkspaceAlertDialog(onDismissRequest = { if(!busy) pending = null }, title = { Text(stringResource(R.string.place_merge_confirm_title, plan.removedName, plan.keptName)) },
            text = { Text(mergeMessage(plan, metric, locale), Modifier.semantics { testTagsAsResourceId = true }.testTag("place-merge-message")) },
            confirmButton = { TextButton(modifier = Modifier.semantics { testTagsAsResourceId = true }.testTag("place-merge-confirm"), enabled = !busy, onClick = {
                busy = true; error = null
                scope.launch {
                    try { actions.merge(plan, place, removed); pending = null; dismiss() }
                    catch(e: Exception) { if(e is CancellationException) throw e; pending = null; error = if(e is SavedPlacesChanged) R.string.saved_changed else R.string.place_merge_error }
                    finally { busy = false }
                }
            }) { Text(stringResource(R.string.place_merge_action), color = ink.inkRust) } },
            dismissButton = { TextButton({ pending = null }, enabled = !busy) { Text(stringResource(R.string.visit_cancel)) } })
    }
}

@Composable internal fun mergeMessage(plan: SavedPlaceMergePlan, metric: Boolean, locale: Locale): String {
    val count = plan.affectedVisitCount
    val parts = mutableListOf(pluralStringResource(R.plurals.place_merge_moves, count, plan.removedName, count, plan.keptName))
    if(plan.radiusGrows) parts += stringResource(R.string.place_merge_radius, todayDistance(plan.mergedRadius, metric, locale))
    if(!plan.coversRemovedPlace) parts += stringResource(R.string.place_merge_far, plan.removedName)
    parts += stringResource(R.string.place_merge_permanent)
    return parts.joinToString(" ")
}

/** An offline plate of the place and its match radius; map tiles stay behind the map opt-in. */
@Composable private fun PlaceMapPlate(name: String, radius: Double, placeInk: androidx.compose.ui.graphics.Color, modifier: Modifier) {
    val ink = LocalWheredayInk.current
    val label = stringResource(R.string.place_map_label, name)
    val value = stringResource(R.string.place_map_value, Math.round(radius).toInt())
    Box(modifier.background(ink.dialTrack, RoundedCornerShape(18.dp)).border(1.dp, ink.border, RoundedCornerShape(18.dp))
        .clearAndSetSemantics { testTag = "place-detail-map"; contentDescription = label; stateDescription = value }) {
        Canvas(Modifier.fillMaxSize()) {
            val step = 24.dp.toPx()
            var x = step; while(x < size.width) { drawLine(ink.dialTick.copy(alpha = 0.35f), Offset(x, 0f), Offset(x, size.height), 1f); x += step }
            var y = step; while(y < size.height) { drawLine(ink.dialTick.copy(alpha = 0.35f), Offset(0f, y), Offset(size.width, y), 1f); y += step }
            val r = size.minDimension * 0.3f
            drawCircle(placeInk.copy(alpha = 0.14f), r, center)
            drawCircle(placeInk, r, center, style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(4.dp.toPx(), 4.dp.toPx()))))
            drawCircle(ink.surface, 10.dp.toPx(), center); drawCircle(placeInk, 7.dp.toPx(), center)
        }
        Text(stringResource(R.string.place_map_radius, Math.round(radius).toInt()), style = WheredayType.mono(11), color = ink.textPrimary,
            modifier = Modifier.align(Alignment.BottomEnd).padding(10.dp).background(ink.card.copy(alpha = 0.92f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
    }
}

/** Serif numbers with mono labels between hairlines; stacks at large text sizes. */
@Composable private fun PlaceStatStrip(items: List<Pair<String, String>>, large: Boolean, modifier: Modifier) {
    val ink = LocalWheredayInk.current
    val cell: @Composable (Pair<String, String>, Modifier) -> Unit = { (value, label), item ->
        Column(item.padding(vertical = 12.dp).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(value, style = WheredayType.title, color = ink.textPrimary, maxLines = 1)
            Text(label, style = WheredayType.mono(11), color = ink.textMuted)
        }
    }
    Column(modifier) {
        Box(Modifier.fillMaxWidth().height(1.dp).background(ink.border))
        if(large) items.forEach { cell(it, Modifier.fillMaxWidth()) }
        else Row(Modifier.height(IntrinsicSize.Min)) {
            items.forEachIndexed { index, item ->
                if(index > 0) Box(Modifier.width(1.dp).fillMaxHeight().background(ink.border))
                cell(item, Modifier.weight(1f).padding(start = if(index > 0) 14.dp else 0.dp))
            }
        }
        Box(Modifier.fillMaxWidth().height(1.dp).background(ink.border))
    }
}

@Composable private fun PlaceRhythmGrid(rhythm: PlaceRhythm, placeInk: androidx.compose.ui.graphics.Color, locale: Locale) {
    val ink = LocalWheredayInk.current
    val label = stringResource(R.string.place_rhythm_label)
    val value = rhythm.peak?.let { (day, hour) ->
        stringResource(R.string.place_rhythm_peak, day.getDisplayName(TextStyle.FULL_STANDALONE, locale),
            DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(java.time.LocalTime.of(hour, 0)))
    } ?: stringResource(R.string.place_rhythm_none)
    val summary = rhythm.summary?.let { rhythmSummary(it) }
    val description = listOfNotNull(value, summary).joinToString(". ")
    Column(Modifier.fillMaxWidth().clearAndSetSemantics { testTag = "place-detail-rhythm"; contentDescription = label; stateDescription = description }, verticalArrangement = Arrangement.spacedBy(4.dp)) {
        rhythm.weekdayOrder.forEach { day ->
            Row(Modifier.height(14.dp), verticalAlignment = Alignment.CenterVertically) {
                Text(day.getDisplayName(TextStyle.NARROW_STANDALONE, locale), style = WheredayType.mono(11), color = ink.textMuted, modifier = Modifier.width(22.dp))
                PlaceRhythm.HOURS.forEach { hour ->
                    val level = rhythm.level(day, hour)
                    Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                        Box(Modifier.size(listOf(3, 6, 9, 12)[level].dp).background(if(level == 0) ink.dialTick.copy(alpha = 0.6f) else placeInk, CircleShape))
                    }
                }
            }
        }
        Row(Modifier.padding(top = 2.dp)) {
            Spacer(Modifier.width(22.dp))
            PlaceRhythm.HOURS.forEach { hour ->
                Box(Modifier.weight(1f), contentAlignment = Alignment.Center) {
                    if(hour in PlaceRhythm.LABELLED_HOURS) Text(NumberFormat.getIntegerInstance(locale).format(hour), style = WheredayType.mono(10), color = ink.textMuted, maxLines = 1, softWrap = false)
                }
            }
        }
    }
}

@Composable internal fun rhythmSummary(summary: PlaceRhythm.Summary): String {
    val part = stringResource(when(summary.part) {
        PlaceRhythm.Part.mornings -> R.string.place_part_mornings; PlaceRhythm.Part.midday -> R.string.place_part_midday
        PlaceRhythm.Part.afternoons -> R.string.place_part_afternoons; PlaceRhythm.Part.evenings -> R.string.place_part_evenings
        PlaceRhythm.Part.nights -> R.string.place_part_nights
    })
    return stringResource(when(summary.lean) { PlaceRhythm.Lean.weekend -> R.string.place_lean_weekend; PlaceRhythm.Lean.weekday -> R.string.place_lean_weekday; PlaceRhythm.Lean.mixed -> R.string.place_lean_mixed }, part)
}

/** "45m", "2h", "2h 15m" or "3d 4h", as iOS `PlaceRhythm.formatDuration`. */
@Composable internal fun placeDuration(duration: Duration): String {
    val minutes = maxOf(0L, duration.toMinutes()).toInt()
    if(minutes < 60) return stringResource(R.string.place_duration_minutes, minutes)
    val hours = minutes / 60
    if(hours >= 48) return stringResource(R.string.place_duration_days, hours / 24, hours % 24)
    return if(minutes % 60 == 0) stringResource(R.string.place_duration_hours, hours) else stringResource(R.string.place_duration_hours_minutes, hours, minutes % 60)
}

@Composable private fun placeHours(duration: Duration): String =
    if(duration < Duration.ofHours(1)) placeDuration(duration) else stringResource(R.string.place_duration_hours, duration.toHours().toInt())

@Composable private fun visitRange(visit: Visit, now: Instant, zone: ZoneId, locale: Locale): String {
    val time = DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale)
    val date = visit.arrivedAt.atZone(zone).toLocalDate()
    val today = now.atZone(zone).toLocalDate()
    val day = when(date) {
        today -> stringResource(R.string.place_today)
        today.minusDays(1) -> stringResource(R.string.place_yesterday)
        else -> DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, if(date.year == today.year) "EEEMMMd" else "MMMdy"), locale).format(date)
    }
    val start = time.format(visit.arrivedAt.atZone(zone))
    val end = visit.departedAt ?: return stringResource(R.string.place_range_since, day, start)
    return stringResource(R.string.place_range, day, start, time.format(end.atZone(zone)))
}

/** A visited spot with no saved place around it, offered with a way to save it. */
@Composable internal fun UnsavedPlaceDetail(
    suggestion: UnsavedPlaceSuggestion, places: List<SavedPlace>, visits: List<Visit>, now: Instant, zone: ZoneId,
    back: () -> Unit, save: (UnsavedPlaceSuggestion) -> Unit, openVisit: (Visit) -> Unit, embedded: Boolean = false,
) {
    val ink = LocalWheredayInk.current
    if(!embedded) BackHandler(onBack = back)
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val large = LocalDensity.current.fontScale >= 1.6f
    val matched = remember(suggestion, places, visits) { suggestion.visits(places, visits) }
    val rhythm = remember(matched, now, zone, locale) { PlaceRhythm(matched, now, zone, WeekFields.of(locale).firstDayOfWeek) }
    val since = matched.minOfOrNull { it.arrivedAt } ?: suggestion.lastVisit
    Column(Modifier.fillMaxSize().background(ink.surface).verticalScroll(rememberScrollState()).semantics { testTagsAsResourceId = true }.testTag("place-unsaved-detail")) {
        if(!embedded) TextButton(back, Modifier.padding(horizontal = 8.dp).testTag("place-detail-back")) { Text(stringResource(R.string.place_back), color = ink.accent) }
        PlaceMapPlate(suggestion.name, 75.0, ink.inkRust, Modifier.padding(horizontal = 16.dp, vertical = 8.dp).fillMaxWidth().height(196.dp))
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.places_unsaved_badge), style = WheredayType.mono(11, FontWeight.Medium, 1.2f), color = ink.inkRust,
                    modifier = Modifier.background(ink.inkRust.copy(alpha = 0.16f), RoundedCornerShape(6.dp)).padding(horizontal = 8.dp, vertical = 4.dp))
                Text(stringResource(R.string.place_visited_since, DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale, "MMMy"), locale).format(since.atZone(zone)).uppercase(locale)),
                    style = WheredayType.mono(11, tracking = 1.2f), color = ink.textMuted, modifier = Modifier.testTag("place-unsaved-kicker"))
            }
            Text(suggestion.name, style = WheredayType.largeTitle, color = ink.textPrimary, modifier = Modifier.semantics { heading() }.testTag("place-detail-name"))
            suggestion.address?.takeIf { it.isNotBlank() }?.let { Text(it, style = WheredayType.mono(12), color = ink.textMuted) }
        }
        PlaceStatStrip(listOf(
            NumberFormat.getIntegerInstance(locale).format(rhythm.visitCount) to stringResource(R.string.place_stat_visits),
            (rhythm.typicalStay?.let { placeDuration(it) } ?: "—") to stringResource(R.string.place_stat_typical),
            placeHours(rhythm.timeThisYear) to stringResource(R.string.place_stat_year, now.atZone(zone).year.toString()),
        ), large, Modifier.padding(horizontal = 24.dp, vertical = 12.dp).testTag("place-detail-stats"))
        Column(Modifier.padding(horizontal = 24.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.place_rhythm_title), style = WheredayType.kicker, color = ink.textMuted, modifier = Modifier.semantics { heading() })
            rhythm.summary?.let { Text(rhythmSummary(it), style = WheredayType.editorial(15).copy(fontStyle = FontStyle.Italic), color = ink.textMuted) }
            PlaceRhythmGrid(rhythm, ink.inkRust, locale)
        }
        RecentVisits(matched, now, zone, locale, openVisit)
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.place_unsaved_body), style = MaterialTheme.typography.bodySmall, color = ink.textMuted, modifier = Modifier.padding(horizontal = 8.dp))
            TEActionButton(stringResource(R.string.place_save_place), { save(suggestion) }, Modifier.fillMaxWidth().testTag("place-unsaved-save"), TEEmphasis.primary)
        }
    }
}
