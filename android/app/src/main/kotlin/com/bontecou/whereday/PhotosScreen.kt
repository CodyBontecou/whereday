package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.data.*
import com.bontecou.whereday.platform.AppGraph
import com.bontecou.whereday.platform.PhotoLibraryAccess
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.catch

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun PhotosScreen(graph:AppGraph,back:()->Unit) {
    val scope=rememberCoroutineScope()
    val context=LocalContext.current
    val resources=context.resources
    val autoLabel=stringResource(R.string.photo_auto_sync)
    val pickerLocationLabel=stringResource(R.string.photo_ask_locations)
    val sync by graph.photoLibrary.state.collectAsStateWithLifecycle()
    val automatic by graph.automaticPhotos.state.collectAsStateWithLifecycle()
    val settings by graph.preferences.settings.collectAsStateWithLifecycle(initialValue=AppSettings())
    var task by remember {mutableStateOf<Job?>(null)}
    var ready by remember {mutableStateOf(false)}
    var libraryAccess by remember {mutableStateOf(graph.photoAccess.current())}
    var originalLocations by remember {mutableStateOf(graph.photoAccess.originalLocationsAllowed())}
    var libraryOptions by rememberSaveable {mutableStateOf(false)}
    var foreground by remember {mutableStateOf(false)}
    var starts by rememberSaveable {mutableStateOf(listOf(""))}
    var selectionEpoch by rememberSaveable {mutableStateOf("")}
    var picking by rememberSaveable {mutableStateOf(false)}
    var selected by rememberSaveable(stateSaver=PhotoViewerDraft.Saver) {mutableStateOf<PhotoViewerDraft?>(null)}
    var removing by remember {mutableStateOf<String?>(null)}
    var removalRunning by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<Int?>(null)}
    var page by remember {mutableStateOf<PhotoLibraryPage?>(null)}
    LaunchedEffect(starts.last()) {
        page=null
        graph.photos.observeLibrary(starts.last()).catch {error=R.string.photo_error_load}.collect {page=it}
    }
    fun refresh() {libraryAccess=graph.photoAccess.current();originalLocations=graph.photoAccess.originalLocationsAllowed();if(task?.isActive!=true) task=scope.launch {ready=false;try {graph.photoLibrary.refresh()} catch(e:CancellationException) {throw e} catch(_:Exception) {error=R.string.photo_error_check_access} finally {ready=foreground && graph.photoLibrary.state.value.validated}}}
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val latestRefresh by rememberUpdatedState({refresh()})
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->when(event) {Lifecycle.Event.ON_RESUME->{foreground=true;latestRefresh()};Lifecycle.Event.ON_STOP->{foreground=false;ready=false;task?.cancel()};else->Unit}}
        lifecycle.addObserver(observer);onDispose {lifecycle.removeObserver(observer)}
    }
    LaunchedEffect(Unit) {refresh()}
    fun add(uris:List<android.net.Uri>) {
        picking=false
        val epoch=selectionEpoch
        if(uris.isNotEmpty()) task=scope.launch {try {graph.photoLibrary.addSelected(uris.map {it.toString()},epoch)} catch(e:CancellationException) {throw e} catch(_:Exception) {error=R.string.photo_error_selection} finally {ready=foreground && graph.photoLibrary.state.value.validated}}
    }
    val picker=rememberLauncherForActivityResult(PhotoPickerContract {settings.requestPhotoLocations}) {add(it)}
    val files=rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) {add(it)}
    val originalPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {originalLocations=graph.photoAccess.originalLocationsAllowed();refresh();graph.automaticPhotos.refresh()}
    val libraryPermission=rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {libraryAccess=graph.photoAccess.current();refresh();graph.automaticPhotos.refresh()}
    fun syncLibrary() {
        if(task?.isActive==true) return
        task=scope.launch {
            ready=false
            try {graph.photoLibrary.syncLibrary()}
            catch(e:CancellationException) {throw e}
            catch(_:Exception) {error=R.string.photo_error_sync}
            finally {libraryAccess=graph.photoAccess.current();ready=foreground && graph.photoLibrary.state.value.validated}
        }
    }
    BackHandler(onBack=back)
    val entries=page?.entries.orEmpty()
    LazyColumn(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("photo-library-content"),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {TextButton(onClick=back) {Text(stringResource(R.string.photo_back_settings))}}
        item {Text(stringResource(R.string.photo_title),style=MaterialTheme.typography.headlineSmall)}
        item {Text(stringResource(R.string.photo_privacy))}
        item {OutlinedButton(enabled=!sync.running && !picking,onClick={picking=true;scope.launch {try {selectionEpoch=graph.photoLibrary.captureSelection();picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))} catch(e:CancellationException) {picking=false;throw e} catch(_:Exception) {picking=false;error=R.string.photo_error_picker}}}) {Text(stringResource(R.string.photo_add))}}
        item {TextButton(enabled=!sync.running && !picking,onClick={picking=true;scope.launch {try {selectionEpoch=graph.photoLibrary.captureSelection();files.launch(arrayOf("image/*"))} catch(e:CancellationException) {picking=false;throw e} catch(_:Exception) {picking=false;error=R.string.photo_error_picker}}}) {Text(stringResource(R.string.photo_choose_files))}}
        item {TextButton(enabled=!sync.running,onClick={refresh()}) {Text(stringResource(R.string.photo_refresh))}}
        item {TextButton(enabled=!sync.running && !picking,modifier=Modifier.testTag("photo-library-options"),onClick={libraryAccess=graph.photoAccess.current();libraryOptions=true}) {Text(stringResource(R.string.photo_library_options))}}
        if(sync.running) item {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth());Text(if(sync.total>0) stringResource(R.string.photo_sync_progress,sync.processed,sync.total) else stringResource(R.string.photo_sync_processed,sync.processed))
            TextButton(onClick={task?.cancel();graph.automaticPhotos.stopCurrent()}) {Text(stringResource(R.string.photo_stop_sync))}
        }}
        sync.message?.let {item {Text(it)}}
        error?.let {item {Text(stringResource(it),color=MaterialTheme.colorScheme.error)}}
        if(!ready || page==null) item {Text(stringResource(R.string.photo_check_access))}
        else {
            item {Text(resources.getQuantityString(R.plurals.photo_saved_count,page!!.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),page!!.count),style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("photo-library-count"))}
            if(entries.isEmpty()) item {Text(stringResource(if(starts.size==1) R.string.photo_empty else R.string.photo_page_empty))}
            items(entries,key={it.asset.uri}) {entry->
                PhotoReferenceCard(entry,{selected=PhotoViewerDraft.open("library",entry.asset.uri)}) {modifier->PhotoImage(graph,entry,128,modifier)}
            }
            item {FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                TextButton(enabled=starts.size>1 && !sync.running,onClick={starts=starts.dropLast(1)}) {Text(stringResource(R.string.photo_previous_page))}
                TextButton(enabled=page!!.hasMore && !sync.running,onClick={val cursor=page?.nextCursor;if(cursor!=null && cursor!=starts.last()) starts=starts+cursor}) {Text(stringResource(R.string.photo_next_page))}
            }}
        }
    }
    if(libraryOptions) WorkspaceAlertDialog(modifier=Modifier.semantics {testTagsAsResourceId=true}.testTag("photo-library-dialog"),onDismissRequest={libraryOptions=false},title={Text(stringResource(R.string.photo_library_options))},text={Column(Modifier.semantics {testTagsAsResourceId=true}.testTag("photo-library-options-content").verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(photoLibraryAccessLabel(libraryAccess))
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(stringResource(R.string.photo_auto_sync),Modifier.weight(1f))
            Switch(modifier=Modifier.testTag("photo-auto-sync").semantics {contentDescription=autoLabel},checked=automatic.enabled,enabled=automatic.loaded && (libraryAccess!=PhotoLibraryAccess.None || automatic.enabled),onCheckedChange={enabled->scope.launch {try {graph.automaticPhotos.configure(enabled)} catch(e:CancellationException) {throw e} catch(_:Exception) {error=R.string.photo_error_auto}}})
        }
        Text(stringResource(R.string.photo_auto_description),style=MaterialTheme.typography.bodySmall)
        automatic.lastSuccess?.let {Text(stringResource(R.string.photo_last_auto,photoCaptureTime(it)),style=MaterialTheme.typography.bodySmall)}
        automatic.message?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
        automatic.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        Text(stringResource(if(originalLocations) R.string.photo_original_allowed else R.string.photo_original_denied))
        Text(stringResource(R.string.photo_locations_description),style=MaterialTheme.typography.bodySmall)
        TextButton(enabled=libraryAccess!=PhotoLibraryAccess.None,onClick={originalPermission.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)}) {Text(stringResource(R.string.photo_allow_original))}
        if(libraryAccess==PhotoLibraryAccess.None) Text(stringResource(R.string.photo_choose_access_first),style=MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text(stringResource(R.string.photo_ask_locations),Modifier.weight(1f))
            Switch(modifier=Modifier.testTag("photo-picker-locations").semantics {contentDescription=pickerLocationLabel},checked=settings.requestPhotoLocations,onCheckedChange={enabled->scope.launch {try {graph.preferences.update {it.copy(requestPhotoLocations=enabled)}} catch(e:CancellationException) {throw e} catch(_:Exception) {error=R.string.photo_error_preference}}})
        }
        Text(stringResource(R.string.photo_picker_description),style=MaterialTheme.typography.bodySmall)
        Text(stringResource(R.string.photo_sync_description))
        OutlinedButton(enabled=libraryAccess!=PhotoLibraryAccess.None && !sync.running,onClick={libraryOptions=false;syncLibrary()}) {Text(stringResource(R.string.photo_sync_accessible))}
        TextButton(onClick={context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:${context.packageName}")))}) {Text(stringResource(R.string.photo_open_permissions))}
    }},confirmButton={TextButton(onClick={libraryPermission.launch(graph.photoAccess.requestPermissions())}) {Text(stringResource(R.string.photo_choose_access))}},dismissButton={TextButton(modifier=Modifier.testTag("photo-library-close"),onClick={libraryOptions=false}) {Text(stringResource(R.string.photo_close_options))}})
    selected?.takeIf {it.owner=="library" && ready}?.let {draft->key(draft.id) {
        LibraryPhotoViewer(graph,draft.current,{selected=null},{removing=it},onCurrent={draft.current=it})
    }}
    removing?.let {reviewed->key(reviewed) {PhotoRemovalReview(removalRunning,{if(!removalRunning) removing=null}) {
        if(removalRunning || removing!=reviewed) return@PhotoRemovalReview
        removalRunning=true
        scope.launch {
            try {graph.photoLibrary.remove(reviewed);selected=null;removing=null}
            catch(e:CancellationException) {throw e}
            catch(_:Exception) {error=R.string.photo_error_remove;removing=null}
            finally {removalRunning=false}
        }
    }}}

}

