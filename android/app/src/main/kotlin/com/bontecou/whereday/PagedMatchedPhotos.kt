package com.bontecou.whereday

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowLeft
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.PagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.paging.*
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.launch
import kotlinx.coroutines.flow.collectLatest

/** Independent caches keep a distant viewer from evicting the strip's visible page. */
internal fun photoPagingConfig()=PagingConfig(pageSize=50,initialLoadSize=50,prefetchDistance=10,maxSize=150,enablePlaceholders=true,jumpThreshold=100)
internal fun PhotoRow.moment()=PhotoMoment(uri,capturedAt,coordinate,PhotoCoordinateSource.entries.first {it.wireValue==coordinateSource})

@Composable internal fun PagedMatchedPhotoCards(graph:AppGraph,range:HistoryRange,select:(String)->Unit) {
    key(graph,range) {PagedMatchedCards({graph.photos.pagingSource(range)},select) {photo,modifier->PhotoImage(graph,photo.libraryEntry(),256,modifier)}}
}

@Composable internal fun PagedMatchedCards(source:()->PagingSource<Int,PhotoRow>,select:(String)->Unit,image:@Composable (PhotoMoment,Modifier)->Unit) {
    val scope=rememberCoroutineScope()
    val flow=remember {Pager(photoPagingConfig(),pagingSourceFactory=source).flow.cachedIn(scope)}
    val photos=flow.collectAsLazyPagingItems()
    if(photos.loadState.refresh is LoadState.Error || photos.loadState.append is LoadState.Error || photos.loadState.prepend is LoadState.Error) {
        WdNotice(stringResource(R.string.photo_load_failed),warning=true,action={WdButton(stringResource(R.string.photo_retry),{photos.retry()},icon=Icons.Outlined.Refresh)})
    }
    LazyRow(Modifier.testTag("matched-photo-strip"),horizontalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(vertical=4.dp)) {
        items(photos.itemCount,key=photos.itemKey {it.uri}) {index->
            val photo=photos[index]?.moment()
            if(photo==null) Box(Modifier.width(112.dp).height(164.dp).clip(RoundedCornerShape(14.dp)).background(wd.card).border(1.dp,wd.border,RoundedCornerShape(14.dp)),contentAlignment=Alignment.Center) {Text(stringResource(R.string.photo_loading),Modifier.padding(8.dp),style=WdType.caption2,color=wd.textMuted,textAlign=TextAlign.Center)}
            else MatchedPhotoCard(photo,{select(photo.uri)}) {modifier->image(photo,modifier)}
        }
    }
}

@Composable internal fun PagedMatchedPhotoViewer(graph:AppGraph,range:HistoryRange,selected:String,close:()->Unit,onCurrent:(String)->Unit={}) {
    val opening=remember {selected}
    val rank by produceState<Int?>(null,graph,range,opening) {
        try {value=graph.photos.rank(range,opening);if(value==null) close()}
        catch(e:kotlinx.coroutines.CancellationException) {throw e}
        catch(_:Exception) {close()}
    }
    rank?.let {initial->key(range,opening) {
        PagedPhotoViewer(initial,{graph.photos.pagingSource(range)},opening,close,onCurrent) {photo,modifier->PhotoImage(graph,photo.moment().libraryEntry(),1536,modifier)}
    }}
}

/** Dismiss on mutation rather than silently reassigning the selected position to another asset. */
@Composable internal fun PagedPhotoViewer(initial:Int,source:()->PagingSource<Int,PhotoRow>,selected:String,close:()->Unit,onCurrent:(String)->Unit={},image:@Composable (PhotoRow,Modifier)->Unit) {
    PagedPhotoEntryViewer(initial,source,selected,close,{it.uri},{photo->
        Text(photoCaptureTime(photo.capturedAt),style=WdType.title3,color=wd.textPrimary);Text(photoSourceLabel(photo.moment().libraryEntry()),style=WdType.caption,color=wd.textMuted)
    },onCurrent=onCurrent,image=image)
}

