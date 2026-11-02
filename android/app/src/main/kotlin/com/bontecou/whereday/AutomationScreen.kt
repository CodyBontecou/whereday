package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.platform.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

private data class AutomationReview(val id:Long,val key:AutomationReviewKey,val identity:AutomationIdentity,val scopes:Set<AutomationScope>)
private data class AutomationDialog(val id:Long,val key:AutomationReviewKey,val scopes:Set<AutomationScope>)
private fun AutomationFailure?.accessUnavailable()=this!=null && this!=AutomationFailure.install

@Composable fun AutomationScreen(graph:AppGraph,back:()->Unit) {
    val state by graph.automationController.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    LaunchedEffect(Unit) {graph.automationController.reload()}
    AutomationForm(state,back,graph.automationController::approve,graph.automationController::disable,graph.automationController::reload) {
        try {context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.llamalab.automate")))}
        catch(_:Exception) {graph.automationController.installUnavailable()}
    }
}

@Composable internal fun AutomationForm(state:AutomationUiState,back:()->Unit,approve:(AutomationIdentity,Set<AutomationScope>)->Unit,
    disable:()->Unit,reload:()->Unit,install:()->Unit) {
    val installed=state.installed;val approval=state.approval
    val current by rememberUpdatedState(state)
    val binding=automationBinding(state);val key=automationReviewKey(state)
    val baseline=automationBaseline(state)
    var draft by rememberSaveable(stateSaver=AutomationChoiceDraft.Saver) {mutableStateOf(AutomationChoiceDraft(binding,baseline))}
    val selected=if(draft.binding==binding) draft.scopes else baseline
    var discard by remember {mutableStateOf<AutomationDialog?>(null)}
    var reviewed by remember {mutableStateOf<AutomationReview?>(null)}
    var revoke by remember {mutableStateOf<AutomationDialog?>(null)}
    val sequence=remember {AtomicLong()};val active=remember {AtomicBoolean(true)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP) {reviewed=null;revoke=null;discard=null}}
        lifecycle.addObserver(observer)
        onDispose {active.set(false);lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(binding,state.loaded) {if(state.loaded && draft.binding!=binding) draft=AutomationChoiceDraft(binding,baseline)}
    LaunchedEffect(key,state.loaded) {
        if(!state.loaded || reviewed?.key!=key) reviewed=null
        if(!state.loaded || revoke?.key!=key) revoke=null
        if(!state.loaded || discard?.key!=key) discard=null
    }
    fun foreground()=active.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    fun interactive()=foreground() && current.loaded && !current.busy
    fun choices():Set<AutomationScope> = if(draft.binding==automationBinding(current)) draft.scopes else automationBaseline(current)
    fun canApprove():Boolean {
        val value=current;val chosen=choices()
        return interactive() && value.installed!=null && chosen.isNotEmpty() && (chosen!=automationBaseline(value) || value.approval?.identity!=value.installed || value.failure.accessUnavailable())
    }
    fun canDisable()=interactive() && (current.approval!=null || current.failure.accessUnavailable())
    fun leave() {
        if(!foreground()) return
        if(current.busy || choices()==automationBaseline(current)) back()
        else discard=AutomationDialog(sequence.incrementAndGet(),automationReviewKey(current),choices().toSet())
    }
    BackHandler {leave()}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).semantics {testTagsAsResourceId=true}.testTag("automation-screen").padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        TextButton(onClick={leave()}) {Text(stringResource(R.string.automation_back))}
        Text(stringResource(R.string.automation_title),style=MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.automation_intro))
        if(!state.loaded) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(R.string.automation_loading))}
        else {
            Text(stringResource(when {
                state.failure.accessUnavailable()->R.string.automation_status_unknown
                approval==null->R.string.automation_status_disabled
                installed!=approval.identity->R.string.automation_status_changed
                else->R.string.automation_status_connected
            }),Modifier.testTag("automation-status"),style=MaterialTheme.typography.titleMedium)
            if(installed==null) {
                Text(stringResource(R.string.automation_not_installed))
                OutlinedButton(enabled=!state.busy,onClick={if(interactive() && current.installed==null) install()}) {Text(stringResource(R.string.automation_install))}
            } else {
                Text(stringResource(R.string.automation_package,installed.packageName),style=MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.automation_installation_help),style=MaterialTheme.typography.bodySmall)
            }
            TextButton(enabled=!state.busy,onClick={if(interactive()) reload()}) {Text(stringResource(R.string.automation_reload))}
            HorizontalDivider()
            Text(stringResource(R.string.automation_allowed),style=MaterialTheme.typography.titleLarge)
            AutomationScope.entries.forEach {scope->
                Row(Modifier.fillMaxWidth().toggleable(scope in selected,enabled=installed!=null && !state.busy,role=Role.Checkbox) {value->
                    if(interactive() && current.installed!=null && automationReviewKey(current)==key) {
                        val now=choices();draft=AutomationChoiceDraft(automationBinding(current),if(value) now+scope else now-scope)
                    }
                }.testTag("automation-scope-${scope.name}").padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Checkbox(checked=scope in selected,onCheckedChange=null)
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {Text(scope.title(),style=MaterialTheme.typography.titleMedium);Text(scope.detail(),style=MaterialTheme.typography.bodySmall)}
                }
            }
            if(selected!=baseline) Text(stringResource(R.string.automation_unsaved),style=MaterialTheme.typography.labelLarge)
            Button(enabled=installed!=null && selected.isNotEmpty() && !state.busy && (selected!=baseline || approval?.identity!=installed || state.failure.accessUnavailable()),
                onClick={if(canApprove()) current.installed?.let {reviewed=AutomationReview(sequence.incrementAndGet(),automationReviewKey(current),it.snapshot(),choices().toSet())}},modifier=Modifier.testTag("automation-save")) {Text(stringResource(R.string.automation_save))}
            Text(stringResource(R.string.automation_results_help),style=MaterialTheme.typography.bodySmall)
            if(approval!=null || state.failure.accessUnavailable()) TextButton(enabled=!state.busy,onClick={if(canDisable()) revoke=AutomationDialog(sequence.incrementAndGet(),automationReviewKey(current),choices().toSet())}) {Text(stringResource(R.string.automation_disable),color=MaterialTheme.colorScheme.error)}
            if(state.busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(R.string.automation_saving))}
            state.notice?.let {Text(automationNoticeText(it),Modifier.testTag("automation-notice"))}
            state.failure?.let {Text(automationFailureText(it),Modifier.testTag("automation-error"),color=MaterialTheme.colorScheme.error)}
            Text(stringResource(R.string.automation_instructions,AutomationActivity.ACTION_RUN),style=MaterialTheme.typography.bodySmall)
        }
    }
    reviewed?.let {ticket->WorkspaceAlertDialog(onDismissRequest={if(reviewed==ticket) reviewed=null},title={Text(stringResource(R.string.automation_review_title))},text={
        Column(Modifier.verticalScroll(rememberScrollState()).testTag("automation-review-body"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(stringResource(R.string.automation_review_body));ticket.scopes.sortedBy {it.ordinal}.forEach {Text(it.title())};Text(stringResource(R.string.automation_review_help))
        }
    },confirmButton={TextButton(enabled=!state.busy,onClick={
        if(reviewed==ticket) {
            reviewed=null
            if(canApprove() && automationReviewKey(current)==ticket.key && current.installed==ticket.identity && choices()==ticket.scopes) approve(ticket.identity,ticket.scopes)
        }
    },modifier=Modifier.testTag("automation-allow")) {Text(stringResource(R.string.automation_allow))}},dismissButton={TextButton(onClick={if(reviewed==ticket) reviewed=null}) {Text(stringResource(R.string.automation_cancel))}})}
    revoke?.let {ticket->WorkspaceAlertDialog(onDismissRequest={if(revoke==ticket) revoke=null},title={Text(stringResource(R.string.automation_revoke_title))},text={Text(stringResource(R.string.automation_revoke_body),Modifier.verticalScroll(rememberScrollState()))},
        confirmButton={TextButton(enabled=!state.busy,onClick={if(revoke==ticket) {revoke=null;if(canDisable() && automationReviewKey(current)==ticket.key) disable()}},modifier=Modifier.testTag("automation-revoke")) {Text(stringResource(R.string.automation_revoke_confirm))}},dismissButton={TextButton(onClick={if(revoke==ticket) revoke=null}) {Text(stringResource(R.string.automation_cancel))}})}
    discard?.let {ticket->WorkspaceAlertDialog(onDismissRequest={if(discard==ticket) discard=null},title={Text(stringResource(R.string.automation_discard_title))},text={Text(stringResource(R.string.automation_discard_body))},
        confirmButton={TextButton(enabled=!state.busy,onClick={if(discard==ticket) {discard=null;if(foreground() && !current.busy && automationReviewKey(current)==ticket.key && choices()==ticket.scopes) back()}},modifier=Modifier.testTag("automation-discard")) {Text(stringResource(R.string.automation_discard_confirm))}},dismissButton={TextButton(onClick={if(discard==ticket) discard=null}) {Text(stringResource(R.string.automation_cancel))}})}
}
