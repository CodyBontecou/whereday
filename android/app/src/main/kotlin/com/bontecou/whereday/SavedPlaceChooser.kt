package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bontecou.whereday.domain.SavedPlace
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import java.text.Collator
import java.text.Normalizer
import java.util.Locale

@Composable internal fun SavedPlaceChooser(graph:AppGraph,dismiss:()->Unit,select:(SavedPlace)->Unit) {
    var query by rememberSaveable {mutableStateOf("")}
    val places by produceState<List<SavedPlace>?>(null,graph) {graph.history.dao.observePlaces().collect {value=it.map {row->row.value}}}
    val matches by produceState<List<SavedPlace>?>(null,places,query) {
        value=null
        val current=places ?: return@produceState
        value=withContext(Dispatchers.Default) {
            fun key(text:String)=Normalizer.normalize(text,Normalizer.Form.NFD).replace(Regex("\\p{M}+"),"").lowercase(Locale.getDefault())
            val search=key(query.trim());val collator=Collator.getInstance()
            current.filter {ensureActive();search.isEmpty() || search in key(it.name) || search in key(it.address.orEmpty())}
                .sortedWith {a,b->collator.compare(a.name,b.name).takeIf {it!=0} ?: a.id.compareTo(b.id)}
        }
    }
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        BackHandler(onBack=dismiss)
        Surface(Modifier.fillMaxSize()) {Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            TextButton(onClick=dismiss) {Text("Cancel saved-place selection")}
            Text("Choose saved place",style=MaterialTheme.typography.headlineSmall)
            Text("This copies the saved name, address and coordinates into the visit draft. It does not change the saved place.",style=MaterialTheme.typography.bodySmall)
            OutlinedTextField(query,{query=it},label={Text("Search saved places")},singleLine=true,modifier=Modifier.fillMaxWidth(),trailingIcon={if(query.isNotEmpty()) TextButton(onClick={query=""}) {Text("Clear search")}})
            val found=matches
            if(found==null) LinearProgressIndicator(Modifier.fillMaxWidth())
            else if(found.isEmpty()) Text(if(places.isNullOrEmpty()) "No saved places yet. Choose a location on the map or enter coordinates." else "No saved places match your search.")
            else LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                items(found,key={it.id}) {place->Card(Modifier.fillMaxWidth().clickable {select(place)}) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Text(place.name,style=MaterialTheme.typography.titleMedium);place.address?.let {Text(it)}
                    Text("Use this saved place",style=MaterialTheme.typography.labelMedium)
                }}}
            }
        }}
    }
}
