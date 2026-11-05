package com.bontecou.whereday

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.font.FontWeight
import java.util.Locale
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
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

private data class MapPointSelectionHint(val id:String,val outlier:Boolean)

/** Map renderer owns only presentation. The underlying GPS history stays unchanged. */
private class JournalMapRenderer {
    var map: MapLibreMap? = null
    var disposed=false
    // Map/style readiness can precede native renderer initialization in MapLibre 13.4.1.
    var queryReady=false
    var points: List<TrackPoint> = emptyList()
    var markerPoints:List<TrackPoint> = emptyList()
    var visits: List<Visit> = emptyList()
    private var replay:ReplayFrame?=null
    private var ends=RouteEndpoints()
    private var layers=MapLayers()
    private var fallback=false
    private var roadGeometry=RoadMapGeometry(emptyList(),emptyList(),emptyList(),false)
    private var activeRecording:ActiveRecordingMap?=null
    private var photos:List<PhotoMapMarker> = emptyList()
    private var photoIcons:Map<String,android.graphics.Bitmap> = emptyMap()
    private var photoDots=false
    private var renderedPhotoClusters:List<PhotoMapMarker>?=null
    private var activePhotoIcons:Map<String,String> = emptyMap()
    private var photoFeatures:String=""
    private val loadedPhotoIcons=mutableMapOf<String,android.graphics.Bitmap>()
    var fitPaddingPixels=48
    var inks=MapInks()
    var startIcon:android.graphics.Bitmap?=null
    var endIcon:android.graphics.Bitmap?=null
    private var fitted = false
    private var location:MapLocationFix?=null
    private var appliedLocation:MapLocationFix?=null
    private var cameraFollowing=false
    private var visitFocus:VisitMapFocus?=null
    private var appliedVisitFocus:VisitMapFocus?=null
    var selectedPointId:String?=null
    /** Builds the paper (or night ink) style for the online/offline and light/dark choice. */
    var styleJson:(Boolean,Boolean)->String={_,dark->"""{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"${inks.hex(MapStyles.land(dark))}"}}]}"""}
    /** The map-first presentation's coloured legs, labelled pins and chips; null for the framed journal map. */
    var home:MapHomeRender?=null
    private val homeImages=mutableMapOf<String,android.graphics.Bitmap>()
    /** Insets (px) that fitting keeps clear of floating chrome: left, top, right, bottom. */
    var chromePadding:IntArray?=null
        set(value) {
            val changed=!(field contentEquals value);field=value
            // Floating chrome measures after the first fit; refit until the person moves the map.
            if(changed && value!=null && autoFitted && map!=null) fit()
        }
    /** The camera shows an automatic fit that nobody has moved since. */
    var autoFitted=false
    fun restoreCamera(saved:JournalMapCamera?) {
        if(saved==null) return
        val target=map ?: return
        fitted=saved.fitted;appliedVisitFocus=saved.appliedFocus
        target.moveCamera(CameraUpdateFactory.newCameraPosition(org.maplibre.android.camera.CameraPosition.Builder()
            .target(LatLng(saved.target.latitude,saved.target.longitude)).zoom(saved.zoom).bearing(saved.bearing).tilt(saved.tilt).build()))
    }
    fun cameraSnapshot():JournalMapCamera? {
        if(disposed || !queryReady) return null
        val current=map ?: return null
        val position=current.cameraPosition
        // A fit inside the floating chrome pads the camera, so its target is the padded centre; restoring
        // without that padding would shift the view, so remember what sits at the middle of the screen instead.
        val padded=position.padding?.any {it!=0.0}==true && viewWidthPx>0 && viewHeightPx>0
        val target=(if(padded) runCatching {current.projection.fromScreenLocation(android.graphics.PointF(viewWidthPx/2f,viewHeightPx/2f))}.getOrNull() else null)
            ?: position.target ?: return null
        val longitude=((target.longitude+180)%360+360)%360-180
        return runCatching {JournalMapCamera(Coordinate(target.latitude,longitude),position.zoom,position.bearing,position.tilt,fitted,appliedVisitFocus)}.getOrNull()
    }
    fun follow(value:MapLocationFix?,trackCamera:Boolean) {
        location=value
        val target=map ?: return
        if(appliedLocation==value && cameraFollowing==trackCamera) return
        val first=appliedLocation==null || !cameraFollowing;appliedLocation=value;cameraFollowing=trackCamera
        if(value!=null && trackCamera) {
            val zoom=if(first) value.maximumZoom else minOf(target.cameraPosition.zoom,value.maximumZoom)
            fitted=true
            target.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(value.coordinate.latitude,value.coordinate.longitude),zoom))
        }
        update()
    }
    private var requestedStyle: String? = null
    var error by mutableStateOf(false)
    fun render(points: List<TrackPoint>, visits: List<Visit>, online: Boolean, dark: Boolean, layers:MapLayers,replay:ReplayFrame?,ends:RouteEndpoints,fallback:Boolean,photos:List<PhotoMapMarker>,photoIcons:Map<String,android.graphics.Bitmap>,activeRecording:ActiveRecordingMap?=null,roadGeometry:RoadMapGeometry,photoDots:Boolean=false,visitFocus:VisitMapFocus?=null) {
        this.points=points; this.visits=visits;this.replay=replay;this.ends=ends;this.layers=layers;this.fallback=fallback;this.photos=photos;this.photoDots=photoDots;this.photoIcons=if(photoDots) emptyMap() else photoIcons
        this.activeRecording=activeRecording;this.roadGeometry=roadGeometry;this.visitFocus=visitFocus
        if(visitFocus==null) appliedVisitFocus=null
        val target=map ?: return
        val styleKey="paper:online=$online:dark=$dark"
        if(requestedStyle != styleKey) {
            requestedStyle=styleKey; error=false;loadedPhotoIcons.clear();renderedPhotoClusters=null;homeImages.clear()
            val builder=Style.Builder().fromJson(styleJson(online,dark))
            target.setStyle(builder) { style ->
                if(map!==target || disposed || requestedStyle!=styleKey) return@setStyle
                style.addSource(GeoJsonSource("journal-route",emptyCollection()))
                style.addSource(GeoJsonSource("journal-road-route",emptyCollection()))
                style.addSource(GeoJsonSource("journal-points",emptyCollection()))
                style.addSource(GeoJsonSource("journal-visits",emptyCollection()))
                style.addSource(GeoJsonSource("journal-replay",emptyCollection()))
                style.addSource(GeoJsonSource("journal-outliers",emptyCollection()))
                style.addSource(GeoJsonSource("journal-start",emptyCollection()))
                style.addSource(GeoJsonSource("journal-end",emptyCollection()))
                style.addSource(GeoJsonSource("journal-photos",emptyCollection()))
                style.addSource(GeoJsonSource("journal-location",emptyCollection()))
                style.addSource(GeoJsonSource("journal-active-route",emptyCollection()))
                style.addSource(GeoJsonSource("journal-active-start",emptyCollection()))
                style.addSource(GeoJsonSource("journal-active-current",emptyCollection()))
                val ink=inks
                style.addLayer(LineLayer("journal-path","journal-route").withProperties(lineColor(ink.path),lineWidth(4f),lineCap(Property.LINE_CAP_ROUND),lineJoin(Property.LINE_JOIN_ROUND)))
                style.addLayer(LineLayer("journal-road-path","journal-road-route").withProperties(lineColor(ink.road),lineWidth(4f),lineCap(Property.LINE_CAP_ROUND),lineJoin(Property.LINE_JOIN_ROUND)))
                style.addLayer(LineLayer("journal-active-path","journal-active-route").withProperties(lineColor(ink.active),lineWidth(5f),lineCap(Property.LINE_CAP_ROUND),lineJoin(Property.LINE_JOIN_ROUND)))
                style.addLayer(CircleLayer("journal-point","journal-points").withProperties(circleColor(ink.path),circleRadius(3f),circleStrokeColor(ink.paper),circleStrokeWidth(1f)))
                // Home pins carry their place ink, size and opacity; framed journal pins use the defaults.
                fun numberOr(name:String,fallback:Float)=Expression.switchCase(Expression.has(name),Expression.get(name),Expression.literal(fallback))
                style.addLayer(CircleLayer("journal-visit","journal-visits").withProperties(
                    circleColor(Expression.switchCase(Expression.has("color"),Expression.toColor(Expression.get("color")),Expression.color(ink.visit))),
                    circleRadius(numberOr("radius",8f)),circleStrokeColor(ink.paper),circleStrokeWidth(numberOr("stroke",2f)),
                    circleOpacity(numberOr("opacity",1f)),circleStrokeOpacity(numberOr("opacity",1f))))
                style.addLayer(CircleLayer("journal-current-location","journal-location").withProperties(circleColor(ink.live),circleRadius(8f),circleStrokeColor(ink.paper),circleStrokeWidth(3f)))
                style.addLayer(CircleLayer("journal-replay-halo","journal-replay").withProperties(circleColor(ink.highlight),circleOpacity(0.18f),circleRadius(18f)))
                style.addLayer(CircleLayer("journal-replay-position","journal-replay").withProperties(circleColor(ink.paper),circleRadius(8f),circleStrokeColor(ink.highlight),circleStrokeWidth(3.5f)))
                style.addLayer(CircleLayer("journal-outlier","journal-outliers").withProperties(circleColor(ink.highlight),circleRadius(5f),circleStrokeColor(ink.paper),circleStrokeWidth(2f)))
                startIcon?.let {style.addImage("route-start",it)}
                endIcon?.let {style.addImage("route-end",it)}
                style.addLayer(SymbolLayer("journal-start-flag","journal-start").withProperties(iconImage("route-start"),iconAnchor(Property.ICON_ANCHOR_BOTTOM),iconAllowOverlap(true),iconIgnorePlacement(true)))
                style.addLayer(SymbolLayer("journal-end-flag","journal-end").withProperties(iconImage("route-end"),iconAnchor(Property.ICON_ANCHOR_BOTTOM),iconAllowOverlap(true),iconIgnorePlacement(true)))
                style.addLayer(SymbolLayer("journal-active-start-flag","journal-active-start").withProperties(iconImage("route-start"),iconAnchor(Property.ICON_ANCHOR_BOTTOM),iconAllowOverlap(true),iconIgnorePlacement(true)))
                style.addLayer(CircleLayer("journal-active-position","journal-active-current").withProperties(circleColor(ink.live),circleRadius(10f),circleStrokeColor(ink.paper),circleStrokeWidth(3f)))
                style.addLayer(SymbolLayer("journal-photo","journal-photos").withProperties(iconImage(Expression.get("icon")),iconAnchor(Property.ICON_ANCHOR_BOTTOM),iconAllowOverlap(true),iconIgnorePlacement(true)))
                style.addLayer(CircleLayer("journal-photo-dot","journal-photos").withProperties(circleColor(ink.photo),circleRadius(Expression.get("dotRadius")),circlePitchScale("viewport"),circlePitchAlignment("viewport"),circleStrokeColor(ink.paper),circleStrokeWidth(2f),visibility(Property.NONE)))
                style.addLayer(CircleLayer("journal-focused-visit","journal-visits").withProperties(circleColor(Expression.switchCase(Expression.has("color"),Expression.toColor(Expression.get("color")),Expression.color(ink.visit))),
                    circleRadius(11f),circleStrokeColor(ink.highlight),circleStrokeWidth(3f),visibility(Property.NONE)))
                style.addLayer(CircleLayer("journal-selected-point","journal-points").withProperties(circleColor(ink.highlight),circleRadius(8f),circleStrokeColor(ink.paper),circleStrokeWidth(3f),visibility(Property.NONE)))
                style.addLayer(CircleLayer("journal-selected-outlier","journal-outliers").withProperties(circleColor(ink.highlight),circleRadius(9f),circleStrokeColor(ink.paper),circleStrokeWidth(3f),visibility(Property.NONE)))
                MapHomeLayers.install(style,ink)
                update()
                if(!fitted && (this.points.isNotEmpty() || this.visits.isNotEmpty() || this.photos.isNotEmpty())) fit()
            }
        } else update()
    }
    fun update() {
        val style=map?.style ?: return
        val presentation=MapPresentation.build(points,visits,ends,layers,replay,fallback)
        fun coordinateLines(segments:List<List<Coordinate>>)=JSONArray(segments.flatMap {segment->MapLineGeometry.splitAtDateLine(segment)}.map {segment->JSONArray(segment.map {p->JSONArray(listOf(p.longitude,p.latitude))})})
        fun lineCoordinates(segments:List<List<TrackPoint>>)=coordinateLines(segments.map {line->line.map {it.coordinate}})
        val lines=coordinateLines(roadGeometry.raw)
        style.getSourceAs<GeoJsonSource>("journal-route")?.setGeoJson(JSONObject().put("type","Feature").put("properties",JSONObject()).put("geometry",JSONObject().put("type","MultiLineString").put("coordinates",lines)).toString())
        style.getSourceAs<GeoJsonSource>("journal-road-route")?.setGeoJson(JSONObject().put("type","Feature").put("properties",JSONObject()).put("geometry",JSONObject().put("type","MultiLineString").put("coordinates",coordinateLines(roadGeometry.roads))).toString())
        fun features(coordinates:List<Pair<Coordinate,String>>):String=JSONObject().put("type","FeatureCollection").put("features",JSONArray(coordinates.map {(c,id)->JSONObject().put("type","Feature").put("properties",JSONObject().put("id",id)).put("geometry",JSONObject().put("type","Point").put("coordinates",JSONArray(listOf(c.longitude,c.latitude))))})).toString()
        fun set(source:String,values:List<Pair<Coordinate,String>>) {style.getSourceAs<GeoJsonSource>(source)?.setGeoJson(features(values))}
        set("journal-points",markerPoints.filterNot {it.outlier}.map {it.coordinate to it.id})
        set("journal-outliers",markerPoints.filter {it.outlier}.map {it.coordinate to it.id})
        for(name in listOf("journal-selected-point","journal-selected-outlier")) style.getLayerAs<CircleLayer>(name)?.let {layer->
            selectedPointId?.let {layer.setFilter(Expression.eq(Expression.get("id"),Expression.literal(it)))}
            layer.setProperties(visibility(if(selectedPointId!=null && markerPoints.any {it.id==selectedPointId}) Property.VISIBLE else Property.NONE))
        }
        val homeMarkers=home?.markers
        if(homeMarkers==null) set("journal-visits",presentation.visits.map {it.coordinate to it.id})
        else style.getSourceAs<GeoJsonSource>("journal-visits")?.setGeoJson(JSONObject().put("type","FeatureCollection").put("features",JSONArray(homeMarkers.map {marker->
            JSONObject().put("type","Feature").put("properties",JSONObject().put("id",marker.id).put("color",inks.hex(marker.color)).put("radius",marker.radius.toDouble())
                .put("stroke",3.0).put("opacity",marker.opacity.toDouble()))
                .put("geometry",JSONObject().put("type","Point").put("coordinates",JSONArray(listOf(marker.coordinate.longitude,marker.coordinate.latitude))))
        })).toString())
        MapHomeLayers.publish(style,home,inks,homeImages,::labelGoesLeft)
        style.getLayerAs<CircleLayer>("journal-focused-visit")?.let {layer->
            visitFocus?.let {layer.setFilter(Expression.eq(Expression.get("id"),Expression.literal(it.id)))}
            // The home map marks a focused visit with its own pin and dashed radius instead.
            layer.setProperties(visibility(if(layers.visits && visitFocus!=null) Property.VISIBLE else Property.NONE))
        }
        set("journal-start",listOfNotNull(presentation.start?.let {it.coordinate to it.id}))
        set("journal-end",listOfNotNull(presentation.end?.let {it.coordinate to it.id}))
        set("journal-location",location?.let {listOf(it.coordinate to "current-location")} ?: emptyList())
        set("journal-replay",replay?.let {listOf(it.current.coordinate to it.current.id)} ?: emptyList())
        val active=activeRecording
        val activeLines=lineCoordinates(active?.segments.orEmpty())
        style.getSourceAs<GeoJsonSource>("journal-active-route")?.setGeoJson(JSONObject().put("type","Feature").put("properties",JSONObject()).put("geometry",JSONObject().put("type","MultiLineString").put("coordinates",activeLines)).toString())
        set("journal-active-start",listOfNotNull(active?.endpoints?.first?.let {it.coordinate to it.id}))
        set("journal-active-current",listOfNotNull(active?.endpoints?.last?.takeIf {it.id!=active.endpoints.first?.id}?.let {it.coordinate to it.id}))
        style.getLayer("journal-photo")?.setProperties(visibility(if(photoDots) Property.NONE else Property.VISIBLE))
        style.getLayer("journal-photo-dot")?.setProperties(visibility(if(photoDots) Property.VISIBLE else Property.NONE))
        val geometryChanged=renderedPhotoClusters!=photos
        if(geometryChanged) {
            activePhotoIcons=photos.associate {cluster->
                val digest=java.security.MessageDigest.getInstance("SHA-256").digest(cluster.id.toByteArray())
                cluster.id to buildString {append("photo-");digest.forEach {byte->val n=byte.toInt() and 255;append("0123456789abcdef"[n shr 4]);append("0123456789abcdef"[n and 15])}}
            }
            val collection=JSONArray()
            photos.forEach {cluster->collection.put(JSONObject().put("type","Feature").put("properties",JSONObject().put("id",cluster.id).put("icon",activePhotoIcons.getValue(cluster.id)).put("dotRadius",if(cluster.photoCount>1 || cluster.placeCount>1) 5 else 3)).put("geometry",JSONObject().put("type","Point").put("coordinates",JSONArray(listOf(cluster.coordinate.longitude,cluster.coordinate.latitude)))))}
            photoFeatures=JSONObject().put("type","FeatureCollection").put("features",collection).toString()
            val active=activePhotoIcons.values.toSet()
            loadedPhotoIcons.keys.filter {it !in active}.toList().forEach {style.removeImage(it);loadedPhotoIcons.remove(it)}
            renderedPhotoClusters=photos
        }
        // An omitted bitmap means its validated owner/settings changed; retaining the old GL image would
        // show a revoked/rebound thumbnail while the replacement directory prepares its sprites.
        val supplied=photos.filter {it.id in photoIcons}.map {activePhotoIcons.getValue(it.id)}.toSet()
        val removedSprites=loadedPhotoIcons.keys.filter {it !in supplied}.toList()
        removedSprites.forEach {style.removeImage(it);loadedPhotoIcons.remove(it)}
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
        if(geometryChanged || firstSpriteArrived || removedSprites.isNotEmpty()) style.getSourceAs<GeoJsonSource>("journal-photos")?.setGeoJson(photoFeatures)
        applyVisitFocus()
        if(!fitted && (points.isNotEmpty() || visits.isNotEmpty() || photos.isNotEmpty() || activeRecording?.points?.isNotEmpty()==true)) fit()
    }
    fun pulse(scale:Float) {map?.style?.let {MapHomeLayers.pulse(it,scale)}}
    var viewWidthPx=0
    var viewHeightPx=0
    var densityScale=1f
    /** Flip a pin's label to its left when the route runs off to the right or the label would slide under the right rail. */
    private fun labelGoesLeft(marker:MapHomeMarker):Boolean {
        val target=map ?: return marker.prefersLeft
        if(!queryReady || viewWidthPx<=0) return marker.prefersLeft
        val x=target.projection.toScreenLocation(LatLng(marker.coordinate.latitude,marker.coordinate.longitude)).x
        val label=marker.labelWidthDp*densityScale
        val right=viewWidthPx-(chromePadding?.get(2) ?: 0)
        val roomLeft=x-label>=0
        return if(marker.prefersLeft) roomLeft || x+label>right else x+label>right && roomLeft
    }
    /** Fit the next data that arrives (a new home range), as the first load does. */
    fun refitOnNextData() {fitted=false;autoFitted=true}
    fun republishLabels() {if(!disposed) map?.style?.let {MapHomeLayers.publishLabels(it,home,::labelGoesLeft)}}
    private fun applyVisitFocus() {
        val focus=visitFocus ?: return
        if(focus==appliedVisitFocus || visits.none {it.id==focus.id}) return
        val target=map ?: return
        val bounds=focus.bounds()
        appliedVisitFocus=focus;fitted=true;autoFitted=false
        // A deliberate saved-visit action selects a centered region, like iOS.
        // The default bounds update retains tilt, which shifts its target away from the visit.
        target.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(bounds.north,bounds.east,bounds.south,bounds.west),0.0,0.0,0))
    }
    fun fit() {
        val fitPoints=points.filter {layers.outliers || !it.outlier}.ifEmpty {if(fallback) points else emptyList()}
        val (start,end)=ends.selected(layers.outliers,fallback)
        val coordinates=(if(layers.path || layers.points) fitPoints.map {it.coordinate} else emptyList()) +
            (if(layers.visits) visits.map {it.coordinate} else emptyList()) +
            (if(layers.endpoints) listOfNotNull(start?.coordinate,end?.coordinate) else emptyList()) + listOfNotNull(replay?.current?.coordinate) + photos.map {it.coordinate} + activeCoordinates() + roadGeometry.fit
        fitCoordinates(coordinates);autoFitted=true
    }
    private fun activeCoordinates():List<Coordinate> = activeRecording?.let {it.points.map {p->p.coordinate}+listOfNotNull(it.endpoints.first?.coordinate,it.endpoints.last?.coordinate)}.orEmpty()
    fun fitActiveRecording() {fitCoordinates(activeCoordinates());autoFitted=false}
    fun fitCoordinates(coordinates:List<Coordinate>) {
        val bounds=MapCameraBounds.enclosing(coordinates) ?: return
        val target=map ?: return
        fitted=true
        val inset=chromePadding
        if(inset!=null) {
            // Floating chrome covers the map's edges; fit inside what stays visible.
            val spread=if(bounds.isPoint) 0.0025 else 0.0
            target.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from((bounds.north+spread).coerceAtMost(85.0),(bounds.east+spread).coerceAtMost(180.0),(bounds.south-spread).coerceAtLeast(-85.0),(bounds.west-spread).coerceAtLeast(-180.0)),
                inset[0]+fitPaddingPixels/2,inset[1]+fitPaddingPixels/2,inset[2]+fitPaddingPixels/2,inset[3]+fitPaddingPixels/2))
        }
        else if(bounds.isPoint) target.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(bounds.south,bounds.west),15.0))
        else target.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(bounds.north,bounds.east,bounds.south,bounds.west),fitPaddingPixels))
    }
    private fun emptyCollection()="""{"type":"FeatureCollection","features":[]}"""
}

