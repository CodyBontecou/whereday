package com.bontecou.whereday

import android.content.ClipData
import android.content.Context
import android.content.Intent
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
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
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.graphics.layer.GraphicsLayer
import androidx.compose.ui.graphics.layer.drawLayer
import androidx.compose.ui.graphics.rememberGraphicsLayer
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.FileProvider
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.text.NumberFormat
import java.time.Instant
import java.time.YearMonth
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@Composable internal fun MonthRecapScreen(graph: AppGraph, metric: Boolean, back: () -> Unit) {
    val zone = remember { ZoneId.systemDefault() }
    val current = remember { YearMonth.now(zone) }
    var offset by rememberSaveable { mutableIntStateOf(0) }
    val month = current.plusMonths(offset.toLong())
    val inputs by remember(month) { graph.history.observeMonthRecap(month, zone) }.collectAsStateWithLifecycle(null)
    val unnamed = stringResource(R.string.timeline_visit)
    val recap = remember(inputs) {
        inputs?.takeIf { it.second.month == month }?.let { (visits, distance) ->
            MonthRecap(visits, distance, month, zone, { it.name?.trim()?.takeIf(String::isNotEmpty) ?: it.address?.trim()?.takeIf(String::isNotEmpty) ?: unnamed }, Instant.now())
        }
    }
    MonthRecapContent(recap, month, month < current, metric, { offset-- }, { offset++ }, back)
}

@Composable internal fun MonthRecapContent(recap: MonthRecap?, month: YearMonth, canForward: Boolean, metric: Boolean, previous: () -> Unit, next: () -> Unit, back: () -> Unit) {
    val ink = LocalWheredayInk.current
    BackHandler(onBack = back)
    val context = LocalContext.current
    val locale = LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val scope = rememberCoroutineScope()
    val layer = rememberGraphicsLayer()
    var sharing by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf(false) }
    val monthTitle = remember(month, locale) { DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMMy"), locale).format(month) }
    val chooser = stringResource(R.string.recap_share_title, monthTitle)
    val rtl = LocalLayoutDirection.current == LayoutDirection.Rtl
    Column(Modifier.fillMaxSize().background(ink.surface).verticalScroll(rememberScrollState()).semantics { testTagsAsResourceId = true }.testTag("recap-screen"),
        horizontalAlignment = Alignment.CenterHorizontally) {
        Column(Modifier.widthIn(max = 480.dp).fillMaxWidth().padding(horizontal = 16.dp), verticalArrangement = Arrangement.spacedBy(18.dp)) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                TextButton(back, Modifier.testTag("recap-done")) { Text(stringResource(R.string.recap_done), color = ink.accent) }
                Spacer(Modifier.weight(1f))
                Text(stringResource(R.string.recap_title), style = WheredayType.headline, color = ink.textPrimary, modifier = Modifier.semantics { heading() })
                Spacer(Modifier.weight(1f)); Spacer(Modifier.width(64.dp))
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                IconButton(previous, Modifier.testTag("recap-previous")) {
                    Icon(if(rtl) Icons.AutoMirrored.Outlined.KeyboardArrowRight else Icons.AutoMirrored.Outlined.KeyboardArrowLeft, stringResource(R.string.recap_previous), tint = ink.textPrimary)
                }
                Text(monthTitle, Modifier.weight(1f).testTag("recap-month"), style = WheredayType.title3, color = ink.textPrimary, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                IconButton(next, Modifier.testTag("recap-next"), enabled = canForward) {
                    Icon(if(rtl) Icons.AutoMirrored.Outlined.KeyboardArrowLeft else Icons.AutoMirrored.Outlined.KeyboardArrowRight, stringResource(R.string.recap_next), tint = ink.textPrimary.copy(alpha = if(canForward) 1f else 0.35f))
                }
            }
            if(recap == null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else {
                // The card is drawn in the night palette so it reads the same wherever it is shared.
                Box(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).drawWithContent { layer.record { this@drawWithContent.drawContent() }; drawLayer(layer) }) {
                    WheredayTheme(true) { MonthRecapCard(recap, monthTitle, metric, locale) }
                }
                TEActionButton(stringResource(R.string.recap_share), {
                    if(sharing) return@TEActionButton
                    sharing = true; error = false
                    scope.launch {
                        try { shareCard(context, layer, month, chooser) }
                        catch(e: Exception) { if(e is CancellationException) throw e; error = true }
                        finally { sharing = false }
                    }
                }, Modifier.fillMaxWidth().testTag("recap-share"), TEEmphasis.primary, enabled = !sharing)
                if(error) Text(stringResource(R.string.recap_share_error), color = MaterialTheme.colorScheme.error)
            }
            Text(stringResource(R.string.recap_privacy), style = WheredayType.mono(11), color = ink.textMuted, modifier = Modifier.padding(bottom = 24.dp))
        }
    }
}

