package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
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
    Column(Modifier.fillMaxSize().background(wd.surface)) {
    PlacesBackBar(stringResource(R.string.photo_back_settings),back)
    LazyColumn(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("photo-library-content"),contentPadding=PaddingValues(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {WdPageHeader(stringResource(R.string.photo_title),Modifier.padding(bottom=4.dp))}
        item {Text(stringResource(R.string.photo_privacy),Modifier.padding(horizontal=24.dp),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)}
        item {WdButton(stringResource(R.string.photo_add),{picking=true;scope.launch {try {selectionEpoch=graph.photoLibrary.captureSelection();picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))} catch(e:CancellationException) {picking=false;throw e} catch(_:Exception) {picking=false;error=R.string.photo_error_picker}}},Modifier.padding(horizontal=16.dp),emphasis=WdEmphasis.primary,enabled=!sync.running && !picking,icon=Icons.Outlined.AddPhotoAlternate)}
        item {WdCard(Modifier.padding(horizontal=16.dp)) {
            WdNavRow(stringResource(R.string.photo_choose_files),icon=Icons.Outlined.Folder,enabled=!sync.running && !picking,onClick={picking=true;scope.launch {try {selectionEpoch=graph.photoLibrary.captureSelection();files.launch(arrayOf("image/*"))} catch(e:CancellationException) {picking=false;throw e} catch(_:Exception) {picking=false;error=R.string.photo_error_picker}}})
            WdNavRow(stringResource(R.string.photo_refresh),icon=Icons.Outlined.Refresh,enabled=!sync.running,onClick={refresh()})
            WdNavRow(stringResource(R.string.photo_library_options),Modifier.testTag("photo-library-options"),icon=Icons.Outlined.Tune,divider=false,enabled=!sync.running && !picking,onClick={libraryAccess=graph.photoAccess.current();libraryOptions=true})
        }}
        if(sync.running) item {WdCard(Modifier.padding(horizontal=16.dp)) {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(10.dp)) {
            Row(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {WdLiveDot(color=wd.accent);Text(if(sync.total>0) stringResource(R.string.photo_sync_progress,sync.processed,sync.total) else stringResource(R.string.photo_sync_processed,sync.processed),style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=wd.textPrimary)}
            PlacesProgress(inset=false)
            WdButton(stringResource(R.string.photo_stop_sync),{task?.cancel();graph.automaticPhotos.stopCurrent()},icon=Icons.Outlined.Stop)
        }}}
        sync.message?.let {item {WdNotice(it,Modifier.padding(horizontal=16.dp))}}
        error?.let {item {WdNotice(stringResource(it),Modifier.padding(horizontal=16.dp),warning=true)}}
        if(!ready || page==null) item {Column {PlacesProgress();Text(stringResource(R.string.photo_check_access),Modifier.padding(horizontal=20.dp,vertical=4.dp),style=WdType.caption,color=wd.textMuted)}}
        else {
            item {Text(resources.getQuantityString(R.plurals.photo_saved_count,page!!.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),page!!.count),style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=wd.textMuted,modifier=Modifier.padding(start=24.dp,end=24.dp,top=8.dp).testTag("photo-library-count"))}
            if(entries.isEmpty()) item {WdEmptyState(stringResource(R.string.redesign_photos_empty_title),stringResource(if(starts.size==1) R.string.photo_empty else R.string.photo_page_empty),icon=Icons.Outlined.PhotoLibrary)}
            items(entries,key={it.asset.uri}) {entry->
                Box(Modifier.padding(horizontal=16.dp)) {PhotoReferenceCard(entry,{selected=PhotoViewerDraft.open("library",entry.asset.uri)}) {modifier->PhotoImage(graph,entry,128,modifier)}}
            }
            item {Row(Modifier.fillMaxWidth().padding(horizontal=16.dp),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                WdButton(stringResource(R.string.photo_previous_page),{starts=starts.dropLast(1)},Modifier.weight(1f),enabled=starts.size>1 && !sync.running)
                WdButton(stringResource(R.string.photo_next_page),{val cursor=page?.nextCursor;if(cursor!=null && cursor!=starts.last()) starts=starts+cursor},Modifier.weight(1f),enabled=page!!.hasMore && !sync.running)
            }}
        }
    }
    }
    if(libraryOptions) WorkspaceAlertDialog(modifier=Modifier.semantics {testTagsAsResourceId=true}.testTag("photo-library-dialog"),onDismissRequest={libraryOptions=false},title={Text(stringResource(R.string.photo_library_options),style=WdType.title2)},text={Column(Modifier.semantics {testTagsAsResourceId=true}.testTag("photo-library-options-content").verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(10.dp)) {
        val note=MaterialTheme.typography.bodySmall
        WdNotice(photoLibraryAccessLabel(libraryAccess),warning=libraryAccess==PhotoLibraryAccess.None,icon=Icons.Outlined.PhotoLibrary)
        PhotoOptionSwitch(stringResource(R.string.photo_auto_sync)) {
            Switch(modifier=Modifier.testTag("photo-auto-sync").semantics {contentDescription=autoLabel},checked=automatic.enabled,enabled=automatic.loaded && (libraryAccess!=PhotoLibraryAccess.None || automatic.enabled),colors=wdSwitchColors(),onCheckedChange={enabled->scope.launch {try {graph.automaticPhotos.configure(enabled)} catch(e:CancellationException) {throw e} catch(_:Exception) {error=R.string.photo_error_auto}}})
        }
        Text(stringResource(R.string.photo_auto_description),style=note,color=wd.textMuted)
        automatic.lastSuccess?.let {Text(stringResource(R.string.photo_last_auto,photoCaptureTime(it)),style=WdType.caption,color=wd.textMuted)}
        automatic.message?.let {Text(it,style=note,color=wd.textMuted)}
        automatic.error?.let {Text(it,style=MaterialTheme.typography.bodyMedium,color=wd.inkRust)}
        WdDivider(Modifier.padding(vertical=4.dp))
        Text(stringResource(if(originalLocations) R.string.photo_original_allowed else R.string.photo_original_denied),style=MaterialTheme.typography.bodyLarge,color=wd.textPrimary)
        Text(stringResource(R.string.photo_locations_description),style=note,color=wd.textMuted)
        WdButton(stringResource(R.string.photo_allow_original),{originalPermission.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)},enabled=libraryAccess!=PhotoLibraryAccess.None,icon=Icons.Outlined.LocationOn)
        if(libraryAccess==PhotoLibraryAccess.None) Text(stringResource(R.string.photo_choose_access_first),style=note,color=wd.inkRust)
        WdDivider(Modifier.padding(vertical=4.dp))
        PhotoOptionSwitch(stringResource(R.string.photo_ask_locations)) {
            Switch(modifier=Modifier.testTag("photo-picker-locations").semantics {contentDescription=pickerLocationLabel},checked=settings.requestPhotoLocations,colors=wdSwitchColors(),onCheckedChange={enabled->scope.launch {try {graph.preferences.update {it.copy(requestPhotoLocations=enabled)}} catch(e:CancellationException) {throw e} catch(_:Exception) {error=R.string.photo_error_preference}}})
        }
        Text(stringResource(R.string.photo_picker_description),style=note,color=wd.textMuted)
        WdDivider(Modifier.padding(vertical=4.dp))
        Text(stringResource(R.string.photo_sync_description),style=MaterialTheme.typography.bodyMedium,color=wd.textPrimary)
        WdButton(stringResource(R.string.photo_sync_accessible),{libraryOptions=false;syncLibrary()},enabled=libraryAccess!=PhotoLibraryAccess.None && !sync.running,icon=Icons.Outlined.Sync)
        TextButton(onClick={context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:${context.packageName}")))}) {Text(stringResource(R.string.photo_open_permissions),color=wd.accent)}
    }},confirmButton={TextButton(onClick={libraryPermission.launch(graph.photoAccess.requestPermissions())}) {Text(stringResource(R.string.photo_choose_access),fontWeight=FontWeight.SemiBold)}},dismissButton={TextButton(modifier=Modifier.testTag("photo-library-close"),onClick={libraryOptions=false}) {Text(stringResource(R.string.photo_close_options),color=wd.textMuted)}})
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

/** A label beside a Whereday switch; the switch keeps its own tag, description and callback. */
@Composable private fun PhotoOptionSwitch(label:String,switch:@Composable ()->Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min=48.dp),horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge,color=wd.textPrimary);switch()
    }
}

