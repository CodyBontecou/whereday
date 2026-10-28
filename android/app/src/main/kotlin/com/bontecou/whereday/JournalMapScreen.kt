package com.bontecou.whereday

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.data.RecordingRow
import java.time.LocalDate
import com.bontecou.whereday.platform.AppGraph
import com.bontecou.whereday.domain.ManualVisitMode

@Composable fun JournalMapScreen(graph:AppGraph,model: JournalViewModel, active: Boolean, recording: RecordingRow?, metric: Boolean, onRecord: () -> Unit,showOutliers:Boolean,onOutliers:(Boolean)->Unit) {
    val selected by model.mapSelection.collectAsStateWithLifecycle()
    val state by model.mapWindow.collectAsStateWithLifecycle()
    var selectedVisit by remember {mutableStateOf<com.bontecou.whereday.domain.Visit?>(null)}
    var currentVisit by remember {mutableStateOf(false)}
    var visitMode by remember {mutableStateOf(ManualVisitMode.current)}
    var visitMessage by remember {mutableStateOf<String?>(null)}
    val settings by graph.preferences.settings.collectAsStateWithLifecycle(initialValue=null)
    var custom by rememberSaveable { mutableStateOf(false) }
    Box(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}) {
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState())) {
        RecordingControls(active,recording,onRecord)
        Column(Modifier.padding(horizontal=20.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick={visitMode=ManualVisitMode.current;currentVisit=true;visitMessage=null}) {Text("Save current place")}
            TextButton(onClick={visitMode=ManualVisitMode.past;currentVisit=true;visitMessage=null}) {Text("Add past visit")}
            visitMessage?.let {Text(it)}
        }
        Row(Modifier.fillMaxWidth().padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            Box(Modifier.weight(1f)) { Choice("Dates",selected.preset,listOf("Today","Yesterday","7 days","30 days","All","Custom")) {
                if(it=="Custom") custom=true else model.mapSelection.value=MapSelection(it)
            } }
        }
        when(val value=state) {
            WindowState.Loading -> Column(Modifier.padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                LinearProgressIndicator(Modifier.fillMaxWidth())
                Text("Loading journal history…")
            }
            is WindowState.Failed -> Column(Modifier.padding(20.dp)) { Text(value.message); TextButton(onClick=model::retry) { Text("Try again") } }
            is WindowState.Ready -> {
                val photos=matchedPhotos(graph,value.window.range,settings?.showPhotoMarkers==true)
                val photoLayer=photoMapLayer(graph,photos.window?.photos.orEmpty())
                RecordingScreen(value.window.points,value.window.visits,value.window.summary,metric) {
                RouteReplayButton(graph,com.bontecou.whereday.domain.ReplayRequest.Range(value.window.range,showOutliers),value.window.points,value.window.visits,metric,(if(showOutliers) value.window.summary.count else value.window.summary.count-value.window.summary.outlierCount)>=2,value.window.endpoints)
                JournalMap(value.window.points,value.window.visits,endpoints=value.window.endpoints,showOutliers=showOutliers,onOutliers=onOutliers,onVisitSelected={selectedVisit=it},photoLayer=photoLayer,follow=rememberMapFollow(),followMetric=metric)
                if(settings?.showPhotoMarkers==true) {
                    photos.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                    photos.window?.let {if(it.count>it.photos.size) Text("Showing ${it.photos.size} of ${it.count} matched photos.",style=MaterialTheme.typography.bodySmall)}
                    if(photos.window==null && photos.error==null) Text("Checking photo access…")
                    TextButton(onClick=photos.refresh) {Text("Refresh matched photos")}
                }
            }
            }
        }
    }
    Spacer(Modifier.fillMaxHeight().width(20.dp).testTag("journal-map-scroll-margin"))
    }
    selectedVisit?.let {visit-> key(visit.id) {
        VisitEditor(graph,visit,{selectedVisit=null},{value,reuse->graph.history.saveVisit(value,visit,reuse)},{graph.history.deleteVisit(visit)})
    }}
    if(currentVisit) VisitEditor(graph,null,{currentVisit=false},{value,reuse->
        graph.history.saveVisit(value,null,reuse)
        visitMessage="Visit saved. Review it in Timeline."
    },null,visitMode)
    if(custom) CustomDateRange(selected.firstDay ?: LocalDate.now().minusDays(6),selected.lastDay ?: LocalDate.now(),{ custom=false }) { first,last -> model.mapSelection.value=MapSelection("Custom",first,last); custom=false }
}

