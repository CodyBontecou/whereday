package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.CancellationException

internal data class PhotoDirectoryUi(val directory:PhotoPlaceDirectory?=null,val counts:PhotoPlaceCounts?=null,val message:Int?=null,val markers:List<PhotoAreaSummary> = emptyList(),val retry:()->Unit={})

/** Eligibility comes from the same foreground validation gate that protects the sampled map photos. */
@Composable internal fun photoDirectory(graph:AppGraph,range:HistoryRange,eligible:Boolean):PhotoDirectoryUi {
    val currentEligibility by rememberUpdatedState(eligible)
    var retry by remember {mutableIntStateOf(0)}
    var ui by remember(graph,range) {mutableStateOf(PhotoDirectoryUi())}
    LaunchedEffect(graph,range,retry) {
        try {
            graph.photoPlaces.observe(range,snapshotFlow {currentEligibility}).collect {state->
                ui=when(state) {
                    PhotoPlaceDirectoryState.Unavailable->PhotoDirectoryUi()
                    PhotoPlaceDirectoryState.Loading->PhotoDirectoryUi(message=R.string.photo_places_loading)
                    PhotoPlaceDirectoryState.Failed->PhotoDirectoryUi(message=R.string.photo_places_error)
                    is PhotoPlaceDirectoryState.Ready->try {
                        val counts=state.directory.counts()
                        if(state.directory.active.value) PhotoDirectoryUi(state.directory,counts,markers=state.directory.markers()) else PhotoDirectoryUi()
                    } catch(error:CancellationException) {throw error}
                    catch(_:Exception) {if(state.directory.active.value) PhotoDirectoryUi(message=R.string.photo_places_error) else PhotoDirectoryUi()}
                }
            }
        } catch(error:CancellationException) {throw error}
        catch(_:Exception) {ui=PhotoDirectoryUi(message=R.string.photo_places_error)}
    }
    val active=ui.directory?.active?.collectAsStateWithLifecycle()?.value==true
    return if(eligible && active) ui.copy(retry={retry++}) else PhotoDirectoryUi(message=ui.message.takeIf {eligible},retry={retry++})
}

