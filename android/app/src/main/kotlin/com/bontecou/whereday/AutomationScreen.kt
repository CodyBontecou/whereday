package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.OpenInNew
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
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
    val card=Modifier.padding(horizontal=16.dp)
    Column(Modifier.fillMaxSize().background(wd.surface).verticalScroll(rememberScrollState()).semantics {testTagsAsResourceId=true}.testTag("automation-screen").padding(bottom=32.dp)) {
        SettingsBackHeader(stringResource(R.string.automation_title),stringResource(R.string.automation_back),{leave()})
        Text(stringResource(R.string.automation_intro),Modifier.padding(horizontal=20.dp),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
        if(!state.loaded) SettingsProgress(stringResource(R.string.automation_loading),card.padding(top=20.dp))
        else {
            WdCard(card.padding(top=16.dp)) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                val connected=!state.failure.accessUnavailable() && approval!=null && installed==approval.identity
                Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Icon(if(connected) Icons.Outlined.Link else if(approval==null && !state.failure.accessUnavailable()) Icons.Outlined.LinkOff else Icons.Outlined.ErrorOutline,null,Modifier.size(20.dp),
                        tint=if(connected) wd.accent else if(approval==null && !state.failure.accessUnavailable()) wd.textMuted else wd.inkRust)
                    Text(stringResource(when {
                        state.failure.accessUnavailable()->R.string.automation_status_unknown
                        approval==null->R.string.automation_status_disabled
                        installed!=approval.identity->R.string.automation_status_changed
                        else->R.string.automation_status_connected
                    }),Modifier.weight(1f).testTag("automation-status"),style=WdType.title3,color=wd.textPrimary)
                }
                if(installed==null) {
                    Text(stringResource(R.string.automation_not_installed),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
                    WdButton(stringResource(R.string.automation_install),{if(interactive() && current.installed==null) install()},enabled=!state.busy,trailingIcon=Icons.AutoMirrored.Outlined.OpenInNew)
                } else {
                    Text(stringResource(R.string.automation_package,installed.packageName),style=WdType.caption,color=wd.textPrimary)
                    Text(stringResource(R.string.automation_installation_help),style=WdType.caption2,color=wd.textMuted)
                }
                SettingsTextAction(stringResource(R.string.automation_reload),{if(interactive()) reload()},enabled=!state.busy)
            }}
            WdSectionHeader(stringResource(R.string.automation_allowed))
            WdCard(card) {
                AutomationScope.entries.forEachIndexed {index,scope->
                    val rowEnabled=installed!=null && !state.busy
                    WdRow(Modifier.toggleable(scope in selected,enabled=rowEnabled,role=Role.Checkbox) {value->
                        if(interactive() && current.installed!=null && automationReviewKey(current)==key) {
                            val now=choices();draft=AutomationChoiceDraft(automationBinding(current),if(value) now+scope else now-scope)
                        }
                    }.testTag("automation-scope-${scope.name}"),divider=index<AutomationScope.entries.lastIndex) {
                        Checkbox(checked=scope in selected,onCheckedChange=null,enabled=rowEnabled,
                            colors=CheckboxDefaults.colors(checkedColor=wd.accent,uncheckedColor=wd.textMuted,checkmarkColor=wd.surface))
                        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                            Text(scope.title(),style=MaterialTheme.typography.bodyLarge,color=if(rowEnabled) wd.textPrimary else wd.textMuted)
                            Text(scope.detail(),style=WdType.caption2,color=wd.textMuted)
                        }
                    }
                }
            }
            if(selected!=baseline) Text(stringResource(R.string.automation_unsaved),Modifier.padding(start=20.dp,end=20.dp,top=8.dp),style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=wd.inkRust)
            WdButton(stringResource(R.string.automation_save),{if(canApprove()) current.installed?.let {reviewed=AutomationReview(sequence.incrementAndGet(),automationReviewKey(current),it.snapshot(),choices().toSet())}},
                card.padding(top=16.dp).testTag("automation-save"),emphasis=WdEmphasis.primary,
                enabled=installed!=null && selected.isNotEmpty() && !state.busy && (selected!=baseline || approval?.identity!=installed || state.failure.accessUnavailable()))
            WdSectionFooter(stringResource(R.string.automation_results_help))
            if(approval!=null || state.failure.accessUnavailable()) WdButton(stringResource(R.string.automation_disable),{if(canDisable()) revoke=AutomationDialog(sequence.incrementAndGet(),automationReviewKey(current),choices().toSet())},
                card.padding(top=16.dp),emphasis=WdEmphasis.destructive,enabled=!state.busy)
            if(state.busy) SettingsProgress(stringResource(R.string.automation_saving),card.padding(top=16.dp))
            state.notice?.let {SettingsNotice(card.padding(top=12.dp),icon=Icons.Outlined.CheckCircle) {Text(automationNoticeText(it),Modifier.testTag("automation-notice"))}}
            state.failure?.let {SettingsNotice(card.padding(top=12.dp),warning=true) {Text(automationFailureText(it),Modifier.testTag("automation-error"))}}
            WdSectionHeader(stringResource(R.string.redesign_settings_how_to))
            WdCard(card) {Text(stringResource(R.string.automation_instructions,AutomationActivity.ACTION_RUN),Modifier.padding(16.dp),style=WdType.caption,color=wd.textPrimary)}
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
        confirmButton={TextButton(enabled=!state.busy,onClick={if(revoke==ticket) {revoke=null;if(canDisable() && automationReviewKey(current)==ticket.key) disable()}},modifier=Modifier.testTag("automation-revoke"),colors=ButtonDefaults.textButtonColors(contentColor=wd.inkRust)) {Text(stringResource(R.string.automation_revoke_confirm))}},dismissButton={TextButton(onClick={if(revoke==ticket) revoke=null}) {Text(stringResource(R.string.automation_cancel))}})}
    discard?.let {ticket->WorkspaceAlertDialog(onDismissRequest={if(discard==ticket) discard=null},title={Text(stringResource(R.string.automation_discard_title))},text={Text(stringResource(R.string.automation_discard_body))},
        confirmButton={TextButton(enabled=!state.busy,onClick={if(discard==ticket) {discard=null;if(foreground() && !current.busy && automationReviewKey(current)==ticket.key && choices()==ticket.scopes) back()}},modifier=Modifier.testTag("automation-discard")) {Text(stringResource(R.string.automation_discard_confirm))}},dismissButton={TextButton(onClick={if(discard==ticket) discard=null}) {Text(stringResource(R.string.automation_cancel))}})}
}
