package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
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
    Box(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).testTag("outing-detail-content").padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        TextButton(onClick=back) { Text(stringResource(R.string.timeline_back)) }
        when(val current=state) {
            OutingState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            OutingState.Failed -> Text(stringResource(R.string.outing_load_error),color=MaterialTheme.colorScheme.error)
            is OutingState.Ready -> {
                val detail=current.detail
                val session=detail.session
                val photoRange=remember(session.startedAt,session.endedAt,detail.summary.count) {HistoryRange(session.startedAt,(session.endedAt ?: java.time.Instant.now()).plusMillis(1))}
                val photos=matchedPhotos(graph,photoRange)
                val photoLayer=photoMapLayer(graph,photoRange,photos.count!=null,validationFailed=photos.error!=null)
                Text(session.name ?: stringResource(if(detail.inferred) R.string.timeline_inferred else R.string.outing_details),style=MaterialTheme.typography.headlineSmall,modifier=Modifier.testTag("outing-title"))
                Text(timelineRange(session.startedAt,session.endedAt,R.string.timeline_recording))
                Text(stringResource(R.string.outing_point_summary,resources.getQuantityString(R.plurals.timeline_point_count,detail.summary.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),detail.summary.count),resources.getQuantityString(R.plurals.outing_glitch_count,detail.summary.outlierCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),detail.summary.outlierCount)),modifier=Modifier.testTag("outing-point-summary"))
                Text(distanceText(detail.summary.distanceMeters,metric,locale),style=MaterialTheme.typography.headlineMedium)
                val duration=java.time.Duration.between(session.startedAt,session.endedAt ?: java.time.Instant.now()).toMinutes().coerceAtLeast(0)
                val average=detail.summary.averageSpeed?.let {speed->stringResource(R.string.outing_average_speed,distanceText(speed*3600,metric,locale))} ?: stringResource(R.string.outing_average_unavailable)
                Text(stringResource(R.string.outing_duration_summary,duration/60,duration%60,average),modifier=Modifier.testTag("outing-duration"))
                RouteReplayButton(graph,if(inferredHeader==null) ReplayRequest.Outing(id) else ReplayRequest.Inferred(inferredHeader),metric,
                    ((detail.summary.count-detail.summary.outlierCount).takeIf {it>0} ?: detail.summary.count)>=2,onShowingChange={replayShowing=it})
                JournalMap(detail.points,detail.visits,followMetric=metric,endpoints=detail.endpoints,allOutlierFallback=true,onVisitSelected={visit->if(onVisitOpened!=null) onVisitOpened(visit) else selectedVisit=visit},photoLayer=photoLayer,
                    roadController=graph.roadDisplay,roadVisible=!visitInspectionActive && !editing && !deleting && selectedVisit==null && !replayShowing,
                    pointMarkerSource=rememberHistoryPointMarkerSource(graph,if(inferredHeader==null) ReplayRequest.Outing(id) else ReplayRequest.Inferred(inferredHeader)))
                if(detail.summary.count>detail.points.size) Text(resources.getQuantityString(R.plurals.outing_sampled,detail.summary.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),detail.summary.count),style=MaterialTheme.typography.bodySmall)
                session.notes?.takeIf { it.isNotBlank() }?.let { Text(it,style=MaterialTheme.typography.bodyLarge) }
                val calendarTitle=stringResource(R.string.calendar_outing_title,sequence)
                CalendarEventButton(session to detail.endpoints.first,enabled=!busy,makeDraft={now->CalendarEventDraft.outing(session,detail.endpoints.first?.coordinate,calendarTitle,now)})
                Button(onClick={ showOnMap(session) }) { Text(stringResource(R.string.outing_show_map)) }
                if(!detail.inferred) OutlinedButton(enabled=!busy,onClick={ editingId=id }) { Text(stringResource(R.string.outing_edit)) }
                else Text(stringResource(R.string.outing_inferred_explanation),style=MaterialTheme.typography.bodyMedium)
                if(detail.visits.isNotEmpty()) {
                    Text(stringResource(R.string.outing_linked_visits),style=MaterialTheme.typography.titleLarge)
                    detail.visits.forEach { visit -> VisitNavigationRow(visit) {if(onVisitOpened!=null) onVisitOpened(visit) else selectedVisit=visit} }
                }
                MatchedPhotoStrip(graph,photos,photoRange)
                Choice(stringResource(R.string.outing_format),exportSettings.selection.format.name,ExportFormat.entries.map {it.name},enabled=exportSettings.ready && !exportSettings.saving) {
                    graph.exportSettings.update(exportSettings.selection.copy(format=ExportFormat.valueOf(it)))
                }
                Text(stringResource(R.string.outing_format_explanation),style=MaterialTheme.typography.bodySmall)
                HistoryFileActions(graph,stringResource(R.string.outing_export),!busy && exportSettings.ready && !exportSettings.saving && exportSettings.error==null) {
                    val selection=exportSettings.selection
                    graph.exports.prepareOuting(id,selection.format,selection.options,selection.filename,sequence,inferredHeader)
                }
                exportSettings.failure?.let {Text(exportSettingsText(it),color=MaterialTheme.colorScheme.error)}
                TextButton(enabled=!busy,onClick={ deleting=true }) { Text(stringResource(R.string.outing_delete),color=MaterialTheme.colorScheme.error) }
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
        error?.let { Text(stringResource(it),color=MaterialTheme.colorScheme.error) }
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    Spacer(Modifier.fillMaxHeight().width(20.dp).testTag("outing-detail-scroll-margin"))
    }
    selectedVisit?.let {visit-> key(visit.id) {
        VisitEditor(graph,visit,{selectedVisit=null},{value,reuse,remember,overlap->graph.history.saveVisit(value,visit,reuse,remember,overlap)},{graph.history.deleteVisit(visit)})
    }}
    if(deleting) WorkspaceAlertDialog(onDismissRequest={ deleting=false },title={ Text(stringResource(R.string.outing_delete_question)) },text={ Text(stringResource(R.string.outing_delete_explanation)) },confirmButton={ TextButton(enabled=!busy,onClick={ scope.launch {
        busy=true
        try {
            if(inferredHeader==null) {
                if(graph.history.dao.recording()?.sessionId==id) graph.recordingCommands.stop()
                graph.history.deleteSession(id)
            } else graph.history.deleteInferred(inferredHeader)
            deleting=false; back()
        } catch(e:Exception) { if(e is CancellationException) throw e; error=R.string.outing_delete_error; deleting=false }
        finally { busy=false }
    } }) { Text(stringResource(R.string.outing_delete)) } },dismissButton={ TextButton(onClick={ deleting=false }) { Text(stringResource(R.string.visit_cancel)) } })
}

