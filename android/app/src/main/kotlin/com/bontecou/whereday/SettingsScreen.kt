package com.bontecou.whereday

import android.content.Intent
import android.net.Uri
import android.provider.Settings
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll

@Composable fun SettingsScreen(graph: AppGraph, settings: AppSettings, places: List<SavedPlace>, count: Long) {
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var usage by rememberSaveable {mutableStateOf(false)}
    if(usage) {UsageAnalyticsScreen(graph.usageAnalytics) {usage=false};return}
    var support by rememberSaveable {mutableStateOf(false)}
    if(support) {SupportScreen(graph) {support=false};return}
    var countryDays by rememberSaveable { mutableStateOf(false) }
    if (countryDays) { CountryDaysScreen(graph) { countryDays=false }; return }
    var licenses by rememberSaveable { mutableStateOf(false) }
    if (licenses) { GeographicLicenses { licenses=false }; return }
    var automaticExports by rememberSaveable {mutableStateOf(false)}
    if(automaticExports) {AutomaticExportsScreen(graph,settings.exportFolder!=null) {automaticExports=false};return}
    var automation by rememberSaveable {mutableStateOf(false)}
    if(automation) {AutomationScreen(graph) {automation=false};return}
    var webhooks by rememberSaveable {mutableStateOf(false)}
    if(webhooks) {WebhookScreen(graph) {webhooks=false};return}
    var photos by rememberSaveable {mutableStateOf(false)}
    if(photos) {PhotosScreen(graph) {photos=false};return}
    var library by rememberSaveable {mutableStateOf(false)}
    if(library) {SavedPlacesScreen(graph,places) {library=false};return}
    var addingPlace by remember { mutableStateOf(false) }
    var editingPlace by remember { mutableStateOf<SavedPlace?>(null) }
    var clear by remember { mutableStateOf(false) }
    var clearing by remember {mutableStateOf(false)}
    var message by remember { mutableStateOf<String?>(null) }
    val folder = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri -> if (uri != null) scope.launch {
        try {
            context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            graph.preferences.update { latest -> latest.copy(exportFolder = uri.toString()) }
        } catch (e: Exception) { message = "Folder access could not be saved. Choose a writable folder." }
    } }
    LazyColumn(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("settings-screen"), contentPadding = PaddingValues(20.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
        item { Text("Recording", style = MaterialTheme.typography.titleLarge) }
        item { Choice("Distance filter", "${settings.distanceFilter} m", listOf(5, 10, 25, 50, 100, 200).map { it.toString() }) { scope.launch { graph.preferences.update { latest -> latest.copy(distanceFilter = it.toInt()) } } } }
        item { Choice("Stop after", if (settings.stopAfterHours == 0) "Never" else "${settings.stopAfterHours} h", listOf("0", "1", "2", "4", "8", "12")) { scope.launch { graph.preferences.update { latest -> latest.copy(stopAfterHours = it.toInt()) } } } }
        item { Text("Recording settings apply to the next start.", style = MaterialTheme.typography.bodySmall) }
        item { TextButton(onClick = { context.startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${context.packageName}"))) }) { Text("Location and notification permissions") } }
        item { HorizontalDivider() }
        item { Text("Inferred outings",style=MaterialTheme.typography.titleLarge) }
        item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) { Text("Include inferred outings",Modifier.padding(top=12.dp)); Switch(settings.inferredEnabled,{ scope.launch { graph.preferences.update { latest -> latest.copy(inferredEnabled=it) } } }) } }
        if(settings.inferredEnabled) {
            item { Choice("Split after a gap", "${settings.inferredGapMinutes} min",listOf("15","30","60","120","240")) { scope.launch { graph.preferences.update { latest -> latest.copy(inferredGapMinutes=it.toInt()) } } } }
            item { Choice("Minimum duration",if(settings.inferredMinimumMinutes==0) "None" else "${settings.inferredMinimumMinutes} min",listOf("0","5","15","30","60")) { scope.launch { graph.preferences.update { latest -> latest.copy(inferredMinimumMinutes=it.toInt()) } } } }
            item { Choice("Minimum points",settings.inferredMinimumPoints.toString(),listOf("1","2","3","5","10")) { scope.launch { graph.preferences.update { latest -> latest.copy(inferredMinimumPoints=it.toInt()) } } } }
        }
        item { HorizontalDivider() }
        item { Text("Appearance", style = MaterialTheme.typography.titleLarge) }
        item { Choice("Theme", settings.appearance, listOf("system", "light", "dark")) { scope.launch { graph.preferences.update { latest -> latest.copy(appearance = it) } } } }
        item { Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) { Text("Metric distances", Modifier.padding(top = 12.dp)); Switch(settings.metric, { scope.launch { graph.preferences.update { latest -> latest.copy(metric = it) } } }) } }
        item { HorizontalDivider() }
        item { OutlinedButton(onClick={ countryDays=true }) { Text("Country Days") } }
        item {OutlinedButton(onClick={photos=true}) {Text("Photos")}}
        item { Text("Saved places", style = MaterialTheme.typography.titleLarge) }
        item {Text("${places.size} saved ${if(places.size==1) "place" else "places"}",style=MaterialTheme.typography.bodySmall)}
        item {OutlinedButton(onClick={library=true}) {Text("Manage saved places")}}
        item { OutlinedButton(onClick = { addingPlace = true }) { Text("Add saved place") } }
        item { HorizontalDivider() }
        item { Text("Data and privacy", style = MaterialTheme.typography.titleLarge) }
        item {OutlinedButton(onClick={usage=true}) {Text(androidx.compose.ui.res.stringResource(R.string.usage_title))}}
        item { Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("Network geocoding",Modifier.weight(1f).padding(top=12.dp))
            Switch(settings.networkGeocoding,{ enabled -> scope.launch { graph.preferences.update { latest -> latest.copy(networkGeocoding=enabled) } } },modifier=Modifier.semantics { contentDescription="Allow network geocoding" })
        } }
        item { Text("Place search sends submitted text; country and address lookups can send coordinates to your device’s geocoding service. Disabling this stops these lookups and country analysis; recording, offline pin selection and manual stays remain available.",style=MaterialTheme.typography.bodySmall) }
        item { Text("$count recorded points. Location history is stored on this phone. Automatic cloud backup and device transfer are disabled.") }
        item { OutlinedButton(onClick = { folder.launch(null) }) { Text(if (settings.exportFolder == null) "Choose export folder" else "Change export folder") } }
        item { OutlinedButton(onClick={automaticExports=true}) {Text("Automatic exports")} }
        item { OutlinedButton(onClick={webhooks=true}) {Text("Webhooks")} }
        item {OutlinedButton(onClick={automation=true}) {Text("Automation access")}}
        message?.let { item { Text(it, color = MaterialTheme.colorScheme.error) } }
        item { TextButton(onClick = { clear = true }) { Text("Clear all journal data", color = MaterialTheme.colorScheme.error) } }
        item { TextButton(onClick = { scope.launch { graph.preferences.update { latest -> latest.copy(onboarded = false) } } }) { Text("Replay onboarding") } }
        item { TextButton(onClick={ licenses=true }) { Text("Geographic data and licenses") } }
        item {OutlinedButton(onClick={support=true}) {Text(androidx.compose.ui.res.stringResource(R.string.support_title))}}
    }
    if (addingPlace || editingPlace != null) {
        val expected=editingPlace
        PlaceEditor(expected,{addingPlace=false;editingPlace=null},{graph.savedPlaces.save(it,expected)},expected?.let {place ->{graph.savedPlaces.delete(place)}})
    }
    if (clear) AlertDialog(onDismissRequest = { if(!clearing) clear = false }, title = { Text("Clear all journal data?") }, text = { Text("This deletes visits, routes, saved places, country stays and photo references on this phone. Original photos and exported files remain in their libraries and destination folders.") },
        confirmButton = { TextButton(enabled=!clearing,onClick = { if(clearing) return@TextButton;clearing=true;scope.launch {try {
            graph.historyImports.clearAll {
            graph.reviewMilestones.clearJournalMilestones()
            graph.usageAnalytics.clearAll()
            graph.automationController.clearAll()
            graph.countryAnalysis.stopAndJoin()
            graph.placeLookup.clearCache()
            graph.automaticExportController.clearAll()
            graph.exports.clearCache()
            graph.recordingCommands.stop()
            graph.automaticPhotos.clearAll()
            graph.webhookController.clearAll {graph.photoLibrary.clearAll {graph.replay.clearCache { kotlinx.coroutines.withContext(kotlinx.coroutines.Dispatchers.IO) { graph.database.clearAllTables() } }}}
            graph.activityLog.clear()
            }
            clear = false
        } catch(e:CancellationException) {throw e} catch(_:Exception) {graph.activityLog.record(com.bontecou.whereday.platform.AppEvent.journalClearFailed);message="Journal data could not be completely cleared. Try again.";clear=false} finally {clearing=false}
        } }) { Text("Clear all data") } }, dismissButton = { TextButton(enabled=!clearing,onClick = { clear = false }) { Text("Cancel") } })
}

