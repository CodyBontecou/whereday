package com.bontecou.whereday.domain

import java.time.Duration
import java.time.Instant

/** LocationManager's duplicate-stop rules. The chronological survivor retains its identity,
 * coordinates and correction provenance; metadata only fills missing target values. */
object VisitReconciliation {
    fun shouldMerge(target:Visit,source:Visit,now:Instant,distance:(Coordinate,Coordinate)->Double={a,b->a.distanceTo(b)}):Boolean {
        if(target.id==source.id || target.source!=VisitSource.automatic || source.source!=VisitSource.automatic || distance(target.coordinate,source.coordinate)>100.0) return false
        if(Duration.between(target.arrivedAt,source.arrivedAt).abs()<=Duration.ofMinutes(15)) return true
        val targetEnd=target.departedAt ?: now;val sourceEnd=source.departedAt ?: now
        val gap=when {
            targetEnd<source.arrivedAt->Duration.between(targetEnd,source.arrivedAt)
            sourceEnd<target.arrivedAt->Duration.between(sourceEnd,target.arrivedAt)
            else->Duration.ZERO
        }
        return gap<=Duration.ofMinutes(30)
    }
    fun merge(target:Visit,source:Visit):Visit {
        val sourceNotes=source.notes?.trim()?.takeIf {it.isNotEmpty()}
        val targetNotes=target.notes?.trim()?.takeIf {it.isNotEmpty()}
        val notes=when {
            sourceNotes==null->target.notes
            targetNotes==null->source.notes
            sourceNotes!=targetNotes->"$targetNotes\n\n$sourceNotes"
            else->target.notes
        }
        return target.copy(arrivedAt=minOf(target.arrivedAt,source.arrivedAt),
            departedAt=if(target.departedAt==null || source.departedAt==null) null else maxOf(target.departedAt,source.departedAt),
            departureInferred=target.departureInferred || source.departureInferred,
            name=target.name ?: source.name,address=target.address ?: source.address,notes=notes)
    }
    fun inferredDeparture(visit:Visit,nextArrival:Instant?,now:Instant)=maxOf(visit.arrivedAt,nextArrival ?: now)
}
