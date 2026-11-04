package com.bontecou.whereday

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.listSaver
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.data.RecordingRow
import java.time.LocalDate
import java.time.Instant
import java.time.ZoneId
import com.bontecou.whereday.domain.HistoryRange
import com.bontecou.whereday.platform.AppGraph
import com.bontecou.whereday.domain.ManualVisitMode

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun JournalMapScreen(graph:AppGraph,model: JournalViewModel, active: Boolean, recording: RecordingRow?, metric: Boolean, onRecord: () -> Unit,showOutliers:Boolean,onOutliers:(Boolean)->Unit,access:RecordingAccessState?=null,onAccessOptions:(PermissionAccessKind)->Unit={},onVisitOpened:((com.bontecou.whereday.domain.Visit)->Unit)?=null,visitInspectionActive:Boolean=false) {
    val creationLabelColor=if(visitInspectionActive) wd.textMuted.copy(alpha=0.5f) else wd.accent
    val selected by model.mapSelection.collectAsStateWithLifecycle()
    val state by model.mapWindow.collectAsStateWithLifecycle()
    val activeRecording=rememberActiveRecordingMap(graph,active,showOutliers,visible=state is WindowState.Ready)
    var selectedVisit by rememberSaveable(stateSaver=VisitEditorVisitSaver) {mutableStateOf<com.bontecou.whereday.domain.Visit?>(null)}
    var currentVisit by rememberSaveable {mutableStateOf(false)}
    var visitMode by rememberSaveable {mutableStateOf(ManualVisitMode.current)}
    var visitSaved by remember {mutableStateOf(false)}
    val settings by graph.preferences.settings.collectAsStateWithLifecycle(initialValue=null)
    var custom by rememberSaveable { mutableStateOf(false) }
    var replayShowing by remember {mutableStateOf(false)}
    Box(Modifier.fillMaxSize().background(wd.surface).semantics {testTagsAsResourceId=true}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        RecordingControls(active,recording,record=onRecord)
        access?.let {RecordingAccessNotice(it,onAccessOptions)}
        // The iOS trace chrome: a floating date capsule beside the log-a-stop capsules.
        FlowRow(Modifier.fillMaxWidth().padding(horizontal=20.dp).padding(top=14.dp),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            MapDateFilter(selected.preset) {
                if(it=="Custom") custom=true else model.mapSelection.value=MapSelection(it)
            }
            MapCreationCapsule(stringResource(R.string.map_save_current),Icons.Outlined.AddLocationAlt,creationLabelColor,!visitInspectionActive) {visitMode=ManualVisitMode.current;currentVisit=true;visitSaved=false}
            MapCreationCapsule(stringResource(R.string.map_add_past),Icons.Outlined.History,creationLabelColor,!visitInspectionActive) {visitMode=ManualVisitMode.past;currentVisit=true;visitSaved=false}
        }
        if(visitSaved) Row(Modifier.padding(horizontal=20.dp).padding(top=8.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Outlined.CheckCircle,null,Modifier.size(16.dp),tint=wd.success)
            Text(stringResource(R.string.map_visit_saved),style=MaterialTheme.typography.bodySmall,color=wd.textPrimary)
        }
        when(val value=state) {
            WindowState.Loading -> WdCard(Modifier.padding(20.dp)) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    LinearProgressIndicator(Modifier.fillMaxWidth(),color=wd.accent,trackColor=wd.dialTrack)
                    Text(stringResource(R.string.map_loading_history),style=WdType.caption,color=wd.textMuted)
                }
            }
            is WindowState.Failed -> WdNotice(value.message,Modifier.padding(20.dp),icon=Icons.Outlined.ErrorOutline,warning=true) {
                WdButton(stringResource(R.string.map_retry_history),model::retry,icon=Icons.Outlined.Refresh)
            }
            is WindowState.Ready -> {
                val browsing=rememberPhotoBrowserRange(selected,value.window.range)
                val photos=matchedPhotos(graph,browsing.range,settings?.showPhotoMarkers==true)
                val photoLayer=photoMapLayer(graph,browsing.range,photos.count!=null,onBrowserRange=browsing.hold,validationFailed=photos.error!=null)
                RecordingScreen(value.window.points,value.window.visits,value.window.summary,metric) {
                RouteReplayButton(graph,com.bontecou.whereday.domain.ReplayRequest.Range(value.window.range,showOutliers),metric,(if(showOutliers) value.window.summary.count else value.window.summary.count-value.window.summary.outlierCount)>=2,onShowingChange={replayShowing=it},owner="${selected.copy(visitFocus=null)}:outliers=$showOutliers")
                JournalMap(value.window.points,value.window.visits,endpoints=value.window.endpoints,showOutliers=showOutliers,onOutliers=onOutliers,onVisitSelected={visit->if(onVisitOpened!=null) onVisitOpened(visit) else selectedVisit=visit},photoLayer=photoLayer,follow=rememberMapFollow(),followMetric=metric,activeRecording=activeRecording,
                    roadController=graph.roadDisplay,roadVisible=!visitInspectionActive && selectedVisit==null && !currentVisit && !custom && !replayShowing,
                    visitFocus=selected.visitFocus?.let {target->value.window.visits.find {it.id==target.id}?.let {target.copy(coordinate=it.coordinate)}},
                    onVisitFocusCleared={model.mapSelection.value=model.mapSelection.value.copy(visitFocus=null)},inspectionScope=selected.copy(visitFocus=null),
                    pointMarkerSource=rememberHistoryPointMarkerSource(graph,com.bontecou.whereday.domain.ReplayRequest.Range(value.window.range,showOutliers)))
                if(settings?.showPhotoMarkers==true) Column(verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    photos.error?.let {Text(stringResource(it),style=MaterialTheme.typography.bodySmall,color=wd.inkRust)}
                    photos.count?.let {if(it>500) Text(stringResource(R.string.map_photo_limit,it),style=WdType.caption2,color=wd.textMuted)}
                    if(photos.count==null && photos.error==null) Text(stringResource(R.string.map_checking_photos),style=WdType.caption2,color=wd.textMuted)
                    TraceCapsule(stringResource(R.string.map_refresh_photos),photos.refresh,icon=Icons.Outlined.Refresh)
                }
            }
            }
        }
        Spacer(Modifier.height(24.dp))
    }
    Spacer(Modifier.fillMaxHeight().width(20.dp).testTag("journal-map-scroll-margin"))
    }
    selectedVisit?.let {visit-> key(visit.id) {
        VisitEditor(graph,visit,{selectedVisit=null},{value,reuse,remember,overlap->graph.history.saveVisit(value,visit,reuse,remember,overlap)},{graph.history.deleteVisit(visit)})
    }}
    if(currentVisit) VisitEditor(graph,null,{currentVisit=false},{value,reuse,remember,overlap->
        graph.history.saveVisit(value,null,reuse,remember,overlap)
        visitSaved=true
    },null,visitMode)
    if(custom) {
        val draft=rememberSaveable(selected,saver=mapDateDraftSaver) {model.mapDateDraft()}
        CustomMapDateRange(draft,{custom=false}) {selection->
            model.mapSelection.value=selection;custom=false
        }
    }
}

