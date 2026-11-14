package com.bontecou.whereday

import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.LinkAnnotation
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextLinkStyles
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.withLink
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.whereday.export.*
import java.time.LocalDate
import java.time.YearMonth
import java.time.format.DateTimeFormatter
import java.util.Locale

/** The five choices in the recipe sentence, in the order the localized template numbers them. */
internal enum class RecipeToken { what, range, format, layout, destination }

/** Proper names, the same in every language, as on iOS. */
internal fun ExportFormat.title() = when(this) {
    ExportFormat.json -> "JSON"; ExportFormat.csv -> "CSV"; ExportFormat.markdown -> "Markdown"; ExportFormat.geojson -> "GeoJSON"
    ExportFormat.gpx -> "GPX"; ExportFormat.kml -> "KML"; ExportFormat.owntracks -> "OwnTracks"; ExportFormat.overland -> "Overland"
}

private enum class RecipeWhat { visits, routes, outings, all }
private fun ExportSelection.what() = when { outings -> RecipeWhat.outings; options.kind == DataKind.visits -> RecipeWhat.visits; options.kind == DataKind.points -> RecipeWhat.routes; else -> RecipeWhat.all }
private fun ExportSelection.withWhat(value: RecipeWhat) = when(value) {
    RecipeWhat.outings -> copy(outings = true)
    RecipeWhat.visits -> copy(outings = false, options = options.copy(kind = DataKind.visits))
    RecipeWhat.routes -> copy(outings = false, options = options.copy(kind = DataKind.points))
    RecipeWhat.all -> copy(outings = false, options = options.copy(kind = DataKind.all))
}

