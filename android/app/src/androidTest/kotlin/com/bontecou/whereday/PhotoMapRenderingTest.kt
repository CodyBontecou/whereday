package com.bontecou.whereday

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.RectF
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.bontecou.whereday.domain.*
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** An empty test activity renders explicit fixtures; no AppGraph or journal database is opened. */
@RunWith(AndroidJUnit4::class)
class PhotoMapRenderingTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private fun View.mapView():MapView?=if(this is MapView) this else if(this is ViewGroup) (0 until childCount).firstNotNullOfOrNull {getChildAt(it).mapView()} else null
    @Test fun latePhotoSpriteAppearsOnAnAlreadyLoadedOfflineMap() {
        check(androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa") {"Rendering tests require the isolated mapQa application"}
        val layer=mutableStateOf<PhotoMapLayer?>(null)
        val tapped=AtomicReference<List<PhotoCluster>>(emptyList())
        val point=TrackPoint(id="synthetic-pin",coordinate=Coordinate(48.8584,2.2945),time=Instant.parse("2026-10-27T14:00:00Z"),accuracy=5.0)
        compose.setContent {MaterialTheme {JournalMap(listOf(point),emptyList(),endpoints=RouteEndpoints(),photoLayer=layer.value)}}
        val map=AtomicReference<MapLibreMap>()
        val view=compose.runOnIdle {compose.activity.window.decorView.mapView()!!}
        compose.runOnIdle {view.getMapAsync {map.set(it)}}
        compose.waitUntil(10000) {compose.runOnIdle {map.get()?.style?.isFullyLoaded==true}}
        val photos=listOf(PhotoMoment("content://synthetic/a",point.time,point.coordinate,PhotoCoordinateSource.photoGPS),PhotoMoment("content://synthetic/b",point.time.plusSeconds(1),point.coordinate,PhotoCoordinateSource.photoGPS))
        val cluster=PhotoClusters.build(photos).single()
        val sprite=Bitmap.createBitmap(128,128,Bitmap.Config.ARGB_8888).apply {eraseColor(Color.MAGENTA)}
        compose.runOnIdle {layer.value=PhotoMapLayer(true,true,listOf(cluster),emptyMap(),{},{},{tapped.set(it)})
        }
        // Simulate bounded asynchronous decoding after native layout consumed the geometry.
        android.os.SystemClock.sleep(500)
        compose.runOnIdle {layer.value=layer.value!!.copy(icons=mapOf(cluster.id to sprite))}
        fun rendered()=compose.runOnIdle {map.get().queryRenderedFeatures(RectF(0f,0f,view.width.toFloat(),view.height.toFloat()),"journal-photo").size}
        try {compose.waitUntil(10000) {rendered()==1}} catch(_:androidx.compose.ui.test.ComposeTimeoutException) { /* Report the exact render symptom below. */ }
        assertEquals("Late photo group must render a native symbol, not merely update its accessibility count",1,rendered())
        val pixel=compose.runOnIdle {
            val point=map.get().projection.toScreenLocation(org.maplibre.android.geometry.LatLng(cluster.coordinate.latitude,cluster.coordinate.longitude))
            val origin=IntArray(2);view.getLocationOnScreen(origin)
            android.graphics.PointF(origin[0]+point.x,origin[1]+point.y)
        }
        val automation=androidx.test.platform.app.InstrumentationRegistry.getInstrumentation().uiAutomation
        val time=android.os.SystemClock.uptimeMillis()
        for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
            val event=android.view.MotionEvent.obtain(time,android.os.SystemClock.uptimeMillis(),action,pixel.x,pixel.y,0)
            try {assertTrue(automation.injectInputEvent(event,true))} finally {event.recycle()}
        }
        compose.waitUntil(10000) {tapped.get().size==1}
        assertEquals(cluster.id,tapped.get().single().id)
        compose.runOnIdle {layer.value=layer.value!!.copy(enabled=false,clusters=emptyList(),icons=emptyMap())}
        compose.waitUntil(10000) {rendered()==0}
        assertEquals(0,rendered())
    }
}
