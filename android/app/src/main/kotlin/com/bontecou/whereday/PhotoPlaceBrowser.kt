package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items as gridItems
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.PhotoCamera
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import kotlinx.coroutines.launch
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
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
        Surface(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true},color=wd.surface,contentColor=wd.textPrimary) {
            BoxWithConstraints(Modifier.fillMaxSize().padding(horizontal=16.dp,vertical=12.dp)) {
            val headerLimit=maxHeight*.35f
            Column(Modifier.fillMaxSize().testTag("photo-place-browser-content"),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                WdBackChip(stringResource(if(place!=null && initialPlace==null) R.string.photo_places_back else R.string.photo_places_close),back,Modifier.testTag("photo-places-close"))
                if(place==null) {
                    Column(Modifier.heightIn(max=headerLimit).verticalScroll(rememberScrollState()).testTag("photo-place-info").padding(horizontal=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        val resources=LocalContext.current.resources
                        Text(stringResource(R.string.photo_places_summary,resources.getQuantityString(R.plurals.photo_places_count,counts.places.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),counts.places),resources.getQuantityString(R.plurals.photo_places_photo_count,counts.photos.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),counts.photos)),style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=wd.textMuted,modifier=Modifier.testTag("photo-places-summary"))
                        Text(title ?: stringResource(R.string.photo_places_all),modifier=Modifier.testTag("photo-place-directory-title").semantics {heading()},style=WdType.title,color=wd.textPrimary)
                        Text(stringResource(if(areas==null) R.string.photo_places_range_description else R.string.photo_places_area_description),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
                    }
                    PlaceDirectoryPage(directory,areas,shownDirectory.after,shownDirectory.page,!paging,{previous(false)},{last->if(draft.directoryPage==shownDirectory) draft.directoryPage=shownDirectory.next(last)}) {draft.select(it.id)}
                } else key(place.id) {
                    Column(Modifier.heightIn(max=headerLimit).verticalScroll(rememberScrollState()).testTag("photo-place-info").padding(horizontal=8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                        Text(stringResource(R.string.timeline_time_range,photoCaptureTime(place.first),photoCaptureTime(place.last)),style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=wd.textMuted)
                        Text(stringResource(R.string.photo_places_here),modifier=Modifier.testTag("photo-place-collection-title").semantics {heading()},style=WdType.title,color=wd.textPrimary)
                        Text(photoPlaceAtText(place),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
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
    if(state.failed) WdNotice(stringResource(R.string.photo_places_error),warning=true,action={WdButton(stringResource(R.string.photo_places_retry),{retry++},icon=Icons.Outlined.Refresh)})
    else if(state.entries==null) PlacesProgress(inset=false)
    else if(state.entries!!.isEmpty()) WdEmptyState(stringResource(R.string.redesign_photo_places_empty_title),stringResource(R.string.photo_places_empty),icon=Icons.Outlined.PhotoLibrary)
    LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("photo-places-list"),verticalArrangement=Arrangement.spacedBy(8.dp)) {
        items(state.entries.orEmpty(),key={it.id}) {place->
            val shape=RoundedCornerShape(16.dp)
            Row(Modifier.fillMaxWidth().clip(shape).background(wd.card).border(1.dp,wd.border,shape).clickable(role=Role.Button) {select(place)}.testTag("photo-place-card").padding(16.dp),
                horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
                Box(Modifier.size(36.dp).clip(CircleShape).background(placeInk(place.id.toString()).copy(alpha=0.14f)),contentAlignment=Alignment.Center) {
                    Icon(Icons.Outlined.PhotoCamera,null,Modifier.size(18.dp),tint=placeInk(place.id.toString()))
                }
                Column(Modifier.weight(1f),verticalArrangement=Arrangement.spacedBy(3.dp)) {
                    Text(photoPlaceAtText(place),style=MaterialTheme.typography.bodyLarge.copy(fontWeight=FontWeight.SemiBold),color=wd.textPrimary)
                    Text(stringResource(R.string.timeline_time_range,photoCaptureTime(place.first),photoCaptureTime(place.last)),style=WdType.caption2,color=wd.textMuted)
                    Text(stringResource(R.string.photo_places_open),style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=wd.accent)
                }
                Icon(Icons.AutoMirrored.Filled.KeyboardArrowRight,null,Modifier.size(18.dp),tint=wd.textMuted)
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
    if(state.failed) WdNotice(stringResource(R.string.photo_load_failed),warning=true,action={WdButton(stringResource(R.string.photo_retry),{retry++},icon=Icons.Outlined.Refresh)})
    else if(state.entries==null) PlacesProgress(inset=false)
    // A grid of rounded photo tiles, as in the iOS photo moments.
    LazyVerticalGrid(GridCells.Adaptive(104.dp),Modifier.weight(1f).fillMaxWidth().testTag("photo-place-photos-list"),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        gridItems(state.entries.orEmpty(),key={it.photo.uri}) {entry->
            Column(Modifier.clip(RoundedCornerShape(12.dp)).clickable(role=Role.Button) {select(entry.photo.uri)},verticalArrangement=Arrangement.spacedBy(4.dp)) {
                image(entry.photo,Modifier.fillMaxWidth().aspectRatio(1f).clip(RoundedCornerShape(12.dp)))
                Text(photoCaptureTime(entry.photo.takenAt),style=WdType.caption2,color=wd.textPrimary,maxLines=2)
                Text(stringResource(R.string.photo_open),style=WdType.caption2.copy(fontWeight=FontWeight.SemiBold),color=wd.accent)
            }
        }
    }
    DirectoryPageControls(cursor.page,enabled && state.entries!=null && cursor.page>0,enabled && state.more,previous,{state.entries?.lastOrNull()?.let {next(it.sequence)}})
}

@Composable private fun DirectoryPageControls(page:Int,previousEnabled:Boolean,nextEnabled:Boolean,previous:()->Unit,next:()->Unit) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalAlignment=Alignment.CenterVertically) {
        WdButton(stringResource(R.string.photo_places_previous),previous,Modifier.weight(1f).testTag("photo-places-previous"),enabled=previousEnabled)
        Text(stringResource(R.string.photo_places_page,page+1),Modifier.testTag("photo-places-page").padding(horizontal=4.dp),style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=wd.textMuted)
        WdButton(stringResource(R.string.photo_places_next),next,Modifier.weight(1f).testTag("photo-places-next"),enabled=nextEnabled)
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
            Text(photoCaptureTime(entry.photo.takenAt),style=WdType.title3,color=wd.textPrimary);Text(photoSourceLabel(entry.photo.libraryEntry()),style=WdType.caption,color=wd.textMuted)
        },onCurrent=onCurrent) {entry,modifier->image(entry.photo,modifier)}
    }
}

@Composable private fun photoPlaceAtText(place:PhotoPlaceSummary):String {
    val resources=LocalContext.current.resources
    val locale=LocalConfiguration.current.locales[0]
    val coordinate=stringResource(R.string.photo_places_coordinates,String.format(locale,"%.5f",place.coordinate.latitude),String.format(locale,"%.5f",place.coordinate.longitude))
    return stringResource(R.string.photo_places_at,resources.getQuantityString(R.plurals.photo_places_photo_count,place.photoCount.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),place.photoCount),coordinate)
}
