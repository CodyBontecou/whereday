package com.bontecou.whereday.domain

import java.time.Duration
import kotlin.math.ceil
import kotlin.math.floor

/** Same point-index progression and 250 ms/180-tick pacing as the iOS replay calculator. */
object RouteReplay {
    fun clampedProgress(value:Double)=if(value.isFinite()) value.coerceIn(0.0,1.0) else 0.0
    fun index(progress:Double,count:Long):Long? {
        if(count<=0) return null
        val last=count-1
        return floor(clampedProgress(progress)*last+0.5).toLong().coerceIn(0,last)
    }
    fun progress(index:Long,count:Long)=if(count<=1) 0.0 else index.coerceIn(0,count-1).toDouble()/(count-1)
    fun stepSize(count:Long)=if(count<=1) 1L else ceil((count-1)/180.0).toLong().coerceAtLeast(1)
    fun next(index:Long,count:Long)=if(count<=1) 0L else (index.coerceIn(0,count-1)+stepSize(count)).coerceAtMost(count-1)
    fun formattedDuration(value:Duration):String {
        val seconds=if(value.isNegative) 0 else value.seconds+if(value.nano>=500_000_000) 1 else 0
        return if(seconds>=3600) "%d:%02d:%02d".format(java.util.Locale.ROOT,seconds/3600,seconds%3600/60,seconds%60)
            else "%d:%02d".format(java.util.Locale.ROOT,seconds/60,seconds%60)
    }
}

sealed interface ReplayRequest {
    data class Range(val range:HistoryRange,val includeOutliers:Boolean=false):ReplayRequest
    data class Outing(val id:String):ReplayRequest
    data class Inferred(val header:InferredOutingHeader):ReplayRequest
}

data class ReplayFrame(val index:Long,val count:Long,val current:TrackPoint,val visiblePoints:List<TrackPoint>,
    val elapsed:Duration,val duration:Duration,val distanceMeters:Double,val totalDistanceMeters:Double) {
    val progress get()=RouteReplay.progress(index,count)
}
