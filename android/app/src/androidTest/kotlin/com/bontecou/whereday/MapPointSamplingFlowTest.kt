package com.bontecou.whereday

import android.graphics.RectF
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.maplibre.android.camera.CameraUpdateFactory
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.geometry.LatLngBounds
import org.maplibre.android.maps.*
import org.maplibre.android.style.expressions.Expression
import org.maplibre.android.style.sources.GeoJsonSource
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

class MapPointSamplingFlowTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val start=Instant.parse("2026-11-02T00:00:00Z")
    private var database:HistoryDatabase?=null
    @Before fun guard() {check(context.packageName=="com.bontecou.whereday.mapqa")}
    @After fun close() {compose.activityRule.scenario.close();database?.close()}
    private fun show(points:List<TrackPoint>,source:MapPointMarkerSource?=null) {compose.setContent {MaterialTheme {Surface {
        Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {JournalMap(points,emptyList(),pointMarkerSource=source)}
    }}}}
    private fun native():Pair<MapView,MapLibreMap> {
        compose.onNodeWithTag("journal-native-map").performScrollTo()
        val view=compose.runOnIdle {checkNotNull(findNativeMapView())};val map=AtomicReference<MapLibreMap>()
        compose.runOnUiThread {view.getMapAsync {map.set(it)}}
        compose.waitUntil(10000) {compose.runOnIdle {map.get()?.style?.isFullyLoaded==true}}
        compose.awaitNativeMapFrame(view,map.get());return view to map.get()
    }
    private fun ids(view:MapView,map:MapLibreMap):Set<String> = compose.runOnIdle {
        if(!view.isEnabled) emptySet() else map.queryRenderedFeatures(RectF(0f,0f,view.width.toFloat(),view.height.toFloat()),"journal-point","journal-outlier").map {it.getStringProperty("id")}.toSet()
    }
    private fun capture(stage:String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        if(InstrumentationRegistry.getArguments().getString("capturePointSampler")!="true") return
        compose.waitForIdle();instrumentation.uiAutomation.waitForIdle(500,5000)
        val image=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val values=android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-point-sampler-$stage${InstrumentationRegistry.getArguments().getString("pointSamplerCaptureSuffix").orEmpty()}-20261102.png")
                put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
            }
            val uri=checkNotNull(context.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
            context.contentResolver.openOutputStream(uri)!!.use {check(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
        } finally {image.recycle()}
    }
    @Test fun denseNativeDotsAreViewportBoundedWithoutReducingRoutePresentation() {
        val points=(0 until 1600).map {i->TrackPoint("dense-$i",Coordinate(18+(i/40)*.001,-66+(i%40)*.001),start.plusSeconds(i.toLong()),5.0)}
        show(points);val (view,map)=native()
        compose.waitUntil(10000) {ids(view,map).isNotEmpty()}
        val rendered=ids(view,map)
        assertTrue("Native marker cap: ${rendered.size}",rendered.size in 1..160)
        val presentation=MapPresentation.build(points,emptyList(),RouteEndpoints.from(points),MapLayers())
        assertEquals(1600,presentation.segments.sumOf {it.size})
        compose.waitUntil(10000) {compose.runOnIdle {
            map.queryRenderedFeatures(RectF(0f,0f,view.width.toFloat(),view.height.toFloat()),"journal-path").isNotEmpty()
        }}
        capture("dense")
    }

    @Test fun completeDatabaseSourceMakesAnUnsampledPointInspectableAndRefreshesItsMetadata() {
        val db=HistoryDatabase.create(context,null).also {database=it};val history=HistoryRepository(db)
        val base=TrackPoint("point-000000",Coordinate(18.0,-66.0),start,5.0)
        val unique=base.copy(id="point-000001",coordinate=Coordinate(18.005,-66.005),accuracy=9.0)
        val range=HistoryRange(start,start.plusSeconds(10000))
        val route=runBlocking {
            db.writeTransaction {repeat(5001) {i->history.dao.put(PointRow(if(i==1) unique else base.copy(id="point-%06d".format(i),time=start.plusMillis(i.toLong())),null))}}
            history.readWindow(range,displayLimit=25).points
        }
        assertFalse(route.any {it.id==unique.id})
        val source=MapPointMarkerSource {viewport,selected,_->history.observePointMarkers(ReplayRequest.Range(range),viewport,selected)}
        show(route,source);val (view,map)=native()
        compose.runOnUiThread {map.moveCamera(CameraUpdateFactory.newLatLngBounds(LatLngBounds.from(18.005,-66.0,18.0,-66.005),128))}
        compose.waitUntil(10000) {unique.id in ids(view,map)}
        compose.onNodeWithTag("map-point-browse").performScrollTo().performClick()
        compose.onNodeWithTag("map-point-choice-${unique.id}").performScrollTo().performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithTag("map-point-accuracy").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("map-point-inspection").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("map-point-accuracy").assertIsDisplayed().assertTextContains("9 m",substring=true)
        runBlocking {history.dao.put(PointRow(unique.copy(accuracy=42.0),null))}
        compose.waitUntil(10000) {compose.onAllNodes(hasTestTag("map-point-accuracy") and hasText("42 m",substring=true)).fetchSemanticsNodes().isNotEmpty()}
        assertEquals(5001L,runBlocking {history.dao.totalPointCount()});capture("unsampled-inspection")
    }
    @Test fun dateLineCameraAndPanUpdateTheGridWithoutChangingSourceIdentity() {
        val points=listOf(TrackPoint("east",Coordinate(0.0,179.0),start,5.0),TrackPoint("west",Coordinate(0.0,-179.0),start.plusSeconds(1),5.0))
        show(points);val (view,map)=native()
        compose.runOnUiThread {map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.0,180.0),6.0))}
        compose.waitUntil(10000) {ids(view,map)==setOf("east","west")}
        compose.runOnUiThread {map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.0,0.0),6.0))}
        compose.waitUntil(10000) {ids(view,map).isEmpty()}
        compose.runOnUiThread {map.moveCamera(CameraUpdateFactory.newLatLngZoom(LatLng(0.0,180.0),6.0))}
        compose.waitUntil(10000) {ids(view,map)==setOf("east","west")}
    }
}
