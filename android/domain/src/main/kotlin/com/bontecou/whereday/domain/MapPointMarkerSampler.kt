package com.bontecou.whereday.domain

import kotlin.math.*

/** Web-Mercator world fractions; x may wrap beyond one world at the date line. */
data class MapPointViewport(val x:Double,val y:Double,val width:Double,val height:Double,val viewportWidth:Double,val viewportHeight:Double) {
    val usable:Boolean=x.isFinite() && y.isFinite() && width.isFinite() && height.isFinite() && viewportWidth.isFinite() && viewportHeight.isFinite() &&
        width>0 && height>0 && viewportWidth>0 && viewportHeight>0
    companion object {
        fun projectedY(latitude:Double):Double = 0.5-ln(tan((90+latitude)*PI/360))/(2*PI)
        fun geographic(north:Double,east:Double,south:Double,west:Double,viewportWidth:Double,viewportHeight:Double):MapPointViewport {
            val top=projectedY(north);val bottom=projectedY(south)
            val span=east-west
            return MapPointViewport((west+180)/360,top,(if(span<0) span+360 else span)/360,bottom-top,viewportWidth,viewportHeight)
        }
    }
}

data class MapPointMarkers(val points:List<TrackPoint>,val selected:TrackPoint?=null)

/** One stable route point per visible 48 dp cell, at most 160 retained points. */
class MapPointMarkerSampler(private val viewport:MapPointViewport?,private val selectedPointId:String?=null) {
    private val cells=mutableMapOf<Int,Pair<Long,TrackPoint>>()
    private var ordinal=0L
    private var selected:TrackPoint?=null
    private val columns:Int
    private val rows:Int
    init {
        var c=if(viewport?.usable==true) min(160.0,viewport.viewportWidth/48).toInt().coerceAtLeast(1) else 1
        var r=if(viewport?.usable==true) min(160.0,viewport.viewportHeight/48).toInt().coerceAtLeast(1) else 1
        if(c*r>maximumMarkerCount) {
            val scale=sqrt(maximumMarkerCount.toDouble()/(c*r))
            c=(c*scale).toInt().coerceAtLeast(1);r=(r*scale).toInt().coerceAtLeast(1)
        }
        columns=c;rows=r
    }
    fun add(point:TrackPoint) {
        val index=ordinal++
        if(point.id==selectedPointId) selected=point
        val rect=viewport?.takeIf {it.usable} ?: return
        val coordinate=point.coordinate
        var x=((coordinate.longitude+180)/360-rect.x)%1.0
        if(x<0) x+=1
        val y=MapPointViewport.projectedY(coordinate.latitude)-rect.y
        val width=min(rect.width,1.0)
        if(!x.isFinite() || !y.isFinite() || x>width || y<0 || y>rect.height) return
        val column=min(columns-1,(x/width*columns).toInt())
        val row=min(rows-1,(y/rect.height*rows).toInt())
        val cell=row*columns+column
        if(cell !in cells || point.id==selectedPointId) cells[cell]=index to point
    }
    fun points():List<TrackPoint> = cells.values.sortedBy {it.first}.map {it.second}
    fun snapshot()=MapPointMarkers(points(),selected)
    companion object {
        const val maximumMarkerCount=160
        fun sample(points:Iterable<TrackPoint>,viewport:MapPointViewport?,selectedPointId:String?=null):List<TrackPoint> =
            MapPointMarkerSampler(viewport,selectedPointId).apply {points.forEach(::add)}.points()
    }
}
