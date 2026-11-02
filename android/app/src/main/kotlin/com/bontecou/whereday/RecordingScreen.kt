package com.bontecou.whereday

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalConfiguration
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.data.RecordingRow
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

fun distanceText(meters: Double, metric: Boolean,locale:Locale=Locale.getDefault()): String = if (metric) String.format(locale, "%.2f km", meters / 1000) else String.format(locale, "%.2f mi", meters / 1609.344)
fun timeText(time: java.time.Instant): String = DateTimeFormatter.ofPattern("MMM d, HH:mm").withZone(ZoneId.systemDefault()).format(time)

@Composable fun RecordingControls(active: Boolean,state: RecordingRow?,record: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        Text(stringResource(R.string.recording_journal_title), style = MaterialTheme.typography.headlineSmall)
        Text(stringResource(if (active) R.string.recording_active_summary else R.string.recording_idle_summary), color = MaterialTheme.colorScheme.onSurfaceVariant)
        FilledTonalButton(onClick = record, modifier = Modifier.fillMaxWidth().heightIn(min = 56.dp)) { Text(stringResource(if (active) R.string.shortcut_stop else R.string.shortcut_start)) }
        state?.interruption?.let { Text(recordingInterruptionText(it), Modifier.testTag("recording-interruption"), color = MaterialTheme.colorScheme.error) }
    }
}

@Composable fun RecordingScreen(points: List<TrackPoint>, visits: List<Visit>, summary: PointSummary, metric: Boolean,mapContent:(@Composable ()->Unit)?=null) {
    val sorted = remember(points) { points.sortedBy { it.time } }
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val statistics=listOf(stringResource(R.string.recording_point_label) to summary.count.toString(),stringResource(R.string.recording_visit_label) to visits.size.toString(),stringResource(R.string.recording_distance_label) to distanceText(summary.distanceMeters,metric,locale))
    Column(Modifier.fillMaxWidth().padding(20.dp), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        if(LocalDensity.current.fontScale>=1.3f) Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
            statistics.forEach {(label,value)->Stat(label,value,Modifier.fillMaxWidth())}
        } else Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
            statistics.forEach {(label,value)->Stat(label,value,Modifier.weight(1f))}
        }
        if(mapContent!=null) mapContent() else JournalMap(sorted, visits,followMetric=metric)
        if(summary.count>sorted.size) Text(stringResource(R.string.recording_sample_summary,sorted.size,summary.count),style=MaterialTheme.typography.bodySmall)
        sorted.lastOrNull()?.let { point ->
            Text(stringResource(R.string.recording_latest_fix,timeText(point.time)), style = MaterialTheme.typography.titleSmall)
            Text(String.format(Locale.getDefault(), "%.5f, %.5f · ±%.0f m", point.coordinate.latitude, point.coordinate.longitude, point.accuracy), style = MaterialTheme.typography.bodyMedium)
        }
        if (sorted.isEmpty()) Text(stringResource(R.string.recording_offline_hint))
    }
}

@Composable private fun Stat(label: String, value: String,modifier:Modifier=Modifier) { Column(modifier,verticalArrangement = Arrangement.spacedBy(4.dp)) { Text(value, style = MaterialTheme.typography.titleLarge); Text(label, style = MaterialTheme.typography.labelSmall) } }

@Composable fun RoutePreview(points: List<TrackPoint>) {
    val accent = MaterialTheme.colorScheme.primary
    val background = MaterialTheme.colorScheme.surfaceContainer
    val clean = remember(points) { points.filterNot { it.outlier } }
    Surface(shape = MaterialTheme.shapes.large, color = background) {
        Column(Modifier.padding(16.dp)) {
            Text(stringResource(R.string.recording_route_title), style = MaterialTheme.typography.titleMedium)
            Canvas(Modifier.fillMaxWidth().height(220.dp)) {
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
                    drawPath(path, accent, style = Stroke(3.dp.toPx()))
                    drawCircle(accent, 5.dp.toPx(), position(clean.first()))
                    drawCircle(accent, 7.dp.toPx(), position(clean.last()))
                }
            }
            Text(if (clean.isEmpty()) stringResource(R.string.recording_route_empty) else androidx.compose.ui.res.pluralStringResource(R.plurals.recording_route_points,clean.size,java.text.NumberFormat.getIntegerInstance(androidx.compose.ui.platform.LocalConfiguration.current.locales[0]).format(clean.size)), style = MaterialTheme.typography.labelMedium)
        }
    }
}
