package com.bontecou.whereday

import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean

private data class LauncherReviewKey(val action:LauncherAction?,val active:Boolean,val generation:Long)

/** Ordinary callbacks freeze the ticket identity outside Compose's updated lambda captures. */
private class LauncherConsentGate(private val lifecycle:Lifecycle) {
    class Ticket(val key:LauncherReviewKey) {
        val consumed=AtomicBoolean(false)
        var hidden by mutableStateOf(false)
    }
    private var current:Ticket?=null
    var alive=true
    fun review(key:LauncherReviewKey)=Ticket(key).also {current=it}
    fun invalidate() {current=null}
    private fun consume(ticket:Ticket):Boolean {
        if(!alive || current!==ticket || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) || !ticket.consumed.compareAndSet(false,true)) return false
        ticket.hidden=true;return true
    }
    fun cancel(ticket:Ticket,dismiss:()->Unit):()->Unit = {if(consume(ticket)) dismiss()}
    fun approve(ticket:Ticket,dismiss:()->Unit,confirm:(Boolean)->Unit):()->Unit = {
        if(consume(ticket)) {
            val start=ticket.key.action==LauncherAction.start
            val already=if(start) ticket.key.active else !ticket.key.active
            if(already) dismiss() else confirm(start)
        }
    }
}

/** Public shortcuts request a single foreground choice; callbacks never become recording toggles. */
@Composable internal fun LauncherRecordingConfirmation(action:LauncherAction?,active:Boolean,dismiss:()->Unit,confirm:(Boolean)->Unit) {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var foreground by remember(lifecycle) {mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))}
    var generation by remember(lifecycle) {mutableLongStateOf(0)}
    val gate=remember(lifecycle) {LauncherConsentGate(lifecycle)}
    DisposableEffect(gate) {
        val observer=LifecycleEventObserver {_,event->when(event) {
            Lifecycle.Event.ON_STOP->{gate.invalidate();foreground=false;generation++}
            Lifecycle.Event.ON_START->foreground=true
            else->Unit
        }}
        lifecycle.addObserver(observer)
        onDispose {gate.alive=false;gate.invalidate();lifecycle.removeObserver(observer)}
    }
    val key=LauncherReviewKey(action,active,generation)
    val ticket=remember(gate,key) {gate.review(key)}
    val latestDismiss=rememberUpdatedState(dismiss)
    val latestConfirm=rememberUpdatedState(confirm)
    val cancel=remember(gate,ticket) {gate.cancel(ticket) {latestDismiss.value()}}
    val approve=remember(gate,ticket) {gate.approve(ticket,{latestDismiss.value()}) {latestConfirm.value(it)}}
    if(action!=LauncherAction.start && action!=LauncherAction.stop || !foreground || ticket.hidden) return
    val start=action==LauncherAction.start
    val already=if(start) active else !active
    key(ticket) { WorkspaceAlertDialog(onDismissRequest=cancel,
        title={Text(stringResource(when {already && start->R.string.launcher_already_active;already->R.string.launcher_already_stopped;start->R.string.launcher_start_title;else->R.string.launcher_stop_title}))},
        text={Text(stringResource(when {already->R.string.launcher_check_journal;start->R.string.launcher_start_body;else->R.string.launcher_stop_body}),Modifier.verticalScroll(rememberScrollState()))},
        confirmButton={TextButton(onClick=approve,modifier=Modifier.testTag("launcher-recording-confirm")) {
            Text(stringResource(when {already->R.string.launcher_done;start->R.string.shortcut_start;else->R.string.shortcut_stop}))
        }},
        dismissButton={if(!already) TextButton(onClick=cancel,modifier=Modifier.testTag("launcher-recording-cancel")) {Text(stringResource(R.string.launcher_cancel))}})
    }
}
