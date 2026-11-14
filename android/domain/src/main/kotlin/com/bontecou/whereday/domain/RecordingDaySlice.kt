package com.bontecou.whereday.domain

import java.time.Duration
import java.time.Instant

/**
 * One local day of a stored recording, as iOS `RecordingSessionSummary.clipped(to:)`.
 * The stored session is never changed; it stays openable as the complete outing.
 * [endedAt] is the slice's end (the next midnight, the recording's end, or now while live).
 */
data class RecordingDaySlice(val session: RecordingSession, val day: HistoryRange, val startedAt: Instant, val endedAt: Instant, val live: Boolean, private val now: Instant) {
    /** Last stored (millisecond) instant of the slice: the following midnight belongs only to the next day. */
    val lastInstant: Instant get() = minOf(endedAt, day.until.minusMillis(1)).coerceAtLeast(startedAt)
    /** Half-open database window for the slice's fixes, visits, photos, replay and export. */
    val window: HistoryRange get() = HistoryRange(startedAt, lastInstant.plusMillis(1))
    val duration: Duration get() = Duration.between(startedAt, endedAt)
    /** The recording also has time on another day, so this day's view differs from the complete outing. */
    val isPartial: Boolean get() = session.startedAt < day.from || (session.endedAt ?: now) > day.until
    /** Session-shaped header for titles and figures: open only while the slice is live. */
    val presented: RecordingSession get() = session.copy(startedAt = startedAt, endedAt = if (live) null else endedAt)
    /** The header the map and other closed-window consumers read, ending before the next midnight. */
    val closedWindowSession: RecordingSession get() = session.copy(startedAt = startedAt, endedAt = if (live) null else lastInstant)
    /** Visits shown with the slice overlap its day (iOS dayRange filter): arrived before its end, left after its start. */
    fun overlapsDay(visit: Visit): Boolean = visit.arrivedAt < day.until && (visit.departedAt ?: now) > day.from
}

object RecordingDayClip {
    /**
     * Clips [session] to the half-open local [day]. Returns null when the recording has no time on that day,
     * including a recording that ended exactly at the day's first instant. A zero-length slice is kept only when
     * it starts inside the day and [hasDailyPoints] (fixes in the day at or before now) is true.
     */
    fun clip(session: RecordingSession, day: HistoryRange, now: Instant, hasDailyPoints: Boolean = true): RecordingDaySlice? {
        val active = session.endedAt == null
        val start = maxOf(session.startedAt, day.from)
        val end = minOf(session.endedAt ?: now, day.until, now)
        if (!(start < end || (start == end && day.contains(session.startedAt) && hasDailyPoints))) return null
        return RecordingDaySlice(session, day, start, end, live = active && day.contains(now), now = now)
    }

    /** The window a stored outing's fixes are read from: the complete recording, or its slice of [day]. */
    fun range(session: RecordingSession, now: Instant, day: HistoryRange? = null): HistoryRange {
        if (day == null) return HistoryRange(session.startedAt, (session.endedAt ?: now).coerceAtLeast(session.startedAt).plusMillis(1))
        return (clip(session, day, now) ?: error("This outing has no movement on this day.")).window
    }
}
