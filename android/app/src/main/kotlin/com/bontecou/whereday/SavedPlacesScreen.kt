package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.outlined.AddLocationAlt
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Add
import androidx.compose.material.icons.outlined.Check
import androidx.compose.material.icons.outlined.Place
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
import androidx.compose.material.icons.outlined.UploadFile
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.font.FontWeight
import java.text.NumberFormat
import androidx.compose.ui.unit.dp
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

@Composable fun SavedPlacesScreen(graph:AppGraph,places:List<SavedPlace>,back:(()->Unit)?,showAddFab:Boolean=true) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    fun number(value:Double)=NumberFormat.getNumberInstance(locale).apply {maximumFractionDigits=2}.format(value)
    var query by rememberSaveable {mutableStateOf("")}
    var adding by rememberSaveable {mutableStateOf(false)}
    var editing by rememberSaveable(stateSaver=SavedPlaceSnapshot.Saver) {mutableStateOf<SavedPlace?>(null)}
    // Tapping a place opens its detail (the Place board); the editor is one tap further.
    var detailId by rememberSaveable {mutableStateOf<String?>(null)}
    var openedVisit by rememberSaveable(stateSaver=VisitEditorVisitSaver) {mutableStateOf<com.bontecou.whereday.domain.Visit?>(null)}
    var preview by remember {mutableStateOf<SavedPlaceImportPreview?>(null)}
    var policyName by rememberSaveable {mutableStateOf(SavedPlaceDuplicatePolicy.keepExisting.name)}
    val policy=SavedPlaceDuplicatePolicy.valueOf(policyName)
    var busy by remember {mutableStateOf(false)}
    var job by remember {mutableStateOf<Job?>(null)}
    var message by remember {mutableStateOf<PlaceUiText?>(null)}
    var confirmation by remember {mutableStateOf<PlaceReview?>(null)}
    fun leave() {if(busy) job?.cancel() else if(preview!=null) {preview=null;message=null} else back?.invoke()}
    // As the Places tab the library is a root page: Back leaves the app as from any other tab.
    BackHandler(enabled=back!=null || busy || preview!=null,onBack=::leave)
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
        Column(Modifier.fillMaxSize().background(wd.surface)) {
        PlacesBackBar(stringResource(if(busy) R.string.saved_csv_cancel else R.string.saved_back_library),::leave)
        LazyColumn(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("saved-places-content"),contentPadding=PaddingValues(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {WdPageHeader(stringResource(R.string.saved_csv_preview),Modifier.padding(bottom=4.dp),compact=true)}
            item {Text(stringResource(R.string.saved_csv_counts,pluralStringResource(R.plurals.saved_csv_valid_rows,currentPreview.places.size,currentPreview.places.size),pluralStringResource(R.plurals.saved_csv_invalid_rows,currentPreview.invalidRows.size,currentPreview.invalidRows.size)),Modifier.padding(horizontal=24.dp).testTag("saved-csv-counts"),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)}
            item {Column {
                val keep=stringResource(R.string.saved_csv_keep);val update=stringResource(R.string.saved_csv_update)
                PlacesPolicyChoice(stringResource(R.string.saved_csv_policy),policy.name,SavedPlaceDuplicatePolicy.entries.map {it.name},!busy,labelFor={if(it==SavedPlaceDuplicatePolicy.keepExisting.name) keep else update}) {policyName=it}
                WdSectionFooter(stringResource(R.string.saved_csv_matching))
            }}
            val ready=review?.takeIf {it.preview===currentPreview && it.policy==policy && it.existing==places}
            if(ready!=null) item {WdCard(Modifier.padding(horizontal=16.dp)) {Text(savedPlacePlanText(ready.plan.summary),modifier=Modifier.padding(16.dp).testTag("saved-csv-plan"),style=WdType.title3,color=wd.textPrimary)}}
            else item {PlacesProgress()}
            item {WdButton(stringResource(R.string.saved_csv_import_valid),{confirmation=ready},Modifier.padding(horizontal=16.dp),emphasis=WdEmphasis.primary,enabled=!busy && ready!=null && currentPreview.places.isNotEmpty(),icon=Icons.Outlined.UploadFile)}
            message?.let {item {WdNotice(it.text(),Modifier.padding(horizontal=16.dp),warning=true)}}
            if(busy) item {PlacesProgress()}
            val entries=ready?.plan?.entries?.associateBy {it.place.id}.orEmpty()
            item {WdSectionHeader(stringResource(R.string.redesign_places_section_rows))}
            items(currentPreview.rows,key={it.rowNumber}) {row ->
                val place=row.place
                WdCard(Modifier.padding(horizontal=16.dp)) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
                        Box(Modifier.size(10.dp).clip(CircleShape).background(if(place==null) wd.inkRust else placeInk(place.id)))
                        Text(stringResource(R.string.saved_csv_row_title,row.rowNumber,row.name.ifEmpty {stringResource(R.string.saved_csv_unnamed)}),style=WdType.title3,color=wd.textPrimary,modifier=Modifier.weight(1f))
                    }
                    if(place==null) row.issues.forEach {Text(savedPlaceCsvText(it).text(),style=MaterialTheme.typography.bodyMedium,color=wd.inkRust)}
                    else {
                        entries[place.id]?.let {Text(stringResource(when(it.action) {SavedPlaceImportAction.add->R.string.saved_csv_action_add;SavedPlaceImportAction.update->R.string.saved_csv_action_update;SavedPlaceImportAction.skip->R.string.saved_csv_action_skip}),style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=if(it.action==SavedPlaceImportAction.skip) wd.textMuted else wd.accent)}
                        Text(stringResource(R.string.saved_csv_coordinate,String.format(locale,"%.6f",place.coordinate.latitude),String.format(locale,"%.6f",place.coordinate.longitude),number(place.radius)),style=WdType.caption,color=wd.textMuted)
                        place.address?.let {Text(it,style=MaterialTheme.typography.bodyMedium,color=wd.textPrimary)}
                    }
                }}
            }
        }
        }
        confirmation?.let {confirmed ->WorkspaceAlertDialog(onDismissRequest={if(busy) job?.cancel() else confirmation=null},title={Text(stringResource(R.string.saved_csv_confirm_title),style=WdType.title2)},text={
            val summary=confirmed.plan.summary
            Column(Modifier.verticalScroll(androidx.compose.foundation.rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.saved_csv_confirm_body,savedPlacePlanText(summary),confirmed.preview.invalidRows.size),color=wd.textPrimary)
                if(busy) {PlacesProgress(Modifier,inset=false);Text(stringResource(R.string.saved_csv_importing),style=WdType.caption,color=wd.textMuted)}
            }
        },confirmButton={TextButton(enabled=!busy,onClick={job=scope.launch {
            busy=true;message=null
            try {
                val summary=graph.savedPlaces.import(confirmed.preview.places,confirmed.existing,confirmed.policy)
                preview=null;confirmation=null;message=PlaceUiText(R.string.saved_csv_result,listOf(summary.added,summary.updated,summary.skipped))
            } catch(e:Exception) {confirmation=null;if(e is CancellationException) {message=PlaceUiText(R.string.saved_csv_canceled);throw e};message=PlaceUiText(if(e is SavedPlacesChanged) R.string.saved_changed else R.string.saved_csv_import_error)}
            finally {busy=false}
        }}) {Text(stringResource(R.string.saved_csv_commit),fontWeight=FontWeight.SemiBold)}},dismissButton={TextButton(onClick={if(busy) job?.cancel() else confirmation=null}) {Text(stringResource(if(busy) R.string.saved_csv_cancel else R.string.visit_cancel),color=wd.textMuted)}})}
        return
    }
    val filtered by produceState<List<SavedPlace>?>(null,places,query,locale) {
        value=null
        value=withContext(Dispatchers.Default) {
            fun searchable(value:String)=savedPlaceSearchKey(value,locale)
            val key=searchable(query.trim());val collator=Collator.getInstance(locale)
            places.filter {ensureActive();key.isEmpty() || key in searchable(it.name) || key in searchable(it.address.orEmpty())}
                .sortedWith {a,b->collator.compare(a.name,b.name).takeIf {it!=0} ?: a.id.compareTo(b.id)}
        }
    }
    Box(Modifier.fillMaxSize().background(wd.surface)) {
    Column(Modifier.fillMaxSize()) {
    if(back!=null || busy) PlacesBackBar(stringResource(if(busy) R.string.saved_csv_cancel else R.string.saved_back_settings),::leave) else Spacer(Modifier.statusBarsPadding())
    val importCsv={picker.launch(arrayOf("text/*","application/csv","application/octet-stream"))}
    LazyColumn(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("saved-places-content"),contentPadding=PaddingValues(bottom=if(showAddFab) 104.dp else 32.dp)) {
        // The Places board: tracked count over the serif title, with CSV import behind the header's overflow action.
        item {WdPageHeader(stringResource(R.string.saved_title),eyebrow=pluralStringResource(R.plurals.redesign_places_saved_eyebrow,places.size,places.size)) {
            WdIconButton(Icons.Outlined.UploadFile,stringResource(R.string.saved_import_csv),importCsv,Modifier.testTag("saved-places-import"),enabled=!busy)
        }}
        item {OutlinedTextField(query,{query=it},placeholder={Text(stringResource(R.string.visit_saved_search))},singleLine=true,shape=RoundedCornerShape(28.dp),
            colors=wdTextFieldColors().copy(focusedContainerColor=wd.container,unfocusedContainerColor=wd.container,unfocusedIndicatorColor=androidx.compose.ui.graphics.Color.Transparent),
            modifier=Modifier.padding(horizontal=16.dp).padding(top=4.dp).fillMaxWidth().heightIn(min=56.dp).testTag("saved-places-search"),
            leadingIcon={Icon(Icons.Outlined.Search,null,tint=wd.textMuted)},trailingIcon={
            if(query.isNotEmpty()) TextButton(onClick={query=""}) {Text(stringResource(R.string.visit_saved_clear),style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=wd.accent)}
        })}
        if(!showAddFab) item {Row(Modifier.padding(horizontal=16.dp).padding(top=12.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            WdButton(stringResource(R.string.saved_add),{adding=true},Modifier.weight(1f),emphasis=WdEmphasis.primary,enabled=!busy,icon=Icons.Outlined.Add)
        }}
        item {WdSectionFooter(stringResource(R.string.saved_csv_help),Modifier.padding(horizontal=4.dp).padding(top=6.dp))}
        message?.let {item {WdNotice(it.text(),Modifier.padding(horizontal=16.dp).padding(top=12.dp))}}
        if(busy) item {Column {PlacesProgress();Text(stringResource(R.string.saved_csv_reading),Modifier.padding(horizontal=20.dp,vertical=6.dp),style=WdType.caption,color=wd.textMuted)}}
        val matches=filtered
        if(matches==null) item {PlacesProgress()}
        else if(matches.isEmpty()) item {
            val blank=query.isBlank()
            WdEmptyState(stringResource(if(blank) R.string.redesign_places_empty_title else R.string.redesign_places_no_match_title),stringResource(if(blank) R.string.saved_empty else R.string.saved_no_match),icon=if(blank) Icons.Outlined.Place else Icons.Outlined.SearchOff)
        }
        else {
            item {Text(pluralStringResource(R.plurals.saved_count,places.size,matches.size,places.size),Modifier.padding(start=20.dp,end=20.dp,top=20.dp,bottom=8.dp),style=WdType.eyebrow,color=wd.textMuted)}
            itemsIndexed(matches,key={_,it->it.id}) {index,place ->
                val ink=placeInk(place.id)
                Row(Modifier.padding(horizontal=16.dp).settingsSegment(index==0,index==matches.lastIndex).clickable(enabled=!busy,role=Role.Button) {detailId=place.id}.testTag("saved-place-${place.id}")
                    .heightIn(min=72.dp).padding(start=16.dp,end=20.dp,top=8.dp,bottom=8.dp),horizontalArrangement=Arrangement.spacedBy(16.dp),verticalAlignment=Alignment.CenterVertically) {
                    Box(Modifier.size(40.dp).clip(CircleShape).background(ink.copy(alpha=0.16f)),contentAlignment=Alignment.Center) {
                        Icon(Icons.Outlined.Place,null,Modifier.size(20.dp),tint=ink)
                    }
                    Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                        Text(place.name,style=WdType.title3.copy(fontWeight=FontWeight.Normal,lineHeight=26.sp),color=wd.textPrimary);place.address?.let {Text(it,style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)}
                    }
                    Text(stringResource(R.string.saved_radius,number(place.radius)),style=WdType.caption,color=wd.textMuted)
                }
            }
        }
    }
    }
    if(showAddFab) WdExtendedFab(stringResource(R.string.saved_add),Icons.Outlined.AddLocationAlt,{if(!busy) adding=true},
        Modifier.align(Alignment.BottomEnd).padding(16.dp).testTag("saved-places-add"))
    // The place detail covers the library inside the same box, whatever column hosts this screen.
    places.find {it.id==detailId}?.let {place->
        Box(Modifier.fillMaxSize()) {PlaceDetailScreen(graph,place,{detailId=null},{editing=place}) {openedVisit=it}}
    }
    }
    openedVisit?.let {visit->key(visit.id) {
        VisitEditor(graph,visit,{openedVisit=null},{value,reuse,remember,overlap->graph.history.saveVisit(value,visit,reuse,remember,overlap)},{graph.history.deleteVisit(visit)})
    }}
    if(adding || editing!=null) {
        val expected=editing
        key(expected!=null,expected?.id) {
            PlaceEditor(expected,{adding=false;editing=null},{graph.savedPlaces.save(it,expected)},expected?.let {place->{graph.savedPlaces.delete(place)}})
        }
    }
}

