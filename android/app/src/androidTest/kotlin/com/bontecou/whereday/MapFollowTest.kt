package com.bontecou.whereday

import android.view.View
import android.view.ViewGroup
import android.os.SystemClock
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.maplibre.android.maps.*
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReference

class MapFollowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val events=MutableSharedFlow<MapLocationFix>(extraBufferCapacity=8)
    private val active=AtomicInteger();private val starts=AtomicInteger()
    private val source=MapLocationSource {flow {starts.incrementAndGet();active.incrementAndGet();try {emitAll(events)} finally {active.decrementAndGet()}}}
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    private fun content(permitted:Boolean=true,fontScale:Float=1f) {
        compose.setContent {CompositionLocalProvider(LocalDensity provides Density(LocalDensity.current.density,fontScale)) {MaterialTheme {JournalMap(listOf(TrackPoint("synthetic",Coordinate(48.8584,2.2945),Instant.EPOCH,5.0)),emptyList(),follow=rememberMapFollow(source) {permitted})}}}
    }
    private fun View.mapView():MapView?=if(this is MapView) this else if(this is ViewGroup) (0 until childCount).firstNotNullOfOrNull {getChildAt(it).mapView()} else null
    private fun map():MapLibreMap {
        val result=AtomicReference<MapLibreMap>()
        val view=compose.runOnIdle {compose.activity.window.decorView.mapView()!!}
        compose.runOnIdle {view.getMapAsync {result.set(it)}}
        compose.waitUntil(10000) {compose.runOnIdle {result.get()?.style?.isFullyLoaded==true}}
        compose.awaitNativeMapFrame(view,result.get())
        return result.get()
    }
    @Test fun followCentersTheNativeCameraAndFitReleasesCameraWhileHideStopsLocation() {
        content();val map=map();assertEquals(0,starts.get())
        compose.onNodeWithTag("map-follow-location").performClick()
        compose.waitUntil(5000) {active.get()==1}
        assertTrue(events.tryEmit(MapLocationFix(Coordinate(18.0,-66.0),20000.0,SystemClock.elapsedRealtime())))
        compose.waitUntil(5000) {compose.runOnIdle {kotlin.math.abs(map.cameraPosition.target!!.latitude-18.0)<0.0001}}
        val view=compose.runOnIdle {compose.activity.window.decorView.mapView()!!}
        fun markers()=compose.runOnIdle {map.queryRenderedFeatures(android.graphics.RectF(0f,0f,view.width.toFloat(),view.height.toFloat()),"journal-current-location").size}
        compose.waitUntil(5000) {markers()==1}
        compose.onNodeWithTag("map-location-accuracy").assertTextContains("20000",substring=true)
        assertTrue(compose.runOnIdle {map.cameraPosition.zoom<10.0})
        compose.onNodeWithText("Fit route").performClick()
        assertEquals(1,active.get())
        compose.waitUntil(5000) {compose.runOnIdle {kotlin.math.abs(map.cameraPosition.target!!.latitude-48.8584)<0.0001}}
        // Re-follow the same fix, proving Fit released only the camera and not its marker.
        compose.onNodeWithTag("map-follow-location").performClick()
        compose.waitUntil(5000) {compose.runOnIdle {kotlin.math.abs(map.cameraPosition.target!!.latitude-18.0)<0.0001}}
        compose.waitUntil(5000) {markers()==1}
        compose.onNodeWithTag("map-hide-location").performClick()
        compose.waitUntil(5000) {active.get()==0}
        compose.onNodeWithTag("map-location-accuracy").assertDoesNotExist()
        compose.waitUntil(5000) {markers()==0}
        compose.onNodeWithText("Follow my location").assertExists()
    }
    @Test fun deniedAccessExplanationAndCancelNeverStartLocationCollection() {
        content(false,1.8f)
        compose.onNodeWithTag("map-follow-location").performClick()
        compose.onNodeWithText("Follow your location?").assertIsDisplayed()
        compose.onNodeWithText("Not now").assertIsDisplayed().performClick()
        assertEquals(0,starts.get());assertEquals(0,active.get())
        compose.onNodeWithText("Follow my location").assertExists()
    }
    @Test fun aCoveringDialogSuspendsUpdatesAndClosingItRestartsOnlyTheRequestedFollow() {
        content();map()
        compose.onNodeWithTag("map-follow-location").performClick();compose.waitUntil(5000) {active.get()==1}
        compose.onNodeWithText("Map layers").performClick();compose.waitUntil(5000) {active.get()==0}
        compose.onNodeWithText("Done map layers").performClick();compose.waitUntil(5000) {active.get()==1}
        compose.onNodeWithTag("map-hide-location").performClick();compose.waitUntil(5000) {active.get()==0}
    }
    @Test fun panningTheNativeMapReleasesFollowAndLeavesLocationVisible() {
        content();map()
        compose.onNodeWithTag("map-follow-location").performClick();compose.waitUntil(5000) {active.get()==1}
        assertTrue(events.tryEmit(MapLocationFix(Coordinate(18.0,-66.0),5.0,SystemClock.elapsedRealtime())))
        compose.onNodeWithTag("journal-native-map").performTouchInput {swipeLeft()}
        compose.onNodeWithText("Follow my location").assertExists()
        assertEquals(1,active.get())
        compose.onNodeWithTag("map-hide-location").performClick();compose.waitUntil(5000) {active.get()==0}
    }
    @Test fun backgroundingStopsCollectionAndReturningResumesTheExistingChoice() {
        content();map()
        compose.onNodeWithTag("map-follow-location").performClick();compose.waitUntil(5000) {active.get()==1}
        compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
        compose.waitUntil(5000) {active.get()==0}
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitUntil(5000) {active.get()==1}
        compose.onNodeWithTag("map-hide-location").performClick();compose.waitUntil(5000) {active.get()==0}
    }
}
