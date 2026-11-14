package com.bontecou.whereday.domain

import java.time.Duration
import java.time.Instant
import java.util.UUID

/** Pure transition engine. Persist [DwellState] and emitted changes in one transaction. */
data class DwellState(
    val anchor: Coordinate? = null, val candidateSince: Instant? = null,
    val lastTime: Instant? = null, val visitId: String? = null,
    val departureSince: Instant? = null,
    val nextAnchor: Coordinate? = null, val nextCandidateSince: Instant? = null,
)
data class DwellResult(val state: DwellState, val arrived: Visit? = null, val departedId: String? = null, val departedAt: Instant? = null)
class VisitDetector(
    private val radiusMeters: Double = 125.0,
    private val dwell: Duration = Duration.ofMinutes(5),
    private val departureDwell: Duration = Duration.ofMinutes(2),
    private val maximumGap: Duration = Duration.ofMinutes(20),
) {
    fun consume(state: DwellState, point: TrackPoint): DwellResult {
        if (!TrackQuality.accepts(point) || point.outlier || state.lastTime?.let { point.time <= it } == true) return DwellResult(state)
        val gap = state.lastTime?.let { Duration.between(it, point.time) > maximumGap } == true
        if (gap) {
            // An unobserved interval is not evidence of continued presence.
            return DwellResult(candidate(point), departedId = state.visitId, departedAt = state.lastTime)
        }
        val anchor = state.anchor ?: return DwellResult(candidate(point))
        val distance = anchor.distanceTo(point.coordinate)
        val inside = distance <= radiusMeters + point.accuracy.coerceAtMost(50.0)
        if (state.visitId == null) {
            if (!inside) return DwellResult(candidate(point))
            if (Duration.between(state.candidateSince ?: point.time, point.time) < dwell) return DwellResult(state.copy(lastTime = point.time))
            val visit = Visit(id = UUID.randomUUID().toString(), coordinate = anchor, arrivedAt = state.candidateSince ?: point.time, detectedCoordinate = anchor)
            return DwellResult(state.copy(lastTime = point.time, visitId = visit.id), arrived = visit)
        }
        if (inside) return DwellResult(state.copy(lastTime = point.time, departureSince = null, nextAnchor = null, nextCandidateSince = null))
        val since = state.departureSince ?: point.time
        // Evidence outside the old place can also support a new stop. Keep its own spatial
        // anchor so travel between several locations never becomes an invented long dwell.
        val continuing = state.nextAnchor?.distanceTo(point.coordinate)?.let { it <= radiusMeters + point.accuracy.coerceAtMost(50.0) } == true
        val nextAnchor = if (continuing) state.nextAnchor!! else point.coordinate
        val nextSince = if (continuing) state.nextCandidateSince ?: point.time else point.time
        if (Duration.between(since, point.time) < departureDwell) return DwellResult(state.copy(
            lastTime = point.time, departureSince = since, nextAnchor = nextAnchor, nextCandidateSince = nextSince))
        val next = DwellState(anchor = nextAnchor, candidateSince = nextSince, lastTime = state.lastTime)
        // A delayed batch may establish both transitions; emit both for one atomic commit.
        return consume(next, point).copy(departedId = state.visitId, departedAt = since)
    }
    private fun candidate(point: TrackPoint) = DwellState(point.coordinate, point.time, point.time)
}
