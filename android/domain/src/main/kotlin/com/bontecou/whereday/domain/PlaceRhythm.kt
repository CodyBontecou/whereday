package com.bontecou.whereday.domain

import java.time.DayOfWeek
import java.time.Duration
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit
import kotlin.math.abs

/** Visits matched to saved places by match radius. Each visit belongs to its nearest containing place. */
class PlaceVisitIndex(places: List<SavedPlace>, visits: List<Visit>) {
    private val byPlace: Map<String, List<Visit>>
    val unmatched: List<Visit>

    init {
        val grouped = LinkedHashMap<String, MutableList<Visit>>()
        val loose = mutableListOf<Visit>()
        for(visit in visits) {
            var best: SavedPlace? = null
            var bestDistance = Double.POSITIVE_INFINITY
            for(place in places) {
                // One degree of latitude is ~111 km, so a cheap check skips most places.
                if(abs(place.coordinate.latitude - visit.coordinate.latitude) * 111_000 > place.radius) continue
                val distance = place.coordinate.distanceTo(visit.coordinate)
                if(distance <= place.radius && distance < bestDistance) { best = place; bestDistance = distance }
            }
            if(best != null) grouped.getOrPut(best.id) { mutableListOf() } += visit else loose += visit
        }
        byPlace = grouped
        unmatched = loose
    }

    /** Visits keep the caller's order (newest first for the journal's visit list). */
    fun visits(place: SavedPlace): List<Visit> = byPlace[place.id].orEmpty()

    companion object {
        /** A stable ink slot per place, cycling the place inks in the library's name order. */
        fun ink(place: SavedPlace, places: List<SavedPlace>): Int = places.indexOfFirst { it.id == place.id }.coerceAtLeast(0)
    }
}

/** When and how long you spend at one place, from its matched visits. */
class PlaceRhythm(visits: List<Visit>, now: Instant, private val zone: ZoneId, firstDayOfWeek: DayOfWeek = DayOfWeek.MONDAY) {
    enum class Part { mornings, midday, afternoons, evenings, nights }
    enum class Lean { weekend, weekday, mixed }
    data class Summary(val lean: Lean, val part: Part)

    val visitCount = visits.size
    val typicalStay: Duration?
    val timeThisYear: Duration
    /** Seconds present per weekday (`DayOfWeek.value - 1`) and hour. */
    private val cells = Array(7) { LongArray(24) }
    private val maximum: Long
    val weekdayOrder: List<DayOfWeek> = (0 until 7).map { firstDayOfWeek.plus(it.toLong()) }

    init {
        val durations = visits.mapNotNull { visit -> visit.departedAt?.takeIf { it > visit.arrivedAt }?.let { Duration.between(visit.arrivedAt, it) } }.sorted()
        typicalStay = durations.getOrNull(durations.size / 2)
        val yearStart = now.atZone(zone).toLocalDate().withDayOfYear(1).atStartOfDay(zone).toInstant()
        var thisYear = 0L
        for(visit in visits) {
            val end = minOf(visit.departedAt ?: now, now)
            if(end <= visit.arrivedAt) continue
            thisYear += maxOf(0L, Duration.between(maxOf(visit.arrivedAt, yearStart), end).seconds)
            // A week is enough to show the pattern without walking a stale open visit hour by hour.
            val rhythmEnd = minOf(end, visit.arrivedAt.plus(Duration.ofDays(7)))
            var cursor = visit.arrivedAt
            while(cursor < rhythmEnd) {
                val local = cursor.atZone(zone)
                val next = minOf(local.truncatedTo(ChronoUnit.HOURS).plusHours(1).toInstant(), rhythmEnd)
                cells[local.dayOfWeek.value - 1][local.hour] += Duration.between(cursor, next).seconds
                cursor = next
            }
        }
        timeThisYear = Duration.ofSeconds(thisYear)
        maximum = cells.maxOf { row -> row.max() }
    }

    /** 0 means never; 1–3 are thirds of the busiest hour. */
    fun level(day: DayOfWeek, hour: Int): Int {
        val value = cells[day.value - 1][hour]
        if(value <= 0 || maximum <= 0) return 0
        return minOf(3, (value.toDouble() / maximum * 3).toInt() + 1)
    }

    /** The busiest weekday and hour, the first in Monday-first order on ties. */
    val peak: Pair<DayOfWeek, Int>? get() {
        if(maximum <= 0) return null
        for(day in DayOfWeek.entries) for(hour in 0 until 24) if(cells[day.value - 1][hour] == maximum) return day to hour
        return null
    }

