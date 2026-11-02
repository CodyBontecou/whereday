package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import java.text.NumberFormat

@Composable internal fun PlaceSearchDialog(service:PlaceLookupService,networkAllowed:Flow<Boolean>,dismiss:()->Unit,select:(PlaceLookupResult)->Unit) {
    val allowed by networkAllowed.collectAsState(initial=false)
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val locale=LocalConfiguration.current.locales[0]
    val number=remember(locale) {NumberFormat.getNumberInstance(locale).apply {minimumFractionDigits=6;maximumFractionDigits=6}}
    var query by rememberSaveable {mutableStateOf("")}
    var results by remember {mutableStateOf<List<PlaceLookupResult>>(emptyList())}
    var busy by remember {mutableStateOf(false)}
    var attempted by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<Int?>(null)}
    var version by remember {mutableIntStateOf(0)}
    var request by remember {mutableStateOf<Job?>(null)}
    val scope=rememberCoroutineScope()
    fun invalidate() {version++;request?.cancel();request=null;busy=false;attempted=false;results=emptyList();error=null}
    fun close() {invalidate();dismiss()}
    val normalized=query.trim()
    val valid=normalized.codePointCount(0,normalized.length)>=2 && normalized.length<=256
    fun search() {
        if(!allowed || !valid) return
        invalidate();val generation=version;val submitted=query;busy=true;attempted=true
        request=scope.launch {
            try {
                if(!networkAllowed.first() || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@launch
                val found=service.search(submitted);ensureActive()
                if(version==generation && networkAllowed.first() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) results=found
            } catch(e:Exception) {
                if(e is CancellationException && e !is TimeoutCancellationException) throw e
                if(version==generation) error=when(e) {
                    is PlaceLookupDisabled->R.string.place_search_disabled
                    is TimeoutCancellationException->R.string.place_search_timeout
                    else->R.string.place_search_error
                }
            } finally {if(version==generation) {busy=false;request=null}}
        }
    }
    LaunchedEffect(allowed) {if(!allowed) invalidate()}
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP) invalidate()}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer);request?.cancel()}
    }
    WorkspaceDialog(fillRegion=true,onDismissRequest={close()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        BackHandler(onBack={close()})
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().imePadding().semantics {testTagsAsResourceId=true}.testTag("place-search-content").padding(20.dp)) {
                TextButton(onClick={close()},modifier=Modifier.testTag("place-search-close")) {Text(stringResource(R.string.place_search_cancel))}
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    Text(stringResource(R.string.place_search_title),style=MaterialTheme.typography.headlineSmall)
                    Text(stringResource(R.string.place_search_disclosure),style=MaterialTheme.typography.bodySmall)
                    if(!allowed) Text(stringResource(R.string.place_search_disabled))
                    OutlinedTextField(query,{if(it.length<=256) {invalidate();query=it}},label={Text(stringResource(R.string.place_search_query))},singleLine=true,modifier=Modifier.fillMaxWidth().testTag("place-search-query"),
                        keyboardOptions=KeyboardOptions(imeAction=ImeAction.Search),keyboardActions=KeyboardActions(onSearch={search()}),
                        trailingIcon={if(query.isNotEmpty()) TextButton(onClick={invalidate();query=""},modifier=Modifier.testTag("place-search-clear")) {Text(stringResource(R.string.place_search_clear))}})
                    Row(horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        Button(enabled=allowed&&valid&&!busy,onClick={search()},modifier=Modifier.testTag("place-search-submit")) {Text(stringResource(R.string.place_search_submit))}
                        if(busy) TextButton(onClick={invalidate()},modifier=Modifier.testTag("place-search-stop")) {Text(stringResource(R.string.place_search_stop))}
                    }
                    if(busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(R.string.place_search_loading))}
                    error?.let {Text(stringResource(it),color=MaterialTheme.colorScheme.error)}
                    if(!busy && error==null && attempted && results.isEmpty()) Text(stringResource(R.string.place_search_empty))
                    if(!attempted && !busy) Text(stringResource(R.string.place_search_hint),style=MaterialTheme.typography.bodySmall)
                    if(allowed) results.forEach {result->
                        val generation=version
                        Card(Modifier.fillMaxWidth().testTag("place-search-result-${result.id}").clickable {
                            scope.launch {
                                if(networkAllowed.first() && version==generation && result in results && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
                                    invalidate();select(result)
                                }
                            }
                        }) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                            Text(result.name,style=MaterialTheme.typography.titleMedium)
                            result.address?.let {Text(it)}
                            Text(stringResource(R.string.place_search_coordinate,number.format(result.coordinate.latitude),number.format(result.coordinate.longitude)),style=MaterialTheme.typography.bodySmall)
                            Text(stringResource(R.string.place_search_select),style=MaterialTheme.typography.labelMedium)
                        }}
                    }
                }
            }
        }
    }
}
