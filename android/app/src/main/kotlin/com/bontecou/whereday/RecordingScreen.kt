package com.bontecou.whereday

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.FiberManualRecord
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.GpsNotFixed
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.data.RecordingRow
import java.time.LocalDate
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale

fun distanceText(meters: Double, metric: Boolean,locale:Locale=Locale.getDefault()): String = if (metric) String.format(locale, "%.2f km", meters / 1000) else String.format(locale, "%.2f mi", meters / 1609.344)
fun timeText(time: java.time.Instant): String = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(time)

/** The Trace header (mono date caption over a serif title) and the recording card: ink with a live dot while recording, paper on standby. */
@Composable fun RecordingControls(active: Boolean,state: RecordingRow?,header:Boolean=true,record: () -> Unit) {
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val today=remember(locale) {DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(LocalDate.now())}
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if(header) WdPageHeader(stringResource(R.string.recording_journal_title),eyebrow=stringResource(R.string.map_redesign_header_eyebrow,today),compact=true)
        Column(Modifier.fillMaxWidth().padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            val summary=stringResource(if (active) R.string.recording_active_summary else R.string.recording_idle_summary)
            val label=stringResource(if (active) R.string.shortcut_stop else R.string.shortcut_start)
            if(active) WdInkCard {
                WdLiveDot()
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text(stringResource(R.string.map_redesign_recording).uppercase(),style=WdType.eyebrow,color=wd.onInkCardMuted)
                    Text(summary,style=MaterialTheme.typography.bodyMedium,color=wd.onInkCard)
                }
                RecordingStopPill(label,record)
            } else WdCard {
                Column(Modifier.padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                        Box(Modifier.size(8.dp).clip(CircleShape).background(wd.textMuted.copy(alpha=0.35f)))
                        Text(stringResource(R.string.map_redesign_standby).uppercase(),style=WdType.eyebrow,color=wd.textMuted)
                    }
                    Text(summary,style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
                    WdButton(label,record,Modifier.heightIn(min=56.dp),emphasis=WdEmphasis.primary,icon=Icons.Filled.FiberManualRecord)
                }
            }
            state?.interruption?.let {
                val shape=RoundedCornerShape(16.dp)
                Row(Modifier.fillMaxWidth().clip(shape).background(wd.secondaryContainer).padding(16.dp),
                    horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.Top) {
                    Icon(Icons.Outlined.ErrorOutline,null,Modifier.size(20.dp),tint=wd.onSecondaryContainer)
                    Text(recordingInterruptionText(it), Modifier.weight(1f).testTag("recording-interruption"), color = wd.onSecondaryContainer,style=MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
}

/** The stop control on the ink card: a paper capsule with a rust stop square, as on iOS. */
@Composable private fun RecordingStopPill(label:String,onClick:()->Unit) {
    Row(Modifier.heightIn(min=48.dp).clip(CircleShape).background(wd.onInkCard).clickable(role=Role.Button,onClick=onClick).padding(horizontal=14.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        Icon(Icons.Filled.Stop,null,Modifier.size(16.dp),tint=wd.inkRust)
        Text(label,style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=wd.inkCard)
    }
}

@Composable fun RecordingScreen(points: List<TrackPoint>, visits: List<Visit>, summary: PointSummary, metric: Boolean,mapContent:(@Composable ()->Unit)?=null) {
    val sorted = remember(points) { points.sortedBy { it.time } }
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val statistics=listOf(stringResource(R.string.recording_point_label) to summary.count.toString(),stringResource(R.string.recording_visit_label) to visits.size.toString(),stringResource(R.string.recording_distance_label) to distanceText(summary.distanceMeters,metric,locale))
    Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        if(mapContent!=null) mapContent() else JournalMap(sorted, visits,followMetric=metric)
        Column(verticalArrangement=Arrangement.spacedBy(0.dp)) {
            Text(stringResource(R.string.map_redesign_selected_dates).uppercase(),style=WdType.eyebrow,color=wd.textMuted,modifier=Modifier.padding(bottom=6.dp))
            // RouteReplayStat rhythm: hairline rule, then serif figures over mono captions.
            WdDivider()
            if(LocalDensity.current.fontScale>=1.3f) Column(Modifier.padding(top=12.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                statistics.forEach {(label,value)->Stat(label,value,Modifier.fillMaxWidth())}
            } else Row(Modifier.fillMaxWidth().padding(top=12.dp), horizontalArrangement = Arrangement.SpaceBetween) {
                statistics.forEach {(label,value)->Stat(label,value,Modifier.weight(1f))}
            }
        }
        if(summary.count>sorted.size) Text(stringResource(R.string.recording_sample_summary,sorted.size,summary.count),style=WdType.caption2,color=wd.textMuted)
        sorted.lastOrNull()?.let { point ->
            WdCard {
                Row(Modifier.padding(horizontal=16.dp,vertical=14.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    WdLiveDot(color=wd.accent)
                    Column(verticalArrangement=Arrangement.spacedBy(3.dp)) {
                        Text(stringResource(R.string.recording_latest_fix,timeText(point.time)), style = WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=wd.textPrimary)
                        Text(String.format(Locale.getDefault(), "%.5f, %.5f · ±%.0f m", point.coordinate.latitude, point.coordinate.longitude, point.accuracy), style = WdType.caption2,color=wd.textMuted)
                    }
                }
            }
        }
        if (sorted.isEmpty()) WdNotice(stringResource(R.string.recording_offline_hint),icon=Icons.Outlined.GpsNotFixed)
    }
}

@Composable private fun Stat(label: String, value: String,modifier:Modifier=Modifier) { Column(modifier,verticalArrangement = Arrangement.spacedBy(2.dp)) { Text(value, style = WdType.title2,color=wd.textPrimary); Text(label, style = WdType.caption2,color=wd.textMuted) } }

@Composable fun RoutePreview(points: List<TrackPoint>) {
    val accent = wd.accent
    val start = wd.placeInks[3]
    val end = wd.placeInks[1]
    val paper = wd.card
    val clean = remember(points) { points.filterNot { it.outlier } }
    WdCard(radius=18.dp) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.recording_route_title), style = WdType.title3,color=wd.textPrimary)
            Canvas(Modifier.fillMaxWidth().height(220.dp).clip(RoundedCornerShape(12.dp)).background(wd.surface)) {
                if (clean.isNotEmpty()) {
                    val minLat = clean.minOf { it.coordinate.latitude }; val maxLat = clean.maxOf { it.coordinate.latitude }
                    val minLon = clean.minOf { it.coordinate.longitude }; val maxLon = clean.maxOf { it.coordinate.longitude }
                    val latitudeScale = kotlin.math.cos(Math.toRadians((minLat + maxLat) / 2)).coerceAtLeast(0.01)
                    val widthDegrees = ((maxLon - minLon) * latitudeScale).coerceAtLeast(0.001)
                    val heightDegrees = (maxLat - minLat).coerceAtLeast(0.001)
                    val scale = minOf((size.width - 32) / widthDegrees, (size.height - 32) / heightDegrees)
                    fun position(p: TrackPoint) = Offset((size.width / 2 + (p.coordinate.longitude - (minLon + maxLon) / 2) * latitudeScale * scale).toFloat(), (size.height / 2 - (p.coordinate.latitude - (minLat + maxLat) / 2) * scale).toFloat())
                    val path = Path()
                    clean.forEachIndexed { i, point -> val p = position(point); if (i == 0) path.moveTo(p.x, p.y) else path.lineTo(p.x, p.y) }
                    drawPath(path, accent, style = Stroke(3.dp.toPx(),cap=StrokeCap.Round,join=StrokeJoin.Round))
                    drawCircle(paper, 8.dp.toPx(), position(clean.first())); drawCircle(start, 5.dp.toPx(), position(clean.first()))
                    drawCircle(paper, 10.dp.toPx(), position(clean.last())); drawCircle(end, 7.dp.toPx(), position(clean.last()))
                }
            }
            Text(if (clean.isEmpty()) stringResource(R.string.recording_route_empty) else androidx.compose.ui.res.pluralStringResource(R.plurals.recording_route_points,clean.size,java.text.NumberFormat.getIntegerInstance(androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(clean.size)), style = WdType.caption,color=wd.textMuted)
        }
    }
}
