package com.bontecou.whereday

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import com.bontecou.whereday.export.*
import java.time.*

@Composable fun ExportToggle(label:String,value:Boolean,enabled:Boolean=true,change:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().toggleable(value=value,enabled=enabled,role=Role.Switch,onValueChange=change),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f));Switch(checked=value,onCheckedChange=null,enabled=enabled)
    }
}
@Composable fun ExportFilters(o:ExportOptions,change:(ExportOptions)->Unit,kind:DataKind,outings:Boolean=false) {
    var dates by remember {mutableStateOf(false)}
    val first=o.from.takeIf {it!=Instant.EPOCH}?.atZone(o.zone)?.toLocalDate() ?: LocalDate.now(o.zone)
    val last=o.until.takeIf {it!=Instant.MAX}?.atZone(o.zone)?.toLocalDate() ?: LocalDate.now(o.zone)
    if(dates) CustomDateRange(first,last,{dates=false}) {a,b ->change(o.copy(from=a.atStartOfDay(o.zone).toInstant(),until=b.plusDays(1).atStartOfDay(o.zone).toInstant().minusNanos(1)));dates=false}
    Text("Date range",style=MaterialTheme.typography.titleMedium)
    OutlinedButton(onClick={dates=true}) {Text(if(o.from==Instant.EPOCH && o.until==Instant.MAX) "All dates" else "$first – $last")}
    if(o.from!=Instant.EPOCH || o.until!=Instant.MAX) TextButton(onClick={change(o.copy(from=Instant.EPOCH,until=Instant.MAX))}) {Text("Reset to all dates")}
    Text("Time zone: ${o.zone.id}",style=MaterialTheme.typography.bodySmall)
    ExportToggle("Time of day",o.startTime!=null) {change(o.copy(startTime=if(it) LocalTime.of(8,0) else null,endTime=if(it) LocalTime.of(18,0) else null))}
    if(o.startTime!=null) {
        ExportTime("From time",o.startTime!!) {change(o.copy(startTime=it))}
        ExportTime("Until time",o.endTime!!) {change(o.copy(endTime=it))}
        Text("A window crossing midnight includes matching times on both sides of midnight.",style=MaterialTheme.typography.bodySmall)
    }
    if(outings || kind!=DataKind.points) {
        ExportToggle("Only completed visits",o.completedOnly) {change(o.copy(completedOnly=it))}
        Choice("Minimum visit minutes",o.minimumVisitMinutes.toString(),listOf("0","5","15","30","60")) {change(o.copy(minimumVisitMinutes=it.toLong()))}
        if(outings) Text("Visit filters apply to aggregate outing files. One file per outing includes all linked visits.",style=MaterialTheme.typography.bodySmall)
        Text("Visit fields",style=MaterialTheme.typography.titleMedium)
        ExportToggle("Visit coordinates",o.visitCoordinates) {change(o.copy(visitCoordinates=it))}
        ExportToggle("Location name",o.visitName) {change(o.copy(visitName=it))}
        ExportToggle("Address",o.visitAddress) {change(o.copy(visitAddress=it))}
        ExportToggle("Visit notes",o.visitNotes) {change(o.copy(visitNotes=it))}
        ExportToggle("Visit duration",o.visitDuration) {change(o.copy(visitDuration=it))}
    }
    if(outings || kind!=DataKind.visits) {
        if(!outings) {
        ExportToggle("Exclude GPS glitches",o.excludeOutliers) {change(o.copy(excludeOutliers=it))}
        Choice("Maximum accuracy (m)",o.maximumAccuracy?.toInt()?.toString() ?: "Any",listOf("Any","10","25","50","100")) {change(o.copy(maximumAccuracy=it.toDoubleOrNull()))}
        }
        Text("Point fields",style=MaterialTheme.typography.titleMedium)
        ExportToggle("Altitude",o.pointAltitude) {change(o.copy(pointAltitude=it))}
        ExportToggle("Speed",o.pointSpeed) {change(o.copy(pointSpeed=it))}
        ExportToggle("Accuracy",o.pointAccuracy) {change(o.copy(pointAccuracy=it))}
        ExportToggle("Outlier flag",o.pointOutlier) {change(o.copy(pointOutlier=it))}
    }
}
@Composable private fun ExportTime(label:String,value:LocalTime,change:(LocalTime)->Unit) {
    val context=LocalContext.current
    OutlinedButton(onClick={TimePickerDialog(context,{_,hour,minute ->change(LocalTime.of(hour,minute))},value.hour,value.minute,DateFormat.is24HourFormat(context)).show()}) {Text("$label: $value")}
}
