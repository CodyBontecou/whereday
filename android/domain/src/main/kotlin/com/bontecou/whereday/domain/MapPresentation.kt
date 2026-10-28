package com.bontecou.whereday.domain

import java.time.Duration

data class RouteEndpoints(val first:TrackPoint?=null,val last:TrackPoint?=null,val firstGood:TrackPoint?=null,val lastGood:TrackPoint?=null) {
    fun selected(outliers:Boolean=false,fallback:Boolean=false):Pair<TrackPoint?,TrackPoint?> =
        if(outliers || (fallback && firstGood==null)) first to last else firstGood to lastGood
    companion object {fun from(points:List<TrackPoint>):RouteEndpoints=RouteEndpointBuilder().apply {points.sortedWith(compareBy({it.time},{it.id})).forEach(::add)}.build()}
}
class RouteEndpointBuilder {
    private var first:TrackPoint?=null;private var last:TrackPoint?=null
    private var firstGood:TrackPoint?=null;private var lastGood:TrackPoint?=null
    fun add(point:TrackPoint) {
        require(last?.let {point.time>=it.time}!=false)
        if(first==null) first=point
        last=point
        if(!point.outlier) {if(firstGood==null) firstGood=point;lastGood=point}
    }
    fun build()=RouteEndpoints(first,last,firstGood,lastGood)
}
data class MapLayers(val path:Boolean=true,val points:Boolean=true,val visits:Boolean=true,val endpoints:Boolean=true,val outliers:Boolean=false)
data class MapPresentation(val segments:List<List<TrackPoint>>,val points:List<TrackPoint>,val visits:List<Visit>,val start:TrackPoint?,val end:TrackPoint?) {
    companion object {
        fun build(source:List<TrackPoint>,visits:List<Visit>,ends:RouteEndpoints,layers:MapLayers,replay:ReplayFrame?=null,fallback:Boolean=false):MapPresentation {
            val included=if(replay!=null) replay.visiblePoints else source.filter {layers.outliers || !it.outlier}.ifEmpty {if(fallback) source else emptyList()}.sortedWith(compareBy({it.time},{it.id}))
            val segments=mutableListOf<List<TrackPoint>>();var current=mutableListOf<TrackPoint>()
            if(layers.path) included.forEach {point->
                if(current.lastOrNull()?.let {Duration.between(it.time,point.time)>Duration.ofMinutes(10)}==true) {if(current.size>1) segments.add(current);current=mutableListOf()}
                current.add(point)
            }
            if(current.size>1) segments.add(current)
            val (first,last)=ends.selected(layers.outliers,fallback)
            return MapPresentation(segments,if(layers.points) included else emptyList(),if(layers.visits) visits else emptyList(),
                first.takeIf {layers.endpoints},last.takeIf {layers.endpoints && it?.id!=first?.id})
        }
    }
}
