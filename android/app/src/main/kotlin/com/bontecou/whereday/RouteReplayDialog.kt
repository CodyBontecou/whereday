package com.bontecou.whereday

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
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

@Composable internal fun RouteReplayButton(graph:AppGraph,request:ReplayRequest,points:List<TrackPoint>,visits:List<Visit>,metric:Boolean,available:Boolean,endpoints:RouteEndpoints=RouteEndpoints.from(points)) {
    var showing by remember(request) {mutableStateOf(false)}
    OutlinedButton(enabled=available,onClick={showing=true}) {Text("Replay route")}
    if(showing) RouteReplayDialog(graph,request,points,visits,metric,endpoints) {showing=false}
}

@Composable private fun RouteReplayDialog(graph:AppGraph,request:ReplayRequest,points:List<TrackPoint>,visits:List<Visit>,metric:Boolean,endpoints:RouteEndpoints,dismiss:()->Unit) {
    var archive by remember {mutableStateOf<ReplayArchive?>(null)}
    var frame by remember {mutableStateOf<ReplayFrame?>(null)}
    var cursor by remember {mutableLongStateOf(0)}
    var playing by remember {mutableStateOf(true)}
    var error by remember {mutableStateOf<String?>(null)}
    var changed by remember {mutableStateOf(false)}
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
        try {owned=graph.replay.prepare(request);archive=owned}
        catch(e:CancellationException) {throw e}
        catch(_:Exception) {error="Route replay could not load. Close and try again.";playing=false}
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
        catch(_:Exception) {frame=null;error="Replay cache is unavailable. Close and try again.";playing=false}
    }
    LaunchedEffect(playing,frame,foreground) {
        val current=frame ?: return@LaunchedEffect
        if(playing && foreground && !changed) {
            if(current.index>=current.count-1) playing=false
            else {delay(250);cursor=RouteReplay.next(current.index,current.count)}
        }
    }
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}) {
                Column(Modifier.fillMaxSize().testTag("route-replay-content").verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text("Route replay",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall)
                        TextButton(onClick=dismiss) {Text("Close route replay")}
                    }
                    if(changed) Text("History changed. Close and start replay again to load the latest route.")
                    else if(frame==null && error==null) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Preparing route replay…")}
                    error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                    frame?.let {current->
                        JournalMap(points,visits,replay=current,endpoints=endpoints,showOutliers=(request as? ReplayRequest.Range)?.includeOutliers ?: false,allOutlierFallback=request !is ReplayRequest.Range)
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                            Button(onClick={if(!playing && cursor>=current.count-1) cursor=0;playing=!playing}) {Text(if(playing) "Pause route replay" else "Play route replay")}
                            Text("Point ${current.index+1} of ${current.count}",Modifier.weight(1f))
                        }
                        Text(timeText(current.current.time))
                        Slider(value=RouteReplay.progress(cursor,current.count).toFloat(),onValueChange={playing=false;cursor=RouteReplay.index(it.toDouble(),current.count) ?: 0},
                            modifier=Modifier.semantics {contentDescription="Route replay progress";stateDescription="Point ${current.index+1} of ${current.count}. Elapsed ${RouteReplay.formattedDuration(current.elapsed)} of ${RouteReplay.formattedDuration(current.duration)}. ${distanceText(current.distanceMeters,metric)} of ${distanceText(current.totalDistanceMeters,metric)}."})
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                            TextButton(modifier=Modifier.weight(1f),onClick={playing=false;cursor=0}) {Text("Replay start")}
                            TextButton(modifier=Modifier.weight(1f),onClick={playing=false;cursor=current.count-1}) {Text("Replay end")}
                        }
                        Text("Elapsed ${RouteReplay.formattedDuration(current.elapsed)} / ${RouteReplay.formattedDuration(current.duration)}")
                        Text("Distance ${distanceText(current.distanceMeters,metric)} / ${distanceText(current.totalDistanceMeters,metric)}")
                        if(current.index+1>current.visiblePoints.size) Text("Map draws ${current.visiblePoints.size} sampled points. Replay position and statistics include every route point.",style=MaterialTheme.typography.bodySmall)
                    }
                }
                // A safe scroll origin outside the embedded map's gesture surface.
                Spacer(Modifier.fillMaxHeight().width(20.dp).testTag("route-replay-scroll-margin"))
            }
        }
    }
}
