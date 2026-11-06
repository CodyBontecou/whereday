package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.DeleteOutline
import androidx.compose.material.icons.outlined.Edit
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Info
import androidx.compose.material.icons.outlined.Map
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import java.util.Locale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.*
import com.bontecou.whereday.export.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.collectLatest

private sealed interface OutingState {
    data object Loading: OutingState
    data class Ready(val detail: OutingDetail): OutingState
    data object Failed: OutingState
}
@Composable fun OutingDetailScreen(graph: AppGraph,id: String,metric: Boolean,back: () -> Unit,showOnMap: (RecordingSession) -> Unit,inferredHeader: InferredOutingHeader?=null,sequence:Int=1,onVisitOpened:((Visit)->Unit)?=null,visitInspectionActive:Boolean=false) {
    val state by produceState<OutingState>(OutingState.Loading,id,inferredHeader?.endedAt) {
        graph.database.invalidationTracker.createFlow("points","sessions","visits").collectLatest {
            try { value=OutingState.Ready(if(inferredHeader==null) graph.history.outingDetail(id) else graph.history.inferredDetail(inferredHeader)) }
            catch(e:Exception) { if(e is CancellationException) throw e; value=OutingState.Failed }
        }
    }
    var editingId by rememberSaveable {mutableStateOf<String?>(null)}
    val editing=editingId==id
    LaunchedEffect(id) {if(editingId!=id) editingId=null}
    var selectedVisit by rememberSaveable(id,stateSaver=VisitEditorVisitSaver) { mutableStateOf<Visit?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var replayShowing by remember {mutableStateOf(false)}
    var error by remember { mutableStateOf<Int?>(null) }
    val resources=LocalContext.current.resources
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val scope=rememberCoroutineScope()
    val exportSettings by graph.exportSettings.state.collectAsStateWithLifecycle()
    BackHandler(onBack=back)
    Box(Modifier.fillMaxSize().background(wd.surface).semantics {testTagsAsResourceId=true}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("outing-detail-content").padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        WdBackChip(stringResource(R.string.timeline_back),back)
        when(val current=state) {
            OutingState.Loading -> WdLoading()
            OutingState.Failed -> WdNotice(stringResource(R.string.outing_load_error),icon=Icons.Outlined.ErrorOutline,warning=true)
            is OutingState.Ready -> {
                val detail=current.detail
                val session=detail.session
                val photoRange=remember(session.startedAt,session.endedAt,detail.summary.count) {HistoryRange(session.startedAt,(session.endedAt ?: java.time.Instant.now()).plusMillis(1))}
                val photos=matchedPhotos(graph,photoRange)
                val photoLayer=photoMapLayer(graph,photoRange,photos.count!=null,validationFailed=photos.error!=null)
                val live=session.endedAt==null
                JournalMap(detail.points,detail.visits,followMetric=metric,endpoints=detail.endpoints,allOutlierFallback=true,onVisitSelected={visit->if(onVisitOpened!=null) onVisitOpened(visit) else selectedVisit=visit},photoLayer=photoLayer,
                    roadController=graph.roadDisplay,roadVisible=!visitInspectionActive && !editing && !deleting && selectedVisit==null && !replayShowing,
                    pointMarkerSource=rememberHistoryPointMarkerSource(graph,if(inferredHeader==null) ReplayRequest.Outing(id) else ReplayRequest.Inferred(inferredHeader)))
                RouteReplayButton(graph,if(inferredHeader==null) ReplayRequest.Outing(id) else ReplayRequest.Inferred(inferredHeader),metric,
                    ((detail.summary.count-detail.summary.outlierCount).takeIf {it>0} ?: detail.summary.count)>=2,onShowingChange={replayShowing=it})
                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(session.startedAt.atZone(java.time.ZoneId.systemDefault()).toLocalDate().format(java.time.format.DateTimeFormatter.ofLocalizedDate(java.time.format.FormatStyle.FULL).withLocale(locale)).uppercase(locale),
                            style=WdType.caption2.copy(letterSpacing=0.14.em),color=wd.textMuted,modifier=Modifier.weight(1f,fill=false))
                        if(live) WdBadge(stringResource(R.string.timeline_recording),color=wd.inkRust)
                        else if(detail.inferred) WdBadge(stringResource(R.string.timeline_inferred),color=wd.textMuted)
                    }
                    Text(session.name ?: stringResource(if(detail.inferred) R.string.timeline_inferred else R.string.outing_details),style=WdType.title.copy(fontWeight=FontWeight.Normal),color=wd.textPrimary,modifier=Modifier.semantics {heading()}.testTag("outing-title"))
                    Text(timelineRange(session.startedAt,session.endedAt,R.string.timeline_recording),style=WdType.caption,color=if(live) wd.inkRust else wd.textMuted)
                }
                // Outing board: the route first, then the title, then the figures in one surface-container row.
                WdCard {
                    val duration=java.time.Duration.between(session.startedAt,session.endedAt ?: java.time.Instant.now()).toMinutes().coerceAtLeast(0)
                    Row(Modifier.padding(horizontal=20.dp,vertical=12.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        OutingFigure(todayDistance(detail.summary.distanceMeters,metric,locale),stringResource(R.string.timeline_redesign_distance),Modifier.weight(1f))
                        OutingFigure(if(duration<60) stringResource(R.string.timeline_redesign_minutes,duration.toInt()) else stringResource(R.string.timeline_redesign_hours,(duration/60).toInt(),"%02d".format(duration%60)),
                            stringResource(R.string.outing_figure_duration),Modifier.weight(1f))
                        OutingFigure(java.text.NumberFormat.getIntegerInstance(locale).format(detail.summary.count),stringResource(R.string.today_stat_points),Modifier.weight(1f))
                        if(live) WdLiveDot()
                    }
                    WdDivider(Modifier.padding(start=20.dp))
                    Column(Modifier.padding(horizontal=20.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        val average=detail.summary.averageSpeed?.let {speed->stringResource(R.string.outing_average_speed,distanceText(speed*3600,metric,locale))} ?: stringResource(R.string.outing_average_unavailable)
                        Text(stringResource(R.string.outing_duration_summary,duration/60,duration%60,average),style=WdType.caption.copy(fontWeight=FontWeight.Medium),color=wd.textPrimary,modifier=Modifier.testTag("outing-duration"))
                        Text(stringResource(R.string.outing_point_summary,resources.getQuantityString(R.plurals.timeline_point_count,detail.summary.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),detail.summary.count),resources.getQuantityString(R.plurals.outing_glitch_count,detail.summary.outlierCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),detail.summary.outlierCount)),
                            style=WdType.caption,color=wd.textMuted,modifier=Modifier.testTag("outing-point-summary"))
                    }
                }
                if(detail.summary.count>detail.points.size) Text(resources.getQuantityString(R.plurals.outing_sampled,detail.summary.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),detail.summary.count),style=WdType.caption2,color=wd.textMuted)
                session.notes?.takeIf { it.isNotBlank() }?.let {
                    OutingSectionLabel(stringResource(R.string.timeline_redesign_notes))
                    Text(it,style=WdType.serifBody,color=wd.textPrimary)
                }
                val calendarTitle=stringResource(R.string.calendar_outing_title,sequence)
                CalendarEventButton(session to detail.endpoints.first,enabled=!busy,makeDraft={now->CalendarEventDraft.outing(session,detail.endpoints.first?.coordinate,calendarTitle,now)})
                WdButton(stringResource(R.string.outing_show_map),{ showOnMap(session) },emphasis=WdEmphasis.primary,icon=Icons.Outlined.Map)
                if(!detail.inferred) WdButton(stringResource(R.string.outing_edit),{ editingId=id },enabled=!busy,icon=Icons.Outlined.Edit)
                else WdNotice(stringResource(R.string.outing_inferred_explanation),icon=Icons.Outlined.Info)
                if(detail.visits.isNotEmpty()) {
                    OutingSectionLabel(stringResource(R.string.outing_linked_visits))
                    detail.visits.forEach { visit -> VisitNavigationRow(visit) {if(onVisitOpened!=null) onVisitOpened(visit) else selectedVisit=visit} }
                }
                MatchedPhotoStrip(graph,photos,photoRange)
                OutingSectionLabel(stringResource(R.string.timeline_redesign_export))
                Choice(stringResource(R.string.outing_format),exportSettings.selection.format.name,ExportFormat.entries.map {it.name},enabled=exportSettings.ready && !exportSettings.saving) {
                    graph.exportSettings.update(exportSettings.selection.copy(format=ExportFormat.valueOf(it)))
                }
                Text(stringResource(R.string.outing_format_explanation),style=WdType.caption2,color=wd.textMuted)
                HistoryFileActions(graph,stringResource(R.string.outing_export),!busy && exportSettings.ready && !exportSettings.saving && exportSettings.error==null) {
                    val selection=exportSettings.selection
                    graph.exports.prepareOuting(id,selection.format,selection.options,selection.filename,sequence,inferredHeader)
                }
                exportSettings.failure?.let {Text(exportSettingsText(it),style=MaterialTheme.typography.bodyMedium,color=wd.inkRust)}
                WdButton(stringResource(R.string.outing_delete),{ deleting=true },Modifier.padding(top=8.dp),emphasis=WdEmphasis.destructive,enabled=!busy,icon=Icons.Outlined.DeleteOutline)
                if(editing) key(id) {OutingEditor(session,{if(!busy) editingId=null},busy) {name,notes->
                    if(!busy && editingId==id) {
                        busy=true
                        scope.launch {
                            try {graph.journalCommands.editOuting(id,name,notes);if(editingId==id) editingId=null}
                            catch(e:Exception) {if(e is CancellationException) throw e;error=R.string.outing_save_error}
                            finally {busy=false}
                        }
                    }
                }}
            }
        }
        error?.let { WdNotice(stringResource(it),icon=Icons.Outlined.ErrorOutline,warning=true) }
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape),color=wd.accent,trackColor=wd.dialTrack)
    }
    Spacer(Modifier.fillMaxHeight().width(20.dp).testTag("outing-detail-scroll-margin"))
    }
    selectedVisit?.let {visit-> key(visit.id) {
        VisitEditor(graph,visit,{selectedVisit=null},{value,reuse,remember,overlap->graph.history.saveVisit(value,visit,reuse,remember,overlap)},{graph.history.deleteVisit(visit)})
    }}
    if(deleting) WorkspaceAlertDialog(onDismissRequest={ deleting=false },title={ Text(stringResource(R.string.outing_delete_question),style=WdType.title2,color=wd.textPrimary) },text={ Text(stringResource(R.string.outing_delete_explanation),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted) },confirmButton={ TextButton(enabled=!busy,colors=outingDialogButtonColors(wd.inkRust),onClick={ scope.launch {
        busy=true
        try {
            if(inferredHeader==null) {
                if(graph.history.dao.recording()?.sessionId==id) graph.recordingCommands.stop()
                graph.history.deleteSession(id)
            } else graph.history.deleteInferred(inferredHeader)
            deleting=false; back()
        } catch(e:Exception) { if(e is CancellationException) throw e; error=R.string.outing_delete_error; deleting=false }
        finally { busy=false }
    } }) { Text(stringResource(R.string.outing_delete),fontWeight=FontWeight.SemiBold) } },dismissButton={ TextButton(colors=outingDialogButtonColors(wd.accent),onClick={ deleting=false }) { Text(stringResource(R.string.visit_cancel)) } })
}

