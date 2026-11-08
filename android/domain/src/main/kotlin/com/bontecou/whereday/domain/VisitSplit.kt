package com.bontecou.whereday.domain

import java.time.Duration
import java.time.Instant

enum class VisitSplitProblem { time, placeName, coordinate, samePlace }

class InvalidVisitSplit(val problem: VisitSplitProblem) : IllegalArgumentException(when (problem) {
    VisitSplitProblem.time -> "Choose a split time at least a minute after arrival and before departure."
    VisitSplitProblem.placeName -> "Name the place to split the visit."
    VisitSplitProblem.coordinate -> "Choose a location or enter valid coordinates."
    VisitSplitProblem.samePlace -> "Choose a different place for the second visit."
})

/** The second place a split visit moved on to, as chosen from nearby businesses, the map or saved places. */
data class SplitPlace(
    val name: String, val address: String?, val coordinate: Coordinate,
    val placeSource: String, val distanceMeters: Double? = null,
)

/**
 * ManualVisitStore.splitVisit on iOS: neighbouring storefronts are often detected as one stop.
 * The first part keeps its identity, place and notes and ends at the split; a new corrected visit
 * covers the rest at the chosen place. Both are reviewed, so reconciliation keeps them apart.
 */
object VisitSplit {
    val minimumPart: Duration = Duration.ofMinutes(1)

    fun canSplit(visit: Visit): Boolean {
        val departure = visit.departedAt ?: return false
        return Duration.between(visit.arrivedAt, departure) >= minimumPart.multipliedBy(2)
    }

    /** Allowed split times, keeping at least a minute on each side; null for open or too-short visits. */
    fun range(visit: Visit): ClosedRange<Instant>? {
        if (!canSplit(visit)) return null
        return visit.arrivedAt.plus(minimumPart)..visit.departedAt!!.minus(minimumPart)
    }

    /** The default split: the midpoint, rounded down to the minute and kept inside [range]. */
    fun suggestedTime(visit: Visit): Instant? {
        val range = range(visit) ?: return null
        val departure = visit.departedAt!!
        val middle = visit.arrivedAt.plus(Duration.between(visit.arrivedAt, departure).dividedBy(2))
        val rounded = Instant.ofEpochSecond(middle.epochSecond - Math.floorMod(middle.epochSecond, 60L))
        return rounded.coerceIn(range.start, range.endInclusive)
    }

    fun split(visit: Visit, at: Instant, place: SplitPlace, secondId: String, now: Instant): Pair<Visit, Visit> {
        val range = range(visit) ?: throw InvalidVisitSplit(VisitSplitProblem.time)
        if (at !in range) throw InvalidVisitSplit(VisitSplitProblem.time)
        val name = place.name.trim()
        if (name.isEmpty()) throw InvalidVisitSplit(VisitSplitProblem.placeName)
        val coordinate = place.coordinate
        if (!coordinate.latitude.isFinite() || !coordinate.longitude.isFinite() ||
            coordinate.latitude !in -90.0..90.0 || coordinate.longitude !in -180.0..180.0) {
            throw InvalidVisitSplit(VisitSplitProblem.coordinate)
        }
        if (VisitReconciliation.nameKey(visit.name ?: visit.address) == VisitReconciliation.nameKey(name)) {
            throw InvalidVisitSplit(VisitSplitProblem.samePlace)
        }
        val second = Visit(
            id = secondId, coordinate = coordinate, arrivedAt = at, departedAt = visit.departedAt,
            name = name, address = place.address?.trim()?.ifEmpty { null },
            source = visit.source, confirmation = Confirmation.corrected,
            // Undo correction on the second part returns it to what detection saw.
            originalCoordinate = visit.detectedCoordinate ?: visit.coordinate,
            originalName = visit.detectedName, originalAddress = visit.detectedAddress,
            detectedCoordinate = visit.detectedCoordinate, detectedName = visit.detectedName, detectedAddress = visit.detectedAddress,
            confirmedAt = now, updatedAt = now,
            placeSource = place.placeSource, placeDistanceMeters = place.distanceMeters?.takeIf { it.isFinite() && it >= 0 },
            countryCode = visit.countryCode, timeZone = visit.timeZone,
            departureInferred = visit.departureInferred,
            countryResolutionAttemptedAt = visit.countryResolutionAttemptedAt,
        )
        // The first part now has an observed end: the user just placed it.
        val first = visit.copy(
            departedAt = at, departureInferred = false,
            confirmation = if (visit.confirmation == Confirmation.unconfirmed) Confirmation.confirmed else visit.confirmation,
            confirmedAt = if (visit.confirmation == Confirmation.unconfirmed) now else visit.confirmedAt,
            updatedAt = now,
        )
        return first to second
    }
}
