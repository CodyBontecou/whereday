package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Search
import androidx.compose.ui.Alignment
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import java.text.NumberFormat
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.data.SavedPlacesChanged
import com.bontecou.whereday.export.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import java.io.ByteArrayOutputStream
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

private class PlaceReadError(val resource:Int):IllegalArgumentException()
private data class PlaceReview(val preview:SavedPlaceImportPreview,val existing:List<SavedPlace>,val policy:SavedPlaceDuplicatePolicy,val plan:SavedPlaceImportPlan)

/** The Places library: saved places by visits, recency or name, plus frequent spots not saved yet.
 *  [fromSettings] keeps a back link to Settings; the Places tab has none. */
@Composable fun SavedPlacesScreen(graph:AppGraph,places:List<SavedPlace>,metric:Boolean=true,openVisit:(Visit)->Unit={},fromSettings:Boolean=false,back:()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    fun number(value:Double)=NumberFormat.getNumberInstance(locale).apply {maximumFractionDigits=2}.format(value)
    var query by rememberSaveable {mutableStateOf("")}
    var adding by rememberSaveable {mutableStateOf(false)}
    var editing by rememberSaveable(stateSaver=SavedPlaceSnapshot.Saver) {mutableStateOf<SavedPlace?>(null)}
    // The saved place whose detail is open; the detail always reads the live row by identity.
    var detailId by rememberSaveable {mutableStateOf<String?>(null)}
    // A frequent unsaved spot's detail, by grid key; and the spot being saved from it.
    var suggestionId by rememberSaveable {mutableStateOf<String?>(null)}
    var savingSpot by rememberSaveable {mutableStateOf<String?>(null)}
    var filterName by rememberSaveable {mutableStateOf(PlacesFilter.mostVisited.name)}
    val filter=PlacesFilter.valueOf(filterName)
    var preview by remember {mutableStateOf<SavedPlaceImportPreview?>(null)}
    var policyName by rememberSaveable {mutableStateOf(SavedPlaceDuplicatePolicy.keepExisting.name)}
    val policy=SavedPlaceDuplicatePolicy.valueOf(policyName)
    var busy by remember {mutableStateOf(false)}
    var job by remember {mutableStateOf<Job?>(null)}
    var message by remember {mutableStateOf<PlaceUiText?>(null)}
    var confirmation by remember {mutableStateOf<PlaceReview?>(null)}
    fun leave() {if(busy) job?.cancel() else if(preview!=null) {preview=null;message=null} else back()}
    BackHandler(onBack=::leave)
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->
        if(uri!=null) job=scope.launch {
            busy=true;message=null
            try {
                if(uri.scheme!="content") throw PlaceReadError(R.string.saved_csv_choose)
                preview=withContext(Dispatchers.IO) {
                    val bytes=context.contentResolver.openInputStream(uri)?.use {input ->
                        val out=ByteArrayOutputStream();val buffer=ByteArray(64*1024)
                        while(true) {ensureActive();val count=input.read(buffer);if(count<0) break
                            if(out.size()+count>SavedPlaceCsvImporter.maximumFileSize) throw InvalidSavedPlaceCsv(SavedPlaceCsvIssue(SavedPlaceCsvProblem.fileSize));out.write(buffer,0,count)}
                        out.toByteArray()
                    } ?: throw PlaceReadError(R.string.saved_csv_open)
                    val coroutine=currentCoroutineContext();SavedPlaceCsvImporter.parse(bytes) {coroutine.ensureActive()}
                }
            } catch(e:Exception) {if(e is CancellationException) {message=PlaceUiText(R.string.saved_csv_canceled);throw e};message=when(e) {is InvalidSavedPlaceCsv->savedPlaceCsvText(e.issue);is PlaceReadError->PlaceUiText(e.resource);else->PlaceUiText(R.string.saved_csv_read_error)}}
            finally {busy=false}
        }
    }
    val currentPreview=preview
    val review by produceState<PlaceReview?>(null,currentPreview,places,policy) {
        value=null
        if(currentPreview!=null) value=PlaceReview(currentPreview,places,policy,graph.savedPlaces.preview(currentPreview.places,places,policy))
    }
    if(currentPreview!=null) {
        LazyColumn(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("saved-places-content"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {TextButton(onClick=::leave) {Text(stringResource(if(busy) R.string.saved_csv_cancel else R.string.saved_back_library))}}
            item {Text(stringResource(R.string.saved_csv_preview),style=MaterialTheme.typography.headlineSmall)}
            item {Text(stringResource(R.string.saved_csv_counts,pluralStringResource(R.plurals.saved_csv_valid_rows,currentPreview.places.size,currentPreview.places.size),pluralStringResource(R.plurals.saved_csv_invalid_rows,currentPreview.invalidRows.size,currentPreview.invalidRows.size)),Modifier.testTag("saved-csv-counts"))}
            item {
                val keep=stringResource(R.string.saved_csv_keep);val update=stringResource(R.string.saved_csv_update)
                Choice(stringResource(R.string.saved_csv_policy),policy.name,SavedPlaceDuplicatePolicy.entries.map {it.name},!busy,labelFor={if(it==SavedPlaceDuplicatePolicy.keepExisting.name) keep else update}) {policyName=it}
            }
            item {Text(stringResource(R.string.saved_csv_matching),style=MaterialTheme.typography.bodySmall)}
            val ready=review?.takeIf {it.preview===currentPreview && it.policy==policy && it.existing==places}
            if(ready!=null) item {Text(savedPlacePlanText(ready.plan.summary),modifier=Modifier.testTag("saved-csv-plan"),style=MaterialTheme.typography.titleMedium)}
            else item {LinearProgressIndicator(Modifier.fillMaxWidth())}
            item {Button(enabled=!busy && ready!=null && currentPreview.places.isNotEmpty(),onClick={confirmation=ready}) {Text(stringResource(R.string.saved_csv_import_valid))}}
            message?.let {item {Text(it.text(),color=MaterialTheme.colorScheme.error)}}
            if(busy) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
            val entries=ready?.plan?.entries?.associateBy {it.place.id}.orEmpty()
            items(currentPreview.rows,key={it.rowNumber}) {row ->
                val place=row.place
                Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(stringResource(R.string.saved_csv_row_title,row.rowNumber,row.name.ifEmpty {stringResource(R.string.saved_csv_unnamed)}),style=MaterialTheme.typography.titleMedium)
                    if(place==null) row.issues.forEach {Text(savedPlaceCsvText(it).text(),color=MaterialTheme.colorScheme.error)}
                    else {
                        entries[place.id]?.let {Text(stringResource(when(it.action) {SavedPlaceImportAction.add->R.string.saved_csv_action_add;SavedPlaceImportAction.update->R.string.saved_csv_action_update;SavedPlaceImportAction.skip->R.string.saved_csv_action_skip}))}
                        Text(stringResource(R.string.saved_csv_coordinate,String.format(locale,"%.6f",place.coordinate.latitude),String.format(locale,"%.6f",place.coordinate.longitude),number(place.radius)))
                        place.address?.let {Text(it)}
                    }
                }}
            }
        }
        confirmation?.let {confirmed ->WorkspaceAlertDialog(onDismissRequest={if(busy) job?.cancel() else confirmation=null},title={Text(stringResource(R.string.saved_csv_confirm_title))},text={
            val summary=confirmed.plan.summary
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.saved_csv_confirm_body,savedPlacePlanText(summary),confirmed.preview.invalidRows.size))
                if(busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(R.string.saved_csv_importing))}
            }
        },confirmButton={TextButton(enabled=!busy,onClick={job=scope.launch {
            busy=true;message=null
            try {
                val summary=graph.savedPlaces.import(confirmed.preview.places,confirmed.existing,confirmed.policy)
                preview=null;confirmation=null;message=PlaceUiText(R.string.saved_csv_result,listOf(summary.added,summary.updated,summary.skipped))
            } catch(e:Exception) {confirmation=null;if(e is CancellationException) {message=PlaceUiText(R.string.saved_csv_canceled);throw e};message=PlaceUiText(if(e is SavedPlacesChanged) R.string.saved_changed else R.string.saved_csv_import_error)}
            finally {busy=false}
        }}) {Text(stringResource(R.string.saved_csv_commit))}},dismissButton={TextButton(onClick={if(busy) job?.cancel() else confirmation=null}) {Text(stringResource(if(busy) R.string.saved_csv_cancel else R.string.visit_cancel))}})}
        return
    }
    val ink=LocalWheredayInk.current
    val visitRows by graph.history.dao.observeVisits().collectAsStateWithLifecycle(emptyList())
    val visits=remember(visitRows) {visitRows.map {it.value}}
    val unnamed=stringResource(R.string.timeline_visit)
    // A–Z in the reader's collation; inks follow this order, as iOS follows its library order.
    val ordered=remember(places,locale) {val collator=Collator.getInstance(locale);places.sortedWith {a,b->collator.compare(a.name,b.name).takeIf {it!=0} ?: a.id.compareTo(b.id)}}
    val library by produceState<PlacesLibrary?>(null,ordered,visits) {
        value=withContext(Dispatchers.Default) {PlacesLibrary(ordered,visits) {it.name?.trim()?.takeIf(String::isNotEmpty) ?: it.address?.trim()?.takeIf(String::isNotEmpty) ?: unnamed}}
    }
    val now=remember(visits) {java.time.Instant.now()};val zone=remember {java.time.ZoneId.systemDefault()}
    val actions=remember(graph) {PlaceDetailActions({change,expected->graph.savedPlaces.save(change,expected)},graph.savedPlaces::mergePlan) {plan,kept,removed->graph.savedPlaces.merge(plan,kept,removed)}}
    val editor:@Composable ()->Unit={
        if(adding || editing!=null) {
            val expected=editing
            key(expected!=null,expected?.id) {
                PlaceEditor(expected,{adding=false;editing=null},{graph.savedPlaces.save(it,expected)},expected?.let {place->{graph.savedPlaces.delete(place);if(detailId==place.id) detailId=null}})
            }
        }
        val spot=savingSpot?.let {id->library?.suggestions?.firstOrNull {it.id==id}}
        if(spot!=null) key(spot.id) {
            // Saving a spot keeps its page open: it turns into the new place's detail.
            PlaceEditor(null,{savingSpot=null},{saved->graph.savedPlaces.save(saved,null);detailId=saved.id;suggestionId=null;savingSpot=null},null,PlaceEditorPreset(spot.name,spot.coordinate,null,spot.address))
        }
    }
    fun matches(place:SavedPlace):Boolean {
        val key=savedPlaceSearchKey(query.trim(),locale)
        return key.isEmpty() || key in savedPlaceSearchKey(place.name,locale) || key in savedPlaceSearchKey(place.address.orEmpty(),locale)
    }
    val detail:@Composable (Boolean,PlacesLibrary)->Unit={embedded,lib->
        val spot=suggestionId?.let {id->lib.suggestions.firstOrNull {it.id==id}}
        val shown=if(embedded) detailId?.takeIf {id->ordered.any {it.id==id}} ?: lib.sorted(if(filter==PlacesFilter.unsaved) PlacesFilter.mostVisited else filter,::matches).firstOrNull()?.id
            ?: lib.sorted(PlacesFilter.mostVisited).firstOrNull()?.id else detailId
        when {
            spot!=null -> UnsavedPlaceDetail(spot,ordered,visits,now,zone,{suggestionId=null},{savingSpot=it.id},openVisit,embedded)
            embedded && shown==null -> Column(Modifier.fillMaxSize().padding(32.dp).semantics {testTagsAsResourceId=true}.testTag("places-choose"),verticalArrangement=Arrangement.spacedBy(8.dp,Alignment.CenterVertically),horizontalAlignment=Alignment.CenterHorizontally) {
                Text(stringResource(R.string.places_choose_title),style=WheredayType.title2,color=ink.textMuted)
                Text(stringResource(R.string.places_choose_body),style=WheredayType.body,color=ink.textMuted,textAlign=TextAlign.Center)
            }
            else -> key(shown) {PlaceDetailContent(ordered.firstOrNull {it.id==shown},ordered,visits,now,zone,metric,actions,{detailId=null},{editing=it},openVisit,embedded)}
        }
    }
    val lib=library
    BoxWithConstraints(Modifier.fillMaxSize().background(ink.surface)) {
        val twoPane=maxWidth>=600.dp
        if(lib==null) LinearProgressIndicator(Modifier.fillMaxWidth().padding(20.dp))
        else if(twoPane) Row(Modifier.fillMaxSize()) {
            PlacesList(lib,filter,query,busy,message,fromSettings,twoPane=true,selected=suggestionId ?: detailId,
                onQuery={query=it},onFilter={filterName=it.name},onPlace={detailId=it.id;suggestionId=null},onSpot={suggestionId=it.id},onEdit={editing=it},onAdd={adding=true},
                onImport={picker.launch(arrayOf("text/*","application/csv","application/octet-stream"))},onLeave=::leave,matches=::matches,metric=metric,zone=zone,now=now,modifier=Modifier.widthIn(max=420.dp).weight(1f))
            Box(Modifier.width(1.dp).fillMaxHeight().background(ink.border))
            Box(Modifier.weight(1.4f).fillMaxHeight()) {detail(true,lib)}
        } else if(detailId!=null || suggestionId!=null) detail(false,lib)
        else PlacesList(lib,filter,query,busy,message,fromSettings,twoPane=false,selected=null,
            onQuery={query=it},onFilter={filterName=it.name},onPlace={detailId=it.id},onSpot={suggestionId=it.id},onEdit={editing=it},onAdd={adding=true},
            onImport={picker.launch(arrayOf("text/*","application/csv","application/octet-stream"))},onLeave=::leave,matches=::matches,metric=metric,zone=zone,now=now,modifier=Modifier.fillMaxSize())
    }
    editor()
}

