package com.bontecou.whereday.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/** Database windows are half-open; adjacent days never duplicate the midnight fix. */
data class HistoryRange(val from: Instant, val until: Instant) {
    init { require(from < until) }
    fun contains(instant: Instant) = instant >= from && instant < until
    companion object {
        fun day(day: LocalDate, zone: ZoneId) = HistoryRange(day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant())
        fun days(first: LocalDate, last: LocalDate, zone: ZoneId): HistoryRange {
            require(first <= last)
            return HistoryRange(first.atStartOfDay(zone).toInstant(), last.plusDays(1).atStartOfDay(zone).toInstant())
        }
        val all = HistoryRange(Instant.ofEpochMilli(Long.MIN_VALUE), Instant.ofEpochMilli(Long.MAX_VALUE))
    }
}

data class PointSummary(
    val count: Long = 0, val outlierCount: Long = 0, val distanceMeters: Double = 0.0,
    val firstTime: Instant? = null, val lastTime: Instant? = null,
) {
    val duration: Duration get() = if(firstTime != null && lastTime != null) Duration.between(firstTime,lastTime) else Duration.ZERO
    val averageSpeed: Double? get() = duration.toMillis().takeIf { it > 0 }?.let { distanceMeters * 1000 / it }
}

/** Accepts chronologically ordered fixes. Memory stays constant regardless of history size. */
class PointSummaryBuilder {
    private var summary = PointSummary()
    private var previousGood: TrackPoint? = null
    fun add(point: TrackPoint) {
        require(summary.lastTime?.let { point.time >= it } != false) { "Points must be chronological" }
        val distance = if(!point.outlier) previousGood?.coordinate?.distanceTo(point.coordinate) ?: 0.0 else 0.0
        summary=summary.copy(count=summary.count+1,outlierCount=summary.outlierCount+if(point.outlier) 1 else 0,
            distanceMeters=summary.distanceMeters+distance,firstTime=summary.firstTime ?: point.time,lastTime=point.time)
        if(!point.outlier) previousGood=point
    }
    fun build() = summary
}

/** Fixed target indices include both endpoints and are independent of batch boundaries. */
class UniformPointSampler(total: Long, val limit: Int = 2500) {
    init { require(total >= 0 && limit >= 2) }
    private val targets = if(total <= limit) LongArray(total.toInt()) { it.toLong() }
        else LongArray(limit) { index -> ((total-1).toDouble()*index/(limit-1)).toLong() }
    private var index=0L
    private var next=0
    private val selected=ArrayList<TrackPoint>(targets.size)
    fun add(point: TrackPoint) {
        addSelected(point)
    }
    /** Reports selection without copying the retained point list on every streamed input. */
    fun addSelected(point:TrackPoint):Boolean {
        val take=next < targets.size && index == targets[next]
        if(take) {selected.add(point);next++}
        index++
        return take
    }
    fun points(): List<TrackPoint> = selected.toList()
}

data class HistoryWindow(val range: HistoryRange, val points: List<TrackPoint>, val summary: PointSummary, val visits: List<Visit>, val sessions: List<RecordingSession>, val inferred: List<InferredOutingHeader> = emptyList(),val endpoints:RouteEndpoints=RouteEndpoints())
data class OutingDetail(val session: RecordingSession, val points: List<TrackPoint>, val summary: PointSummary, val visits: List<Visit>,val inferred: Boolean=false,val endpoints:RouteEndpoints=RouteEndpoints())
data class ActiveRecordingMap(val session:RecordingSession,val points:List<TrackPoint>,val count:Long,val endpoints:RouteEndpoints,val segments:List<List<TrackPoint>>)
data class InferredOutingHeader(val id: String,val startedAt: Instant,val endedAt: Instant,val summary: PointSummary)

/** Builds legacy outings without retaining their raw point arrays in timeline memory. */
class StreamingOutingInference(private val sessions: List<RecordingSession>,private val rules: InferenceRules) {
    private var first: TrackPoint?=null
    private var last: TrackPoint?=null
    private var summary=PointSummaryBuilder()
    private val results=mutableListOf<InferredOutingHeader>()
    fun add(point: TrackPoint) {
        if(sessions.any { point.time>=it.startedAt && (it.endedAt==null || point.time<=it.endedAt) }) return
        if(last?.let { Duration.between(it.time,point.time)>rules.gap }==true) flush()
        if(first==null) first=point
        summary.add(point); last=point
    }
    private fun flush() {
        val start=first ?: return
        val end=last!!
        val stats=summary.build()
        if(stats.count>=rules.minimumPoints && stats.duration>=rules.minimumDuration) results.add(InferredOutingHeader("inferred:${start.id}",start.time,end.time,stats))
        first=null; last=null; summary=PointSummaryBuilder()
    }
    fun finish(): List<InferredOutingHeader> { flush(); return results.toList() }
}
