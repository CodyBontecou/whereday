package com.bontecou.whereday

import androidx.compose.ui.Alignment

import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.shape.RoundedCornerShape
import android.text.format.DateFormat
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PlayArrow
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.material.icons.outlined.Schedule
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.export.*
import com.bontecou.whereday.platform.*
import java.text.NumberFormat
import java.time.*
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class AutomaticExportActions(
    val update:((AutomaticExportSettings)->AutomaticExportSettings)->Unit,val enabled:(Boolean)->Unit,
    val run:()->Unit,val retry:()->Unit,val cancel:()->Unit,val reset:()->Unit,val refresh:()->Unit,
)
@Composable fun AutomaticExportsScreen(graph:AppGraph,folderConfigured:Boolean,back:()->Unit) {
    val ui by graph.automaticExportController.state.collectAsStateWithLifecycle()
    val controller=graph.automaticExportController
    val actions=remember(controller) {AutomaticExportActions({controller.updateSettings(it)},controller::setEnabled,{controller.runNow()},{controller.retry()},controller::cancelPending,controller::reset,{controller.refresh()})}
    LaunchedEffect(controller) {controller.refresh()}
    AutomaticExportsContent(ui,folderConfigured,actions,back)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun AutomaticExportsContent(ui:AutomaticExportUiState,folderConfigured:Boolean,actions:AutomaticExportActions,back:()->Unit,
    chooseTime:((Int,Int,(Int,Int)->Unit)->Unit)?=null) {
    val context=LocalContext.current
    val locale=LocalConfiguration.current.locales[0]
    val number=NumberFormat.getIntegerInstance(locale)
    val zone=ZoneId.systemDefault()
    val clock24=DateFormat.is24HourFormat(context)
    val time=remember(locale,clock24) {DateTimeFormatter.ofPattern(DateFormat.getBestDateTimePattern(locale,if(clock24) "Hm" else "hm"),locale)}
    fun date(instant:Instant,zone:ZoneId=ZoneId.systemDefault())=DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,FormatStyle.SHORT).withLocale(locale).withZone(zone).format(instant)
    val current by rememberUpdatedState(ui)
    val state=ui.state;val settings=state.settings;val pending=state.pending
    val editable=ui.loaded && !ui.busy && pending==null
    var reset by remember {mutableStateOf(false)}
    var workspaceAnchor by rememberSaveable(stateSaver=AutomaticAnchorDraft.Saver) {mutableStateOf<AutomaticAnchorDraft?>(null)}
    val active=remember {AtomicBoolean(true)}
    val anchorGeneration=remember {AtomicLong()}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP) {anchorGeneration.incrementAndGet();if(!context.activityConfigurationChange()) workspaceAnchor=null}}
        lifecycle.addObserver(observer)
        onDispose {active.set(false);anchorGeneration.incrementAndGet();lifecycle.removeObserver(observer)}
    }
    fun foreground()=active.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    fun canEdit()=foreground() && current.loaded && !current.busy && current.state.pending==null
    fun edit(change:(AutomaticExportSettings)->AutomaticExportSettings) {if(canEdit()) actions.update(change)}
    fun anchor() {
        if(!canEdit()) return
        val expected=current.state.settings;val generation=anchorGeneration.incrementAndGet();val consumed=AtomicBoolean()
        val selected:(Int,Int)->Unit={hour,minute->
            if(consumed.compareAndSet(false,true) && generation==anchorGeneration.get() && canEdit() && current.state.settings==expected && hour in 0..23 && minute in 0..59) {
                actions.update {it.copy(schedule=it.schedule.copy(hour=hour,minute=minute))}
            }
        }
        if(chooseTime!=null) chooseTime(expected.schedule.hour,expected.schedule.minute,selected)
        else workspaceAnchor=AutomaticAnchorDraft(expected)
    }
    LaunchedEffect(ui.loaded,settings,pending) {
        if(ui.loaded && (workspaceAnchor?.expected?.let {it!=settings}==true || pending!=null)) workspaceAnchor=null
    }
    workspaceAnchor?.let {request->key(request.id) {
        WorkspaceClockDialog(request.expected.schedule.hour,request.expected.schedule.minute,clock24,"automatic-anchor",title={hour,minute->
                Text(stringResource(R.string.automatic_export_anchor,java.time.LocalTime.of(hour,minute).format(time)))},
            dismiss={if(workspaceAnchor===request) workspaceAnchor=null},confirmEnabled=editable && settings==request.expected,
            confirm={hour,minute->
                if(workspaceAnchor===request && canEdit() && current.state.settings==request.expected && hour in 0..23 && minute in 0..59) {
                    workspaceAnchor=null
                    actions.update {it.copy(schedule=it.schedule.copy(hour=hour,minute=minute))}
                }
            })
    }}
    BackHandler(onBack=back)
    val repeats=(0..23).associate {it.toString() to if(it==0) stringResource(R.string.automatic_export_daily) else pluralStringResource(R.plurals.automatic_export_interval,it,number.format(it))}
    val kinds=mapOf("visits" to stringResource(R.string.automatic_export_visits),"points" to stringResource(R.string.automatic_export_points),"all" to stringResource(R.string.automatic_export_all),"outings" to stringResource(R.string.automatic_export_outings))
    val modes=mapOf("rewrite" to stringResource(R.string.automatic_export_rewrite),"append" to stringResource(R.string.automatic_export_append))
    // The back capsule scrolls with the page so tests and TalkBack reach it through the same scroll container.
    Column(Modifier.fillMaxSize().background(wd.surface).semantics {testTagsAsResourceId=true}.testTag("automatic-exports-screen").verticalScroll(rememberScrollState()).padding(bottom=24.dp)) {
        WdTopBar(stringResource(R.string.automatic_export_title),back,backLabel=stringResource(R.string.automatic_export_back))
        ExportBody(stringResource(R.string.automatic_export_intro),Modifier.padding(horizontal=24.dp).padding(top=4.dp))
        if(!ui.loaded && ui.failure==null) Column(Modifier.exportInset().padding(top=20.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {ExportProgress();ExportBusyLine(stringResource(R.string.automatic_export_loading))}
        if(ui.loaded) {
            // AutoExports board: the master switch first, then the next planned run, the recipe, and status with its actions.
            ExportCard(Modifier.padding(top=12.dp)) {
                ExportToggleRow(stringResource(R.string.automatic_export_enable),settings.enabled,enabled=!ui.busy || settings.enabled,modifier=Modifier.testTag("automatic-export-enabled"),divider=false) {value->
                    if(foreground() && current.loaded && (!current.busy || !value && current.state.settings.enabled)) actions.enabled(value)
                }
            }
            if(settings.enabled) Column(Modifier.exportInset().padding(top=12.dp).clip(RoundedCornerShape(28.dp)).background(wd.inkCard).padding(horizontal=20.dp,vertical=16.dp).testTag("automatic-export-next-card"),
                verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(stringResource(R.string.automatic_export_next_title).uppercase(),style=WdType.caption2.copy(fontSize=10.sp,letterSpacing=0.12.em),color=wd.onInkCardMuted)
                Text(ui.nextAttempt?.let {date(it)} ?: time.format(LocalTime.of(settings.schedule.hour,settings.schedule.minute)),style=WdType.title2.copy(fontWeight=FontWeight.Normal),color=wd.onInkCard)
                Text(stringResource(R.string.automatic_export_next_caption,time.format(LocalTime.of(settings.schedule.hour,settings.schedule.minute)),zone.id),style=MaterialTheme.typography.bodySmall,color=wd.onInkCardMuted)
            }
            ExportCard(Modifier.padding(top=12.dp)) {
                ExportChoiceRow(stringResource(R.string.automatic_export_repeat),settings.schedule.intervalHours.toString(),repeats.keys.toList(),editable,labelFor={repeats.getValue(it)}) {value->edit {it.copy(schedule=it.schedule.copy(intervalHours=value.toInt()))}}
                WdNavRow(stringResource(R.string.automatic_export_anchor,time.format(LocalTime.of(settings.schedule.hour,settings.schedule.minute))),Modifier.testTag("automatic-export-anchor"),icon=Icons.Outlined.Schedule,enabled=editable) {anchor()}
                Column(Modifier.fillMaxWidth().padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.automatic_export_format),style=MaterialTheme.typography.bodyLarge,color=wd.textPrimary)
                    FlowRow(Modifier.testTag("automatic-export-formats"),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        ExportFormat.entries.forEach {format->WdChip(recipeFormatName(format),settings.format==format,{edit {it.copy(format=format)}},enabled=editable)}
                    }
                    Text(stringResource(R.string.automatic_export_data),style=MaterialTheme.typography.bodyLarge,color=wd.textPrimary,modifier=Modifier.padding(top=4.dp))
                    val dataKeys=DataKind.entries.map {it.name}+"outings"
                    WdSegmented(dataKeys,if(settings.outings) "outings" else settings.kind.name,{kinds.getValue(it)},{value->edit {if(value=="outings") it.copy(outings=true) else it.copy(outings=false,kind=DataKind.valueOf(value))}},
                        Modifier.testTag("automatic-export-data"),enabled=editable)
                }
                if(settings.schedule.intervalHours>0) {WdDivider(Modifier.padding(start=20.dp));ExportChoiceRow(stringResource(R.string.automatic_export_file_mode),settings.fileMode.name,ScheduledFileMode.entries.map {it.name},editable,divider=false,labelFor={modes.getValue(it)}) {value->edit {it.copy(fileMode=ScheduledFileMode.valueOf(value))}}}
                WdDivider(Modifier.padding(start=20.dp))
                WdRow(divider=false) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.automatic_export_folder_title),style=MaterialTheme.typography.bodyLarge,color=wd.textPrimary)
                        Text(stringResource(if(folderConfigured) R.string.automatic_export_folder_ready else R.string.automatic_export_folder_missing),style=MaterialTheme.typography.bodySmall,color=if(folderConfigured) wd.textMuted else wd.inkRust)
                    }
                }
            }
            if(settings.outings) WdSectionFooter(stringResource(R.string.automatic_export_outings_help))
            if(settings.schedule.intervalHours>0 && settings.format in setOf(ExportFormat.gpx,ExportFormat.kml)) WdSectionFooter(stringResource(R.string.automatic_export_container_help))
            WdSectionFooter(stringResource(R.string.automatic_export_policy))
            // Status: the last successful export, any pending window, then Run now beside Cancel.
            Column(Modifier.exportInset().padding(top=16.dp).clip(RoundedCornerShape(16.dp)).background(if(state.lastSuccess!=null) wd.containerLow else wd.noticeContainer).padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                Text(state.lastSuccess?.let {stringResource(R.string.automatic_export_last_success,date(it))} ?: stringResource(R.string.automatic_export_no_success),style=MaterialTheme.typography.bodyMedium,
                    color=if(state.lastSuccess!=null) wd.textPrimary else wd.onNoticeContainer)
                ui.nextAttempt?.let {Text(stringResource(R.string.automatic_export_next,date(it)),style=WdType.caption,color=wd.textMuted)}
                if(settings.enabled && pending!=null && ui.nextAttempt==null) Text(stringResource(R.string.automatic_export_saved_retry),style=WdType.caption,color=wd.textMuted)
                pending?.let {Text(stringResource(R.string.automatic_export_pending,date(it.plan.filenameDate,it.plan.options.zone)),style=MaterialTheme.typography.bodyMedium,color=wd.warning)}
                if(ui.busy) {ExportProgress();ExportBusyLine(stringResource(R.string.automatic_export_updating))}
            }
            Row(Modifier.exportInset().padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                if(pending!=null) WdButton(stringResource(R.string.automatic_export_retry),{if(foreground() && current.loaded && !current.busy && current.state.pending?.id==pending.id) actions.retry()},Modifier.weight(1f).testTag("automatic-export-retry"),emphasis=WdEmphasis.primary,enabled=!ui.busy,icon=Icons.Outlined.Refresh)
                else WdButton(stringResource(R.string.automatic_export_run),{if(foreground() && current.loaded && !current.busy && current.state.pending==null) actions.run()},Modifier.weight(1f).testTag("automatic-export-run"),emphasis=WdEmphasis.primary,enabled=!ui.busy,icon=Icons.Outlined.PlayArrow)
                if(pending!=null || ui.busy) WdButton(stringResource(R.string.automatic_export_cancel_pending),{if(foreground() && current.loaded && (current.state.pending!=null || current.busy)) actions.cancel()},Modifier.weight(1f),emphasis=WdEmphasis.destructive)
            }
        }
        Column(Modifier.exportInset().padding(top=12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            state.lastFailure?.let {ExportCallout(automaticExportFailureText(it),warning=true)}
            state.lastStatus?.let {ExportCallout(automaticExportNoticeText(it))}
            ui.failure?.let {ExportCallout(automaticExportFailureText(it),warning=true)}
            ui.notice?.let {ExportCallout(automaticExportActionText(it))}
            if(!ui.loaded && ui.failure!=null) {
                WdButton(stringResource(R.string.automatic_export_retry_load),actions.refresh,enabled=!ui.busy,emphasis=WdEmphasis.primary)
                ExportLink(stringResource(R.string.automatic_export_reset_settings),{reset=true},enabled=!ui.busy,destructive=true)
            }
        }
    }
    if(reset) WorkspaceAlertDialog(onDismissRequest={reset=false},title={Text(stringResource(R.string.automatic_export_reset_title))},text={Text(stringResource(R.string.automatic_export_reset_body))},confirmButton={TextButton(onClick={reset=false;if(foreground() && !current.loaded && !current.busy && current.failure!=null) actions.reset()}) {Text(stringResource(R.string.automatic_export_reset))}},dismissButton={TextButton(onClick={reset=false}) {Text(stringResource(R.string.automatic_export_cancel))}})
}