@Composable private fun PlacesList(
    library:PlacesLibrary,filter:PlacesFilter,query:String,busy:Boolean,message:PlaceUiText?,fromSettings:Boolean,twoPane:Boolean,selected:String?,
    onQuery:(String)->Unit,onFilter:(PlacesFilter)->Unit,onPlace:(SavedPlace)->Unit,onSpot:(UnsavedPlaceSuggestion)->Unit,onEdit:(SavedPlace)->Unit,onAdd:()->Unit,
    onImport:()->Unit,onLeave:()->Unit,matches:(SavedPlace)->Boolean,metric:Boolean,zone:java.time.ZoneId,now:java.time.Instant,modifier:Modifier,
) {
    val ink=LocalWheredayInk.current
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val large=androidx.compose.ui.platform.LocalDensity.current.fontScale>=1.6f
    val suggestions=library.suggestions
    // The unnamed filter only exists while there are spots to name.
    val shownFilter=if(filter==PlacesFilter.unsaved && suggestions.isEmpty()) PlacesFilter.mostVisited else filter
    LazyColumn(modifier.semantics {testTagsAsResourceId=true}.testTag("saved-places-content"),contentPadding=PaddingValues(bottom=24.dp)) {
        if(fromSettings || busy) item {TextButton(onClick=onLeave,modifier=Modifier.padding(horizontal=8.dp)) {Text(stringResource(if(busy) R.string.saved_csv_cancel else R.string.saved_back_settings),color=ink.accent)}}
        item {
            Row(Modifier.fillMaxWidth().padding(start=24.dp,end=24.dp,top=12.dp),verticalAlignment=Alignment.Bottom,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.places_kicker,library.places.size,library.visitedPlaceCount),style=WheredayType.mono(11,tracking=1.5f),color=ink.textMuted,modifier=Modifier.testTag("places-kicker"))
                    Text(stringResource(R.string.places_title),style=WheredayType.largeTitle,color=ink.textPrimary,modifier=Modifier.semantics {heading()})
                }
                val add=stringResource(R.string.saved_add)
                IconButton(onAdd,Modifier.size(48.dp).background(if(busy) ink.textMuted else ink.accent,CircleShape).semantics {contentDescription=add}.testTag("saved-add"),enabled=!busy) {
                    Icon(Icons.Outlined.Add,null,tint=ink.surface)
                }
            }
        }
        item {
            OutlinedTextField(query,onQuery,placeholder={Text(stringResource(R.string.place_merge_search))},label={Text(stringResource(R.string.visit_saved_search))},singleLine=true,
                leadingIcon={Icon(Icons.Outlined.Search,null,tint=ink.textMuted)},shape=RoundedCornerShape(14.dp),
                modifier=Modifier.fillMaxWidth().padding(start=16.dp,end=16.dp,top=14.dp).testTag("saved-places-search"),trailingIcon={
                    if(query.isNotEmpty()) TextButton(onClick={onQuery("")}) {Text(stringResource(R.string.visit_saved_clear))}
                })
        }
        item {
            Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=12.dp).semantics {selectableGroup()},horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                FilterChip(shownFilter==PlacesFilter.mostVisited,{onFilter(PlacesFilter.mostVisited)},{Text(stringResource(R.string.places_filter_most))},Modifier.testTag("places-filter-mostVisited"))
                FilterChip(shownFilter==PlacesFilter.recent,{onFilter(PlacesFilter.recent)},{Text(stringResource(R.string.places_filter_recent))},Modifier.testTag("places-filter-recent"))
                if(suggestions.isNotEmpty()) FilterChip(shownFilter==PlacesFilter.unsaved,{onFilter(PlacesFilter.unsaved)},{Text(stringResource(R.string.places_filter_unnamed,suggestions.size))},Modifier.testTag("places-filter-unsaved"),
                    leadingIcon={Box(Modifier.size(8.dp).background(ink.inkRust,CircleShape))})
                FilterChip(shownFilter==PlacesFilter.alphabetical,{onFilter(PlacesFilter.alphabetical)},{Text(stringResource(R.string.places_filter_az))},Modifier.testTag("places-filter-alphabetical"))
            }
        }
        message?.let {item {Text(it.text(),Modifier.padding(horizontal=24.dp,vertical=4.dp).testTag("saved-places-message"),color=ink.textPrimary)}}
        if(busy) item {Column(Modifier.padding(horizontal=24.dp)) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(R.string.saved_csv_reading))}}
        if(shownFilter==PlacesFilter.unsaved) {
            val key=savedPlaceSearchKey(query.trim(),locale)
            val spots=suggestions.filter {key.isEmpty() || key in savedPlaceSearchKey(it.name,locale)}
            item {Text(stringResource(R.string.places_unsaved_explain),Modifier.padding(horizontal=24.dp,vertical=4.dp),style=MaterialTheme.typography.bodySmall,color=ink.textMuted)}
            item {PlacesCard {
                if(spots.isEmpty()) Text(stringResource(R.string.saved_no_match),Modifier.padding(vertical=16.dp),color=ink.textMuted)
                spots.forEachIndexed {index,spot->
                    SuggestionRow(spot,now,zone,selected==spot.id && twoPane,busy) {onSpot(spot)}
                    if(index<spots.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(ink.dialTrack))
                }
            }}
        } else if(library.places.isEmpty()) item {PlacesCard {
            Column(Modifier.fillMaxWidth().padding(vertical=20.dp),horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.places_empty_title),style=WheredayType.title3,color=ink.textPrimary)
                Text(stringResource(R.string.saved_empty),style=MaterialTheme.typography.bodyMedium,color=ink.textMuted,textAlign=TextAlign.Center)
                TEActionButton(stringResource(R.string.saved_add),onAdd,Modifier.fillMaxWidth(),TEEmphasis.primary,enabled=!busy)
            }
        }} else {
            val sorted=library.sorted(shownFilter,matches)
            item {PlacesCard {
                if(sorted.isEmpty()) Text(stringResource(R.string.saved_no_match),Modifier.padding(vertical=16.dp).testTag("saved-places-no-match"),color=ink.textMuted)
                sorted.forEachIndexed {index,place->
                    PlaceRow(place,library,zone,now,large,selected==place.id && twoPane,busy,{onEdit(place)}) {onPlace(place)}
                    if(index<sorted.lastIndex) Box(Modifier.fillMaxWidth().height(1.dp).background(ink.dialTrack))
                }
            }}
        }
        item {
            val buttons:@Composable (Modifier)->Unit={item->
                TEActionButton(stringResource(R.string.saved_import_csv),onImport,item.testTag("saved-import"),enabled=!busy)
                if(suggestions.isNotEmpty() && shownFilter!=PlacesFilter.unsaved)
                    TEActionButton(pluralStringResource(R.plurals.places_name_spots,suggestions.size,suggestions.size),{onFilter(PlacesFilter.unsaved)},item.testTag("places-name-spots"))
            }
            if(large) Column(Modifier.padding(start=16.dp,end=16.dp,top=12.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {buttons(Modifier.fillMaxWidth())}
            else Row(Modifier.padding(start=16.dp,end=16.dp,top=12.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {buttons(Modifier.weight(1f))}
        }
        item {Column(Modifier.padding(start=24.dp,end=24.dp,top=14.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.places_footnote),style=WheredayType.mono(11),color=ink.textMuted)
            Text(stringResource(R.string.saved_csv_help),style=WheredayType.mono(11),color=ink.textMuted)
        }}
    }
}

@Composable private fun PlacesCard(content:@Composable ColumnScope.()->Unit) {
    val ink=LocalWheredayInk.current
    Column(Modifier.padding(horizontal=16.dp).fillMaxWidth().background(ink.card,RoundedCornerShape(16.dp)).border(1.dp,ink.border,RoundedCornerShape(16.dp)).padding(horizontal=16.dp,vertical=4.dp),content=content)
}

@Composable private fun PlaceRow(place:SavedPlace,library:PlacesLibrary,zone:java.time.ZoneId,now:java.time.Instant,large:Boolean,selected:Boolean,busy:Boolean,edit:()->Unit,open:()->Unit) {
    val ink=LocalWheredayInk.current
    val visits=library.index.visits(place)
    val latest=visits.maxByOrNull {it.arrivedAt}
    val home=place.kind==SavedPlaceKind.home
    val subtitle=when {
        home && !place.address.isNullOrBlank() -> place.address
        latest!=null && latest.departedAt==null -> stringResource(R.string.places_here_now)
        latest!=null -> stringResource(R.string.places_last,relativeDay(latest.arrivedAt,now,zone))
        !place.address.isNullOrBlank() -> place.address
        else -> stringResource(R.string.places_no_visits)
    }
    val count=if(home) PlacesLibrary.dayCount(visits,zone).let {pluralStringResource(R.plurals.places_day_count,it,it)} else pluralStringResource(R.plurals.place_visit_count,visits.size,visits.size)
    val editLabel=stringResource(R.string.place_edit)
    Row(Modifier.fillMaxWidth().heightIn(min=58.dp).then(if(selected) Modifier.background(ink.surface,RoundedCornerShape(14.dp)).border(1.5.dp,ink.inkRust,RoundedCornerShape(14.dp)) else Modifier)
        .selectable(selected,enabled=!busy,role=Role.Button,onClick=open).semantics(mergeDescendants=true) {customActions=listOf(CustomAccessibilityAction(editLabel) {edit();true})}
        .padding(horizontal=if(selected) 10.dp else 0.dp,vertical=8.dp).testTag("saved-place-row"),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(12.dp).background(ink.placeInk(PlaceVisitIndex.ink(place,library.places)),CircleShape))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(1.dp)) {
            Text(place.name,style=WheredayType.title3,color=ink.textPrimary)
            Text(subtitle.orEmpty(),style=WheredayType.mono(11),color=ink.textMuted,maxLines=if(large) 3 else 1,overflow=TextOverflow.Ellipsis)
        }
        Text(count,style=WheredayType.mono(12),color=if(selected) ink.inkRust else ink.textMuted)
    }
}

