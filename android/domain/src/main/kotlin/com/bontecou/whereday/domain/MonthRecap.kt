package com.bontecou.whereday.domain

import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneId

/** Metres moved on each day of a month, from consecutive kept route points, streamed in time order. */
class MonthDistance(val month: YearMonth, private val zone: ZoneId) {
    val perDay = DoubleArray(month.lengthOfMonth())
    /** Day indexes (0-based) with at least one kept point. */
    val pointDays = HashSet<Int>()
    private var previous: TrackPoint? = null
    private var previousDate: LocalDate? = null

    fun add(point: TrackPoint) {
        if(point.outlier) return
        val date = point.time.atZone(zone).toLocalDate()
        if(YearMonth.from(date) != month) return
        val index = date.dayOfMonth - 1
        pointDays += index
        // Movement is attributed to the day it ends on, only within one day.
        previous?.let { if(previousDate == date) perDay[index] += it.coordinate.distanceTo(point.coordinate) }
        previous = point; previousDate = date
    }
}

/** One month's totals, computed from recorded visits and route points, as iOS `MonthRecap`. */
class MonthRecap(visits: List<Visit>, distance: MonthDistance?, val month: YearMonth, zone: ZoneId, name: (Visit) -> String,
    now: Instant, locality: (Coordinate) -> String? = { null }) {
    data class TopPlace(val name: String, val duration: Duration)
    enum class Closing { cities, countries, visits }

    val dayCount = month.lengthOfMonth()
    val visitCount: Int
    val placeCount: Int
    val countryCount: Int
    val cityCount: Int
    val newPlaces: Int
    val recordedDays: Int
    val visitsPerDay: IntArray
    val distancePerDay: DoubleArray = distance?.perDay?.copyOf() ?: DoubleArray(dayCount)
    val totalDistance = distancePerDay.sum()
    val biggestDay: LocalDate?
    val topPlaces: List<TopPlace>

    init {
        val start = month.atDay(1).atStartOfDay(zone).toInstant()
        val end = month.plusMonths(1).atDay(1).atStartOfDay(zone).toInstant()
        val inMonth = visits.filter { it.arrivedAt < end && (it.departedAt ?: now) > start }
        visitCount = inMonth.size
        placeCount = inMonth.mapTo(HashSet(), name).size
        countryCount = inMonth.mapNotNullTo(HashSet()) { it.countryCode }.size
        cityCount = inMonth.mapNotNullTo(HashSet()) { locality(it.coordinate) }.size
        val perDay = Array(dayCount) { HashSet<String>() }
        val durations = LinkedHashMap<String, Long>()
        for(visit in inMonth) {
            val from = maxOf(visit.arrivedAt, start)
            val until = minOf(visit.departedAt ?: now, end, now)
            val label = name(visit)
            durations[label] = (durations[label] ?: 0L) + maxOf(0L, Duration.between(from, until).seconds)
            perDay.getOrNull(from.atZone(zone).dayOfMonth - 1)?.add(label)
        }
        visitsPerDay = IntArray(dayCount) { perDay[it].size }
        recordedDays = (0 until dayCount).count { visitsPerDay[it] > 0 || distance?.pointDays?.contains(it) == true }
        val ranking = if(totalDistance > 0) distancePerDay.toList() else visitsPerDay.map { it.toDouble() }
        // The first day with the largest value; Swift's `max(by:)` keeps the first of equals.
        var best = -1
        for(i in ranking.indices) if(best < 0 || ranking[i] > ranking[best]) best = i
        biggestDay = if(best >= 0 && ranking[best] > 0) month.atDay(best + 1) else null
        topPlaces = durations.entries.sortedByDescending { it.value }.take(3).map { TopPlace(it.key, Duration.ofSeconds(it.value)) }
        // A place is new when its first-ever visit falls in this month.
        val firstSeen = HashMap<String, Instant>()
        for(visit in visits) { val label = name(visit); val seen = firstSeen[label]; if(seen == null || visit.arrivedAt < seen) firstSeen[label] = visit.arrivedAt }
        newPlaces = firstSeen.values.count { it >= start && it < end }
    }

    val hasDistance get() = totalDistance > 0

    /** Distance per day when route points exist, otherwise places visited per day. */
    val barValues: List<Double> get() = if(hasDistance) distancePerDay.toList() else visitsPerDay.map { it.toDouble() }

    /** The top fifth of days are highlighted. */
    val highlightThreshold: Double get() {
        val sorted = barValues.filter { it > 0 }.sortedDescending()
        if(sorted.isEmpty()) return Double.POSITIVE_INFINITY
        return sorted[minOf(sorted.size - 1, maxOf(0, sorted.size / 5))]
    }

    /** Which closing line applies: cities when known, then countries, then visits. */
    val closing: Pair<Closing, Int> get() = when {
        cityCount > 0 -> Closing.cities to cityCount
        countryCount > 0 -> Closing.countries to countryCount
        else -> Closing.visits to visitCount
    }
}
