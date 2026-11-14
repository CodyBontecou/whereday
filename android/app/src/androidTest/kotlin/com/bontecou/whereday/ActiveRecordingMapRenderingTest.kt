package com.bontecou.whereday

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import androidx.lifecycle.Lifecycle
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.*
import org.junit.Assert.*
import org.maplibre.android.maps.*
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicInteger

class ActiveRecordingMapRenderingTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    private fun View.mapView():MapView?=if(this is MapView) this else if(this is ViewGroup) (0 until childCount).firstNotNullOfOrNull {getChildAt(it).mapView()} else null
    private fun map():Pair<MapLibreMap,MapView> {
        val value=AtomicReference<MapLibreMap>()
        val view=compose.runOnIdle {compose.activity.window.decorView.mapView()!!}
        compose.runOnIdle {view.getMapAsync {value.set(it)}}
        compose.waitUntil(10000) {compose.runOnIdle {value.get()?.style?.isFullyLoaded==true}}
        compose.awaitNativeMapFrame(view,value.get())
        return value.get() to view
    }
    private fun rendered(map:MapLibreMap,view:MapView,layer:String)=compose.runOnIdle {
        map.queryRenderedFeatures(android.graphics.RectF(0f,0f,view.width.toFloat(),view.height.toFloat()),layer).size
    }
    private fun capture(name:String) {
        if(InstrumentationRegistry.getArguments().getString("captureMapFit")!="true") return
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-map-fit-$name-20261029.png")
            put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png")
            put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
        }
        val uri=checkNotNull(context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        context.contentResolver.openOutputStream(uri)!!.use {stream->
            val bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
            try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,stream))} finally {bitmap.recycle()}
        }
    }
    @Test fun livePathFitsIndependentlyOfHistoricalPointsAndOrdinaryLayerChoicesThenClears() {
        val at=Instant.parse("2026-10-29T12:00:00Z")
        val history=TrackPoint("history",Coordinate(0.0,0.0),at,5.0)
        val points=listOf(TrackPoint("active-a",Coordinate(48.8584,2.2945),at,5.0),TrackPoint("active-b",Coordinate(48.8586,2.2947),at.plusSeconds(10),5.0))
        val snapshot=ActiveRecordingMap(RecordingSession("active",at),points,2,RouteEndpoints.from(points),listOf(points))
        var enabled by mutableStateOf(true)
        compose.setContent {MaterialTheme {JournalMap(listOf(history),emptyList(),activeRecording=ActiveRecordingMapUi(enabled,snapshot,ActiveRecordingMapStatus.ready,{enabled=it},{}))}}
        val (map,view)=map()
        compose.onNodeWithTag("fit-active-recording").assertIsEnabled().performClick()
        compose.waitUntil(5000) {compose.runOnIdle {kotlin.math.abs(map.cameraPosition.target!!.latitude-48.8585)<.001}}
        compose.waitUntil(5000) {rendered(map,view,"journal-active-path")>0 && rendered(map,view,"journal-active-position")==1 && rendered(map,view,"journal-active-start-flag")==1}
        compose.onNodeWithTag("active-recording-map-summary").assertTextEquals("Current recording path: 2 of 2 points shown.")
        compose.onNodeWithText("Map layers").performClick()
        compose.onNodeWithTag("map-layer-path").performClick()
        compose.onNodeWithTag("map-layer-endpoints").performClick()
        compose.onNodeWithText("Done map layers").performClick()
        compose.waitUntil(5000) {rendered(map,view,"journal-active-path")>0 && rendered(map,view,"journal-active-position")==1}
        compose.onNodeWithText("Map layers").performClick()
        compose.onNodeWithTag("map-layer-active-recording").performScrollTo().performClick()
        compose.onNodeWithText("Done map layers").performClick()
        compose.waitUntil(5000) {rendered(map,view,"journal-active-path")==0 && rendered(map,view,"journal-active-position")==0 && rendered(map,view,"journal-active-start-flag")==0}
        compose.onNodeWithTag("fit-active-recording").assertDoesNotExist()
    }

    @Test fun realRepositoryLayerDefaultsOffAndDisappearsWhenInactiveBackgroundedOrStopped()=runBlocking {
        val graph=AppGraph.from(InstrumentationRegistry.getInstrumentation().targetContext)
        graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
        try {
            val at=Instant.parse("2026-10-29T12:00:00Z")
            val points=listOf(TrackPoint("live-a",Coordinate(48.8584,2.2945),at,5.0),TrackPoint("live-b",Coordinate(48.8586,2.2947),at.plusSeconds(10),5.0),TrackPoint("outlier",Coordinate(48.9,2.3),at.plusSeconds(20),5.0,outlier=true))
            graph.database.writeTransaction {
                graph.history.dao.put(SessionRow(RecordingSession("active",at)))
                graph.history.dao.put(RecordingRow(requested=true,sessionId="active"))
                graph.history.dao.putPoints(points.map {PointRow(it,"active")})
            }
            var active by mutableStateOf(true)
            var outliers by mutableStateOf(false)
            val readers=AtomicInteger()
            val source:(Boolean)->Flow<ActiveRecordingMap?> = {include->flow {
                readers.incrementAndGet()
                try {emitAll(graph.history.observeActiveRecordingMap(include))} finally {readers.decrementAndGet()}
            }}
            compose.setContent {MaterialTheme {JournalMap(emptyList(),emptyList(),activeRecording=rememberActiveRecordingMap(graph,active,outliers,source=source))}}
            val (map,view)=map()
            compose.onNodeWithTag("active-recording-map-summary").assertDoesNotExist()
            assertEquals(0,readers.get())
            compose.onNodeWithText("Map layers").performClick()
            compose.onNodeWithTag("map-layer-active-recording").performScrollTo().performClick()
            compose.onNodeWithText("Done map layers").performClick()
            compose.waitUntil(5000) {runCatching {compose.onNodeWithTag("active-recording-map-summary").assertExists()}.isSuccess}
            compose.onNodeWithTag("fit-active-recording").performClick()
            compose.waitUntil(5000) {rendered(map,view,"journal-active-position")==1}
            compose.waitUntil(5000) {readers.get()==1}
            compose.runOnIdle {outliers=true}
            compose.waitUntil(5000) {runCatching {compose.onNodeWithTag("active-recording-map-summary").assertTextEquals("Current recording path: 3 of 3 points shown.")}.isSuccess}
            compose.runOnIdle {outliers=false}
            compose.waitUntil(5000) {runCatching {compose.onNodeWithTag("active-recording-map-summary").assertTextEquals("Current recording path: 2 of 2 points shown.")}.isSuccess}
            compose.runOnIdle {active=false}
            compose.onNodeWithText("Start recording to show its path. This layer does not start recording.").assertExists()
            compose.waitUntil(5000) {rendered(map,view,"journal-active-position")==0}
            compose.waitUntil(5000) {readers.get()==0}
            compose.runOnIdle {active=true}
            compose.waitUntil(5000) {rendered(map,view,"journal-active-position")==1}
            compose.activityRule.scenario.moveToState(Lifecycle.State.STARTED)
            // A hidden Activity may have no Compose hierarchy. Assert the actual subscription.
            compose.waitUntil(5000) {readers.get()==0}
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(5000) {readers.get()==1}
            compose.waitUntil(5000) {rendered(map,view,"journal-active-position")==1}
            graph.history.stop()
            compose.waitUntil(5000) {rendered(map,view,"journal-active-position")==0}
        } finally {graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}}
        Unit
    }

    @Test fun observationFailureClearsTheLayerAndExplicitRetryLoadsIt() {
        val graph=AppGraph.from(InstrumentationRegistry.getInstrumentation().targetContext)
        val at=Instant.parse("2026-10-29T12:00:00Z")
        val points=listOf(TrackPoint("retry-a",Coordinate(48.8584,2.2945),at,5.0),TrackPoint("retry-b",Coordinate(48.8586,2.2947),at.plusSeconds(10),5.0))
        val snapshot=ActiveRecordingMap(RecordingSession("retry",at),points,2,RouteEndpoints.from(points),listOf(points))
        val attempts=AtomicInteger()
        val source:(Boolean)->Flow<ActiveRecordingMap?> = {flow {
            if(attempts.incrementAndGet()==1) throw java.io.IOException("Synthetic observation failure")
            emit(snapshot);awaitCancellation()
        }}
        compose.setContent {MaterialTheme {JournalMap(emptyList(),emptyList(),activeRecording=rememberActiveRecordingMap(graph,true,false,source=source))}}
        val (map,view)=map()
        compose.onNodeWithText("Map layers").performClick()
        compose.onNodeWithTag("map-layer-active-recording").performScrollTo().performClick()
        compose.onNodeWithText("Done map layers").performClick()
        compose.waitUntil(5000) {runCatching {compose.onNodeWithText("Current recording path could not load.").assertExists()}.isSuccess}
        compose.onNodeWithTag("fit-active-recording").assertIsNotEnabled()
        assertEquals(0,rendered(map,view,"journal-active-position"))
        compose.onNodeWithText("Retry current recording path").performClick()
        compose.waitUntil(5000) {runCatching {compose.onNodeWithTag("active-recording-map-summary").assertExists()}.isSuccess}
        compose.onNodeWithTag("fit-active-recording").performClick()
        compose.waitUntil(5000) {rendered(map,view,"journal-active-position")==1}
        assertEquals(2,attempts.get())
    }

    @Test fun routeFitKeepsDateLinePointsVisibleWithoutAWorldwideCamera() {
        val at=Instant.parse("2026-10-29T12:00:00Z")
        val points=(0 until 2500).map {i->TrackPoint("date-line-$i",Coordinate(10.0+i/25000.0,if(i%2==0) 179.8 else -179.7),at.plusSeconds(i.toLong()),5.0)}
        compose.setContent {MaterialTheme {JournalMap(points,emptyList())}}
        val (map,view)=map()
        compose.onNodeWithText("Fit route").performClick()
        compose.waitUntil(5000) {compose.runOnIdle {kotlin.math.abs(map.cameraPosition.target!!.longitude)>179.0 && map.cameraPosition.zoom>7.0}}
        compose.runOnIdle {
            for(point in listOf(points.first(),points.last())) {
                val pixel=map.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(point.coordinate.latitude,point.coordinate.longitude))
                assertTrue("Point must be inside the fitted map: $pixel",pixel.x in 0f..view.width.toFloat() && pixel.y in 0f..view.height.toFloat())
            }
        }
        compose.waitUntil(5000) {rendered(map,view,"journal-point")>0}
        compose.waitUntil(5000) {compose.runOnIdle {
            val middle=map.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(10.05,-179.95))
            map.queryRenderedFeatures(android.graphics.RectF(middle.x-4,middle.y-4,middle.x+4,middle.y+4),"journal-path").isNotEmpty()
        }}
        capture("date-line")
    }

    @Test fun activeFitHandlesEquivalentDateLineFixesAndPolarLimitsWithFiniteCameras() {
        val at=Instant.parse("2026-10-29T12:00:00Z")
        fun snapshot(coordinates:List<Coordinate>):ActiveRecordingMap {
            val points=coordinates.mapIndexed {i,c->TrackPoint("singular-$i",c,at.plusSeconds(i.toLong()),5.0)}
            return ActiveRecordingMap(RecordingSession("singular",at),points,points.size.toLong(),RouteEndpoints.from(points),listOf(points))
        }
        var current by mutableStateOf(snapshot(listOf(Coordinate(0.0,180.0),Coordinate(0.0,-180.0))))
        compose.setContent {MaterialTheme {JournalMap(emptyList(),emptyList(),activeRecording=ActiveRecordingMapUi(true,current,ActiveRecordingMapStatus.ready,{},{}))}}
        val (map,view)=map()
        compose.onNodeWithTag("fit-active-recording").performClick()
        compose.waitUntil(5000) {compose.runOnIdle {kotlin.math.abs(map.cameraPosition.target!!.longitude)>179.9 && map.cameraPosition.zoom>=14.9}}
        for(latitude in listOf(90.0,-90.0)) {
            compose.runOnIdle {current=snapshot(listOf(Coordinate(latitude,12.0)))}
            compose.onNodeWithTag("fit-active-recording").performClick()
            compose.waitUntil(5000) {compose.runOnIdle {
                val camera=map.cameraPosition
                // Native camera constraints keep the viewport inside the projected world, so its
                // center is slightly inward from the requested limit. Check the visible point.
                val projectedLatitude=latitude.coerceIn(-MapCameraBounds.MAX_LATITUDE,MapCameraBounds.MAX_LATITUDE)
                val pixel=map.projection.toScreenLocation(org.maplibre.android.geometry.LatLng(projectedLatitude,12.0))
                // SDK projected viewport dimensions can round by less than one dp.
                val tolerance=view.resources.displayMetrics.density
                camera.zoom.isFinite() && camera.target!!.latitude.isFinite() &&
                    kotlin.math.abs(camera.target!!.latitude-projectedLatitude)<.001 &&
                    pixel.x in -tolerance..(view.width+tolerance) && pixel.y in -tolerance..(view.height+tolerance)
            }}
        }
        compose.runOnIdle {current=snapshot(listOf(Coordinate(10.0,179.8),Coordinate(10.1,-179.7)))}
        compose.onNodeWithTag("fit-active-recording").performClick()
        compose.waitUntil(5000) {compose.runOnIdle {kotlin.math.abs(map.cameraPosition.target!!.longitude)>179.0 && map.cameraPosition.zoom>7.0}}
    }
}