@Composable internal fun LibraryPhotoViewer(graph:AppGraph,selected:String,close:()->Unit,remove:(String)->Unit,onCurrent:(String)->Unit={}) {
    val opening=remember {selected}
    var error by remember {mutableStateOf(false)}
    var retry by remember {mutableIntStateOf(0)}
    val rank by produceState<Int?>(null,graph,opening,retry) {
        value=null;error=false
        try {value=graph.photos.libraryRank(opening);if(value==null) close()}
        catch(e:kotlinx.coroutines.CancellationException) {throw e}
        catch(_:Exception) {error=true}
    }
    if(error) WorkspaceAlertDialog(onDismissRequest=close,title={Text(stringResource(R.string.photo_load_failed_title),style=WdType.title2)},text={Text(stringResource(R.string.photo_retry_open),color=wd.textPrimary)},confirmButton={TextButton(onClick={retry++}) {Text(stringResource(R.string.photo_retry),fontWeight=FontWeight.SemiBold)}},dismissButton={TextButton(modifier=Modifier.testTag("photo-pager-close"),onClick=close) {Text(stringResource(R.string.photo_close),color=wd.textMuted)}})
    rank?.let {initial->
        PagedLibraryPhotoViewer(initial,{graph.photos.libraryPagingSource()},opening,close,remove,onCurrent) {entry,modifier->PhotoImage(graph,entry,1536,modifier)}
    }
}

@Composable internal fun PagedLibraryPhotoViewer(initial:Int,source:()->PagingSource<Int,PhotoLibraryEntry>,selected:String,close:()->Unit,remove:(String)->Unit,onCurrent:(String)->Unit={},image:@Composable (PhotoLibraryEntry,Modifier)->Unit) {
    PagedPhotoEntryViewer(initial,source,selected,close,{it.asset.uri},{entry->
        Text(photoCaptureTime(entry.asset.capturedAt),style=WdType.title3,color=wd.textPrimary)
        Text(photoSourceLabel(entry),style=WdType.caption,color=wd.textMuted)
    },remove={remove(it.asset.uri)},onCurrent=onCurrent,image=image)
}

