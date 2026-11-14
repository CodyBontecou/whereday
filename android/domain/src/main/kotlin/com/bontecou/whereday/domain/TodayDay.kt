package com.bontecou.whereday.domain

import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** Port of the current iOS `TravelMode` speed bands (Shared/DistanceFormatter.swift). */
enum class TravelMode { walk, run, bike, drive, transit, fly;
    companion object {
        fun infer(averageSpeed: Double): TravelMode? {
            if(!averageSpeed.isFinite() || averageSpeed <= 0.3) return null
            return when {
                averageSpeed < 2.0 -> walk
                averageSpeed < 3.4 -> run
                averageSpeed < 8.0 -> bike
                averageSpeed < 45.0 -> drive
                averageSpeed < 70.0 -> transit
                else -> fly
            }
        }
        fun infer(meters: Double, seconds: Double): TravelMode? = if(seconds > 0) infer(meters / seconds) else null
    }
}

/**
 * Today's stays clipped to midnight and now, with the moves between them.
 * Port of `TodayDay` in the current iOS `TodayView.swift`; inks are indexes into the four place inks.
 */
data class TodayDay(val stays: List<Stay>, val entries: List<Entry>, val segments: List<Segment>, val away: Duration?) {
    data class Move(val distance: Double, val duration: Duration, val mode: TravelMode?)
    data class Stay(val visit: Visit?, val name: String, val start: Instant, val end: Instant, val current: Boolean, val ink: Int, val arrival: Move? = null) {
        /** Nil-visit stays are the stretch at Home before the first stop. */
        val id: String get() = visit?.id ?: "home-start"
        val since: Instant get() = visit?.arrivedAt ?: start
    }
    data class Segment(val id: String, val start: Double, val end: Double, val ink: Int)
    sealed interface Entry {
        val id: String
        data class StayEntry(val stay: Stay) : Entry { override val id get() = "stay-${stay.id}" }
        data class MoveEntry(override val id: String, val move: Move) : Entry
        data class Unrecorded(override val id: String, val duration: Duration) : Entry
    }

    val currentStay: Stay? get() = stays.lastOrNull { it.current }

    companion object {
        const val INKS = 4
        /** Stretches without a single point at least this long read as "Not recorded". */
        val unrecordedGapThreshold: Duration = Duration.ofMinutes(30)

        fun build(
            visits: List<Visit>, points: List<TrackPoint>, now: Instant, zone: ZoneId,
            name: (Visit) -> String, home: SavedPlace? = null, marksUnrecordedGaps: Boolean = false,
            isCurrent: (Visit) -> Boolean = { it.departedAt == null },
        ): TodayDay {
            val startOfDay = now.atZone(zone).toLocalDate().atStartOfDay(zone).toInstant()
            val kept = points.filter { !it.outlier }.sortedBy { it.time }
            fun inside(c: Coordinate) = home != null && home.coordinate.distanceTo(c) <= home.radius
            fun placeName(visit: Visit) = if(home != null && visit.name.isNullOrBlank() && inside(visit.coordinate)) home.name else name(visit)
            val inks = LinkedHashMap<String, Int>()
            fun ink(name: String) = inks.getOrPut(name) { inks.size % INKS }
            home?.let { ink(it.name) }
            val stays = visits.filter { it.arrivedAt <= now && (it.departedAt ?: now) >= startOfDay }.sortedBy { it.arrivedAt }.mapNotNull { visit ->
                val start = maxOf(visit.arrivedAt, startOfDay)
                val end = minOf(visit.departedAt ?: now, now)
                if(end <= start) null else placeName(visit).let { Stay(visit, it, start, end, isCurrent(visit), ink(it)) }
            }.toMutableList()
            if(home != null) homeStretch(home, stays.firstOrNull(), kept, startOfDay, now)?.let { (end, current) ->
                val first = stays.firstOrNull()
                if(first != null && end >= first.start && first.visit?.let { inside(it.coordinate) } == true) stays[0] = first.copy(start = startOfDay)
                else stays.add(0, Stay(null, home.name, startOfDay, end, current, ink(home.name)))
            }
            val entries = mutableListOf<Entry>()
            for(index in stays.indices) {
                if(index > 0) {
                    val gapStart = stays[index - 1].end
                    val gapEnd = stays[index].start
                    val gap = Duration.between(gapStart, gapEnd)
                    if(gap >= Duration.ofMinutes(1)) {
                        val window = kept.filter { it.time >= gapStart && it.time <= gapEnd }
                        if(marksUnrecordedGaps && window.isEmpty() && gap >= unrecordedGapThreshold) entries += Entry.Unrecorded("gap-${stays[index].id}", gap)
                        else {
                            val distance = pathDistance(window)
                            val move = Move(distance, gap, if(distance > 0) TravelMode.infer(distance, gap.toMillis() / 1000.0) else null)
                            stays[index] = stays[index].copy(arrival = move)
                            entries += Entry.MoveEntry("move-${stays[index].id}", move)
                        }
                    }
                }
                entries += Entry.StayEntry(stays[index])
            }
            val last = stays.lastOrNull()
            if(marksUnrecordedGaps && last != null && !last.current) {
                val lastPoint = kept.filter { it.time >= last.end }.maxOfOrNull { it.time }
                val silence = Duration.between(lastPoint ?: last.end, now)
                if(lastPoint == null && silence >= unrecordedGapThreshold) entries += Entry.Unrecorded("gap-end", silence)
            }
            val segments = stays.map { Segment(it.id, dayFraction(it.start, startOfDay), dayFraction(it.end, startOfDay), it.ink) }
            // Away runs from the first thing recorded today: time anywhere but Home, or between stays without one.
            val firstRecorded = listOfNotNull(stays.firstOrNull()?.start, kept.firstOrNull()?.time).minOrNull()
            val away = firstRecorded?.let { first ->
                val span = Duration.between(first, now).coerceAtLeast(Duration.ZERO)
                val present = stays.filter { stay -> home == null || (stay.visit?.let { inside(it.coordinate) } ?: true) }
                    .fold(Duration.ZERO) { sum, stay -> sum + Duration.between(stay.start, stay.end) }
                (span - present).coerceAtLeast(Duration.ZERO)
            }
            return TodayDay(stays, entries, segments, away)
        }

        internal fun homeStretch(home: SavedPlace, firstStay: Stay?, points: List<TrackPoint>, startOfDay: Instant, now: Instant): Pair<Instant, Boolean>? {
            if(firstStay != null && firstStay.start <= startOfDay.plusSeconds(60)) return null
            val limit = firstStay?.start ?: now
            val early = points.filter { it.time >= startOfDay && it.time < limit }
            fun inside(c: Coordinate) = home.coordinate.distanceTo(c) <= home.radius
            val first = early.firstOrNull() ?: return null
            if(!inside(first.coordinate)) return null
            val leftAt = early.indexOfFirst { !inside(it.coordinate) }
            if(leftAt >= 0) {
                val end = early[maxOf(0, leftAt - 1)].time
                return if(end > startOfDay.plusSeconds(60)) end to false else null
            }
            return if(firstStay == null) now to true else limit to false
        }

        fun dayFraction(instant: Instant, startOfDay: Instant): Double =
            (Duration.between(startOfDay, instant).toMillis() / 86_400_000.0).coerceIn(0.0, 1.0)

        fun pathDistance(window: List<TrackPoint>): Double = window.zipWithNext { a, b -> a.coordinate.distanceTo(b.coordinate) }.sum()
    }
}
