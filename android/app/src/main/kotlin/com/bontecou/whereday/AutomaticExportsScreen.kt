package com.bontecou.whereday

import android.app.TimePickerDialog
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.export.*
import com.bontecou.whereday.platform.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable fun AutomaticExportsScreen(graph:AppGraph,folderConfigured:Boolean,back:()->Unit) {
    val ui by graph.automaticExportController.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    val state=ui.state;val settings=state.settings;val pending=state.pending
    val editable=ui.loaded && !ui.busy && pending==null
    var reset by remember {mutableStateOf(false)}
    BackHandler(onBack=back)
    LaunchedEffect(Unit) {graph.automaticExportController.refresh()}
    fun date(instant:Instant,zone:ZoneId=ZoneId.systemDefault())=DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,FormatStyle.SHORT).withZone(zone).format(instant)
    val repeats=listOf("Once daily")+(1..23).map {"Every $it ${if(it==1) "hour" else "hours"}"}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        TextButton(onClick=back) {Text("Back to settings")}
        Text("Automatic exports",style=MaterialTheme.typography.headlineMedium)
        Text("Save the current day's history to your export folder. Android may delay background runs; opening Whereday checks the latest due run.")
        if(!ui.loaded && ui.error==null) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Loading automatic export settings…")}
        if(ui.loaded) {
            ExportToggle("Enable automatic exports",settings.enabled,enabled=!ui.busy || settings.enabled) {graph.automaticExportController.configure(settings.copy(enabled=it))}
            OutlinedButton(enabled=editable,onClick={TimePickerDialog(context,{_,hour,minute ->graph.automaticExportController.configure(settings.copy(schedule=settings.schedule.copy(hour=hour,minute=minute)))},settings.schedule.hour,settings.schedule.minute,DateFormat.is24HourFormat(context)).show()}) {
                Text("Daily anchor: ${java.time.LocalTime.of(settings.schedule.hour,settings.schedule.minute)}")
            }
            Text("Time zone: ${ZoneId.systemDefault().id}",style=MaterialTheme.typography.bodySmall)
            Choice("Repeat",repeats[settings.schedule.intervalHours],repeats,editable) {value ->graph.automaticExportController.configure(settings.copy(schedule=settings.schedule.copy(intervalHours=repeats.indexOf(value))))}
            Choice("Export format",settings.format.name,ExportFormat.entries.map {it.name},editable) {value ->graph.automaticExportController.configure(settings.copy(format=ExportFormat.valueOf(value)))}
            Choice("Export data",if(settings.outings) "outings" else settings.kind.name,DataKind.entries.map {it.name}+"outings",editable) {value ->
                graph.automaticExportController.configure(if(value=="outings") settings.copy(outings=true) else settings.copy(outings=false,kind=DataKind.valueOf(value)))
            }
            if(settings.outings) Text("Outings are selected by their start time. Complete routes are included. Inferred outings follow Settings; a pending retry keeps its captured inference choices.",style=MaterialTheme.typography.bodySmall)
            if(settings.schedule.intervalHours>0) {
                Choice("Update day file",settings.fileMode.name,ScheduledFileMode.entries.map {it.name},editable) {value ->graph.automaticExportController.configure(settings.copy(fileMode=ScheduledFileMode.valueOf(value)))}
                if(settings.format in setOf(ExportFormat.gpx,ExportFormat.kml)) Text("GPX and KML always rewrite the complete day so far.",style=MaterialTheme.typography.bodySmall)
            }
            Text(if(folderConfigured) "Export folder selected. Uses the filename template from Export." else "Choose an export folder in Settings before running.",style=MaterialTheme.typography.bodySmall)
            Text("Verified lifetime export access is required. Once-daily runs rewrite the complete day so far. Missed historical days can be exported manually.",style=MaterialTheme.typography.bodySmall)
            HorizontalDivider()
            Text(state.lastSuccess?.let {"Last successful export: ${date(it)}"} ?: "No successful automatic export yet.")
            ui.nextAttempt?.let {Text("Next planned run: ${date(it)}. Android may delay it.")}
            if(settings.enabled && pending!=null && ui.nextAttempt==null) Text("Android has a saved retry request. Its timing may be delayed.",style=MaterialTheme.typography.bodySmall)
            pending?.let {Text("Pending export: ${date(it.plan.filenameDate,it.plan.options.zone)}. Retry keeps this captured window and destination.")}
            if(ui.busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Updating automatic exports…")}
            if(pending!=null) OutlinedButton(enabled=!ui.busy,onClick=graph.automaticExportController::retry) {Text("Retry pending export")}
            else OutlinedButton(enabled=!ui.busy,onClick=graph.automaticExportController::runNow) {Text("Run export now")}
            if(pending!=null || ui.busy) TextButton(onClick=graph.automaticExportController::cancelPending) {Text("Cancel pending export")}
        }
        state.lastError?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        state.lastNotice?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
        ui.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        ui.message?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
        if(!ui.loaded && ui.error!=null) {
            TextButton(enabled=!ui.busy,onClick=graph.automaticExportController::refresh) {Text("Retry loading automatic exports")}
            TextButton(enabled=!ui.busy,onClick={reset=true}) {Text("Reset automatic export settings")}
        }
    }
    if(reset) AlertDialog(onDismissRequest={reset=false},title={Text("Reset automatic exports?")},text={Text("This disables the schedule and clears its saved requests. Exported documents and preserved backups remain in their folder.")},confirmButton={TextButton(onClick={reset=false;graph.automaticExportController.reset()}) {Text("Reset automatic exports")}},dismissButton={TextButton(onClick={reset=false}) {Text("Cancel")}})
}
