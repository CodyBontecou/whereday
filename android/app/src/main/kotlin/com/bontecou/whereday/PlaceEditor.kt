package com.bontecou.whereday

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Map
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
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
    val fieldShape=RoundedCornerShape(10.dp)
    // A paper sheet with TECard-style sections, an ink save slab and a rust delete action, as on iOS.
    WorkspaceDialog(fillRegion=true,onDismissRequest=::cancel,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=wd.surface,contentColor=wd.textPrimary) {
            Column(Modifier.fillMaxSize().imePadding()) {
                Box(Modifier.fillMaxWidth().heightIn(min=56.dp).padding(horizontal=12.dp)) {
                    PlaceEditorPill(stringResource(R.string.visit_cancel),::cancel,Modifier.align(Alignment.CenterStart).testTag("saved-editor-cancel"),enabled=!busy)
                    Text(stringResource(if(place==null) R.string.saved_editor_add else R.string.saved_editor_edit),style=WdType.title3,color=wd.textPrimary,maxLines=1,overflow=TextOverflow.Ellipsis,textAlign=TextAlign.Center,
                        modifier=Modifier.align(Alignment.Center).padding(horizontal=104.dp).semantics {heading()})
                }
                Column(Modifier.weight(1f).semantics {testTagsAsResourceId=true}.testTag("saved-editor-fields").verticalScroll(rememberScrollState()).padding(bottom=16.dp)) {
                    WdSectionHeader(stringResource(R.string.redesign_places_section_details))
                    Column(Modifier.padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        OutlinedTextField(name,{name=it},label={Text(stringResource(R.string.visit_name))},enabled=!busy,shape=fieldShape,colors=wdTextFieldColors(),textStyle=WdType.serifBody,modifier=Modifier.fillMaxWidth().testTag("saved-name"))
                        OutlinedTextField(address,{address=it},label={Text(stringResource(R.string.visit_address))},enabled=!busy,shape=fieldShape,colors=wdTextFieldColors(),modifier=Modifier.fillMaxWidth().testTag("saved-address"))
                    }
                    WdSectionHeader(stringResource(R.string.redesign_places_section_location))
                    Column(Modifier.padding(horizontal=16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                        WdButton(stringResource(R.string.visit_choose_location),{focus.clearFocus();keyboard?.hide();choosingLocation=true},enabled=!busy,icon=Icons.Outlined.Map)
                        OutlinedTextField(lat,{lat=it},label={Text(stringResource(R.string.visit_latitude))},enabled=!busy,shape=fieldShape,colors=wdTextFieldColors(),textStyle=WdType.monoBody,modifier=Modifier.fillMaxWidth().testTag("saved-latitude"))
                        OutlinedTextField(lon,{lon=it},label={Text(stringResource(R.string.visit_longitude))},enabled=!busy,shape=fieldShape,colors=wdTextFieldColors(),textStyle=WdType.monoBody,modifier=Modifier.fillMaxWidth().testTag("saved-longitude"))
                    }
                    WdSectionHeader(stringResource(R.string.redesign_places_section_radius))
                    OutlinedTextField(radius,{radius=it},label={Text(stringResource(R.string.saved_editor_radius))},enabled=!busy,shape=fieldShape,colors=wdTextFieldColors(),textStyle=WdType.monoBody,modifier=Modifier.padding(horizontal=16.dp).fillMaxWidth().testTag("saved-radius"))
                    error?.let {val notice=RoundedCornerShape(14.dp)
                        Text(stringResource(it),style=MaterialTheme.typography.bodyMedium,color=wd.inkRust,modifier=Modifier.padding(start=16.dp,end=16.dp,top=16.dp).fillMaxWidth().clip(notice)
                            .background(wd.inkRust.copy(alpha=0.08f)).border(1.dp,wd.inkRust.copy(alpha=0.35f),notice).padding(14.dp).testTag("saved-editor-error"))}
                    if(busy) PlacesProgress(Modifier.padding(start=16.dp,end=16.dp,top=12.dp),inset=false)
                    if(delete!=null) WdButton(stringResource(R.string.saved_editor_delete),{deleting=true},Modifier.padding(start=16.dp,end=16.dp,top=28.dp),emphasis=WdEmphasis.destructive,enabled=!busy,icon=Icons.Outlined.Delete)
                }
                WdDivider()
                Box(Modifier.fillMaxWidth().background(wd.surface).padding(horizontal=16.dp,vertical=12.dp)) {
                    key(ticket) {WdButton(stringResource(R.string.visit_save),onClick={
                        if(!actions.allows(ticket) || busy) return@WdButton
                        val candidate=try {SavedPlace(id=draftId,name=name.trim(),coordinate=Coordinate(lat.trim().replace(',','.').toDouble(),lon.trim().replace(',','.').toDouble()),address=address.ifBlank {null},radius=radius.trim().replace(',','.').toDouble())}
                            catch(_:IllegalArgumentException) {error=R.string.saved_editor_invalid;return@WdButton}
                        busy=true;error=null
                        scope.launch {try {save(candidate);dismiss()} catch(e:Exception) {if(e is CancellationException) throw e;error=if(e is SavedPlacesChanged) R.string.saved_changed else R.string.saved_editor_save_error} finally {busy=false}}
                    },modifier=Modifier.testTag("saved-editor-save"),emphasis=WdEmphasis.primary,enabled=!busy,busy=busy)}
                }
            }
        }
    }
    if(deleting) WorkspaceAlertDialog(onDismissRequest={if(!busy) deleting=false},title={Text(stringResource(R.string.saved_delete_title),style=WdType.title2)},text={Text(stringResource(R.string.saved_delete_body),color=wd.textPrimary)},confirmButton={key(ticket) {TextButton(modifier=Modifier.semantics {testTagsAsResourceId=true}.testTag("saved-delete-confirm"),enabled=!busy,onClick={
        if(!actions.allows(ticket) || busy) return@TextButton
        busy=true
        scope.launch {try {delete?.invoke();dismiss()} catch(e:Exception) {if(e is CancellationException) throw e;error=if(e is SavedPlacesChanged) R.string.saved_changed else R.string.saved_editor_delete_error;deleting=false} finally {busy=false}}
    }) {Text(stringResource(R.string.saved_delete_action),color=if(busy) wd.textMuted else wd.inkRust,fontWeight=FontWeight.SemiBold)}}},dismissButton={TextButton(modifier=Modifier.semantics {testTagsAsResourceId=true}.testTag("saved-delete-cancel"),enabled=!busy,onClick={deleting=false}) {Text(stringResource(R.string.visit_cancel))}})
    if(discarding) WorkspaceAlertDialog(onDismissRequest={discarding=false},title={Text(stringResource(R.string.saved_discard_title),style=WdType.title2)},confirmButton={key(ticket) {TextButton(onClick={if(actions.allows(ticket) && !busy) dismiss()}) {Text(stringResource(R.string.saved_discard_action),color=wd.inkRust,fontWeight=FontWeight.SemiBold)}}},dismissButton={TextButton(onClick={discarding=false}) {Text(stringResource(R.string.visit_keep_editing))}})
}

/** A paper capsule for a sheet's leading text action (iOS "Cancel"). */
@Composable internal fun PlaceEditorPill(text:String,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true) {
    Box(modifier.heightIn(min=44.dp).clip(CircleShape).background(wd.card).border(1.dp,wd.border,CircleShape)
        .clickable(enabled=enabled,role=Role.Button,onClick=onClick).padding(horizontal=14.dp),contentAlignment=Alignment.Center) {
        Text(text,style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=if(enabled) wd.textPrimary else wd.textMuted,maxLines=1)
    }
}
