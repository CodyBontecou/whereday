package com.bontecou.whereday.domain

import kotlin.math.*

/** GeoJSON line pieces for the short crossing of the date line. Display only, never persisted. */
object MapLineGeometry {
    fun splitAtDateLine(coordinates:List<Coordinate>):List<List<Coordinate>> {
        if(coordinates.size<2) return emptyList()
        val output=mutableListOf<List<Coordinate>>()
        var current=mutableListOf(projectable(coordinates.first()))
        fun append(point:Coordinate) {if(current.lastOrNull()!=point) current.add(point)}
        fun finish() {if(current.size>=2) output.add(current.toList())}
        for(raw in coordinates.drop(1)) {
            val next=projectable(raw)
            val previous=current.last()
            var delta=next.longitude-previous.longitude
            if(delta>180.0) delta-=360.0 else if(delta< -180.0) delta+=360.0
            val longitude=previous.longitude+delta
            if(longitude>180.0 || longitude< -180.0) {
                val edge=if(longitude>180.0) 180.0 else -180.0
                val fraction=((edge-previous.longitude)/delta).coerceIn(0.0,1.0)
                // Intersect the straight projected segment, rather than bending it at the cut.
                fun mercator(latitude:Double)=ln(tan(PI/4.0+Math.toRadians(latitude)/2.0))
                val y=mercator(previous.latitude)+(mercator(next.latitude)-mercator(previous.latitude))*fraction
                val latitude=when(fraction) {0.0->previous.latitude;1.0->next.latitude;else->Math.toDegrees(atan(sinh(y)))}
                append(Coordinate(latitude,edge));finish()
                current=mutableListOf(Coordinate(latitude,-edge))
                append(next)
            } else append(Coordinate(next.latitude,longitude))
        }
        finish()
        return output
    }
    private fun projectable(point:Coordinate)=Coordinate(point.latitude.coerceIn(-MapCameraBounds.MAX_LATITUDE,MapCameraBounds.MAX_LATITUDE),point.longitude)
}
