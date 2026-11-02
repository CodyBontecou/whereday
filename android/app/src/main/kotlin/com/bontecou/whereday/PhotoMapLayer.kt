package com.bontecou.whereday

import android.graphics.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*

/** GL hit-test ids belong to one immutable directory, even when its grid/database ids are reused. */
internal data class PhotoMapMarker(val snapshot:String,val area:PhotoAreaSummary) {
    val id:String get()="$snapshot:${area.id}"
    val areaId:String get()=area.id
    val coordinate get()=area.coordinate
    val photoCount get()=area.photoCount
    val placeCount get()=area.placeCount
    val anchor get()=area.anchor
    val representativeUri get()=area.representativeUri
    val isArea get()=area.isArea
}

/** At most 500 metadata summaries reach the renderer; memberships stay in the private directory. */
internal data class PhotoMapLayer(
    val enabled:Boolean,val images:Boolean,val clusters:List<PhotoMapMarker>,val icons:Map<String,Bitmap>,
    val enable:(Boolean)->Unit,val showImages:(Boolean)->Unit,val open:(List<PhotoMapMarker>)->Unit,
    val browsePlaces:(()->Unit)?=null,val directoryMessage:String?=null,val retryDirectory:(()->Unit)?=null,
    val dots:Boolean=false,val showDots:(Boolean)->Unit={}
) {val visibleClusters get()=if(enabled) clusters else emptyList()}

private data class PhotoBrowserRequest(val id:String,val owner:PhotoPlaceDirectory,val counts:PhotoPlaceCounts,val areas:Set<String>?=null,val title:Int=R.string.photo_places_all,val place:PhotoPlaceSummary?=null)
private data class PhotoIconSnapshot(val owner:PhotoPlaceDirectory,val style:PhotoMarkerStyle,val scale:Float,val icons:Map<String,Bitmap>)

@Composable internal fun photoMapLayer(graph:AppGraph,range:HistoryRange,validated:Boolean,
    thumbnail:suspend(String,Int)->Bitmap={uri,pixels->graph.photoLibrary.image(uri,pixels)},
    onBrowserRange:(HistoryRange?)->Unit={},
    validationFailed:Boolean=false,
    image:@Composable (PhotoMoment,Modifier,Int)->Unit={photo,modifier,size->PhotoImage(graph,photo.libraryEntry(),size,modifier)}):PhotoMapLayer {
    val settings by graph.preferences.settings.collectAsStateWithLifecycle(initialValue=null)
    val enabled=settings?.showPhotoMarkers==true
    val images=settings?.showPhotoImages!=false
    val dots=settings?.showPhotoDots==true
    val style=PhotoMarkerStyle.resolve(images,dots)
    val latestThumbnail by rememberUpdatedState(thumbnail)
    val directory=photoDirectory(graph,range,enabled && validated)
    val ready=directory.directory
    val snapshot=remember(ready) {java.util.UUID.randomUUID().toString()}
    val markers=remember(ready,directory.markers) {directory.markers.map {PhotoMapMarker(snapshot,it)}}
    var opening by rememberSaveable(stateSaver=PhotoBrowserOpening.Saver) {mutableStateOf<PhotoBrowserOpening?>(null)}
    var openError by remember {mutableStateOf<Int?>(null)}
    val context=LocalContext.current
    fun selectOpening(value:PhotoBrowserOpening?) {if(value!=null) openError=null;opening=value;onBrowserRange(if(value==null) null else range)}
    val latestDrop by rememberUpdatedState({selectOpening(null)})
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    LaunchedEffect(opening?.id) {if(opening==null) latestDrop()}
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP && !context.activityConfigurationChange()) latestDrop()}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer);if(!context.activityConfigurationChange()) latestDrop()}
    }
    val clearing by graph.photoPlaces.clearing.collectAsStateWithLifecycle()
    if(opening!=null) BlockVisitLinks()
    var hadReady by remember {mutableStateOf(false)}
    LaunchedEffect(ready,validated,validationFailed,settings,opening,clearing,directory.message) {
        if(clearing || validationFailed || directory.message==R.string.photo_places_error || (settings!=null && (!enabled || opening?.epoch?.let {it!=settings!!.photoEpoch}==true))) selectOpening(null)
        else if(ready!=null) {
            hadReady=true
            if(opening?.snapshot?.let {it!=ready.identity}==true) selectOpening(null)
        } else if(hadReady && !validated && !context.activityConfigurationChange()) selectOpening(null)
    }
    LaunchedEffect(ready) {openError=null}
    val request by produceState<PhotoBrowserRequest?>(null,ready,opening) {
        value=null
        val pending=opening ?: return@produceState
        val source=ready?.takeIf {it.identity==pending.snapshot && it.active.value} ?: return@produceState
        try {
            val counts=if(pending.areas==null) source.counts() else {
                val areas=source.markers().filter {it.id in pending.areas}
                require(areas.size==pending.areas.size)
                PhotoPlaceCounts(areas.sumOf {it.photoCount},areas.sumOf {it.placeCount},areas.size.toLong())
            }
            val place=pending.place?.let {checkNotNull(source.place(it))}
            if(source.active.value) value=PhotoBrowserRequest(pending.id,source,counts,pending.areas,when(pending.title) {"area"->R.string.photo_places_area;"map"->R.string.photo_places_map;else->R.string.photo_places_all},place)
        } catch(error:CancellationException) {throw error}
        catch(_:Exception) {if(opening?.id==pending.id && !context.activityConfigurationChange()) {selectOpening(null);openError=R.string.photo_places_open_error}}
    }
    val target=request?.takeIf {it.owner===ready && it.id==opening?.id && it.owner.identity==opening?.snapshot}
    if(target==null) opening?.let {pending->key(pending.id) {PhotoBrowserLoading {if(opening?.id==pending.id) selectOpening(null)}}}
    target?.let {target->key(target.id) {
        PhotoPlaceBrowser(graph,target.owner,target.counts,close={selectOpening(null)},areas=target.areas,title=stringResource(target.title),initialPlace=target.place,state=opening?.browser,image=image)
    }}
    val scope=rememberCoroutineScope()
    val scale=LocalContext.current.resources.displayMetrics.density
    val spriteState by produceState<PhotoIconSnapshot?>(null,ready,markers,style,scale) {
        value=null
        if(ready!=null) {
            var sprites=if(style==PhotoMarkerStyle.dots) emptyMap() else markers.associate {it.id to photoPin(scale,null,it.photoCount)}
            value=PhotoIconSnapshot(ready,style,scale,sprites)
            if(style==PhotoMarkerStyle.images) for(marker in markers) {
                currentCoroutineContext().ensureActive()
                val uri=marker.representativeUri ?: continue
                try {
                    val bitmap=latestThumbnail(uri,128)
                    currentCoroutineContext().ensureActive()
                    sprites=sprites+(marker.id to photoPin(scale,bitmap,marker.photoCount))
                    value=PhotoIconSnapshot(ready,style,scale,sprites)
                } catch(error:CancellationException) {throw error}
                catch(_:Exception) {currentCoroutineContext().ensureActive();try {graph.photoLibrary.unavailable(uri)} catch(error:CancellationException) {throw error} catch(_:Exception) {}}
            }
        }
    }
    // Grid ids can survive a source mutation. Never reuse an old owner's thumbnail after replacement/revocation.
    val icons=spriteState?.takeIf {it.owner===ready && it.style==style && it.scale==scale}?.icons.orEmpty()
    return PhotoMapLayer(enabled,images,markers,icons,
        {value->scope.launch {graph.preferences.update {it.copy(showPhotoMarkers=value)}}},
        {value->scope.launch {graph.preferences.update {if(it.showPhotoDots) it else it.copy(showPhotoImages=value)}}},
        {matches->if(ready!=null && ready.active.value && matches.isNotEmpty()) {
            val selected=markers.filter {candidate->matches.any {it.id==candidate.id}}
            if(selected.isNotEmpty()) scope.launch {
                try {
                    val marker=selected.singleOrNull()
                    val place=if(marker!=null && !marker.isArea) ready.place(marker.anchor) ?: error("Photo place no longer exists") else null
                    if(ready.active.value) selectOpening(PhotoBrowserOpening(java.util.UUID.randomUUID().toString(),ready.identity,selected.map {it.areaId}.toSet(),if(marker?.isArea==true) "area" else "map",place?.id,settings!!.photoEpoch))
                } catch(error:CancellationException) {throw error}
                catch(_:Exception) {if(ready.active.value) openError=R.string.photo_places_open_error}
            }
        }},
        browsePlaces=if(ready!=null && directory.counts!=null) ({selectOpening(PhotoBrowserOpening(java.util.UUID.randomUUID().toString(),ready.identity,epoch=settings!!.photoEpoch))}) else null,
        directoryMessage=(openError ?: directory.message)?.let {stringResource(it)},
        retryDirectory=directory.retry.takeIf {openError!=null || directory.message==R.string.photo_places_error},
        dots=dots,showDots={value->scope.launch {graph.preferences.update {it.copy(showPhotoDots=value)}}})
}

