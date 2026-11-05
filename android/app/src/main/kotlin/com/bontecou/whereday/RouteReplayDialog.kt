package com.bontecou.whereday

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
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
    // iOS's companion "Replay" capsule: paper with an ink play glyph.
    RouteReplayHost(graph,request,metric,available,onShowingChange,owner) {open,enabled->TraceCapsule(stringResource(R.string.map_replay),open,enabled=enabled,icon=Icons.Filled.PlayArrow,floating=true)}
}

/** Owns one replay opening for [request]; [trigger] draws whatever starts it (the map's mini dial, or a capsule). */
@Composable internal fun RouteReplayHost(graph:AppGraph,request:ReplayRequest,metric:Boolean,available:Boolean,onShowingChange:(Boolean)->Unit={},owner:String=request.toString(),
    trigger:@Composable (open:()->Unit,enabled:Boolean)->Unit) {
    var opening by rememberSaveable(owner,stateSaver=ReplayOpening.Saver) {mutableStateOf<ReplayOpening?>(null)}
    val current=opening?.takeIf {it.owner==owner}
    val showing=current!=null
    val latestShowingChange by rememberUpdatedState(onShowingChange)
    SideEffect {latestShowingChange(showing)}
    DisposableEffect(Unit) {onDispose {latestShowingChange(false)}}
    trigger({opening=ReplayOpening(owner,request)},available)
    if(current!=null) key(current) {RouteReplayDialog(graph,current.request,metric) {opening=null}}
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun RouteReplayDialog(graph:AppGraph,request:ReplayRequest,metric:Boolean,dismiss:()->Unit) {
    val reduceMotion=rememberReduceMotion()
    var speed by rememberSaveable {mutableStateOf(ReplaySpeed.normal.name)}
    BlockVisitLinks()
    var archive by remember {mutableStateOf<ReplayArchive?>(null)}
    var frame by remember {mutableStateOf<ReplayFrame?>(null)}
    var cursor by rememberSaveable {mutableLongStateOf(0)}
    var fingerprint by rememberSaveable {mutableStateOf<String?>(null)}
    // Reduce Motion: no auto-play on open; Play jumps instead of animating.
    var playing by remember {mutableStateOf(fingerprint==null && !reduceMotion)}
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
            else {delay(ReplayPlayback.tickMillis);cursor=ReplayPlayback(current.index,current.count,true,ReplaySpeed.restore(speed)).tick().index}
        }
    }
    var ahead by remember {mutableStateOf<List<TrackPoint>?>(null)}
    LaunchedEffect(archive) {
        val source=archive ?: return@LaunchedEffect
        // The whole route, drawn faintly ahead of the playhead.
        ahead=try {source.frame(source.count-1).visiblePoints} catch(e:CancellationException) {throw e} catch(_:Exception) {null}
    }
    val controller=remember {JournalMapController()}
    val colors=wd
    WorkspaceDialog(fillRegion=true,onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false,decorFitsSystemWindows=false)) {
        Surface(Modifier.fillMaxSize(),color=wd.surface,contentColor=wd.textPrimary) {
            Box(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("route-replay-content")) {
                val current=frame
                val visits=archive?.visits.orEmpty()
                val density=LocalDensity.current
                var panelHeight by remember {mutableStateOf(300.dp)}
                val top=WindowInsets.statusBars.asPaddingValues().calculateTopPadding()
                val padding=PaddingValues(top=top+84.dp,bottom=panelHeight+8.dp,start=12.dp,end=12.dp)
                if(current!=null) {
                    val markerSource=remember(archive) {archive?.let {source->MapPointMarkerSource {viewport,selected,index->
                        kotlinx.coroutines.flow.flow {emit(source.pointMarkers(viewport,checkNotNull(index),selected))}
                    }}}
                    val layers by graph.preferences.settings.collectAsStateWithLifecycle(initialValue=null)
                    JournalMap(current.visiblePoints,visits,Modifier.fillMaxSize(),followMetric=metric,replay=current,endpoints=archive?.endpoints ?: RouteEndpoints(),showOutliers=(request as? ReplayRequest.Range)?.includeOutliers ?: false,
                        allOutlierFallback=request !is ReplayRequest.Range,roadController=graph.roadDisplay,roadVisible=foreground && !changed,pointMarkerSource=markerSource,
                        home=MapHomeOptions(controller,(layers?.map ?: com.bontecou.whereday.data.MapLayerSettings()).copy(endpoints=false),padding,MapHomeMode.Day(current.current.time,false),replayAhead=ahead,overlayTop=top+84.dp))
                } else Box(Modifier.fillMaxSize().background(androidx.compose.ui.graphics.Color(MapStyles.land(colors.dark))))
                // Header card: the moment's date and clock over the serif "Replay", with Close.
                Row(Modifier.align(Alignment.TopCenter).padding(top=top+8.dp,start=12.dp,end=12.dp).fillMaxWidth().wdFloating(RoundedCornerShape(22.dp),colors,8.dp)
                    .padding(start=14.dp,end=8.dp,top=8.dp,bottom=8.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(1.dp)) {
                        Text(current?.let {replayKicker(it.current.time,locale)} ?: "",style=WdType.caption2.copy(fontSize=9.5.sp,letterSpacing=0.15.em,fontWeight=FontWeight.Medium),color=colors.textMuted,maxLines=1)
                        Text(stringResource(R.string.map_home_replay_title),style=WdType.title2.copy(fontSize=22.sp,lineHeight=24.sp),color=colors.textPrimary,modifier=Modifier.semantics {heading()})
                    }
                    IconButton(onClick=dismiss,modifier=Modifier.size(44.dp).testTag("replay-close")) {Icon(Icons.Outlined.Close,stringResource(R.string.replay_close),tint=colors.textPrimary)}
                }
                // Replay controls: status, place and clock; the day bar and scrubber; play, speeds and Done; distance and points.
                Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth().onSizeChanged {panelHeight=with(density) {it.height.toDp()}}
                    .shadow(16.dp,RoundedCornerShape(topStart=26.dp,topEnd=26.dp),ambientColor=colors.textPrimary.copy(alpha=0.14f),spotColor=colors.textPrimary.copy(alpha=0.14f))
                    .clip(RoundedCornerShape(topStart=26.dp,topEnd=26.dp)).background(colors.surface).navigationBarsPadding().padding(start=22.dp,end=22.dp,top=18.dp,bottom=18.dp)
                    .heightIn(max=480.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    if(changed) WdNotice(stringResource(R.string.replay_changed),icon=Icons.Outlined.Sync)
                    else if(current==null && error==null) Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                        LinearProgressIndicator(Modifier.fillMaxWidth(),color=wd.inkRust,trackColor=wd.dialTrack)
                        Text(stringResource(R.string.replay_preparing),style=WdType.caption,color=wd.textMuted)
                    }
                    error?.let {WdNotice(stringResource(it),icon=Icons.Outlined.ErrorOutline,warning=true)}
                    if(current!=null && !changed) {
                        val status=replayStatus(current,visits,metric)
                        Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                                Text(status.first,Modifier.testTag("replay-status"),style=WdType.caption2.copy(fontSize=10.5.sp,letterSpacing=0.13.em,fontWeight=FontWeight.Medium),color=colors.textMuted,maxLines=2)
                                Text(status.second,style=WdType.title2.copy(fontSize=24.sp,lineHeight=27.sp),color=colors.textPrimary,maxLines=2)
                            }
                            Text(dayClockText(current.current.time),Modifier.testTag("replay-clock"),style=WdType.title.copy(fontSize=42.sp,lineHeight=42.sp,fontFeatureSettings="tnum"),color=colors.textPrimary)
                        }
                        ReplayDayBar(current,visits)
                        val progressLabel=stringResource(R.string.replay_progress)
                        val progressState=stringResource(R.string.replay_progress_state,stringResource(R.string.replay_point,current.index+1,current.count),RouteReplay.formattedDuration(current.elapsed),RouteReplay.formattedDuration(current.duration),distanceText(current.distanceMeters,metric,locale),distanceText(current.totalDistanceMeters,metric,locale))
                        Row(verticalAlignment=Alignment.CenterVertically) {
                            IconButton(onClick={playing=false;cursor=0},Modifier.size(44.dp).testTag("replay-start")) {Icon(Icons.Filled.SkipPrevious,stringResource(R.string.replay_start),tint=colors.textMuted)}
                            Slider(value=RouteReplay.progress(cursor,current.count).toFloat(),onValueChange={playing=false;cursor=RouteReplay.index(it.toDouble(),current.count) ?: 0},
                                modifier=Modifier.weight(1f).testTag("replay-progress").semantics {contentDescription=progressLabel;stateDescription=progressState},
                                colors=SliderDefaults.colors(thumbColor=wd.inkRust,activeTrackColor=wd.inkRust,inactiveTrackColor=wd.dialTrack,activeTickColor=wd.inkRust,inactiveTickColor=wd.dialTick))
                            IconButton(onClick={playing=false;cursor=current.count-1},Modifier.size(44.dp).testTag("replay-end")) {Icon(Icons.Filled.SkipNext,stringResource(R.string.replay_end),tint=colors.textMuted)}
                        }
                        FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp),itemVerticalAlignment=Alignment.CenterVertically) {
                            val playLabel=stringResource(if(playing) R.string.replay_pause else R.string.replay_play)
                            Box(Modifier.size(52.dp).clip(CircleShape).background(colors.textPrimary).clickable(role=Role.Button,onClickLabel=playLabel) {
                                val next=ReplayPlayback(cursor,current.count,playing,ReplaySpeed.restore(speed)).toggle(reduceMotion)
                                cursor=next.index;playing=next.playing
                            }.semantics {contentDescription=playLabel}.testTag("replay-toggle"),contentAlignment=Alignment.Center) {
                                Icon(if(playing) Icons.Filled.Pause else Icons.Filled.PlayArrow,null,Modifier.size(22.dp),tint=colors.surface)
                            }
                            ReplaySpeed.entries.forEach {choice->
                                ReplayChip(choice.label,choice.name==speed,Modifier.testTag("replay-speed-${choice.multiplier}")) {speed=choice.name}
                            }
                            Spacer(Modifier.weight(1f))
                            ReplayChip(stringResource(R.string.map_home_done),false,Modifier.testTag("replay-done"),onClick=dismiss)
                        }
                        Row(Modifier.fillMaxWidth().drawBehind {drawLine(colors.border,androidx.compose.ui.geometry.Offset(0f,0f),androidx.compose.ui.geometry.Offset(size.width,0f),1.dp.toPx())}.padding(top=10.dp),
                            horizontalArrangement=Arrangement.SpaceBetween) {
                            val footer=WdType.caption2.copy(fontSize=10.5.sp,letterSpacing=0.1.em,fontWeight=FontWeight.Medium)
                            Text(stringResource(R.string.map_home_replay_so_far,dayDistance(current.distanceMeters,metric,locale)).uppercase(),style=footer,color=colors.textMuted)
                            Text(stringResource(R.string.map_home_replay_points,java.text.NumberFormat.getIntegerInstance(locale).format(current.index+1),java.text.NumberFormat.getIntegerInstance(locale).format(current.count)).uppercase(locale),
                                Modifier.testTag("replay-counter"),style=footer,color=colors.textMuted)
                        }
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                            Text(stringResource(R.string.replay_elapsed,RouteReplay.formattedDuration(current.elapsed),RouteReplay.formattedDuration(current.duration)),Modifier.testTag("replay-elapsed"),style=WdType.caption2,color=wd.textMuted)
                            Text(stringResource(R.string.replay_distance,distanceText(current.distanceMeters,metric,locale),distanceText(current.totalDistanceMeters,metric,locale)),Modifier.testTag("replay-distance"),style=WdType.caption2,color=wd.textMuted)
                        }
                        if(current.index+1>current.visiblePoints.size) Text(resources.getQuantityString(R.plurals.replay_sampled,current.visiblePoints.size,current.visiblePoints.size),style=WdType.caption2,color=wd.textMuted)
                    }
                }
            }
        }
    }
}