@OptIn(ExperimentalLayoutApi::class)
@Composable internal fun JournalMap(points: List<TrackPoint>, visits: List<Visit>, modifier: Modifier=Modifier,replay:ReplayFrame?=null,endpoints:RouteEndpoints=RouteEndpoints.from(points),showOutliers:Boolean=false,onOutliers:((Boolean)->Unit)?=null,allOutlierFallback:Boolean=false,onVisitSelected:((Visit)->Unit)?=null,photoLayer:PhotoMapLayer?=null,follow:MapFollowUi?=null,followMetric:Boolean=true,activeRecording:ActiveRecordingMapUi?=null,roadRoute:RoadRoute?=null,showStraightSegments:Boolean=true,roadController:com.bontecou.whereday.platform.RoadDisplayController?=null,roadVisible:Boolean=true,visitFocus:VisitMapFocus?=null,onVisitFocusCleared:()->Unit={},inspectionScope:Any?=null,pointMarkerSource:MapPointMarkerSource?=null,
    home:MapHomeOptions?=null,fullBleed:Boolean=false) {
    val context=LocalContext.current
    val pointLocale=LocalConfiguration.current.locales[0]
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var savedCamera by rememberSaveable(stateSaver=JournalMapCamera.Saver) {mutableStateOf<JournalMapCamera?>(null)}
    val renderer=remember(context) { JournalMapRenderer().apply {val scale=context.resources.displayMetrics.density;fitPaddingPixels=kotlin.math.ceil(48*scale).toInt()} }
    // Route ink follows the Whereday palette: accent paths, place inks for visits/flags, live dot, rust highlights.
    val inks=mapInks()
    val flagScale=context.resources.displayMetrics.density
    renderer.inks=inks
    renderer.startIcon=remember(flagScale,inks) {routeFlag(flagScale,false,inks)}
    renderer.endIcon=remember(flagScale,inks) {routeFlag(flagScale,true,inks)}
    val mapView=remember(context) { MapLibre.getInstance(context); RendererReadyMapView(context).also { it.onCreate(null) } }
    var localOnline by rememberSaveable { mutableStateOf(false) }
    var localPoints by rememberSaveable { mutableStateOf(true) }
    var localPath by rememberSaveable {mutableStateOf(true)}
    var localVisits by rememberSaveable {mutableStateOf(true)}
    var localEnds by rememberSaveable {mutableStateOf(true)}
    // The home map follows the persisted Layers sheet; the framed journal maps keep their own per-screen switches.
    val online=home?.layers?.online ?: localOnline
    val showPoints=home?.layers?.points ?: localPoints
    val showPath=home?.layers?.path ?: localPath
    val showVisits=home?.layers?.visits ?: localVisits
    val showEnds=home?.layers?.endpoints ?: localEnds
    val straightSegments=home?.layers?.straightGaps ?: showStraightSegments
    var pickingVisit by remember {mutableStateOf(false)}
    var candidateVisits by remember {mutableStateOf<Set<String>?>(null)}
    val latestVisitAction by rememberUpdatedState(onVisitSelected)
    val latestVisibleVisits by rememberUpdatedState(visits)
    var selectedPointId by rememberSaveable(inspectionScope) {mutableStateOf<String?>(null)}
    // Only the ID is saved. This transient eligibility hint never supplies displayed metadata.
    var selectionHint by remember(inspectionScope) {mutableStateOf<MapPointSelectionHint?>(null)}
    fun selectPoint(point:TrackPoint?) {
        selectedPointId=point?.id;selectionHint=point?.let {MapPointSelectionHint(it.id,it.outlier)}
    }
    // The native MapView outlives a saved-state owner replacement when the date scope changes.
    val latestPointSelection by rememberUpdatedState<(TrackPoint?)->Unit>({point->selectPoint(point)})
    var pickingPoint by rememberSaveable {mutableStateOf(false)}
    var configuring by rememberSaveable {mutableStateOf(false)}
    val preparedRoad=rememberRoadDisplay(roadController,points,roadVisible && showPath && !configuring && !pickingVisit && !pickingPoint,showOutliers,allOutlierFallback)
    val displayedRoad=if(roadController==null) roadRoute else preparedRoad
    val attributionLinks=androidx.compose.ui.platform.LocalUriHandler.current
    var attributionError by remember(displayedRoad) {mutableStateOf(false)}
    val layers=MapLayers(showPath,showPoints,showVisits,showEnds,showOutliers)
    val contents=remember(points,visits,endpoints,layers,replay,allOutlierFallback) {MapPresentation.build(points,visits,endpoints,layers,replay,allOutlierFallback)}
    var viewport by remember(mapView) {mutableStateOf<MapPointViewport?>(null)}
    var metersPerDp by remember(mapView) {mutableStateOf<Double?>(null)}
    var viewportSize by remember(mapView) {mutableStateOf(0 to 0)}
    fun refreshViewport() {
        val map=renderer.map ?: return
        if(renderer.disposed || !renderer.queryReady || mapView.width<=0 || mapView.height<=0) return
        val bounds=DoubleArray(4);map.projection.getVisibleCoordinateBounds(bounds)
        val scale=context.resources.displayMetrics.density.toDouble()
        viewport=MapPointViewport.geographic(bounds[0],bounds[1],bounds[2],bounds[3],mapView.width/scale,mapView.height/scale).takeIf {it.usable}
        viewportSize=mapView.width to mapView.height
        renderer.viewWidthPx=mapView.width;renderer.viewHeightPx=mapView.height
    }
    val markerLoad=rememberMapPointMarkers(contents.points,viewport,selectedPointId,pointMarkerSource,replay?.index,showPoints,inspectionScope)
    val displayPoints=markerLoad.markers.points
    val selectedPoint=markerLoad.markers.selected
    val excludedSelection=selectionHint?.let {it.id==selectedPointId && it.outlier && !showOutliers && !(allOutlierFallback && endpoints.firstGood==null)}==true
    LaunchedEffect(selectedPointId,markerLoad.ready,selectedPoint,showPoints,excludedSelection) {
        if(!showPoints || excludedSelection || (markerLoad.ready && selectedPoint==null)) selectPoint(null)
    }
    LaunchedEffect(visitFocus) {if(visitFocus!=null) selectedPointId=null}
    val latestDisplayPoints by rememberUpdatedState(displayPoints)
    val latestSelectedPoint by rememberUpdatedState(selectedPointId)
    SideEffect {
        if(selectedPoint!=null && selectedPoint.id==selectedPointId) selectionHint=MapPointSelectionHint(selectedPoint.id,selectedPoint.outlier)
        else if(selectedPointId==null) selectionHint=null
        renderer.markerPoints=displayPoints;renderer.selectedPointId=selectedPoint?.id;renderer.update()
    }
    val roadGeometry=remember(points,contents.segments,layers,replay,allOutlierFallback,displayedRoad,straightSegments) {RoadMapGeometry.build(points,contents.segments,layers,displayedRoad,replay,allOutlierFallback,straightSegments)}
    val photoClusters=photoLayer?.visibleClusters.orEmpty()
    val activeSnapshot=activeRecording?.snapshot.takeIf {activeRecording?.enabled==true}
    val activeSummary=activeSnapshot?.let {stringResource(R.string.active_recording_map_summary,it.points.size,it.count)}
    fun countLabel(id:Int,count:Long)=context.resources.getQuantityString(id,count.coerceAtMost(Int.MAX_VALUE.toLong()).toInt(),count)
    val mapDescription=listOfNotNull(
        stringResource(R.string.map_a11y_title),
        if(roadGeometry.roads.isNotEmpty()) stringResource(R.string.map_road_estimate_description) else null,
        stringResource(if(showPath) R.string.map_a11y_path_shown else R.string.map_a11y_path_hidden),
        countLabel(R.plurals.map_a11y_points,displayPoints.size.toLong()),countLabel(R.plurals.map_a11y_visits,contents.visits.size.toLong()),
        stringResource(if(showEnds) R.string.map_a11y_endpoints_shown else R.string.map_a11y_endpoints_hidden),
        countLabel(R.plurals.map_a11y_groups,photoClusters.size.toLong()),countLabel(R.plurals.map_a11y_photos,photoClusters.sumOf {it.photoCount}),
        countLabel(R.plurals.map_a11y_places,photoClusters.sumOf {it.placeCount}),countLabel(R.plurals.map_a11y_areas,photoClusters.count {it.isArea}.toLong()),
        if(follow!=null) stringResource(if(follow.fix!=null) R.string.map_a11y_location_shown else R.string.map_a11y_location_hidden) else null,
        if(photoClusters.isNotEmpty() && photoLayer?.dots==true) stringResource(R.string.map_photo_dots_description) else null,
        activeSummary,stringResource(R.string.map_a11y_gestures),
    ).joinToString(" ")
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
    val latestVisitFocus by rememberUpdatedState(visitFocus)
    LaunchedEffect(visitFocus) {if(visitFocus!=null) latestFollow?.stop?.invoke()}
    val latestActiveRecording by rememberUpdatedState(activeSnapshot)
    val latestRoadGeometry by rememberUpdatedState(roadGeometry)
    renderer.styleJson={mapOnline,mapDark->MapStyles.json(context,mapOnline,mapDark)}
    if(home!=null) {
        val art=remember(context,dark) {MapMarkerArt(context,dark)}
        val homeInks=wd
        val strings=mapHomeStrings()
        renderer.home=remember(home.mode,home.replayAhead,home.focusCandidates,contents,roadGeometry,visits,visitFocus,homeInks,art,strings,layers,replay!=null) {
            buildMapHomeRender(home,contents,roadGeometry,visits,visitFocus,homeInks,art,strings,layers,replay!=null)
        }
        val density=LocalDensity.current
        val direction=androidx.compose.ui.platform.LocalLayoutDirection.current
        renderer.chromePadding=with(density) {intArrayOf(home.padding.calculateLeftPadding(direction).roundToPx(),home.padding.calculateTopPadding().roundToPx(),
            home.padding.calculateRightPadding(direction).roundToPx(),home.padding.calculateBottomPadding().roundToPx())}
        SideEffect {home.controller.attach(fit={latestFollow?.stop?.invoke();renderer.follow(latestFollow?.fix,false);renderer.fit()},
            fitActive={latestFollow?.stop?.invoke();renderer.follow(latestFollow?.fix,false);renderer.fitActiveRecording()},
            focus={coordinates->latestFollow?.stop?.invoke();renderer.follow(latestFollow?.fix,false);renderer.fitCoordinates(coordinates);renderer.autoFitted=false},
            center={renderer.cameraSnapshot()?.target})}
        // A new range on the home map is fitted afresh; the same range (or a recreated screen) keeps its camera.
        var fittedScope by rememberSaveable {mutableStateOf<String?>(null)}
        val scopeKey=inspectionScope?.toString()
        LaunchedEffect(scopeKey) {
            if(fittedScope!=null && fittedScope!=scopeKey && visitFocus==null) renderer.refitOnNextData()
            fittedScope=scopeKey
        }
        val pulsing=renderer.home?.now!=null && !rememberReduceMotion()
        LaunchedEffect(pulsing) {
            if(!pulsing) {renderer.pulse(1f);return@LaunchedEffect}
            val started=withFrameNanos {it}
            while(true) {
                val elapsed=(withFrameNanos {it}-started)/1_000_000_000.0
                renderer.pulse((1.0+0.25*kotlin.math.sin(elapsed*2*Math.PI/1.6)).toFloat())
                kotlinx.coroutines.delay(50)
            }
        }
    } else {renderer.home=null;renderer.chromePadding=null}
    DisposableEffect(mapView,lifecycle) {
        var started=false; var resumed=false; var destroyed=false
        val rendered=MapView.OnDidFinishRenderingFrameListener {_,_,_->
            if(!renderer.disposed && renderer.map!=null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) {
                val firstReady=!renderer.queryReady
                renderer.queryReady=true;mapView.isEnabled=true
                if(firstReady) renderer.cameraSnapshot()?.let {savedCamera=it}
                if(viewport==null || viewportSize!=(mapView.width to mapView.height)) refreshViewport()
            }
        }
        mapView.addOnDidFinishRenderingFrameListener(rendered)
        fun sync() {
            val shouldStart=lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            val shouldResume=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if(shouldStart && !started) { mapView.onStart(); started=true }
            if(shouldResume && !resumed) {
                mapView.onResume();resumed=true
                renderer.map?.triggerRepaint()
            }
            if(!shouldResume) {renderer.queryReady=false;mapView.isEnabled=false}
            if(!shouldResume && resumed) { mapView.onPause(); resumed=false }
            if(!shouldStart && started) { mapView.onStop(); started=false }
        }
        val observer=LifecycleEventObserver { _, event ->
            sync()
            if(event==Lifecycle.Event.ON_DESTROY && !destroyed) {
                mapView.isEnabled=false;renderer.queryReady=false;renderer.disposed=true;renderer.map=null
                mapView.onDestroy(); destroyed=true
            }
        }
        lifecycle.addObserver(observer); sync()
        onDispose {
            mapView.isEnabled=false;renderer.queryReady=false;renderer.disposed=true;renderer.map=null
            lifecycle.removeObserver(observer);mapView.removeOnDidFinishRenderingFrameListener(rendered)
            if(!destroyed) {if(resumed) mapView.onPause();if(started) mapView.onStop();mapView.onDestroy()}
        }
    }
    if(pickingPoint) {
        BlockVisitLinks()
        WorkspaceAlertDialog(onDismissRequest={pickingPoint=false},title={Text(stringResource(R.string.map_point_browse))},text={
            Column(verticalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.map_point_sampled),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
                LazyColumn(Modifier.heightIn(max=400.dp)) {
                    items(displayPoints,key={it.id}) {point->
                        TextButton(onClick={selectPoint(point);pickingPoint=false},modifier=Modifier.fillMaxWidth().testTag("map-point-choice-${point.id}")) {
                            Column(Modifier.fillMaxWidth()) {
                                Text(pointTimestamp(point,pointLocale),style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=wd.textPrimary)
                                Text(String.format(pointLocale,"%.5f, %.5f",point.coordinate.latitude,point.coordinate.longitude),style=WdType.caption2,color=wd.textMuted)
                                if(point.outlier) Text(stringResource(R.string.map_point_outlier),style=WdType.caption2.copy(fontWeight=FontWeight.SemiBold),color=wd.inkRust)
                            }
                        }
                    }
                }
            }
        },confirmButton={TextButton(onClick={pickingPoint=false}) {Text(stringResource(R.string.map_visits_close))}})
    }
    if(pickingVisit && onVisitSelected!=null) WorkspaceAlertDialog(onDismissRequest={pickingVisit=false},title={Text(stringResource(R.string.map_visits))},text={
        val choices=contents.visits.filter {candidateVisits==null || it.id in candidateVisits.orEmpty()}
        if(choices.isEmpty()) Text(stringResource(R.string.map_visits_empty),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
        else LazyColumn(Modifier.heightIn(max=400.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            items(choices,key={it.id}) {visit-> VisitNavigationRow(visit) {pickingVisit=false;selectedPointId=null;onVisitSelected(visit)} }
        }
    },confirmButton={TextButton(onClick={pickingVisit=false}) {Text(stringResource(R.string.map_visits_close))}})
    if(configuring) WorkspaceAlertDialog(onDismissRequest={configuring=false},title={Text(stringResource(R.string.map_layers))},text={
        Column(Modifier.semantics {testTagsAsResourceId=true}.testTag("map-layers-content").verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            MapLayerToggle(stringResource(R.string.map_layer_path),showPath,"map-layer-path") {localPath=it}
            MapLayerToggle(stringResource(R.string.map_layer_points),showPoints,"map-layer-points") {localPoints=it}
            MapLayerToggle(stringResource(R.string.map_layer_visits),showVisits,"map-layer-visits") {localVisits=it}
            MapLayerToggle(stringResource(R.string.map_layer_endpoints),showEnds,"map-layer-endpoints") {localEnds=it}
            activeRecording?.let {current->
                MapLayerToggle(stringResource(R.string.active_recording_map_layer),current.enabled,"map-layer-active-recording",change=current.change)
                Text(stringResource(R.string.active_recording_map_description),style=MaterialTheme.typography.bodySmall,color=wd.textMuted)
            }
            photoLayer?.let {photo->
                MapLayerToggle(stringResource(R.string.map_layer_photos),photo.enabled,"map-layer-photos",change=photo.enable)
                MapLayerToggle(stringResource(R.string.map_layer_images),photo.images,"map-layer-photo-images",enabled=!photo.dots) {value->
                    latestPhotoLayer?.takeIf {!it.dots}?.showImages?.invoke(value)
                }
                MapLayerToggle(stringResource(R.string.map_layer_photo_dots),photo.dots,"map-layer-photo-dots",change=photo.showDots)
                Text(stringResource(R.string.map_photo_dots_description),style=MaterialTheme.typography.bodySmall,color=wd.textMuted)
            }
            if(onOutliers!=null) MapLayerToggle(stringResource(R.string.map_layer_outliers),showOutliers,"map-layer-outliers",change=onOutliers)
            Text(stringResource(if(onOutliers!=null) R.string.map_outliers_main else R.string.map_outliers_local),style=MaterialTheme.typography.bodySmall,color=wd.textMuted)
            val (start,end)=endpoints.selected(showOutliers,allOutlierFallback)
            start?.let {Text(stringResource(R.string.map_route_start,timeText(it.time)),style=WdType.caption2,color=wd.textMuted)}
            end?.takeIf {it.id!=start?.id}?.let {Text(stringResource(R.string.map_route_end,timeText(it.time)),style=WdType.caption2,color=wd.textMuted)}
        }
    },confirmButton={TextButton(onClick={configuring=false}) {Text(stringResource(R.string.map_layers_done))}})
    val colors=wd
    // At large text the floating controls leave the map and wrap above it, so every label stays readable.
    val overlay=LocalDensity.current.fontScale<1.3f
    val onlineControl:@Composable ()->Unit={Box(Modifier.wdFloating(CircleShape,colors,6.dp)) {WdChip(stringResource(R.string.map_online),online,{ localOnline=!localOnline },Modifier.heightIn(min=44.dp),icon=Icons.Outlined.Public)}}
    val layersControl:@Composable ()->Unit={TraceCapsule(stringResource(R.string.map_layers),{configuring=true},icon=Icons.Outlined.Layers,floating=true)}
    val fitControl:@Composable ()->Unit={TraceCapsule(stringResource(R.string.map_fit),{ follow?.stop?.invoke();renderer.follow(follow?.fix,false);renderer.fit() },icon=Icons.Outlined.CenterFocusWeak,floating=true)}
    val followControl:@Composable ()->Unit={follow?.let {source->
        val controls=if(visitFocus==null) source else MapFollowUi(source.enabled,source.showing,source.fix,source.message,
            {if(!source.enabled) onVisitFocusCleared();source.toggle()},source.stop,source.hide)
        MapFollowControl(controls,followMetric)
    }}
    val nativeMap:@Composable (Modifier)->Unit={mapModifier->AndroidView(factory={ mapView.apply {
        addOnDidFailLoadingMapListener { renderer.error=true }
        getMapAsync { map ->
            if(renderer.disposed) return@getMapAsync
            renderer.map=map
            renderer.restoreCamera(savedCamera)
            map.addOnCameraIdleListener {refreshViewport();renderer.cameraSnapshot()?.let {savedCamera=it}
                // The home map's scale bar reads metres per dp at the camera's latitude.
                renderer.republishLabels()
                map.cameraPosition.target?.let {target->metersPerDp=map.projection.getMetersPerPixelAtLatitude(target.latitude)*context.resources.displayMetrics.density}}
            map.addOnCameraMoveStartedListener {reason->if(reason==MapLibreMap.OnCameraMoveStartedListener.REASON_API_GESTURE) {renderer.autoFitted=false;latestFollow?.stop?.invoke()}}
            renderer.follow(latestFollow?.fix,latestFollow?.enabled==true && latestVisitFocus==null)
            map.addOnMapClickListener {coordinate->
                if(renderer.disposed || !renderer.queryReady || renderer.map!==map) return@addOnMapClickListener false
                val photoAction=latestPhotoLayer
                if(photoAction!=null && photoAction.visibleClusters.isNotEmpty()) {
                    val pixel=map.projection.toScreenLocation(coordinate)
                    val clusters=photoAction.visibleClusters
                    val density=context.resources.displayMetrics.density
                    val matches=if(photoAction.dots) {
                        val centers=clusters.map {marker->map.projection.toScreenLocation(LatLng(marker.coordinate.latitude,marker.coordinate.longitude)).let {PhotoMarkerPoint((it.x/density).toDouble(),(it.y/density).toDouble())}}
                        // Native Android targets are 48 dp; ties keep the stable directory order.
                        val index=PhotoDotHitTesting.targetIndex(PhotoMarkerPoint((pixel.x/density).toDouble(),(pixel.y/density).toDouble()),centers,48.0)
                        listOfNotNull(index?.let {clusters[it]})
                    } else {
                        val radius=28f*density
                        val ids=map.queryRenderedFeatures(android.graphics.RectF(pixel.x-radius,pixel.y-radius,pixel.x+radius,pixel.y+radius),"journal-photo").mapNotNull {it.getStringProperty("id")}.toSet()
                        clusters.filter {it.id in ids}
                    }
                    if(matches.isNotEmpty()) {latestPointSelection(null);photoAction.open(matches);return@addOnMapClickListener true}
                }
                val action=latestVisitAction
                if(action!=null && latestLayers.visits) {
                    val pixel=map.projection.toScreenLocation(coordinate)
                    val radius=24f*context.resources.displayMetrics.density
                    val bounds=android.graphics.RectF(pixel.x-radius,pixel.y-radius,pixel.x+radius,pixel.y+radius)
                    val ids=map.queryRenderedFeatures(bounds,"journal-visit").mapNotNull {it.getStringProperty("id")}.toSet()
                    val matches=latestVisibleVisits.filter {it.id in ids}
                    if(matches.size==1) {latestPointSelection(null);action(matches.single());return@addOnMapClickListener true}
                    if(matches.size>1) {latestPointSelection(null);candidateVisits=ids;pickingVisit=true;return@addOnMapClickListener true}
                }
                val pixel=map.projection.toScreenLocation(coordinate)
                val density=context.resources.displayMetrics.density
                val radius=24f*density
                val ids=map.queryRenderedFeatures(android.graphics.RectF(pixel.x-radius,pixel.y-radius,pixel.x+radius,pixel.y+radius),"journal-point","journal-outlier").mapNotNull {it.getStringProperty("id")}.toSet()
                val candidates=latestDisplayPoints.filter {it.id in ids}
                val centers=candidates.map {point->map.projection.toScreenLocation(LatLng(point.coordinate.latitude,point.coordinate.longitude)).let {it.x.toDouble()/density to it.y.toDouble()/density}}
                val hit=pointHitIndex(pixel.x.toDouble()/density,pixel.y.toDouble()/density,centers)?.let {candidates[it]}
                val hadSelection=latestSelectedPoint!=null
                latestPointSelection(hit?.takeIf {it.id!=latestSelectedPoint})
                hit!=null || hadSelection
            }
            renderer.render(latestPoints,latestVisits,latestOnline,latestDark,latestLayers,latestReplay,latestEnds,latestFallback,latestPhotoLayer?.visibleClusters.orEmpty(),latestPhotoLayer?.icons.orEmpty(),latestActiveRecording,latestRoadGeometry,latestPhotoLayer?.dots==true,latestVisitFocus)
        }
    } },update={ view->view.contentDescription=mapDescription;renderer.follow(follow?.fix,follow?.enabled==true && visitFocus==null);renderer.viewWidthPx=view.width;renderer.viewHeightPx=view.height;renderer.densityScale=context.resources.displayMetrics.density
        // The home map credits tiles itself; MapLibre's own logo would sit under the tab bar.
        renderer.map?.uiSettings?.let {ui->val homeMap=home!=null;if(ui.isLogoEnabled==homeMap) ui.isLogoEnabled=!homeMap;if(ui.isAttributionEnabled==homeMap) ui.isAttributionEnabled=!homeMap
            // The compass appears when the map is rotated; on the home map it sits below the header, left of the rail.
            renderer.chromePadding?.let {inset->ui.setCompassMargins(0,inset[1],inset[2],0)}}
        renderer.render(points,visits,online,dark,layers,replay,endpoints,allOutlierFallback,photoClusters,photoLayer?.icons.orEmpty(),activeSnapshot,roadGeometry,photoLayer?.dots==true,visitFocus) },modifier=mapModifier.testTag("journal-native-map"))}
    if(home!=null) {
        JournalMapHomeFrame(modifier,home,nativeMap,markerLoad,selectedPoint,followMetric,metersPerDp,
            focusedVisit=visitFocus?.let {focus->visits.find {it.id==focus.id}}.takeIf {home.showsFocusLabel},{selectPoint(null)},renderer.error,roadGeometry.roads.isNotEmpty(),displayedRoad?.attributions.orEmpty(),photoLayer,online,
            browsePoints={pickingPoint=true},browseVisits=if(onVisitSelected!=null && visits.isNotEmpty()) ({selectedPointId=null;candidateVisits=null;pickingVisit=true}) else null)
        return
    }
    Column(modifier,verticalArrangement=Arrangement.spacedBy(10.dp)) {
        if(!overlay) FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            onlineControl();layersControl();fitControl()
        }
        if(!overlay) followControl()
        val frame=RoundedCornerShape(if(fullBleed) 0.dp else 20.dp)
        // Trace fills its page edge to edge; elsewhere the map is a 300dp framed card.
        Box(Modifier.fillMaxWidth().then(if(fullBleed) Modifier.weight(1f) else Modifier.height(300.dp)).clip(frame).background(colors.dialTrack)
            .then(if(fullBleed) Modifier else Modifier.border(1.dp,colors.border,frame))) {
            nativeMap(Modifier.fillMaxSize())
            if(overlay && fullBleed) {
                // Trace owns the top edge (back pill); its map controls sit just below it, on the trailing side.
                Column(Modifier.align(Alignment.TopEnd).statusBarsPadding().padding(top=72.dp,end=16.dp),horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(8.dp)) {onlineControl();layersControl();fitControl()}
            } else if(overlay) {
                Box(Modifier.align(Alignment.TopStart).padding(10.dp)) {onlineControl()}
                Column(Modifier.align(Alignment.TopEnd).padding(10.dp),horizontalAlignment=Alignment.End,verticalArrangement=Arrangement.spacedBy(8.dp)) {layersControl();fitControl()}
                // Bottom-end keeps the MapLibre logo and attribution (bottom-start) visible and tappable.
                Box(Modifier.align(Alignment.BottomEnd).padding(10.dp).widthIn(max=300.dp)) {followControl()}
            }
        }
        visitFocus?.let {focus->visits.find {it.id==focus.id}?.let {visit->
            Row(Modifier.wdFloating(CircleShape,colors,0.dp).padding(horizontal=14.dp,vertical=10.dp),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Box(Modifier.size(10.dp).clip(CircleShape).background(colors.placeInks[2]).border(2.dp,colors.inkRust,CircleShape))
                Text(stringResource(R.string.map_selected_visit,visit.name?.takeIf {it.isNotBlank()} ?: stringResource(R.string.timeline_visit)),Modifier.testTag("map-focused-visit"),style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=colors.textPrimary)
            }
        }}
        if(markerLoad.loading) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            CircularProgressIndicator(Modifier.size(14.dp),color=colors.accent,trackColor=colors.dialTrack,strokeWidth=2.dp)
            Text(stringResource(R.string.map_point_loading),Modifier.testTag("map-point-loading"),style=WdType.caption2,color=colors.textMuted)
        }
        if(markerLoad.failed) Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.map_point_load_failed),Modifier.weight(1f).testTag("map-point-load-failed"),style=MaterialTheme.typography.bodySmall,color=colors.inkRust)
            TraceCapsule(stringResource(R.string.map_point_retry),markerLoad.retry,Modifier.testTag("map-point-retry"),icon=Icons.Outlined.Refresh)
        }
        selectedPoint?.let {MapPointInspection(it,followMetric) {selectPoint(null)}}
        activeRecording?.takeIf {it.enabled}?.let {current->
            WdCard(radius=14.dp) {
                Column(Modifier.padding(14.dp),verticalArrangement=Arrangement.spacedBy(6.dp)) {
                    Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                        WdLiveDot(Modifier.size(16.dp),color=if(current.status==ActiveRecordingMapStatus.ready) colors.liveDot else colors.textMuted)
                        Text(stringResource(R.string.map_redesign_live_path).uppercase(),style=WdType.eyebrow,color=colors.textMuted)
                    }
                    activeSummary?.let {Text(it,modifier=Modifier.testTag("active-recording-map-summary"),style=MaterialTheme.typography.bodyMedium,color=colors.textPrimary)}
                    activeSnapshot?.let {snapshot->
                        snapshot.endpoints.first?.let {point->Text(stringResource(R.string.active_recording_map_start,timeText(point.time),"${point.coordinate.latitude}, ${point.coordinate.longitude}"),style=WdType.caption2,color=colors.textMuted)}
                        snapshot.endpoints.last?.takeIf {it.id!=snapshot.endpoints.first?.id}?.let {point->Text(stringResource(R.string.active_recording_map_latest,timeText(point.time),"${point.coordinate.latitude}, ${point.coordinate.longitude}"),style=WdType.caption2,color=colors.textMuted)}
                    }
                    val message=when(current.status) {
                        ActiveRecordingMapStatus.inactive->R.string.active_recording_map_inactive
                        ActiveRecordingMapStatus.paused->R.string.active_recording_map_paused
                        ActiveRecordingMapStatus.loading->R.string.active_recording_map_loading
                        ActiveRecordingMapStatus.empty->R.string.active_recording_map_empty
                        ActiveRecordingMapStatus.failed->R.string.active_recording_map_failed
                        else->null
                    }
                    message?.let {Text(stringResource(it),style=MaterialTheme.typography.bodySmall,color=if(current.status==ActiveRecordingMapStatus.failed) colors.inkRust else colors.textMuted)}
                    FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp),modifier=Modifier.padding(top=4.dp)) {
                        if(current.status==ActiveRecordingMapStatus.failed) TraceCapsule(stringResource(R.string.active_recording_map_retry),current.retry,icon=Icons.Outlined.Refresh)
                        TraceCapsule(stringResource(R.string.active_recording_map_fit),{follow?.stop?.invoke();renderer.follow(follow?.fix,false);renderer.fitActiveRecording()},Modifier.testTag("fit-active-recording"),
                            enabled=activeSnapshot?.count?.let {it>0}==true,icon=Icons.Outlined.CenterFocusWeak)
                    }
                }
            }
        }
        val browsePoints=displayPoints.isNotEmpty()
        val browseVisits=onVisitSelected!=null && visits.isNotEmpty()
        val photoActions=photoClusters.isNotEmpty() || photoLayer?.browsePlaces!=null || photoLayer?.retryDirectory!=null
        if(!fullBleed && (browsePoints || browseVisits || photoActions)) FlowRow(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            if(browsePoints) TraceCapsule(stringResource(R.string.map_point_browse),{pickingPoint=true},Modifier.testTag("map-point-browse"),icon=Icons.Outlined.ScatterPlot)
            if(browseVisits) TraceCapsule(stringResource(R.string.map_visits),{selectedPointId=null;candidateVisits=null;pickingVisit=true},icon=Icons.Outlined.Place)
            if(photoClusters.isNotEmpty()) TraceCapsule(stringResource(R.string.map_photos),{photoLayer?.open?.invoke(photoClusters)},icon=Icons.Outlined.PhotoLibrary)
            photoLayer?.browsePlaces?.let {browse->TraceCapsule(stringResource(R.string.map_browse_photos),browse,Modifier.testTag("browse-photo-places"),icon=Icons.Outlined.PhotoAlbum)}
            photoLayer?.retryDirectory?.let {retry->TraceCapsule(stringResource(R.string.map_retry_photos),retry,icon=Icons.Outlined.Refresh)}
        }
        photoLayer?.directoryMessage?.let {Text(it,style=MaterialTheme.typography.bodySmall,color=colors.textMuted)}
        if(roadGeometry.roads.isNotEmpty()) {
            Text(stringResource(R.string.map_road_estimate_description),modifier=Modifier.testTag("road-estimate-caption"),style=WdType.caption2,color=colors.textMuted)
            FlowRow(horizontalArrangement=Arrangement.spacedBy(8.dp),verticalArrangement=Arrangement.spacedBy(4.dp),modifier=Modifier.testTag("road-attributions")) {
                displayedRoad?.attributions.orEmpty().distinct().forEach {credit->
                    TraceCapsule(stringResource(if(credit.kind==RoadAttributionKind.provider) R.string.map_road_provider_credit else R.string.map_road_data_credit,credit.name),
                        {try {attributionLinks.openUri(credit.url);attributionError=false} catch(_:Exception) {attributionError=true}},compact=true)
                }
            }
            if(attributionError) Text(stringResource(R.string.map_attribution_link_error),style=MaterialTheme.typography.bodySmall,color=colors.inkRust)
        }
        if(renderer.error) WdNotice(stringResource(R.string.map_tiles_failed),icon=Icons.Outlined.CloudOff,warning=true)
        if(online) Text(stringResource(R.string.map_tiles_disclosure),style=WdType.caption2,color=colors.textMuted)
        if(!online && !fullBleed) Text(stringResource(R.string.map_offline),style=WdType.caption2,color=colors.textMuted)
    }
}

