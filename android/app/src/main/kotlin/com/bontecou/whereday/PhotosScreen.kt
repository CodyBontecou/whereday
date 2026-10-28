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
    var selected by rememberSaveable {mutableStateOf<String?>(null)}
    var removing by remember {mutableStateOf<String?>(null)}
    var removalRunning by remember {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var page by remember {mutableStateOf<PhotoLibraryPage?>(null)}
    LaunchedEffect(starts.last()) {
        page=null
        graph.photos.observeLibrary(starts.last()).catch {error="Photos could not be loaded. Try refreshing."}.collect {page=it}
    }
    fun refresh() {libraryAccess=graph.photoAccess.current();originalLocations=graph.photoAccess.originalLocationsAllowed();if(task?.isActive!=true) task=scope.launch {ready=false;try {graph.photoLibrary.refresh()} catch(e:CancellationException) {throw e} catch(_:Exception) {error="Photo access could not be checked. Try refreshing."} finally {ready=foreground && graph.photoLibrary.state.value.validated}}}
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
        if(uris.isNotEmpty()) task=scope.launch {try {graph.photoLibrary.addSelected(uris.map {it.toString()},epoch)} catch(e:CancellationException) {throw e} catch(_:Exception) {error="Photo selection expired. Select the photos again."} finally {ready=foreground && graph.photoLibrary.state.value.validated}}
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
            catch(_:Exception) {error="Photo library sync could not start. Check access and try again."}
            finally {libraryAccess=graph.photoAccess.current();ready=foreground && graph.photoLibrary.state.value.validated}
        }
    }
    BackHandler(onBack=back)
    val entries=page?.entries.orEmpty()
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {TextButton(onClick=back) {Text("Back to Settings")}}
        item {Text("Photos",style=MaterialTheme.typography.headlineSmall)}
        item {Text("Choose photos to show them with your journal. Original files stay in their library. Only local references and matching metadata are saved.")}
        item {OutlinedButton(enabled=!sync.running && !picking,onClick={picking=true;scope.launch {try {selectionEpoch=graph.photoLibrary.captureSelection();picker.launch(PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly))} catch(e:CancellationException) {picking=false;throw e} catch(_:Exception) {picking=false;error="The photo picker could not open."}}}) {Text("Add photos")}}
        item {TextButton(enabled=!sync.running && !picking,onClick={picking=true;scope.launch {try {selectionEpoch=graph.photoLibrary.captureSelection();files.launch(arrayOf("image/*"))} catch(e:CancellationException) {picking=false;throw e} catch(_:Exception) {picking=false;error="The photo picker could not open."}}}) {Text("Choose photo files")}}
        item {TextButton(enabled=!sync.running,onClick={refresh()}) {Text("Refresh photos")}}
        item {TextButton(enabled=!sync.running && !picking,onClick={libraryAccess=graph.photoAccess.current();libraryOptions=true}) {Text("Library access and sync")}}
        if(sync.running) item {Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
            LinearProgressIndicator(Modifier.fillMaxWidth());Text(if(sync.total>0) "Checking photos · ${sync.processed} of ${sync.total}" else "Checking photos · ${sync.processed}")
            TextButton(onClick={task?.cancel();graph.automaticPhotos.stopCurrent()}) {Text("Stop photo sync")}
        }}
        sync.message?.let {item {Text(it)}}
        error?.let {item {Text(it,color=MaterialTheme.colorScheme.error)}}
        if(!ready || page==null) item {Text("Checking photo access…")}
        else {
            item {Text(if(page!!.count==1L) "1 saved photo reference" else "${page!!.count} saved photo references",style=MaterialTheme.typography.titleMedium)}
            if(entries.isEmpty()) item {Text(if(starts.size==1) "No photos selected yet." else "No more photos on this page.")}
            items(entries,key={it.asset.uri}) {entry->
                PhotoReferenceCard(entry,{selected=entry.asset.uri}) {modifier->PhotoImage(graph,entry,128,modifier)}
            }
            item {Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                TextButton(enabled=starts.size>1 && !sync.running,onClick={starts=starts.dropLast(1)}) {Text("Previous photos")}
                TextButton(enabled=page!!.hasMore && !sync.running,onClick={val cursor=page?.nextCursor;if(cursor!=null && cursor!=starts.last()) starts=starts+cursor}) {Text("Next photos")}
            }}
        }
    }
    if(libraryOptions) AlertDialog(onDismissRequest={libraryOptions=false},title={Text("Library access and sync")},text={Column(Modifier.verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        Text(when(libraryAccess) {PhotoLibraryAccess.None->"Library access: not allowed. Use the system picker to choose individual photos.";PhotoLibraryAccess.Partial->"Library access: selected photos only.";PhotoLibraryAccess.Full->"Library access: all photos."})
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("Automatic photo sync",Modifier.weight(1f))
            Switch(modifier=Modifier.semantics {contentDescription="Automatic photo sync"},checked=automatic.enabled,enabled=automatic.loaded && (libraryAccess!=PhotoLibraryAccess.None || automatic.enabled),onCheckedChange={enabled->scope.launch {try {graph.automaticPhotos.configure(enabled)} catch(e:CancellationException) {throw e} catch(_:Exception) {error="Automatic photo sync could not be saved."}}})
        }
        Text("When enabled, Whereday checks your allowed library photos while the app is open and after library changes. Sync covers your map dates and journal history.",style=MaterialTheme.typography.bodySmall)
        automatic.lastSuccess?.let {Text("Last automatic sync: ${timeText(it)}",style=MaterialTheme.typography.bodySmall)}
        automatic.message?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
        automatic.error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
        Text(if(originalLocations) "Original photo locations: allowed" else "Original photo locations: not allowed")
        Text("Photo locations can place pictures on your map. Nearby route or visit matches remain available when locations are missing.",style=MaterialTheme.typography.bodySmall)
        TextButton(enabled=libraryAccess!=PhotoLibraryAccess.None,onClick={originalPermission.launch(android.Manifest.permission.ACCESS_MEDIA_LOCATION)}) {Text("Allow original photo locations")}
        if(libraryAccess==PhotoLibraryAccess.None) Text("Choose library access first to allow original photo locations.",style=MaterialTheme.typography.bodySmall)
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            Text("Ask for locations in photo picker",Modifier.weight(1f))
            Switch(modifier=Modifier.semantics {contentDescription="Ask for locations in photo picker"},checked=settings.requestPhotoLocations,onCheckedChange={enabled->scope.launch {try {graph.preferences.update {it.copy(requestPhotoLocations=enabled)}} catch(e:CancellationException) {throw e} catch(_:Exception) {error="Photo picker preference could not be saved."}}})
        }
        Text("Supported pickers ask whether to include locations. Your choice in the picker controls what is shared.",style=MaterialTheme.typography.bodySmall)
        Text("Library sync reads capture times and available location metadata for photos Android allows. Original photos stay in their library. Choose Sync accessible library for a manual check, or enable Automatic photo sync.")
        OutlinedButton(enabled=libraryAccess!=PhotoLibraryAccess.None && !sync.running,onClick={libraryOptions=false;syncLibrary()}) {Text("Sync accessible library")}
        TextButton(onClick={context.startActivity(android.content.Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,android.net.Uri.parse("package:${context.packageName}")))}) {Text("Open app permissions")}
    }},confirmButton={TextButton(onClick={libraryPermission.launch(graph.photoAccess.requestPermissions())}) {Text("Choose library access")}},dismissButton={TextButton(onClick={libraryOptions=false}) {Text("Close library options")}})
    val index=entries.indexOfFirst {it.asset.uri==selected}
    LaunchedEffect(page,selected) {if(page!=null && selected!=null && index<0) selected=null}
    if(selected!=null && ready && index>=0) key(selected) {LibraryPhotoViewer(graph,selected!!,{selected=null},{removing=it})}
    if(removing!=null) AlertDialog(onDismissRequest={if(!removalRunning) removing=null},title={Text("Remove this photo reference?")},text={Text("The original photo stays in its library. This removes its links and metadata from Whereday.")},confirmButton={TextButton(enabled=!removalRunning,onClick={val uri=removing ?: return@TextButton;if(removalRunning) return@TextButton;removalRunning=true;scope.launch {
        try {graph.photoLibrary.remove(uri);selected=null;removing=null}
        catch(e:CancellationException) {throw e}
        catch(_:Exception) {error="The photo reference could not be removed.";removing=null}
        finally {removalRunning=false}
    }}) {Text("Remove photo")}},dismissButton={TextButton(enabled=!removalRunning,onClick={removing=null}) {Text("Cancel")}})
}

