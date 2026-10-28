package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.platform.*

private fun AutomationScope.title()=when(this) {
    AutomationScope.summary->"Read statistics"
    AutomationScope.visits->"Read visits"
    AutomationScope.outings->"Read outings"
    AutomationScope.renameVisits->"Rename visits"
    AutomationScope.renameOutings->"Rename outings"
    AutomationScope.recording->"Control recording"
    AutomationScope.exports->"Create export files"
}
private fun AutomationScope.detail()=when(this) {
    AutomationScope.summary->"Point and visit counts, distance and current recording duration."
    AutomationScope.visits->"Visit names, addresses and arrival/departure times."
    AutomationScope.outings->"Recorded outing names and start/end times."
    AutomationScope.renameVisits->"Change visit names and read the updated name, address and times."
    AutomationScope.renameOutings->"Change outing names and read their name and times."
    AutomationScope.recording->"Start or stop recording while Whereday is open. Starting requires existing location access."
    AutomationScope.exports->"Complete files using the requested filters. Verified lifetime export access is also required."
}

@Composable fun AutomationScreen(graph:AppGraph,back:()->Unit) {
    val state by graph.automationController.state.collectAsStateWithLifecycle()
    val context=LocalContext.current
    LaunchedEffect(Unit) {graph.automationController.reload()}
    AutomationForm(state,back,graph.automationController::approve,graph.automationController::disable,graph.automationController::reload) {
        context.startActivity(android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("https://play.google.com/store/apps/details?id=com.llamalab.automate")))
    }
}

@Composable internal fun AutomationForm(state:AutomationUiState,back:()->Unit,approve:(AutomationIdentity,Set<AutomationScope>)->Unit,
    disable:()->Unit,reload:()->Unit,install:()->Unit) {
    val installed=state.installed;val approval=state.approval
    val current by rememberUpdatedState(state)
    val baseline=approval?.takeIf {it.identity==installed}?.scopes.orEmpty()
    var selected by remember(approval?.revision,installed,state.message=="Automation access disabled.") {mutableStateOf(baseline)}
    var discard by remember {mutableStateOf(false)}
    var reviewed by remember {mutableStateOf<Pair<AutomationIdentity,Set<AutomationScope>>?>(null)}
    var revoke by remember {mutableStateOf(false)}
    val dirty=selected!=baseline
    fun leave() {if(current.busy || selected==baseline) back() else discard=true}
    BackHandler {leave()}
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).semantics {testTagsAsResourceId=true}.testTag("automation-screen").padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        TextButton(onClick={leave()}) {Text("Back to settings")}
        Text("Automation",style=MaterialTheme.typography.headlineMedium)
        Text("Connect Automate to run only the Whereday actions you approve. Opening this screen does not grant access.")
        if(!state.loaded) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Loading automation access…")}
        else {
            Text(when {
                state.error!=null && approval==null->"Access status unavailable"
                approval==null->"Access disabled"
                installed!=approval.identity->"App changed · approval required"
                else->"Connected to Automate"
            },style=MaterialTheme.typography.titleMedium)
            if(installed==null) {
                Text("Automate is not installed. Install it, then return here to choose its actions.")
                OutlinedButton(onClick=install) {Text("Get Automate")}
            } else {
                Text("Automate · ${installed.packageName}",style=MaterialTheme.typography.bodySmall)
                Text("Approval applies to this installation. Reinstalling Automate or changing its signer requires approval again.",style=MaterialTheme.typography.bodySmall)
            }
            TextButton(enabled=!state.busy,onClick=reload) {Text("Check installed app")}
            HorizontalDivider()
            Text("Allowed actions",style=MaterialTheme.typography.titleLarge)
            AutomationScope.entries.forEach {scope ->
                Row(Modifier.fillMaxWidth().toggleable(scope in selected,enabled=installed!=null && !state.busy,role=Role.Checkbox) {
                    selected=if(it) selected+scope else selected-scope
                }.testTag("automation-scope-${scope.name}").padding(vertical=8.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    Checkbox(checked=scope in selected,onCheckedChange=null)
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                        Text(scope.title(),style=MaterialTheme.typography.titleMedium)
                        Text(scope.detail(),style=MaterialTheme.typography.bodySmall)
                    }
                }
            }
            if(dirty) Text("Unsaved changes",style=MaterialTheme.typography.labelLarge)
            Button(enabled=installed!=null && selected.isNotEmpty() && !state.busy && (dirty || approval?.identity!=installed),
                onClick={installed?.let {reviewed=it to selected.toSet()}}) {Text("Save automation access")}
            Text("Result files allow new reads for ten minutes. Copied data and already-open files cannot be withdrawn.",style=MaterialTheme.typography.bodySmall)
            if(approval!=null || state.error!=null) TextButton(enabled=!state.busy,onClick={revoke=true}) {Text("Disable automation access",color=MaterialTheme.colorScheme.error)}
            if(state.busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Saving access… You can return to Settings while it finishes.")}
            state.message?.let {Text(it)}
            state.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            Text("In Automate, use App decision with action com.bontecou.whereday.automation.RUN and Whereday’s package. Results return as read-only file URIs. Start with statistics before enabling changes.",style=MaterialTheme.typography.bodySmall)
        }
    }
    reviewed?.let {(identity,scopes) ->AlertDialog(onDismissRequest={reviewed=null},title={Text("Allow these Automate actions?")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text("Automate can run these actions without another confirmation:")
            scopes.sortedBy {it.ordinal}.forEach {Text(it.title())}
            Text("Only approve actions you intend to use. You can disable access here later.")
        }
    },confirmButton={TextButton(onClick={reviewed=null;approve(identity,scopes)}) {Text("Allow selected actions")}},dismissButton={TextButton(onClick={reviewed=null}) {Text("Cancel")}})}
    if(revoke) AlertDialog(onDismissRequest={revoke=false},title={Text("Disable automation access?")},text={Text("Cancel pending commands and remove temporary results. Copied data, already-open files and an already-started recording remain available.")},
        confirmButton={TextButton(onClick={revoke=false;disable()}) {Text("Disable access")}},dismissButton={TextButton(onClick={revoke=false}) {Text("Cancel")}})
    if(discard) AlertDialog(onDismissRequest={discard=false},title={Text("Discard unsaved automation choices?")},text={Text("Saved access stays as it is.")},
        confirmButton={TextButton(onClick={discard=false;back()}) {Text("Discard choices")}},dismissButton={TextButton(onClick={discard=false}) {Text("Cancel")}})
}