@Composable private fun MapLayerToggle(label:String,checked:Boolean,id:String,enabled:Boolean=true,change:(Boolean)->Unit) {
    Row(Modifier.fillMaxWidth().testTag(id).toggleable(checked,enabled=enabled,role=Role.Checkbox,onValueChange=change).heightIn(min=48.dp),
        horizontalArrangement=Arrangement.spacedBy(12.dp),verticalAlignment=Alignment.CenterVertically) {
        Text(label,Modifier.weight(1f),style=MaterialTheme.typography.bodyLarge,color=if(enabled) wd.textPrimary else wd.textMuted);Switch(checked,null,enabled=enabled,colors=wdSwitchColors())
    }
}

/** Map ink as ARGB ints for MapLibre: the Whereday palette, never Material defaults. */
internal data class MapInks(val path:Int=0xFF1E2B45.toInt(),val road:Int=0xFF24459E.toInt(),val active:Int=0xFFA8381F.toInt(),val visit:Int=0xFFB7862B.toInt(),val live:Int=0xFFF08A6B.toInt(),
    val highlight:Int=0xFFA8381F.toInt(),val photo:Int=0xFF5E7F5A.toInt(),val paper:Int=0xFFFBF9F4.toInt(),val background:Int=0xFFF3EFE6.toInt(),
    val start:Int=0xFF5E7F5A.toInt(),val end:Int=0xFFC2462A.toInt(),val pole:Int=0xFF1E2B45.toInt()) {
    fun hex(color:Int)=String.format(Locale.ROOT,"#%06X",color and 0xFFFFFF)
}
@Composable internal fun mapInks():MapInks {
    val c=wd
    return remember(c) {// Estimated roads use iOS's pencil-blue onboarding ink so they stay distinct from the raw navy path they replace.
    MapInks(path=c.accent.toArgb(),road=(if(c.dark) 0xFF94B3FF else 0xFF24459E).toInt(),active=c.inkRust.toArgb(),visit=c.placeInks[2].toArgb(),live=c.liveDot.toArgb(),highlight=c.inkRust.toArgb(),
        photo=c.placeInks[3].toArgb(),paper=c.card.toArgb(),background=c.surface.toArgb(),start=c.accent.toArgb(),end=c.placeInks[1].toArgb(),pole=c.textPrimary.toArgb())}
}

