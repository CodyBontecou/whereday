package com.bontecou.whereday

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Modifier
import androidx.compose.ui.Alignment
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import org.maplibre.android.MapLibre
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.*
import org.maplibre.android.style.layers.CircleLayer
import org.maplibre.android.style.layers.PropertyFactory.*
import org.maplibre.android.style.sources.GeoJsonSource
import java.util.concurrent.atomic.AtomicBoolean

/** The picker edits only a draft. Lookup and map egress require separate explicit actions. */
@Composable internal fun PlaceLocationPicker(initial:Coordinate?,dismiss:()->Unit,use:(PlaceLocationSelection)->Unit) {
    var searching by rememberSaveable {mutableStateOf(false)}
    var selectedSource by rememberSaveable {mutableStateOf("userEntered")}
    var selectedName by rememberSaveable {mutableStateOf<String?>(null)}
    var selectedAddress by rememberSaveable {mutableStateOf<String?>(null)}
    var reverseRequest by remember {mutableStateOf<Pair<Coordinate,Long>?>(null)}
    var reverseBusy by remember {mutableStateOf(false)}
    var reverseStatus by remember {mutableStateOf<String?>(null)}
    var reverseError by remember {mutableStateOf<String?>(null)}
    var pinPlaced by rememberSaveable {mutableStateOf(false)}
    var latitude by rememberSaveable {mutableStateOf(initial?.latitude)}
    var longitude by rememberSaveable {mutableStateOf(initial?.longitude)}
    var cameraLatitude by rememberSaveable {mutableStateOf(initial?.latitude ?: 0.0)}
    var cameraLongitude by rememberSaveable {mutableStateOf(initial?.longitude ?: 0.0)}
    var zoom by rememberSaveable {mutableStateOf(if(initial==null) 1.0 else 15.0)}
    var online by rememberSaveable {mutableStateOf(false)}
    var error by remember {mutableStateOf<String?>(null)}
    var ready by remember {mutableStateOf(false)}
    val context=LocalContext.current
    val graph=remember(context) {AppGraph.from(context)}
    val settings by graph.preferences.settings.collectAsState(initial=null)
    val networkAllowed=settings?.networkGeocoding==true
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val mapView=remember(context) {MapLibre.getInstance(context);MapView(context).also {it.onCreate(null)}}
    val alive=remember(mapView) {AtomicBoolean(true)}
    var map by remember {mutableStateOf<MapLibreMap?>(null)}
    val dark=MaterialTheme.colorScheme.background.luminance()<0.5f
    val selected=latitude?.let {lat->longitude?.let {lon->Coordinate(lat,lon)}}
    val latestSelected by rememberUpdatedState(selected)
    fun clearMetadata() {reverseRequest=null;selectedName=null;selectedAddress=null;selectedSource="userEntered";reverseError=null;reverseStatus=null}
    LaunchedEffect(reverseRequest,networkAllowed) {
        reverseBusy=false
        val request=reverseRequest ?: return@LaunchedEffect
        if(!networkAllowed) {reverseRequest=null;reverseStatus=null;reverseError="Network geocoding is disabled. You can still choose coordinates offline.";return@LaunchedEffect}
        reverseBusy=true;reverseError=null;reverseStatus=null
        try {
            val result=graph.placeLookup.reverse(request.first)
            ensureActive()
            selectedName=result?.name;selectedAddress=result?.address;selectedSource=if(result==null) "userEntered" else "coreLocationGeocode";pinPlaced=true
            if(result==null) reverseError="No address was found. You can enter one in the editor."
            else reverseStatus="Address found for selected pin. Review it before using."
        } catch(e:Exception) {if(e is CancellationException && e !is TimeoutCancellationException) throw e;reverseError=if(e is TimeoutCancellationException) "Address lookup timed out. Try again or enter an address in the editor." else "Address lookup is unavailable. You can enter one in the editor."}
        finally {if(reverseRequest==request) {reverseBusy=false;reverseRequest=null}}
    }
    fun drawPin(target:MapLibreMap?) {
        val point=latestSelected
        target?.style?.getSourceAs<GeoJsonSource>("selected-place")?.setGeoJson(
            if(point==null) """{"type":"FeatureCollection","features":[]}"""
            else """{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[${point.longitude},${point.latitude}]}}""")
    }
    DisposableEffect(mapView,lifecycle) {
        var started=false;var resumed=false;var destroyed=false
        fun sync() {
            val start=lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            val resume=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if(start&&!started) {mapView.onStart();started=true}
            if(resume&&!resumed) {mapView.onResume();resumed=true}
            if(!resume&&resumed) {mapView.onPause();resumed=false}
            if(!start&&started) {mapView.onStop();started=false}
        }
        val observer=LifecycleEventObserver {_,event->
            sync();if(event==Lifecycle.Event.ON_DESTROY&&!destroyed) {alive.set(false);mapView.onDestroy();destroyed=true}
        }
        lifecycle.addObserver(observer);sync()
        onDispose {
            alive.set(false)
            lifecycle.removeObserver(observer)
            if(!destroyed) {if(resumed) mapView.onPause();if(started) mapView.onStop();mapView.onDestroy()}
            map=null
        }
    }
    LaunchedEffect(map,online,dark) {
        val target=map ?: return@LaunchedEffect
        error=null
        val builder=if(online) Style.Builder().fromUri("https://tiles.openfreemap.org/styles/${if(dark) "dark" else "liberty"}")
            else Style.Builder().fromJson("""{"version":8,"sources":{},"layers":[{"id":"background","type":"background","paint":{"background-color":"${if(dark) "#18211c" else "#edf1eb"}"}}]}""")
        target.setStyle(builder) {style->
            if(!alive.get() || map!==target) return@setStyle
            style.addSource(GeoJsonSource("selected-place","""{"type":"FeatureCollection","features":[]}"""))
            style.addLayer(CircleLayer("selected-pin","selected-place").withProperties(circleColor("#bf3b36"),circleRadius(9f),circleStrokeColor("#ffffff"),circleStrokeWidth(3f)))
            drawPin(target)
        }
    }
    LaunchedEffect(map,selected) {drawPin(map)}
    Dialog(onDismissRequest=dismiss,properties=DialogProperties(usePlatformDefaultWidth=false)) {
        BackHandler(onBack=dismiss)
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().semantics {testTagsAsResourceId=true}.testTag("place-location-content").verticalScroll(rememberScrollState()).padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton(modifier=Modifier.weight(1f),onClick=dismiss) {Text("Cancel location selection")}
                    TextButton(modifier=Modifier.weight(1f),enabled=selected!=null,onClick={selected?.let {use(PlaceLocationSelection(it,pinPlaced,selectedName,selectedAddress,selectedSource))}}) {Text("Use this location")}
                }
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("Choose location",Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall)
                    TextButton(onClick={searching=true}) {Text("Search places")}
                }
                Text("Move the map, then tap to place a pin or drop one at the map center. You can also enter coordinates in the place editor.")
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("Online street map",Modifier.weight(1f).padding(top=12.dp))
                    Switch(online,{online=it},Modifier.semantics {contentDescription="Load online street map"})
                }
                Text("Online tiles: OpenFreeMap / OpenMapTiles / OpenStreetMap. Enabling sends requests for the viewed area to the map provider.",style=MaterialTheme.typography.bodySmall)
                AndroidView(factory={mapView.apply {
                    addOnDidFailLoadingMapListener {error="Street tiles could not load. Turn off the online map or enter coordinates in the editor."}
                    getMapAsync {target->
                        if(!alive.get()) return@getMapAsync
                        target.uiSettings.isAttributionEnabled=true
                        target.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(cameraLatitude,cameraLongitude),zoom))
                        target.addOnCameraIdleListener {
                            target.cameraPosition.target?.wrap()?.let {cameraLatitude=it.latitude;cameraLongitude=it.longitude}
                            zoom=target.cameraPosition.zoom
                        }
                        target.addOnMapClickListener {point->
                            val wrapped=point.wrap();clearMetadata();latitude=wrapped.latitude;longitude=wrapped.longitude;pinPlaced=true;true
                        }
                        map=target;ready=true
                    }
                }},modifier=Modifier.fillMaxWidth().height(280.dp).semantics {contentDescription="Location selection map. Use Drop pin at map center for an accessible selection."})
                if(!online) Text("Offline coordinate map. Street details require the online map.",style=MaterialTheme.typography.bodySmall)
                error?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                OutlinedButton(enabled=ready,onClick={
                    map?.cameraPosition?.target?.wrap()?.let {clearMetadata();latitude=it.latitude;longitude=it.longitude;pinPlaced=true}
                }) {Text("Drop pin at map center")}
                Text("Map center: ${"%.6f".format(java.util.Locale.ROOT,cameraLatitude)}, ${"%.6f".format(java.util.Locale.ROOT,cameraLongitude)}",style=MaterialTheme.typography.bodySmall)
                if(selected==null) Text("No location selected.")
                else {
                    Text("Selected location",style=MaterialTheme.typography.titleMedium)
                    selectedName?.let {Text(it)}
                    selectedAddress?.let {Text(it)}
                    Text("Latitude: ${selected.latitude}")
                    Text("Longitude: ${selected.longitude}")
                    Text("This updates the draft only. Save the place in the editor to keep it.",style=MaterialTheme.typography.bodySmall)
                    Text("Finding an address sends the selected coordinates to your device’s geocoding service. No journal history is sent.",style=MaterialTheme.typography.bodySmall)
                    if(!networkAllowed) Text("Network geocoding is disabled. Coordinates still work offline.",style=MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled=networkAllowed&&!reverseBusy,onClick={reverseRequest=selected to System.nanoTime()}) {Text("Find address for pin")}
                    if(reverseBusy) {LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(onClick={reverseRequest=null}) {Text("Cancel address lookup")}}
                    reverseStatus?.let {Text(it,style=MaterialTheme.typography.bodySmall)}
                    reverseError?.let {Text(it,color=MaterialTheme.colorScheme.error)}
                }
            }
            // The page margin is a stable scroll gesture area outside the interactive map.
            Spacer(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(20.dp).systemBarsPadding()
                .semantics {testTagsAsResourceId=true}.testTag("place-location-scroll-margin"))
            }
        }
    }
    if(searching) PlaceSearchDialog(graph.placeLookup,networkAllowed,{searching=false}) {result->
        clearMetadata();latitude=result.coordinate.latitude;longitude=result.coordinate.longitude
        pinPlaced=true;selectedName=result.name;selectedAddress=result.address;selectedSource="coreLocationGeocode";searching=false
        map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(result.coordinate.latitude,result.coordinate.longitude),15.0))
    }

}
