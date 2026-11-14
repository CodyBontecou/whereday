package com.bontecou.whereday.domain

import java.time.Instant
import java.time.ZoneId
import kotlin.math.abs
import kotlin.math.floor

enum class PlacesFilter { mostVisited, recent, alphabetical, unsaved }

/** A spot visited at least three times with no saved place around it. */
data class UnsavedPlaceSuggestion(val id: String, val name: String, val address: String?, val coordinate: Coordinate, val visitCount: Int, val lastVisit: Instant) {
    /** This spot's visits that still match no saved place, in the caller's order. */
    fun visits(places: List<SavedPlace>, visits: List<Visit>): List<Visit> = PlaceVisitIndex(places, visits).unmatched.filter { gridKey(it) == id }

    companion object {
        private const val STEP = 0.0014
        // Swift `rounded()` rounds halves away from zero.
        private fun schoolbook(value: Double) = (if(value < 0) -floor(abs(value) + 0.5) else floor(value + 0.5)).toLong()

        /** The ~150 m grid cell a visit falls in; suggestions are keyed by it. */
        fun gridKey(visit: Visit): String = "${schoolbook(visit.coordinate.latitude / STEP)}:${schoolbook(visit.coordinate.longitude / STEP)}"

        /** Distinct grid cells among visits that match no saved place. */
        fun distinctSpotCount(unmatched: List<Visit>): Int = unmatched.mapTo(HashSet(), ::gridKey).size

        /** Visits are grouped on a ~150 m grid; [visits] are expected newest first. */
        fun suggestions(visits: List<Visit>, name: (Visit) -> String, minimumVisits: Int = 3, limit: Int = 12): List<UnsavedPlaceSuggestion> {
            val groups = LinkedHashMap<String, MutableList<Visit>>()
            for(visit in visits) groups.getOrPut(gridKey(visit)) { mutableListOf() } += visit
            return groups.mapNotNull { (key, group) ->
                if(group.size < minimumVisits) return@mapNotNull null
                val latest = group.first()
                // The most frequent name, the first in code-point order on ties.
                val counts = group.groupingBy(name).eachCount()
                val label = counts.entries.sortedWith(compareByDescending<Map.Entry<String, Int>> { it.value }.thenBy { it.key }).first().key
                UnsavedPlaceSuggestion(key, label, group.firstNotNullOfOrNull { it.address },
                    Coordinate(group.sumOf { it.coordinate.latitude } / group.size, group.sumOf { it.coordinate.longitude } / group.size), group.size, latest.arrivedAt)
            }.sortedWith(compareByDescending<UnsavedPlaceSuggestion> { it.visitCount }.thenByDescending { it.lastVisit }.thenBy { it.id }).take(limit)
        }
    }
}

/** Saved places with their matched visits, plus frequent spots that aren't saved yet. */
class PlacesLibrary(val places: List<SavedPlace>, visits: List<Visit>, name: (Visit) -> String) {
    val index = PlaceVisitIndex(places, visits)
    val suggestions = UnsavedPlaceSuggestion.suggestions(index.unmatched, name)
    /** Saved places with at least one visit, plus every distinct unsaved spot you've stopped at. */
    val visitedPlaceCount = places.count { index.visits(it).isNotEmpty() } + UnsavedPlaceSuggestion.distinctSpotCount(index.unmatched)

    /** [matches] filters by the caller's locale-aware search; [places] arrive in A–Z order. Home is pinned first. */
    fun sorted(filter: PlacesFilter, matches: (SavedPlace) -> Boolean = { true }): List<SavedPlace> {
        val matching = places.filter(matches)
        val ordered = when(filter) {
            PlacesFilter.alphabetical, PlacesFilter.unsaved -> matching
            PlacesFilter.mostVisited -> matching.sortedByDescending { index.visits(it).size }
            PlacesFilter.recent -> matching.sortedByDescending { index.visits(it).maxOfOrNull { visit -> visit.arrivedAt } ?: Instant.MIN }
        }
        return ordered.filter { it.kind == SavedPlaceKind.home } + ordered.filter { it.kind != SavedPlaceKind.home }
    }

    companion object {
        /** Home counts the distinct days you were there, not individual visits. */
        fun dayCount(visits: List<Visit>, zone: ZoneId): Int = visits.mapTo(HashSet()) { it.arrivedAt.atZone(zone).toLocalDate() }.size
    }
}