/** A paper capsule button used across the Trace map: hairline, mono label, optional glyph; floating adds the map shadow. */
@Composable internal fun TraceCapsule(text:String,onClick:()->Unit,modifier:Modifier=Modifier,enabled:Boolean=true,icon:ImageVector?=null,floating:Boolean=false,compact:Boolean=false) {
    val colors=wd
    val shape=CircleShape
    val ink=if(enabled) colors.textPrimary else colors.textMuted.copy(alpha=0.6f)
    Row((if(floating) modifier.wdFloating(shape,colors,6.dp) else modifier.clip(shape).background(colors.card).border(1.dp,colors.border,shape))
        .clickable(enabled=enabled,role=Role.Button,onClick=onClick).heightIn(min=if(compact) 36.dp else 44.dp).padding(horizontal=if(compact) 12.dp else 14.dp,vertical=6.dp),
        verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(6.dp)) {
        if(icon!=null) Icon(icon,null,Modifier.size(16.dp),tint=if(enabled) colors.accent else ink)
        Text(text,style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=ink)
    }
}

private fun routeFlag(scale:Float,end:Boolean,inks:MapInks):android.graphics.Bitmap {
    val bitmap=android.graphics.Bitmap.createBitmap((44*scale).toInt().coerceAtLeast(1),(42*scale).toInt().coerceAtLeast(1),android.graphics.Bitmap.Config.ARGB_8888)
    bitmap.density=(160*scale).toInt()
    val canvas=android.graphics.Canvas(bitmap);canvas.scale(scale,scale);canvas.translate(6f,3f)
    val paint=android.graphics.Paint(android.graphics.Paint.ANTI_ALIAS_FLAG)
    paint.color=inks.paper;paint.strokeWidth=5f;canvas.drawLine(16f,5f,16f,39f,paint)
    paint.color=inks.pole;paint.strokeWidth=2f;canvas.drawLine(16f,5f,16f,39f,paint)
    paint.color=inks.paper;canvas.drawCircle(16f,39f,3.5f,paint)
    paint.color=if(end) inks.end else inks.start;canvas.drawCircle(16f,39f,2.5f,paint)
    if(end) {
        paint.color=inks.paper;canvas.drawRect(15f,5f,37f,23f,paint)
        paint.color=inks.end
        for(row in 0..3) for(col in 0..4) if((row+col)%2==0) canvas.drawRect(16f+col*4,6f+row*4,20f+col*4,10f+row*4,paint)
    } else {
        val outline=android.graphics.Path();outline.moveTo(15f,4.5f);outline.lineTo(39f,14f);outline.lineTo(15f,23.5f);outline.close()
        paint.color=inks.paper;canvas.drawPath(outline,paint)
        val path=android.graphics.Path();path.moveTo(16f,6f);path.lineTo(37f,14f);path.lineTo(16f,22f);path.close()
        paint.color=inks.start;canvas.drawPath(path,paint)
    }
    return bitmap
}
