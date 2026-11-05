package com.bontecou.whereday

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
            WdSectionHeader(stringResource(R.string.export_redesign_section_schedule))
            ExportCard {
                ExportToggleRow(stringResource(R.string.automatic_export_enable),settings.enabled,enabled=!ui.busy || settings.enabled,modifier=Modifier.testTag("automatic-export-enabled")) {value->
                    if(foreground() && current.loaded && (!current.busy || !value && current.state.settings.enabled)) actions.enabled(value)
                }
                WdNavRow(stringResource(R.string.automatic_export_anchor,time.format(LocalTime.of(settings.schedule.hour,settings.schedule.minute))),Modifier.testTag("automatic-export-anchor"),icon=Icons.Outlined.Schedule,enabled=editable) {anchor()}
                ExportChoiceRow(stringResource(R.string.automatic_export_repeat),settings.schedule.intervalHours.toString(),repeats.keys.toList(),editable,divider=false,labelFor={repeats.getValue(it)}) {value->edit {it.copy(schedule=it.schedule.copy(intervalHours=value.toInt()))}}
            }
            WdSectionFooter(stringResource(R.string.automatic_export_zone,zone.id))
            WdSectionHeader(stringResource(R.string.export_redesign_section_contents))
            ExportCard {
                ExportChoiceRow(stringResource(R.string.automatic_export_format),settings.format.name,ExportFormat.entries.map {it.name},editable) {value->edit {it.copy(format=ExportFormat.valueOf(value))}}
                ExportChoiceRow(stringResource(R.string.automatic_export_data),if(settings.outings) "outings" else settings.kind.name,DataKind.entries.map {it.name}+"outings",editable,divider=settings.schedule.intervalHours>0,labelFor={kinds.getValue(it)}) {value->edit {if(value=="outings") it.copy(outings=true) else it.copy(outings=false,kind=DataKind.valueOf(value))}}
                if(settings.schedule.intervalHours>0) ExportChoiceRow(stringResource(R.string.automatic_export_file_mode),settings.fileMode.name,ScheduledFileMode.entries.map {it.name},editable,divider=false,labelFor={modes.getValue(it)}) {value->edit {it.copy(fileMode=ScheduledFileMode.valueOf(value))}}
            }
            if(settings.outings) WdSectionFooter(stringResource(R.string.automatic_export_outings_help))
            if(settings.schedule.intervalHours>0 && settings.format in setOf(ExportFormat.gpx,ExportFormat.kml)) WdSectionFooter(stringResource(R.string.automatic_export_container_help))
            ExportCallout(stringResource(if(folderConfigured) R.string.automatic_export_folder_ready else R.string.automatic_export_folder_missing),Modifier.exportInset().padding(top=16.dp),warning=!folderConfigured)
            WdSectionFooter(stringResource(R.string.automatic_export_policy))
            WdSectionHeader(stringResource(R.string.export_redesign_section_status))
            ExportCard {
                Column(Modifier.fillMaxWidth().padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    Text(state.lastSuccess?.let {stringResource(R.string.automatic_export_last_success,date(it))} ?: stringResource(R.string.automatic_export_no_success),style=MaterialTheme.typography.bodyLarge,color=wd.textPrimary)
                    ui.nextAttempt?.let {Text(stringResource(R.string.automatic_export_next,date(it)),style=WdType.caption,color=wd.textMuted)}
                    if(settings.enabled && pending!=null && ui.nextAttempt==null) Text(stringResource(R.string.automatic_export_saved_retry),style=WdType.caption,color=wd.textMuted)
                    pending?.let {Text(stringResource(R.string.automatic_export_pending,date(it.plan.filenameDate,it.plan.options.zone)),style=MaterialTheme.typography.bodyMedium,color=wd.warning)}
                    if(ui.busy) {ExportProgress();ExportBusyLine(stringResource(R.string.automatic_export_updating))}
                    if(pending!=null) WdButton(stringResource(R.string.automatic_export_retry),{if(foreground() && current.loaded && !current.busy && current.state.pending?.id==pending.id) actions.retry()},Modifier.testTag("automatic-export-retry"),emphasis=WdEmphasis.primary,enabled=!ui.busy,icon=Icons.Outlined.Refresh)
                    else WdButton(stringResource(R.string.automatic_export_run),{if(foreground() && current.loaded && !current.busy && current.state.pending==null) actions.run()},Modifier.testTag("automatic-export-run"),emphasis=WdEmphasis.primary,enabled=!ui.busy,icon=Icons.Outlined.PlayArrow)
                    if(pending!=null || ui.busy) ExportLink(stringResource(R.string.automatic_export_cancel_pending),{if(foreground() && current.loaded && (current.state.pending!=null || current.busy)) actions.cancel()},destructive=true)
                }
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