@Composable fun Choice(label: String, value: String, choices: List<String>, enabled:Boolean=true,change: (String) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
        Text(label, Modifier.padding(top = 12.dp))
        Box { TextButton(enabled=enabled,onClick = { expanded = true }) { Text(value.replaceFirstChar { it.uppercase() }) }
            DropdownMenu(expanded, { expanded = false }) { choices.forEach { choice -> DropdownMenuItem(text = { Text(choice.replaceFirstChar { it.uppercase() }) }, onClick = { expanded = false; change(choice) }) } }
        }
    }
}

@Composable internal fun PlaceEditor(place: SavedPlace?, dismiss: () -> Unit, save:suspend (SavedPlace)->Unit,delete:(suspend ()->Unit)?) {
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    val draftId=rememberSaveable {place?.id ?: java.util.UUID.randomUUID().toString()}
    var name by rememberSaveable {mutableStateOf(place?.name.orEmpty())}
    var address by rememberSaveable {mutableStateOf(place?.address.orEmpty())}
    var lat by rememberSaveable {mutableStateOf(place?.coordinate?.latitude?.toString().orEmpty())}
    var lon by rememberSaveable {mutableStateOf(place?.coordinate?.longitude?.toString().orEmpty())}
    var radius by rememberSaveable {mutableStateOf((place?.radius ?: 150.0).toString())}
    var choosingLocation by rememberSaveable {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(false)}
    var deleting by remember {mutableStateOf(false)}
    var discarding by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val dirty=name!=place?.name.orEmpty() || address!=place?.address.orEmpty() || lat!=place?.coordinate?.latitude?.toString().orEmpty() ||
        lon!=place?.coordinate?.longitude?.toString().orEmpty() || radius!=(place?.radius ?: 150.0).toString()
    fun cancel() {if(!busy) {if(dirty) discarding=true else dismiss()}}
    if(choosingLocation) {
        val coordinate=try {Coordinate(lat.toDouble(),lon.toDouble())} catch(_:IllegalArgumentException) {null}
        PlaceLocationPicker(coordinate,{choosingLocation=false}) {selected->
            lat=selected.coordinate.latitude.toString();lon=selected.coordinate.longitude.toString()
            if(selected.replaceMetadata) {
                address=selected.address.orEmpty()
                if(name.isBlank()) name=selected.name.orEmpty()
            }
            choosingLocation=false
        }
        return
    }
    AlertDialog(onDismissRequest=::cancel,title={Text(if(place==null) "Save a place" else "Edit saved place")},text={
        Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name,{name=it},label={Text("Name")},enabled=!busy)
            OutlinedTextField(address,{address=it},label={Text("Address")},enabled=!busy)
            OutlinedButton(enabled=!busy,onClick={focus.clearFocus();keyboard?.hide();choosingLocation=true}) {Text("Choose location on map")}
            OutlinedTextField(lat,{lat=it},label={Text("Latitude")},enabled=!busy)
            OutlinedTextField(lon,{lon=it},label={Text("Longitude")},enabled=!busy)
            OutlinedTextField(radius,{radius=it},label={Text("Match radius (meters)")},enabled=!busy)
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(delete!=null) TextButton(enabled=!busy,onClick={deleting=true}) {Text("Delete saved place")}
        }
    },confirmButton={TextButton(enabled=!busy,onClick={
        val candidate=try {SavedPlace(id=draftId,name=name.trim(),coordinate=Coordinate(lat.toDouble(),lon.toDouble()),address=address.ifBlank {null},radius=radius.toDouble())}
            catch(_:IllegalArgumentException) {error="Enter a name, valid coordinates and a radius from 25 to 10,000 meters.";return@TextButton}
        scope.launch {busy=true;error=null;try {save(candidate);dismiss()} catch(e:Exception) {if(e is CancellationException) throw e;error=if(e is SavedPlacesChanged) e.message else "Place could not be saved. Check available storage and try again."} finally {busy=false}}
    }) {Text("Save")}},dismissButton={TextButton(enabled=!busy,onClick=::cancel) {Text("Cancel")}})
    if(deleting) AlertDialog(onDismissRequest={if(!busy) deleting=false},title={Text("Delete this saved place?")},text={Text("Visits and route history remain in your journal.")},confirmButton={TextButton(enabled=!busy,onClick={scope.launch {
        busy=true;try {delete?.invoke();dismiss()} catch(e:Exception) {if(e is CancellationException) throw e;error=if(e is SavedPlacesChanged) e.message else "Place could not be deleted. Try again.";deleting=false} finally {busy=false}
    }}) {Text("Delete place")}},dismissButton={TextButton(enabled=!busy,onClick={deleting=false}) {Text("Cancel")}})
    if(discarding) AlertDialog(onDismissRequest={discarding=false},title={Text("Discard saved place changes?")},confirmButton={TextButton(onClick=dismiss) {Text("Discard changes")}},dismissButton={TextButton(onClick={discarding=false}) {Text("Keep editing")}})
}
