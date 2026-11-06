package com.bontecou.whereday.domain

import java.time.Duration
import java.time.Instant

/** How route points are chosen while recording (iOS RouteSamplingMode). Stable [key]s are persisted. */
enum class RouteSamplingMode(val key:String) {
    /** A point each time you move the distance filter. The location provider does the filtering. */
    distance("distance"),
    /** A point every interval, moving or not. */
    time("time"),
    /** A point every interval, but only once you've moved the distance filter since the last one. */
    distanceAndTime("distance_and_time");

    val usesInterval get()=this!=distance
    val usesDistance get()=this!=time

    companion object {
        /** Unknown or missing values keep the original distance-based behaviour. */
        fun fromKey(key:String?)=entries.firstOrNull {it.key==key} ?: distance
    }
}

/** What to ask the fused location provider for. Kept free of Play services types so it can be unit tested. */
data class RouteLocationRequest(val highAccuracy:Boolean,val intervalMillis:Long,val minUpdateIntervalMillis:Long,
    val minUpdateDistanceMeters:Float,val maxUpdateDelayMillis:Long)

data class RouteSamplingPolicy(val mode:RouteSamplingMode=RouteSamplingMode.distance,val distanceMeters:Int=5,val intervalSeconds:Int=DEFAULT_INTERVAL_SECONDS) {
    init {
        require(distanceMeters>=0) {"Distance must not be negative."}
        require(intervalSeconds>0) {"Interval must be positive."}
    }
    val interval:Duration get()=Duration.ofSeconds(intervalSeconds.toLong())

    /**
     * Distance mode is unchanged from before time sampling existed. Time-based modes ask for one fix per interval
     * and no minimum displacement: Android's provider then powers GPS down between fixes, and the visit detector
     * keeps receiving fixes while you're parked. [RouteSampler] applies the distance gate for distance + time.
     */
    val locationRequest:RouteLocationRequest get()=when(mode) {
        RouteSamplingMode.distance->RouteLocationRequest(true,5_000L,1_000L,distanceMeters.toFloat(),10_000L)
        else->interval.toMillis().let {RouteLocationRequest(true,it,it,0f,0L)}
    }

    companion object {
        val INTERVAL_OPTIONS=listOf(10,30,60,120,300)
        const val DEFAULT_INTERVAL_SECONDS=120
    }
}

/**
 * Decides which delivered fixes become route points (iOS RouteSampler). Every fix still reaches visit detection;
 * this only chooses what is stored as the route. Pure, so the timing rules are unit tested.
 */
class RouteSampler(policy:RouteSamplingPolicy) {
    var policy:RouteSamplingPolicy=policy
        set(value) {
            if(value==field) return
            field=value
            // Shortening the interval brings the next sample forward; lengthening pushes it back.
            nextDue=lastKept?.time?.plus(value.interval)
        }
    var lastKept:TrackPoint?=null;private set
    /** When the next time-based point may be kept. null until the first usable fix. */
    var nextDue:Instant?=null;private set

    fun reset() {lastKept=null;nextDue=null}

    /** The fixes to store as route points, in time order. */
    fun process(points:List<TrackPoint>):List<TrackPoint> {
        val ordered=points.sortedBy {it.time}
        if(!policy.mode.usesInterval) {ordered.lastOrNull()?.let {lastKept=it};return ordered}
        return ordered.filter(::keep)
    }

    private fun keep(point:TrackPoint):Boolean {
        if(!TrackQuality.accepts(point)) return false
        lastKept?.let {if(point.time<=it.time) return false}
        val due=nextDue ?: point.time
        // The provider's fixes arrive about one interval apart, sometimes a little early. Without this slack an early
        // fix would be dropped and the route would skip to the one after, doubling the gap.
        if(point.time<due.minus(tolerance)) return false
        if(policy.mode==RouteSamplingMode.distanceAndTime) {
            lastKept?.let {if(it.coordinate.distanceTo(point.coordinate)<policy.distanceMeters) return false}
        }
        // Count from when the point was due, not when it arrived, so early fixes never shorten the average spacing.
        lastKept=point;nextDue=maxOf(due,point.time).plus(policy.interval)
        return true
    }

    private val tolerance:Duration get()=Duration.ofMillis(maxOf(500L,policy.interval.toMillis()/10))
}
