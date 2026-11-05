package com.bontecou.whereday

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material.icons.automirrored.outlined.HelpOutline
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bontecou.whereday.data.AppSettings
import com.bontecou.whereday.platform.*
import java.text.NumberFormat
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

internal data class SettingsActions(val update:((AppSettings)->AppSettings)->Unit,val folder:()->Unit,val permissions:()->Unit,
    val navigate:(SettingsDestination)->Unit,val clear:()->Unit,val dismissClear:(Long)->Unit,val dismissFeedback:()->Unit)
private data class ClearConfirmation(val id:Long,val attempt:Long)

@Composable internal fun SettingsContent(settings:AppSettings,placeCount:Int,count:Long,commands:SettingsActionState,clear:JournalClearState,
    actions:SettingsActions,scroll:LazyListState=rememberLazyListState()) {
    val resources=LocalContext.current.resources
    val number=NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0])
    val busy=clear.busy
    val editable=!busy && !commands.busy
    val currentClear by rememberUpdatedState(clear)
    val currentCommands by rememberUpdatedState(commands)
    var confirmation by remember {mutableStateOf<ClearConfirmation?>(null)}
    val sequence=remember {AtomicLong()}
    val active=remember {AtomicBoolean(true)}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP) confirmation=null}
        lifecycle.addObserver(observer)
        onDispose {active.set(false);lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(clear.attempt) {confirmation=null}
    fun foreground()=active.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    fun edit(change:(AppSettings)->AppSettings) {if(foreground() && !currentClear.busy && !currentCommands.busy) actions.update(change)}
    fun navigate(value:SettingsDestination) {if(foreground() && !currentClear.busy) actions.navigate(value)}
    val never=stringResource(R.string.settings_never);val none=stringResource(R.string.settings_none)
    val themes=mapOf("system" to stringResource(R.string.settings_system),"light" to stringResource(R.string.settings_light),"dark" to stringResource(R.string.settings_dark))
    val meters=listOf(5,10,25,50,100,200).associate {it.toString() to stringResource(R.string.settings_meters,number.format(it))}
    val hours=listOf(0,1,2,4,8,12).associate {it.toString() to if(it==0) never else stringResource(R.string.settings_hours,number.format(it))}
    val minutes=listOf(0,5,15,30,60,120,240).associate {it.toString() to if(it==0) none else stringResource(R.string.settings_minutes,number.format(it))}
    val geocoding=stringResource(R.string.settings_allow_geocoding)
    val card=Modifier.padding(horizontal=16.dp)
    LazyColumn(Modifier.fillMaxSize().background(wd.surface).semantics {testTagsAsResourceId=true}.testTag("settings-screen"),state=scroll,contentPadding=PaddingValues(bottom=32.dp)) {
        item("header") {WdPageHeader(stringResource(R.string.shell_settings),Modifier.padding(bottom=4.dp),eyebrow=stringResource(R.string.redesign_settings_eyebrow))}
        item("recording") {WdSectionHeader(stringResource(R.string.settings_recording))}
        item("recording-card") {WdCard(card) {
            SettingsChoiceRow("settings-distance") {Choice(stringResource(R.string.settings_distance),settings.distanceFilter.toString(),meters.keys.toList(),editable,labelFor={meters[it] ?: resources.getString(R.string.settings_meters,number.format(it.toInt()))}) {key->edit {it.copy(distanceFilter=key.toInt())}}}
            SettingsChoiceRow("settings-stop") {Choice(stringResource(R.string.settings_stop_after),settings.stopAfterHours.toString(),hours.keys.toList(),editable,labelFor={hours[it] ?: resources.getString(R.string.settings_hours,number.format(it.toInt()))}) {key->edit {it.copy(stopAfterHours=key.toInt())}}}
            WdNavRow(stringResource(R.string.settings_permissions),icon=Icons.Outlined.LocationOn,divider=false,enabled=!busy,onClick={if(foreground() && !currentClear.busy) actions.permissions()})
        }}
        item("recording-help") {WdSectionFooter(stringResource(R.string.settings_recording_help))}
        item("inference") {WdSectionHeader(stringResource(R.string.settings_inferred))}
        item("inference-card") {WdCard(card) {
            SettingsToggle(stringResource(R.string.settings_include_inferred),settings.inferredEnabled,editable,Modifier.testTag("settings-inferred-enabled"),divider=settings.inferredEnabled) {value->edit {it.copy(inferredEnabled=value)}}
            if(settings.inferredEnabled) {
                SettingsChoiceRow("settings-inferred-gap") {Choice(stringResource(R.string.settings_gap),settings.inferredGapMinutes.toString(),listOf("15","30","60","120","240"),editable,labelFor={minutes[it] ?: resources.getString(R.string.settings_minutes,number.format(it.toInt()))}) {key->edit {it.copy(inferredGapMinutes=key.toInt())}}}
                SettingsChoiceRow("settings-inferred-duration") {Choice(stringResource(R.string.settings_minimum_duration),settings.inferredMinimumMinutes.toString(),listOf("0","5","15","30","60"),editable,labelFor={minutes[it] ?: resources.getString(R.string.settings_minutes,number.format(it.toInt()))}) {key->edit {it.copy(inferredMinimumMinutes=key.toInt())}}}
                SettingsChoiceRow("settings-inferred-points",divider=false) {Choice(stringResource(R.string.settings_minimum_points),settings.inferredMinimumPoints.toString(),listOf("1","2","3","5","10"),editable,labelFor={number.format(it.toInt())}) {key->edit {it.copy(inferredMinimumPoints=key.toInt())}}}
            }
        }}
        item("appearance") {WdSectionHeader(stringResource(R.string.settings_appearance))}
        item("appearance-card") {WdCard(card) {
            SettingsChoiceRow("settings-theme") {Choice(stringResource(R.string.settings_theme),settings.appearance,themes.keys.toList(),editable,labelFor={themes[it] ?: themes.getValue("system")}) {key->edit {it.copy(appearance=key)}}}
            SettingsToggle(stringResource(R.string.settings_metric),settings.metric,editable,Modifier.testTag("settings-metric"),divider=false) {value->edit {it.copy(metric=value)}}
        }}
        item("places") {WdSectionHeader(stringResource(R.string.settings_places))}
        item("places-card") {WdCard(card) {
            WdNavRow(stringResource(R.string.settings_country_days),icon=Icons.Outlined.Public,enabled=!busy,onClick={navigate(SettingsDestination.countries)})
            WdNavRow(stringResource(R.string.settings_photos),icon=Icons.Outlined.PhotoLibrary,enabled=!busy,onClick={navigate(SettingsDestination.photos)})
            WdNavRow(stringResource(R.string.settings_manage_places),icon=Icons.Outlined.Place,enabled=!busy,onClick={navigate(SettingsDestination.places)})
            WdNavRow(stringResource(R.string.settings_add_place),icon=Icons.Outlined.AddLocationAlt,divider=false,enabled=!busy,onClick={navigate(SettingsDestination.addPlace)})
        }}
        item("place-count") {WdSectionFooter(pluralStringResource(R.plurals.settings_place_count,placeCount,number.format(placeCount)),Modifier.testTag("settings-place-count"))}
        item("privacy") {WdSectionHeader(stringResource(R.string.settings_privacy))}
        item("privacy-card") {WdCard(card) {
            WdNavRow(stringResource(R.string.usage_title),enabled=!busy,onClick={navigate(SettingsDestination.usage)})
            WdNavRow(stringResource(R.string.road_settings_title),enabled=!busy,onClick={navigate(SettingsDestination.roads)})
            WdNavRow(stringResource(R.string.visits_title),enabled=!busy,onClick={navigate(SettingsDestination.visitsOnly)})
            SettingsToggle(stringResource(R.string.settings_geocoding),settings.networkGeocoding,editable,Modifier.testTag("settings-geocoding").semantics {contentDescription=geocoding},divider=false) {value->edit {it.copy(networkGeocoding=value)}}
        }}
        item("geocoding-help") {WdSectionFooter(stringResource(R.string.network_geocoding_disclosure))}
        item("data") {WdSectionHeader(stringResource(R.string.redesign_settings_data))}
        item("data-card") {WdCard(card) {
            WdNavRow(stringResource(if(settings.exportFolder==null) R.string.settings_choose_folder else R.string.settings_change_folder),icon=Icons.Outlined.Folder,enabled=editable,onClick={if(foreground() && !currentClear.busy && !currentCommands.busy) actions.folder()})
            WdNavRow(stringResource(R.string.settings_automatic_exports),icon=Icons.Outlined.Schedule,enabled=!busy,onClick={navigate(SettingsDestination.exports)})
            WdNavRow(stringResource(R.string.settings_webhooks),icon=Icons.Outlined.Webhook,enabled=!busy,onClick={navigate(SettingsDestination.webhooks)})
            WdNavRow(stringResource(R.string.settings_automation),icon=Icons.Outlined.SmartToy,divider=false,enabled=!busy,onClick={navigate(SettingsDestination.automation)})
        }}
        item("point-count") {WdSectionFooter(pluralStringResource(R.plurals.settings_point_count,count.coerceIn(0,Int.MAX_VALUE.toLong()).toInt(),number.format(count)),Modifier.testTag("settings-point-count"))}
        if(commands.busy) item("saving") {SettingsProgress(stringResource(R.string.settings_saving),card.padding(top=16.dp))}
        if(commands.failure!=null || commands.folderSaved) item("settings-feedback") {SettingsNotice(card.padding(top=16.dp),warning=commands.failure!=null) {
            commands.failure?.let {failure->Text(stringResource(when(failure) {SettingsFailure.preferences->R.string.settings_preferences_failed;SettingsFailure.folder->R.string.settings_folder_failed;SettingsFailure.folderPicker->R.string.settings_folder_picker_failed;SettingsFailure.permissions->R.string.settings_permissions_failed}))}
            if(commands.folderSaved) Text(stringResource(R.string.settings_folder_saved))
            SettingsTextAction(stringResource(R.string.settings_dismiss),actions.dismissFeedback)
        }}
        item("about") {WdSectionHeader(stringResource(R.string.redesign_settings_about))}
        item("about-card") {WdCard(card) {
            WdNavRow(stringResource(R.string.support_title),icon=Icons.AutoMirrored.Outlined.HelpOutline,enabled=!busy,onClick={navigate(SettingsDestination.support)})
            WdNavRow(stringResource(R.string.settings_licenses),icon=Icons.Outlined.Map,enabled=!busy,onClick={navigate(SettingsDestination.licenses)})
            WdNavRow(stringResource(R.string.software_notices_title),Modifier.testTag("settings-software-notices"),icon=Icons.Outlined.Description,enabled=!busy,onClick={navigate(SettingsDestination.software)})
            WdRow(divider=false,enabled=editable,onClick={edit {it.copy(onboarded=false)}}) {
                Icon(Icons.Outlined.Replay,null,Modifier.size(20.dp),tint=wd.textMuted)
                Text(stringResource(R.string.settings_replay_onboarding),Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge,color=if(editable) wd.textPrimary else wd.textMuted)
            }
        }}
        if(clear.phase!=JournalClearPhase.idle) item("clear-status") {SettingsNotice(card.padding(top=24.dp),warning=clear.phase==JournalClearPhase.failed,icon=if(busy) null else if(clear.phase==JournalClearPhase.complete) Icons.Outlined.CheckCircle else Icons.Outlined.ErrorOutline) {
            if(busy) {SettingsProgress(stringResource(R.string.settings_clearing));Text(stringResource(R.string.settings_clearing_help),style=WdType.caption2,color=wd.textMuted)}
            else {Text(stringResource(if(clear.phase==JournalClearPhase.complete) R.string.settings_cleared else R.string.settings_clear_failed),Modifier.testTag("settings-clear-result"));SettingsTextAction(stringResource(R.string.settings_dismiss),{actions.dismissClear(clear.attempt)})}
        }}
        item("clear") {WdCard(card.padding(top=24.dp)) {
            WdRow(Modifier.testTag("settings-clear"),divider=false,enabled=!busy,onClick={if(foreground() && !currentClear.busy) confirmation=ClearConfirmation(sequence.incrementAndGet(),currentClear.attempt)}) {
                Icon(Icons.Outlined.DeleteForever,null,Modifier.size(20.dp),tint=if(busy) wd.textMuted else wd.inkRust)
                Text(stringResource(R.string.settings_clear),Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.Medium),color=if(busy) wd.textMuted else wd.inkRust)
            }
        }}
    }
    confirmation?.let {ticket->WorkspaceAlertDialog(onDismissRequest={if(confirmation==ticket) confirmation=null},title={Text(stringResource(R.string.settings_clear_title))},text={Text(stringResource(R.string.settings_clear_body),Modifier.verticalScroll(rememberScrollState()).testTag("settings-clear-disclosure"))},
        confirmButton={TextButton(enabled=!busy,onClick={
            if(confirmation==ticket && foreground() && !currentClear.busy && currentClear.attempt==ticket.attempt) {confirmation=null;actions.clear()}
        },modifier=Modifier.testTag("settings-clear-confirm"),colors=ButtonDefaults.textButtonColors(contentColor=wd.inkRust)) {Text(stringResource(R.string.settings_clear_confirm))}},
        dismissButton={TextButton(onClick={if(confirmation==ticket) confirmation=null},modifier=Modifier.testTag("settings-clear-cancel")) {Text(stringResource(R.string.settings_cancel))}})}
}

