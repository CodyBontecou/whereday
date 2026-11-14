package com.bontecou.whereday.domain

/** Presentation bounds; longitudes may be unwrapped across the date line. Never stored as fixes. */
data class MapCameraBounds(val south:Double,val west:Double,val north:Double,val east:Double) {
    val isPoint:Boolean get()=south==north && west==east
    companion object {
        // Web Mercator cannot project the geographic poles.
        const val MAX_LATITUDE=85.0511287798066

        /** Smallest longitude arc containing the bounded drawing input, excluding its largest gap. */
        fun enclosing(coordinates:List<Coordinate>):MapCameraBounds? {
            if(coordinates.isEmpty()) return null
            val longitudes=coordinates.map {if(it.longitude==180.0) -180.0 else it.longitude}.distinct().sorted()
            var west=longitudes.first()
            var east=longitudes.last()
            var largestGap=west+360.0-east
            for(i in 0 until longitudes.lastIndex) {
                val gap=longitudes[i+1]-longitudes[i]
                if(gap>largestGap) {
                    largestGap=gap
                    west=longitudes[i+1]
                    east=longitudes[i]+360.0
                }
            }
            if((west+east)/2.0>180.0) {west-=360.0;east-=360.0}
            return MapCameraBounds(
                coordinates.minOf {it.latitude}.coerceIn(-MAX_LATITUDE,MAX_LATITUDE),west,
                coordinates.maxOf {it.latitude}.coerceIn(-MAX_LATITUDE,MAX_LATITUDE),east,
            )
        }
    }
}