/** A paper capsule for logging a stop, ink-tinted like the iOS add-place menu. */
@Composable private fun MapCreationCapsule(text:String,icon:androidx.compose.ui.graphics.vector.ImageVector,color:androidx.compose.ui.graphics.Color,enabled:Boolean,onClick:()->Unit) {
    val colors=wd
    Row(Modifier.heightIn(min=44.dp).wdFloating(CircleShape,colors,4.dp).clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(horizontal=14.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        Icon(icon,null,Modifier.size(16.dp),tint=color)
        Text(text,style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=color)
    }
}

internal val mapDateDraftSaver=listSaver<MapDateDraft,String>(
    save={listOf(it.range.from.toString(),it.range.until.toString(),it.zone.id,
        it.pendingFirst.toString(),it.pendingLast.toString(),it.selectedFirst.toString(),it.selectedLast.toString(),
        it.firstEdited.toString(),it.lastEdited.toString())},
    restore={values->
        val range=HistoryRange(Instant.parse(values[0]),Instant.parse(values[1]));val zone=ZoneId.of(values[2])
        if(values.size==3) MapDateDraft(range,zone) else MapDateDraft(range,zone,
            Instant.parse(values[3]),Instant.parse(values[4]),LocalDate.parse(values[5]),LocalDate.parse(values[6]),
            values[7].toBooleanStrict(),values[8].toBooleanStrict())
    }
)
