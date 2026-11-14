package com.bontecou.whereday

import android.graphics.PointF
import android.os.SystemClock
import android.view.MotionEvent
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import org.junit.*
import org.junit.Assert.*
import org.maplibre.android.geometry.LatLng
import org.maplibre.android.maps.*
import java.time.Instant
import java.util.concurrent.atomic.*

/** The optional debugger probe holds the real render-thread boundary; default CI checks
 * ordinary first-frame input and both pause/resume and stop/start transitions. */
class NativeMapInputReadinessTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private fun tap(view:MapView,pixel:PointF) {
        val location=compose.runOnUiThread {IntArray(2).also(view::getLocationOnScreen)}
        val at=SystemClock.uptimeMillis()
        for(action in listOf(MotionEvent.ACTION_DOWN,MotionEvent.ACTION_UP)) {
            val event=MotionEvent.obtain(at,SystemClock.uptimeMillis(),action,location[0]+pixel.x,location[1]+pixel.y,0)
            try {assertTrue(instrumentation.uiAutomation.injectInputEvent(event,true))} finally {event.recycle()}
        }
    }
    @Test fun inputWaitsForTheFirstFrameAndWorksAfterBothResumePaths() {
        check(instrumentation.targetContext.packageName=="com.bontecou.whereday.mapqa")
        val coordinate=Coordinate(48.8584,2.2945)
        val visit=Visit(coordinate=coordinate,arrivedAt=Instant.EPOCH,name="Synthetic map visit")
        val selected=AtomicInteger();val observedTaps=AtomicInteger();val rendered=AtomicBoolean()
        compose.setContent {MaterialTheme {Surface(Modifier.fillMaxSize()) {Column(Modifier.fillMaxSize().safeDrawingPadding()) {
            JournalMap(listOf(TrackPoint(coordinate=coordinate,time=Instant.EPOCH,accuracy=5.0)),listOf(visit),onVisitSelected={selected.incrementAndGet()})
        }}}}
        val view=compose.runOnIdle {checkNotNull(findNativeMapView())}
        val map=AtomicReference<MapLibreMap>()
        compose.runOnUiThread {
            view.addOnDidFinishRenderingFrameListener {_,_,_->rendered.set(true)}
            view.getMapAsync {map.set(it);it.addOnMapClickListener {observedTaps.incrementAndGet();false}}
        }
        compose.waitUntil(10_000) {compose.runOnUiThread {map.get()?.style?.isFullyLoaded==true}}
        if(InstrumentationRegistry.getArguments().getString("requireRendererHold")=="true") {
            assertFalse("The controlled probe must reach pre-frame input",rendered.get())
            assertFalse("SDK input must be disabled before the native actor exists",compose.runOnUiThread {view.isEnabled})
            tap(view,compose.runOnUiThread {PointF(view.width/2f,view.height/2f)})
            assertFalse(rendered.get());assertEquals(0,selected.get());assertEquals(0,observedTaps.get())
        }
        compose.awaitNativeMapFrame(view,map.get())
        assertTrue(compose.runOnUiThread {view.isEnabled})
        fun select(expected:Int) {
            // Distinct selections must not become an SDK double-tap zoom gesture.
            SystemClock.sleep(android.view.ViewConfiguration.getDoubleTapTimeout().toLong()+50)
            tap(view,compose.runOnUiThread {map.get().projection.toScreenLocation(LatLng(coordinate.latitude,coordinate.longitude))})
            compose.waitUntil(5000) {selected.get()==expected}
        }
        select(1)
        for((index,state) in listOf(Lifecycle.State.STARTED,Lifecycle.State.CREATED).withIndex()) {
            compose.activityRule.scenario.moveToState(state)
            assertFalse(compose.runOnUiThread {view.isEnabled})
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(5000) {compose.runOnUiThread {view.isEnabled}}
            select(index+2)
        }
    }
    @Test fun pickerInputWaitsForTheFirstFrameAndWorksAfterResume() {
        check(instrumentation.targetContext.packageName=="com.bontecou.whereday.mapqa")
        val coordinate=Coordinate(48.8584,2.2945)
        val allowed=kotlinx.coroutines.flow.MutableStateFlow(false)
        val lookup=PlaceLookupService(object:PlaceLookupProvider {
            override suspend fun search(query:String,language:String):List<PlaceLookupRecord> = error("Offline picker must not search")
            override suspend fun reverse(coordinate:Coordinate,language:String):List<PlaceLookupRecord> = error("Offline picker must not reverse geocode")
        },allowed,{"en"})
        val map=AtomicReference<MapLibreMap>()
        compose.setContent {MaterialTheme {PlaceLocationPicker(coordinate,lookup,allowed,{},{},{map.set(it)})}}
        val view=compose.runOnIdle {checkNotNull(findNativeMapView())}
        val rendered=AtomicBoolean()
        val probe=InputPinProbe()
        val renderListener=MapView.OnDidFinishRenderingFrameListener {fully,_,_->rendered.set(true);probe.record("frame",if(fully) 1 else 0)}
        val styleListener=MapView.OnDidFinishLoadingStyleListener {probe.record("style-loaded")}
        val sourceListener=MapView.OnSourceChangedListener {id->if(id=="selected-place") probe.record("selected-source-changed")}
        compose.runOnUiThread {
            view.addOnDidFinishRenderingFrameListener(renderListener)
            view.addOnDidFinishLoadingStyleListener(styleListener)
            view.addOnSourceChangedListener(sourceListener)
        }
        try {
        probe.record("listeners-attached")
        compose.waitUntil(10_000) {compose.runOnUiThread {map.get()?.style?.isFullyLoaded==true}}
        if(InstrumentationRegistry.getArguments().getString("requireRendererHold")=="true") {
            assertFalse("The controlled picker probe must reach pre-frame input",rendered.get())
            assertFalse("Picker SDK input must wait for its native actor",compose.runOnUiThread {view.isEnabled})
            tap(view,compose.runOnUiThread {PointF(view.width/2f+80f,view.height/2f)})
            assertFalse(rendered.get())
        }
        probe.record("before-frame-wait")
        compose.awaitNativeMapFrame(view,map.get())
        probe.record("after-frame-wait")
        assertTrue(compose.runOnUiThread {view.isEnabled})
        probe.record("before-initial-query")
        // Keep the original await/enable/query ordering; do not query to collect
        // diagnostics until AFTER the first original result is irrevocably captured.
        val firstPins=compose.runOnUiThread {map.get().queryRenderedFeatures(map.get().projection.toScreenLocation(LatLng(coordinate.latitude,coordinate.longitude)),"selected-pin").size}
        probe.record("original-initial-pin-count",firstPins)
        if(firstPins!=1) compose.runOnUiThread {
            val target=map.get();val pixel=target.projection.toScreenLocation(LatLng(coordinate.latitude,coordinate.longitude))
            val all=target.queryRenderedFeatures(android.graphics.RectF(0f,0f,view.width.toFloat(),view.height.toFloat()),"selected-pin").size
            val source=target.style?.getSourceAs<org.maplibre.android.style.sources.GeoJsonSource>("selected-place")!=null
            val layer=target.style?.getLayer("selected-pin")!=null
            println("[DEBUG-input-pin] post-zero width=${view.width} height=${view.height} pixelX=${pixel.x} pixelY=${pixel.y} allPins=$all source=$source layer=$layer styleLoaded=${target.style?.isFullyLoaded} enabled=${view.isEnabled} focus=${view.hasWindowFocus()}")
        }
        assertEquals(1,firstPins)
        fun select(offset:Float) {
            SystemClock.sleep(android.view.ViewConfiguration.getDoubleTapTimeout().toLong()+50)
            val pixel=compose.runOnUiThread {PointF(view.width/2f+offset,view.height/2f)}
            tap(view,pixel)
            compose.waitUntil(5000) {compose.runOnUiThread {map.get().queryRenderedFeatures(pixel,"selected-pin").size==1}}
        }
        select(50f)
        for((index,state) in listOf(Lifecycle.State.STARTED,Lifecycle.State.CREATED).withIndex()) {
            compose.activityRule.scenario.moveToState(state)
            assertFalse(compose.runOnUiThread {view.isEnabled})
            compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
            compose.waitUntil(5000) {compose.runOnUiThread {view.isEnabled}}
            select(if(index==0) -50f else 100f)
        }
        } finally {
            compose.runOnUiThread {
                view.removeOnDidFinishRenderingFrameListener(renderListener)
                view.removeOnDidFinishLoadingStyleListener(styleListener)
                view.removeOnSourceChangedListener(sourceListener)
            }
            probe.dump()
        }
    }

}