@Composable private fun OutingEditor(session: RecordingSession,dismiss: () -> Unit,busy:Boolean,save: (String?,String?) -> Unit) {
    var name by rememberSaveable(session.id) {mutableStateOf(session.name.orEmpty())}
    var notes by rememberSaveable(session.id) {mutableStateOf(session.notes.orEmpty())}
    val actions=rememberVisibleEditorActions()
    val ticket=actions.epoch
    WorkspaceAlertDialog(onDismissRequest=dismiss,title={ Text(stringResource(R.string.outing_edit)) },text={ Column(Modifier.semantics {testTagsAsResourceId=true}.verticalScroll(rememberScrollState()).testTag("outing-editor-fields"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(name,{name=it},enabled=!busy,modifier=Modifier.testTag("outing-name"),label={Text(stringResource(R.string.visit_name))})
        TextButton(enabled=!busy && name.isNotBlank(),onClick={name=""}) {Text(stringResource(R.string.outing_default_name))}
        OutlinedTextField(notes,{notes=it},enabled=!busy,modifier=Modifier.testTag("outing-notes"),label={Text(stringResource(R.string.visit_notes))},minLines=3)
    } },confirmButton={key(ticket) {TextButton(enabled=!busy,onClick={if(actions.allows(ticket) && !busy) save(name,notes)}) {Text(stringResource(R.string.outing_save))}}},dismissButton={TextButton(enabled=!busy,onClick=dismiss) {Text(stringResource(R.string.visit_cancel))}})
}
