package com.bontecou.whereday

import androidx.compose.runtime.*
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

/** Drafts may survive a stop, but an old approval must not become current on resume. */
@Stable internal class VisibleEditorActions(private val lifecycle:Lifecycle) {
    private val active=AtomicBoolean(true)
    private val generation=AtomicLong()
    var epoch by mutableLongStateOf(0)
        private set
    fun allows(ticket:Long)=active.get() && generation.get()==ticket && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    fun stopped() {epoch=generation.incrementAndGet()}
    fun disposed() {active.set(false);generation.incrementAndGet()}
}

@Composable internal fun rememberVisibleEditorActions():VisibleEditorActions {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val actions=remember(lifecycle) {VisibleEditorActions(lifecycle)}
    DisposableEffect(lifecycle,actions) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP) actions.stopped()}
        lifecycle.addObserver(observer)
        onDispose {actions.disposed();lifecycle.removeObserver(observer)}
    }
    return actions
}