/** "Export my visits from the last 7 days as GPX, all in one file, into the share sheet." with each choice tappable. */
@Composable internal fun ArchiveRecipeComposer(
    selection: ExportSelection, enabled: Boolean, update: (ExportSelection) -> Unit,
    destination: ExportDestination, setDestination: (ExportDestination) -> Unit, folderName: String?,
    recipes: List<ExportRecipe>, saveRecipes: (List<ExportRecipe>) -> Unit, today: LocalDate,
    onAllOptions: () -> Unit, modifier: Modifier = Modifier,
) {
    val ink = LocalWheredayInk.current
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    var editing by rememberSaveable { mutableStateOf<String?>(null) }
    var naming by rememberSaveable { mutableStateOf<String?>(null) }
    var deleting by remember { mutableStateOf<ExportRecipe?>(null) }
    val matching = recipes.firstOrNull { it.matches(selection, destination, today) }
    val range = ExportRange.detect(selection.options, today)
    val phrases = mapOf(
        RecipeToken.what to whatPhrase(selection.what()),
        RecipeToken.range to rangePhrase(range, selection.options, today, locale),
        RecipeToken.format to android.icu.text.ListFormatter.getInstance(locale).format((listOf(selection.format) + selection.additionalFormats.sortedBy { it.ordinal }).map { it.title() }),
        RecipeToken.layout to stringResource(when { !selection.splitDays -> R.string.recipe_layout_one; selection.outings -> R.string.recipe_layout_outing; else -> R.string.recipe_layout_day }),
        RecipeToken.destination to if(destination == ExportDestination.folder && folderName != null) stringResource(R.string.recipe_destination_folder, folderName) else stringResource(R.string.recipe_destination_share),
    )
    fun handle(token: RecipeToken) {
        if(!enabled) return
        if(token == RecipeToken.layout) update(selection.copy(splitDays = !selection.splitDays)) else editing = token.name
    }
    Column(modifier) {
        Column(Modifier.padding(start = 4.dp, end = 56.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.recipe_kicker), style = WheredayType.mono(11, tracking = 1.5f), color = ink.textMuted)
            Text(matching?.name ?: stringResource(R.string.recipe_new), Modifier.semantics { heading() }.testTag("recipe-title"), style = WheredayType.largeTitle, color = ink.textPrimary, maxLines = 2)
        }
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 14.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            recipes.forEach { recipe -> RecipeChip(recipe, recipe.id == matching?.id, enabled, { update(recipe.resolved(today)); setDestination(recipe.destination) }) { deleting = recipe } }
            if(matching == null) {
                val dash = ink.dialTick
                Box(Modifier.heightIn(min = 44.dp).clip50().drawBehind {
                    drawRoundRect(dash, cornerRadius = CornerRadius(size.height / 2), style = Stroke(1.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(3.dp.toPx(), 3.dp.toPx()))))
                }.combinedClickable(enabled = enabled, role = Role.Button, onClick = { naming = "" }).padding(horizontal = 12.dp).testTag("recipe-save"), contentAlignment = Alignment.Center) {
                    Text(stringResource(R.string.recipe_save_this), style = MaterialTheme.typography.labelMedium, color = ink.textMuted)
                }
            }
        }
        val labels = RecipeToken.entries.associateWith { stringResource(changeLabel(it)) }
        val template = stringResource(R.string.recipe_sentence, *(0 until 5).map { "$it" }.toTypedArray())
        val link = TextLinkStyles(SpanStyle(color = ink.inkRust, textDecoration = TextDecoration.Underline))
        val sentence = buildAnnotatedString {
            var rest = template
            while(true) {
                val open = rest.indexOf(''); if(open < 0) { append(rest); break }
                val close = rest.indexOf('', open); if(close < 0) { append(rest); break }
                append(rest.substring(0, open))
                val token = rest.substring(open + 1, close).toIntOrNull()?.let { RecipeToken.entries.getOrNull(it) }
                if(token != null) withLink(LinkAnnotation.Clickable(token.name, link) { handle(token) }) { append(phrases.getValue(token)) }
                rest = rest.substring(close + 1)
            }
        }
        Text(sentence, Modifier.fillMaxWidth().padding(horizontal = 4.dp).padding(top = 8.dp).testTag("recipe-sentence").semantics {
            customActions = RecipeToken.entries.map { token -> CustomAccessibilityAction(labels.getValue(token)) { handle(token); true } }
        }, style = WheredayType.editorial(28).copy(lineHeight = 38.sp), color = ink.textPrimary)
        Row(Modifier.fillMaxWidth().padding(start = 4.dp, top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.recipe_hint), Modifier.weight(1f), style = WheredayType.mono(11, tracking = 1f), color = ink.textMuted)
            TextButton(onAllOptions, Modifier.testTag("recipe-all-options")) { Text(stringResource(R.string.recipe_all_options), style = WheredayType.mono(11, FontWeight.SemiBold, 1f), color = ink.inkRust) }
        }
    }
    when(editing?.let(RecipeToken::valueOf)) {
        RecipeToken.what -> RecipeChoices(stringResource(R.string.recipe_what_title), RecipeWhat.entries, selection.what(), { whatPhrase(it).replaceFirstChar { c -> c.titlecase(locale) } }, { editing = null }) { update(selection.withWhat(it)); editing = null }
        RecipeToken.range -> {
            val options = listOf(ExportRange(ExportRangePreset.allTime), ExportRange(ExportRangePreset.today), ExportRange(ExportRangePreset.yesterday), ExportRange(ExportRangePreset.last7Days),
                ExportRange(ExportRangePreset.last30Days), ExportRange(ExportRangePreset.thisMonth), ExportRange(ExportRangePreset.month, YearMonth.from(today).minusMonths(1).atDay(1)),
                ExportRange(ExportRangePreset.year, today.withDayOfYear(1)), ExportRange(ExportRangePreset.custom))
            RecipeChoices(stringResource(R.string.recipe_range_title), options, options.firstOrNull { it == range } ?: options.last(),
                { if(it.preset == ExportRangePreset.custom) stringResource(R.string.recipe_range_custom) else rangePhrase(it, selection.options, today, locale).replaceFirstChar { c -> c.titlecase(locale) } }, { editing = null }) {
                editing = null
                if(it.preset == ExportRangePreset.custom) onAllOptions() else update(selection.copy(options = it.applyTo(selection.options, today)))
            }
        }
        RecipeToken.format -> RecipeFormats(selection, { editing = null }) { update(it) }
        RecipeToken.destination -> RecipeChoices(stringResource(R.string.recipe_destination_title), ExportDestination.entries, destination,
            { if(it == ExportDestination.share) stringResource(R.string.recipe_destination_share_option) else folderName?.let { name -> stringResource(R.string.recipe_destination_folder, name) } ?: stringResource(R.string.recipe_destination_folder_missing) },
            { editing = null }, enabledFor = { it == ExportDestination.share || folderName != null }) { setDestination(it); editing = null }
        else -> {}
    }
    naming?.let { draft ->
        val suggested = "${phrases.getValue(RecipeToken.format)}, ${phrases.getValue(RecipeToken.range).replaceFirstChar { it.titlecase(locale) }}"
        var name by rememberSaveable { mutableStateOf(draft.ifEmpty { suggested }) }
        WorkspaceAlertDialog(onDismissRequest = { naming = null }, title = { Text(stringResource(R.string.recipe_save_title)) }, text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(name, { if(it.length <= 200) name = it }, Modifier.fillMaxWidth().testTag("recipe-name"), singleLine = true, label = { Text(stringResource(R.string.recipe_name)) })
                Text(stringResource(R.string.recipe_save_body), style = MaterialTheme.typography.bodySmall)
            }
        }, confirmButton = { TextButton(enabled = name.isNotBlank(), modifier = Modifier.testTag("recipe-save-confirm"), onClick = {
            saveRecipes(recipes + ExportRecipe(name = name.trim(), selection = selection, range = range, destination = destination)); naming = null
        }) { Text(stringResource(R.string.visit_save)) } }, dismissButton = { TextButton({ naming = null }) { Text(stringResource(R.string.visit_cancel)) } })
    }
    deleting?.let { recipe ->
        WorkspaceAlertDialog(onDismissRequest = { deleting = null }, title = { Text(stringResource(R.string.recipe_delete_title, recipe.name)) },
            confirmButton = { TextButton(modifier = Modifier.testTag("recipe-delete-confirm"), onClick = { saveRecipes(recipes.filter { it.id != recipe.id }); deleting = null }) { Text(stringResource(R.string.recipe_delete), color = ink.inkRust) } },
            dismissButton = { TextButton({ deleting = null }) { Text(stringResource(R.string.visit_cancel)) } })
    }
}

