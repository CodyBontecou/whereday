package com.bontecou.whereday.domain

import java.time.Duration
import java.time.Instant

/** Captured summary timing; photo/visit scope is closed even when recording remains active. */
data class OutingMetrics(val startedAt:Instant,val effectiveEnd:Instant,val durationSeconds:Double,val distanceMeters:Double) {
    val averageSpeedMetersPerSecond:Double? get()=if(durationSeconds>0 && distanceMeters>0) distanceMeters/durationSeconds else null
    val range:HistoryRange get()=HistoryRange(startedAt,effectiveEnd.plusMillis(1))
    companion object {
        fun capture(session:RecordingSession,summary:PointSummary,now:Instant):OutingMetrics {
            val end=maxOf(session.startedAt,session.endedAt ?: summary.lastTime ?: now)
            val duration=Duration.between(session.startedAt,end)
            return OutingMetrics(session.startedAt,end,duration.seconds.toDouble()+duration.nano/1_000_000_000.0,summary.distanceMeters)
        }
    }
}
