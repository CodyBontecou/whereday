package com.bontecou.whereday.domain

/** Elapsed time avoids accepting an old fix after the wall clock changes. Never stored as history. */
data class CurrentVisitLocation(val coordinate:Coordinate,val accuracyMeters:Double,val elapsedMillis:Long) {
    fun isUsable(nowElapsedMillis:Long):Boolean = accuracyMeters.isFinite() && accuracyMeters in 0.0..100.0 &&
        elapsedMillis>=0 && nowElapsedMillis>=elapsedMillis && nowElapsedMillis-elapsedMillis<=300_000
}

fun interface CurrentVisitLocationSource {
    suspend fun locate():CurrentVisitLocation?
}
