package com.bontecou.whereday

import android.text.format.DateFormat
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.DateRange
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.compositeOver
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.bontecou.whereday.export.*
import java.text.NumberFormat
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle

// MARK: Shared archive chrome (Export, automatic exports, imports, webhooks)

/** Cards on archive pages sit 16dp in from the edges, under 20dp section headers. */
internal fun Modifier.exportInset()=padding(horizontal=16.dp)

/** A quiet card inset for archive pages. */
@Composable internal fun ExportCard(modifier:Modifier=Modifier,content:@Composable ColumnScope.()->Unit) {WdCard(modifier.exportInset(),content=content)}

/** Standalone switch row; kept compact so other settings lists can reuse it. */
@Composable fun ExportToggle(label:String,value:Boolean,enabled:Boolean=true,modifier:Modifier=Modifier,change:(Boolean)->Unit) {
    Row(modifier.fillMaxWidth().heightIn(min=48.dp).toggleable(value=value,enabled=enabled,role=Role.Switch,onValueChange=change),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f).padding(end=12.dp),style=MaterialTheme.typography.bodyLarge,color=if(enabled) wd.textPrimary else wd.textMuted);Switch(checked=value,onCheckedChange=null,enabled=enabled,colors=wdSwitchColors())
    }
}

/** A switch row inside an ExportCard (TERow + TEToggleStyle); toggleable semantics stay on the tagged row. */
@Composable internal fun ExportToggleRow(label:String,value:Boolean,enabled:Boolean=true,modifier:Modifier=Modifier,divider:Boolean=true,detail:String?=null,change:(Boolean)->Unit) {
    Column(Modifier.fillMaxWidth()) {
        Row(modifier.fillMaxWidth().heightIn(min=52.dp).toggleable(value=value,enabled=enabled,role=Role.Switch,onValueChange=change).padding(horizontal=16.dp,vertical=10.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                Text(label,style=MaterialTheme.typography.bodyLarge,color=if(enabled) wd.textPrimary else wd.textMuted)
                if(detail!=null) Text(detail,style=WdType.caption2,color=wd.textMuted)
            }
            Switch(checked=value,onCheckedChange=null,enabled=enabled,colors=wdSwitchColors())
        }
        if(divider) WdDivider(Modifier.padding(start=16.dp))
    }
}

/** A label beside a paper capsule that opens a menu; large text stacks the capsule under the label. */
@Composable internal fun <T> ExportMenuRow(label:String,value:T,choices:List<Pair<T,String>>,enabled:Boolean=true,divider:Boolean=true,currentLabel:String?=null,change:(T)->Unit) {
    var expanded by remember {mutableStateOf(false)}
    val picker:@Composable ()->Unit={
        Box {
            Row(Modifier.heightIn(min=40.dp).clip(CircleShape).background(wd.surface).border(1.dp,wd.border,CircleShape)
                .clickable(enabled=enabled,role=Role.Button) {expanded=true}.padding(start=14.dp,end=10.dp),
                verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(4.dp)) {
                // A value outside the menu (a custom accuracy or filename) still shows its own label, as Choice did.
                Text(currentLabel ?: choices.firstOrNull {it.first==value}?.second.orEmpty(),style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=if(enabled) wd.accent else wd.textMuted,maxLines=2,overflow=TextOverflow.Ellipsis)
                Icon(Icons.Filled.KeyboardArrowDown,null,Modifier.size(16.dp),tint=if(enabled) wd.accent else wd.textMuted)
            }
            WorkspaceDropdownMenu(expanded,{expanded=false}) {choices.forEach {(choice,text)->
                DropdownMenuItem(text={Text(text,style=MaterialTheme.typography.bodyLarge,color=if(choice==value) wd.accent else wd.textPrimary,fontWeight=if(choice==value) FontWeight.SemiBold else null)},onClick={expanded=false;change(choice)})
            }}
        }
    }
    Column(Modifier.fillMaxWidth()) {
        if(LocalDensity.current.fontScale>=1.3f) {
            Column(Modifier.fillMaxWidth().padding(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(label,Modifier.fillMaxWidth(),style=MaterialTheme.typography.bodyLarge,color=if(enabled) wd.textPrimary else wd.textMuted);picker()
            }
        } else Row(Modifier.fillMaxWidth().heightIn(min=52.dp).padding(horizontal=16.dp,vertical=6.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(label,Modifier.weight(1f).padding(end=12.dp),style=MaterialTheme.typography.bodyLarge,color=if(enabled) wd.textPrimary else wd.textMuted);picker()
        }
        if(divider) WdDivider(Modifier.padding(start=16.dp))
    }
}

/** The archive version of Settings' Choice: same string keys and labels, Whereday chrome. */
@Composable internal fun ExportChoiceRow(label:String,value:String,choices:List<String>,enabled:Boolean=true,divider:Boolean=true,labelFor:(String)->String={it.replaceFirstChar {c->c.uppercase()}},change:(String)->Unit) {
    ExportMenuRow(label,value,choices.map {it to labelFor(it)},enabled,divider,labelFor(value),change)
}

/** A tappable row with an accent label, for in-card actions such as resetting dates. */
@Composable internal fun ExportActionRow(text:String,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,divider:Boolean=true,destructive:Boolean=false) {
    WdRow(modifier,divider,onClick,enabled) {
        Text(text,style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.SemiBold),color=when {!enabled->wd.textMuted;destructive->wd.inkRust;else->wd.accent},modifier=Modifier.weight(1f))
    }
}

/** WdNotice whose body text can carry its own testTag (tests read the tagged Text directly). */
@Composable internal fun ExportCallout(text:String,modifier:Modifier=Modifier,textModifier:Modifier=Modifier,title:String?=null,warning:Boolean=false,
    action:(@Composable ColumnScope.()->Unit)?=null) {
    val ink=if(warning) wd.inkRust else wd.accent
    val shape=RoundedCornerShape(14.dp)
    Column(modifier.fillMaxWidth().clip(shape).background(ink.copy(alpha=if(wd.dark) 0.12f else 0.06f).compositeOver(wd.card))
        .border(1.dp,ink.copy(alpha=0.35f),shape).padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.Top) {
            Icon(if(warning) Icons.Outlined.ErrorOutline else Icons.Outlined.Info,null,Modifier.size(20.dp),tint=ink)
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                if(title!=null) Text(title,style=MaterialTheme.typography.titleMedium,color=wd.textPrimary)
                Text(text,textModifier,style=MaterialTheme.typography.bodyMedium,color=if(title!=null) wd.textMuted else wd.textPrimary)
            }
        }
        action?.invoke(this)
    }
}

