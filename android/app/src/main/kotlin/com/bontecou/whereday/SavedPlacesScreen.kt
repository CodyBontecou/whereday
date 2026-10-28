package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
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

private data class PlaceReview(val preview:SavedPlaceImportPreview,val existing:List<SavedPlace>,val policy:SavedPlaceDuplicatePolicy,val plan:SavedPlaceImportPlan)

@Composable fun SavedPlacesScreen(graph:AppGraph,places:List<SavedPlace>,back:()->Unit) {
    val context=LocalContext.current;val scope=rememberCoroutineScope()
    var query by rememberSaveable {mutableStateOf("")}
    var adding by remember {mutableStateOf(false)}
    var editing by remember {mutableStateOf<SavedPlace?>(null)}
    var preview by remember {mutableStateOf<SavedPlaceImportPreview?>(null)}
    var policyName by rememberSaveable {mutableStateOf(SavedPlaceDuplicatePolicy.keepExisting.name)}
    val policy=SavedPlaceDuplicatePolicy.valueOf(policyName)
    var busy by remember {mutableStateOf(false)}
    var job by remember {mutableStateOf<Job?>(null)}
    var message by remember {mutableStateOf<String?>(null)}
    var confirmation by remember {mutableStateOf<PlaceReview?>(null)}
    fun leave() {if(busy) job?.cancel() else if(preview!=null) {preview=null;message=null} else back()}
    BackHandler(onBack=::leave)
    val picker=rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) {uri ->
        if(uri!=null) job=scope.launch {
            busy=true;message=null
            try {
                require(uri.scheme=="content") {"Choose a CSV document."}
                preview=withContext(Dispatchers.IO) {
                    val bytes=context.contentResolver.openInputStream(uri)?.use {input ->
                        val out=ByteArrayOutputStream();val buffer=ByteArray(64*1024)
                        while(true) {ensureActive();val count=input.read(buffer);if(count<0) break
                            require(out.size()+count<=SavedPlaceCsvImporter.maximumFileSize) {"Choose a CSV file of 5 MB or smaller."};out.write(buffer,0,count)}
                        out.toByteArray()
                    } ?: error("The CSV file could not be opened.")
                    val coroutine=currentCoroutineContext();SavedPlaceCsvImporter.parse(bytes) {coroutine.ensureActive()}
                }
            } catch(e:Exception) {if(e is CancellationException) {message="Import canceled.";throw e};message=if(e is IllegalArgumentException) e.message else "The CSV file could not be read. Choose it again and check access."}
            finally {busy=false}
        }
    }
    val currentPreview=preview
    val review by produceState<PlaceReview?>(null,currentPreview,places,policy) {
        value=null
        if(currentPreview!=null) value=PlaceReview(currentPreview,places,policy,graph.savedPlaces.preview(currentPreview.places,places,policy))
    }
    if(currentPreview!=null) {
        LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            item {TextButton(onClick=::leave) {Text(if(busy) "Cancel import" else "Back to saved places")}}
            item {Text("CSV import preview",style=MaterialTheme.typography.headlineSmall)}
            item {Text("${currentPreview.places.size} valid ${if(currentPreview.places.size==1) "row" else "rows"} · ${currentPreview.invalidRows.size} invalid ${if(currentPreview.invalidRows.size==1) "row" else "rows"}. Invalid rows will not be imported.")}
            item {Choice("Matching places",if(policy==SavedPlaceDuplicatePolicy.keepExisting) "Keep existing places" else "Update matching places",listOf("Keep existing places","Update matching places"),!busy) {
                policyName=if(it=="Keep existing places") SavedPlaceDuplicatePolicy.keepExisting.name else SavedPlaceDuplicatePolicy.updateExisting.name
            }}
            item {Text("Names match without case differences when coordinates are within either place's radius. The nearest matching place is used; later rows are compared with earlier changes.",style=MaterialTheme.typography.bodySmall)}
            val ready=review?.takeIf {it.preview===currentPreview && it.policy==policy && it.existing==places}
            if(ready!=null) item {Text("${ready.plan.summary.added} added · ${ready.plan.summary.updated} updated · ${ready.plan.summary.skipped} skipped",style=MaterialTheme.typography.titleMedium)}
            else item {LinearProgressIndicator(Modifier.fillMaxWidth())}
            item {Button(enabled=!busy && ready!=null && currentPreview.places.isNotEmpty(),onClick={confirmation=ready}) {Text("Import valid places")}}
            message?.let {item {Text(it,color=MaterialTheme.colorScheme.error)}}
            if(busy) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
            val entries=ready?.plan?.entries?.associateBy {it.place.id}.orEmpty()
            items(currentPreview.rows,key={it.rowNumber}) {row ->
                val place=row.place
                Card(Modifier.fillMaxWidth()) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text("Row ${row.rowNumber} · ${row.name.ifEmpty {"Unnamed place"}}",style=MaterialTheme.typography.titleMedium)
                    if(place==null) row.errors.forEach {Text(it,color=MaterialTheme.colorScheme.error)}
                    else {
                        entries[place.id]?.let {Text(when(it.action) {SavedPlaceImportAction.add->"Add new place";SavedPlaceImportAction.update->"Update matching place";SavedPlaceImportAction.skip->"Keep existing place; skip this row"})}
                        Text("${place.coordinate.latitude}, ${place.coordinate.longitude} · ${place.radius.toInt()} m")
                        place.address?.let {Text(it)}
                    }
                }}
            }
        }
        confirmation?.let {confirmed ->AlertDialog(onDismissRequest={if(busy) job?.cancel() else confirmation=null},title={Text("Import saved places?")},text={
            val summary=confirmed.plan.summary
            Column(verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Text("${summary.added} will be added, ${summary.updated} updated and ${summary.skipped} skipped. ${confirmed.preview.invalidRows.size} invalid ${if(confirmed.preview.invalidRows.size==1) "row" else "rows"} will be left out. Existing visits and routes remain unchanged.")
                if(busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Importing saved places…")}
            }
        },confirmButton={TextButton(enabled=!busy,onClick={job=scope.launch {
            busy=true;message=null
            try {
                val summary=graph.savedPlaces.import(confirmed.preview.places,confirmed.existing,confirmed.policy)
                preview=null;confirmation=null;message="Added: ${summary.added}. Updated: ${summary.updated}. Skipped: ${summary.skipped}."
            } catch(e:Exception) {confirmation=null;if(e is CancellationException) {message="Import canceled.";throw e};message=if(e is SavedPlacesChanged) e.message else "Saved places could not be imported. Check available storage and try again."}
            finally {busy=false}
        }}) {Text("Import places")}},dismissButton={TextButton(onClick={if(busy) job?.cancel() else confirmation=null}) {Text(if(busy) "Cancel import" else "Cancel")}})}
        return
    }
    val filtered by produceState<List<SavedPlace>?>(null,places,query) {
        value=null
        value=withContext(Dispatchers.Default) {
            fun searchable(value:String)=Normalizer.normalize(value,Normalizer.Form.NFD).replace(Regex("\\p{M}+"),"").lowercase(Locale.getDefault())
            val key=searchable(query.trim());val collator=Collator.getInstance()
            places.filter {ensureActive();key.isEmpty() || key in searchable(it.name) || key in searchable(it.address.orEmpty())}
                .sortedWith {a,b->collator.compare(a.name,b.name).takeIf {it!=0} ?: a.id.compareTo(b.id)}
        }
    }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {TextButton(onClick=::leave) {Text(if(busy) "Cancel import" else "Back to settings")}}
        item {Text("Saved places",style=MaterialTheme.typography.headlineSmall)}
        item {OutlinedTextField(query,{query=it},label={Text("Search saved places")},singleLine=true,modifier=Modifier.fillMaxWidth(),trailingIcon={
            if(query.isNotEmpty()) TextButton(onClick={query=""}) {Text("Clear search")}
        })}
        item {Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            OutlinedButton(enabled=!busy,onClick={adding=true}) {Text("Add place")}
            OutlinedButton(enabled=!busy,onClick={picker.launch(arrayOf("text/*","application/csv","application/octet-stream"))}) {Text("Import CSV")}
        }}
        item {Text("CSV: name, latitude, longitude; optional address and radius_meters. Up to 5 MB and 10,000 rows. Review duplicates before importing.",style=MaterialTheme.typography.bodySmall)}
        message?.let {item {Text(it)}}
        if(busy) item {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Reading CSV…")}
        val matches=filtered
        if(matches==null) item {LinearProgressIndicator(Modifier.fillMaxWidth())}
        else if(matches.isEmpty()) item {Text(if(query.isBlank()) "No saved places yet. Add a place or import a CSV file." else "No saved places match your search.")}
        else {
            item {Text("${matches.size} of ${places.size} saved ${if(places.size==1) "place" else "places"}",style=MaterialTheme.typography.bodySmall)}
            items(matches,key={it.id}) {place ->Card(Modifier.fillMaxWidth().clickable(enabled=!busy) {editing=place}) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(place.name,style=MaterialTheme.typography.titleMedium);place.address?.let {Text(it)}
                    Text("Match radius: ${place.radius.toInt()} m",style=MaterialTheme.typography.bodySmall)
                }
            }}
        }
    }
    if(adding || editing!=null) {
        val expected=editing
        PlaceEditor(expected,{adding=false;editing=null},{graph.savedPlaces.save(it,expected)},expected?.let {place->{graph.savedPlaces.delete(place)}})
    }
}