/** A stable place ink per saved place, matching the iOS dial and map colors. */
@Composable @ReadOnlyComposable internal fun placeInk(id:String)=wd.placeInks[(id.hashCode() and Int.MAX_VALUE)%wd.placeInks.size]

/** A pushed page's leading paper back capsule; the serif title lives in the page body as on iOS. */
@Composable internal fun PlacesBackBar(label:String,onBack:()->Unit,modifier:Modifier=Modifier) {
    Row(modifier.fillMaxWidth().statusBarsPadding().heightIn(min=56.dp).padding(horizontal=12.dp),verticalAlignment=Alignment.CenterVertically) {WdBackChip(label,onBack)}
}

/** A thin ink progress rule used for inline loading states. */
@Composable internal fun PlacesProgress(modifier:Modifier=Modifier,inset:Boolean=true) {
    LinearProgressIndicator(modifier.then(if(inset) Modifier.padding(horizontal=20.dp,vertical=8.dp) else Modifier).fillMaxWidth().clip(CircleShape),color=wd.accent,trackColor=wd.dialTrack)
}

/** The duplicate policy as a card of checkable rows (iOS inline picker) instead of a dropdown. */
@Composable private fun PlacesPolicyChoice(label:String,value:String,choices:List<String>,enabled:Boolean,labelFor:(String)->String,change:(String)->Unit) {
    WdSectionHeader(label)
    WdCard(Modifier.padding(horizontal=16.dp)) {
        choices.forEachIndexed {index,choice->WdRow(Modifier.semantics {selected=choice==value},divider=index<choices.lastIndex,onClick={change(choice)},enabled=enabled) {
            Text(labelFor(choice),style=MaterialTheme.typography.bodyLarge,color=if(enabled) wd.textPrimary else wd.textMuted,modifier=Modifier.weight(1f))
            if(choice==value) Icon(Icons.Outlined.Check,null,Modifier.size(18.dp),tint=wd.accent)
        }}
    }
}
