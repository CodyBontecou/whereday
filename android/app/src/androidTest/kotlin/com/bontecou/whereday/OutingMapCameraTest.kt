package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.compose.ui.test.junit4.StateRestorationTester
import com.bontecou.whereday.data.PhotoAreaSummary
import com.bontecou.whereday.domain.*
import org.junit.*
import org.junit.Assert.*
import org.maplibre.android.maps.*
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.camera.*
import java.time.Instant
import java.util.UUID
import java.util.concurrent.atomic.AtomicReference

class OutingMapCameraTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val points=listOf(TrackPoint("fit-a",Coordinate(18.0,-66.0),Instant.EPOCH,5.0),TrackPoint("fit-b",Coordinate(18.01,-66.01),Instant.EPOCH.plusSeconds(1),5.0))
    private val farPose=CameraPosition.Builder().target(LatLng(40.0,60.0)).zoom(8.0).bearing(25.0).tilt(30.0).build()
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    private fun native():Pair<MapView,MapLibreMap> {
        val view=compose.runOnIdle {checkNotNull(findNativeMapView())}
        val map=AtomicReference<MapLibreMap>();compose.runOnUiThread {view.getMapAsync {map.set(it)}}
        compose.waitUntil(10_000) {compose.runOnIdle {map.get()?.style?.isFullyLoaded==true}}
        compose.awaitNativeMapFrame(view,map.get());return view to map.get()
    }
    private fun pan(map:MapLibreMap) {
        compose.runOnUiThread {map.moveCamera(CameraUpdateFactory.newCameraPosition(farPose))}
        compose.waitForIdle()
    }
    private fun assertFar(map:MapLibreMap)=compose.runOnIdle {
        val p=map.cameraPosition
        assertEquals(40.0,p.target!!.latitude,0.00001);assertEquals(60.0,p.target!!.longitude,0.00001)
        assertEquals(8.0,p.zoom,0.00001);assertEquals(25.0,p.bearing,0.00001);assertEquals(30.0,p.tilt,0.00001)
    }
    private fun fitted(view:MapView,map:MapLibreMap,coordinates:List<Coordinate>) {
        compose.waitUntil(10_000) {compose.runOnIdle {
            map.cameraPosition.bearing==0.0 && map.cameraPosition.tilt==0.0 && coordinates.all {
                val p=map.projection.toScreenLocation(LatLng(it.latitude,it.longitude))
                p.x>=1 && p.y>=1 && p.x<view.width-1 && p.y<view.height-1
            }
        }}
    }
    private fun layer(ready:Boolean):PhotoMapLayer {
        val marker=PhotoMapMarker("focus-photo",PhotoAreaSummary("photo-place",1,1,Coordinate(18.5,-65.5),1))
        return PhotoMapLayer(true,false,if(ready) listOf(marker) else emptyList(),emptyMap(),{},{},{},dots=true,fitReady=ready)
    }
    @Test fun consumedCameraRequestRestoresManualPoseAndFreshSameScopeRequestRefits() {
        var request by mutableStateOf(UUID.randomUUID().toString())
        var outliers by mutableStateOf(false)
        val tester=StateRestorationTester(compose)
        tester.setContent {MaterialTheme {JournalMap(points,emptyList(),showOutliers=outliers,outingFocusRequest=request)}}
        val (view,map)=native();fitted(view,map,points.map {it.coordinate})
        pan(map);compose.awaitNativeMapFrame(view,map);compose.waitForIdle();assertFar(map)
        tester.emulateSavedInstanceStateRestore()
        val (restoredView,restored)=native();assertFar(restored)
        compose.runOnIdle {request=UUID.randomUUID().toString()}
        fitted(restoredView,restored,points.map {it.coordinate})
        pan(restored);compose.awaitNativeMapFrame(restoredView,restored)
        compose.runOnIdle {outliers=true};compose.waitForIdle();assertFar(restored)
    }
    @Test fun pendingFocusWaitsForPhotoMetadataAndDoesNotReplayAfterLaterValidation() {
        val request=UUID.randomUUID().toString();var ready by mutableStateOf(false)
        compose.setContent {MaterialTheme {JournalMap(points,emptyList(),outingFocusRequest=request,photoLayer=layer(ready))}}
        val (view,map)=native();pan(map);compose.awaitNativeMapFrame(view,map);assertFar(map)
        compose.runOnIdle {ready=true}
        fitted(view,map,points.map {it.coordinate}+Coordinate(18.5,-65.5))
        pan(map);compose.awaitNativeMapFrame(view,map)
        compose.runOnIdle {ready=false};compose.waitForIdle()
        compose.runOnIdle {ready=true};compose.waitForIdle();assertFar(map)
    }
    @Test fun aUserGestureCancelsAnUnconsumedPhotoDependentFocus() {
        var request by mutableStateOf<String?>(UUID.randomUUID().toString());var ready by mutableStateOf(false)
        compose.setContent {MaterialTheme {JournalMap(points,emptyList(),outingFocusRequest=request,onOutingFocusCleared={request=null},photoLayer=layer(ready))}}
        val (view,map)=native();pan(map);compose.awaitNativeMapFrame(view,map)
        assertTrue(compose.runOnIdle {compose.activity.hasWindowFocus() && view.isShown})
        androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isAssignableFrom(MapView::class.java)).perform(androidx.test.espresso.action.ViewActions.swipeLeft())
        compose.waitUntil(10_000) {request==null}
        pan(map);compose.awaitNativeMapFrame(view,map)
        compose.runOnIdle {ready=true};compose.waitForIdle();assertFar(map)
    }
}
