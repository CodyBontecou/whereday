package com.bontecou.whereday

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bontecou.whereday.domain.*
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.*
import org.maplibre.android.style.layers.*
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import org.json.JSONArray
import org.json.JSONObject

/** Map renderer owns only presentation. The underlying GPS history stays unchanged. */
private class JournalMapRenderer {
    var map: MapLibreMap? = null
    var disposed=false
    var points: List<TrackPoint> = emptyList()
    var visits: List<Visit> = emptyList()
    private var replay:ReplayFrame?=null
    private var ends=RouteEndpoints()
    private var layers=MapLayers()
    private var fallback=false
    private var photos:List<PhotoCluster> = emptyList()
    private var photoIcons:Map<String,android.graphics.Bitmap> = emptyMap()
    private var renderedPhotoClusters:List<PhotoCluster>?=null
    private var activePhotoIcons:Map<String,String> = emptyMap()
    private var photoFeatures:String=""
    private val loadedPhotoIcons=mutableMapOf<String,android.graphics.Bitmap>()
    var fitPaddingPixels=48
    var startIcon:android.graphics.Bitmap?=null
    var endIcon:android.graphics.Bitmap?=null
    private var fitted = false
    private var location:MapLocationFix?=null
    private var appliedLocation:MapLocationFix?=null
    private var cameraFollowing=false
    fun follow(value:MapLocationFix?,trackCamera:Boolean) {
        location=value
        val target=map ?: return
        if(appliedLocation==value && cameraFollowing==trackCamera) return
        val first=appliedLocation==null || !cameraFollowing;appliedLocation=value;cameraFollowing=trackCamera
        if(value!=null && trackCamera) {
            val zoom=if(first) value.maximumZoom else minOf(target.cameraPosition.zoom,value.maximumZoom)
            target.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(value.coordinate.latitude,value.coordinate.longitude),zoom))
            fitted=true
        }
        update()
    }
    private var requestedStyle: String? = null
    var error: String? by mutableStateOf(null)
    fun render(points: List<TrackPoint>, visits: List<Visit>, online: Boolean, dark: Boolean, layers:MapLayers,replay:ReplayFrame?,ends:RouteEndpoints,fallback:Boolean,photos:List<PhotoCluster>,photoIcons:Map<String,android.graphics.Bitmap>) {
        this.points=points; this.visits=visits;this.replay=replay;this.ends=ends;this.layers=layers;this.fallback=fallback;this.photos=photos;this.photoIcons=photoIcons
        val target=map ?: return
        val styleKey=if(online) "https://tiles.openfreemap.org/styles/${if(dark) "dark" else "liberty"}" else "offline:$dark"
        if(requestedStyle != styleKey) {
            requestedStyle=styleKey; error=null;loadedPhotoIcons.clear();renderedPhotoClusters=null
            val builder=if(online) Style.Builder().fromUri(styleKey) else Style.Builder().fromJson("""{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"${if(dark) "#18211c" else "#edf1eb"}"}}]}""")
            target.setStyle(builder) { style ->
                if(map!==target || disposed || requestedStyle!=styleKey) return@setStyle
                style.addSource(GeoJsonSource("journal-route",emptyCollection()))
                style.addSource(GeoJsonSource("journal-points",emptyCollection()))
                style.addSource(GeoJsonSource("journal-visits",emptyCollection()))
                style.addSource(GeoJsonSource("journal-replay",emptyCollection()))
                style.addSource(GeoJsonSource("journal-outliers",emptyCollection()))
                style.addSource(GeoJsonSource("journal-start",emptyCollection()))
                style.addSource(GeoJsonSource("journal-end",emptyCollection()))
                style.addSource(GeoJsonSource("journal-photos",emptyCollection()))
                style.addSource(GeoJsonSource("journal-location",emptyCollection()))
                style.addLayer(LineLayer("journal-path","journal-route").withProperties(lineColor("#39835b"),lineWidth(4f)))
                style.addLayer(CircleLayer("journal-point","journal-points").withProperties(circleColor("#39835b"),circleRadius(3f),circleStrokeColor("#ffffff"),circleStrokeWidth(1f)))
                style.addLayer(CircleLayer("journal-visit","journal-visits").withProperties(circleColor("#a16a2e"),circleRadius(8f),circleStrokeColor("#ffffff"),circleStrokeWidth(2f)))
                style.addLayer(CircleLayer("journal-current-location","journal-location").withProperties(circleColor("#1976d2"),circleRadius(8f),circleStrokeColor("#ffffff"),circleStrokeWidth(3f)))
                style.addLayer(CircleLayer("journal-replay-position","journal-replay").withProperties(circleColor("#285d42"),circleRadius(10f),circleStrokeColor("#ffffff"),circleStrokeWidth(3f)))
                style.addLayer(CircleLayer("journal-outlier","journal-outliers").withProperties(circleColor("#b3261e"),circleRadius(5f),circleStrokeColor("#ffffff"),circleStrokeWidth(2f)))
                startIcon?.let {style.addImage("route-start",it)}
                endIcon?.let {style.addImage("route-end",it)}
                style.addLayer(SymbolLayer("journal-start-flag","journal-start").withProperties(iconImage("route-start"),iconAnchor(Property.ICON_ANCHOR_BOTTOM),iconAllowOverlap(true),iconIgnorePlacement(true)))
                style.addLayer(SymbolLayer("journal-end-flag","journal-end").withProperties(iconImage("route-end"),iconAnchor(Property.ICON_ANCHOR_BOTTOM),iconAllowOverlap(true),iconIgnorePlacement(true)))
                style.addLayer(SymbolLayer("journal-photo","journal-photos").withProperties(iconImage(Expression.get("icon")),iconAnchor(Property.ICON_ANCHOR_BOTTOM),iconAllowOverlap(true),iconIgnorePlacement(true)))
                update()
                if(!fitted && (this.points.isNotEmpty() || this.visits.isNotEmpty() || this.photos.isNotEmpty())) fit()
            }
        } else update()
    }
    fun update() {
        val style=map?.style ?: return
        val presentation=MapPresentation.build(points,visits,ends,layers,replay,fallback)
        val lines=JSONArray(presentation.segments.map {segment->JSONArray(segment.map {p->JSONArray(listOf(p.coordinate.longitude,p.coordinate.latitude))})})
        style.getSourceAs<GeoJsonSource>("journal-route")?.setGeoJson(JSONObject().put("type","Feature").put("properties",JSONObject()).put("geometry",JSONObject().put("type","MultiLineString").put("coordinates",lines)).toString())
        fun features(coordinates:List<Pair<Coordinate,String>>):String=JSONObject().put("type","FeatureCollection").put("features",JSONArray(coordinates.map {(c,id)->JSONObject().put("type","Feature").put("properties",JSONObject().put("id",id)).put("geometry",JSONObject().put("type","Point").put("coordinates",JSONArray(listOf(c.longitude,c.latitude))))})).toString()
        fun set(source:String,values:List<Pair<Coordinate,String>>) {style.getSourceAs<GeoJsonSource>(source)?.setGeoJson(features(values))}
        set("journal-points",presentation.points.filterNot {it.outlier}.map {it.coordinate to it.id})
        set("journal-outliers",presentation.points.filter {it.outlier}.map {it.coordinate to it.id})
        set("journal-visits",presentation.visits.map {it.coordinate to it.id})
        set("journal-start",listOfNotNull(presentation.start?.let {it.coordinate to it.id}))
        set("journal-end",listOfNotNull(presentation.end?.let {it.coordinate to it.id}))
        set("journal-location",location?.let {listOf(it.coordinate to "current-location")} ?: emptyList())
        set("journal-replay",replay?.let {listOf(it.current.coordinate to it.current.id)} ?: emptyList())
        val geometryChanged=renderedPhotoClusters!=photos
        if(geometryChanged) {
            activePhotoIcons=photos.associate {cluster->
                val digest=java.security.MessageDigest.getInstance("SHA-256").digest(cluster.id.toByteArray())
                cluster.id to buildString {append("photo-");digest.forEach {byte->val n=byte.toInt() and 255;append("0123456789abcdef"[n shr 4]);append("0123456789abcdef"[n and 15])}}
            }
            val collection=JSONArray()
            photos.forEach {cluster->collection.put(JSONObject().put("type","Feature").put("properties",JSONObject().put("id",cluster.id).put("icon",activePhotoIcons.getValue(cluster.id))).put("geometry",JSONObject().put("type","Point").put("coordinates",JSONArray(listOf(cluster.coordinate.longitude,cluster.coordinate.latitude)))))}
            photoFeatures=JSONObject().put("type","FeatureCollection").put("features",collection).toString()
            val active=activePhotoIcons.values.toSet()
            loadedPhotoIcons.keys.filter {it !in active}.toList().forEach {style.removeImage(it);loadedPhotoIcons.remove(it)}
            renderedPhotoClusters=photos
        }
        var firstSpriteArrived=false
        photos.forEach {cluster->photoIcons[cluster.id]?.let {bitmap->
            val icon=activePhotoIcons.getValue(cluster.id)
            if(loadedPhotoIcons[icon]!==bitmap) {
                if(icon !in loadedPhotoIcons) firstSpriteArrived=true
                style.addImage(icon,bitmap);loadedPhotoIcons[icon]=bitmap
            }
        }}
        // A symbol laid out before its first sprite exists stays absent in MapLibre 13.4.
        // Publish after installing images and relayout only when a new sprite key arrives.
        if(geometryChanged || firstSpriteArrived) style.getSourceAs<GeoJsonSource>("journal-photos")?.setGeoJson(photoFeatures)
        if(!fitted && (points.isNotEmpty() || visits.isNotEmpty() || photos.isNotEmpty())) fit()
    }
    fun fit() {
        val fitPoints=points.filter {layers.outliers || !it.outlier}.ifEmpty {if(fallback) points else emptyList()}
        val (start,end)=ends.selected(layers.outliers,fallback)
        val coordinates=(if(layers.path || layers.points) fitPoints.map {it.coordinate} else emptyList()) +
            (if(layers.visits) visits.map {it.coordinate} else emptyList()) +
            (if(layers.endpoints) listOfNotNull(start?.coordinate,end?.coordinate) else emptyList()) + listOfNotNull(replay?.current?.coordinate) + photos.map {it.coordinate}
        if(coordinates.isEmpty()) return
        val target=map ?: return
        if(coordinates.distinct().size==1) target.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(coordinates[0].latitude,coordinates[0].longitude),15.0))
        else target.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.Builder().includes(coordinates.map { LatLng(it.latitude,it.longitude) }).build(),fitPaddingPixels))
        fitted=true
    }
    private fun emptyCollection()="""{"type":"FeatureCollection","features":[]}"""
}

