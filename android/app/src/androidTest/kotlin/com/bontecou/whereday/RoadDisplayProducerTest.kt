package com.bontecou.whereday

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.AppSettings
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.maplibre.android.maps.*
import java.time.Instant
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

/** The real shared producer and native map, with synthetic directions only; no app graph/provider override. */
class RoadDisplayProducerTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val points=listOf(TrackPoint("a",Coordinate(48.8584,2.2945),Instant.EPOCH,5.0),
        TrackPoint("b",Coordinate(48.8584,2.3045),Instant.EPOCH.plusSeconds(600),5.0))
    private val calls=AtomicInteger()
    private val settings=MutableStateFlow(AppSettings(roadDisplayEnabled=true,roadAccessRevision="a"))
    private val journal=MutableStateFlow(JournalClearState(generation="a"))
    private lateinit var controller:RoadDisplayController
    private val release=CompletableDeferred<Unit>()
    private fun response(a:Coordinate,b:Coordinate)=RoadDirectionsResult(a.distanceTo(b)*1.3,listOf(a,Coordinate(a.latitude+.003,(a.longitude+b.longitude)/2),b))
    private fun setup(blockFirst:Boolean=false) {
        check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")
        controller=RoadDisplayController(RoadDirections {a,b,_->
            if(calls.incrementAndGet()==1 && blockFirst) withContext(NonCancellable) {release.await()}
            response(a,b)
        },settings,{settings.value},{settings.value=settings.value.copy(roadDisplayEnabled=false)},journal,minimumRequestIntervalMillis=0)
    }
    @After fun close()=runBlocking {release.complete(Unit);if(::controller.isInitialized) controller.closeForTest()}
    // Production close is module-internal; clear joins all workers and the test owns no journal data.
    private suspend fun RoadDisplayController.closeForTest() {clearAndJoin()}
    private fun View.mapView():MapView?=if(this is MapView) this else if(this is ViewGroup) (0 until childCount).firstNotNullOfOrNull {getChildAt(it).mapView()} else null
    private fun map():Pair<MapLibreMap,MapView> {
        val reference=AtomicReference<MapLibreMap>()
        val view=compose.runOnIdle {checkNotNull(compose.activity.window.decorView.mapView())}
        compose.runOnIdle {view.getMapAsync {reference.set(it)}}
        compose.waitUntil(10000) {compose.runOnIdle {reference.get()?.style?.isFullyLoaded==true}}
        compose.awaitNativeMapFrame(view,reference.get())
        return reference.get() to view
    }
    private fun roads(map:Pair<MapLibreMap,MapView>)=compose.runOnIdle {map.first.queryRenderedFeatures(android.graphics.RectF(0f,0f,map.second.width.toFloat(),map.second.height.toFloat()),"journal-road-path").size}
    @Test fun changedSourceAndOptOutCannotLeavePreparedRoadsOnTheMap() {
        setup();var source by mutableStateOf(points)
        compose.setContent {MaterialTheme {Column(Modifier.fillMaxSize()) {JournalMap(source,emptyList(),roadController=controller)}}}
        val map=map();compose.waitUntil(10000) {roads(map)>0};assertEquals(1,calls.get())
        compose.runOnIdle {source=points.map {it.copy(coordinate=it.coordinate.copy(latitude=it.coordinate.latitude+.01))}}
        compose.waitUntil(5000) {calls.get()==2}
        // Source replacement retains the user's viewport. Explicit Fit brings the new route into view.
        compose.onNodeWithText("Fit route").performClick()
        compose.waitUntil(10000) {calls.get()==2 && roads(map)>0}
        controller.invalidatePolicy("off");settings.value=settings.value.copy(roadDisplayEnabled=false,roadAccessRevision="off")
        compose.waitUntil(10000) {roads(map)==0 && controller.state.value.foregroundSessions==0}
        compose.onNodeWithTag("road-estimate-caption").assertDoesNotExist();assertEquals(2,calls.get())
    }
    @Test fun coveredSourceReleasesAdmissionAndLateWorkerCannotReplaceItsSuccessor() {
        setup(true);var visible by mutableStateOf(true)
        compose.setContent {MaterialTheme {Column(Modifier.fillMaxSize()) {JournalMap(points,emptyList(),roadController=controller,roadVisible=visible)}}}
        val map=map();compose.waitUntil(5000) {calls.get()==1}
        compose.runOnIdle {visible=false};compose.waitUntil(5000) {controller.state.value.foregroundSessions==0}
        assertEquals(0,roads(map));compose.runOnIdle {visible=true}
        compose.waitUntil(5000) {controller.state.value.foregroundSessions==1}
        assertEquals(1,calls.get());release.complete(Unit)
        compose.waitUntil(10000) {calls.get()==2 && roads(map)>0}
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.waitUntil(5000) {controller.state.value.foregroundSessions==0}
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        try {compose.waitUntil(10000) {calls.get()==3 && roads(map)>0}}
        catch(error:ComposeTimeoutException) {
            val nativeState=runCatching {compose.runOnIdle {
                val rendered=map.first.queryRenderedFeatures(android.graphics.RectF(0f,0f,map.second.width.toFloat(),map.second.height.toFloat()),"journal-road-path").size
                "renderedRoads=$rendered, styleLoaded=${map.first.style?.isFullyLoaded}, "+
                    "attached=${map.second.isAttachedToWindow}, shown=${map.second.isShown}, "+
                    "size=${map.second.width}x${map.second.height}, "+
                    "sameMapView=${compose.activity.window.decorView.mapView()===map.second}, "+
                    "windowFocus=${compose.activity.hasWindowFocus()}"
            }}.getOrElse {"Native diagnostic unavailable: ${it.javaClass.simpleName}"}
            val semantics=runCatching {compose.onAllNodes(isRoot(),useUnmergedTree=true).printToString()}
                .getOrElse {"Semantics unavailable: ${it.javaClass.simpleName}"}
            throw AssertionError("Road resume timed out: calls=${calls.get()}, controller=${controller.state.value}, "+
                "lifecycle=${compose.activity.lifecycle.currentState}, $nativeState. Current semantics:\n"+
                semantics,error)
        }
    }
    @Test fun replayFramesReusePreparedSourceAndRevealRoadOnlyAtItsRecordedEndpoint() {
        setup();var frame by mutableStateOf(ReplayFrame(0,2,points[0],points.take(1),Duration.ZERO,Duration.ofSeconds(600),0.0,700.0))
        compose.setContent {MaterialTheme {Column(Modifier.fillMaxSize()) {JournalMap(points,emptyList(),replay=frame,roadController=controller)}}}
        val map=map();compose.waitUntil(5000) {calls.get()==1}
        compose.waitUntil(5000) {controller.state.value.foregroundSessions==1};assertEquals(0,roads(map))
        compose.runOnIdle {frame=ReplayFrame(1,2,points[1],points,Duration.ofSeconds(600),Duration.ofSeconds(600),700.0,700.0)}
        compose.waitUntil(10000) {roads(map)>0};assertEquals(1,calls.get())
        compose.runOnIdle {frame=frame.copy(index=0,current=points[0],visiblePoints=points.take(1))}
        compose.waitUntil(5000) {roads(map)==0};assertEquals(1,calls.get())
    }
    @Test fun outlierFallbackWindowFocusAndPathChoiceControlActualPreparation() {
        setup();var fallback by mutableStateOf(false);var overlay by mutableStateOf(false)
        val outliers=points.map {it.copy(outlier=true)}
        compose.setContent {MaterialTheme {
            Column(Modifier.fillMaxSize()) {JournalMap(outliers,emptyList(),allOutlierFallback=fallback,roadController=controller)}
            if(overlay) androidx.compose.material3.AlertDialog(onDismissRequest={overlay=false},title={androidx.compose.material3.Text("Synthetic overlay")},
                confirmButton={androidx.compose.material3.TextButton(onClick={overlay=false}) {androidx.compose.material3.Text("Dismiss overlay")}})
        }}
        val map=map();compose.waitUntil(5000) {!controller.state.value.loading};assertEquals(0,calls.get())
        compose.runOnIdle {fallback=true};compose.waitUntil(10000) {calls.get()==1 && roads(map)>0}
        compose.runOnIdle {overlay=true};compose.waitUntil(5000) {controller.state.value.foregroundSessions==0}
        compose.onNodeWithText("Dismiss overlay").performClick()
        compose.waitUntil(10000) {calls.get()==2 && roads(map)>0}
        compose.onNode(hasText("Map layers") and hasClickAction()).performClick()
        compose.onNodeWithTag("map-layer-path").performClick()
        compose.onNodeWithText("Done map layers").performClick()
        compose.waitUntil(5000) {controller.state.value.foregroundSessions==0 && roads(map)==0};assertEquals(2,calls.get())
        compose.onNode(hasText("Map layers") and hasClickAction()).performClick()
        compose.onNodeWithTag("map-layer-path").performClick()
        compose.onNodeWithText("Done map layers").performClick()
        compose.waitUntil(10000) {calls.get()==3 && roads(map)>0}
    }

}
