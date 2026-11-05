package com.bontecou.whereday

import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.repeatOnLifecycle
import com.bontecou.whereday.domain.ActiveRecordingMap
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.Flow

internal enum class ActiveRecordingMapStatus {off,inactive,paused,loading,empty,ready,failed}
internal data class ActiveRecordingMapUi(val enabled:Boolean,val snapshot:ActiveRecordingMap?,val status:ActiveRecordingMapStatus,val change:(Boolean)->Unit,val retry:()->Unit)
private data class ActiveRecordingRead(val owner:Any?=null,val complete:Boolean=false,val failed:Boolean=false,val value:ActiveRecordingMap?=null)

/** This observes committed history only. Recording intent and location subscriptions are unchanged. */
@Composable internal fun rememberActiveRecordingMap(graph:AppGraph,active:Boolean,includeOutliers:Boolean,visible:Boolean=true,source:((Boolean)->Flow<ActiveRecordingMap?>)?=null,
    /** The home map's persisted Live session layer; framed maps keep a per-screen switch. */
    persisted:Boolean?=null,onPersisted:((Boolean)->Unit)?=null):ActiveRecordingMapUi {
    var local by rememberSaveable {mutableStateOf(false)}
    val enabled=persisted ?: local
    var attempt by remember {mutableIntStateOf(0)}
    val ownerLifecycle=LocalLifecycleOwner.current.lifecycle
    val lifecycle by ownerLifecycle.currentStateAsState()
    val defaultSource=remember(graph) {{outliers:Boolean->graph.history.observeActiveRecordingMap(outliers)}}
    val reader=source ?: defaultSource
    val focused=LocalWindowInfo.current.isWindowFocused
    val eligible=enabled && active && visible && focused && lifecycle.isAtLeast(Lifecycle.State.RESUMED)
    val request=remember(graph,eligible,includeOutliers,attempt,reader) {Any()}
    val result by produceState(ActiveRecordingRead(),request) {
        value=ActiveRecordingRead(owner=request)
        if(eligible) try {
            // Lifecycle cancellation cannot depend on another Compose frame being rendered.
            ownerLifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
                try {
                    reader(includeOutliers).flowOn(Dispatchers.Default).collect {if(value.owner===request) value=ActiveRecordingRead(owner=request,complete=true,value=it)}
                } finally {if(value.owner===request) value=ActiveRecordingRead(owner=request)}
            }
        } catch(error:CancellationException) {throw error}
        catch(_:Exception) {if(value.owner===request) value=ActiveRecordingRead(owner=request,complete=true,failed=true)}
    }
    val current=result.owner===request
    val snapshot=result.value.takeIf {eligible && current && result.complete && !result.failed}
    val status=when {
        !enabled->ActiveRecordingMapStatus.off
        !active->ActiveRecordingMapStatus.inactive
        !eligible->ActiveRecordingMapStatus.paused
        current && result.failed->ActiveRecordingMapStatus.failed
        !current || !result.complete->ActiveRecordingMapStatus.loading
        snapshot==null->ActiveRecordingMapStatus.inactive
        snapshot.count==0L->ActiveRecordingMapStatus.empty
        else->ActiveRecordingMapStatus.ready
    }
    return ActiveRecordingMapUi(enabled,snapshot,status,onPersisted ?: {local=it},{attempt++})
}
