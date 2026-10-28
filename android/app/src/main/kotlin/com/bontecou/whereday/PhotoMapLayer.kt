package com.bontecou.whereday

import android.graphics.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*

internal data class PhotoMapLayer(
    val enabled:Boolean,val images:Boolean,val clusters:List<PhotoCluster>,val icons:Map<String,Bitmap>,
    val enable:(Boolean)->Unit,val showImages:(Boolean)->Unit,val open:(List<PhotoCluster>)->Unit
) {val visibleClusters get()=if(enabled) clusters else emptyList()}

@Composable internal fun photoMapLayer(graph:AppGraph,photos:List<PhotoMoment>):PhotoMapLayer {
    val settings by graph.preferences.settings.collectAsStateWithLifecycle(initialValue=null)
    val enabled=settings?.showPhotoMarkers==true
    val images=settings?.showPhotoImages!=false
    val scope=rememberCoroutineScope()
    val scale=LocalContext.current.resources.displayMetrics.density
    val clusters by produceState<List<PhotoCluster>>(emptyList(),photos,enabled) {
        value=emptyList()
        if(enabled) value=withContext(Dispatchers.Default) {
            val caller=currentCoroutineContext()
            PhotoClusters.build(photos,distance={a,b->
                val meters=FloatArray(1);android.location.Location.distanceBetween(a.latitude,a.longitude,b.latitude,b.longitude,meters);meters[0].toDouble()
            },checkpoint={caller.ensureActive()})
        }
    }
    val icons by produceState<Map<String,Bitmap>>(emptyMap(),clusters,images,scale) {
        value=clusters.associate {it.id to photoPin(scale,null,it.photos.size)}
        if(images) for(cluster in clusters) {
            currentCoroutineContext().ensureActive()
            try {
                val bitmap=graph.photoLibrary.image(cluster.representative.uri,128)
                value=value+(cluster.id to photoPin(scale,bitmap,cluster.photos.size))
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {try {graph.photoLibrary.unavailable(cluster.representative.uri)} catch(e:CancellationException) {throw e} catch(_:Exception) {}}
        }
    }
    var choosing by remember {mutableStateOf<Set<String>?>(null)}
    var selected by remember {mutableStateOf<String?>(null)}
    var viewerPhotos by remember {mutableStateOf<List<PhotoMoment>>(emptyList())}
    val safeClusters=clusters.filter {cluster->cluster.photos.all {it in photos}}
    val current=safeClusters.filter {it.id in choosing.orEmpty()}
    if(choosing!=null && current.isNotEmpty()) AlertDialog(onDismissRequest={choosing=null},title={Text("Photos on map")},text={
        val choices=current.flatMap {it.photos}.sortedWith(compareBy({it.takenAt},{it.uri}))
        LazyColumn(Modifier.heightIn(max=400.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(choices,key={it.uri}) {photo->
                OutlinedCard(onClick={viewerPhotos=choices;selected=photo.uri;choosing=null},modifier=Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(12.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
                        PhotoImage(graph,photo.libraryEntry(),128,Modifier.size(56.dp))
                        Column(Modifier.weight(1f)) {Text(timeText(photo.takenAt));Text("Open photo",style=MaterialTheme.typography.labelMedium)}
                    }
                }
            }
        }
    },confirmButton={TextButton(onClick={choosing=null}) {Text("Close map photos")}})
    LaunchedEffect(clusters,enabled) {if(!enabled || current.isEmpty()) choosing=null}
    // Keep only currently validated moments in an open viewer after deletion/revocation/range changes.
    val valid=viewerPhotos.mapNotNull {old->photos.firstOrNull {it.uri==old.uri}}
    selected?.let {uri->MatchedPhotoViewer(graph,valid,uri) {selected=null}}
    return PhotoMapLayer(enabled,images,safeClusters,icons,
        {value->scope.launch {graph.preferences.update {it.copy(showPhotoMarkers=value)}}},
        {value->scope.launch {graph.preferences.update {it.copy(showPhotoImages=value)}}},
        {matches->val choices=matches.flatMap {it.photos}.sortedWith(compareBy({it.takenAt},{it.uri}));
            if(choices.size==1) {viewerPhotos=choices;selected=choices.single().uri}
            else if(choices.isNotEmpty()) choosing=matches.map {it.id}.toSet()
        })
}

/** Fixed size sprites avoid provider images or arbitrary camera coordinates in native annotations. */
private fun photoPin(scale:Float,image:Bitmap?,count:Int):Bitmap {
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
        canvas.drawText(count.toString(),39f,15f,paint)
    }
    return bitmap
}
