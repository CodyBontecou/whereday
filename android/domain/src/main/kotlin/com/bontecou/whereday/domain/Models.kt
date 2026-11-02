package com.bontecou.whereday.domain

import java.time.Instant
import java.time.LocalDate
import java.util.UUID
import kotlin.math.*

data class Coordinate(val latitude: Double, val longitude: Double) {
    init { require(latitude.isFinite() && latitude in -90.0..90.0); require(longitude.isFinite() && longitude in -180.0..180.0) }
    fun distanceTo(other: Coordinate): Double = SurfaceDistance.between(this,other)
}

data class TrackPoint(
    val id: String = UUID.randomUUID().toString(), val coordinate: Coordinate,
    val time: Instant, val accuracy: Double, val altitude: Double? = null,
    val speed: Double? = null, val outlier: Boolean = false,
    val countryCode: String? = null, val timeZone: String? = null,
    val countryResolutionAttemptedAt: Instant? = null,
)

enum class VisitSource { automatic, manual, imported }
enum class Confirmation { unconfirmed, confirmed, corrected }
data class Visit(
    val id: String = UUID.randomUUID().toString(), val coordinate: Coordinate,
    val arrivedAt: Instant, val departedAt: Instant? = null,
    val name: String? = null, val address: String? = null, val notes: String? = null,
    val source: VisitSource = VisitSource.automatic, val confirmation: Confirmation = Confirmation.unconfirmed,
    val originalCoordinate: Coordinate? = null, val originalName: String? = null, val originalAddress: String? = null,
    val detectedCoordinate: Coordinate? = null, val detectedName: String? = null, val detectedAddress: String? = null,
    val confirmedAt: Instant? = null, val updatedAt: Instant? = null, val placeSource: String? = null,
    val placeDistanceMeters: Double? = null, val countryCode: String? = null, val timeZone: String? = null,
    val departureInferred: Boolean = false,
    val countryResolutionAttemptedAt: Instant? = null,
) {
    init { require(departedAt == null || departedAt >= arrivedAt) }
    fun correct(coordinate: Coordinate, name: String?, address: String?, now: Instant) = copy(
        coordinate = coordinate, name = name, address = address, confirmation = Confirmation.corrected,
        originalCoordinate = originalCoordinate ?: this.coordinate,
        originalName = if (originalCoordinate == null) this.name else originalName,
        originalAddress = if (originalCoordinate == null) this.address else originalAddress,
        confirmedAt = confirmedAt ?: now, updatedAt = now, placeSource = "userEntered", placeDistanceMeters = null,
        countryCode = if (coordinate == this.coordinate) countryCode else null,
        timeZone = if (coordinate == this.coordinate) timeZone else null,
        countryResolutionAttemptedAt = if (coordinate == this.coordinate) countryResolutionAttemptedAt else null,
    )
    fun undoCorrection(now: Instant): Visit = originalCoordinate?.let {
        copy(coordinate = it, name = originalName, address = originalAddress, confirmation = Confirmation.unconfirmed,
            confirmedAt = null, updatedAt = now,
            placeSource = null, placeDistanceMeters = null,
            countryCode = if (it == coordinate) countryCode else null, timeZone = if (it == coordinate) timeZone else null,
            countryResolutionAttemptedAt = if (it == coordinate) countryResolutionAttemptedAt else null)
    } ?: this
}

data class SavedPlace(val id: String = UUID.randomUUID().toString(), val name: String, val coordinate: Coordinate, val address: String? = null, val radius: Double = 150.0) {
    init { require(name.isNotBlank()); require(radius in 25.0..10_000.0) }
}
data class RecordingSession(val id: String = UUID.randomUUID().toString(), val startedAt: Instant, val endedAt: Instant? = null, val name: String? = null, val notes: String? = null)
data class CountryStay(val id: String = UUID.randomUUID().toString(), val countryCode: String, val firstDay: LocalDate, val lastDay: LocalDate) {
    init { require(firstDay <= lastDay); require(countryCode in CountryRegions.all) }
}

object TrackQuality {
    fun accepts(point: TrackPoint) = point.accuracy.isFinite() && point.accuracy in 0.0..100.0
    fun flaggedIds(previous: List<TrackPoint>, next: TrackPoint): Set<String> {
        val last = previous.lastOrNull() ?: return emptySet()
        val flagged = mutableSetOf<String>()
        val seconds = (next.time.toEpochMilli() - last.time.toEpochMilli()) / 1000.0
        if (seconds > 0 && last.coordinate.distanceTo(next.coordinate) / seconds > 40) flagged += next.id
        previous.getOrNull(previous.lastIndex - 1)?.let { before ->
            if (before.coordinate.distanceTo(last.coordinate) > 100 && last.coordinate.distanceTo(next.coordinate) > 100 && before.coordinate.distanceTo(next.coordinate) < 30) flagged += last.id
        }
        return flagged
    }
    fun distance(points: List<TrackPoint>): Double = points.filterNot { it.outlier }.sortedBy { it.time }
        .zipWithNext().sumOf { (a, b) -> a.coordinate.distanceTo(b.coordinate) }
}
