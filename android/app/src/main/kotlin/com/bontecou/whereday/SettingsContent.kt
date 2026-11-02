package com.bontecou.whereday

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.*
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
    LazyColumn(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("settings-screen"),state=scroll,contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        item("recording") {Text(stringResource(R.string.settings_recording),style=MaterialTheme.typography.titleLarge)}
        item("distance") {Box(Modifier.testTag("settings-distance")) {Choice(stringResource(R.string.settings_distance),settings.distanceFilter.toString(),meters.keys.toList(),editable,labelFor={meters[it] ?: resources.getString(R.string.settings_meters,number.format(it.toInt()))}) {key->edit {it.copy(distanceFilter=key.toInt())}}}}
        item("stop") {Box(Modifier.testTag("settings-stop")) {Choice(stringResource(R.string.settings_stop_after),settings.stopAfterHours.toString(),hours.keys.toList(),editable,labelFor={hours[it] ?: resources.getString(R.string.settings_hours,number.format(it.toInt()))}) {key->edit {it.copy(stopAfterHours=key.toInt())}}}}
        item("recording-help") {Text(stringResource(R.string.settings_recording_help),style=MaterialTheme.typography.bodySmall)}
        item("permissions") {TextButton(enabled=!busy,onClick={if(foreground() && !currentClear.busy) actions.permissions()}) {Text(stringResource(R.string.settings_permissions))}}
        item("divider-inference") {HorizontalDivider()}
        item("inference") {Text(stringResource(R.string.settings_inferred),style=MaterialTheme.typography.titleLarge)}
        item("inferred-enabled") {ExportToggle(stringResource(R.string.settings_include_inferred),settings.inferredEnabled,editable,Modifier.testTag("settings-inferred-enabled")) {value->edit {it.copy(inferredEnabled=value)}}}
        if(settings.inferredEnabled) {
            item("inferred-gap") {Box(Modifier.testTag("settings-inferred-gap")) {Choice(stringResource(R.string.settings_gap),settings.inferredGapMinutes.toString(),listOf("15","30","60","120","240"),editable,labelFor={minutes[it] ?: resources.getString(R.string.settings_minutes,number.format(it.toInt()))}) {key->edit {it.copy(inferredGapMinutes=key.toInt())}}}}
            item("inferred-duration") {Box(Modifier.testTag("settings-inferred-duration")) {Choice(stringResource(R.string.settings_minimum_duration),settings.inferredMinimumMinutes.toString(),listOf("0","5","15","30","60"),editable,labelFor={minutes[it] ?: resources.getString(R.string.settings_minutes,number.format(it.toInt()))}) {key->edit {it.copy(inferredMinimumMinutes=key.toInt())}}}}
            item("inferred-points") {Box(Modifier.testTag("settings-inferred-points")) {Choice(stringResource(R.string.settings_minimum_points),settings.inferredMinimumPoints.toString(),listOf("1","2","3","5","10"),editable,labelFor={number.format(it.toInt())}) {key->edit {it.copy(inferredMinimumPoints=key.toInt())}}}}
        }
        item("divider-appearance") {HorizontalDivider()}
        item("appearance") {Text(stringResource(R.string.settings_appearance),style=MaterialTheme.typography.titleLarge)}
        item("theme") {Box(Modifier.testTag("settings-theme")) {Choice(stringResource(R.string.settings_theme),settings.appearance,themes.keys.toList(),editable,labelFor={themes[it] ?: themes.getValue("system")}) {key->edit {it.copy(appearance=key)}}}}
        item("metric") {ExportToggle(stringResource(R.string.settings_metric),settings.metric,editable,Modifier.testTag("settings-metric")) {value->edit {it.copy(metric=value)}}}
        item("divider-places") {HorizontalDivider()}
        item("countries") {OutlinedButton(enabled=!busy,onClick={navigate(SettingsDestination.countries)}) {Text(stringResource(R.string.settings_country_days))}}
        item("photos") {OutlinedButton(enabled=!busy,onClick={navigate(SettingsDestination.photos)}) {Text(stringResource(R.string.settings_photos))}}
        item("places") {Text(stringResource(R.string.settings_places),style=MaterialTheme.typography.titleLarge)}
        item("place-count") {Text(pluralStringResource(R.plurals.settings_place_count,placeCount,number.format(placeCount)),Modifier.testTag("settings-place-count"),style=MaterialTheme.typography.bodySmall)}
        item("manage-places") {OutlinedButton(enabled=!busy,onClick={navigate(SettingsDestination.places)}) {Text(stringResource(R.string.settings_manage_places))}}
        item("add-place") {OutlinedButton(enabled=!busy,onClick={navigate(SettingsDestination.addPlace)}) {Text(stringResource(R.string.settings_add_place))}}
        item("divider-privacy") {HorizontalDivider()}
        item("privacy") {Text(stringResource(R.string.settings_privacy),style=MaterialTheme.typography.titleLarge)}
        item("usage") {OutlinedButton(enabled=!busy,onClick={navigate(SettingsDestination.usage)}) {Text(stringResource(R.string.usage_title))}}
        item("roads") {OutlinedButton(enabled=!busy,onClick={navigate(SettingsDestination.roads)}) {Text(stringResource(R.string.road_settings_title))}}
        item("visits-only") {OutlinedButton(enabled=!busy,onClick={navigate(SettingsDestination.visitsOnly)}) {Text(stringResource(R.string.visits_title))}}
        item("geocoding") {ExportToggle(stringResource(R.string.settings_geocoding),settings.networkGeocoding,editable,Modifier.testTag("settings-geocoding").semantics {contentDescription=geocoding}) {value->edit {it.copy(networkGeocoding=value)}}}
        item("geocoding-help") {Text(stringResource(R.string.network_geocoding_disclosure),style=MaterialTheme.typography.bodySmall)}
        item("point-count") {Text(pluralStringResource(R.plurals.settings_point_count,count.coerceIn(0,Int.MAX_VALUE.toLong()).toInt(),number.format(count)),Modifier.testTag("settings-point-count"))}
        item("folder") {OutlinedButton(enabled=editable,onClick={if(foreground() && !currentClear.busy && !currentCommands.busy) actions.folder()}) {Text(stringResource(if(settings.exportFolder==null) R.string.settings_choose_folder else R.string.settings_change_folder))}}
        item("exports") {OutlinedButton(enabled=!busy,onClick={navigate(SettingsDestination.exports)}) {Text(stringResource(R.string.settings_automatic_exports))}}
        item("webhooks") {OutlinedButton(enabled=!busy,onClick={navigate(SettingsDestination.webhooks)}) {Text(stringResource(R.string.settings_webhooks))}}
        item("automation") {OutlinedButton(enabled=!busy,onClick={navigate(SettingsDestination.automation)}) {Text(stringResource(R.string.settings_automation))}}
        if(commands.busy) item("saving") {LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(R.string.settings_saving))}
        commands.failure?.let {failure->item("settings-failure") {Text(stringResource(when(failure) {SettingsFailure.preferences->R.string.settings_preferences_failed;SettingsFailure.folder->R.string.settings_folder_failed;SettingsFailure.folderPicker->R.string.settings_folder_picker_failed;SettingsFailure.permissions->R.string.settings_permissions_failed}),color=MaterialTheme.colorScheme.error)}}
        if(commands.folderSaved) item("folder-saved") {Text(stringResource(R.string.settings_folder_saved))}
        if(commands.failure!=null || commands.folderSaved) item("dismiss-settings-feedback") {TextButton(onClick=actions.dismissFeedback) {Text(stringResource(R.string.settings_dismiss))}}
        if(clear.phase!=JournalClearPhase.idle) item("clear-status") {
            if(busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(R.string.settings_clearing));Text(stringResource(R.string.settings_clearing_help),style=MaterialTheme.typography.bodySmall)}
            else {Text(stringResource(if(clear.phase==JournalClearPhase.complete) R.string.settings_cleared else R.string.settings_clear_failed),Modifier.testTag("settings-clear-result"));TextButton(onClick={actions.dismissClear(clear.attempt)}) {Text(stringResource(R.string.settings_dismiss))}}
        }
        item("clear") {TextButton(enabled=!busy,onClick={if(foreground() && !currentClear.busy) confirmation=ClearConfirmation(sequence.incrementAndGet(),currentClear.attempt)},modifier=Modifier.testTag("settings-clear")) {Text(stringResource(R.string.settings_clear),color=MaterialTheme.colorScheme.error)}}
        item("onboarding") {TextButton(enabled=editable,onClick={edit {it.copy(onboarded=false)}}) {Text(stringResource(R.string.settings_replay_onboarding))}}
        item("licenses") {TextButton(enabled=!busy,onClick={navigate(SettingsDestination.licenses)}) {Text(stringResource(R.string.settings_licenses))}}
        item("software") {TextButton(enabled=!busy,onClick={navigate(SettingsDestination.software)},modifier=Modifier.testTag("settings-software-notices")) {Text(stringResource(R.string.software_notices_title))}}
        item("support") {OutlinedButton(enabled=!busy,onClick={navigate(SettingsDestination.support)}) {Text(stringResource(R.string.support_title))}}
    }
    confirmation?.let {ticket->WorkspaceAlertDialog(onDismissRequest={if(confirmation==ticket) confirmation=null},title={Text(stringResource(R.string.settings_clear_title))},text={Text(stringResource(R.string.settings_clear_body),Modifier.verticalScroll(rememberScrollState()).testTag("settings-clear-disclosure"))},
        confirmButton={TextButton(enabled=!busy,onClick={
            if(confirmation==ticket && foreground() && !currentClear.busy && currentClear.attempt==ticket.attempt) {confirmation=null;actions.clear()}
        },modifier=Modifier.testTag("settings-clear-confirm")) {Text(stringResource(R.string.settings_clear_confirm))}},
        dismissButton={TextButton(onClick={if(confirmation==ticket) confirmation=null},modifier=Modifier.testTag("settings-clear-cancel")) {Text(stringResource(R.string.settings_cancel))}})}
}
