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
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
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

@Composable fun SavedPlacesScreen(graph:AppGraph,places:List<SavedPlace>,back:()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    val locale=LocalConfiguration.current.locales[0] ?: Locale.getDefault()
    fun number(value:Double)=NumberFormat.getNumberInstance(locale).apply {maximumFractionDigits=2}.format(value)
    var query by rememberSaveable {mutableStateOf("")}
    var adding by rememberSaveable {mutableStateOf(false)}
    var editing by rememberSaveable(stateSaver=SavedPlaceSnapshot.Saver) {mutableStateOf<SavedPlace?>(null)}
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
    val filtered by produceState<List<SavedPlace>?>(null,places,query,locale) {
        value=null
        value=withContext(Dispatchers.Default) {
            fun searchable(value:String)=savedPlaceSearchKey(value,locale)
            val key=searchable(query.trim());val collator=Collator.getInstance(locale)
            places.filter {ensureActive();key.isEmpty() || key in searchable(it.name) || key in searchable(it.address.orEmpty())}
                .sortedWith {a,b->collator.compare(a.name,b.name).takeIf {it!=0} ?: a.id.compareTo(b.id)}
        }
    }
    LazyColumn(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("saved-places-content"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {TextButton(onClick=::leave) {Text(stringResource(if(busy) R.string.saved_csv_cancel else R.string.saved_back_settings))}}
        item {Text(stringResource(R.string.saved_title),style=MaterialTheme.typography.headlineSmall)}
        item {OutlinedTextField(query,{query=it},label={Text(stringResource(R.string.visit_saved_search))},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("saved-places-search"),trailingIcon={
            if(query.isNotEmpty()) TextButton(onClick={query=""}) {Text(stringResource(R.string.visit_saved_clear))}
        })}
        item {FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled=!busy,onClick={adding=true}) {Text(stringResource(R.string.saved_add))}
            OutlinedButton(enabled=!busy,onClick={picker.launch(arrayOf("text/*","application/csv","application/octet-stream"))}) {Text(stringResource(R.string.saved_import_csv))}
        }}
        item {Text(stringResource(R.string.saved_csv_help),style=MaterialTheme.typography.bodySmall)}
        message?.let {item {Text(it.text())}}
        if(busy) item {LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(R.string.saved_csv_reading))}
        val matches=filtered
        if(matches==null) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
        else if(matches.isEmpty()) item {Text(stringResource(if(query.isBlank()) R.string.saved_empty else R.string.saved_no_match))}
        else {
            item {Text(pluralStringResource(R.plurals.saved_count,places.size,matches.size,places.size),style=MaterialTheme.typography.bodySmall)}
            items(matches,key={it.id}) {place ->Card(Modifier.fillMaxWidth().clickable(enabled=!busy) {editing=place}) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(place.name,style=MaterialTheme.typography.titleMedium);place.address?.let {Text(it)}
                    Text(stringResource(R.string.saved_radius,number(place.radius)),style=MaterialTheme.typography.bodySmall)
                }
            }}
        }
    }
    if(adding || editing!=null) {
        val expected=editing
        key(expected!=null,expected?.id) {
            PlaceEditor(expected,{adding=false;editing=null},{graph.savedPlaces.save(it,expected)},expected?.let {place->{graph.savedPlaces.delete(place)}})
        }
    }
}