@Composable internal fun <T:Any> PagedPhotoEntryViewer(initial:Int,source:()->PagingSource<Int,T>,selected:String,close:()->Unit,uri:(T)->String,details:@Composable (T)->Unit,remove:((T)->Unit)?=null,onCurrent:(String)->Unit={},image:@Composable (T,Modifier)->Unit) {
    val scope=rememberCoroutineScope()
    var invalidated by remember {mutableStateOf(false)}
    val flow=remember {
        Pager(photoPagingConfig(),initialKey=initial) {
            source().also {it.registerInvalidatedCallback {scope.launch {invalidated=true}}}
        }.flow.cachedIn(scope)
    }
    val photos=flow.collectAsLazyPagingItems()
    // A fresh rank has already been resolved by URI. Do not restore a stale numeric page over it.
    val pager=remember {PagerState(currentPage=initial,pageCount={photos.itemCount})}
    var verified by remember {mutableStateOf(false)}
    val first=if(initial<photos.itemCount) photos.peek(initial) else null
    LaunchedEffect(first,photos.loadState.refresh) {
        if(!verified && first!=null) {
            if(uri(first)==selected) verified=true else invalidated=true
        } else if(!verified && photos.loadState.refresh is LoadState.NotLoading && initial>=photos.itemCount) invalidated=true
    }
    LaunchedEffect(invalidated) {if(invalidated) close()}
    val currentCallback by rememberUpdatedState(onCurrent)
    LaunchedEffect(pager,verified,photos.itemSnapshotList) {
        if(verified && !invalidated) snapshotFlow {pager.settledPage}.collectLatest {index->
            if(index in 0 until photos.itemCount) photos.peek(index)?.let {currentCallback(uri(it))}
        }
    }
    if(invalidated) return
    WorkspaceDialog(fillRegion=true,onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize(),color=wd.surface,contentColor=wd.textPrimary) {
            Column(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}.testTag("photo-pager-content").verticalScroll(rememberScrollState()).padding(horizontal=16.dp,vertical=12.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                PhotoViewerTopRow(close,Modifier.testTag("photo-pager-close")) {
                    if(verified && photos.itemCount>0) Text(stringResource(R.string.photo_number,pager.currentPage+1,photos.itemCount),style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=wd.textMuted)
                }
                if(verified && photos.itemCount>0) {
                    HorizontalPager(pager,Modifier.fillMaxWidth().height(360.dp).clip(RoundedCornerShape(16.dp)).background(wd.dialTrack),key=photos.itemKey(uri)) {index->
                        val photo=photos[index]
                        if(photo==null) Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) {Text(stringResource(R.string.photo_loading),style=WdType.caption,color=wd.textMuted)} else image(photo,Modifier.fillMaxSize())
                    }
                    val current=photos[pager.currentPage.coerceIn(0,photos.itemCount-1)]
                    current?.let {PhotoViewerDetails {details(it)}}
                    PhotoViewerControls(pager.currentPage>0,pager.currentPage<photos.itemCount-1,{scope.launch {pager.animateScrollToPage((pager.currentPage-1).coerceAtLeast(0))}},{scope.launch {pager.animateScrollToPage((pager.currentPage+1).coerceAtMost(photos.itemCount-1))}},Modifier.testTag("photo-pager-previous"),Modifier.testTag("photo-pager-next"))
                    if(remove!=null && current!=null) WdButton(stringResource(R.string.photo_remove_whereday),{remove(current)},emphasis=WdEmphasis.destructive,icon=Icons.Outlined.Delete)
                } else Column {PlacesProgress(inset=false);Text(stringResource(R.string.photo_loading),Modifier.padding(top=8.dp),style=WdType.caption,color=wd.textMuted)}
                if(photos.loadState.refresh is LoadState.Error || photos.loadState.append is LoadState.Error || photos.loadState.prepend is LoadState.Error) {
                    WdNotice(stringResource(R.string.photo_load_failed),warning=true,action={WdButton(stringResource(R.string.photo_retry),{photos.retry()},icon=Icons.Outlined.Refresh)})
                }
            }
        }
    }
}

/** The viewer's paper close capsule with a trailing mono position caption. */
@Composable internal fun PhotoViewerTopRow(close:()->Unit,closeModifier:Modifier,trailing:@Composable RowScope.()->Unit={}) {
    Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
        Row(closeModifier.heightIn(min=44.dp).clip(CircleShape).background(wd.card).border(1.dp,wd.border,CircleShape).clickable(role=Role.Button,onClick=close).padding(start=10.dp,end=14.dp),
            verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
            Icon(Icons.Outlined.Close,null,Modifier.size(16.dp),tint=wd.textPrimary)
            Text(stringResource(R.string.photo_close),style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=wd.textPrimary,maxLines=1)
        }
        Spacer(Modifier.weight(1f));trailing()
    }
}

/** Capture time and match source in a hairline card beneath the photo. */
@Composable internal fun PhotoViewerDetails(content:@Composable ColumnScope.()->Unit) {
    WdCard {Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(4.dp),content=content)}
}

/** Previous and next as paired paper buttons. */
@Composable internal fun PhotoViewerControls(previousEnabled:Boolean,nextEnabled:Boolean,previous:()->Unit,next:()->Unit,previousModifier:Modifier=Modifier,nextModifier:Modifier=Modifier) {
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(10.dp)) {
        WdButton(stringResource(R.string.photo_previous),previous,previousModifier.weight(1f),enabled=previousEnabled,icon=Icons.AutoMirrored.Filled.KeyboardArrowLeft)
        WdButton(stringResource(R.string.photo_next),next,nextModifier.weight(1f),enabled=nextEnabled,trailingIcon=Icons.AutoMirrored.Filled.KeyboardArrowRight)
    }
}
