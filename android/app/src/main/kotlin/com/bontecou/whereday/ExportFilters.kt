package com.bontecou.whereday

import android.text.format.DateFormat
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import com.bontecou.whereday.export.*
import java.text.NumberFormat
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

@Composable fun ExportToggle(label:String,value:Boolean,enabled:Boolean=true,modifier:Modifier=Modifier,change:(Boolean)->Unit) {
    Row(modifier.fillMaxWidth().toggleable(value=value,enabled=enabled,role=Role.Switch,onValueChange=change),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=androidx.compose.ui.Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f));Switch(checked=value,onCheckedChange=null,enabled=enabled)
    }
}
@Composable fun ExportFilters(o:ExportOptions,change:(ExportOptions)->Unit,kind:DataKind,outings:Boolean=false,enabled:Boolean=true,settingsReady:Boolean=true) {
    var dates by rememberSaveable(stateSaver=ExportDateDraft.Saver) {mutableStateOf<ExportDateDraft?>(null)}
    val latestOptions by rememberUpdatedState(o)
    val latestChange by rememberUpdatedState(change)
    val latestEnabled by rememberUpdatedState(enabled)
    val latestReady by rememberUpdatedState(settingsReady)
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val active=remember(lifecycle) {java.util.concurrent.atomic.AtomicBoolean(true)}
    DisposableEffect(lifecycle) {onDispose {active.set(false)}}
    LaunchedEffect(enabled,settingsReady,o.from,o.until,o.zone) {
        if(settingsReady && (!enabled || dates?.matches(o)==false)) dates=null
    }
    val locale=LocalConfiguration.current.locales[0]
    val date=remember(locale) {DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale)}
    val integer=remember(locale) {NumberFormat.getIntegerInstance(locale)}
    val decimal=remember(locale) {NumberFormat.getNumberInstance(locale).apply {maximumFractionDigits=340}}
    val any=stringResource(R.string.export_accuracy_any)
    val today=LocalDate.now(o.zone)
    val from=o.from.takeIf {it!=ExportOptions.allDatesStart}?.atZone(o.zone)?.toLocalDate()
    val until=o.until.takeIf {it!=Instant.MAX}?.atZone(o.zone)?.toLocalDate()
    val first=from ?: minOf(today,until ?: today)
    val last=until ?: maxOf(today,first)
    dates?.let {request->key(request.id) {
        CustomDateRange(request.first,request.last,{if(dates===request) dates=null},zone=request.zone,enabled=enabled && settingsReady) {a,b->
            if(active.get() && latestEnabled && latestReady && dates===request && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
                request.applyTo(latestOptions,a,b)?.let {value->dates=null;latestChange(value)}
            }
        }
    }}
    Text(stringResource(R.string.export_date_range),style=MaterialTheme.typography.titleMedium)
    OutlinedButton(enabled=enabled && settingsReady,onClick={
        if(active.get() && latestEnabled && latestReady && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) dates=ExportDateDraft.open(latestOptions)
    },modifier=Modifier.testTag("export-date-range")) {Text(when {
        o.from==ExportOptions.allDatesStart && o.until==Instant.MAX->stringResource(R.string.export_all_dates)
        o.until==Instant.MAX->stringResource(R.string.export_from_date,date.format(first))
        o.from==ExportOptions.allDatesStart->stringResource(R.string.export_through_date,date.format(last))
        else->stringResource(R.string.export_between_dates,date.format(first),date.format(last))
    })}
    if(o.from!=ExportOptions.allDatesStart || o.until!=Instant.MAX) TextButton(enabled=enabled,onClick={change(o.copy(from=ExportOptions.allDatesStart,until=Instant.MAX))},modifier=Modifier.testTag("export-reset-dates")) {Text(stringResource(R.string.export_reset_dates))}
    Text(stringResource(R.string.export_time_zone,o.zone.id),style=MaterialTheme.typography.bodySmall)
    ExportToggle(stringResource(R.string.export_time_of_day),o.startTime!=null,enabled,Modifier.testTag("export-time-enabled")) {change(o.copy(startTime=if(it) LocalTime.of(8,0) else null,endTime=if(it) LocalTime.of(18,0) else null))}
    if(o.startTime!=null) {
        ExportTime(stringResource(R.string.export_from_time),o.startTime!!,enabled,"export-time-from",settingsReady) {change(o.copy(startTime=it))}
        ExportTime(stringResource(R.string.export_until_time),o.endTime!!,enabled,"export-time-until",settingsReady) {change(o.copy(endTime=it))}
        Text(stringResource(R.string.export_overnight_help),style=MaterialTheme.typography.bodySmall)
    }
    if(outings || kind!=DataKind.points) {
        ExportToggle(stringResource(R.string.export_completed_visits),o.completedOnly,enabled,Modifier.testTag("export-completed")) {change(o.copy(completedOnly=it))}
        Choice(stringResource(R.string.export_minimum_minutes),o.minimumVisitMinutes.toString(),listOf("0","5","15","30","60"),enabled=enabled,labelFor={integer.format(it.toLong())}) {change(o.copy(minimumVisitMinutes=it.toLong()))}
        if(outings) Text(stringResource(R.string.export_outing_filters_help),style=MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.export_visit_fields),style=MaterialTheme.typography.titleMedium)
        ExportToggle(stringResource(R.string.export_visit_coordinates),o.visitCoordinates,enabled,Modifier.testTag("export-visit-coordinates")) {change(o.copy(visitCoordinates=it))}
        ExportToggle(stringResource(R.string.export_visit_name),o.visitName,enabled,Modifier.testTag("export-visit-name")) {change(o.copy(visitName=it))}
        ExportToggle(stringResource(R.string.export_visit_address),o.visitAddress,enabled,Modifier.testTag("export-visit-address")) {change(o.copy(visitAddress=it))}
        ExportToggle(stringResource(R.string.export_visit_notes),o.visitNotes,enabled,Modifier.testTag("export-visit-notes")) {change(o.copy(visitNotes=it))}
        ExportToggle(stringResource(R.string.export_visit_duration),o.visitDuration,enabled,Modifier.testTag("export-visit-duration")) {change(o.copy(visitDuration=it))}
    }
    if(outings || kind!=DataKind.visits) {
        if(!outings) {
            ExportToggle(stringResource(R.string.export_exclude_outliers),o.excludeOutliers,enabled,Modifier.testTag("export-exclude-outliers")) {change(o.copy(excludeOutliers=it))}
            Choice(stringResource(R.string.export_maximum_accuracy),o.maximumAccuracy?.toString() ?: "any",listOf("any","10","25","50","100"),enabled=enabled,labelFor={if(it=="any") any else decimal.format(it.toDouble())}) {change(o.copy(maximumAccuracy=it.toDoubleOrNull()))}
        }
        Text(stringResource(R.string.export_point_fields),style=MaterialTheme.typography.titleMedium)
        ExportToggle(stringResource(R.string.export_point_altitude),o.pointAltitude,enabled,Modifier.testTag("export-point-altitude")) {change(o.copy(pointAltitude=it))}
        ExportToggle(stringResource(R.string.export_point_speed),o.pointSpeed,enabled,Modifier.testTag("export-point-speed")) {change(o.copy(pointSpeed=it))}
        ExportToggle(stringResource(R.string.export_point_accuracy),o.pointAccuracy,enabled,Modifier.testTag("export-point-accuracy")) {change(o.copy(pointAccuracy=it))}
        ExportToggle(stringResource(R.string.export_point_outlier),o.pointOutlier,enabled,Modifier.testTag("export-point-outlier")) {change(o.copy(pointOutlier=it))}
    }
}
@Composable private fun ExportTime(label:String,value:LocalTime,enabled:Boolean,tag:String,settingsReady:Boolean,change:(LocalTime)->Unit) {
    val context=LocalContext.current
    val locale=LocalConfiguration.current.locales[0]
    val clock24=DateFormat.is24HourFormat(context)
    val format=remember(locale,clock24) {DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale,if(clock24) "Hm" else "hm"),locale)}
    var opening by rememberSaveable(stateSaver=ExportClockDraft.Saver) {mutableStateOf<ExportClockDraft?>(null)}
    val latestValue by rememberUpdatedState(value);val latestEnabled by rememberUpdatedState(enabled);val latestChange by rememberUpdatedState(change)
    val latestReady by rememberUpdatedState(settingsReady)
    val lifecycle=androidx.lifecycle.compose.LocalLifecycleOwner.current.lifecycle
    val active=remember {java.util.concurrent.atomic.AtomicBoolean(true)}
    DisposableEffect(lifecycle) {
        val observer=androidx.lifecycle.LifecycleEventObserver {_,event->if(event==androidx.lifecycle.Lifecycle.Event.ON_STOP && !context.activityConfigurationChange()) opening=null}
        lifecycle.addObserver(observer)
        onDispose {active.set(false);lifecycle.removeObserver(observer)}
    }
    // The new Activity briefly has no collected settings. Keep its restored draft
    // disabled until those settings arrive; real busy/disabled transitions still cancel it.
    LaunchedEffect(enabled,value,settingsReady) {if(settingsReady && (!enabled || opening?.let {it.expected!=value}==true)) opening=null}
    OutlinedButton(enabled=enabled && settingsReady,onClick={if(active.get() && latestReady && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) opening=ExportClockDraft(value)},modifier=Modifier.testTag(tag)) {Text(stringResource(R.string.export_time_value,label,format.format(value)))}
    opening?.let {request->key(request.id) {val expected=request.expected;WorkspaceClockDialog(expected.hour,expected.minute,clock24,tag,title={_,_->Text(label)},dismiss={opening=null},confirmEnabled=enabled && settingsReady,confirm={hour,minute->
        if(active.get() && latestReady && latestEnabled && latestValue==expected && opening===request && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            opening=null;latestChange(LocalTime.of(hour,minute))
        }
    })}}
}