/** Renders the captured card to a PNG in the shared cache folder and offers it to other apps. */
private suspend fun shareCard(context: Context, layer: GraphicsLayer, month: YearMonth, title: String) {
    val bitmap = layer.toImageBitmap().asAndroidBitmap()
    val file = withContext(Dispatchers.IO) {
        val folder = File(context.cacheDir, "recaps").apply { mkdirs() }
        // One file per month; older cards are replaced rather than accumulated.
        folder.listFiles()?.forEach { it.delete() }
        File(folder, "whereday-recap-$month.png").also { out -> out.outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, it) } }
    }
    val uri = FileProvider.getUriForFile(context, "${context.packageName}.exports", file)
    val send = Intent(Intent.ACTION_SEND).setType("image/png").putExtra(Intent.EXTRA_STREAM, uri).putExtra(Intent.EXTRA_TITLE, title)
        .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION).also { it.clipData = ClipData.newRawUri(title, uri) }
    context.startActivity(Intent.createChooser(send, title).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
}

@Composable private fun MonthRecapCard(recap: MonthRecap, monthTitle: String, metric: Boolean, locale: Locale) {
    val ink = LocalWheredayInk.current
    val number = remember(locale) { NumberFormat.getIntegerInstance(locale) }
    val days = pluralStringResource(R.plurals.recap_days, recap.recordedDays, recap.recordedDays)
    val placesLine = pluralStringResource(R.plurals.recap_places, recap.placeCount, recap.placeCount)
    val (kind, count) = recap.closing
    val closing = pluralStringResource(when(kind) { MonthRecap.Closing.cities -> R.plurals.recap_cities; MonthRecap.Closing.countries -> R.plurals.recap_countries; MonthRecap.Closing.visits -> R.plurals.recap_visits }, count, count)
    val monthName = recap.month.month.getDisplayName(java.time.format.TextStyle.FULL_STANDALONE, locale)
    Column(Modifier.fillMaxWidth().background(ink.surface).padding(start = 28.dp, end = 28.dp, top = 36.dp, bottom = 28.dp).testTag("recap-card"), verticalArrangement = Arrangement.spacedBy(18.dp)) {
        Row(verticalAlignment = Alignment.Bottom) {
            Text(stringResource(R.string.recap_brand), Modifier.weight(1f), style = WheredayType.title3.copy(fontStyle = FontStyle.Italic), color = ink.textPrimary)
            Text(monthTitle.uppercase(locale), style = WheredayType.mono(11), color = ink.textMuted)
        }
        Text(buildAnnotatedString {
            append(days); append("\n"); append(placesLine); append("\n")
            withStyle(SpanStyle(color = ink.inkRust, fontStyle = FontStyle.Italic)) { append(closing) }
        }, style = WheredayType.editorial(44).copy(lineHeight = 46.sp), color = ink.textPrimary, modifier = Modifier.testTag("recap-headline"))
        val bars = recap.barValues
        val maximum = maxOf(bars.maxOrNull() ?: 0.0, 1.0)
        val threshold = recap.highlightThreshold
        val barsLabel = stringResource(if(recap.hasDistance) R.string.recap_bars_distance else R.string.recap_bars_places, monthName)
        Canvas(Modifier.fillMaxWidth().height(60.dp).clearAndSetSemantics { testTag = "recap-bars"; contentDescription = barsLabel }) {
            val slot = size.width / bars.size
            val width = minOf(6.dp.toPx(), slot)
            bars.forEachIndexed { index, value ->
                val height = maxOf(6.dp.toPx(), (60.dp.toPx() * value / maximum).toFloat())
                val x = slot * index + (slot - width) / 2
                drawRoundRect(if(value > 0 && value >= threshold) ink.liveDot else ink.border, Offset(x, size.height - height), Size(width, height), CornerRadius(width / 2))
            }
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            val moved = if(recap.hasDistance) todayDistance(recap.totalDistance, metric, locale) to stringResource(R.string.recap_moved)
                else number.format(recap.visitCount) to stringResource(R.string.recap_visits_label)
            listOf(moved, number.format(recap.newPlaces) to stringResource(R.string.recap_new_places),
                (recap.biggestDay?.let { DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale, "MMMd"), locale).format(it) } ?: "—") to stringResource(R.string.recap_biggest_day)
            ).forEach { (value, label) ->
                Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}.testTag("recap-stat")) {
                    Text(value, style = WheredayType.title, color = ink.textPrimary, maxLines = 1)
                    Text(label, style = WheredayType.mono(11), color = ink.textMuted)
                }
            }
        }
        Column {
            recap.topPlaces.forEachIndexed { index, place ->
                Row(Modifier.fillMaxWidth().padding(vertical = 8.dp).semantics(mergeDescendants = true) {}.testTag("recap-top-place"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(String.format(locale, "%02d", index + 1), Modifier.width(22.dp), style = WheredayType.mono(11), color = ink.textMuted)
                    Text(place.name, Modifier.weight(1f), style = WheredayType.title3, color = ink.textPrimary, maxLines = 1)
                    Text(recapHours(place.duration), style = WheredayType.mono(12), color = ink.textMuted)
                }
                Box(Modifier.fillMaxWidth().height(1.dp).background(ink.border))
            }
        }
        Text(stringResource(R.string.recap_footer), Modifier.padding(top = 8.dp), style = WheredayType.mono(11), color = ink.textMuted)
    }
}

@Composable private fun recapHours(duration: java.time.Duration): String =
    if(duration < java.time.Duration.ofHours(1)) placeDuration(duration) else stringResource(R.string.place_duration_hours, duration.toHours().toInt())