@Composable private fun OutingEditor(session: RecordingSession,dismiss: () -> Unit,busy:Boolean,save: (String?,String?) -> Unit) {
    var name by rememberSaveable(session.id) {mutableStateOf(session.name.orEmpty())}
    var notes by rememberSaveable(session.id) {mutableStateOf(session.notes.orEmpty())}
    val actions=rememberVisibleEditorActions()
    val ticket=actions.epoch
    WorkspaceAlertDialog(onDismissRequest=dismiss,title={ Text(stringResource(R.string.outing_edit),style=WdType.title2,color=wd.textPrimary) },text={ Column(Modifier.semantics {testTagsAsResourceId=true}.verticalScroll(rememberScrollState()).testTag("outing-editor-fields"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(name,{name=it},enabled=!busy,modifier=Modifier.testTag("outing-name"),label={Text(stringResource(R.string.visit_name))},colors=wdTextFieldColors(),shape=RoundedCornerShape(10.dp))
        TextButton(enabled=!busy && name.isNotBlank(),colors=outingDialogButtonColors(wd.inkRust),onClick={name=""}) {Text(stringResource(R.string.outing_default_name),style=WdType.caption2.copy(fontWeight=FontWeight.SemiBold,letterSpacing=0.1.em))}
        OutlinedTextField(notes,{notes=it},enabled=!busy,modifier=Modifier.testTag("outing-notes"),label={Text(stringResource(R.string.visit_notes))},minLines=3,colors=wdTextFieldColors(),shape=RoundedCornerShape(10.dp))
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape),color=wd.accent,trackColor=wd.dialTrack)
    } },confirmButton={key(ticket) {TextButton(enabled=!busy,colors=outingDialogButtonColors(wd.accent),onClick={if(actions.allows(ticket) && !busy) save(name,notes)}) {Text(stringResource(R.string.outing_save),fontWeight=FontWeight.SemiBold)}}},dismissButton={TextButton(enabled=!busy,colors=outingDialogButtonColors(wd.accent),onClick=dismiss) {Text(stringResource(R.string.visit_cancel))}})
}

/** Tracked mono section label, as iOS sectionLabel / TESectionHeader inside a padded page. */
@Composable private fun OutingSectionLabel(text:String) {
    Text(text.uppercase(),style=WdType.eyebrow,color=wd.textMuted,modifier=Modifier.padding(top=8.dp).semantics {heading()})
}

@Composable private fun outingDialogButtonColors(ink:androidx.compose.ui.graphics.Color)=ButtonDefaults.textButtonColors(contentColor=ink,disabledContentColor=wd.textMuted.copy(alpha=0.6f))

@Composable private fun OutingFigure(value:String,label:String,modifier:Modifier=Modifier) {
    Column(modifier,verticalArrangement=Arrangement.spacedBy(2.dp)) {
        Text(value,style=WdType.figure.copy(fontSize=24.sp),color=wd.textPrimary,maxLines=1)
        Text(label.uppercase(),style=WdType.caption2.copy(fontSize=10.sp,letterSpacing=0.1.em),color=wd.textMuted,maxLines=2)
    }
}
