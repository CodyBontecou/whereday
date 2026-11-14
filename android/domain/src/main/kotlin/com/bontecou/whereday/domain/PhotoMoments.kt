package com.bontecou.whereday.domain

import java.time.Duration
import java.time.Instant

/** URIs are device-local references, never a portable photo identifier. */
data class PhotoAssetMetadata(val uri:String,val takenAt:Instant?,val gps:Coordinate?=null,val identity:String?=null) {
    init {require(uri.isNotBlank());require(identity==null || identity.isNotBlank())}
}
enum class PhotoCoordinateSource(val wireValue:String) {
    photoGPS("photo_gps"), route("inferred_route"), visit("inferred_visit")
}
data class PhotoMoment(val uri:String,val takenAt:Instant,val coordinate:Coordinate,val source:PhotoCoordinateSource)

object PhotoCorrelation {
    val routeWindow:Duration=Duration.ofMinutes(15)
    val visitGrace:Duration=Duration.ofMinutes(15)

    /** Caller supplies the nearest eligible fixes and the earliest matching visit, without sampling. */
    fun resolve(asset:PhotoAssetMetadata,before:TrackPoint?,after:TrackPoint?,visit:Visit?,now:Instant):PhotoMoment? {
        val time=asset.takenAt ?: return null
        asset.gps?.let {return PhotoMoment(asset.uri,time,it,PhotoCoordinateSource.photoGPS)}
        val candidates=listOfNotNull(before,after)
        val nearest=candidates.minByOrNull {Duration.between(it.time,time).abs()}
        if(nearest!=null && Duration.between(nearest.time,time).abs()<=routeWindow)
            return PhotoMoment(asset.uri,time,nearest.coordinate,PhotoCoordinateSource.route)
        if(visit!=null && time>=visit.arrivedAt.minus(visitGrace) && time<=(visit.departedAt ?: now).plus(visitGrace))
            return PhotoMoment(asset.uri,time,visit.coordinate,PhotoCoordinateSource.visit)
        return null
    }
}

/** Match iOS's ordered, endpoint-retaining display limit without capping stored moments. */
class PhotoMomentSampler(total:Long,val limit:Int=500) {
    init {require(total>=0 && limit>=2)}
    private val indices=if(total<=limit) LongArray(total.toInt()) {it.toLong()}
        else LongArray(limit) {((total-1).toDouble()*it/(limit-1)).toLong()}
    private var index=0L
    private var next=0
    private val photos=ArrayList<PhotoMoment>(indices.size)
    fun add(photo:PhotoMoment) {
        if(next<indices.size && index==indices[next]) {photos.add(photo);next++}
        index++
    }
    fun photos():List<PhotoMoment> = photos.toList()
}
data class PhotoWindow(val count:Long,val photos:List<PhotoMoment>)
