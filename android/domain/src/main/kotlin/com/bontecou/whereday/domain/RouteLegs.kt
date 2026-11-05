package com.bontecou.whereday.domain

import java.time.Instant

/** One coloured stretch of the travel path: the visit it leads to decides its ink. */
data class RouteLeg(val destination:String?,val ink:Int?,val points:List<TrackPoint>) {
    val start:Instant get()=points.first().time
    val distanceMeters:Double get()=DayStory.pathDistance(points)
}

/** Colours the route by destination, as the map-first design asks: each leg takes the ink of the visit it arrives at. */
object RouteLegs {
    /** The place a moment on the path belongs to: the stay it is inside, otherwise the next stay it leads to. */
    fun destination(time:Instant,visits:List<Visit>):Visit? {
        val sorted=visits.sortedWith(compareBy({it.arrivedAt},{it.id}))
        return sorted.lastOrNull {it.arrivedAt<=time && (it.departedAt==null || time<=it.departedAt)}
            ?: sorted.firstOrNull {it.arrivedAt>time}
    }

    /** Splits each path segment wherever its destination changes. Boundary fixes are shared so the line stays continuous. */
    fun build(segments:List<List<TrackPoint>>,visits:List<Visit>):List<RouteLeg> {
        val inks=PlaceInks.index(visits)
        val sorted=visits.sortedWith(compareBy({it.arrivedAt},{it.id}))
        val legs=mutableListOf<RouteLeg>()
        segments.forEach {segment->
            var current=mutableListOf<TrackPoint>();var key:String?=null;var started=false
            segment.forEach {point->
                val target=destination(point.time,sorted)?.let(::placeKey)
                if(started && target!=key) {
                    current.add(point)
                    if(current.size>1) legs+=RouteLeg(key,key?.let(inks::get),current)
                    current=mutableListOf(point)
                } else current.add(point)
                key=target;started=true
            }
            if(current.size>1) legs+=RouteLeg(key,key?.let(inks::get),current)
        }
        return legs
    }

    /** Ink for a line that starts at [time]: road-matched lines carry no fixes of their own. */
    fun inkAt(time:Instant,visits:List<Visit>):Int? = destination(time,visits)?.let {PlaceInks.index(visits)[placeKey(it)]}

    /** Time chips sit at the start of the main legs only: long enough to matter, at most [limit] of them. */
    fun chipLegs(legs:List<RouteLeg>,minimumMeters:Double=250.0,limit:Int=6):List<RouteLeg> {
        // Successive legs to the same place read as one journey; label only where the destination changes.
        val starts=legs.filterIndexed {index,leg->index==0 || legs[index-1].destination!=leg.destination}
        return starts.filter {it.distanceMeters>=minimumMeters}.sortedByDescending {it.distanceMeters}.take(limit).sortedBy {it.start}
    }
}