private fun replayKicker(time:java.time.Instant,locale:Locale):String {
    val at=time.atZone(ZoneId.systemDefault())
    return listOf(DateTimeFormatter.ofPattern("EEE",locale).format(at),DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale,"MMMd"),locale).format(at),dayClock.format(at))
        .joinToString(" · ").uppercase(locale)
}

/** iOS traceStatus: staying somewhere, arriving, or on the move ("WALKING · 4 KM/H" / "Toward Ferry Building"). */
@Composable private fun replayStatus(frame:ReplayFrame,visits:List<Visit>,metric:Boolean):Pair<String,String> {
    val time=frame.current.time
    val sorted=visits.sortedBy {it.arrivedAt}
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    sorted.lastOrNull {it.arrivedAt<=time && (it.departedAt==null || time<=it.departedAt)}?.let {visit->
        return stringResource(R.string.map_home_replay_staying,dayClockText(visit.arrivedAt)).uppercase(locale) to (visit.name?.takeIf {it.isNotBlank()} ?: stringResource(R.string.timeline_visit))
    }
    val neighbour=frame.visiblePoints.getOrNull(frame.visiblePoints.size-2)
    val speed=ReplayPlayback.movementSpeed(frame.current,neighbour)
    val kicker=speed?.let {value->
        val mode=TravelMode.infer(value)?.let {travelModeActive(it)} ?: stringResource(R.string.map_home_replay_moving)
        "$mode · ${if(metric) String.format(locale,"%.0f km/h",value*3.6) else String.format(locale,"%.0f mph",value*2.23694)}"
    } ?: stringResource(R.string.map_home_replay_moving)
    val title=sorted.firstOrNull {it.arrivedAt>time}?.name?.takeIf {it.isNotBlank()}?.let {stringResource(R.string.map_home_replay_toward,it)}
        ?: sorted.lastOrNull {(it.departedAt ?: time)<time}?.name?.takeIf {it.isNotBlank()}?.let {stringResource(R.string.map_home_replay_after,it)}
        ?: stringResource(R.string.map_home_replay_on_move)
    return kicker.uppercase(locale) to title
}

