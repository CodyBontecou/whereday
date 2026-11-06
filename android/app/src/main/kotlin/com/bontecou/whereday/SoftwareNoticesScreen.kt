package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.SearchOff
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
import androidx.compose.ui.text.style.TextAlign
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
    val card=Modifier.padding(horizontal=16.dp)
    Column(Modifier.fillMaxSize().background(wd.surface).semantics {testTagsAsResourceId=true}.testTag("software-notices-screen")) {
        SettingsBackHeader(stringResource(R.string.software_notices_title),stringResource(R.string.common_back),{leave()},backTag="software-notices-back")
        when {
            catalogFailed->SettingsNotice(card,warning=true) {
                Text(stringResource(R.string.software_notices_failed),Modifier.testTag("software-notices-error"))
                SettingsTextAction(stringResource(R.string.software_notices_retry),{if(interactive()) catalogRetry++},Modifier.testTag("software-notices-retry"))
            }
            catalog==null->SettingsProgress(stringResource(R.string.software_notices_loading),card.padding(top=8.dp))
            document!=null->{
                val loaded=readResult?.takeIf {it.sha256==document.sha256}
                when {
                    loaded?.failed==true->SettingsNotice(card,warning=true) {
                        Text(stringResource(R.string.software_notices_failed),Modifier.testTag("software-notices-document-error"))
                        SettingsTextAction(stringResource(R.string.software_notices_retry),{if(interactive() && currentDocument==document) documentRetry++},Modifier.testTag("software-notices-document-retry"))
                    }
                    loaded==null->SettingsProgress(stringResource(R.string.software_notices_loading),card.padding(top=8.dp))
                    else->key(component.id,document.sha256) {
                        SelectionContainer(Modifier.weight(1f)) {
                            val chunks=requireNotNull(loaded.chunks)
                            LazyColumn(Modifier.fillMaxSize().testTag("software-notices-text"),state=rememberLazyListState(),contentPadding=PaddingValues(start=16.dp,end=16.dp,bottom=32.dp)) {
                                item(key="component") {Text(component.name,Modifier.padding(horizontal=4.dp),style=WdType.title3,color=wd.textPrimary)}
                                item(key="document") {Text(document.title,Modifier.padding(start=4.dp,end=4.dp,top=2.dp,bottom=12.dp),style=WdType.caption,color=wd.textMuted)}
                                items(chunks.size,key={it}) {index->
                                    Text(chunks[index],Modifier.settingsSegment(index==0,index==chunks.lastIndex).padding(horizontal=16.dp,vertical=if(chunks.size==1) 16.dp else 8.dp).testTag("software-notice-chunk-$index"),style=WdType.caption2,color=wd.textPrimary)
                                }
                                item(key="end") {Text(stringResource(R.string.software_notices_end),Modifier.fillMaxWidth().padding(top=16.dp).testTag("software-notice-end"),style=WdType.caption2,color=wd.textMuted,textAlign=TextAlign.Center)}
                            }
                        }
                    }
                }
            }
            component!=null->key(component.id) {
                LazyColumn(Modifier.weight(1f).testTag("software-notices-documents"),state=rememberLazyListState(),contentPadding=PaddingValues(start=16.dp,end=16.dp,bottom=32.dp)) {
                    item {Text(component.name,Modifier.padding(horizontal=4.dp),style=WdType.title2,color=wd.textPrimary)}
                    item {SelectionContainer {Text(component.coordinate,Modifier.padding(start=4.dp,end=4.dp,top=2.dp,bottom=16.dp),style=WdType.caption,color=wd.textMuted)}}
                    itemsIndexed(component.documents,key={_,it->it.sha256}) {index,item->
                        Box(Modifier.settingsSegment(index==0,index==component.documents.lastIndex)) {
                            WdNavRow(item.title,Modifier.testTag("software-notice-document-${item.sha256}"),icon=Icons.Outlined.Description,divider=false,
                                onClick={if(interactive() && currentComponent==component && currentDocument==null) {selectedDocument=item.sha256}})
                        }
                    }
                }
            }
            else->{
                OutlinedTextField(query,{query=it.take(256)},label={Text(stringResource(R.string.software_notices_search))},singleLine=true,
                    leadingIcon={Icon(Icons.Outlined.Search,null,tint=wd.textMuted)},shape=RoundedCornerShape(12.dp),colors=wdTextFieldColors(),
                    modifier=card.fillMaxWidth().testTag("software-notices-search"))
                val matches=remember(catalog,query) {catalog.orEmpty().filter {it.name.contains(query,ignoreCase=true) || it.coordinate.contains(query,ignoreCase=true)}}
                LazyColumn(Modifier.weight(1f).testTag("software-notices-list"),state=overviewScroll,contentPadding=PaddingValues(start=16.dp,end=16.dp,top=12.dp,bottom=32.dp)) {
                    item {Text(stringResource(R.string.software_notices_intro),Modifier.padding(start=4.dp,end=4.dp,bottom=12.dp),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)}
                    if(matches.isEmpty()) item {WdCard {WdEmptyState(stringResource(R.string.software_notices_empty),null,Modifier.testTag("software-notices-empty"),icon=Icons.Outlined.SearchOff)}}
                    itemsIndexed(matches,key={_,it->it.id}) {index,item->
                        WdRow(Modifier.settingsSegment(index==0,index==matches.lastIndex).testTag("software-notice-component-${item.id}"),divider=false,
                            onClick={if(interactive() && currentComponent==null) {focus.clearFocus();keyboard?.hide();selected=item.id;selectedDocument=null}}) {
                            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(2.dp)) {
                                Text(item.name,style=MaterialTheme.typography.bodyLarge,color=wd.textPrimary)
                                Text(item.coordinate,style=WdType.caption2,color=wd.textMuted)
                            }
                            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,null,Modifier.size(18.dp),tint=wd.textMuted)
                        }
                    }
                }
            }
        }
    }
}
