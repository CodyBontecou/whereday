package com.bontecou.whereday

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bontecou.whereday.platform.*
import java.util.concurrent.atomic.AtomicBoolean

private data class RecoveryApproval(val id:Long,val attempt:Long)

/** The journal UI is absent until recovery finishes; this screen cannot dismiss the admission fence. */
@Composable internal fun JournalClearBarrier(state:JournalClearState,retry:()->Unit) {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val current=rememberUpdatedState(state)
    val latestRetry=rememberUpdatedState(retry)
    val active=remember {AtomicBoolean(true)}
    var approval by remember {mutableStateOf<RecoveryApproval?>(null)}
    var serial by remember {mutableLongStateOf(0)}
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP) approval=null}
        lifecycle.addObserver(observer)
        onDispose {active.set(false);lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(state.attempt,state.busy) {if(state.busy || approval?.attempt!=state.attempt) approval=null}
    fun interactive()=active.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED) && !current.value.busy
    Surface(Modifier.fillMaxSize()) {Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(24.dp).testTag("journal-clear-barrier").semantics {liveRegion=LiveRegionMode.Polite},verticalArrangement=Arrangement.spacedBy(20.dp)) {
        Text(stringResource(if(state.phase==JournalClearPhase.checking) R.string.clear_recovery_checking else R.string.clear_recovery_title),modifier=Modifier.semantics {heading()},style=MaterialTheme.typography.headlineSmall)
        if(state.busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(if(state.phase==JournalClearPhase.checking) R.string.clear_recovery_checking_help else R.string.clear_recovery_progress))}
        else {
            Text(stringResource(if(state.needsApproval) R.string.clear_recovery_unreadable else R.string.clear_recovery_failed))
            TextButton(onClick={if(interactive()) {if(current.value.needsApproval) approval=RecoveryApproval(++serial,current.value.attempt) else latestRetry.value()}},modifier=Modifier.testTag("journal-clear-retry")) {Text(stringResource(if(state.needsApproval) R.string.clear_recovery_review else R.string.clear_recovery_retry))}
        }
        Text(stringResource(R.string.clear_recovery_exports),style=MaterialTheme.typography.bodySmall)
    }}
    approval?.let {ticket->
        val attempt=ticket.attempt
        key(ticket,attempt) {WorkspaceAlertDialog(onDismissRequest={if(approval==ticket) approval=null},
            title={Text(stringResource(R.string.settings_clear_title))},text={Text(stringResource(R.string.settings_clear_body),Modifier.verticalScroll(rememberScrollState()))},
            confirmButton={TextButton(onClick={if(approval==ticket) {approval=null;if(interactive() && current.value.attempt==attempt && current.value.needsApproval) latestRetry.value()}},modifier=Modifier.testTag("journal-clear-recovery-confirm")) {Text(stringResource(R.string.settings_clear_confirm))}},
            dismissButton={TextButton(onClick={if(approval==ticket) approval=null}) {Text(stringResource(R.string.settings_cancel))}})}
    }
}