    /** An honest one-line read of the pattern, e.g. "mostly weekday evenings"; needs three visits. */
    val summary: Summary? get() {
        val peak = peak ?: return null
        if(visitCount < 3) return null
        val total = cells.sumOf { it.sum() }
        val weekend = cells[DayOfWeek.SATURDAY.value - 1].sum() + cells[DayOfWeek.SUNDAY.value - 1].sum()
        val part = when(peak.second) { in 5 until 11 -> Part.mornings; in 11 until 14 -> Part.midday; in 14 until 17 -> Part.afternoons; in 17 until 22 -> Part.evenings; else -> Part.nights }
        val share = if(total > 0) weekend.toDouble() / total else 0.5
        return Summary(when { share >= 0.6 -> Lean.weekend; share <= 0.15 -> Lean.weekday; else -> Lean.mixed }, part)
    }

    companion object {
        /** The waking-day window the grid draws, 07:00 through the 22:00 hour. */
        val HOURS = 7..22
        val LABELLED_HOURS = setOf(7, 11, 15, 19, 22)
    }
}

/**
 * What folding one saved place into another changes, worked out before the user confirms.
 *
 * Visits are tied to saved places by geometry (the nearest containing match radius) and by the
 * name copied onto them. A merge widens the kept place's radius to cover the removed circle, and
 * renames visits that carry the removed place's name.
 */
data class SavedPlaceMergePlan(
    val keptId: String, val removedId: String, val keptName: String, val removedName: String,
    /** Visits currently matched to the removed place by its radius. */
    val movedVisitIds: Set<String>,
    /** Visits whose stored name came from the removed place; they take the kept name. */
    val renamedVisitIds: Set<String>,
    val mergedRadius: Double,
    /** False when the removed circle is too far away to cover; renamed visits still take the new name. */
    val coversRemovedPlace: Boolean,
    val radiusGrows: Boolean,
) {
    val affectedVisitCount get() = (movedVisitIds + renamedVisitIds).size

    companion object {
        /** Largest radius the editor accepts; a merge never grows a place beyond it. */
        const val MAXIMUM_RADIUS = 10_000.0

        fun build(kept: SavedPlace, removed: SavedPlace, places: List<SavedPlace>, visits: List<Visit>): SavedPlaceMergePlan {
            require(kept.id != removed.id)
            val moved = PlaceVisitIndex(places, visits).visits(removed).mapTo(mutableSetOf()) { it.id }
            val oldName = removed.name.trim()
            val renamed = visits.filter { visit ->
                val named = matches(visit.name, oldName) && (visit.placeSource == "userEntered" || visit.id in moved)
                named || (visit.id in moved && matches(visit.detectedName, oldName))
            }.mapTo(mutableSetOf()) { it.id }
            val needed = kept.coordinate.distanceTo(removed.coordinate) + removed.radius
            val radius = minOf(MAXIMUM_RADIUS, maxOf(kept.radius, kotlin.math.ceil(needed)))
            return SavedPlaceMergePlan(kept.id, removed.id, kept.name.trim(), oldName, moved, renamed, radius, needed <= MAXIMUM_RADIUS, radius > kept.radius)
        }

        fun matches(value: String?, name: String): Boolean {
            val trimmed = value?.trim().orEmpty()
            return trimmed.isNotEmpty() && name.isNotEmpty() && trimmed.equals(name, ignoreCase = true)
        }

        /** The kept place after the merge: its kind, address and note fill gaps from the removed one. */
        fun merged(plan: SavedPlaceMergePlan, kept: SavedPlace, removed: SavedPlace): SavedPlace {
            val notes = listOfNotNull(kept.notes, removed.notes).map { it.trim() }.filter { it.isNotEmpty() }
            return kept.copy(radius = plan.mergedRadius, kind = kept.kind ?: removed.kind,
                address = kept.address?.takeIf { it.isNotBlank() } ?: removed.address,
                notes = notes.takeIf { it.isNotEmpty() }?.joinToString("\n\n"))
        }

        /** Visits renamed by the merge, with their update time set to [now]. */
        fun renamed(plan: SavedPlaceMergePlan, kept: SavedPlace, visits: List<Visit>, now: Instant): List<Visit> = visits.filter { it.id in plan.renamedVisitIds }.mapNotNull { visit ->
            var next = visit
            if(matches(visit.name, plan.removedName)) {
                next = next.copy(name = plan.keptName, address = if(visit.placeSource == "userEntered" && kept.address != null) kept.address else visit.address)
            }
            if(matches(visit.detectedName, plan.removedName)) next = next.copy(detectedName = plan.keptName)
            if(next == visit) null else next.copy(updatedAt = now)
        }
    }
}