/** A Choice inside a settings card: tagged for tests, padded like a TERow, with a hairline beneath. */
@Composable private fun SettingsChoiceRow(tag:String,divider:Boolean=true,content:@Composable ()->Unit) {
    Column(Modifier.fillMaxWidth()) {
        Box(Modifier.fillMaxWidth().testTag(tag).heightIn(min=52.dp).padding(horizontal=16.dp,vertical=8.dp),contentAlignment=Alignment.CenterStart) {content()}
        if(divider) WdDivider(Modifier.padding(start=16.dp))
    }
}

/** TEToggleStyle row with toggleable semantics, so the whole row reports on/off and enabled state. */
@Composable internal fun SettingsToggle(label:String,value:Boolean,enabled:Boolean,modifier:Modifier=Modifier,detail:String?=null,divider:Boolean=true,change:(Boolean)->Unit) {
    WdRow(modifier.toggleable(value=value,enabled=enabled,role=Role.Switch,onValueChange=change),divider) {
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
            Text(label,style=MaterialTheme.typography.bodyLarge,color=if(enabled) wd.textPrimary else wd.textMuted)
            if(detail!=null) Text(detail,style=WdType.caption2,color=wd.textMuted)
        }
        Switch(value,null,enabled=enabled,colors=wdSwitchColors())
    }
}
