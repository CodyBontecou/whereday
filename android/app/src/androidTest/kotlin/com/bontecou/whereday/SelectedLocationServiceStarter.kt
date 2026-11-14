package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.lifecycle.Lifecycle
import androidx.test.core.app.ActivityScenario
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*

/** Test-only user-action driver. Services still enforce their real foreground/permission guards. */
internal suspend fun <A:ComponentActivity> startSelectedLocationServices(
    scenario:ActivityScenario<A>, graph:AppGraph, route:Boolean, visits:Boolean,
    focusTimeoutMs:Long, betweenStarts:suspend ()->Unit={}
) {
    // Share the original single focus-wait allowance, rather than adding another full timeout.
    var focusBudgetNanos=focusTimeoutMs*1_000_000
    suspend fun focusedOwner():A {
        val began=System.nanoTime()
        try {
            return withTimeout((focusBudgetNanos/1_000_000).coerceAtLeast(0)) {
                var owner:A?=null
                while(owner==null) {
                    scenario.onActivity {current->
                        if(!current.isDestroyed && !current.isFinishing &&
                            current.lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED) && current.hasWindowFocus()) owner=current
                    }
                    currentCoroutineContext().ensureActive()
                    if(owner==null) delay(30)
                }
                checkNotNull(owner)
            }
        } catch(timeout:TimeoutCancellationException) {
            throw AssertionError("visible window; routeRequested=${graph.history.dao.recording()?.requested}; visitsRequested=${graph.history.dao.visitMonitoring()?.requested}; routeRuntime=${com.bontecou.whereday.platform.RecordingRuntime.active.value}; visitRuntime=${com.bontecou.whereday.platform.VisitMonitoringRuntime.state.value}",timeout)
        } finally {focusBudgetNanos=(focusBudgetNanos-(System.nanoTime()-began)).coerceAtLeast(0)}
    }
    // ActivityScenario owns the current instance. A previous command can suspend across
    // recreation, so never pass its now-destroyed owner to the next independent action.
    if(visits) graph.visitMonitoringCommands.startVisible(focusedOwner(),graph.journalClear.state.value.generation)
    if(visits && route) betweenStarts()
    if(route) graph.recordingCommands.startVisible(focusedOwner())
}
