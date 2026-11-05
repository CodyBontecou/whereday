package com.bontecou.whereday.domain

import kotlin.math.*

/** Ephemeral foreground map position. It is never a recording sample or an import record. */
data class MapLocationFix(val coordinate:Coordinate,val accuracyMeters:Double,val elapsedMillis:Long) {
    fun usable(nowElapsedMillis:Long)=accuracyMeters.isFinite() && accuracyMeters in 0.0..100_000.0 &&
        elapsedMillis>=0 && nowElapsedMillis>=elapsedMillis && nowElapsedMillis-elapsedMillis<=30_000
    /** Avoid a precise-looking street zoom for an approximate grant. */
    val maximumZoom:Double get() {
        val metersPerPixel=maxOf(accuracyMeters,25.0)/75.0
        return log2(40_075_016.686*maxOf(cos(Math.toRadians(coordinate.latitude)),0.01)/(256*metersPerPixel)).coerceIn(3.0,16.0)
    }
}
