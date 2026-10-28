package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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
    data class Failed(val message: String): OutingState
}
@Composable fun OutingDetailScreen(graph: AppGraph,id: String,metric: Boolean,back: () -> Unit,showOnMap: (RecordingSession) -> Unit,inferredHeader: InferredOutingHeader?=null,sequence:Int=1) {
    val state by produceState<OutingState>(OutingState.Loading,id,inferredHeader?.endedAt) {
        graph.database.invalidationTracker.createFlow("points","sessions","visits").collectLatest {
            try { value=OutingState.Ready(if(inferredHeader==null) graph.history.outingDetail(id) else graph.history.inferredDetail(inferredHeader)) }
            catch(e:Exception) { if(e is CancellationException) throw e; value=OutingState.Failed("This outing could not be loaded. It may have been deleted.") }
        }
    }
    var editing by remember { mutableStateOf(false) }
    var selectedVisit by remember { mutableStateOf<Visit?>(null) }
    var deleting by remember { mutableStateOf(false) }
    var busy by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val context=LocalContext.current
    val scope=rememberCoroutineScope()
    val exportSettings by graph.exportSettings.state.collectAsStateWithLifecycle()
    BackHandler(onBack=back)
    Box(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        TextButton(onClick=back) { Text("Back to timeline") }
        when(val current=state) {
            OutingState.Loading -> LinearProgressIndicator(Modifier.fillMaxWidth())
            is OutingState.Failed -> Text(current.message,color=MaterialTheme.colorScheme.error)
            is OutingState.Ready -> {
                val detail=current.detail
                val session=detail.session
                val photoRange=remember(session.startedAt,session.endedAt,detail.summary.count) {HistoryRange(session.startedAt,(session.endedAt ?: java.time.Instant.now()).plusMillis(1))}
                val photos=matchedPhotos(graph,photoRange)
                val photoLayer=photoMapLayer(graph,photos.window?.photos.orEmpty())
                Text(session.name ?: if(detail.inferred) "Inferred outing" else "Outing details",style=MaterialTheme.typography.headlineSmall)
                Text("${timeText(session.startedAt)} → ${session.endedAt?.let(::timeText) ?: "Recording"}")
                Text("${detail.summary.count} points · ${detail.summary.outlierCount} GPS glitches")
                Text(distanceText(detail.summary.distanceMeters,metric),style=MaterialTheme.typography.headlineMedium)
                val duration=java.time.Duration.between(session.startedAt,session.endedAt ?: java.time.Instant.now()).toMinutes().coerceAtLeast(0)
                Text("${duration/60}h ${duration%60}m · ${detail.summary.averageSpeed?.let { speed -> distanceText(speed*3600,metric)+"/h average" } ?: "Average speed unavailable"}")
                RouteReplayButton(graph,if(inferredHeader==null) ReplayRequest.Outing(id) else ReplayRequest.Inferred(inferredHeader),detail.points,detail.visits,metric,
                    ((detail.summary.count-detail.summary.outlierCount).takeIf {it>0} ?: detail.summary.count)>=2,detail.endpoints)
                JournalMap(detail.points,detail.visits,endpoints=detail.endpoints,allOutlierFallback=true,onVisitSelected={selectedVisit=it},photoLayer=photoLayer)
                if(detail.summary.count>detail.points.size) Text("The map is sampled; statistics include all ${detail.summary.count} points.",style=MaterialTheme.typography.bodySmall)
                session.notes?.takeIf { it.isNotBlank() }?.let { Text(it,style=MaterialTheme.typography.bodyLarge) }
                Button(onClick={ showOnMap(session) }) { Text("Show on map") }
                if(!detail.inferred) OutlinedButton(enabled=!busy,onClick={ editing=true }) { Text("Edit outing") }
                else Text("This outing is inferred from gaps in imported or older route points. Its boundaries follow your inferred-outing settings.",style=MaterialTheme.typography.bodyMedium)
                if(detail.visits.isNotEmpty()) {
                    Text("Linked visits",style=MaterialTheme.typography.titleLarge)
                    detail.visits.forEach { visit -> VisitNavigationRow(visit) {selectedVisit=visit} }
                }
                MatchedPhotoStrip(graph,photos,photoRange)
                Choice("Outing file format",exportSettings.selection.format.name,ExportFormat.entries.map {it.name},enabled=exportSettings.ready && !exportSettings.saving) {
                    graph.exportSettings.update(exportSettings.selection.copy(format=ExportFormat.valueOf(it)))
                }
                Text("JSON and CSV contain the outing summary. Markdown also includes visits and every route point. Route formats contain points only.",style=MaterialTheme.typography.bodySmall)
                HistoryFileActions(graph,"Export outing",!busy && exportSettings.ready && !exportSettings.saving && exportSettings.error==null) {
                    val selection=exportSettings.selection
                    graph.exports.prepareOuting(id,selection.format,selection.options,selection.filename,sequence,inferredHeader)
                }
                exportSettings.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                TextButton(enabled=!busy,onClick={ deleting=true }) { Text("Delete outing",color=MaterialTheme.colorScheme.error) }
                if(editing) OutingEditor(session,{ editing=false }) { name,notes -> scope.launch {
                    busy=true
                    try { graph.journalCommands.editOuting(id,name,notes); editing=false }
                    catch(e:Exception) { if(e is CancellationException) throw e; error="Your outing changes could not be saved." }
                    finally { busy=false }
                } }
            }
        }
        error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
    }
    Spacer(Modifier.fillMaxHeight().width(20.dp).testTag("outing-detail-scroll-margin"))
    }
    selectedVisit?.let {visit-> key(visit.id) {
        VisitEditor(graph,visit,{selectedVisit=null},{value,reuse->graph.history.saveVisit(value,visit,reuse)},{graph.history.deleteVisit(visit)})
    }}
    if(deleting) AlertDialog(onDismissRequest={ deleting=false },title={ Text("Delete this outing?") },text={ Text("This removes the outing and its recorded points. Visits remain in your journal. An active recording will stop first.") },confirmButton={ TextButton(enabled=!busy,onClick={ scope.launch {
        busy=true
        try {
            if(inferredHeader==null) {
                if(graph.history.dao.recording()?.sessionId==id) graph.recordingCommands.stop()
                graph.history.deleteSession(id)
            } else graph.history.deleteInferred(inferredHeader)
            deleting=false; back()
        } catch(e:Exception) { if(e is CancellationException) throw e; error="The outing could not be deleted."; deleting=false }
        finally { busy=false }
    } }) { Text("Delete outing") } },dismissButton={ TextButton(onClick={ deleting=false }) { Text("Cancel") } })
}

@Composable private fun OutingEditor(session: RecordingSession,dismiss: () -> Unit,save: (String?,String?) -> Unit) {
    var name by remember { mutableStateOf(session.name.orEmpty()) }
    var notes by remember { mutableStateOf(session.notes.orEmpty()) }
    AlertDialog(onDismissRequest=dismiss,title={ Text("Edit outing") },text={ Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
        OutlinedTextField(name,{ name=it },label={ Text("Name") })
        TextButton(enabled=name.isNotBlank(),onClick={ name="" }) { Text("Use default name") }
        OutlinedTextField(notes,{ notes=it },label={ Text("Notes") },minLines=3)
    } },confirmButton={ TextButton(onClick={ save(name,notes) }) { Text("Save outing") } },dismissButton={ TextButton(onClick=dismiss) { Text("Cancel") } })
}
