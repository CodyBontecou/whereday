package com.bontecou.whereday.domain

enum class PhotoMarkerStyle {
    images, pins, dots;
    companion object {
        fun resolve(images:Boolean,dots:Boolean)=if(dots) PhotoMarkerStyle.dots else if(images) PhotoMarkerStyle.images else pins
    }
}

data class PhotoMarkerPoint(val x:Double,val y:Double)

/** Screen-space centers are projected at touch time, so rotation/zoom/pitch cannot stale the targets. */
object PhotoDotHitTesting {
    fun targetIndex(point:PhotoMarkerPoint,centers:List<PhotoMarkerPoint?>,targetSize:Double=44.0):Int? {
        require(targetSize.isFinite() && targetSize>0)
        if(!point.x.isFinite() || !point.y.isFinite()) return null
        var nearest=Double.POSITIVE_INFINITY
        var winner:Int?=null
        centers.forEachIndexed {index,center->
            if(center!=null && center.x.isFinite() && center.y.isFinite()) {
                val dx=point.x-center.x;val dy=point.y-center.y
                if(kotlin.math.abs(dx)<=targetSize/2 && kotlin.math.abs(dy)<=targetSize/2) {
                    val distance=dx*dx+dy*dy
                    if(distance<nearest) {nearest=distance;winner=index}
                }
            }
        }
        return winner
    }
}
