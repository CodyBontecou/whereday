package com.bontecou.whereday

import org.maplibre.android.maps.MapView
import org.maplibre.android.maps.renderer.surfaceview.MapLibreVulkanSurfaceView

/** MapLibre dispatches gestures even when disabled; its annotation hit test can query
 * the native renderer before surface initialization. Gate input before SDK dispatch. */
internal class RendererReadyMapView(context:android.content.Context):MapView(context) {
    init {
        isEnabled=false
        // In pinned 13.4.1, a first surface arriving before the Vulkan loop's
        // initial no-surface acknowledgment leaves graphicsSurfaceCreated false
        // forever. Establish that acknowledgment while this view is unattached;
        // no actual surface/native renderer has been created or destroyed yet.
        (renderView as? MapLibreVulkanSurfaceView)?.let {surface->
            check(!surface.isAttachedToWindow)
            surface.surfaceDestroyed(surface.holder)
        }
    }
    override fun dispatchTouchEvent(event:android.view.MotionEvent):Boolean =
        if(!isEnabled) true else super.dispatchTouchEvent(event)
    override fun dispatchGenericMotionEvent(event:android.view.MotionEvent):Boolean =
        if(!isEnabled) false else super.dispatchGenericMotionEvent(event)
    override fun dispatchKeyEvent(event:android.view.KeyEvent):Boolean =
        if(!isEnabled) false else super.dispatchKeyEvent(event)
}

