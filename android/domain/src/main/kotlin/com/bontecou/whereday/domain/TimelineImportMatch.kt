package com.bontecou.whereday.domain

import java.time.Duration
import java.time.Instant
import kotlin.math.*

/** iOS timeline import matching uses chronology/location, independently of imported UUIDs
 * or editable names/notes. This is intentionally separate from backup identity replacement. */
data class TimelineImportMatch(val visit:Boolean,val coordinate:Coordinate,val time:Instant,val departure:Instant?=null) {
    val bucket:Long get() {
        val seconds=time.epochSecond.toDouble()+time.nano/1e9
        return if(visit) floor(seconds/60).toLong() else (floor(abs(seconds)+0.5)*sign(seconds)).toLong()
    }
    fun matches(other:TimelineImportMatch):Boolean {
        if(visit!=other.visit) return false
        if(!visit) return bucket==other.bucket && coordinate.distanceTo(other.coordinate)<=10
        if(Duration.between(time,other.time).abs()>Duration.ofSeconds(60) || coordinate.distanceTo(other.coordinate)>100) return false
        return if(departure==null || other.departure==null) departure==other.departure
            else Duration.between(departure,other.departure).abs()<=Duration.ofSeconds(300)
    }
    companion object {
        fun from(value:Visit)=TimelineImportMatch(true,value.coordinate,value.arrivedAt,value.departedAt)
        fun from(value:TrackPoint)=TimelineImportMatch(false,value.coordinate,value.time)
    }
}
