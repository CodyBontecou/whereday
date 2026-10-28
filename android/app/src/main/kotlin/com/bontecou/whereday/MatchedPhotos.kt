package com.bontecou.whereday

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.compose.currentStateAsState
import androidx.lifecycle.repeatOnLifecycle
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*

internal data class MatchedPhotosState(val window:PhotoWindow?=null,val error:String?=null,val refresh:()->Unit={})

/** Revalidate only previously selected references. No permission prompt or library enumeration. */
@Composable internal fun matchedPhotos(graph:AppGraph,range:HistoryRange,enabled:Boolean=true):MatchedPhotosState {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val lifecycleState by lifecycle.currentStateAsState()
    val sync by graph.photoLibrary.state.collectAsStateWithLifecycle()
    var retry by remember {mutableIntStateOf(0)}
    var state by remember(range,enabled) {mutableStateOf(MatchedPhotosState())}
    LaunchedEffect(graph,range,enabled,retry,lifecycle) {
        if(!enabled) return@LaunchedEffect
        lifecycle.repeatOnLifecycle(Lifecycle.State.RESUMED) {
            state=MatchedPhotosState()
            try {
                graph.photoLibrary.refresh()
                if(!graph.photoLibrary.state.value.validated) state=MatchedPhotosState(error="Photo access could not be checked. Try refreshing.")
                else graph.photos.observeWindow(range).collect {state=MatchedPhotosState(it)}
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {state=MatchedPhotosState(error="Matched photos could not be loaded. Try refreshing.")}
        }
    }
    val safe=enabled && lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && sync.validated && !sync.running
    return state.copy(window=state.window.takeIf {safe},refresh={retry++})
}
internal fun PhotoMoment.libraryEntry()=PhotoLibraryEntry(PhotoAssetRow(uri,takenAt,null,true,true,takenAt),source.wireValue)

@Composable internal fun MatchedPhotoViewer(graph:AppGraph,photos:List<PhotoMoment>,selected:String,close:()->Unit) {
    val index=photos.indexOfFirst {it.uri==selected}
    if(index>=0) key(selected) {PhotoPager(graph,photos.map {it.libraryEntry()},index,close,pageLabel=false)}
    else LaunchedEffect(selected,photos) {close()}
}

@Composable internal fun MatchedPhotoStrip(graph:AppGraph,state:MatchedPhotosState,range:HistoryRange) {
    var selected by remember {mutableStateOf<String?>(null)}
    LaunchedEffect(state.window==null) {if(state.window==null) selected=null}
    Text("Photos",style=MaterialTheme.typography.titleLarge)
    when {
        state.error!=null->Text(state.error,color=MaterialTheme.colorScheme.error)
        state.window==null->Text("Checking photo access…",style=MaterialTheme.typography.bodyMedium)
        state.window.count==0L->Text("No matched photos. Select photos in Settings → Photos; photo GPS or a nearby route or visit can place them in this outing.")
        else->{
            Text("${state.window.count} matched "+if(state.window.count==1L) "photo" else "photos")
            PagedMatchedPhotoCards(graph,range) {selected=it}
        }
    }
    TextButton(onClick=state.refresh) {Text("Refresh matched photos")}
    selected?.let {uri->if(state.window!=null) key(range,uri) {PagedMatchedPhotoViewer(graph,range,uri) {selected=null}}}
}

/** One labelled button per photo. Image and caption are decorative children of that button. */
@Composable internal fun MatchedPhotoCards(photos:List<PhotoMoment>,select:(PhotoMoment)->Unit,image:@Composable (PhotoMoment,Modifier)->Unit) {
    LazyRow(Modifier.testTag("matched-photo-strip"),horizontalArrangement=Arrangement.spacedBy(12.dp),contentPadding=PaddingValues(4.dp)) {
        items(photos,key={it.uri}) {photo->
            MatchedPhotoCard(photo,{select(photo)}) {modifier->image(photo,modifier)}
        }
    }
}

@Composable internal fun MatchedPhotoCard(photo:PhotoMoment,select:()->Unit,image:@Composable (Modifier)->Unit) {
    OutlinedCard(onClick=select,modifier=Modifier.testTag("matched-photo-card").semantics {contentDescription="Photo taken at ${timeText(photo.takenAt)}"}) {
        Column(Modifier.width(112.dp).clearAndSetSemantics {}.padding(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            image(Modifier.size(96.dp))
            Text(timeText(photo.takenAt),style=MaterialTheme.typography.labelMedium)
            Text("Open photo",style=MaterialTheme.typography.labelMedium)
        }
    }
}
