package com.bontecou.whereday

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.bontecou.whereday.data.SavedPlacesChanged
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*

@Composable internal fun PlaceEditor(place: SavedPlace?, dismiss: () -> Unit, save:suspend (SavedPlace)->Unit,delete:(suspend ()->Unit)?) {
    BlockVisitLinks()
    val actions=rememberVisibleEditorActions()
    val ticket=actions.epoch
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    val draftId=rememberSaveable {place?.id ?: java.util.UUID.randomUUID().toString()}
    var name by rememberSaveable {mutableStateOf(place?.name.orEmpty())}
    var address by rememberSaveable {mutableStateOf(place?.address.orEmpty())}
    var lat by rememberSaveable {mutableStateOf(place?.coordinate?.latitude?.toString().orEmpty())}
    var lon by rememberSaveable {mutableStateOf(place?.coordinate?.longitude?.toString().orEmpty())}
    var radius by rememberSaveable {mutableStateOf((place?.radius ?: 150.0).toString())}
    var choosingLocation by rememberSaveable {mutableStateOf(false)}
    var error by remember {mutableStateOf<Int?>(null)}
    var busy by remember {mutableStateOf(false)}
    var deleting by remember {mutableStateOf(false)}
    var discarding by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    val dirty=name!=place?.name.orEmpty() || address!=place?.address.orEmpty() || lat!=place?.coordinate?.latitude?.toString().orEmpty() ||
        lon!=place?.coordinate?.longitude?.toString().orEmpty() || radius!=(place?.radius ?: 150.0).toString()
    fun cancel() {if(!busy) {if(dirty) discarding=true else dismiss()}}
    if(choosingLocation) {
        val coordinate=try {Coordinate(lat.trim().replace(',','.').toDouble(),lon.trim().replace(',','.').toDouble())} catch(_:IllegalArgumentException) {null}
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
    WorkspaceAlertDialog(onDismissRequest=::cancel,title={Text(stringResource(if(place==null) R.string.saved_editor_add else R.string.saved_editor_edit))},text={
        Column(Modifier.semantics {testTagsAsResourceId=true}.testTag("saved-editor-fields").verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedTextField(name,{name=it},label={Text(stringResource(R.string.visit_name))},enabled=!busy,modifier=Modifier.testTag("saved-name"))
            OutlinedTextField(address,{address=it},label={Text(stringResource(R.string.visit_address))},enabled=!busy,modifier=Modifier.testTag("saved-address"))
            OutlinedButton(enabled=!busy,onClick={focus.clearFocus();keyboard?.hide();choosingLocation=true}) {Text(stringResource(R.string.visit_choose_location))}
            OutlinedTextField(lat,{lat=it},label={Text(stringResource(R.string.visit_latitude))},enabled=!busy,modifier=Modifier.testTag("saved-latitude"))
            OutlinedTextField(lon,{lon=it},label={Text(stringResource(R.string.visit_longitude))},enabled=!busy,modifier=Modifier.testTag("saved-longitude"))
            OutlinedTextField(radius,{radius=it},label={Text(stringResource(R.string.saved_editor_radius))},enabled=!busy,modifier=Modifier.testTag("saved-radius"))
            error?.let {Text(stringResource(it),color=MaterialTheme.colorScheme.error,modifier=Modifier.testTag("saved-editor-error"))}
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(delete!=null) TextButton(enabled=!busy,onClick={deleting=true}) {Text(stringResource(R.string.saved_editor_delete))}
        }
    },confirmButton={key(ticket) {TextButton(modifier=Modifier.testTag("saved-editor-save"),enabled=!busy,onClick={
        if(!actions.allows(ticket) || busy) return@TextButton
        val candidate=try {SavedPlace(id=draftId,name=name.trim(),coordinate=Coordinate(lat.trim().replace(',','.').toDouble(),lon.trim().replace(',','.').toDouble()),address=address.ifBlank {null},radius=radius.trim().replace(',','.').toDouble())}
            catch(_:IllegalArgumentException) {error=R.string.saved_editor_invalid;return@TextButton}
        busy=true;error=null
        scope.launch {try {save(candidate);dismiss()} catch(e:Exception) {if(e is CancellationException) throw e;error=if(e is SavedPlacesChanged) R.string.saved_changed else R.string.saved_editor_save_error} finally {busy=false}}
    }) {Text(stringResource(R.string.visit_save))}}},dismissButton={TextButton(modifier=Modifier.testTag("saved-editor-cancel"),enabled=!busy,onClick=::cancel) {Text(stringResource(R.string.visit_cancel))}})
    if(deleting) WorkspaceAlertDialog(onDismissRequest={if(!busy) deleting=false},title={Text(stringResource(R.string.saved_delete_title))},text={Text(stringResource(R.string.saved_delete_body))},confirmButton={key(ticket) {TextButton(modifier=Modifier.semantics {testTagsAsResourceId=true}.testTag("saved-delete-confirm"),enabled=!busy,onClick={
        if(!actions.allows(ticket) || busy) return@TextButton
        busy=true
        scope.launch {try {delete?.invoke();dismiss()} catch(e:Exception) {if(e is CancellationException) throw e;error=if(e is SavedPlacesChanged) R.string.saved_changed else R.string.saved_editor_delete_error;deleting=false} finally {busy=false}}
    }) {Text(stringResource(R.string.saved_delete_action))}}},dismissButton={TextButton(modifier=Modifier.semantics {testTagsAsResourceId=true}.testTag("saved-delete-cancel"),enabled=!busy,onClick={deleting=false}) {Text(stringResource(R.string.visit_cancel))}})
    if(discarding) WorkspaceAlertDialog(onDismissRequest={discarding=false},title={Text(stringResource(R.string.saved_discard_title))},confirmButton={key(ticket) {TextButton(onClick={if(actions.allows(ticket) && !busy) dismiss()}) {Text(stringResource(R.string.saved_discard_action))}}},dismissButton={TextButton(onClick={discarding=false}) {Text(stringResource(R.string.visit_keep_editing))}})
}
