package com.bontecou.whereday.domain

import kotlin.math.*

/** Identity is the ordered list of local references; neither time nor coordinate alone identifies a photo. */
data class PhotoCluster(val photos:List<PhotoMoment>,val coordinate:Coordinate, val isArea:Boolean=false, val places:List<PhotoCluster> = emptyList()) {
    val id:String get()=photos.joinToString("\u0000") {it.uri}
    val representative:PhotoMoment get()=photos.last()
}

object PhotoClusters {
    fun build(photos:List<PhotoMoment>,thresholdMeters:Double=35.0,distance:((Coordinate,Coordinate)->Double)?=null,checkpoint:()->Unit={}):List<PhotoCluster> {
        require(thresholdMeters.isFinite() && thresholdMeters>=0)
        data class Working(val photos:MutableList<PhotoMoment>,var x:Double,var y:Double,var z:Double) {
            fun coordinate()=Coordinate(atan2(z,sqrt(x*x+y*y))*180/PI,atan2(y,x)*180/PI)
            fun append(photo:PhotoMoment) {
                this.photos.add(photo)
                val latitude=photo.coordinate.latitude*PI/180;val longitude=photo.coordinate.longitude*PI/180
                x+=cos(latitude)*cos(longitude);y+=cos(latitude)*sin(longitude);z+=sin(latitude)
            }
        }
        val working=mutableListOf<Working>()
        val width=max(1.0,thresholdMeters*1.02)
        val index=mutableMapOf<SpatialCell,MutableSet<Int>>()
        val measure=distance ?: { a:Coordinate,b:Coordinate -> a.distanceTo(b) }
        for(photo in photos.sortedWith(compareBy({it.takenAt},{it.uri}))) {
            checkpoint()
            val cell=SpatialCell.of(photo.coordinate,width)
            // An injected diagnostic metric has no geographic bound, so it must use exhaustive search.
            val candidates=if(distance!=null) working.indices.asSequence() else cell.neighbours().flatMap {index[it].orEmpty().asSequence()}
            var nearest:Int?=null
            var nearestDistance=Double.POSITIVE_INFINITY
            for(candidate in candidates) {
                checkpoint()
                val meters=measure(photo.coordinate,working[candidate].coordinate())
                if(meters<=thresholdMeters && (meters<nearestDistance || (meters==nearestDistance && candidate<(nearest ?: Int.MAX_VALUE)))) {
                    nearest=candidate;nearestDistance=meters
                }
            }
            if(nearest!=null) {
                val oldCell=SpatialCell.of(working[nearest].coordinate(),width)
                working[nearest].append(photo)
                val newCell=SpatialCell.of(working[nearest].coordinate(),width)
                if(oldCell!=newCell) {
                    index[oldCell]?.let {it.remove(nearest);if(it.isEmpty()) index.remove(oldCell)}
                    index.getOrPut(newCell){mutableSetOf()}.add(nearest)
                }
            } else {
                index.getOrPut(cell){mutableSetOf()}.add(working.size)
                val latitude=photo.coordinate.latitude*PI/180;val longitude=photo.coordinate.longitude*PI/180
                working.add(Working(mutableListOf(photo),cos(latitude)*cos(longitude),cos(latitude)*sin(longitude),sin(latitude)))
            }
        }
        return working.map {PhotoCluster(it.photos.toList(),it.coordinate())}
    }

    /** Coarsens whole places; area anchors remain actual member places. No photo is discarded. */
    fun annotations(places:List<PhotoCluster>,maximumCount:Int=500,checkpoint:()->Unit={}):List<PhotoCluster> {
        require(maximumCount>0)
        require(places.none {it.isArea})
        if(places.size<=maximumCount) return places
        var width=1.0/1024
        while(true) {
            checkpoint()
            val cells=linkedMapOf<Pair<Int,Int>,MutableList<PhotoCluster>>()
            for(place in places) {
                checkpoint()
                val cell=if(width>=360) 0 to 0 else floor((place.coordinate.latitude+90)/width).toInt() to floor((place.coordinate.longitude+180)/width).toInt()
                cells.getOrPut(cell){mutableListOf()}.add(place)
            }
            if(cells.size<=maximumCount) return cells.values.map {members ->
                PhotoCluster(members.flatMap {it.photos}.sortedWith(compareBy({it.takenAt},{it.uri})),members.first().coordinate,true,members.toList())
            }.sortedWith(compareBy({it.photos.first().takenAt},{it.places.first().id}))
            width*=2
        }
    }

    private data class SpatialCell(val x:Long,val y:Long,val z:Long) {
        fun neighbours()=sequence {
            for(dx in -1L..1L) for(dy in -1L..1L) for(dz in -1L..1L) yield(SpatialCell(x+dx,y+dy,z+dz))
        }
        companion object {
            fun of(coordinate:Coordinate,width:Double):SpatialCell {
                val latitude=coordinate.latitude*PI/180;val longitude=coordinate.longitude*PI/180
                return SpatialCell(floor(cos(latitude)*cos(longitude)*6371000/width).toLong(),floor(cos(latitude)*sin(longitude)*6371000/width).toLong(),floor(sin(latitude)*6371000/width).toLong())
            }
        }
    }

}
