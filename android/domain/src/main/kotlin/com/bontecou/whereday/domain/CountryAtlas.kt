package com.bontecou.whereday.domain

import java.time.LocalDate

/**
 * Port of `CountryAtlas` in the current iOS `AtlasView.swift`: country-day presences scoped to a year,
 * with ink ranks assigned in all-time order so a country keeps its colour between Year and All.
 */
class CountryAtlas(allTime: List<CountryPresence>, val year: Int? = null, homeCountryCode: String? = null, name: (String) -> String = { it }) {
    data class Summary(val countryCode: String, val days: List<CountryPresence>)

    val summaries: List<Summary>
    /** Presences per day, most-visited country first. */
    val countriesByDay: Map<LocalDate, List<CountryPresence>>
    private val rank: Map<String, Int>

    init {
        val order = Comparator<Summary> { a, b -> if(a.days.size != b.days.size) b.days.size - a.days.size else name(a.countryCode).compareTo(name(b.countryCode), ignoreCase = true) }
        val all = allTime.groupBy { it.countryCode }.map { (code, days) -> Summary(code, days.sortedBy { it.day }) }.sortedWith(order)
        val ranked = all.map { it.countryCode }.toMutableList()
        if(homeCountryCode != null) { ranked.remove(homeCountryCode); ranked.add(0, homeCountryCode) }
        rank = ranked.withIndex().associate { it.value to it.index }
        summaries = if(year == null) all else all.mapNotNull { summary -> summary.days.filter { it.day.year == year }.takeIf { it.isNotEmpty() }?.let { Summary(summary.countryCode, it) } }.sortedWith(order)
        val byDay = LinkedHashMap<LocalDate, MutableList<CountryPresence>>()
        summaries.forEach { summary -> summary.days.forEach { byDay.getOrPut(it.day) { mutableListOf() } += it } }
        countriesByDay = byDay
    }

    val maximumDays: Int get() = summaries.firstOrNull()?.days?.size ?: 1

    /** Index into the twelve atlas inks; the first (home) ink goes to Home's country or the all-time leader. */
    fun ink(countryCode: String): Int = (rank[countryCode] ?: 0) % INKS

    /** Days with no record between the first recorded day and today (within the year when scoped). */
    fun gapCount(today: LocalDate): Int {
        var cursor = countriesByDay.keys.minOrNull() ?: return 0
        var last = today
        if(year != null) {
            val start = LocalDate.of(year, 1, 1); val end = LocalDate.of(year, 12, 31)
            if(cursor < start) cursor = start
            if(end < last) last = end
        }
        var gaps = 0
        while(cursor <= last) { if(cursor !in countriesByDay) gaps++; cursor = cursor.plusDays(1) }
        return gaps
    }

    companion object {
        const val INKS = 12
        /** Years from the first recorded year through the current one. */
        fun years(allTime: List<CountryPresence>, today: LocalDate): List<Int> {
            val first = allTime.minOfOrNull { it.day.year } ?: today.year
            return (minOf(first, today.year)..today.year).toList()
        }
    }
}
