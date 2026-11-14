package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.platform.*

@Composable internal fun UsageAnalyticsScreen(controller:UsageAnalyticsController,dismiss:()->Unit) {
    val status by controller.state.collectAsStateWithLifecycle()
    val control by controller.control.collectAsStateWithLifecycle()
    UsageAnalyticsForm(status,control,dismiss) {controller.setEnabled(it)}
}

@Composable internal fun UsageAnalyticsForm(status:UsageAnalyticsStatus,control:UsageControlState,dismiss:()->Unit,change:(Boolean)->Unit) {
    BackHandler(onBack=dismiss)
    val shareLabel=stringResource(R.string.usage_share)
    var confirming by rememberSaveable {mutableStateOf(false)}
    Column(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("usage-analytics-screen")
        .verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        TextButton(onClick=dismiss) {Text(stringResource(R.string.usage_back))}
        Text(stringResource(R.string.usage_title),style=MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.usage_optional))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(16.dp)) {
            Text(stringResource(R.string.usage_share),Modifier.weight(1f).padding(top=12.dp))
            Switch(checked=status.enabled,onCheckedChange={enabled->if(enabled) confirming=true else change(false)},
                enabled=status.loaded && !control.busy && (status.enabled || (status.available && status.error!=UsageAnalyticsError.storage)),
                modifier=Modifier.testTag("usage-analytics-switch").semantics {contentDescription=shareLabel})
        }
        if(!status.available) Text(stringResource(R.string.usage_unavailable))
        Text(stringResource(R.string.usage_collected),style=MaterialTheme.typography.titleMedium)
        Text(stringResource(R.string.usage_payload))
        Text(stringResource(R.string.usage_excluded))
        Text(stringResource(R.string.usage_provider))
        Text(stringResource(R.string.usage_revoke))
        if(control.busy) LinearProgressIndicator(Modifier.fillMaxWidth())
        if(status.error==UsageAnalyticsError.storage || control.error==UsageControlError.storage) {
            Text(stringResource(R.string.usage_storage_error),color=MaterialTheme.colorScheme.error)
            OutlinedButton(enabled=status.loaded && !control.busy,onClick={change(false)}) {Text(stringResource(R.string.usage_reset))}
        } else if(control.error==UsageControlError.scheduling) Text(stringResource(R.string.usage_scheduling_error),color=MaterialTheme.colorScheme.error)
        else if(control.error==UsageControlError.unavailable) Text(stringResource(R.string.usage_unavailable))
        else if(control.saved && !status.enabled) Text(stringResource(R.string.usage_disabled))
    }
    if(confirming) WorkspaceAlertDialog(onDismissRequest={confirming=false},title={Text(stringResource(R.string.usage_consent_title))},
        text={Text(stringResource(R.string.usage_consent_body))},
        confirmButton={TextButton(enabled=status.loaded && status.available && !control.busy && status.error!=UsageAnalyticsError.storage,onClick={confirming=false;change(true)}) {Text(stringResource(R.string.usage_allow))}},
        dismissButton={TextButton(onClick={confirming=false}) {Text(stringResource(R.string.usage_cancel))}})
}