@Composable internal fun JournalMap(points: List<TrackPoint>, visits: List<Visit>, modifier: Modifier=Modifier,replay:ReplayFrame?=null,endpoints:RouteEndpoints=RouteEndpoints.from(points),showOutliers:Boolean=false,onOutliers:((Boolean)->Unit)?=null,allOutlierFallback:Boolean=false,onVisitSelected:((Visit)->Unit)?=null,photoLayer:PhotoMapLayer?=null,follow:MapFollowUi?=null,followMetric:Boolean=true) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val renderer=remember(context) { JournalMapRenderer().apply {val scale=context.resources.displayMetrics.density;fitPaddingPixels=kotlin.math.ceil(48*scale).toInt();startIcon=routeFlag(scale,false);endIcon=routeFlag(scale,true)} }
    val mapView=remember(context) { MapLibre.getInstance(context); MapView(context).also { it.onCreate(null) } }
    var online by rememberSaveable { mutableStateOf(false) }
    var showPoints by rememberSaveable { mutableStateOf(true) }
    var showPath by rememberSaveable {mutableStateOf(true)}
    var showVisits by rememberSaveable {mutableStateOf(true)}
    var showEnds by rememberSaveable {mutableStateOf(true)}
    var pickingVisit by remember {mutableStateOf(false)}
    var candidateVisits by remember {mutableStateOf<Set<String>?>(null)}
    val latestVisitAction by rememberUpdatedState(onVisitSelected)
    val latestVisibleVisits by rememberUpdatedState(visits)
    var configuring by rememberSaveable {mutableStateOf(false)}
    val layers=MapLayers(showPath,showPoints,showVisits,showEnds,showOutliers)
    val contents=remember(points,visits,endpoints,layers,replay,allOutlierFallback) {MapPresentation.build(points,visits,endpoints,layers,replay,allOutlierFallback)}
    val photoClusters=photoLayer?.visibleClusters.orEmpty()
    val mapDescription="Journal map. Travel path ${if(showPath) "shown" else "hidden"}. ${contents.points.size} GPS markers. ${contents.visits.size} visit markers. Start and end flags ${if(showEnds) "shown" else "hidden"}. ${photoClusters.size} photo groups containing ${photoClusters.sumOf {it.photos.size}} photos. ${if(follow!=null) "Your current location ${if(follow.fix!=null) "shown" else "not shown"}. " else ""}Scroll by dragging two fingers. Zoom by pinching two fingers."
    val dark=MaterialTheme.colorScheme.background.luminance()<0.5f
    val latestPoints by rememberUpdatedState(points)
    val latestVisits by rememberUpdatedState(visits)
    val latestOnline by rememberUpdatedState(online)
    val latestDark by rememberUpdatedState(dark)
    val latestLayers by rememberUpdatedState(layers)
    val latestEnds by rememberUpdatedState(endpoints)
    val latestFallback by rememberUpdatedState(allOutlierFallback)
    val latestReplay by rememberUpdatedState(replay)
    val latestPhotoLayer by rememberUpdatedState(photoLayer)
    val latestFollow by rememberUpdatedState(follow)
    DisposableEffect(mapView,lifecycle) {
        var started=false; var resumed=false; var destroyed=false
        fun sync() {
            val shouldStart=lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            val shouldResume=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if(shouldStart && !started) { mapView.onStart(); started=true }
            if(shouldResume && !resumed) { mapView.onResume(); resumed=true }
            if(!shouldResume && resumed) { mapView.onPause(); resumed=false }
            if(!shouldStart && started) { mapView.onStop(); started=false }
        }
        val observer=LifecycleEventObserver { _, event ->
            sync()
            if(event==Lifecycle.Event.ON_DESTROY && !destroyed) { mapView.onDestroy(); destroyed=true }
        }
        lifecycle.addObserver(observer); sync()
        onDispose { lifecycle.removeObserver(observer); if(!destroyed) { if(resumed) mapView.onPause(); if(started) mapView.onStop(); mapView.onDestroy() }; renderer.map=null;renderer.disposed=true }
    }
    if(pickingVisit && onVisitSelected!=null) AlertDialog(onDismissRequest={pickingVisit=false},title={Text("Visits on map")},text={
        val choices=contents.visits.filter {candidateVisits==null || it.id in candidateVisits.orEmpty()}
        if(choices.isEmpty()) Text("No visits are visible. Enable Visit markers in Map layers to choose a visit.")
        else LazyColumn(Modifier.heightIn(max=400.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(choices,key={it.id}) {visit-> VisitNavigationRow(visit) {pickingVisit=false;onVisitSelected(visit)} }
        }
    },confirmButton={TextButton(onClick={pickingVisit=false}) {Text("Close map visits")}})
    if(configuring) AlertDialog(onDismissRequest={configuring=false},title={Text("Map layers")},text={
        Column(Modifier.semantics {testTagsAsResourceId=true}.testTag("map-layers-content").verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            MapLayerToggle("Travel path",showPath,"map-layer-path") {showPath=it}
            MapLayerToggle("GPS points",showPoints,"map-layer-points") {showPoints=it}
            MapLayerToggle("Visit markers",showVisits,"map-layer-visits") {showVisits=it}
            MapLayerToggle("Start and end markers",showEnds,"map-layer-endpoints") {showEnds=it}
            photoLayer?.let {photo->
                MapLayerToggle("Photo markers",photo.enabled,"map-layer-photos",photo.enable)
                MapLayerToggle("Photo images",photo.images,"map-layer-photo-images",photo.showImages)
            }
            if(onOutliers!=null) MapLayerToggle("Show GPS outliers",showOutliers,"map-layer-outliers",onOutliers)
            Text(if(onOutliers!=null) "Outlier fixes are red. Outlier visibility is saved for the main map; original fixes stay unchanged." else "Outlier fixes are red. Layer choices only change this map.",style=MaterialTheme.typography.bodySmall)
            val (start,end)=endpoints.selected(showOutliers,allOutlierFallback)
            start?.let {Text("Route start · ${timeText(it.time)}",style=MaterialTheme.typography.bodySmall)}
            end?.takeIf {it.id!=start?.id}?.let {Text("Route end · ${timeText(it.time)}",style=MaterialTheme.typography.bodySmall)}
        }
    },confirmButton={TextButton(onClick={configuring=false}) {Text("Done map layers")}})
    Column(modifier,verticalArrangement=Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
            FilterChip(online,{ online=!online },label={ Text("Online map") })
            TextButton(onClick={configuring=true}) {Text("Map layers")}
            TextButton(onClick={ follow?.stop?.invoke();renderer.follow(follow?.fix,false);renderer.fit() }) { Text("Fit route") }
        }
        follow?.let {MapFollowControl(it,followMetric)}
        if(onVisitSelected!=null && visits.isNotEmpty()) TextButton(onClick={candidateVisits=null;pickingVisit=true}) {Text("Visits on map")}
        if(photoClusters.isNotEmpty()) TextButton(onClick={photoLayer?.open?.invoke(photoClusters)}) {Text("Photos on map")}
        if(online) Text("Map tiles: OpenFreeMap / OpenMapTiles / OpenStreetMap. The map provider receives requests for the viewed area.",style=MaterialTheme.typography.bodySmall)
        AndroidView(factory={ mapView.apply {
            addOnDidFailLoadingMapListener { renderer.error="Map tiles could not load. Your recorded history is safe. Turn Online map off to view the route offline." }
            getMapAsync { map ->
                if(renderer.disposed) return@getMapAsync
                renderer.map=map
                map.addOnCameraMoveStartedListener {reason->if(reason==MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) latestFollow?.stop?.invoke()}
                renderer.follow(latestFollow?.fix,latestFollow?.enabled==true)
                map.addOnMapClickListener {coordinate->
                    val photoAction=latestPhotoLayer
                    if(photoAction!=null && photoAction.visibleClusters.isNotEmpty()) {
                        val pixel=map.projection.toScreenLocation(coordinate)
                        val radius=28f*context.resources.displayMetrics.density
                        val ids=map.queryRenderedFeatures(android.graphics.RectF(pixel.x-radius,pixel.y-radius,pixel.x+radius,pixel.y+radius),"journal-photo").mapNotNull {it.getStringProperty("id")}.toSet()
                        val matches=photoAction.visibleClusters.filter {it.id in ids}
                        if(matches.isNotEmpty()) {photoAction.open(matches);return@addOnMapClickListener true}
                    }
                    val action=latestVisitAction
                    if(renderer.disposed || action==null || !latestLayers.visits) false
                    else {
                        val pixel=map.projection.toScreenLocation(coordinate)
                        val radius=24f*context.resources.displayMetrics.density
                        val bounds=android.graphics.RectF(pixel.x-radius,pixel.y-radius,pixel.x+radius,pixel.y+radius)
                        val ids=map.queryRenderedFeatures(bounds,"journal-visit").mapNotNull {it.getStringProperty("id")}.toSet()
                        val matches=latestVisibleVisits.filter {it.id in ids}
                        when(matches.size) {
                            0->false
                            1->{action(matches.single());true}
                            else->{candidateVisits=ids;pickingVisit=true;true}
                        }
                    }
                }
                renderer.render(latestPoints,latestVisits,latestOnline,latestDark,latestLayers,latestReplay,latestEnds,latestFallback,latestPhotoLayer?.visibleClusters.orEmpty(),latestPhotoLayer?.icons.orEmpty())
            }
        } },update={ view->view.contentDescription=mapDescription;renderer.follow(follow?.fix,follow?.enabled==true);renderer.render(points,visits,online,dark,layers,replay,endpoints,allOutlierFallback,photoClusters,photoLayer?.icons.orEmpty()) },modifier=Modifier.fillMaxWidth().height(300.dp).testTag("journal-native-map"))
        renderer.error?.let { Text(it,color=MaterialTheme.colorScheme.error) }
        if(!online) Text("Offline route view. Enable Online map to load a street map.",style=MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun MapLayerToggle(label:String,checked:Boolean,id:String,change:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().testTag(id).toggleable(checked,role=Role.Checkbox,onValueChange=change).heightIn(min=48.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Text(label,Modifier.weight(1f).padding(top=12.dp));Checkbox(checked,onCheckedChange=null)
    }
}
private fun routeFlag(scale:Float,end:Boolean):android.graphics.Bitmap {
    val bitmap=android.graphics.Bitmap.createBitmap((44*scale).toInt().coerceAtLeast(1),(42*scale).toInt().coerceAtLeast(1),android.graphics.Bitmap.Config.ARGB_8888)
    bitmap.density=(160*scale).toInt()
    val canvas=android.graphics.Canvas(bitmap);canvas.scale(scale,scale);canvas.translate(6f,3f)
    val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color=android.graphics.Color.WHITE;paint.strokeWidth=5f;canvas.drawLine(16f,5f,16f,39f,paint)
    paint.color=android.graphics.Color.rgb(40,93,66);paint.strokeWidth=2f;canvas.drawLine(16f,5f,16f,39f,paint)
    paint.color=android.graphics.Color.WHITE;canvas.drawCircle(16f,39f,3f,paint)
    paint.color=android.graphics.Color.rgb(40,93,66);canvas.drawCircle(16f,39f,2f,paint)
    if(end) {
        paint.color=android.graphics.Color.WHITE;canvas.drawRect(16f,6f,36f,22f,paint)
        paint.color=android.graphics.Color.rgb(35,43,38)
        for(row in 0..3) for(col in 0..4) if((row+col)%2==0) canvas.drawRect(16f+col*4,6f+row*4,20f+col*4,10f+row*4,paint)
    } else {
        val path=android.graphics.Path();path.moveTo(16f,6f);path.lineTo(37f,14f);path.lineTo(16f,22f);path.close();canvas.drawPath(path,paint)
    }
    return bitmap
}