@Composable internal fun PhotoReferenceCard(entry:PhotoLibraryEntry,open:()->Unit,image:@Composable (Modifier)->Unit) {
    val time=photoCaptureTime(entry.asset.capturedAt)
    val source=photoSourceLabel(entry)
    val description=stringResource(R.string.photo_reference_label,time,source)
    val shape=RoundedCornerShape(16.dp)
    Box(Modifier.fillMaxWidth().clip(shape).background(wd.card).border(1.dp,wd.border,shape).clickable(role=Role.Button,onClick=open).testTag("photo-library-card").semantics {contentDescription=description}) {
        Row(Modifier.clearAndSetSemantics {}.padding(10.dp),horizontalArrangement=Arrangement.spacedBy(14.dp),verticalAlignment=Alignment.CenterVertically) {
            image(Modifier.size(72.dp).clip(RoundedCornerShape(10.dp)))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                Text(time,style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=wd.textPrimary)
                Text(source,style=WdType.caption2,color=wd.textMuted)
                Text(stringResource(R.string.photo_open),style=MaterialTheme.typography.bodyMedium.copy(fontWeight=FontWeight.SemiBold),color=wd.accent)
            }
            Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,null,Modifier.size(18.dp),tint=wd.textMuted)
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
    // Thumbnails fill their rounded tile as in iOS photo grids; the full viewer shows the whole frame.
    if(image!=null) Image(image.asImageBitmap(),stringResource(R.string.photo_selected),modifier.background(wd.dialTrack),contentScale=if(pixels<=256) ContentScale.Crop else ContentScale.Fit)
    else Box(modifier.background(wd.dialTrack).padding(8.dp),contentAlignment=Alignment.Center) {
        val loading=available && !failed
        Column(horizontalAlignment=Alignment.CenterHorizontally,verticalArrangement=Arrangement.spacedBy(4.dp)) {
            Icon(if(loading) Icons.Outlined.Image else Icons.Outlined.HideImage,null,Modifier.size(18.dp),tint=wd.textMuted)
            Text(stringResource(if(loading) R.string.photo_loading else R.string.photo_unavailable),style=WdType.caption2,color=wd.textMuted,textAlign=TextAlign.Center)
        }
    }
}
@Composable internal fun PhotoPager(graph:AppGraph,entries:List<PhotoLibraryEntry>,initial:Int,close:()->Unit,remove:((String)->Unit)?=null,pageLabel:Boolean=true) {
    BlockVisitLinks()
    val pager=rememberPagerState(initialPage=initial,pageCount={entries.size})
    val scope=rememberCoroutineScope()
    WorkspaceDialog(fillRegion=true,onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=wd.surface,contentColor=wd.textPrimary) {
            Column(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("photo-pager-content").verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                PhotoViewerTopRow(close,Modifier) {Text(stringResource(if(pageLabel) R.string.photo_number_on_page else R.string.photo_number,pager.currentPage+1,entries.size),style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=wd.textMuted)}
                HorizontalPager(pager,Modifier.fillMaxWidth().height(360.dp).clip(RoundedCornerShape(16.dp)).background(wd.dialTrack),key={entries[it].asset.uri}) {index->PhotoImage(graph,entries[index],1536,Modifier.fillMaxSize())}
                val entry=entries[pager.currentPage.coerceIn(entries.indices)]
                PhotoViewerDetails {
                    Text(photoCaptureTime(entry.asset.capturedAt),style=WdType.title3,color=wd.textPrimary)
                    Text(photoSourceLabel(entry),style=WdType.caption,color=wd.textMuted)
                }
                PhotoViewerControls(pager.currentPage>0,pager.currentPage<entries.lastIndex,{scope.launch {pager.animateScrollToPage((pager.currentPage-1).coerceAtLeast(0))}},{scope.launch {pager.animateScrollToPage((pager.currentPage+1).coerceAtMost(entries.lastIndex))}})
                if(remove!=null) WdButton(stringResource(R.string.photo_remove_whereday),{remove(entry.asset.uri)},emphasis=WdEmphasis.destructive,icon=Icons.Outlined.Delete)
            }
        }
    }
}
