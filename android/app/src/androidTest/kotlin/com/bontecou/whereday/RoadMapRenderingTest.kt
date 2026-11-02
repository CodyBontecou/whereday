package com.bontecou.whereday

import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runners.MethodSorters
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.*
import java.time.Instant
import java.time.Duration
import java.util.concurrent.atomic.AtomicReference
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

@FixMethodOrder(MethodSorters.NAME_ASCENDING)
class RoadMapRenderingTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val points=(0..2).map {TrackPoint("p$it",Coordinate(48.8584,2.2945+it*.01),Instant.EPOCH.plusSeconds(it*600L),5.0)}
    private val north=Coordinate(48.8784,2.2995)
    private val south=Coordinate(48.8384,2.3095)
    private fun route()=RoadRoute(RoadSnapping.fingerprint(points),points.size,listOf(RoadSegment(0,1,listOf(points[0].coordinate,north,points[1].coordinate),true),RoadSegment(1,2,listOf(points[1].coordinate,south,points[2].coordinate),true)))
    @Before fun guard() {check(target.packageName=="com.bontecou.whereday.mapqa")}
    private fun View.mapView():MapView?=if(this is MapView) this else if(this is ViewGroup) (0 until childCount).firstNotNullOfOrNull {getChildAt(it).mapView()} else null
    private fun map():Pair<MapLibreMap,MapView> {
        val result=AtomicReference<MapLibreMap>()
        val view=compose.runOnIdle {checkNotNull(compose.activity.window.decorView.mapView())}
        compose.runOnIdle {view.getMapAsync {result.set(it)}}
        compose.waitUntil(10000) {compose.runOnIdle {result.get()?.style?.isFullyLoaded==true}}
        compose.onNodeWithTag("journal-native-map").performScrollTo()
        compose.awaitNativeMapFrame(view,result.get())
        return result.get() to view
    }
    private fun rendered(map:MapLibreMap,view:MapView,layer:String)=compose.runOnIdle {
        map.queryRenderedFeatures(android.graphics.RectF(0f,0f,view.width.toFloat(),view.height.toFloat()),layer).size
    }
    private fun at(map:MapLibreMap,coordinate:Coordinate)=compose.runOnIdle {
        val p=map.projection.toScreenLocation(LatLng(coordinate.latitude,coordinate.longitude))
        map.queryRenderedFeatures(android.graphics.RectF(p.x-8,p.y-8,p.x+8,p.y+8),"journal-road-path").size
    }
    @Composable private fun Fixture(dark:Boolean=false,spanish:Boolean=false,content:@Composable ()->Unit) {
        val base=LocalContext.current
        val configuration=LocalConfiguration.current
        val localized=remember(configuration,spanish) {android.content.res.Configuration(configuration).apply {if(spanish) setLocales(android.os.LocaleList.forLanguageTags("es"))}}
        val context=remember(base,localized,spanish) {if(spanish) base.createConfigurationContext(localized) else base}
        CompositionLocalProvider(LocalContext provides context,LocalConfiguration provides localized) {
            MaterialTheme(colorScheme=if(dark) darkColorScheme() else lightColorScheme()) {
                Surface {Column(Modifier.fillMaxSize().safeDrawingPadding().verticalScroll(rememberScrollState())) {content()}}
            }
        }
    }
    private fun capture(name:String,map:MapLibreMap,view:MapView,expectedRoad:Boolean=true) {
        val fully=AtomicBoolean(false)
        val listener=MapView.OnDidFinishRenderingFrameListener {complete,_,_->if(complete) fully.set(true)}
        compose.runOnIdle {view.addOnDidFinishRenderingFrameListener(listener);map.triggerRepaint()}
        try {compose.waitUntil(10000) {fully.get()}}
        finally {compose.runOnIdle {view.removeOnDidFinishRenderingFrameListener(listener)}}
        // A logical feature query can precede SurfaceView buffer presentation. Cross a
        // compositor-frame boundary before inspecting actual displayed pixels.
        val frames=AtomicInteger()
        val ticker=object:android.view.Choreographer.FrameCallback {
            override fun doFrame(time:Long) {if(frames.incrementAndGet()<3) android.view.Choreographer.getInstance().postFrameCallback(this)}
        }
        compose.runOnIdle {android.view.Choreographer.getInstance().postFrameCallback(ticker)}
        try {compose.waitUntil(5000) {frames.get()>=3}}
        finally {compose.runOnIdle {android.view.Choreographer.getInstance().removeFrameCallback(ticker)}}
        val bounds=compose.runOnIdle {val location=IntArray(2);view.getLocationOnScreen(location);android.graphics.Rect(location[0],location[1],location[0]+view.width,location[1]+view.height)}
        val bitmap=checkNotNull(InstrumentationRegistry.getInstrumentation().uiAutomation.takeScreenshot())
        try {
            val crop=android.graphics.Rect(0,0,bitmap.width,bitmap.height)
            assertTrue(crop.intersect(bounds))
            val pixels=IntArray(crop.width()*crop.height())
            bitmap.getPixels(pixels,0,crop.width(),crop.left,crop.top,crop.width(),crop.height())
            val blue=pixels.count {color->kotlin.math.abs(android.graphics.Color.red(color)-25)<=12 && kotlin.math.abs(android.graphics.Color.green(color)-118)<=12 && kotlin.math.abs(android.graphics.Color.blue(color)-210)<=12}
            if(expectedRoad) assertTrue("Displayed blue road pixels required, found $blue",blue>300)
            else assertEquals("Removed roads must not remain in the displayed buffer",0,blue)
            if(InstrumentationRegistry.getArguments().getString("captureRoadMap")=="true") {
                val values=android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-road-map-$name-20261030.png")
                    put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png")
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
                }
                val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
                target.contentResolver.openOutputStream(uri)!!.use {output->check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output))}
            }
        } finally {bitmap.recycle()}
    }
    @Test fun aRoadDetoursFitAndRemovalRestoresRawPath() {
        var road by mutableStateOf<RoadRoute?>(route())
        compose.setContent {Fixture {JournalMap(points,emptyList(),roadRoute=road,showStraightSegments=false)}}
        val (map,view)=map()
        compose.waitUntil(10000) {rendered(map,view,"journal-road-path")>0 && at(map,north)>0 && at(map,south)>0}
        assertEquals(0,rendered(map,view,"journal-path"))
        assertTrue(compose.runOnIdle {map.projection.visibleRegion.latLngBounds.contains(LatLng(north.latitude,north.longitude))})
        assertTrue(compose.runOnIdle {view.contentDescription.toString().contains(target.getString(R.string.map_road_estimate_description))})
        compose.onNodeWithTag("road-estimate-caption").assertTextEquals(target.getString(R.string.map_road_estimate_description)).assertIsDisplayed()
        capture("roads",map,view)
        compose.runOnIdle {road=null}
        compose.waitUntil(10000) {rendered(map,view,"journal-road-path")==0 && rendered(map,view,"journal-path")>0}
        compose.onNodeWithTag("road-estimate-caption").assertDoesNotExist()
        capture("raw",map,view,false)
        compose.runOnIdle {road=route()}
        compose.waitUntil(10000) {rendered(map,view,"journal-road-path")>0 && rendered(map,view,"journal-path")==0}

    }
    @Test fun bReplayWaitsForSampledEndpointRatherThanUsingFullHistoryIndex() {
        val between=points[0].copy(id="between",coordinate=Coordinate(48.8584,2.2995),time=Instant.EPOCH.plusSeconds(300))
        fun frame(p:TrackPoint)=ReplayFrame(9999,10000,p,listOf(points[0],p),Duration.ZERO,Duration.ZERO,0.0,0.0)
        var progress by mutableStateOf(frame(between))
        compose.setContent {Fixture {JournalMap(points,emptyList(),replay=progress,roadRoute=route(),showStraightSegments=false)}}
        val (map,view)=map()
        compose.waitUntil(10000) {rendered(map,view,"journal-replay-position")==1}
        assertEquals(0,rendered(map,view,"journal-road-path"));assertEquals(0,rendered(map,view,"journal-path"))
        compose.runOnIdle {progress=frame(points[1])}
        compose.waitUntil(10000) {at(map,north)>0}
        assertEquals(0,at(map,south))
        compose.runOnIdle {progress=frame(points[2])}
        compose.waitUntil(10000) {at(map,south)>0}
        capture("replay-complete",map,view)
    }
    @Test fun cStaleRouteAndPathControlsCannotLeaveRoadPixelsBehind() {
        var current by mutableStateOf(points)
        var dark by mutableStateOf(false)
        compose.setContent {Fixture(dark) {JournalMap(current,emptyList(),roadRoute=route(),showStraightSegments=false)}}
        val (map,view)=map()
        compose.waitUntil(10000) {rendered(map,view,"journal-road-path")>0}
        compose.runOnIdle {current=points.mapIndexed {i,p->if(i==0) p.copy(accuracy=6.0) else p};dark=true}
        compose.waitUntil(10000) {compose.runOnIdle {map.style?.isFullyLoaded==true} && rendered(map,view,"journal-road-path")==0 && rendered(map,view,"journal-path")>0}
        compose.runOnIdle {current=points}
        compose.waitUntil(10000) {rendered(map,view,"journal-road-path")>0}
        compose.onNode(hasText(target.getString(R.string.map_layers)) and hasClickAction()).performClick()
        compose.onNodeWithTag("map-layer-path").performClick()
        compose.onNodeWithText(target.getString(R.string.map_layers_done)).performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithTag("map-layers-content").fetchSemanticsNodes().isEmpty()}
        compose.waitUntil(10000) {rendered(map,view,"journal-road-path")==0 && rendered(map,view,"journal-path")==0}
        compose.onNode(hasText(target.getString(R.string.map_layers)) and hasClickAction()).performClick()
        compose.onNodeWithTag("map-layer-path").performClick()
        compose.onNodeWithText(target.getString(R.string.map_layers_done)).performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithTag("map-layers-content").fetchSemanticsNodes().isEmpty()}
        compose.waitUntil(10000) {rendered(map,view,"journal-road-path")>0}
    }
    @Test fun dSpanishDarkMapDescribesEstimatedDisplay() {
        compose.setContent {Fixture(dark=true,spanish=true) {JournalMap(points,emptyList(),roadRoute=route(),showStraightSegments=false)}}
        val (map,view)=map()
        compose.waitUntil(10000) {rendered(map,view,"journal-road-path")>0}
        assertTrue(compose.runOnIdle {view.contentDescription.toString().contains("La ruta mostrada en las vías es una estimación.")})
        compose.onNodeWithTag("road-estimate-caption").assertTextEquals("La ruta mostrada en las vías es una estimación. Las ubicaciones grabadas no cambian.").assertIsDisplayed()
        capture("spanish-dark",map,view)
    }
}
