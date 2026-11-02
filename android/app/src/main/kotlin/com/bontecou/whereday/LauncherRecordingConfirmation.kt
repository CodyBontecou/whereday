package com.bontecou.whereday

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.material.icons.outlined.StopCircle
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
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
        title={Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
                if(already) Icon(Icons.Outlined.CheckCircle,null,Modifier.size(16.dp),tint=wd.textMuted)
                else if(start) WdLiveDot(Modifier.size(16.dp)) else Icon(Icons.Outlined.StopCircle,null,Modifier.size(16.dp),tint=wd.inkRust)
                Text(stringResource(R.string.launcher_kicker_recording).uppercase(),style=WdType.eyebrow,color=if(already) wd.textMuted else if(start) wd.accent else wd.inkRust)
            }
            Text(stringResource(when {already && start->R.string.launcher_already_active;already->R.string.launcher_already_stopped;start->R.string.launcher_start_title;else->R.string.launcher_stop_title}),style=WdType.title2,color=wd.textPrimary)
        }},
        text={Text(stringResource(when {already->R.string.launcher_check_journal;start->R.string.launcher_start_body;else->R.string.launcher_stop_body}),Modifier.verticalScroll(rememberScrollState()),
            style=MaterialTheme.typography.bodyLarge,color=wd.textMuted)},
        confirmButton={InkDialogButton(stringResource(when {already->R.string.launcher_done;start->R.string.shortcut_start;else->R.string.shortcut_stop}),approve,
            Modifier.testTag("launcher-recording-confirm"),destructive=!already && !start)},
        dismissButton={if(!already) InkDialogButton(stringResource(R.string.launcher_cancel),cancel,Modifier.testTag("launcher-recording-cancel"),muted=true)})
    }
}