@Composable internal fun PhotoPlaceBrowser(graph:AppGraph,directory:PhotoPlaceDirectory,counts:PhotoPlaceCounts,close:()->Unit,areas:Set<String>?=null,title:String?=null,initialPlace:PhotoPlaceSummary?=null,state:PhotoDirectoryDraft?=null,
    image:@Composable (PhotoMoment,Modifier,Int)->Unit={photo,modifier,size->PhotoImage(graph,photo.libraryEntry(),size,modifier)}) {
    val context=LocalContext.current
    fun invalidated() {if(!context.activityConfigurationChange()) close()}
    val active by directory.active.collectAsStateWithLifecycle()
    if(!active) {LaunchedEffect(Unit) {invalidated()};return}
    BlockVisitLinks()
    val draft=state ?: rememberSaveable(saver=PhotoDirectoryDraft.Saver) {PhotoDirectoryDraft(directory.identity,initialPlace?.id)}
    if(draft.snapshot!=directory.identity) {LaunchedEffect(directory.identity) {close()};return}
    val valid by produceState<Boolean?>(null,directory,draft) {
        try {
            val d=draft.directoryPage;val c=draft.collectionPage
            value=directory.validPlacePage(d.page,d.after,areas) && (draft.place?.let {directory.validPhotoPage(it,c.page,c.after)} ?: (c==PhotoPageCursor()))
        } catch(error:CancellationException) {throw error}
        catch(_:Exception) {value=false}
    }
    if(valid!=true) {
        if(valid==false) LaunchedEffect(Unit) {invalidated()}
        PhotoBrowserLoading(close)
        return
    }
    val scope=rememberCoroutineScope()
    var paging by remember {mutableStateOf(false)}
    val selectedPlace=draft.place
    val loaded by produceState<Pair<Long,PhotoPlaceSummary?>?>(null,directory,selectedPlace) {
        value=null
        if(selectedPlace!=null) try {value=selectedPlace to directory.place(selectedPlace)}
        catch(error:CancellationException) {throw error}
        catch(_:Exception) {invalidated()}
    }
    val place=initialPlace?.takeIf {it.id==selectedPlace} ?: loaded?.takeIf {it.first==selectedPlace}?.second
    if(selectedPlace!=null && loaded?.let {it.first==selectedPlace && it.second==null}==true) {LaunchedEffect(selectedPlace) {invalidated()};return}
    val back={if(selectedPlace!=null && initialPlace==null) draft.select(null) else close()}
    if(selectedPlace!=null && place==null) {
        PhotoBrowserLoading(back)
        return
    }
    fun previous(collection:Boolean) {
        if(paging) return
        val before=if(collection) draft.collectionPage else draft.directoryPage
        if(before.page==0) return
        paging=true
        scope.launch {
            try {
                val after=if(collection) directory.previousPhotoCursor(checkNotNull(selectedPlace),before.after) else directory.previousPlaceCursor(before.after,areas)
                if(directory.active.value && (if(collection) draft.collectionPage else draft.directoryPage)==before) {
                    if(collection) draft.collectionPage=PhotoPageCursor(before.page-1,after) else draft.directoryPage=PhotoPageCursor(before.page-1,after)
                }
            } catch(error:CancellationException) {throw error}
            catch(_:Exception) {invalidated()}
            finally {paging=false}
        }
    }
    val shownDirectory=draft.directoryPage;val shownCollection=draft.collectionPage
    WorkspaceDialog(fillRegion=true,onDismissRequest=back,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        BackHandler(onBack=back)
        Surface(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(20.dp)) {
            val headerLimit=maxHeight*.35f
            Column(Modifier.fillMaxSize().testTag("photo-place-browser-content"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                TextButton(modifier=Modifier.testTag("photo-places-close"),onClick=back) {Text(stringResource(if(place!=null && initialPlace==null) R.string.photo_places_back else R.string.photo_places_close))}
                if(place==null) {
                    Column(Modifier.heightIn(max=headerLimit).verticalScroll(rememberScrollState()).testTag("photo-place-info"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(title ?: stringResource(R.string.photo_places_all),modifier=Modifier.testTag("photo-place-directory-title"),style=MaterialTheme.typography.headlineSmall)
                        Text(stringResource(if(areas==null) R.string.photo_places_range_description else R.string.photo_places_area_description))
                        val resources=LocalContext.current.resources
                        Text(stringResource(R.string.photo_places_summary,resources.getQuantityString(R.plurals.photo_places_count,counts.places.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),counts.places),resources.getQuantityString(R.plurals.photo_places_photo_count,counts.photos.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),counts.photos)),style=MaterialTheme.typography.titleMedium,modifier=Modifier.testTag("photo-places-summary"))
                    }
                    PlaceDirectoryPage(directory,areas,shownDirectory.after,shownDirectory.page,!paging,{previous(false)},{last->if(draft.directoryPage==shownDirectory) draft.directoryPage=shownDirectory.next(last)}) {draft.select(it.id)}
                } else key(place.id) {
                    Column(Modifier.heightIn(max=headerLimit).verticalScroll(rememberScrollState()).testTag("photo-place-info"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.photo_places_here),modifier=Modifier.testTag("photo-place-collection-title"),style=MaterialTheme.typography.headlineSmall)
                        Text(photoPlaceAtText(place))
                        Text(stringResource(R.string.timeline_time_range,photoCaptureTime(place.first),photoCaptureTime(place.last)),style=MaterialTheme.typography.bodySmall)
                    }
                    PlacePhotoCollection(directory,place.id,shownCollection,!paging,{previous(true)},{last->if(draft.collectionPage==shownCollection) draft.collectionPage=shownCollection.next(last)},{draft.photo=PhotoViewerDraft.open("${directory.identity}:${place.id}",it)}) {photo,modifier->image(photo,modifier,128)}
                }
            }
            }
        }
    }
    if(place!=null) draft.photo?.takeIf {it.owner=="${directory.identity}:${place.id}"}?.let {photo->key(photo.id) {
        DirectoryPhotoViewer(directory,place.id,photo.current,{if(!context.activityConfigurationChange()) draft.photo=null},onCurrent={photo.current=it}) {entry,modifier->image(entry,modifier,1536)}
    }}
}

private data class DirectoryPage<T>(val entries:List<T>?=null,val more:Boolean=false,val failed:Boolean=false,val cursor:Long?=null)