@Composable internal fun PhotoReferenceCard(entry:PhotoLibraryEntry,open:()->Unit,image:@Composable (Modifier)->Unit) {
    val time=photoCaptureTime(entry.asset.capturedAt)
    val source=photoSourceLabel(entry)
    val description=stringResource(R.string.photo_reference_label,time,source)
    OutlinedCard(onClick=open,modifier=Modifier.fillMaxWidth().testTag("photo-library-card").semantics {contentDescription=description}) {
        Row(Modifier.clearAndSetSemantics {}.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            image(Modifier.size(72.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(time,style=MaterialTheme.typography.titleSmall)
                Text(source,style=MaterialTheme.typography.bodySmall)
                Text(stringResource(R.string.photo_open),color=MaterialTheme.colorScheme.primary)
            }
        }
    }
}

@Composable internal fun PhotoImage(graph:AppGraph,entry:PhotoLibraryEntry,pixels:Int,modifier:Modifier) {
    PhotoImageContent(entry.asset.uri,entry.asset.available,pixels,modifier,graph.photoLibrary::image,graph.photoLibrary::unavailable)
}
private data class DecodedPhoto(val uri:String,val pixels:Int,val bitmap:android.graphics.Bitmap)
@Composable internal fun PhotoImageContent(uri:String,available:Boolean,pixels:Int,modifier:Modifier,load:suspend(String,Int)->android.graphics.Bitmap,unavailable:suspend(String)->Unit) {
    var failed by remember(uri,available,pixels) {mutableStateOf(false)}
    val bitmap by produceState<DecodedPhoto?>(null,uri,available,pixels) {
        value=null
        if(available) try {value=DecodedPhoto(uri,pixels,load(uri,pixels))}
        catch(e:CancellationException) {throw e}
        catch(_:Exception) {
            failed=true
            try {unavailable(uri)} catch(e:CancellationException) {throw e} catch(_:Exception) { /* Clear-data can exclude late image callbacks. */ }
        }
    }
    // produceState retains its prior value until the replacement effect starts.
    val image=bitmap?.takeIf {available && !failed && it.uri==uri && it.pixels==pixels}?.bitmap
    if(image!=null) Image(image.asImageBitmap(),stringResource(R.string.photo_selected),modifier,contentScale=ContentScale.Fit)
    else Surface(modifier,color=MaterialTheme.colorScheme.surfaceContainer) {Box(Modifier.padding(8.dp)) {Text(stringResource(if(available && !failed) R.string.photo_loading else R.string.photo_unavailable),style=MaterialTheme.typography.bodySmall)}}
}
@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun PhotoPager(graph:AppGraph,entries:List<PhotoLibraryEntry>,initial:Int,close:()->Unit,remove:((String)->Unit)?=null,pageLabel:Boolean=true) {
    BlockVisitLinks()
    val pager=rememberPagerState(initialPage=initial,pageCount={entries.size})
    val scope=rememberCoroutineScope()
    WorkspaceDialog(fillRegion=true,onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("photo-pager-content").verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                TextButton(onClick=close) {Text(stringResource(R.string.photo_close))}
                Text(stringResource(if(pageLabel) R.string.photo_number_on_page else R.string.photo_number,pager.currentPage+1,entries.size),style=MaterialTheme.typography.titleLarge)
                HorizontalPager(pager,Modifier.fillMaxWidth().height(360.dp),key={entries[it].asset.uri}) {index->PhotoImage(graph,entries[index],1536,Modifier.fillMaxSize())}
                val entry=entries[pager.currentPage.coerceIn(entries.indices)]
                Text(photoCaptureTime(entry.asset.capturedAt))
                Text(photoSourceLabel(entry))
                FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    TextButton(enabled=pager.currentPage>0,onClick={scope.launch {pager.animateScrollToPage((pager.currentPage-1).coerceAtLeast(0))}}) {Text(stringResource(R.string.photo_previous))}
                    TextButton(enabled=pager.currentPage<entries.lastIndex,onClick={scope.launch {pager.animateScrollToPage((pager.currentPage+1).coerceAtMost(entries.lastIndex))}}) {Text(stringResource(R.string.photo_next))}
                }
                if(remove!=null) TextButton(onClick={remove(entry.asset.uri)}) {Text(stringResource(R.string.photo_remove_whereday),color=MaterialTheme.colorScheme.error)}
            }
        }
    }
}
