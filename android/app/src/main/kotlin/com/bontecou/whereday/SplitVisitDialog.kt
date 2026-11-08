package com.bontecou.whereday

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import com.bontecou.whereday.data.VisitChanged
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import java.time.Instant
import java.util.UUID

/**
 * iOS SplitVisitSheet: neighbouring storefronts are often detected as one stop. The user picks when they
 * moved on and where they went next; the stop becomes two back-to-back reviewed visits.
 */
@Composable internal fun SplitVisitDialog(
    graph:AppGraph,visit:Visit,dismiss:()->Unit,split:suspend (Instant,SplitPlace)->Unit,
    nearbyLookup:PlaceLookupService=graph.placeLookup,
    nearbyAvailable:Boolean=com.bontecou.whereday.platform.GeoapifyPlaceLookup.available,
) {
    val range=VisitSplit.range(visit) ?: run {dismiss();return}
    val context=LocalContext.current
    val settings by graph.preferences.settings.collectAsState(initial=null)
    val networkAllowed=remember(graph) {graph.preferences.settings.map {it.networkGeocoding}}
    var splitMillis by rememberSaveable {mutableLongStateOf(VisitSplit.suggestedTime(visit)!!.toEpochMilli())}
    var name by rememberSaveable {mutableStateOf("")}
    var address by rememberSaveable {mutableStateOf<String?>(null)}
    var latitude by rememberSaveable {mutableStateOf<Double?>(null)}
    var longitude by rememberSaveable {mutableStateOf<Double?>(null)}
    var source by rememberSaveable {mutableStateOf("userEntered")}
    var distance by rememberSaveable {mutableStateOf<Double?>(null)}
    var nearby by remember {mutableStateOf(false)}
    var choosingMap by remember {mutableStateOf(false)}
    var choosingSaved by remember {mutableStateOf(false)}
    var busy by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    val secondId=rememberSaveable {UUID.randomUUID().toString()}
    val scope=rememberCoroutineScope()
    val scroll=rememberScrollState()
    // The error sits under the place choices; bring it into view so a refused split never looks like a dead tap.
    LaunchedEffect(error) {if(error!=null) scroll.animateScrollTo(scroll.maxValue)}
    val splitAt=Instant.ofEpochMilli(splitMillis)
    val coordinate=if(latitude!=null && longitude!=null) Coordinate(latitude!!,longitude!!) else null
    val firstName=visit.name?.trim()?.takeIf {it.isNotEmpty()} ?: visit.address?.trim()?.takeIf {it.isNotEmpty()}
        ?: stringResource(R.string.calendar_unknown_place)

    fun choose(placeName:String?,placeAddress:String?,point:Coordinate,placeSource:String,meters:Double?) {
        name=placeName?.trim().orEmpty();address=placeAddress?.trim()?.ifEmpty {null}
        latitude=point.latitude;longitude=point.longitude;source=placeSource;distance=meters;error=null
    }
    fun message(problem:VisitSplitProblem)=context.getString(when(problem) {
        VisitSplitProblem.time->R.string.visit_split_error_time
        VisitSplitProblem.placeName->R.string.visit_split_error_name
        VisitSplitProblem.coordinate->R.string.visit_error_coordinate
        VisitSplitProblem.samePlace->R.string.visit_split_error_same
    })
    fun submit() {
        if(busy) return
        val point=coordinate ?: run {error=message(VisitSplitProblem.coordinate);return}
        // A typed name drops provider provenance, as it does in the visit editor.
        val place=SplitPlace(name,address,point,source,distance)
        try {VisitSplit.split(visit,splitAt,place,secondId,Instant.now())}
        catch(e:InvalidVisitSplit) {error=message(e.problem);return}
        busy=true;error=null
        scope.launch {
            try {split(splitAt,place);dismiss()}
            catch(e:Exception) {
                if(e is CancellationException) throw e
                error=when(e) {
                    is InvalidVisitSplit->message(e.problem)
                    is VisitChanged->context.getString(R.string.visit_error_changed)
                    else->context.getString(R.string.visit_error_save)
                }
            } finally {busy=false}
        }
    }

    if(choosingMap) {
        PlaceLocationPicker(coordinate ?: visit.coordinate,{choosingMap=false}) {selected->
            choose(selected.name.takeIf {selected.replaceMetadata} ?: name,selected.address.takeIf {selected.replaceMetadata} ?: address,
                selected.coordinate,if(selected.replaceMetadata) selected.placeSource else "userEntered",null)
            choosingMap=false
        }
        return
    }

    WorkspaceAlertDialog(onDismissRequest={if(!busy) dismiss()},modifier=Modifier.testTag("visit-split-dialog"),
        title={Text(stringResource(R.string.visit_split_title))},
        text={
            Column(Modifier.fillMaxWidth().semantics {testTagsAsResourceId=true}.verticalScroll(scroll),verticalArrangement=Arrangement.spacedBy(14.dp)) {
                Text(stringResource(R.string.visit_split_explanation),style=WdType.serifBody,color=wd.textMuted)
                // The time control carries its own "Moved on at" label, so this card needs no section heading.
                WdCard {
                    Box(Modifier.padding(horizontal=16.dp,vertical=12.dp)) {
                        VisitDateTimeControl(stringResource(R.string.visit_split_moved_on),splitAt,enabled=!busy,modifier=Modifier.testTag("visit-split-time")) {
                            splitMillis=it.coerceIn(range.start,range.endInclusive).toEpochMilli()
                            error=if(it !in range) context.getString(R.string.visit_split_error_time) else null
                        }
                    }
                }
                WdCard {
                    SplitPart(1,firstName,visit.arrivedAt,splitAt,divider=true)
                    SplitPart(2,name.trim().ifEmpty {null},splitAt,visit.departedAt!!,divider=false)
                }
                SplitSection(stringResource(R.string.visit_split_where_next)) {
                    WdNavRow(stringResource(R.string.nearby_title),Modifier.testTag("visit-split-nearby"),icon=Icons.Outlined.Storefront,enabled=!busy) {nearby=true}
                    WdNavRow(stringResource(R.string.visit_choose_location),Modifier.testTag("visit-split-map"),icon=Icons.Outlined.Map,enabled=!busy) {choosingMap=true}
                    WdNavRow(stringResource(R.string.visit_choose_saved),Modifier.testTag("visit-split-saved"),icon=Icons.Outlined.BookmarkBorder,enabled=!busy,divider=coordinate!=null) {choosingSaved=true}
                    if(coordinate!=null) OutlinedTextField(name,{name=it;source="userEntered";distance=null;error=null},
                        label={Text(stringResource(R.string.visit_split_second_name))},enabled=!busy,singleLine=true,colors=wdTextFieldColors(),textStyle=WdType.serifBody,
                        modifier=Modifier.fillMaxWidth().padding(12.dp).testTag("visit-split-name"))
                }
                Text(stringResource(R.string.visit_split_saves_two),style=WdType.caption2,color=wd.textMuted)
                error?.let {
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                        Icon(Icons.Outlined.ErrorOutline,null,Modifier.size(20.dp),tint=wd.inkRust)
                        Text(it,style=MaterialTheme.typography.bodyMedium,color=wd.inkRust,modifier=Modifier.weight(1f).testTag("visit-split-error"))
                    }
                }
                if(busy) LinearProgressIndicator(Modifier.fillMaxWidth(),color=wd.accent,trackColor=wd.dialTrack)
            }
        },
        confirmButton={TextButton(enabled=!busy && coordinate!=null && name.isNotBlank(),onClick=::submit,modifier=Modifier.testTag("visit-split-confirm")) {
            Text(stringResource(R.string.visit_split_action))
        }},
        dismissButton={TextButton(enabled=!busy,onClick=dismiss,modifier=Modifier.testTag("visit-split-cancel")) {Text(stringResource(R.string.visit_cancel))}})

    if(nearby) NearbyPlacesDialog(visit.coordinate,nearbyLookup,networkAllowed,nearbyAvailable,{nearby=false},select={suggestion->
        choose(suggestion.name,suggestion.address,suggestion.coordinate,"geoapify",suggestion.distanceMeters);nearby=false
    },metric=settings?.metric ?: true)
    if(choosingSaved) SavedPlaceChooser(graph,{choosingSaved=false}) {place->
        choose(place.name,place.address,place.coordinate,"userEntered",null);choosingSaved=false
    }
}

@Composable private fun SplitSection(title:String,content:@Composable ColumnScope.()->Unit) {
    Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(6.dp)) {
        Text(title.uppercase(),style=WdType.eyebrow,color=wd.textMuted,modifier=Modifier.padding(start=4.dp).semantics {heading()})
        WdCard(content=content)
    }
}

@Composable private fun SplitPart(index:Int,name:String?,start:Instant,end:Instant,divider:Boolean) {
    val label=name ?: stringResource(R.string.visit_split_choose_below)
    val range=stringResource(R.string.visit_split_range,journalClock(start),journalClock(end))
    val duration=journalDuration(start,end)
    val description=stringResource(R.string.visit_split_part_description,index,label,range,duration)
    WdRow(Modifier.clearAndSetSemantics {contentDescription=description}.testTag("visit-split-part-$index"),divider=divider) {
        Text("$index",style=WdType.monoBody,color=wd.textMuted)
        Column(Modifier.weight(1f)) {
            Text(label,style=WdType.serifBody,color=if(name==null) wd.textMuted else wd.textPrimary)
            Text(range,style=WdType.caption2,color=wd.textMuted)
        }
        Text(duration,style=WdType.serifBody,color=wd.textPrimary)
    }
}