@Composable private fun ColumnScope.PlaceDirectoryPage(directory:PhotoPlaceDirectory,areas:Set<String>?,after:Long,page:Int,enabled:Boolean,previous:()->Unit,next:(Long)->Unit,select:(PhotoPlaceSummary)->Unit) {
    var retry by remember {mutableIntStateOf(0)}
    val pending by produceState(DirectoryPage<PhotoPlaceSummary>(),directory,areas,after,retry) {
        value=DirectoryPage()
        try {val rows=directory.places(after,51,areas=areas);value=DirectoryPage(rows.take(50),rows.size>50,cursor=after)}
        catch(error:CancellationException) {throw error}
        catch(_:Exception) {value=DirectoryPage(failed=true,cursor=after)}
    }
    val state=pending.takeIf {it.cursor==after} ?: DirectoryPage()
    if(state.failed) {Text(stringResource(R.string.photo_places_error),color=MaterialTheme.colorScheme.error);TextButton(onClick={retry++}) {Text(stringResource(R.string.photo_places_retry))}}
    else if(state.entries==null) LinearProgressIndicator(Modifier.fillMaxWidth())
    else if(state.entries!!.isEmpty()) Text(stringResource(R.string.photo_places_empty))
    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("photo-places-list"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        items(state.entries.orEmpty(),key={it.id}) {place->
            OutlinedCard(onClick={select(place)},modifier=Modifier.fillMaxWidth().testTag("photo-place-card")) {
                Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
                    Text(photoPlaceAtText(place),style=MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.timeline_time_range,photoCaptureTime(place.first),photoCaptureTime(place.last)),style=MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.photo_places_open),style=MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
    DirectoryPageControls(page,enabled && state.entries!=null && page>0,enabled && state.more,previous,{state.entries?.lastOrNull()?.let {next(it.id)}})
}

@Composable private fun ColumnScope.PlacePhotoCollection(directory:PhotoPlaceDirectory,place:Long,cursor:PhotoPageCursor,enabled:Boolean,previous:()->Unit,next:(Long)->Unit,select:(String)->Unit,image:@Composable (PhotoMoment,Modifier)->Unit) {
    var retry by remember {mutableIntStateOf(0)}
    val pending by produceState(DirectoryPage<PhotoPlaceEntry>(),directory,place,cursor.after,retry) {
        value=DirectoryPage()
        try {val rows=directory.photos(place,cursor.after,51);value=DirectoryPage(rows.take(50),rows.size>50,cursor=cursor.after)}
        catch(error:CancellationException) {throw error}
        catch(_:Exception) {value=DirectoryPage(failed=true,cursor=cursor.after)}
    }
    val state=pending.takeIf {it.cursor==cursor.after} ?: DirectoryPage()
    if(state.failed) {Text(stringResource(R.string.photo_load_failed),color=MaterialTheme.colorScheme.error);TextButton(onClick={retry++}) {Text(stringResource(R.string.photo_retry))}}
    else if(state.entries==null) LinearProgressIndicator(Modifier.fillMaxWidth())
    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("photo-place-photos-list"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        items(state.entries.orEmpty(),key={it.photo.uri}) {entry->
            OutlinedCard(onClick={select(entry.photo.uri)},modifier=Modifier.fillMaxWidth()) {
                Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                    image(entry.photo,Modifier.size(56.dp))
                    Column(Modifier.weight(1f)) {Text(photoCaptureTime(entry.photo.takenAt));Text(stringResource(R.string.photo_open),style=MaterialTheme.typography.labelMedium)}
                }
            }
        }
    }
    DirectoryPageControls(cursor.page,enabled && state.entries!=null && cursor.page>0,enabled && state.more,previous,{state.entries?.lastOrNull()?.let {next(it.sequence)}})
}

@OptIn(ExperimentalLayoutApi::class)
@Composable private fun DirectoryPageControls(page:Int,previousEnabled:Boolean,nextEnabled:Boolean,previous:()->Unit,next:()->Unit) {
    FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp)) {
        TextButton(enabled=previousEnabled,onClick=previous,modifier=Modifier.testTag("photo-places-previous")) {Text(stringResource(R.string.photo_places_previous))}
        Text(stringResource(R.string.photo_places_page,page+1),Modifier.testTag("photo-places-page").padding(top=12.dp))
        TextButton(enabled=nextEnabled,onClick=next,modifier=Modifier.testTag("photo-places-next")) {Text(stringResource(R.string.photo_places_next))}
    }
}

@Composable private fun DirectoryPhotoViewer(directory:PhotoPlaceDirectory,place:Long,selected:String,close:()->Unit,onCurrent:(String)->Unit={},image:@Composable (PhotoMoment,Modifier)->Unit) {
    val opening=remember {selected}
    val rank by produceState<Int?>(null,directory,place,opening) {
        try {value=directory.photoRank(place,opening);if(value==null) close()}
        catch(error:CancellationException) {throw error}
        catch(_:Exception) {close()}
    }
    rank?.let {initial->
        PagedPhotoEntryViewer(initial,{directory.photoPagingSource(place)},opening,close,{it.photo.uri},{entry->
            Text(photoCaptureTime(entry.photo.takenAt));Text(photoSourceLabel(entry.photo.libraryEntry()))
        },onCurrent=onCurrent) {entry,modifier->image(entry.photo,modifier)}
    }
}

@Composable private fun photoPlaceAtText(place:PhotoPlaceSummary):String {
    val resources=LocalContext.current.resources
    val locale=LocalConfiguration.current.locales[0]
    val coordinate=stringResource(R.string.photo_places_coordinates,String.format(locale,"%.5f",place.coordinate.latitude),String.format(locale,"%.5f",place.coordinate.longitude))
    return stringResource(R.string.photo_places_at,resources.getQuantityString(R.plurals.photo_places_photo_count,place.photoCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),place.photoCount),coordinate)
}
