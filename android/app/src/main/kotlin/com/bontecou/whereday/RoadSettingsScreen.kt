package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.data.AppSettings
import com.bontecou.whereday.platform.*

@Composable internal fun RoadSettingsScreen(graph:AppGraph,back:()->Unit) {
    val session=remember(graph) {graph.roadConsentSession()}
    val settings by graph.preferences.settings.collectAsStateWithLifecycle(initialValue=null)
    val policy by graph.roadDisplay.state.collectAsStateWithLifecycle()
    val journal by graph.journalClear.state.collectAsStateWithLifecycle()
    RoadSettingsContent(session,settings,policy,journal,back)
}

@Composable internal fun RoadSettingsContent(session:RoadConsentSession,settings:AppSettings?,policy:RoadDisplayControllerState,
    journal:JournalClearState,back:()->Unit) {
    BackHandler(onBack=back)
    val state by session.state.collectAsStateWithLifecycle()
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val uriHandler=LocalUriHandler.current
    var linkError by remember {mutableStateOf(false)}
    DisposableEffect(session,lifecycle) {
        fun synchronize() {if(lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) session.foreground() else session.background()}
        val observer=LifecycleEventObserver {_,_->synchronize()}
        lifecycle.addObserver(observer);synchronize()
        onDispose {lifecycle.removeObserver(observer);session.close()}
    }
    val provider=session.provider
    val current=settings
    val editable=current!=null && !state.busy && !journal.blocked
    val available=provider!=null && policy.configured
    val enabled=current?.roadDisplayEnabled==true
    val label=stringResource(R.string.road_settings_enable)
    // Capture configuration-local wording before the platform dialog creates its own view context.
    val sharing=provider?.let {stringResource(R.string.road_settings_sharing,it.name)}
    val revoke=stringResource(R.string.road_settings_revoke)
    val consentTitle=stringResource(R.string.road_consent_title)
    val allow=stringResource(R.string.road_consent_allow)
    val cancel=stringResource(R.string.road_consent_cancel)
    val review=state.review?.takeIf {session.isCurrent(it)}
    Surface(Modifier.fillMaxSize()) {
    Column(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("road-settings-screen")
        .verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        TextButton(onClick=back) {Text(stringResource(R.string.road_settings_back))}
        Text(stringResource(R.string.road_settings_title),style=MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.road_settings_explanation))
        ExportToggle(label,enabled,editable && (enabled || available && current?.networkGeocoding==true),
            Modifier.testTag("road-consent-switch").semantics {contentDescription=label}) {value->
            if(value && current!=null) session.review(current) else session.disable()
        }
        if(!available) Text(stringResource(R.string.road_settings_unavailable))
        if(current?.networkGeocoding==false) Text(stringResource(R.string.road_settings_network))
        provider?.let {
            Text(checkNotNull(sharing))
            TextButton(onClick={try {uriHandler.openUri(it.privacyUrl);linkError=false} catch(_:Exception) {linkError=true}}) {Text(stringResource(R.string.road_settings_privacy))}
        }
        Text(revoke)
        if(state.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        state.failure?.let {Text(stringResource(if(it==RoadConsentFailure.storage) R.string.road_consent_storage_error else R.string.road_consent_stale),color=MaterialTheme.colorScheme.error)}
        if(state.failure!=null) OutlinedButton(enabled=editable,onClick={session.disable()}) {Text(stringResource(R.string.road_consent_turn_off))}
        if(state.savedEnabled==false && !enabled) Text(stringResource(R.string.road_consent_disabled))
        if(linkError) Text(stringResource(R.string.road_consent_link_error),color=MaterialTheme.colorScheme.error)
    }
    }
    if(review!=null && provider!=null) WorkspaceAlertDialog(onDismissRequest={session.dismiss(review.id)},title={Text(consentTitle)},
        text={Column(Modifier.verticalScroll(rememberScrollState()).testTag("road-consent-disclosure"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Text(checkNotNull(sharing));Text(revoke)
        }},confirmButton={TextButton(enabled=editable && available && current?.networkGeocoding==true,onClick={session.confirm(review.id)},modifier=Modifier.testTag("road-consent-confirm")) {Text(allow)}},
        dismissButton={TextButton(onClick={session.dismiss(review.id)}) {Text(cancel)}})
}