private fun Modifier.clip50() = this.then(Modifier.background(androidx.compose.ui.graphics.Color.Transparent, RoundedCornerShape(50)))

@OptIn(ExperimentalFoundationApi::class)
@Composable private fun RecipeChip(recipe: ExportRecipe, current: Boolean, enabled: Boolean, load: () -> Unit, delete: () -> Unit) {
    val ink = LocalWheredayInk.current
    val deleteLabel = stringResource(R.string.recipe_delete)
    val loaded = stringResource(R.string.recipe_loaded)
    Box(Modifier.heightIn(min = 44.dp).background(ink.card, RoundedCornerShape(50)).border(1.dp, if(current) ink.inkRust else ink.border, RoundedCornerShape(50))
        .combinedClickable(enabled = enabled, role = Role.Button, onLongClick = delete, onClick = load)
        .semantics { customActions = listOf(CustomAccessibilityAction(deleteLabel) { delete(); true }); if(current) stateDescription = loaded }
        .padding(horizontal = 12.dp).testTag("recipe-chip"), contentAlignment = Alignment.Center) {
        Text(recipe.name, style = MaterialTheme.typography.labelMedium, color = ink.textPrimary, maxLines = 1)
    }
}

@Composable private fun <T> RecipeChoices(title: String, options: List<T>, selected: T, label: @Composable (T) -> String, dismiss: () -> Unit,
    enabledFor: (T) -> Boolean = { true }, choose: (T) -> Unit) {
    WorkspaceAlertDialog(onDismissRequest = dismiss, title = { Text(title) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()).semantics { selectableGroup(); testTagsAsResourceId = true }.testTag("recipe-choices")) {
            options.forEach { option ->
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).selectable(option == selected, enabled = enabledFor(option), role = Role.RadioButton) { choose(option) }.testTag("recipe-choice"),
                    verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    RadioButton(option == selected, null, enabled = enabledFor(option))
                    Text(label(option), color = if(enabledFor(option)) LocalWheredayInk.current.textPrimary else LocalWheredayInk.current.textMuted)
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.recipe_done)) } })
}