/** Thin ink progress bar on a dial-track rail. */
@Composable internal fun ExportProgress(modifier:Modifier=Modifier) {
    LinearProgressIndicator(modifier.fillMaxWidth().clip(CircleShape),color=wd.accent,trackColor=wd.dialTrack)
}

/** An inline busy line: small ink spinner beside a muted status. */
@Composable internal fun ExportBusyLine(text:String,modifier:Modifier=Modifier,textModifier:Modifier=Modifier) {
    Row(modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        CircularProgressIndicator(Modifier.size(16.dp),color=wd.accent,trackColor=wd.dialTrack,strokeWidth=2.dp)
        Text(text,textModifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
    }
}

/** A quiet text action in accent (or rust for destructive), replacing stock TextButtons. */
@Composable internal fun ExportLink(text:String,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,destructive:Boolean=false) {
    TextButton(onClick=onClick,modifier=modifier.heightIn(min=44.dp),enabled=enabled,
        colors=ButtonDefaults.textButtonColors(contentColor=if(destructive) wd.inkRust else wd.accent,disabledContentColor=wd.textMuted)) {
        Text(text,style=MaterialTheme.typography.bodyMedium.copy(fontWeight=FontWeight.SemiBold))
    }
}

/** Muted body copy under a page title or inside a card. */
@Composable internal fun ExportBody(text:String,modifier:Modifier=Modifier) {
    Text(text,modifier.fillMaxWidth(),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
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
    val ranged=o.from!=ExportOptions.allDatesStart || o.until!=Instant.MAX
    WdSectionHeader(stringResource(R.string.export_date_range))
    ExportCard {
        WdNavRow(when {
            o.from==ExportOptions.allDatesStart && o.until==Instant.MAX->stringResource(R.string.export_all_dates)
            o.until==Instant.MAX->stringResource(R.string.export_from_date,date.format(first))
            o.from==ExportOptions.allDatesStart->stringResource(R.string.export_through_date,date.format(last))
            else->stringResource(R.string.export_between_dates,date.format(first),date.format(last))
        },Modifier.testTag("export-date-range"),icon=Icons.Outlined.DateRange,enabled=enabled && settingsReady) {
            if(active.get() && latestEnabled && latestReady && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) dates=ExportDateDraft.open(latestOptions)
        }
        if(ranged) ExportActionRow(stringResource(R.string.export_reset_dates),{change(o.copy(from=ExportOptions.allDatesStart,until=Instant.MAX))},Modifier.testTag("export-reset-dates"),enabled=enabled)
        ExportToggleRow(stringResource(R.string.export_time_of_day),o.startTime!=null,enabled,Modifier.testTag("export-time-enabled"),divider=o.startTime!=null) {change(o.copy(startTime=if(it) LocalTime.of(8,0) else null,endTime=if(it) LocalTime.of(18,0) else null))}
        if(o.startTime!=null) {
            ExportTime(stringResource(R.string.export_from_time),o.startTime!!,enabled,"export-time-from",settingsReady) {change(o.copy(startTime=it))}
            ExportTime(stringResource(R.string.export_until_time),o.endTime!!,enabled,"export-time-until",settingsReady,divider=false) {change(o.copy(endTime=it))}
        }
    }
    WdSectionFooter(stringResource(R.string.export_time_zone,o.zone.id))
    if(o.startTime!=null) WdSectionFooter(stringResource(R.string.export_overnight_help))
    val visitBlock=outings || kind!=DataKind.points
    val pointBlock=outings || kind!=DataKind.visits
    if(visitBlock || (pointBlock && !outings)) {
        WdSectionHeader(stringResource(R.string.export_redesign_section_filters))
        ExportCard {
            val pointFilters=pointBlock && !outings
            if(visitBlock) {
                ExportToggleRow(stringResource(R.string.export_completed_visits),o.completedOnly,enabled,Modifier.testTag("export-completed")) {change(o.copy(completedOnly=it))}
                ExportChoiceRow(stringResource(R.string.export_minimum_minutes),o.minimumVisitMinutes.toString(),listOf("0","5","15","30","60"),enabled=enabled,divider=pointFilters,labelFor={integer.format(it.toLong())}) {change(o.copy(minimumVisitMinutes=it.toLong()))}
            }
            if(pointFilters) {
                ExportToggleRow(stringResource(R.string.export_exclude_outliers),o.excludeOutliers,enabled,Modifier.testTag("export-exclude-outliers")) {change(o.copy(excludeOutliers=it))}
                ExportChoiceRow(stringResource(R.string.export_maximum_accuracy),o.maximumAccuracy?.toString() ?: "any",listOf("any","10","25","50","100"),enabled=enabled,divider=false,labelFor={if(it=="any") any else decimal.format(it.toDouble())}) {change(o.copy(maximumAccuracy=it.toDoubleOrNull()))}
            }
        }
        if(visitBlock && outings) WdSectionFooter(stringResource(R.string.export_outing_filters_help))
    }
    if(visitBlock) {
        WdSectionHeader(stringResource(R.string.export_visit_fields))
        ExportCard {
            ExportToggleRow(stringResource(R.string.export_visit_coordinates),o.visitCoordinates,enabled,Modifier.testTag("export-visit-coordinates")) {change(o.copy(visitCoordinates=it))}
            ExportToggleRow(stringResource(R.string.export_visit_name),o.visitName,enabled,Modifier.testTag("export-visit-name")) {change(o.copy(visitName=it))}
            ExportToggleRow(stringResource(R.string.export_visit_address),o.visitAddress,enabled,Modifier.testTag("export-visit-address")) {change(o.copy(visitAddress=it))}
            ExportToggleRow(stringResource(R.string.export_visit_notes),o.visitNotes,enabled,Modifier.testTag("export-visit-notes")) {change(o.copy(visitNotes=it))}
            ExportToggleRow(stringResource(R.string.export_visit_duration),o.visitDuration,enabled,Modifier.testTag("export-visit-duration"),divider=false) {change(o.copy(visitDuration=it))}
        }
    }
    if(pointBlock) {
        WdSectionHeader(stringResource(R.string.export_point_fields))
        ExportCard {
            ExportToggleRow(stringResource(R.string.export_point_altitude),o.pointAltitude,enabled,Modifier.testTag("export-point-altitude")) {change(o.copy(pointAltitude=it))}
            ExportToggleRow(stringResource(R.string.export_point_speed),o.pointSpeed,enabled,Modifier.testTag("export-point-speed")) {change(o.copy(pointSpeed=it))}
            ExportToggleRow(stringResource(R.string.export_point_accuracy),o.pointAccuracy,enabled,Modifier.testTag("export-point-accuracy")) {change(o.copy(pointAccuracy=it))}
            ExportToggleRow(stringResource(R.string.export_point_outlier),o.pointOutlier,enabled,Modifier.testTag("export-point-outlier"),divider=false) {change(o.copy(pointOutlier=it))}
        }
    }
}
@Composable private fun ExportTime(label:String,value:LocalTime,enabled:Boolean,tag:String,settingsReady:Boolean,divider:Boolean=true,change:(LocalTime)->Unit) {
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
    WdNavRow(stringResource(R.string.export_time_value,label,format.format(value)),Modifier.testTag(tag),icon=Icons.Outlined.Schedule,divider=divider,enabled=enabled && settingsReady) {if(active.get() && latestReady && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) opening=ExportClockDraft(value)}
    opening?.let {request->key(request.id) {val expected=request.expected;WorkspaceClockDialog(expected.hour,expected.minute,clock24,tag,title={_,_->Text(label)},dismiss={opening=null},confirmEnabled=enabled && settingsReady,confirm={hour,minute->
        if(active.get() && latestReady && latestEnabled && latestValue==expected && opening===request && lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.STARTED)) {
            opening=null;latestChange(LocalTime.of(hour,minute))
        }
    })}}
}
