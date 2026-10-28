package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.data.VisitChanged
import com.bontecou.whereday.data.SavedPlacesChanged
import androidx.compose.ui.semantics.contentDescription
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import java.time.Instant
import java.util.UUID

private data class ReusablePlaceReview(val candidate:SavedPlace,val existing:List<SavedPlace>,val plan:SavedPlaceReusePlan)

@Composable internal fun VisitEditor(graph:AppGraph,visit:Visit?,dismiss:()->Unit,save:suspend (Visit,SavedPlaceReuseSave?)->Unit,delete:(suspend ()->Unit)?,mode:ManualVisitMode=ManualVisitMode.past) {
    var locating by remember {mutableStateOf(false)}
    var currentFix by remember {mutableStateOf<CurrentVisitLocation?>(null)}
    var currentFixRequired by rememberSaveable {mutableStateOf(false)}
    val elapsed by produceState(android.os.SystemClock.elapsedRealtime()) {while(true) {delay(1000);value=android.os.SystemClock.elapsedRealtime()}}
    val expiredFix=currentFixRequired && currentFix?.isUsable(maxOf(elapsed,android.os.SystemClock.elapsedRealtime()))!=true
    val reusableId=rememberSaveable {UUID.randomUUID().toString()}
    var saveReusable by rememberSaveable {mutableStateOf(false)}
    var reusableRadius by rememberSaveable {mutableIntStateOf(150)}
    var selectedSavedId by rememberSaveable {mutableStateOf<String?>(null)}
    var selectedSavedName by rememberSaveable {mutableStateOf<String?>(null)}
    val places by produceState<List<SavedPlace>?>(null,graph) {graph.history.dao.observePlaces().collect {rows->
        value=withContext(Dispatchers.Default) {
            val collator=java.text.Collator.getInstance()
            rows.map {it.value}.sortedWith {a,b->collator.compare(a.name,b.name).takeIf {it!=0} ?: a.id.compareTo(b.id)}
        }
    }}
    val id=rememberSaveable {visit?.id ?: UUID.randomUUID().toString()}
    var name by rememberSaveable {mutableStateOf(visit?.name.orEmpty())}
    var address by rememberSaveable {mutableStateOf(visit?.address.orEmpty())}
    var notes by rememberSaveable {mutableStateOf(visit?.notes.orEmpty())}
    var latitude by rememberSaveable {mutableStateOf(visit?.coordinate?.latitude?.toString().orEmpty())}
    var longitude by rememberSaveable {mutableStateOf(visit?.coordinate?.longitude?.toString().orEmpty())}
    val initialArrival=rememberSaveable {visit?.arrivedAt?.toEpochMilli() ?: (if(mode==ManualVisitMode.current) Instant.now() else Instant.now().minusSeconds(3600)).toEpochMilli()}
    val initialDeparture=rememberSaveable {visit?.departedAt?.toEpochMilli() ?: Instant.now().toEpochMilli()}
    var arrivalMillis by rememberSaveable {mutableLongStateOf(initialArrival)}
    var departureMillis by rememberSaveable {mutableLongStateOf(initialDeparture)}
    var stillHere by rememberSaveable {mutableStateOf((visit!=null && visit.departedAt==null) || (visit==null && mode==ManualVisitMode.current))}
    var choosing by rememberSaveable {mutableStateOf(false)}
    var savedChooser by remember {mutableStateOf(false)}
    var source by rememberSaveable {mutableStateOf(visit?.placeSource ?: "userEntered")}
    var error by remember {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(false)}
    var deleting by remember {mutableStateOf(false)}
    var undoing by remember {mutableStateOf(false)}
    var discarding by remember {mutableStateOf(false)}
    var attempted by remember {mutableStateOf<Pair<Pair<VisitDraft,Boolean>,Visit>?>(null)}
    val scope=rememberCoroutineScope();val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    val reusableCandidate=if(visit==null && selectedSavedId==null && saveReusable) runCatching {
        SavedPlace(reusableId,name.trim(),Coordinate(latitude.trim().replace(',','.').toDouble(),longitude.trim().replace(',','.').toDouble()),address.trim().ifEmpty {null},reusableRadius.toDouble())
    }.getOrNull() else null
    val reusableReview by produceState<ReusablePlaceReview?>(null,reusableCandidate,places) {
        value=null
        val candidate=reusableCandidate;val existing=places
        if(candidate!=null && existing!=null) value=ReusablePlaceReview(candidate,existing,graph.savedPlaces.previewReuse(candidate,existing))
    }
    val readyReuse=reusableReview?.takeIf {it.candidate==reusableCandidate && it.existing==places}
    val dirty=name!=visit?.name.orEmpty() || address!=visit?.address.orEmpty() || notes!=visit?.notes.orEmpty() || latitude!=visit?.coordinate?.latitude?.toString().orEmpty() || longitude!=visit?.coordinate?.longitude?.toString().orEmpty() || arrivalMillis!=initialArrival || departureMillis!=initialDeparture || stillHere!=((visit!=null&&visit.departedAt==null)||(visit==null&&mode==ManualVisitMode.current)) || saveReusable
    fun cancel() {if(!busy) {focus.clearFocus();keyboard?.hide();if(dirty) discarding=true else dismiss()}}
    fun persist(value:Visit,reuse:SavedPlaceReuseSave?=null) {scope.launch {
        busy=true;error=null
        try {save(value,reuse);dismiss()} catch(e:Exception) {if(e is CancellationException) throw e;error=if(e is VisitChanged || e is SavedPlacesChanged) e.message else "Visit could not be saved. Check storage and try again."} finally {busy=false}
    }}
    fun submit(confirm:Boolean=false) {
        focus.clearFocus();keyboard?.hide()
        val coordinate=try {Coordinate(latitude.trim().replace(',','.').toDouble(),longitude.trim().replace(',','.').toDouble())} catch(_:IllegalArgumentException) {null}
        val draft=VisitDraft(id,name,address,notes,coordinate,Instant.ofEpochMilli(arrivalMillis),if(stillHere) null else Instant.ofEpochMilli(departureMillis),source)
        try {
            require(!currentFixRequired || currentFix?.isUsable(android.os.SystemClock.elapsedRealtime())==true) {"Current location expired. Request a fresh location or choose a saved place."}
            val key=draft to confirm
            val value=attempted?.takeIf {it.first==key}?.second ?: draft.build(visit,Instant.now(),confirm,mode).also {attempted=key to it}
            val reuse=if(saveReusable && selectedSavedId==null && visit==null) {
                val review=readyReuse ?: throw IllegalArgumentException("Review the reusable place before saving.")
                SavedPlaceReuseSave(review.candidate,review.existing)
            } else null
            persist(value,reuse)
        } catch(e:IllegalArgumentException) {error=e.message}
    }
    BackHandler(onBack=::cancel)
    if(choosing) {
        val coordinate=try {Coordinate(latitude.trim().replace(',','.').toDouble(),longitude.trim().replace(',','.').toDouble())} catch(_:IllegalArgumentException) {null}
        PlaceLocationPicker(coordinate,{choosing=false}) {selected->
            val selectedDraft=VisitDraft(id,name,address,notes,coordinate,Instant.ofEpochMilli(arrivalMillis),if(stillHere) null else Instant.ofEpochMilli(departureMillis),source).selectLocation(selected)
            latitude=selected.coordinate.latitude.toString();longitude=selected.coordinate.longitude.toString()
            name=selectedDraft.name;address=selectedDraft.address;source=selectedDraft.placeSource
            currentFix=null;currentFixRequired=false;selectedSavedId=null;selectedSavedName=null;saveReusable=false;choosing=false;error=null
        }
        return
    }
    AlertDialog(onDismissRequest=::cancel,title={Text(if(visit!=null) "Edit visit" else if(mode==ManualVisitMode.current) "Save current place" else "Add past visit")},text={
        Column(Modifier.semantics {testTagsAsResourceId=true}.testTag("visit-editor-fields").verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(name,{name=it;source="userEntered"},label={Text("Name")},enabled=!busy)
            OutlinedTextField(address,{address=it;source="userEntered"},label={Text("Address")},enabled=!busy)
            OutlinedButton(enabled=!busy,onClick={focus.clearFocus();keyboard?.hide();choosing=true}) {Text("Choose visit location")}
            OutlinedButton(enabled=!busy,onClick={focus.clearFocus();keyboard?.hide();savedChooser=true}) {Text("Choose saved place")}
            if(visit==null && mode==ManualVisitMode.current) {
                OutlinedButton(enabled=!busy,onClick={focus.clearFocus();keyboard?.hide();locating=true}) {Text("Find current location")}
                if(currentFixRequired) Text(if(expiredFix) "Current location expired. Request again before saving." else "Current location ready")
            }
            OutlinedTextField(latitude,{latitude=it;source="userEntered";currentFix=null;currentFixRequired=false;selectedSavedId=null;selectedSavedName=null;saveReusable=false},label={Text("Latitude")},enabled=!busy)
            OutlinedTextField(longitude,{longitude=it;source="userEntered";currentFix=null;currentFixRequired=false;selectedSavedId=null;selectedSavedName=null;saveReusable=false},label={Text("Longitude")},enabled=!busy)
            VisitDateTimeControl("Arrived",Instant.ofEpochMilli(arrivalMillis),!busy) {arrivalMillis=it.toEpochMilli()}
            if(visit!=null || mode==ManualVisitMode.current) Row {Checkbox(stillHere,{stillHere=it},enabled=!busy,modifier=Modifier.semantics {contentDescription="Still here"});Text("Still here",Modifier.padding(top=12.dp))}
            else Text("Past visits need arrival and departure times.",style=MaterialTheme.typography.bodySmall)
            if(!stillHere) VisitDateTimeControl("Departed",Instant.ofEpochMilli(departureMillis),!busy) {departureMillis=it.toEpochMilli()}
            OutlinedTextField(notes,{notes=it},label={Text("Notes")},enabled=!busy)
            if(visit==null) {
                if(selectedSavedId!=null) Text("Using saved place: ${selectedSavedName.orEmpty()}",style=MaterialTheme.typography.bodySmall)
                else {
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        Text("Save as reusable location",Modifier.weight(1f).padding(top=12.dp))
                        Switch(saveReusable,{saveReusable=it},enabled=!busy,modifier=Modifier.semantics {contentDescription="Save visit as reusable place"})
                    }
                    if(saveReusable) {
                        Choice("Match radius","$reusableRadius m",listOf("50 m","100 m","150 m","250 m"),!busy) {reusableRadius=it.substringBefore(' ').toInt()}
                        Text("Future automatic visits within this radius use the saved name. Existing visits and routes stay unchanged.",style=MaterialTheme.typography.bodySmall)
                        if(reusableCandidate==null) Text("Enter a name and valid coordinates to prepare the reusable place.")
                        else if(readyReuse==null) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Reviewing saved places…")}
                        else Text(readyReuse.plan.original?.let {"Update saved place: ${it.name}"} ?: "Create saved place: ${readyReuse.plan.value.name}",style=MaterialTheme.typography.bodyMedium)
                    }
                }
            }
            error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth())
            if(visit?.confirmation==Confirmation.unconfirmed) TextButton(enabled=!busy,onClick={submit(true)}) {Text("Confirm visit")}
            if(visit?.originalCoordinate!=null) TextButton(enabled=!busy,onClick={undoing=true}) {Text("Undo correction")}
            if(delete!=null) TextButton(enabled=!busy,onClick={deleting=true}) {Text("Delete visit",color=MaterialTheme.colorScheme.error)}
        }
    },confirmButton={TextButton(enabled=!busy && !expiredFix && (!saveReusable || readyReuse!=null),onClick={submit()}) {Text("Save")}},dismissButton={TextButton(enabled=!busy,onClick=::cancel) {Text("Cancel")}})
    if(locating) CurrentVisitLocationDialog(graph.currentVisitLocation,{locating=false}) {fix->
        // Current GPS retains user-entered metadata, unlike moving an explicitly selected map pin.
        latitude=fix.coordinate.latitude.toString();longitude=fix.coordinate.longitude.toString();currentFix=fix;currentFixRequired=true
        source="userEntered";selectedSavedId=null;selectedSavedName=null;saveReusable=false;locating=false;error=null
    }
    if(savedChooser) SavedPlaceChooser(graph,{savedChooser=false}) {place->
        name=place.name;address=place.address.orEmpty();latitude=place.coordinate.latitude.toString();longitude=place.coordinate.longitude.toString();source="userEntered";currentFix=null;currentFixRequired=false;selectedSavedId=place.id;selectedSavedName=place.name;saveReusable=false;savedChooser=false;error=null
    }
    if(discarding) AlertDialog(onDismissRequest={discarding=false},title={Text("Discard visit changes?")},confirmButton={TextButton(onClick=dismiss) {Text("Discard changes")}},dismissButton={TextButton(onClick={discarding=false}) {Text("Keep editing")}})
    if(undoing) AlertDialog(onDismissRequest={undoing=false},title={Text("Restore the original place?")},text={Text("This restores the place before correction. Unsaved editor changes will be discarded.")},confirmButton={TextButton(enabled=!busy,onClick={undoing=false;visit?.let {persist(it.undoCorrection(Instant.now()))}}) {Text("Restore place")}},dismissButton={TextButton(onClick={undoing=false}) {Text("Cancel")}})
    if(deleting) AlertDialog(onDismissRequest={if(!busy) deleting=false},title={Text("Delete this visit?")},text={Text("This removes the visit. Recorded routes and saved places remain.")},confirmButton={TextButton(enabled=!busy,onClick={scope.launch {
        busy=true;try {delete?.invoke();dismiss()} catch(e:Exception) {if(e is CancellationException) throw e;error=if(e is VisitChanged || e is SavedPlacesChanged) e.message else "Visit could not be deleted. Try again.";deleting=false} finally {busy=false}
    }}) {Text("Delete")}},dismissButton={TextButton(enabled=!busy,onClick={deleting=false}) {Text("Cancel")}})
}
