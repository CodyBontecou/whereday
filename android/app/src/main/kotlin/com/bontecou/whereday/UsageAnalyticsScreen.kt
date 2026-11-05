package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.CheckCircle
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
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
    val card=Modifier.padding(horizontal=16.dp)
    val switchEnabled=status.loaded && !control.busy && (status.enabled || (status.available && status.error!=UsageAnalyticsError.storage))
    Column(Modifier.fillMaxSize().background(wd.surface).semantics {testTagsAsResourceId=true}.testTag("usage-analytics-screen")
        .verticalScroll(rememberScrollState()).padding(bottom=32.dp)) {
        SettingsBackHeader(stringResource(R.string.usage_title),stringResource(R.string.usage_back),dismiss)
        WdCard(card) {
            WdRow(divider=false) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.usage_share),style=MaterialTheme.typography.bodyLarge,color=if(switchEnabled) wd.textPrimary else wd.textMuted)
                }
                Switch(checked=status.enabled,onCheckedChange={enabled->if(enabled) confirming=true else change(false)},
                    enabled=switchEnabled,colors=wdSwitchColors(),
                    modifier=Modifier.testTag("usage-analytics-switch").semantics {contentDescription=shareLabel})
            }
        }
        WdSectionFooter(stringResource(R.string.usage_optional))
        if(!status.available) SettingsNotice(card.padding(top=12.dp)) {Text(stringResource(R.string.usage_unavailable))}
        if(control.busy) SettingsProgress(null,card.padding(top=12.dp))
        if(status.error==UsageAnalyticsError.storage || control.error==UsageControlError.storage) {
            SettingsNotice(card.padding(top=12.dp),warning=true) {Text(stringResource(R.string.usage_storage_error))}
            WdButton(stringResource(R.string.usage_reset),{change(false)},card.padding(top=12.dp),emphasis=WdEmphasis.destructive,enabled=status.loaded && !control.busy)
        } else if(control.error==UsageControlError.scheduling) SettingsNotice(card.padding(top=12.dp),warning=true) {Text(stringResource(R.string.usage_scheduling_error))}
        else if(control.error==UsageControlError.unavailable) SettingsNotice(card.padding(top=12.dp)) {Text(stringResource(R.string.usage_unavailable))}
        else if(control.saved && !status.enabled) SettingsNotice(card.padding(top=12.dp),icon=Icons.Outlined.CheckCircle) {Text(stringResource(R.string.usage_disabled))}
        Text(stringResource(R.string.usage_collected),Modifier.padding(start=20.dp,end=20.dp,top=24.dp,bottom=8.dp).semantics {heading()},style=WdType.title3,color=wd.textPrimary)
        WdCard(card) {
            listOf(R.string.usage_payload,R.string.usage_excluded,R.string.usage_provider,R.string.usage_revoke).forEachIndexed {index,text->
                WdRow(divider=index<3) {Text(stringResource(text),Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=if(index==0) wd.textPrimary else wd.textMuted)}
            }
        }
    }
    if(confirming) WorkspaceAlertDialog(onDismissRequest={confirming=false},title={Text(stringResource(R.string.usage_consent_title))},
        text={Text(stringResource(R.string.usage_consent_body))},
        confirmButton={TextButton(enabled=status.loaded && status.available && !control.busy && status.error!=UsageAnalyticsError.storage,onClick={confirming=false;change(true)}) {Text(stringResource(R.string.usage_allow))}},
        dismissButton={TextButton(onClick={confirming=false}) {Text(stringResource(R.string.usage_cancel))}})
}
