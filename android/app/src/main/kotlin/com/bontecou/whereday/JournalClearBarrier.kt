package com.bontecou.whereday

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
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
    val checking=state.phase==JournalClearPhase.checking
    Surface(Modifier.fillMaxSize(),color=wd.surface) {Box(Modifier.fillMaxSize()) {
        FieldNotesBackground(cloudsOpacity=0.22f,streetOpacity=0.18f)
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState()).padding(horizontal=24.dp,vertical=32.dp).testTag("journal-clear-barrier").semantics {liveRegion=LiveRegionMode.Polite},
            horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(18.dp)) {
            Spacer(Modifier.height(48.dp))
            val failed=!state.busy
            Box(Modifier.size(64.dp).clip(CircleShape).background(wd.card).border(1.dp,if(failed) wd.inkRust.copy(alpha=0.5f) else wd.border,CircleShape),contentAlignment=Alignment.Center) {
                if(state.busy) CircularProgressIndicator(Modifier.size(28.dp),color=wd.accent,trackColor=wd.dialTrack,strokeWidth=2.5.dp)
                else Icon(if(state.needsApproval) Icons.Outlined.ReportProblem else Icons.Outlined.ErrorOutline,null,Modifier.size(28.dp),tint=wd.inkRust)
            }
            Text(stringResource(R.string.clear_recovery_kicker).uppercase(),style=WdType.eyebrow,color=if(failed) wd.inkRust else wd.textMuted,textAlign=TextAlign.Center)
            Text(stringResource(if(checking) R.string.clear_recovery_checking else R.string.clear_recovery_title),modifier=Modifier.semantics {heading()},
                style=WdType.title,color=wd.textPrimary,textAlign=TextAlign.Center)
            if(state.busy) Text(stringResource(if(checking) R.string.clear_recovery_checking_help else R.string.clear_recovery_progress),
                style=MaterialTheme.typography.bodyLarge,color=wd.textMuted,textAlign=TextAlign.Center)
            else {
                Text(stringResource(if(state.needsApproval) R.string.clear_recovery_unreadable else R.string.clear_recovery_failed),
                    style=MaterialTheme.typography.bodyLarge,color=wd.textMuted,textAlign=TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                WdButton(stringResource(if(state.needsApproval) R.string.clear_recovery_review else R.string.clear_recovery_retry),
                    {if(interactive()) {if(current.value.needsApproval) approval=RecoveryApproval(++serial,current.value.attempt) else latestRetry.value()}},
                    Modifier.testTag("journal-clear-retry"),emphasis=if(state.needsApproval) WdEmphasis.destructive else WdEmphasis.primary,
                    icon=if(state.needsApproval) Icons.Outlined.DeleteOutline else Icons.Outlined.Refresh)
            }
            WdNotice(stringResource(R.string.clear_recovery_exports),icon=Icons.Outlined.PhotoLibrary)
        }
    }}
    approval?.let {ticket->
        val attempt=ticket.attempt
        key(ticket,attempt) {WorkspaceAlertDialog(onDismissRequest={if(approval==ticket) approval=null},
            title={InkDialogTitle(stringResource(R.string.settings_clear_title),Icons.Outlined.DeleteOutline,wd.inkRust)},
            text={Text(stringResource(R.string.settings_clear_body),Modifier.verticalScroll(rememberScrollState()),style=MaterialTheme.typography.bodyLarge,color=wd.textMuted)},
            confirmButton={InkDialogButton(stringResource(R.string.settings_clear_confirm),{if(approval==ticket) {approval=null;if(interactive() && current.value.attempt==attempt && current.value.needsApproval) latestRetry.value()}},Modifier.testTag("journal-clear-recovery-confirm"),destructive=true)},
            dismissButton={InkDialogButton(stringResource(R.string.settings_cancel),{if(approval==ticket) approval=null},muted=true)})}
    }
}
