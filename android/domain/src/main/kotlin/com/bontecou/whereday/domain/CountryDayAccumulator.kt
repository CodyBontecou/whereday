package com.bontecou.whereday.domain

import java.time.*

enum class CountryDayPeriod(val title: String) {
    year("This year"), rolling("Last 365 days"), all("All time");
    fun bounds(today: LocalDate): CountryDayBounds = when (this) {
        year -> CountryDayBounds(today.withDayOfYear(1), today.withMonth(12).withDayOfMonth(31))
        rolling -> CountryDayBounds(today.minusDays(364), null)
        all -> CountryDayBounds()
    }
}

data class CountryDayBounds(val first: LocalDate? = null, val last: LocalDate? = null) {
    init { require(first == null || last == null || first <= last) }
    operator fun contains(day: LocalDate) = (first == null || day >= first) && (last == null || day <= last)
}

/** Memory depends on unique country dates, never the number of GPS fixes. */
class CountryDayAccumulator(private val now: Instant, private val bounds: CountryDayBounds = CountryDayBounds()) {
    private val values = mutableMapOf<Pair<String, LocalDate>, CountryPresence>()
    private val zones = mutableMapOf<String, ZoneId?>()
    var unresolvedPoints: Long = 0; private set
    var unresolvedVisits: Long = 0; private set

    private fun zone(code: String?, identifier: String?): ZoneId? {
        if (code == null || identifier == null) return null
        return zones.getOrPut(identifier) { runCatching { ZoneId.of(identifier) }.getOrNull() }
    }
    fun add(point: TrackPoint) {
        if (point.outlier || point.time > now) return
        val code=CountryRegions.normalize(point.countryCode)
        val zone = zone(code, point.timeZone)
        if (zone == null) { unresolvedPoints++; return }
        add(code!!, point.time.atZone(zone).toLocalDate(), false)
    }
    fun add(visit: Visit) {
        if (visit.arrivedAt > now) return
        val code=CountryRegions.normalize(visit.countryCode)
        val zone = zone(code, visit.timeZone)
        if (zone == null) { unresolvedVisits++; return }
        val start = visit.arrivedAt.atZone(zone).toLocalDate()
        val end = if (visit.departedAt != null && !visit.departureInferred) {
            val exclusiveEnd = if (visit.departedAt > visit.arrivedAt) visit.departedAt.minusMillis(1) else visit.arrivedAt
            minOf(exclusiveEnd, now).atZone(zone).toLocalDate()
        } else start
        addRange(code!!, start, end, false)
    }
    fun add(stay: CountryStay) = addRange(stay.countryCode, stay.firstDay, stay.lastDay, true)
    private fun addRange(code: String, first: LocalDate, last: LocalDate, manual: Boolean) {
        var day = maxOf(first, bounds.first ?: first)
        val end = minOf(last, bounds.last ?: last)
        while (day <= end) {
            add(code, day, manual)
            if (day == end) break
            day = day.plusDays(1)
        }
    }
    private fun add(code: String, day: LocalDate, manual: Boolean) {
        if (day !in bounds) return
        val key = code to day
        val old = values[key]
        values[key] = CountryPresence(code, day, old?.recorded == true || !manual, old?.manual == true || manual)
    }
    fun days(): List<CountryPresence> = values.values.sortedWith(compareBy({ it.countryCode }, { it.day }))
}

fun CountryPresence.sourceLabel() = if (recorded && manual) "Recorded + manual" else if (manual) "Manual" else "Recorded"

/** Matches the iOS country-day CSV contract, including CRLF and provenance. */
fun countryDaysCsv(days: List<CountryPresence>): String = buildString {
    append("country_code,local_date,source\r\n")
    days.sortedWith(compareBy({ it.countryCode }, { it.day })).forEach {
        append(it.countryCode).append(',').append(it.day).append(',')
        append(if (it.recorded && it.manual) "recorded+manual" else if (it.manual) "manual" else "recorded")
        append("\r\n")
    }
}

/** Complete file output without building another history-sized CSV String. */
fun writeCountryDaysCsv(writer:java.io.Writer,days:List<CountryPresence>,checkpoint:()->Unit={}) {
    writer.append("country_code,local_date,source\r\n")
    days.sortedWith(compareBy({it.countryCode},{it.day})).forEach {day ->
        checkpoint()
        writer.append(day.countryCode).append(',').append(day.day.toString()).append(',')
            .append(if(day.recorded && day.manual) "recorded+manual" else if(day.manual) "manual" else "recorded").append("\r\n")
    }
}