/** The 8dp segmented day bar: each stay in its place ink across the replayed span. */
@Composable private fun ReplayDayBar(frame:ReplayFrame,visits:List<Visit>) {
    val colors=wd
    val end=frame.current.time.plus(frame.duration.minus(frame.elapsed))
    val start=frame.current.time.minus(frame.elapsed)
    val span=java.time.Duration.between(start,end).toMillis().coerceAtLeast(1).toFloat()
    val inks=PlaceInks.index(visits)
    val segments=visits.mapNotNull {visit->
        val from=maxOf(visit.arrivedAt,start);val until=minOf(visit.departedAt ?: end,end)
        if(until<=from) null else Triple(java.time.Duration.between(start,from).toMillis()/span,java.time.Duration.between(start,until).toMillis()/span,dayInk(inks[placeKey(visit)] ?: 0))
    }
    Box(Modifier.fillMaxWidth().height(8.dp).clip(RoundedCornerShape(4.dp)).background(colors.border).clearAndSetSemantics {}.drawBehind {
        segments.forEach {(a,b,ink)->drawRect(ink,androidx.compose.ui.geometry.Offset(size.width*a,0f),androidx.compose.ui.geometry.Size((size.width*(b-a)).coerceAtLeast(2f),size.height))}
    })
}

@Composable private fun ReplayChip(text:String,selected:Boolean,modifier:Modifier=Modifier,onClick:()->Unit) {
    val colors=wd
    Box(modifier.heightIn(min=40.dp).clip(CircleShape).background(if(selected) colors.textPrimary else colors.card).border(1.dp,if(selected) colors.textPrimary else colors.border,CircleShape)
        .selectable(selected,role=Role.Button,onClick=onClick).padding(horizontal=14.dp),contentAlignment=Alignment.Center) {
        Text(text,style=MaterialTheme.typography.bodyMedium.copy(fontWeight=FontWeight.Medium),color=if(selected) colors.surface else colors.textPrimary)
    }
}