/** Formats: the first chosen is the primary format; at least one always stays chosen. */
@Composable private fun RecipeFormats(selection: ExportSelection, dismiss: () -> Unit, update: (ExportSelection) -> Unit) {
    val chosen = listOf(selection.format) + selection.additionalFormats.sortedBy { it.ordinal }
    WorkspaceAlertDialog(onDismissRequest = dismiss, title = { Text(stringResource(R.string.recipe_format_title)) }, text = {
        Column(Modifier.verticalScroll(rememberScrollState()).semantics { testTagsAsResourceId = true }.testTag("recipe-formats")) {
            ExportFormat.entries.forEach { format ->
                val on = format in chosen
                Row(Modifier.fillMaxWidth().heightIn(min = 48.dp).toggleable(on, enabled = !(on && chosen.size == 1), role = Role.Checkbox) { wanted ->
                    val next = if(wanted) chosen + format else chosen - format
                    update(selection.copy(format = next.first(), additionalFormats = next.drop(1).toSet()))
                }.testTag("recipe-format-${format.name}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    Checkbox(on, null, enabled = !(on && chosen.size == 1))
                    Text(format.title())
                }
            }
        }
    }, confirmButton = {}, dismissButton = { TextButton(dismiss) { Text(stringResource(R.string.recipe_done)) } })
}

@Composable private fun whatPhrase(what: RecipeWhat) = stringResource(when(what) {
    RecipeWhat.visits -> R.string.recipe_what_visits; RecipeWhat.routes -> R.string.recipe_what_routes
    RecipeWhat.outings -> R.string.recipe_what_outings; RecipeWhat.all -> R.string.recipe_what_all
})

private fun changeLabel(token: RecipeToken) = when(token) {
    RecipeToken.what -> R.string.recipe_change_what; RecipeToken.range -> R.string.recipe_change_range; RecipeToken.format -> R.string.recipe_change_format
    RecipeToken.layout -> R.string.recipe_change_layout; RecipeToken.destination -> R.string.recipe_change_destination
}

/** "the last 7 days", "October", "all of 2026", "Nov 2 – Nov 6", as iOS `rangePhrase`. */
@Composable internal fun rangePhrase(range: ExportRange, options: ExportOptions, today: LocalDate, locale: Locale): String {
    fun format(skeleton: String, date: LocalDate) = DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, skeleton), locale).format(date)
    return when(range.preset) {
        ExportRangePreset.allTime -> stringResource(R.string.recipe_range_all)
        ExportRangePreset.today -> stringResource(R.string.recipe_range_today)
        ExportRangePreset.yesterday -> stringResource(R.string.recipe_range_yesterday)
        ExportRangePreset.last7Days -> stringResource(R.string.recipe_range_7)
        ExportRangePreset.last30Days -> stringResource(R.string.recipe_range_30)
        ExportRangePreset.thisMonth -> stringResource(R.string.recipe_range_this_month)
        ExportRangePreset.month -> format(if(range.anchor!!.year == today.year) "MMMM" else "MMMMy", range.anchor)
        ExportRangePreset.year -> stringResource(R.string.recipe_range_year, range.anchor!!.year.toString())
        ExportRangePreset.custom -> {
            val zone = options.zone
            val first = options.from.takeIf { it != ExportOptions.allDatesStart }?.atZone(zone)?.toLocalDate()
            val last = options.until.takeIf { it != java.time.Instant.MAX }?.atZone(zone)?.toLocalDate()
            val skeleton = if((first ?: today).year == today.year && (last ?: today).year == today.year) "MMMd" else "MMMdy"
            when {
                first != null && last != null && first == last -> format(skeleton, first)
                first != null && last != null -> "${format(skeleton, first)} – ${format(skeleton, last)}"
                first != null -> stringResource(R.string.recipe_range_onward, format(skeleton, first))
                last != null -> stringResource(R.string.recipe_range_through, format(skeleton, last))
                else -> stringResource(R.string.recipe_range_all)
            }
        }
    }
}

/** The folder's own name from its tree URI, e.g. "Whereday" for primary:Documents/Whereday. */
internal fun exportFolderName(uri: String): String? = runCatching {
    android.provider.DocumentsContract.getTreeDocumentId(android.net.Uri.parse(uri)).substringAfter(':').trimEnd('/').substringAfterLast('/').ifEmpty { null }
}.getOrNull()

/** WHAT YOU'LL GET: the recipe in one line and the action that runs it. */
@Composable internal fun ArchiveOutcome(selection: ExportSelection, destination: ExportDestination, folderName: String?, enabled: Boolean, run: () -> Unit) {
    val ink = LocalWheredayInk.current
    val formats = (listOf(selection.format) + selection.additionalFormats.sortedBy { it.ordinal }).map { it.title() }
    val layout = stringResource(when { !selection.splitDays -> R.string.recipe_layout_one; selection.outings -> R.string.recipe_layout_outing; else -> R.string.recipe_layout_day })
    Column(Modifier.fillMaxWidth().background(ink.card, RoundedCornerShape(18.dp)).border(1.dp, ink.border, RoundedCornerShape(18.dp)).padding(16.dp).testTag("recipe-outcome"),
        verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Text(stringResource(R.string.recipe_outcome_title), style = WheredayType.mono(11, tracking = 1.5f), color = ink.textMuted)
        Text(stringResource(R.string.recipe_outcome_line, formats.joinToString(" + "), layout), style = WheredayType.title3, color = ink.textPrimary, modifier = Modifier.testTag("recipe-outcome-line"))
        TEActionButton(stringResource(if(destination == ExportDestination.folder && folderName != null) R.string.recipe_run_folder else R.string.recipe_run_share), run,
            Modifier.fillMaxWidth().testTag("recipe-run"), TEEmphasis.primary, enabled = enabled)
    }
}

