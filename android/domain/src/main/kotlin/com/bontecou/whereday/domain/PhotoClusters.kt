package com.bontecou.whereday.domain

/** Identity is the ordered list of local references; neither time nor coordinate alone identifies a photo. */
data class PhotoCluster(val photos:List<PhotoMoment>,val coordinate:Coordinate) {
    val id:String get()=photos.joinToString("\u0000") {it.uri}
    val representative:PhotoMoment get()=photos.last()
}

object PhotoClusters {
    fun build(photos:List<PhotoMoment>,thresholdMeters:Double=35.0,distance:(Coordinate,Coordinate)->Double={a,b->a.distanceTo(b)},checkpoint:()->Unit={}):List<PhotoCluster> {
        require(thresholdMeters.isFinite() && thresholdMeters>=0)
        data class Working(val photos:MutableList<PhotoMoment>,var latitude:Double,var longitude:Double) {
            fun coordinate()=Coordinate(latitude/this.photos.size,longitude/this.photos.size)
        }
        val working=mutableListOf<Working>()
        for(photo in photos.sortedWith(compareBy({it.takenAt},{it.uri}))) {
            checkpoint()
            val nearest=working.indices.minByOrNull {distance(photo.coordinate,working[it].coordinate())}
            if(nearest!=null && distance(photo.coordinate,working[nearest].coordinate())<=thresholdMeters) {
                val cluster=working[nearest];cluster.photos.add(photo);cluster.latitude+=photo.coordinate.latitude;cluster.longitude+=photo.coordinate.longitude
            } else working.add(Working(mutableListOf(photo),photo.coordinate.latitude,photo.coordinate.longitude))
        }
        return working.map {PhotoCluster(it.photos.toList(),it.coordinate())}.sortedWith(compareBy({it.photos.first().takenAt},{it.id}))
    }
}
