package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*

@Composable internal fun PlaceSearchDialog(service:PlaceLookupService,allowed:Boolean,dismiss:()->Unit,select:(PlaceLookupResult)->Unit) {
    var query by rememberSaveable {mutableStateOf("")}
    var results by remember {mutableStateOf<List<PlaceLookupResult>>(emptyList())}
    var busy by remember {mutableStateOf(false)}
    var attempted by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var version by remember {mutableIntStateOf(0)}
    var request by remember {mutableStateOf<Job?>(null)}
    val scope=rememberCoroutineScope()
    fun invalidate() {version++;request?.cancel();request=null;busy=false;attempted=false;results=emptyList();error=null}
    val normalized=query.trim()
    val valid=normalized.codePointCount(0,normalized.length)>=2 && normalized.length<=256
    fun search() {
        if(!allowed || !valid) return
        invalidate();val generation=version;busy=true;attempted=true
        request=scope.launch {
            try {val found=service.search(query);ensureActive();if(version==generation) results=found}
            catch(e:Exception) {if(e is CancellationException && e !is TimeoutCancellationException) throw e;if(version==generation) error=if(e is PlaceLookupDisabled) e.message else if(e is TimeoutCancellationException) "Place search timed out. Try again or choose coordinates offline." else "Place search is unavailable. Check your connection or try again. You can still choose coordinates offline."}
            finally {if(version==generation) {busy=false;request=null}}
        }
    }
    LaunchedEffect(allowed) {if(!allowed) invalidate()}
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        BackHandler(onBack=dismiss)
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                TextButton(onClick=dismiss) {Text("Cancel place search")}
                Text("Search places",style=MaterialTheme.typography.headlineSmall)
                Text("Search sends the text you submit to your device’s geocoding service. Coverage varies by provider. No device location or journal history is sent.",style=MaterialTheme.typography.bodySmall)
                if(!allowed) Text("Network geocoding is disabled. Enable it in Settings to search, or choose coordinates offline.")
                OutlinedTextField(query,{if(it.length<=256) {invalidate();query=it}},label={Text("Place name or address")},singleLine=true,modifier=Modifier.fillMaxWidth(),
                    keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={search()}),
                    trailingIcon={if(query.isNotEmpty()) TextButton(onClick={invalidate();query=""}) {Text("Clear search")}})
                Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                    Button(enabled=allowed&&valid&&!busy,onClick={search()}) {Text("Search")}
                    if(busy) TextButton(onClick={invalidate()}) {Text("Cancel lookup")}
                }
                if(busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text("Searching places…")}
                error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                LazyColumn(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    if(!busy && error==null && attempted && results.isEmpty()) item {Text("No matching places. Try a city, landmark, or full address.")}
                    if(!attempted && !busy) item {Text("Enter at least two characters and tap Search. Typing does not send a request.",style=MaterialTheme.typography.bodySmall)}
                    items(results,key={it.id}) {result->
                        Card(Modifier.fillMaxWidth().clickable {select(result)}) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(result.name,style=MaterialTheme.typography.titleMedium)
                            result.address?.let {Text(it)}
                            Text("${result.coordinate.latitude}, ${result.coordinate.longitude}",style=MaterialTheme.typography.bodySmall)
                            Text("Select this place",style=MaterialTheme.typography.labelMedium)
                        }}
                    }
                }
            }
        }
    }
}
