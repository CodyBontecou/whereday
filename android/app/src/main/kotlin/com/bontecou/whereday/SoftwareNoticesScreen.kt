package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.*
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference

private data class SoftwareNoticeReadResult(val sha256:String,val chunks:List<String>?=null,val failed:Boolean=false)
@Composable internal fun SoftwareNoticesScreen(back:()->Unit) {
    val context=LocalContext.current
    val repository=remember(context) {SoftwareNoticeRepository(context)}
    SoftwareNoticesContent(back,repository::catalog,repository::document)
}

@Composable internal fun SoftwareNoticesContent(back:()->Unit,
    load:suspend()->List<SoftwareNoticeComponent>,read:suspend(SoftwareNoticeDocument)->List<String>) {
    var selected by rememberSaveable {mutableStateOf<String?>(null)}
    var selectedDocument by rememberSaveable {mutableStateOf<String?>(null)}
    var query by rememberSaveable {mutableStateOf("")}
    val overviewScroll=rememberLazyListState()
    val focus=LocalFocusManager.current;val keyboard=LocalSoftwareKeyboardController.current
    var catalog by remember {mutableStateOf<List<SoftwareNoticeComponent>?>(null)}
    var catalogFailed by remember {mutableStateOf(false)}
    var readResult by remember {mutableStateOf<SoftwareNoticeReadResult?>(null)}
    var catalogRetry by remember {mutableIntStateOf(0)}
    var documentRetry by remember {mutableIntStateOf(0)}
    val latestLoad by rememberUpdatedState(load);val latestRead by rememberUpdatedState(read)
    val latestBack by rememberUpdatedState(back)
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var active by remember(lifecycle) {mutableStateOf(lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))}
    var generation by remember(lifecycle) {mutableLongStateOf(0)}
    val alive=remember {AtomicBoolean(true)}
    val loading=remember {AtomicReference<Job?>(null)};val reading=remember {AtomicReference<Job?>(null)}
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->
            if(event==Lifecycle.Event.ON_STOP) {
                loading.getAndSet(null)?.cancel();reading.getAndSet(null)?.cancel()
                readResult=null;generation++
            }
            active=lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        }
        lifecycle.addObserver(observer)
        onDispose {alive.set(false);loading.getAndSet(null)?.cancel();reading.getAndSet(null)?.cancel();lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(active,generation,catalogRetry) {
        val owner=currentCoroutineContext().job;loading.set(owner)
        try {
            if(active) {
                catalogFailed=false
                try {val value=latestLoad();currentCoroutineContext().ensureActive();catalog=value}
                catch(error:CancellationException) {throw error}
                catch(_:Exception) {currentCoroutineContext().ensureActive();catalog=null;catalogFailed=true}
            }
        } finally {loading.compareAndSet(owner,null)}
    }
    val component=catalog?.firstOrNull {it.id==selected}
    val document=component?.documents?.firstOrNull {it.sha256==selectedDocument}
    val currentComponent by rememberUpdatedState(component)
    val currentDocument by rememberUpdatedState(document)
    LaunchedEffect(document,active,generation,documentRetry) {
        val owner=currentCoroutineContext().job;reading.set(owner)
        try {
            readResult=null
            if(active && document!=null) {
                try {val value=latestRead(document);require(value.isNotEmpty());currentCoroutineContext().ensureActive();readResult=SoftwareNoticeReadResult(document.sha256,value)}
                catch(error:CancellationException) {throw error}
                catch(_:Exception) {currentCoroutineContext().ensureActive();readResult=SoftwareNoticeReadResult(document.sha256,failed=true)}
            }
        } finally {reading.compareAndSet(owner,null)}
    }
    fun interactive()=alive.get() && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
    fun leave() {
        if(!interactive()) return
        when {
            selectedDocument!=null->{reading.getAndSet(null)?.cancel();readResult=null;selectedDocument=null}
            selected!=null->{selected=null}
            else->latestBack()
        }
    }
    BackHandler {leave()}
    Column(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.padding(20.dp).testTag("software-notices-screen"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        TextButton(onClick={leave()},modifier=Modifier.testTag("software-notices-back")) {Text(stringResource(R.string.common_back))}
        Text(stringResource(R.string.software_notices_title),style=MaterialTheme.typography.headlineSmall)
        when {
            catalogFailed->{
                Text(stringResource(R.string.software_notices_failed),Modifier.testTag("software-notices-error"),color=MaterialTheme.colorScheme.error)
                TextButton(onClick={if(interactive()) catalogRetry++},modifier=Modifier.testTag("software-notices-retry")) {Text(stringResource(R.string.software_notices_retry))}
            }
            catalog==null->{CircularProgressIndicator();Text(stringResource(R.string.software_notices_loading))}
            document!=null->{
                val loaded=readResult?.takeIf {it.sha256==document.sha256}
                when {
                    loaded?.failed==true->{
                        Text(stringResource(R.string.software_notices_failed),Modifier.testTag("software-notices-document-error"),color=MaterialTheme.colorScheme.error)
                        TextButton(onClick={if(interactive() && currentDocument==document) documentRetry++},modifier=Modifier.testTag("software-notices-document-retry")) {Text(stringResource(R.string.software_notices_retry))}
                    }
                    loaded==null->{CircularProgressIndicator();Text(stringResource(R.string.software_notices_loading))}
                    else->key(component.id,document.sha256) {
                        SelectionContainer(Modifier.weight(1f)) {
                            LazyColumn(Modifier.fillMaxSize().testTag("software-notices-text"),state=rememberLazyListState()) {
                                item(key="component") {Text(component.name,style=MaterialTheme.typography.titleMedium)}
                                item(key="document") {Text(document.title,style=MaterialTheme.typography.bodyMedium)}
                                items(requireNotNull(loaded.chunks).size,key={it}) {index->
                                    Text(requireNotNull(loaded.chunks)[index],Modifier.fillMaxWidth().testTag("software-notice-chunk-$index"),style=MaterialTheme.typography.bodySmall)
                                }
                                item(key="end") {Text(stringResource(R.string.software_notices_end),Modifier.padding(top=16.dp).testTag("software-notice-end"),style=MaterialTheme.typography.labelMedium)}
                            }
                        }
                    }
                }
            }
            component!=null->key(component.id) {
                LazyColumn(Modifier.weight(1f).testTag("software-notices-documents"),state=rememberLazyListState(),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                    item {Text(component.name,style=MaterialTheme.typography.titleLarge)}
                    item {SelectionContainer {Text(component.coordinate,style=MaterialTheme.typography.bodySmall)}}
                    items(component.documents,key={it.sha256}) {item->
                        TextButton(onClick={if(interactive() && currentComponent==component && currentDocument==null) {selectedDocument=item.sha256}},shape=RoundedCornerShape(8.dp),contentPadding=PaddingValues(12.dp),modifier=Modifier.fillMaxWidth().testTag("software-notice-document-${item.sha256}")) {Text(item.title)}
                    }
                }
            }
            else->{
                OutlinedTextField(query,{query=it.take(256)},label={Text(stringResource(R.string.software_notices_search))},singleLine=true,
                    modifier=Modifier.fillMaxWidth().testTag("software-notices-search"))
                val matches=remember(catalog,query) {catalog.orEmpty().filter {it.name.contains(query,ignoreCase=true) || it.coordinate.contains(query,ignoreCase=true)}}
                LazyColumn(Modifier.weight(1f).testTag("software-notices-list"),state=overviewScroll,verticalArrangement=Arrangement.spacedBy(8.dp)) {
                    item {Text(stringResource(R.string.software_notices_intro),style=MaterialTheme.typography.bodyMedium)}
                    if(matches.isEmpty()) item {Text(stringResource(R.string.software_notices_empty),Modifier.testTag("software-notices-empty"))}
                    items(matches,key={it.id}) {item->
                        TextButton(onClick={if(interactive() && currentComponent==null) {focus.clearFocus();keyboard?.hide();selected=item.id;selectedDocument=null}},shape=RoundedCornerShape(8.dp),contentPadding=PaddingValues(12.dp),modifier=Modifier.fillMaxWidth().testTag("software-notice-component-${item.id}")) {
                            Column(Modifier.fillMaxWidth(),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                                Text(item.name,style=MaterialTheme.typography.titleMedium)
                                Text(item.coordinate,style=MaterialTheme.typography.bodySmall)
                            }
                        }
                    }
                }
            }
        }
    }
}
