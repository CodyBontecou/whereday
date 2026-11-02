package com.bontecou.whereday.domain

/** Elapsed time avoids accepting an old fix after the wall clock changes. Never stored as history. */
data class CurrentVisitLocation(val coordinate:Coordinate,val accuracyMeters:Double,val elapsedMillis:Long) {
    fun isUsable(nowElapsedMillis:Long):Boolean = accuracyMeters.isFinite() && accuracyMeters in 0.0..100.0 &&
        elapsedMillis>=0 && nowElapsedMillis>=elapsedMillis && nowElapsedMillis-elapsedMillis<=300_000

    /** iOS current-place prompts prefer the nearest saved center inside that place's own radius. */
    fun savedPlaceSuggestion(places:List<SavedPlace>,nowElapsedMillis:Long):SavedPlace? {
        if(!isUsable(nowElapsedMillis)) return null
        var nearest:SavedPlace?=null
        var nearestDistance=Double.POSITIVE_INFINITY
        for(place in places) {
            val distance=place.coordinate.distanceTo(coordinate)
            if(distance<=place.radius && distance<nearestDistance) {nearest=place;nearestDistance=distance}
        }
        return nearest
    }
}

fun interface CurrentVisitLocationSource {
    suspend fun locate():CurrentVisitLocation?
}