/** Fixed size sprites avoid provider images or arbitrary camera coordinates in native annotations. */
private fun photoPin(scale:Float,image:Bitmap?,count:Long):Bitmap {
    val bitmap=Bitmap.createBitmap((52*scale).toInt().coerceAtLeast(1),(54*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
    bitmap.density=(160*scale).toInt()
    val canvas=Canvas(bitmap);canvas.scale(scale,scale)
    val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    paint.color=Color.WHITE;canvas.drawRoundRect(2f,8f,46f,48f,6f,6f,paint)
    val path=Path().apply {moveTo(17f,47f);lineTo(24f,54f);lineTo(31f,47f);close()};canvas.drawPath(path,paint)
    if(image!=null) {
        canvas.save();canvas.clipRect(5f,11f,43f,45f)
        val ratio=maxOf(38f/image.width,34f/image.height);val width=image.width*ratio;val height=image.height*ratio
        canvas.drawBitmap(image,null,RectF(24-width/2,28-height/2,24+width/2,28+height/2),paint);canvas.restore()
    } else {
        paint.color=Color.rgb(40,93,66);canvas.drawRoundRect(5f,11f,43f,45f,4f,4f,paint)
        paint.color=Color.WHITE;canvas.drawRoundRect(12f,21f,36f,37f,3f,3f,paint);canvas.drawRect(17f,18f,24f,23f,paint)
        paint.color=Color.rgb(40,93,66);canvas.drawCircle(24f,29f,6f,paint)
    }
    if(count>1) {
        paint.color=Color.rgb(40,93,66);canvas.drawCircle(39f,11f,11f,paint)
        paint.color=Color.WHITE;paint.textSize=11f;paint.typeface=Typeface.create(Typeface.DEFAULT,Typeface.BOLD);paint.textAlign=Paint.Align.CENTER
        canvas.drawText(if(count>99) "99+" else count.toString(),39f,15f,paint)
    }
    return bitmap
}
