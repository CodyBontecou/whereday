package com.bontecou.whereday

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.pager.HorizontalPager
import androidx.compose.foundation.pager.rememberPagerState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.paging.*
import androidx.paging.compose.collectAsLazyPagingItems
import androidx.paging.compose.itemKey
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.launch

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
        Text("Photos could not be loaded.",color=MaterialTheme.colorScheme.error)
        TextButton(onClick={photos.retry()}) {Text("Retry photos")}
    }
    LazyRow(Modifier.testTag("matched-photo-strip"),horizontalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(4.dp)) {
        items(photos.itemCount,key=photos.itemKey {it.uri}) {index->
            val photo=photos[index]?.moment()
            if(photo==null) Surface(Modifier.width(112.dp).height(164.dp),color=MaterialTheme.colorScheme.surfaceContainer) {Text("Loading photo…",Modifier.padding(8.dp))}
            else MatchedPhotoCard(photo,{select(photo.uri)}) {modifier->image(photo,modifier)}
        }
    }
}

@Composable internal fun PagedMatchedPhotoViewer(graph:AppGraph,range:HistoryRange,selected:String,close:()->Unit) {
    val rank by produceState<Int?>(null,graph,range,selected) {
        try {value=graph.photos.rank(range,selected);if(value==null) close()}
        catch(e:kotlinx.coroutines.CancellationException) {throw e}
        catch(_:Exception) {close()}
    }
    rank?.let {initial->key(range,selected) {
        PagedPhotoViewer(initial,{graph.photos.pagingSource(range)},selected,close) {photo,modifier->PhotoImage(graph,photo.moment().libraryEntry(),1536,modifier)}
    }}
}

/** Dismiss on mutation rather than silently reassigning the selected position to another asset. */
@Composable internal fun PagedPhotoViewer(initial:Int,source:()->PagingSource<Int,PhotoRow>,selected:String,close:()->Unit,image:@Composable (PhotoRow,Modifier)->Unit) {
    PagedPhotoEntryViewer(initial,source,selected,close,{it.uri},{photo->
        Text(timeText(photo.capturedAt));Text(photoSourceLabel(photo.moment().libraryEntry()))
    },image=image)
}

@Composable internal fun LibraryPhotoViewer(graph:AppGraph,selected:String,close:()->Unit,remove:(String)->Unit) {
    var error by remember {mutableStateOf(false)}
    var retry by remember {mutableIntStateOf(0)}
    val rank by produceState<Int?>(null,graph,selected,retry) {
        value=null;error=false
        try {value=graph.photos.libraryRank(selected);if(value==null) close()}
        catch(e:kotlinx.coroutines.CancellationException) {throw e}
        catch(_:Exception) {error=true}
    }
    if(error) AlertDialog(onDismissRequest=close,title={Text("Photos could not be loaded")},text={Text("Try opening this photo again.")},confirmButton={TextButton(onClick={retry++}) {Text("Retry photos")}},dismissButton={TextButton(onClick=close) {Text("Close photo")}})
    rank?.let {initial->
        PagedLibraryPhotoViewer(initial,{graph.photos.libraryPagingSource()},selected,close,remove) {entry,modifier->PhotoImage(graph,entry,1536,modifier)}
    }
}

@Composable internal fun PagedLibraryPhotoViewer(initial:Int,source:()->PagingSource<Int,PhotoLibraryEntry>,selected:String,close:()->Unit,remove:(String)->Unit,image:@Composable (PhotoLibraryEntry,Modifier)->Unit) {
    PagedPhotoEntryViewer(initial,source,selected,close,{it.asset.uri},{entry->
        Text(entry.asset.capturedAt?.let(::timeText) ?: "Capture time unavailable")
        Text(photoSourceLabel(entry))
    },remove={remove(it.asset.uri)},image=image)
}

@Composable private fun <T:Any> PagedPhotoEntryViewer(initial:Int,source:()->PagingSource<Int,T>,selected:String,close:()->Unit,uri:(T)->String,details:@Composable (T)->Unit,remove:((T)->Unit)?=null,image:@Composable (T,Modifier)->Unit) {
    val scope=rememberCoroutineScope()
    var invalidated by remember {mutableStateOf(false)}
    val flow=remember {
        Pager(photoPagingConfig(),initialKey=initial) {
            source().also {it.registerInvalidatedCallback {scope.launch {invalidated=true}}}
        }.flow.cachedIn(scope)
    }
    val photos=flow.collectAsLazyPagingItems()
    val pager=rememberPagerState(initialPage=initial,pageCount={photos.itemCount})
    var verified by remember {mutableStateOf(false)}
    val first=if(initial<photos.itemCount) photos.peek(initial) else null
    LaunchedEffect(first,photos.loadState.refresh) {
        if(!verified && first!=null) {
            if(uri(first)==selected) verified=true else invalidated=true
        } else if(!verified && photos.loadState.refresh is LoadState.NotLoading && initial>=photos.itemCount) invalidated=true
    }
    LaunchedEffect(invalidated) {if(invalidated) close()}
    if(invalidated) return
    Dialog(onDismissRequest=close,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        Surface(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                TextButton(onClick=close) {Text("Close photo")}
                if(verified && photos.itemCount>0) {
                    Text("Photo ${pager.currentPage+1} of ${photos.itemCount}",style=MaterialTheme.typography.titleLarge)
                    HorizontalPager(pager,Modifier.fillMaxWidth().height(360.dp),key=photos.itemKey(uri)) {index->
                        val photo=photos[index]
                        if(photo==null) Text("Loading photo…") else image(photo,Modifier.fillMaxSize())
                    }
                    val current=photos[pager.currentPage.coerceIn(0,photos.itemCount-1)]
                    current?.let {details(it)}
                    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                        TextButton(enabled=pager.currentPage>0,onClick={scope.launch {pager.animateScrollToPage((pager.currentPage-1).coerceAtLeast(0))}}) {Text("Previous photo")}
                        TextButton(enabled=pager.currentPage<photos.itemCount-1,onClick={scope.launch {pager.animateScrollToPage((pager.currentPage+1).coerceAtMost(photos.itemCount-1))}}) {Text("Next photo")}
                    }
                    if(remove!=null && current!=null) TextButton(onClick={remove(current)}) {Text("Remove photo from Whereday",color=MaterialTheme.colorScheme.error)}
                } else Text("Loading photo…")
                if(photos.loadState.refresh is LoadState.Error || photos.loadState.append is LoadState.Error || photos.loadState.prepend is LoadState.Error) {
                    Text("Photos could not be loaded.",color=MaterialTheme.colorScheme.error)
                    TextButton(onClick={photos.retry()}) {Text("Retry photos")}
                }
            }
        }
    }
}
