package com.bontecou.whereday.domain

import java.time.Duration
import java.time.Instant

/** A stored stay that a new stop would overlap, and how saving the stop trims it (iOS VisitOverlapResolution). */
data class VisitOverlapResolution(val visit:Visit,val change:Change,
    /** How long the two overlap, measured against now for a stay still in progress. */
    val overlap:Duration) {
    sealed interface Change {
        /** The earlier stay ends where the new stop begins. */
        data class EndsAt(val at:Instant):Change
        /** The later stay begins where the new stop ends. */
        data class StartsAt(val at:Instant):Change
    }

    /** The stored stay after the trim, as iOS createManualVisitTrimmingOverlaps writes it. */
    fun trimmed(now:Instant):Visit = when(change) {
        is Change.EndsAt->visit.copy(departedAt=change.at,departureInferred=false,updatedAt=now)
        is Change.StartsAt->visit.copy(arrivedAt=change.at,updatedAt=now)
    }
}

/** What saving a stop over the given times would do to stored stays (iOS VisitOverlapCheck). */
sealed interface VisitOverlapCheck {
    data object None:VisitOverlapCheck
    /** Every overlapped stay can be trimmed to make room. */
    data class Trims(val resolutions:List<VisitOverlapResolution>):VisitOverlapCheck
    /** This stay sits inside the new stop (or the new stop is open-ended over it); it cannot be trimmed. */
    data class Conflict(val visit:Visit):VisitOverlapCheck
}

/**
 * Port of iOS ManualVisitStore.overlapCheck: checks stored stays (manual and recorded alike) against a new
 * stop's times. Adjacent stays that share a boundary do not overlap; an identical zero-length record does.
 */
object VisitOverlaps {
    /** iOS ManualVisitStore.overlappingVisits: open stays (null departure) run indefinitely. */
    fun overlaps(candidate:Visit,arrivedAt:Instant,departedAt:Instant?):Boolean {
        val end=departedAt ?: Instant.MAX
        val candidateEnd=candidate.departedAt ?: Instant.MAX
        return (candidate.arrivedAt<end && candidateEnd>arrivedAt) || (candidate.arrivedAt==arrivedAt && candidateEnd==end)
    }

    fun check(stored:List<Visit>,arrivedAt:Instant,departedAt:Instant?,excludingId:String?=null,now:Instant):VisitOverlapCheck {
        if(departedAt!=null && departedAt<arrivedAt) return VisitOverlapCheck.None
        val candidates=stored.filter {it.id!=excludingId && overlaps(it,arrivedAt,departedAt)}
        if(candidates.isEmpty()) return VisitOverlapCheck.None

        val resolutions=ArrayList<VisitOverlapResolution>()
        for(candidate in candidates.sortedWith(compareBy<Visit> {it.arrivedAt}.thenBy {it.id})) {
            val candidateEnd=candidate.departedAt ?: Instant.MAX
            val change=when {
                candidate.arrivedAt<arrivedAt->VisitOverlapResolution.Change.EndsAt(arrivedAt)
                departedAt!=null && candidateEnd>departedAt && candidate.arrivedAt<departedAt->VisitOverlapResolution.Change.StartsAt(departedAt)
                else->return VisitOverlapCheck.Conflict(candidate)
            }
            val overlapStart=maxOf(candidate.arrivedAt,arrivedAt)
            val overlapEnd=minOf(candidate.departedAt ?: now,departedAt ?: now)
            val overlap=Duration.between(overlapStart,overlapEnd).takeUnless {it.isNegative} ?: Duration.ZERO
            resolutions+=VisitOverlapResolution(candidate,change,overlap)
        }

        // Two stays cannot both end at the new arrival without one swallowing the other.
        val endings=resolutions.filter {it.change is VisitOverlapResolution.Change.EndsAt}
        val startings=resolutions.filter {it.change is VisitOverlapResolution.Change.StartsAt}
        if(endings.size>1) return VisitOverlapCheck.Conflict(endings[1].visit)
        if(startings.size>1) return VisitOverlapCheck.Conflict(startings[0].visit)
        return VisitOverlapCheck.Trims(resolutions)
    }

    /** Whole overlap minutes as the iOS notice shows them, never below one. */
    fun noticeMinutes(resolution:VisitOverlapResolution):Long = maxOf(1L,Math.round(resolution.overlap.toMillis()/60_000.0))
}