@Composable internal fun PhotoReferenceCard(entry:PhotoLibraryEntry,open:()->Unit,image:@Composable (Modifier)->Unit) {
    val time=entry.asset.capturedAt?.let(::timeText) ?: "Capture time unavailable"
    val source=photoSourceLabel(entry)
    OutlinedCard(onClick=open,modifier=Modifier.fillMaxWidth().testTag("photo-library-card").semantics {contentDescription="Photo taken at $time. $source"}) {
        Row(Modifier.clearAndSetSemantics {}.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            image(Modifier.size(72.dp))
            Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                Text(time,style=MaterialTheme.typography.titleSmall)
                Text(source,style=MaterialTheme.typography.bodySmall)
                Text("Open photo",color=MaterialTheme.colorScheme.primary)
            }
        }
    }
}

internal fun photoSourceLabel(entry:PhotoLibraryEntry):String=if(!entry.asset.available) "Unavailable · reselect to restore access" else when(entry.coordinateSource) {
    "photo_gps"->"Photo GPS"
    "inferred_route"->"Inferred from route"
    "inferred_visit"->"Inferred from visit"
    else->"No location match"
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
    if(image!=null) Image(image.asImageBitmap(),"Selected photo",modifier,contentScale=ContentScale.Fit)
    else Surface(modifier,color=MaterialTheme.colorScheme.surfaceContainer) {Box(Modifier.padding(8.dp)) {Text(if(available && !failed) "Loading photo…" else "Photo unavailable",style=MaterialTheme.typography.bodySmall)}}
}
@Composable internal fun PhotoPager(graph:AppGraph,entries:List<PhotoLibraryEntry>,initial:Int,close:()->Unit,remove:((String)->Unit)?=null,pageLabel:Boolean=true) {
    val pager=rememberPagerState(initialPage=initial,pageCount={entries.size})
    val scope=rememberCoroutineScope()
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                TextButton(onClick=close) {Text("Close photo")}
                Text("Photo ${pager.currentPage+1} of ${entries.size}"+if(pageLabel) " on this page" else "",style=MaterialTheme.typography.titleLarge)
                HorizontalPager(pager,Modifier.fillMaxWidth().height(360.dp),key={entries[it].asset.uri}) {index->PhotoImage(graph,entries[index],1536,Modifier.fillMaxSize())}
                val entry=entries[pager.currentPage.coerceIn(entries.indices)]
                Text(entry.asset.capturedAt?.let(::timeText) ?: "Capture time unavailable")
                Text(photoSourceLabel(entry))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton(enabled=pager.currentPage>0,onClick={scope.launch {pager.animateScrollToPage((pager.currentPage-1).coerceAtLeast(0))}}) {Text("Previous photo")}
                    TextButton(enabled=pager.currentPage<entries.lastIndex,onClick={scope.launch {pager.animateScrollToPage((pager.currentPage+1).coerceAtMost(entries.lastIndex))}}) {Text("Next photo")}
                }
                if(remove!=null) TextButton(onClick={remove(entry.asset.uri)}) {Text("Remove photo from Whereday",color=MaterialTheme.colorScheme.error)}
            }
        }
    }
}
