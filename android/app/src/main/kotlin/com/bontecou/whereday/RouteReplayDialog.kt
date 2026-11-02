package com.bontecou.whereday

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bontecou.whereday.data.ReplayArchive
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.drop

@Composable internal fun RouteReplayButton(graph:AppGraph,request:ReplayRequest,metric:Boolean,available:Boolean,onShowingChange:(Boolean)->Unit={},owner:String=request.toString()) {
    var opening by rememberSaveable(owner,stateSaver=ReplayOpening.Saver) {mutableStateOf<ReplayOpening?>(null)}
    val current=opening?.takeIf {it.owner==owner}
    val showing=current!=null
    val latestShowingChange by rememberUpdatedState(onShowingChange)
    SideEffect {latestShowingChange(showing)}
    DisposableEffect(Unit) {onDispose {latestShowingChange(false)}}
    OutlinedButton(enabled=available,onClick={opening=ReplayOpening(owner,request)}) {Text(stringResource(R.string.map_replay))}
    if(current!=null) key(current) {RouteReplayDialog(graph,current.request,metric) {opening=null}}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun RouteReplayDialog(graph:AppGraph,request:ReplayRequest,metric:Boolean,dismiss:()->Unit) {
    BlockVisitLinks()
    var archive by remember {mutableStateOf<ReplayArchive?>(null)}
    var frame by remember {mutableStateOf<ReplayFrame?>(null)}
    var cursor by rememberSaveable {mutableLongStateOf(0)}
    var fingerprint by rememberSaveable {mutableStateOf<String?>(null)}
    var playing by remember {mutableStateOf(fingerprint==null)}
    var error by remember {mutableStateOf<Int?>(null)}
    var changed by rememberSaveable {mutableStateOf(false)}
    val resources=LocalContext.current.resources
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val zone=ZoneId.systemDefault()
    val timeFormatter=remember(locale,zone) {DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM,FormatStyle.SHORT).withLocale(locale).withZone(zone)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var foreground by remember {mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,_->
            foreground=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if(!foreground) playing=false
        }
        lifecycle.addObserver(observer);onDispose {lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(graph) {
        graph.database.invalidationTracker.createFlow("points","sessions").drop(1).collect {changed=true;playing=false;frame=null}
    }
    LaunchedEffect(request,changed,foreground) {
        if(changed || !foreground) return@LaunchedEffect
        var owned:ReplayArchive?=null
        try {
            owned=graph.replay.prepare(request)
            if(fingerprint!=null && fingerprint!=owned.fingerprint) {changed=true;playing=false;frame=null}
            else {fingerprint=owned.fingerprint;archive=owned}
        }
        catch(e:CancellationException) {throw e}
        catch(_:Exception) {error=R.string.replay_load_error;playing=false}
        finally {
            // Keep the archive until this effect is cancelled by close, data change or backgrounding.
            if(owned!=null) try {awaitCancellation()} finally {
                if(archive===owned) {archive=null;frame=null}
                withContext(NonCancellable) {owned.close()}
            }
        }
    }
    LaunchedEffect(archive,cursor,changed) {
        val source=archive ?: return@LaunchedEffect
        if(changed) return@LaunchedEffect
        try {frame=source.frame(cursor)}
        catch(e:CancellationException) {throw e}
        catch(_:Exception) {frame=null;error=R.string.replay_cache_error;playing=false}
    }
    LaunchedEffect(playing,frame,foreground) {
        val current=frame ?: return@LaunchedEffect
        if(playing && foreground && !changed) {
            if(current.index>=current.count-1) playing=false
            else {delay(250);cursor=RouteReplay.next(current.index,current.count)}
        }
    }
    WorkspaceDialog(fillRegion=true,onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}) {
                Column(Modifier.fillMaxSize().testTag("route-replay-content").verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(stringResource(R.string.replay_title),style=MaterialTheme.typography.headlineSmall)
                        TextButton(onClick=dismiss,modifier=Modifier.testTag("replay-close")) {Text(stringResource(R.string.replay_close))}
                    }
                    if(changed) Text(stringResource(R.string.replay_changed))
                    else if(frame==null && error==null) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(R.string.replay_preparing))}
                    error?.let {Text(stringResource(it),color=MaterialTheme.colorScheme.error)}
                    frame?.let {current->
                        val markerSource=remember(archive) {archive?.let {source->MapPointMarkerSource {viewport,selected,index->
                            kotlinx.coroutines.flow.flow {emit(source.pointMarkers(viewport,checkNotNull(index),selected))}
                        }}}
                        JournalMap(current.visiblePoints,archive?.visits.orEmpty(),followMetric=metric,replay=current,endpoints=archive?.endpoints ?: RouteEndpoints(),showOutliers=(request as? ReplayRequest.Range)?.includeOutliers ?: false,allOutlierFallback=request !is ReplayRequest.Range,roadController=graph.roadDisplay,roadVisible=foreground && !changed,pointMarkerSource=markerSource)
                        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            Button(modifier=Modifier.testTag("replay-toggle"),onClick={if(!playing && cursor>=current.count-1) cursor=0;playing=!playing}) {Text(stringResource(if(playing) R.string.replay_pause else R.string.replay_play))}
                            Text(stringResource(R.string.replay_point,current.index+1,current.count),Modifier.testTag("replay-counter"))
                        }
                        Text(timeFormatter.format(current.current.time))
                        val progressLabel=stringResource(R.string.replay_progress)
                        val progressState=stringResource(R.string.replay_progress_state,stringResource(R.string.replay_point,current.index+1,current.count),RouteReplay.formattedDuration(current.elapsed),RouteReplay.formattedDuration(current.duration),distanceText(current.distanceMeters,metric,locale),distanceText(current.totalDistanceMeters,metric,locale))
                        Slider(value=RouteReplay.progress(cursor,current.count).toFloat(),onValueChange={playing=false;cursor=RouteReplay.index(it.toDouble(),current.count) ?: 0},
                            modifier=Modifier.testTag("replay-progress").semantics {contentDescription=progressLabel;stateDescription=progressState})
                        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                            TextButton(modifier=Modifier.testTag("replay-start"),onClick={playing=false;cursor=0}) {Text(stringResource(R.string.replay_start))}
                            TextButton(modifier=Modifier.testTag("replay-end"),onClick={playing=false;cursor=current.count-1}) {Text(stringResource(R.string.replay_end))}
                        }
                        Text(stringResource(R.string.replay_elapsed,RouteReplay.formattedDuration(current.elapsed),RouteReplay.formattedDuration(current.duration)),Modifier.testTag("replay-elapsed"))
                        Text(stringResource(R.string.replay_distance,distanceText(current.distanceMeters,metric,locale),distanceText(current.totalDistanceMeters,metric,locale)),Modifier.testTag("replay-distance"))
                        if(current.index+1>current.visiblePoints.size) Text(resources.getQuantityString(R.plurals.replay_sampled,current.visiblePoints.size,current.visiblePoints.size),style=MaterialTheme.typography.bodySmall)
                    }
                }
                // A safe scroll origin outside the embedded map's gesture surface.
                Spacer(Modifier.fillMaxHeight().width(20.dp).testTag("route-replay-scroll-margin"))
            }
        }
    }
}
