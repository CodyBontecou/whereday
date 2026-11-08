package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.Logout
import androidx.compose.material.icons.automirrored.outlined.Undo
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.data.VisitOverlap
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong
import com.bontecou.whereday.data.VisitChanged
import com.bontecou.whereday.data.SavedPlacesChanged
import androidx.compose.ui.semantics.contentDescription
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.util.UUID

private class VisitEditorInputError(val resource:Int):IllegalArgumentException()
private data class VisitSaveKey(val baseline:Visit?,val draft:VisitDraft,val reuse:SavedPlaceReuseSave?,val needsReuse:Boolean,val radius:Int,val mode:ManualVisitMode)
private data class VisitOverlapReview(val id:Long,val key:VisitSaveKey,val value:Visit,val rememberPlace:Boolean,val conflicting:Visit)
private data class ReusablePlaceReview(val candidate:SavedPlace,val existing:List<SavedPlace>,val plan:SavedPlaceReusePlan)

@Composable internal fun VisitEditor(graph:AppGraph,visit:Visit?,dismiss:()->Unit,save:suspend (Visit,SavedPlaceReuseSave?,Boolean,Boolean)->Unit,delete:(suspend ()->Unit)?,mode:ManualVisitMode=ManualVisitMode.past,nearbyLookup:PlaceLookupService=graph.placeLookup,nearbyAvailable:Boolean=com.bontecou.whereday.platform.GeoapifyPlaceLookup.available,currentLocationSource:CurrentVisitLocationSource=graph.currentVisitLocation,seed:VisitLinkSeed?=null,onShowOnMap:((Visit)->Unit)?=null,inline:Boolean=false,inlineBackLabel:Int=R.string.common_back) {
    BlockVisitLinks()
    val context=LocalContext.current
    fun failure(error:Exception,fallback:Int)=context.getString(when(error) {is VisitChanged->R.string.visit_error_changed;is SavedPlacesChanged->R.string.visit_error_places_changed;else->fallback})
    var locating by remember {mutableStateOf(false)}
    var nearbyOrigin by remember {mutableStateOf<Coordinate?>(null)}
    val nearbyNetworkAllowed=remember(graph) {graph.preferences.settings.map {it.networkGeocoding}}
    val nearbySettings by graph.preferences.settings.collectAsState(initial=null)
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
    var name by rememberSaveable {mutableStateOf(visit?.name ?: seed?.name.orEmpty())}
    var address by rememberSaveable {mutableStateOf(visit?.address.orEmpty())}
    var notes by rememberSaveable {mutableStateOf(visit?.notes.orEmpty())}
    var latitude by rememberSaveable {mutableStateOf((visit?.coordinate ?: seed?.coordinate)?.latitude?.toString().orEmpty())}
    var longitude by rememberSaveable {mutableStateOf((visit?.coordinate ?: seed?.coordinate)?.longitude?.toString().orEmpty())}
    val initialArrival=rememberSaveable {visit?.arrivedAt?.toEpochMilli() ?: seed?.arrival?.toEpochMilli() ?: (if(mode==ManualVisitMode.current) Instant.now() else Instant.now().minusSeconds(3600)).toEpochMilli()}
    val initialDeparture=rememberSaveable {visit?.departedAt?.toEpochMilli() ?: seed?.departure?.toEpochMilli() ?: Instant.now().toEpochMilli()}
    var arrivalMillis by rememberSaveable {mutableLongStateOf(initialArrival)}
    var departureMillis by rememberSaveable {mutableLongStateOf(initialDeparture)}
    var stillHere by rememberSaveable {mutableStateOf((visit!=null && visit.departedAt==null) || (visit==null && mode==ManualVisitMode.current))}
    var choosing by rememberSaveable {mutableStateOf(false)}
    var savedChooser by rememberSaveable {mutableStateOf(false)}
    var source by rememberSaveable {mutableStateOf(visit?.placeSource ?: "userEntered")}
    var sourceDistance by rememberSaveable {mutableStateOf(visit?.placeDistanceMeters?.takeIf {it.isFinite() && it>=0})}
    var placeSelectionChanged by rememberSaveable {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var busy by remember {mutableStateOf(false)}
    var deleting by remember {mutableStateOf(false)}
    var undoing by remember {mutableStateOf(false)}
    var discarding by remember {mutableStateOf(false)}
    var overlapReview by remember {mutableStateOf<VisitOverlapReview?>(null)}
    var mapReview by remember {mutableStateOf<VisitSaveKey?>(null)}
    val active=remember {AtomicBoolean(true)}
    val reviewSequence=remember {AtomicLong()}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        active.set(true)
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP) {overlapReview=null;mapReview=null}}
        lifecycle.addObserver(observer)
        onDispose {active.set(false);lifecycle.removeObserver(observer)}
    }
    fun interactive()=active.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
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
    fun currentDraft()=VisitDraft(id,name,address,notes,runCatching {Coordinate(latitude.trim().replace(',','.').toDouble(),longitude.trim().replace(',','.').toDouble())}.getOrNull(),
        Instant.ofEpochMilli(arrivalMillis),if(stillHere) null else Instant.ofEpochMilli(departureMillis),source,sourceDistance,placeSelectionChanged)
    val draft=currentDraft()
    val latestBaseline by rememberUpdatedState(visit)
    val latestReuse by rememberUpdatedState(readyReuse)
    val latestMode by rememberUpdatedState(mode)
    fun currentKey():VisitSaveKey {
        val reuse=saveReusable && selectedSavedId==null && latestBaseline==null
        return VisitSaveKey(latestBaseline,currentDraft(),if(reuse) latestReuse?.let {SavedPlaceReuseSave(it.candidate,it.existing)} else null,reuse,reusableRadius,latestMode)
    }
    val saveKey=currentKey()
    val needsReuse=saveKey.needsReuse
    LaunchedEffect(saveKey) {if(overlapReview?.key!=saveKey) overlapReview=null}
    LaunchedEffect(saveKey) {if(mapReview!=saveKey) mapReview=null}
    val dirty=name!=visit?.name.orEmpty() || address!=visit?.address.orEmpty() || notes!=visit?.notes.orEmpty() || latitude!=visit?.coordinate?.latitude?.toString().orEmpty() || longitude!=visit?.coordinate?.longitude?.toString().orEmpty() || arrivalMillis!=initialArrival || departureMillis!=initialDeparture || stillHere!=((visit!=null&&visit.departedAt==null)||(visit==null&&mode==ManualVisitMode.current)) || saveReusable || placeSelectionChanged
    fun cancel() {if(!busy) {focus.clearFocus();keyboard?.hide();if(dirty) discarding=true else dismiss()}}
    fun viewOnMap(discarded:VisitSaveKey?=null) {
        val stored=visit ?: return
        val action=onShowOnMap ?: return
        if(!interactive() || busy) return
        val ticket=currentKey()
        focus.clearFocus();keyboard?.hide()
        if(dirty && discarded==null) {mapReview=ticket;return}
        if(discarded!=null && discarded!=ticket) return
        mapReview=null;busy=true;error=null
        scope.launch {
            try {
                val current=graph.history.dao.visit(stored.id)?.value ?: throw IllegalStateException()
                if(interactive() && currentKey()==ticket) action(current)
            } catch(errorValue:Exception) {
                if(errorValue is CancellationException) throw errorValue
                if(interactive()) error=context.getString(R.string.visit_error_map)
            } finally {busy=false}
        }
    }
    fun persist(value:Visit,reuse:SavedPlaceReuseSave?=null,remember:Boolean=false,allowOverlap:Boolean=false,key:VisitSaveKey=saveKey) {
        if(!interactive() || busy || currentKey()!=key) return
        busy=true;error=null;overlapReview=null
        scope.launch {
            try {save(value,reuse,remember,allowOverlap);dismiss()}
            catch(e:Exception) {
                if(e is CancellationException) throw e
                if(e is VisitOverlap) {
                    if(interactive() && currentKey()==key) overlapReview=VisitOverlapReview(reviewSequence.incrementAndGet(),key,value,remember,e.conflicting)
                } else error=failure(e,R.string.visit_error_save)
            } finally {busy=false}
        }
    }
    fun submit(confirm:Boolean=false) {
        focus.clearFocus();keyboard?.hide()
        if(!interactive() || busy || currentKey()!=saveKey) return
        try {
            if(currentFixRequired && currentFix?.isUsable(android.os.SystemClock.elapsedRealtime())!=true) throw VisitEditorInputError(R.string.visit_error_expired)
            val key=draft to confirm
            val value=attempted?.takeIf {it.first==key}?.second ?: draft.build(visit,Instant.now(),confirm,mode).also {attempted=key to it}
            val reuse=if(needsReuse) saveKey.reuse ?: throw VisitEditorInputError(R.string.visit_error_reuse) else null
            persist(value,reuse,draft.remembersPlace(visit,confirm))
        } catch(e:IllegalArgumentException) {
            error=context.getString(when(e) {
            is InvalidVisitDraft->when(e.problem) {
                VisitDraftProblem.identity->R.string.visit_error_identity;VisitDraftProblem.coordinate->R.string.visit_error_coordinate
                VisitDraftProblem.placeName->R.string.visit_error_name;VisitDraftProblem.departure->R.string.visit_error_departure;VisitDraftProblem.times->R.string.visit_error_times
            }
            is VisitEditorInputError->e.resource
            else->R.string.visit_error_save
        })}
    }
    BackHandler(onBack=::cancel)
    if(choosing) {
        val coordinate=try {Coordinate(latitude.trim().replace(',','.').toDouble(),longitude.trim().replace(',','.').toDouble())} catch(_:IllegalArgumentException) {null}
        PlaceLocationPicker(coordinate,{choosing=false}) {selected->
            val selectedDraft=VisitDraft(id,name,address,notes,coordinate,Instant.ofEpochMilli(arrivalMillis),if(stillHere) null else Instant.ofEpochMilli(departureMillis),source,sourceDistance,placeSelectionChanged).selectLocation(selected)
            latitude=selected.coordinate.latitude.toString();longitude=selected.coordinate.longitude.toString()
            name=selectedDraft.name;address=selectedDraft.address;source=selectedDraft.placeSource;sourceDistance=selectedDraft.placeDistanceMeters;placeSelectionChanged=selectedDraft.placeSelectionChanged
            currentFix=null;currentFixRequired=false;selectedSavedId=null;selectedSavedName=null;saveReusable=false;choosing=false;error=null
        }
        return
    }
    VisitEditorFrame(inline,inlineBackLabel,onDismissRequest=::cancel,title={Text(if(visit!=null) stringResource(R.string.visit_edit) else stringResource(if(mode==ManualVisitMode.current) {if(seed!=null) R.string.visit_link_check_in else R.string.map_save_current} else R.string.map_add_past))},text={
        Column(Modifier.fillMaxWidth().semantics {testTagsAsResourceId=true}.testTag("visit-editor-fields").verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
            val fieldColors=wdTextFieldColors()
            VisitEditorSection(stringResource(R.string.redesign_visit_section_where)) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(name,{name=it;source="userEntered";sourceDistance=null;placeSelectionChanged=false},label={Text(stringResource(R.string.visit_name))},enabled=!busy,colors=fieldColors,textStyle=WdType.serifBody,modifier=Modifier.fillMaxWidth().testTag("visit-name"))
                    OutlinedTextField(address,{address=it;source="userEntered";sourceDistance=null;placeSelectionChanged=false},label={Text(stringResource(R.string.visit_address))},enabled=!busy,colors=fieldColors,modifier=Modifier.fillMaxWidth().testTag("visit-address"))
                }
                WdDivider()
                WdNavRow(stringResource(R.string.visit_choose_location),icon=Icons.Outlined.Map,enabled=!busy) {focus.clearFocus();keyboard?.hide();choosing=true}
                WdNavRow(stringResource(R.string.visit_choose_saved),icon=Icons.Outlined.BookmarkBorder,enabled=!busy) {focus.clearFocus();keyboard?.hide();savedChooser=true}
                WdNavRow(stringResource(R.string.nearby_title),Modifier.testTag("visit-nearby"),icon=Icons.Outlined.Storefront,enabled=!busy && !expiredFix,divider=visit==null && mode==ManualVisitMode.current) {
                    focus.clearFocus();keyboard?.hide()
                    nearbyOrigin=try {Coordinate(latitude.trim().replace(',','.').toDouble(),longitude.trim().replace(',','.').toDouble())} catch(_:IllegalArgumentException) {error=context.getString(R.string.visit_error_coordinate);null}
                }
                if(visit==null && mode==ManualVisitMode.current) {
                    WdNavRow(stringResource(R.string.visit_find_current),icon=Icons.Outlined.MyLocation,enabled=!busy,divider=currentFixRequired) {focus.clearFocus();keyboard?.hide();locating=true}
                    if(currentFixRequired) WdRow(divider=false) {
                        if(expiredFix) Icon(Icons.Outlined.ErrorOutline,null,Modifier.size(20.dp),tint=wd.inkRust) else WdLiveDot()
                        Text(stringResource(if(expiredFix) R.string.visit_fix_expired else R.string.visit_fix_ready),Modifier.weight(1f),style=MaterialTheme.typography.bodyMedium,color=if(expiredFix) wd.inkRust else wd.textPrimary)
                    }
                }
            }
            VisitEditorSection(stringResource(R.string.redesign_visit_section_coordinates)) {
                Column(Modifier.padding(12.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(latitude,{latitude=it;source="userEntered";sourceDistance=null;placeSelectionChanged=false;currentFix=null;currentFixRequired=false;selectedSavedId=null;selectedSavedName=null;saveReusable=false},label={Text(stringResource(R.string.visit_latitude))},enabled=!busy,colors=fieldColors,textStyle=WdType.monoBody,modifier=Modifier.fillMaxWidth().testTag("visit-latitude"))
                    OutlinedTextField(longitude,{longitude=it;source="userEntered";sourceDistance=null;placeSelectionChanged=false;currentFix=null;currentFixRequired=false;selectedSavedId=null;selectedSavedName=null;saveReusable=false},label={Text(stringResource(R.string.visit_longitude))},enabled=!busy,colors=fieldColors,textStyle=WdType.monoBody,modifier=Modifier.fillMaxWidth().testTag("visit-longitude"))
                }
            }
            VisitEditorSection(stringResource(R.string.redesign_visit_section_when)) {
                Column(Modifier.padding(horizontal=16.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    VisitDateTimeControl(stringResource(R.string.visit_arrived),Instant.ofEpochMilli(arrivalMillis),enabled=!busy,modifier=Modifier.testTag("visit-arrival-time")) {arrivalMillis=it.toEpochMilli()}
                    if(visit!=null || mode==ManualVisitMode.current) Row(verticalAlignment=Alignment.CenterVertically) {
                        Checkbox(stillHere,{stillHere=it},enabled=!busy,colors=CheckboxDefaults.colors(checkedColor=wd.accent,uncheckedColor=wd.textMuted,checkmarkColor=wd.surface),modifier=Modifier.semantics {contentDescription=context.getString(R.string.visit_still_here)})
                        Text(stringResource(R.string.visit_still_here),style=MaterialTheme.typography.bodyLarge,color=if(busy) wd.textMuted else wd.textPrimary)
                    }
                    else Text(stringResource(R.string.visit_past_times),style=WdType.caption2,color=wd.textMuted)
                    if(!stillHere) VisitDateTimeControl(stringResource(R.string.visit_departed),Instant.ofEpochMilli(departureMillis),enabled=!busy,modifier=Modifier.testTag("visit-departure-time")) {departureMillis=it.toEpochMilli()}
                }
            }
            VisitEditorSection(stringResource(R.string.redesign_visit_section_notes)) {
                OutlinedTextField(notes,{notes=it},label={Text(stringResource(R.string.visit_notes))},enabled=!busy,colors=fieldColors,textStyle=WdType.serifBody,modifier=Modifier.fillMaxWidth().padding(12.dp).testTag("visit-notes"))
            }
            if(visit==null) VisitEditorSection(stringResource(R.string.redesign_visit_section_reuse)) {
                Column(Modifier.padding(horizontal=16.dp,vertical=14.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    if(selectedSavedId!=null) Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Bookmark,null,Modifier.size(18.dp),tint=wd.accent)
                        Text(stringResource(R.string.visit_using_saved,selectedSavedName.orEmpty()),style=MaterialTheme.typography.bodyMedium,color=wd.textPrimary)
                    }
                    else {
                        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
                            Text(stringResource(R.string.visit_reuse),Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge,color=wd.textPrimary)
                            Switch(saveReusable,{saveReusable=it},enabled=!busy,colors=wdSwitchColors(),modifier=Modifier.semantics {contentDescription=context.getString(R.string.visit_reuse_accessibility)})
                        }
                        if(saveReusable) {
                            Choice(stringResource(R.string.visit_radius),"$reusableRadius m",listOf("50 m","100 m","150 m","250 m"),!busy) {reusableRadius=it.substringBefore(' ').toInt()}
                            Text(stringResource(R.string.visit_reuse_description),style=WdType.caption2,color=wd.textMuted)
                            if(reusableCandidate==null) Text(stringResource(R.string.visit_reuse_prepare),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
                            else if(readyReuse==null) {LinearProgressIndicator(Modifier.fillMaxWidth(),color=wd.accent,trackColor=wd.dialTrack);Text(stringResource(R.string.visit_reuse_reviewing),style=WdType.caption,color=wd.textMuted)}
                            else Text(readyReuse.plan.original?.let {stringResource(R.string.visit_reuse_update,it.name)} ?: stringResource(R.string.visit_reuse_create,readyReuse.plan.value.name),style=MaterialTheme.typography.bodyMedium,color=wd.textPrimary)
                        }
                    }
                }
            }
            error?.let {
                val shape=RoundedCornerShape(14.dp)
                Row(Modifier.fillMaxWidth().clip(shape).background(wd.inkRust.copy(alpha=if(wd.dark) 0.12f else 0.06f)).border(1.dp,wd.inkRust.copy(alpha=0.35f),shape).padding(14.dp),
                    horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Icon(Icons.Outlined.ErrorOutline,null,Modifier.size(20.dp),tint=wd.inkRust)
                    Text(it,style=MaterialTheme.typography.bodyMedium,color=wd.inkRust,modifier=Modifier.weight(1f).testTag("visit-editor-error"))
                }
            }
            if(busy) LinearProgressIndicator(Modifier.fillMaxWidth(),color=wd.accent,trackColor=wd.dialTrack)
            if(visit!=null) VisitEditorSection(stringResource(R.string.redesign_visit_section_more),framed=false) {
                Column(verticalArrangement=Arrangement.spacedBy(10.dp)) {
                    Text(stringResource(R.string.visit_remember_reviewed),style=WdType.caption2,color=wd.textMuted)
                    if(onShowOnMap!=null) WdButton(stringResource(R.string.visit_view_map),{viewOnMap()},Modifier.testTag("visit-view-map"),enabled=!busy,icon=Icons.Outlined.Map)
                    val unknownTitle=stringResource(R.string.calendar_unknown_place)
                    Text(stringResource(R.string.calendar_saved_visit),style=WdType.caption2,color=wd.textMuted)
                    CalendarEventButton(visit,enabled=!busy,makeDraft={now->CalendarEventDraft.visit(visit,unknownTitle,now)})
                    visit.takeIf {it.source==VisitSource.manual && it.departedAt==null}?.let {stored->
                        Text(stringResource(R.string.visit_checkout_description),style=WdType.caption2,color=wd.textMuted)
                        WdButton(stringResource(R.string.visit_checkout),{
                            focus.clearFocus();keyboard?.hide()
                            val now=Instant.now()
                            persist(stored.copy(departedAt=now,departureInferred=false,updatedAt=now),allowOverlap=true)
                        },Modifier.testTag("visit-checkout"),enabled=!busy,icon=Icons.AutoMirrored.Outlined.Logout)
                    }
                    if(visit.confirmation==Confirmation.unconfirmed) WdButton(stringResource(R.string.visit_confirm),{submit(true)},enabled=!busy,icon=Icons.Outlined.Check)
                    if(visit.confirmation==Confirmation.corrected && visit.originalCoordinate!=null) WdButton(stringResource(R.string.visit_undo),{undoing=true},enabled=!busy,icon=Icons.AutoMirrored.Outlined.Undo)
                }
            }
            if(delete!=null) WdButton(stringResource(R.string.visit_delete_action),{deleting=true},enabled=!busy,emphasis=WdEmphasis.destructive,icon=Icons.Outlined.DeleteOutline)
        }
    },confirmButton={
        val enabled=!busy && !expiredFix && (!saveReusable || readyReuse!=null)
        if(inline) WdButton(stringResource(R.string.visit_save),{submit()},Modifier.testTag("visit-save"),emphasis=WdEmphasis.primary,enabled=enabled,busy=busy)
        else TextButton(enabled=enabled,onClick={submit()},modifier=Modifier.testTag("visit-save")) {Text(stringResource(R.string.visit_save))}
    },dismissButton={
        if(inline) WdButton(stringResource(R.string.visit_cancel),::cancel,enabled=!busy)
        else TextButton(enabled=!busy,onClick=::cancel) {Text(stringResource(R.string.visit_cancel))}
    })
    fun selectBusiness(suggestion:NearbyPlaceSuggestion) {
        val coordinate=runCatching {Coordinate(latitude.trim().replace(',','.').toDouble(),longitude.trim().replace(',','.').toDouble())}.getOrNull()
        val selected=VisitDraft(id,name,address,notes,coordinate,Instant.ofEpochMilli(arrivalMillis),if(stillHere) null else Instant.ofEpochMilli(departureMillis),source,sourceDistance,placeSelectionChanged).selectNearby(suggestion,"geoapify")
        val point=checkNotNull(selected.coordinate)
        name=selected.name;address=selected.address;latitude=point.latitude.toString();longitude=point.longitude.toString()
        source=selected.placeSource;sourceDistance=selected.placeDistanceMeters;placeSelectionChanged=selected.placeSelectionChanged
        currentFix=null;currentFixRequired=false;selectedSavedId=null;selectedSavedName=null;saveReusable=false;nearbyOrigin=null;locating=false;error=null
    }
    if(locating) CurrentVisitLocationDialog(currentLocationSource,{locating=false},places,selectSaved={place->
        name=place.name;address=place.address.orEmpty();latitude=place.coordinate.latitude.toString();longitude=place.coordinate.longitude.toString()
        source="userEntered";sourceDistance=null;placeSelectionChanged=false;currentFix=null;currentFixRequired=false;selectedSavedId=place.id;selectedSavedName=place.name;saveReusable=false;locating=false;error=null
    },nearby=nearbyLookup,networkAllowed=nearbyNetworkAllowed,nearbyAvailable=nearbyAvailable,selectBusiness=::selectBusiness,readSavedPlaces={graph.history.dao.places().map {it.value}},metric=nearbySettings?.metric ?: true) {fix->
        // Current GPS retains user-entered metadata, unlike an explicitly selected business.
        latitude=fix.coordinate.latitude.toString();longitude=fix.coordinate.longitude.toString();currentFix=fix;currentFixRequired=true
        source="userEntered";sourceDistance=null;placeSelectionChanged=false;selectedSavedId=null;selectedSavedName=null;saveReusable=false;locating=false;error=null
    }
    nearbyOrigin?.let {origin->
        NearbyPlacesDialog(origin,nearbyLookup,nearbyNetworkAllowed,nearbyAvailable,{nearbyOrigin=null},select=::selectBusiness,
            currentFix=if(currentFixRequired) currentFix else null,metric=nearbySettings?.metric ?: true)
    }
    if(savedChooser) SavedPlaceChooser(graph,{savedChooser=false}) {place->
        name=place.name;address=place.address.orEmpty();latitude=place.coordinate.latitude.toString();longitude=place.coordinate.longitude.toString();source="userEntered";sourceDistance=null;placeSelectionChanged=false;currentFix=null;currentFixRequired=false;selectedSavedId=place.id;selectedSavedName=place.name;saveReusable=false;savedChooser=false;error=null
    }
    overlapReview?.let {ticket->
        WorkspaceAlertDialog(onDismissRequest={if(overlapReview==ticket) overlapReview=null},modifier=Modifier.testTag("visit-overlap-dialog"),
            title={Text(stringResource(R.string.visit_overlap_title))},text={
                Column(Modifier.verticalScroll(rememberScrollState()).testTag("visit-overlap-body")) {
                    val name=ticket.conflicting.name?.trim()?.takeIf {it.isNotEmpty()}
                        ?: ticket.conflicting.address?.trim()?.takeIf {it.isNotEmpty()} ?: stringResource(R.string.visit_overlap_unnamed)
                    Text(stringResource(R.string.visit_overlap_message,name))
                }
            },confirmButton={TextButton(enabled=!busy,onClick={
                if(overlapReview==ticket) {
                    overlapReview=null
                    if(interactive() && currentKey()==ticket.key && !busy) {
                        if(currentFixRequired && currentFix?.isUsable(android.os.SystemClock.elapsedRealtime())!=true) error=context.getString(R.string.visit_error_expired)
                        else persist(ticket.value,ticket.key.reuse,ticket.rememberPlace,true,ticket.key)
                    }
                }
            },modifier=Modifier.testTag("visit-overlap-save")) {Text(stringResource(R.string.visit_overlap_save))}},
            dismissButton={TextButton(onClick={if(overlapReview==ticket) overlapReview=null},modifier=Modifier.testTag("visit-overlap-cancel")) {Text(stringResource(R.string.visit_cancel))}})
    }
    mapReview?.let {ticket->
        WorkspaceAlertDialog(onDismissRequest={if(mapReview==ticket) mapReview=null},title={Text(stringResource(R.string.visit_discard_title))},
            text={Text(stringResource(R.string.visit_discard_map_body))},
            confirmButton={TextButton(enabled=!busy && ticket==saveKey,onClick={if(mapReview==ticket && currentKey()==ticket) viewOnMap(ticket)},modifier=Modifier.testTag("visit-discard-view-map")) {Text(stringResource(R.string.visit_discard_map_confirm))}},
            dismissButton={TextButton(onClick={if(mapReview==ticket) mapReview=null}) {Text(stringResource(R.string.visit_keep_editing))}})
    }
    if(discarding) WorkspaceAlertDialog(onDismissRequest={discarding=false},title={Text(stringResource(R.string.visit_discard_title))},confirmButton={TextButton(onClick=dismiss) {Text(stringResource(R.string.visit_discard),color=wd.inkRust)}},dismissButton={TextButton(onClick={discarding=false}) {Text(stringResource(R.string.visit_keep_editing))}})
    if(undoing) WorkspaceAlertDialog(onDismissRequest={undoing=false},title={Text(stringResource(R.string.visit_restore_title))},text={Text(stringResource(R.string.visit_restore_description))},confirmButton={TextButton(enabled=!busy,onClick={undoing=false;visit?.let {persist(it.undoCorrection(Instant.now()))}}) {Text(stringResource(R.string.visit_restore))}},dismissButton={TextButton(onClick={undoing=false}) {Text(stringResource(R.string.visit_cancel))}})
    if(deleting) WorkspaceAlertDialog(onDismissRequest={if(!busy) deleting=false},title={Text(stringResource(R.string.visit_delete_title))},text={Text(stringResource(R.string.visit_delete_description))},confirmButton={TextButton(enabled=!busy,onClick={scope.launch {
        busy=true;try {delete?.invoke();dismiss()} catch(e:Exception) {if(e is CancellationException) throw e;error=failure(e,R.string.visit_error_delete);deleting=false} finally {busy=false}
    }}) {Text(stringResource(R.string.visit_delete),color=if(busy) wd.textMuted else wd.inkRust)}},dismissButton={TextButton(enabled=!busy,onClick={deleting=false}) {Text(stringResource(R.string.visit_cancel))}})
}

/** A tracked mono caption over a hairline card, as iOS groups the visit detail into TECard sections. */
@Composable private fun VisitEditorSection(title:String,framed:Boolean=true,content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(),style=WdType.eyebrow,color=wd.textMuted,modifier=Modifier.padding(start=4.dp).semantics {heading()})
        if(framed) WdCard(content=content) else Column(content=content)
    }
}
