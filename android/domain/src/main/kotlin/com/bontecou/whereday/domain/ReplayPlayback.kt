package com.bontecou.whereday.domain

import java.time.Duration
import kotlin.math.ceil
import kotlin.math.max

/** Replay speeds on the trace panel: 1× keeps the iOS calculator's 250 ms / 180-tick pace, 10× and 60× cover more of the route each tick. */
enum class ReplaySpeed(val multiplier:Int,val label:String) {
    normal(1,"1×"),fast(10,"10×"),fastest(60,"60×");
    companion object {fun restore(value:String?)=entries.firstOrNull {it.name==value} ?: normal}
}

/** Playback state for the merged route replay / Trace scrubber. Pure, so the UI only renders it. */
data class ReplayPlayback(val index:Long=0,val count:Long=0,val playing:Boolean=false,val speed:ReplaySpeed=ReplaySpeed.normal) {
    val progress:Double get()=RouteReplay.progress(index,count)
    val atEnd:Boolean get()=count<=1 || index>=count-1

    /** Points advanced per 250 ms tick. 1× reaches the end in about 180 ticks (45 s); 10× and 60× are proportionally quicker. */
    fun stepSize():Long {
        if(count<=1) return 1
        val base=RouteReplay.stepSize(count).toDouble()
        val ticks=max(1.0,180.0/speed.multiplier)
        return max(base,ceil((count-1)/ticks)).toLong().coerceAtLeast(1)
    }
    fun tick():ReplayPlayback {
        if(!playing) return this
        if(atEnd) return copy(playing=false)
        val next=(index+stepSize()).coerceAtMost(count-1)
        return copy(index=next,playing=next<count-1)
    }
    /** Playing again at the end starts over, as before. With Reduce Motion there is no animation: it jumps to the end. */
    fun toggle(reduceMotion:Boolean=false):ReplayPlayback = when {
        playing->copy(playing=false)
        reduceMotion->copy(index=(count-1).coerceAtLeast(0),playing=false)
        atEnd->copy(index=0,playing=count>1)
        else->copy(playing=count>1)
    }
    fun scrub(progress:Double)=copy(index=RouteReplay.index(progress,count) ?: 0,playing=false)
    fun seek(index:Long)=copy(index=index.coerceIn(0,(count-1).coerceAtLeast(0)),playing=false)
    fun withCount(value:Long)=copy(count=value,index=index.coerceIn(0,(value-1).coerceAtLeast(0)))
    fun withSpeed(value:ReplaySpeed)=copy(speed=value)

    companion object {
        const val tickMillis=250L
        /** Speed in m/s at a fix: its GPS speed when it carries one, otherwise the pace to its neighbour. */
        fun movementSpeed(current:TrackPoint,neighbour:TrackPoint?):Double? {
            current.speed?.takeIf {it.isFinite() && it>0.5}?.let {return it}
            val other=neighbour ?: return null
            val (a,b)=if(other.time<current.time) other to current else current to other
            val seconds=Duration.between(a.time,b.time).toMillis()/1000.0
            if(seconds<=0 || seconds>15*60) return null
            return (a.coordinate.distanceTo(b.coordinate)/seconds).takeIf {it>0.5}
        }
    }
}
