package com.bontecou.whereday

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.PhotoLibrary
import androidx.compose.material.icons.outlined.Refresh
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.testTagsAsResourceId
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

internal data class MatchedPhotosState(val count:Long?=null,val error:Int?=null,val refresh:()->Unit={},val enabled:Boolean=true)

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
                if(!graph.photoLibrary.state.value.validated) state=MatchedPhotosState(error=R.string.photo_error_check_access)
                else graph.photos.observeCount(range).collect {state=MatchedPhotosState(it)}
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {state=MatchedPhotosState(error=R.string.photo_error_matched)}
        }
    }
    val safe=enabled && lifecycleState.isAtLeast(Lifecycle.State.RESUMED) && sync.validated && !sync.running
    return state.copy(count=state.count.takeIf {safe},refresh={retry++},enabled=enabled)
}
internal fun PhotoMoment.libraryEntry()=PhotoLibraryEntry(PhotoAssetRow(uri,takenAt,null,true,true,takenAt),source.wireValue)

@Composable internal fun MatchedPhotoViewer(graph:AppGraph,photos:List<PhotoMoment>,selected:String,close:()->Unit) {
    val index=photos.indexOfFirst {it.uri==selected}
    if(index>=0) key(selected) {PhotoPager(graph,photos.map {it.libraryEntry()},index,close,pageLabel=false)}
    else LaunchedEffect(selected,photos) {close()}
}

@Composable internal fun MatchedPhotoStrip(graph:AppGraph,state:MatchedPhotosState,range:HistoryRange) {
    val resources=LocalContext.current.resources
    val owner="matched:$range"
    var selected by rememberSaveable(owner,stateSaver=PhotoViewerDraft.Saver) {mutableStateOf<PhotoViewerDraft?>(null)}
    LaunchedEffect(state.enabled,state.error) {if(!state.enabled || state.error!=null) selected=null}
    Text(stringResource(R.string.photo_title),style=WdType.title2,color=wd.textPrimary,modifier=Modifier.semantics {heading()})
    when {
        state.error!=null->WdNotice(stringResource(state.error),warning=true)
        state.count==null->Row(horizontalArrangement=Arrangement.spacedBy(10.dp),verticalAlignment=Alignment.CenterVertically) {
            CircularProgressIndicator(Modifier.size(16.dp),color=wd.accent,trackColor=wd.dialTrack,strokeWidth=2.dp)
            Text(stringResource(R.string.photo_check_access),style=WdType.caption,color=wd.textMuted)
        }
        state.count==0L->WdNotice(stringResource(R.string.photo_matched_empty),icon=Icons.Outlined.PhotoLibrary)
        else->{
            Text(resources.getQuantityString(R.plurals.photo_matched_count,state.count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),state.count),style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=wd.textMuted)
            PagedMatchedPhotoCards(graph,range) {selected=PhotoViewerDraft.open(owner,it)}
        }
    }
    WdButton(stringResource(R.string.photo_matched_refresh),state.refresh,icon=Icons.Outlined.Refresh)
    selected?.takeIf {it.owner==owner}?.let {draft->if(state.count!=null) key(range,draft.id) {
        PagedMatchedPhotoViewer(graph,range,draft.current,close={selected=null},onCurrent={draft.current=it})
    }}
}

/** One labelled button per photo. Image and caption are decorative children of that button. */
@Composable internal fun MatchedPhotoCards(photos:List<PhotoMoment>,select:(PhotoMoment)->Unit,image:@Composable (PhotoMoment,Modifier)->Unit) {
    LazyRow(Modifier.testTag("matched-photo-strip"),horizontalArrangement=Arrangement.spacedBy(10.dp),contentPadding=PaddingValues(vertical=4.dp)) {
        items(photos,key={it.uri}) {photo->
            MatchedPhotoCard(photo,{select(photo)}) {modifier->image(photo,modifier)}
        }
    }
}

@Composable internal fun MatchedPhotoCard(photo:PhotoMoment,select:()->Unit,image:@Composable (Modifier)->Unit) {
    val time=photoCaptureTime(photo.takenAt)
    val description=stringResource(R.string.photo_taken_at,time)
    val shape=RoundedCornerShape(14.dp)
    Box(Modifier.clip(shape).background(wd.card).border(1.dp,wd.border,shape).clickable(role=Role.Button,onClick=select).testTag("matched-photo-card").semantics {contentDescription=description}) {
        Column(Modifier.width(112.dp).clearAndSetSemantics {}.padding(8.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
            image(Modifier.size(96.dp).clip(RoundedCornerShape(10.dp)))
            Text(time,style=WdType.caption2,color=wd.textPrimary,maxLines=2)
            Text(stringResource(R.string.photo_open),style=WdType.caption2.copy(fontWeight=FontWeight.SemiBold),color=wd.accent)
        }
    }
}
