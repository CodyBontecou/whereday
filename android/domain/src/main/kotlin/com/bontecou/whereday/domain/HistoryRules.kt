package com.bontecou.whereday.domain

import java.time.*

data class InferenceRules(val gap: Duration = Duration.ofMinutes(30), val minimumDuration: Duration = Duration.ZERO, val minimumPoints: Int = 1) {
    init { require(gap>Duration.ZERO && minimumDuration>=Duration.ZERO && minimumPoints>=1) }
}
data class InferredOuting(val points: List<TrackPoint>) {
    val id get() = "inferred:${points.first().id}"
    val duration: Duration get() = Duration.between(points.first().time, points.last().time)
}
object OutingInference {
    fun infer(points: List<TrackPoint>, sessions: List<RecordingSession>, rules: InferenceRules = InferenceRules()): List<InferredOuting> {
        val unassigned = points.sortedBy { it.time }.filter { point -> sessions.none { point.time >= it.startedAt && (it.endedAt == null || point.time <= it.endedAt) } }
        val groups = mutableListOf<MutableList<TrackPoint>>()
        unassigned.forEach { point ->
            val last = groups.lastOrNull()
            if (last == null || Duration.between(last.last().time, point.time) > rules.gap) groups += mutableListOf(point)
            else last += point
        }
        return groups.map { InferredOuting(it) }.filter { it.points.size >= rules.minimumPoints && it.duration >= rules.minimumDuration }
    }
}

data class CountryPresence(val countryCode: String, val day: LocalDate, val recorded: Boolean, val manual: Boolean)
object CountryDays {
    fun calculate(visits: List<Visit>, points: List<TrackPoint>, stays: List<CountryStay>, now: Instant): List<CountryPresence> {
        val accumulator = CountryDayAccumulator(now)
        visits.forEach(accumulator::add)
        points.forEach(accumulator::add)
        stays.forEach(accumulator::add)
        return accumulator.days()
    }
}

data class ExportSchedule(val hour: Int = 21, val minute: Int = 0, val intervalHours: Int = 0) {
    init { require(hour in 0..23 && minute in 0..59 && intervalHours in 0..23) }
    /** Anchor cycle repeats by local clock each day, including intervals that do not divide 24. */
    fun occurrences(day: LocalDate, zone: ZoneId): List<Instant> {
        val anchor = day.atTime(hour, minute).atZone(zone)
        val offsets = if (intervalHours == 0) listOf(0) else (0 until 24 step intervalHours).toList()
        return offsets.map { anchor.plusHours(it.toLong()).toInstant() }.distinct().sorted()
    }
    fun next(after: Instant, zone: ZoneId): Instant {
        val today = after.atZone(zone).toLocalDate()
        if(intervalHours==0) {
            // ExportAutomationKit resolves today's anchor first, then adds a calendar day.
            // Keep a spring-gap anchor's resolved clock time when advancing, as iOS does.
            val anchor=today.atTime(hour,minute).atZone(zone)
            return if(anchor.toInstant()>after) anchor.toInstant() else anchor.plusDays(1).toInstant()
        }
        return (-1L..2L).flatMap { occurrences(today.plusDays(it), zone) }.filter { it > after }.min()
    }
    fun latest(at: Instant, zone: ZoneId): Instant? {
        val today=at.atZone(zone).toLocalDate()
        if(intervalHours==0) {
            val anchor=today.atTime(hour,minute).atZone(zone)
            return if(anchor.toInstant()<=at) anchor.toInstant() else anchor.minusDays(1).toInstant()
        }
        return (-1L..0L).flatMap {occurrences(today.plusDays(it),zone)}.filter {it<=at}.maxOrNull()
    }
    /** Matches iOS runIfDue: only the latest current-local-day occurrence, with no backlog. */
    fun due(at:Instant,zone:ZoneId,lastSuccess:Instant?):Instant? = latest(at,zone)?.takeIf {
        it.atZone(zone).toLocalDate()==at.atZone(zone).toLocalDate() && (lastSuccess==null || lastSuccess<it)
    }
}
