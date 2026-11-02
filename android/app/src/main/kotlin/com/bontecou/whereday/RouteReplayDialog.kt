package com.bontecou.whereday

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.ErrorOutline
import androidx.compose.material.icons.outlined.Sync
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.em
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
    // iOS's companion "Replay" capsule: paper with an ink play glyph.
    TraceCapsule(stringResource(R.string.map_replay),{opening=ReplayOpening(owner,request)},enabled=available,icon=Icons.Filled.PlayArrow,floating=true)
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
        Surface(Modifier.fillMaxSize(),color=wd.surface,contentColor=wd.textPrimary) {
            Box(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}) {
                Column(Modifier.fillMaxSize().testTag("route-replay-content").verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                            Text(stringResource(R.string.map_redesign_header_eyebrow,frame?.let {timeFormatter.format(it.current.time)} ?: "").trimEnd(' ','·').uppercase(),
                                style=WdType.caption2.copy(letterSpacing=0.14.em),color=wd.textMuted,maxLines=1)
                            Text(stringResource(R.string.replay_title),style=WdType.title,color=wd.textPrimary,modifier=Modifier.semantics {heading()})
                        }
                        WdCircleButton(Icons.Outlined.Close,stringResource(R.string.replay_close),dismiss,Modifier.testTag("replay-close"))
                    }
                    if(changed) WdNotice(stringResource(R.string.replay_changed),icon=Icons.Outlined.Sync)
                    else if(frame==null && error==null) WdCard(radius=18.dp) {
                        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                            LinearProgressIndicator(Modifier.fillMaxWidth(),color=wd.inkRust,trackColor=wd.dialTrack)
                            Text(stringResource(R.string.replay_preparing),style=WdType.caption,color=wd.textMuted)
                        }
                    }
                    error?.let {WdNotice(stringResource(it),icon=Icons.Outlined.ErrorOutline,warning=true)}
                    frame?.let {current->
                        val markerSource=remember(archive) {archive?.let {source->MapPointMarkerSource {viewport,selected,index->
                            kotlinx.coroutines.flow.flow {emit(source.pointMarkers(viewport,checkNotNull(index),selected))}
                        }}}
                        JournalMap(current.visiblePoints,archive?.visits.orEmpty(),followMetric=metric,replay=current,endpoints=archive?.endpoints ?: RouteEndpoints(),showOutliers=(request as? ReplayRequest.Range)?.includeOutliers ?: false,allOutlierFallback=request !is ReplayRequest.Range,roadController=graph.roadDisplay,roadVisible=foreground && !changed,pointMarkerSource=markerSource)
                        // RouteReplayControl: ink play pill, mono counter over a serif timestamp, rust scrubber, hairline stats.
                        WdCard(radius=18.dp) {
                            Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                                FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                    ReplayInkPill(stringResource(if(playing) R.string.replay_pause else R.string.replay_play),if(playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,
                                        Modifier.testTag("replay-toggle")) {if(!playing && cursor>=current.count-1) cursor=0;playing=!playing}
                                    Column(Modifier.align(Alignment.CenterVertically),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                                        Text(stringResource(R.string.replay_point,current.index+1,current.count),Modifier.testTag("replay-counter"),style=WdType.caption2.copy(letterSpacing=0.08.em),color=wd.textMuted)
                                        Text(timeFormatter.format(current.current.time),style=WdType.title3,color=wd.textPrimary)
                                    }
                                }
                                val progressLabel=stringResource(R.string.replay_progress)
                                val progressState=stringResource(R.string.replay_progress_state,stringResource(R.string.replay_point,current.index+1,current.count),RouteReplay.formattedDuration(current.elapsed),RouteReplay.formattedDuration(current.duration),distanceText(current.distanceMeters,metric,locale),distanceText(current.totalDistanceMeters,metric,locale))
                                Slider(value=RouteReplay.progress(cursor,current.count).toFloat(),onValueChange={playing=false;cursor=RouteReplay.index(it.toDouble(),current.count) ?: 0},
                                    modifier=Modifier.testTag("replay-progress").semantics {contentDescription=progressLabel;stateDescription=progressState},
                                    colors=SliderDefaults.colors(thumbColor=wd.inkRust,activeTrackColor=wd.inkRust,inactiveTrackColor=wd.dialTrack,activeTickColor=wd.inkRust,inactiveTickColor=wd.dialTick))
                                FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                                    TraceCapsule(stringResource(R.string.replay_start),{playing=false;cursor=0},Modifier.testTag("replay-start"),icon=Icons.Filled.SkipPrevious)
                                    TraceCapsule(stringResource(R.string.replay_end),{playing=false;cursor=current.count-1},Modifier.testTag("replay-end"),icon=Icons.Filled.SkipNext)
                                }
                                WdDivider()
                                Column(verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                    Text(stringResource(R.string.replay_elapsed,RouteReplay.formattedDuration(current.elapsed),RouteReplay.formattedDuration(current.duration)),Modifier.testTag("replay-elapsed"),style=WdType.caption,color=wd.textPrimary)
                                    Text(stringResource(R.string.replay_distance,distanceText(current.distanceMeters,metric,locale),distanceText(current.totalDistanceMeters,metric,locale)),Modifier.testTag("replay-distance"),style=WdType.caption,color=wd.textPrimary)
                                }
                            }
                        }
                        if(current.index+1>current.visiblePoints.size) Text(resources.getQuantityString(R.plurals.replay_sampled,current.visiblePoints.size,current.visiblePoints.size),style=WdType.caption2,color=wd.textMuted)
                    }
                }
                // A safe scroll origin outside the embedded map's gesture surface.
                Spacer(Modifier.fillMaxHeight().width(20.dp).testTag("route-replay-scroll-margin"))
            }
        }
    }
}

/** The replay play/pause control: a filled ink capsule with a paper glyph and label. */
@Composable private fun ReplayInkPill(text:String,icon:androidx.compose.ui.graphics.vector.ImageVector,modifier:Modifier=Modifier,onClick:()->Unit) {
    Row(modifier.heightIn(min=48.dp).clip(CircleShape).background(wd.accent).clickable(role=Role.Button,onClick=onClick).padding(start=12.dp,end=16.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        Icon(icon,null,Modifier.size(20.dp),tint=wd.surface)
        Text(text,style=MaterialTheme.typography.bodyMedium.copy(fontWeight=FontWeight.SemiBold),color=wd.surface)
    }
}
