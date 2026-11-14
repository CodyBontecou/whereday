package com.bontecou.whereday

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalWindowInfo
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.repeatOnLifecycle
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.RoadDisplayController
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first

/** Derived display only. Never owns recording points, replay statistics or repository mutations. */
@Composable internal fun rememberRoadDisplay(controller:RoadDisplayController?,points:List<TrackPoint>,
    visible:Boolean,showOutliers:Boolean=false,allOutlierFallback:Boolean=false):RoadRoute? {
    if(controller==null) return null
    val windowInfo=LocalWindowInfo.current
    val focused=windowInfo.isWindowFocused
    val policy by controller.state.collectAsStateWithLifecycle()
    if(!visible || !focused || !policy.configured || !policy.enabled || !policy.networkAllowed || policy.loading ||
        policy.clearing || policy.policyUnavailable || !policy.journalReady || points.size>RoadSnapping.maximumSourcePoints) return null
    val source=remember(points,showOutliers,allOutlierFallback) {
        points.filter {showOutliers || !it.outlier}.ifEmpty {if(allOutlierFallback) points else emptyList()}
            .sortedWith(compareBy({it.time},{it.id}))
    }
    if(source.size<2) return null
    val fingerprint=remember(source) {RoadSnapping.fingerprint(source)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    // A source/policy transition owns a new session. Late teardown cannot suspend its replacement.
    val session=remember(controller,fingerprint,policy.policyRevision,policy.journalGeneration) {controller.createSession()}
    var prepared by remember(session) {mutableStateOf<RoadDisplayPlan?>(null)}
    DisposableEffect(session,lifecycle) {
        val observer=LifecycleEventObserver {_,_->
            if(!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) session.background()
        }
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer);session.close()}
    }
    LaunchedEffect(session,lifecycle,windowInfo) {
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            // Composition can still hold its pre-pause focus value when lifecycle resumes.
            // Observe the live snapshot before admitting work; cancellation removes this wait.
            snapshotFlow {windowInfo.isWindowFocused}.first {it}
            session.foreground()
            try {
                prepared=session.prepare(source)
                awaitCancellation()
            } catch(canceled:CancellationException) {throw canceled}
            catch(_:Exception) {prepared=null} // Raw geometry stays visible when preparation is unavailable.
            finally {prepared=null}
        }
    }
    val candidate=prepared
    return candidate?.route?.takeIf {lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && session.isCurrent(candidate,fingerprint)}
}
