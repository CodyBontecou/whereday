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
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.stringResource
import java.text.NumberFormat
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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
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
    val context=LocalContext.current
    val graph=remember(context) {AppGraph.from(context)}
    val allowed=remember(graph) {graph.preferences.settings.map {it.networkGeocoding}}
    PlaceLocationPicker(initial,graph.placeLookup,allowed,dismiss,use)
}

@Composable internal fun PlaceLocationPicker(
    initial:Coordinate?,lookup:PlaceLookupService,networkAllowed:Flow<Boolean>,
    dismiss:()->Unit,use:(PlaceLocationSelection)->Unit,mapReady:(MapLibreMap)->Unit={}
) {
    var searching by rememberSaveable {mutableStateOf(false)}
    var selectedSource by rememberSaveable {mutableStateOf("userEntered")}
    var selectedName by rememberSaveable {mutableStateOf<String?>(null)}
    var selectedAddress by rememberSaveable {mutableStateOf<String?>(null)}
    var reverseGeneration by remember {mutableIntStateOf(0)}
    var reverseJob by remember {mutableStateOf<Job?>(null)}
    var closed by remember {mutableStateOf(false)}
    val scope=rememberCoroutineScope()
    var reverseBusy by remember {mutableStateOf(false)}
    var reverseStatus by remember {mutableStateOf<Int?>(null)}
    var reverseError by remember {mutableStateOf<Int?>(null)}
    var pinPlaced by rememberSaveable {mutableStateOf(false)}
    var latitude by rememberSaveable {mutableStateOf(initial?.latitude)}
    var longitude by rememberSaveable {mutableStateOf(initial?.longitude)}
    var cameraLatitude by rememberSaveable {mutableStateOf(initial?.latitude ?: 0.0)}
    var cameraLongitude by rememberSaveable {mutableStateOf(initial?.longitude ?: 0.0)}
    var zoom by rememberSaveable {mutableStateOf(if(initial==null) 1.0 else 15.0)}
    var online by rememberSaveable {mutableStateOf(false)}
    var error by remember {mutableStateOf<Int?>(null)}
    var ready by remember {mutableStateOf(false)}
    val context=LocalContext.current
    val allowed by networkAllowed.collectAsState(initial=false)
    val locale=LocalConfiguration.current.locales[0]
    val number=remember(locale) {NumberFormat.getNumberInstance(locale).apply {minimumFractionDigits=6;maximumFractionDigits=6}}
    val onlineDescription=stringResource(R.string.place_pin_online_description)
    val mapDescription=stringResource(R.string.place_pin_map_description)
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    val mapView=remember(context) {MapLibre.getInstance(context);RendererReadyMapView(context).also {it.onCreate(null)}}
    val alive=remember(mapView) {AtomicBoolean(true)}
    var map by remember {mutableStateOf<MapLibreMap?>(null)}
    val dark=MaterialTheme.colorScheme.background.luminance()<0.5f
    val selected=latitude?.let {lat->longitude?.let {lon->Coordinate(lat,lon)}}
    val latestSelected by rememberUpdatedState(selected)
    fun currentSelection()=latitude?.let {lat->longitude?.let {lon->Coordinate(lat,lon)}}
    fun cancelReverse() {reverseGeneration++;reverseJob?.cancel();reverseJob=null;reverseBusy=false;reverseError=null;reverseStatus=null}
    fun clearMetadata() {cancelReverse();selectedName=null;selectedAddress=null;selectedSource="userEntered"}
    fun close() {closed=true;searching=false;cancelReverse();dismiss()}
    fun findAddress() {
        val point=currentSelection() ?: return
        if(closed || !alive.get() || searching || !allowed) return
        cancelReverse();val generation=reverseGeneration;reverseBusy=true
        fun current()=generation==reverseGeneration && point==currentSelection() && !closed && alive.get() && !searching && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
        reverseJob=scope.launch {
            try {
                if(!networkAllowed.first() || !current()) return@launch
                val result=lookup.reverse(point);ensureActive()
                if(networkAllowed.first() && current()) {
                    selectedName=result?.name;selectedAddress=result?.address;selectedSource=if(result==null) "userEntered" else "coreLocationGeocode";pinPlaced=true
                    if(result==null) reverseError=R.string.place_pin_no_address
                    else reverseStatus=R.string.place_pin_found
                }
            } catch(e:Exception) {
                if(e is CancellationException && e !is TimeoutCancellationException) throw e
                if(current()) reverseError=when(e) {
                    is PlaceLookupDisabled->R.string.place_pin_disabled
                    is TimeoutCancellationException->R.string.place_pin_timeout
                    else->R.string.place_pin_lookup_error
                }
            } finally {if(generation==reverseGeneration) {reverseBusy=false;reverseJob=null}}
        }
    }
    LaunchedEffect(allowed) {if(!allowed) cancelReverse()}
    DisposableEffect(lifecycle) {
        val observer=LifecycleEventObserver {_,event->if(event==Lifecycle.Event.ON_STOP) cancelReverse()}
        lifecycle.addObserver(observer)
        onDispose {lifecycle.removeObserver(observer);reverseJob?.cancel()}
    }
    fun drawPin(target:MapLibreMap?) {
        val point=latestSelected
        target?.style?.getSourceAs<GeoJsonSource>("selected-place")?.setGeoJson(
            if(point==null) """{"type":"FeatureCollection","features":[]}"""
            else """{"type":"Feature","properties":{},"geometry":{"type":"Point","coordinates":[${point.longitude},${point.latitude}]}}""")
    }
    DisposableEffect(mapView,lifecycle) {
        var started=false;var resumed=false;var destroyed=false
        val rendered=MapView.OnDidFinishRenderingFrameListener {_,_,_->
            if(alive.get() && map!=null && lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) mapView.isEnabled=true
        }
        mapView.addOnDidFinishRenderingFrameListener(rendered)
        fun sync() {
            val start=lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)
            val resume=lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)
            if(start&&!started) {mapView.onStart();started=true}
            if(resume&&!resumed) {mapView.onResume();resumed=true;map?.triggerRepaint()}
            if(!resume) mapView.isEnabled=false
            if(!resume&&resumed) {mapView.onPause();resumed=false}
            if(!start&&started) {mapView.onStop();started=false}
        }
        val observer=LifecycleEventObserver {_,event->
            sync();if(event==Lifecycle.Event.ON_DESTROY&&!destroyed) {mapView.isEnabled=false;alive.set(false);mapView.onDestroy();destroyed=true}
        }
        lifecycle.addObserver(observer);sync()
        onDispose {
            mapView.isEnabled=false;alive.set(false)
            lifecycle.removeObserver(observer);mapView.removeOnDidFinishRenderingFrameListener(rendered)
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
    WorkspaceDialog(fillRegion=true,onDismissRequest={close()},properties=DialogProperties(usePlatformDefaultWidth=false)) {
        BackHandler(onBack={close()})
        Surface(Modifier.fillMaxSize()) {
            Box(Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding().semantics {testTagsAsResourceId=true}.testTag("place-location-content").padding(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    TextButton(modifier=Modifier.weight(1f).testTag("place-pin-close"),onClick={close()}) {Text(stringResource(R.string.place_pin_cancel))}
                    TextButton(modifier=Modifier.weight(1f).testTag("place-pin-use"),enabled=selected!=null,onClick={
                        if(!closed && alive.get() && !searching && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) currentSelection()?.let {
                            val choice=PlaceLocationSelection(it,pinPlaced,selectedName,selectedAddress,selectedSource)
                            closed=true;cancelReverse();use(choice)
                        }
                    }) {Text(stringResource(R.string.place_pin_use))}
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(12.dp)) {
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.place_pin_title),Modifier.weight(1f),style=MaterialTheme.typography.headlineSmall)
                    TextButton(onClick={cancelReverse();searching=true},modifier=Modifier.testTag("place-pin-search")) {Text(stringResource(R.string.place_pin_search))}
                }
                Text(stringResource(R.string.place_pin_instructions))
                Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text(stringResource(R.string.place_pin_online),Modifier.weight(1f).padding(top=12.dp))
                    Switch(online,{online=it},Modifier.testTag("place-pin-online").semantics {contentDescription=onlineDescription})
                }
                Text(stringResource(R.string.place_pin_tiles_disclosure),style=MaterialTheme.typography.bodySmall)
                AndroidView(factory={mapView.apply {
                    addOnDidFailLoadingMapListener {error=R.string.place_pin_tile_error}
                    getMapAsync {target->
                        if(!alive.get() || closed) return@getMapAsync
                        target.uiSettings.isAttributionEnabled=true
                        target.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(cameraLatitude,cameraLongitude),zoom))
                        target.addOnCameraIdleListener {
                            target.cameraPosition.target?.wrap()?.let {cameraLatitude=it.latitude;cameraLongitude=it.longitude}
                            zoom=target.cameraPosition.zoom
                        }
                        target.addOnMapClickListener {point->
                            if(!alive.get() || !mapView.isEnabled || map!==target) return@addOnMapClickListener false
                            val wrapped=point.wrap();clearMetadata();latitude=wrapped.latitude;longitude=wrapped.longitude;pinPlaced=true;true
                        }
                        map=target;ready=true;mapReady(target)
                    }
                }},modifier=Modifier.fillMaxWidth().height(280.dp).semantics {contentDescription=mapDescription})
                if(!online) Text(stringResource(R.string.place_pin_offline),style=MaterialTheme.typography.bodySmall)
                error?.let {Text(stringResource(it),color=MaterialTheme.colorScheme.error)}
                OutlinedButton(enabled=ready,modifier=Modifier.testTag("place-pin-drop"),onClick={
                    map?.cameraPosition?.target?.wrap()?.let {clearMetadata();latitude=it.latitude;longitude=it.longitude;pinPlaced=true}
                }) {Text(stringResource(R.string.place_pin_drop))}
                Text(stringResource(R.string.place_pin_center,number.format(cameraLatitude),number.format(cameraLongitude)),style=MaterialTheme.typography.bodySmall)
                if(selected==null) Text(stringResource(R.string.place_pin_empty))
                else {
                    Text(stringResource(R.string.place_pin_selected),style=MaterialTheme.typography.titleMedium)
                    selectedName?.let {Text(it)}
                    selectedAddress?.let {Text(it)}
                    Text(stringResource(R.string.place_pin_latitude,number.format(selected.latitude)))
                    Text(stringResource(R.string.place_pin_longitude,number.format(selected.longitude)))
                    Text(stringResource(R.string.place_pin_draft),style=MaterialTheme.typography.bodySmall)
                    Text(stringResource(R.string.place_pin_lookup_disclosure),style=MaterialTheme.typography.bodySmall)
                    if(!allowed) Text(stringResource(R.string.place_pin_disabled),style=MaterialTheme.typography.bodySmall)
                    OutlinedButton(enabled=allowed&&!reverseBusy,onClick={findAddress()},modifier=Modifier.testTag("place-pin-find")) {Text(stringResource(R.string.place_pin_find))}
                    if(reverseBusy) {LinearProgressIndicator(Modifier.fillMaxWidth());TextButton(onClick={cancelReverse()},modifier=Modifier.testTag("place-pin-stop")) {Text(stringResource(R.string.place_pin_stop))}}
                    reverseStatus?.let {Text(stringResource(it),style=MaterialTheme.typography.bodySmall)}
                    reverseError?.let {Text(stringResource(it),color=MaterialTheme.colorScheme.error)}
                }
            }
            }
            // The page margin is a stable scroll gesture area outside the interactive map.
            Spacer(Modifier.align(Alignment.CenterStart).fillMaxHeight().width(20.dp).systemBarsPadding()
                .semantics {testTagsAsResourceId=true}.testTag("place-location-scroll-margin"))
            }
        }
    }
    if(searching) PlaceSearchDialog(lookup,networkAllowed,{searching=false}) {result->
        if(closed || !lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) return@PlaceSearchDialog
        clearMetadata();latitude=result.coordinate.latitude;longitude=result.coordinate.longitude
        pinPlaced=true;selectedName=result.name;selectedAddress=result.address;selectedSource="coreLocationGeocode";searching=false
        map?.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(result.coordinate.latitude,result.coordinate.longitude),15.0))
    }

}
