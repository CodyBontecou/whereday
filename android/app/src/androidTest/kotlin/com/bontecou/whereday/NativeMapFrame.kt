package com.bontecou.whereday

import androidx.compose.ui.test.junit4.ComposeTestRule
import org.maplibre.android.maps.MapLibreMap
import org.maplibre.android.maps.MapView
import java.util.concurrent.atomic.AtomicBoolean
import android.view.View
import android.view.ViewGroup
import android.view.inspector.WindowInspector

internal fun findNativeMapView():MapView? {
    fun View.find():MapView?=if(this is MapView) this else if(this is ViewGroup)
        (0 until childCount).firstNotNullOfOrNull {getChildAt(it).find()} else null
    return WindowInspector.getGlobalWindowViews().asReversed().firstNotNullOfOrNull {it.find()}
}

/** SDK map/style callbacks can run before its renderer actor exists. Preserve feature/pixel
 * assertions, but wait for the native precondition rather than racing the first surface. */
internal fun ComposeTestRule.awaitNativeMapFrame(view:MapView,map:MapLibreMap) {
    val rendered=AtomicBoolean()
    val listener=MapView.OnDidFinishRenderingFrameListener {_,_,_->rendered.set(true)}
    runOnUiThread {view.addOnDidFinishRenderingFrameListener(listener);map.triggerRepaint()}
    try {waitUntil(10_000) {rendered.get()}}
    finally {runOnUiThread {view.removeOnDidFinishRenderingFrameListener(listener)}}
}
