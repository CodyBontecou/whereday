package com.bontecou.whereday

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.Flow

internal fun interface MapPointMarkerSource {
    fun observe(viewport:MapPointViewport,selectedPointId:String?,replayIndex:Long?):Flow<MapPointMarkers>
}
@Composable internal fun rememberHistoryPointMarkerSource(graph:AppGraph,request:ReplayRequest):MapPointMarkerSource =
    remember(graph.history,request) {MapPointMarkerSource {viewport,selected,_->graph.history.observePointMarkers(request,viewport,selected)}}

internal data class MapPointMarkerLoad(val markers:MapPointMarkers,val ready:Boolean,val loading:Boolean=false,val failed:Boolean=false,val retry:()->Unit={})

/** A canceled/covered/replaced source cannot publish its previous viewport or selection. */
@Composable internal fun rememberMapPointMarkers(points:List<TrackPoint>,viewport:MapPointViewport?,selectedPointId:String?,source:MapPointMarkerSource?,replayIndex:Long?,enabled:Boolean,scope:Any?):MapPointMarkerLoad {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) {mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED))}
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,_->foreground=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)}
        lifecycle.addObserver(observer);onDispose {lifecycle.removeObserver(observer)}
    }
    var retries by remember {mutableIntStateOf(0)}
    val token=remember(points,viewport,selectedPointId,source,replayIndex,enabled,scope,foreground,retries) {Any()}
    var result by remember {mutableStateOf<Pair<Any,MapPointMarkerLoad>?>(null)}
    val available=enabled && foreground && viewport?.usable==true
    val local=remember(points,viewport,selectedPointId,available) {
        if(available) MapPointMarkerSampler(viewport,selectedPointId).apply {points.forEach(::add)}.snapshot() else MapPointMarkers(emptyList())
    }
    LaunchedEffect(token) {
        result=null
        if(!available || source==null) return@LaunchedEffect
        try {source.observe(checkNotNull(viewport),selectedPointId,replayIndex).collect {
            currentCoroutineContext().ensureActive()
            result=token to MapPointMarkerLoad(it,ready=true)
        }} catch(error:CancellationException) {throw error}
        catch(_:Exception) {result=token to MapPointMarkerLoad(MapPointMarkers(emptyList()),ready=false,failed=true)}
    }
    if(source==null) return MapPointMarkerLoad(local,ready=available || !enabled)
    val current=result?.takeIf {it.first===token}?.second
    return (current ?: MapPointMarkerLoad(MapPointMarkers(emptyList()),ready=!enabled,loading=available)).copy(retry={retries++})
}