@Composable private fun SuggestionRow(spot:UnsavedPlaceSuggestion,now:java.time.Instant,zone:java.time.ZoneId,selected:Boolean,busy:Boolean,open:()->Unit) {
    val ink=LocalWheredayInk.current
    Row(Modifier.fillMaxWidth().heightIn(min=58.dp).then(if(selected) Modifier.background(ink.surface,RoundedCornerShape(14.dp)).border(1.5.dp,ink.inkRust,RoundedCornerShape(14.dp)) else Modifier)
        .selectable(selected,enabled=!busy,role=Role.Button,onClick=open).semantics(mergeDescendants=true) {}.padding(horizontal=if(selected) 10.dp else 0.dp,vertical=8.dp).testTag("places-suggestion"),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(12.dp)) {
        Box(Modifier.size(12.dp).border(1.5.dp,ink.inkRust,CircleShape))
        Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(1.dp)) {
            Text(spot.name,style=WheredayType.title3,color=ink.textPrimary)
            Text(pluralStringResource(R.plurals.places_spot_line,spot.visitCount,spot.visitCount,relativeDay(spot.lastVisit,now,zone)),style=WheredayType.mono(11),color=ink.textMuted)
        }
        Text(stringResource(R.string.places_unsaved_badge),style=WheredayType.mono(11,FontWeight.SemiBold),color=ink.inkRust)
    }
}

/** "today", "yesterday", a weekday this week, then a date, as iOS `relativeDay`. */
@Composable private fun relativeDay(instant:java.time.Instant,now:java.time.Instant,zone:java.time.ZoneId):String {
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    val date=instant.atZone(zone).toLocalDate();val today=now.atZone(zone).toLocalDate()
    val days=java.time.temporal.ChronoUnit.DAYS.between(date,today)
    return when {
        days==0L -> stringResource(R.string.places_today)
        days==1L -> stringResource(R.string.places_yesterday)
        days in 2..6 -> date.dayOfWeek.getDisplayName(java.time.format.TextStyle.SHORT,locale)
        else -> java.time.format.DateTimeFormatter.ofPattern(android.text.format.DateFormat.getBestDateTimePattern(locale,if(date.year==today.year) "MMMd" else "MMMdy"),locale).format(date)
    }
}
