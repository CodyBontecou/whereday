package com.bontecou.whereday.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class HistoryRulesTest {
    private val origin = Coordinate(18.4655, -66.1057)
    private val start = Instant.parse("2026-10-26T12:00:00Z")
    private fun point(seconds: Long, coordinate: Coordinate = origin, accuracy: Double = 8.0) = TrackPoint(coordinate = coordinate, time = start.plusSeconds(seconds), accuracy = accuracy)

    @Test fun dwellConfirmsOnlyAfterThresholdAndBackdatesArrival() {
        val engine = VisitDetector()
        var state = engine.consume(DwellState(), point(0)).state
        state = engine.consume(state, point(299)).state
        assertNull(state.visitId)
        val result = engine.consume(state, point(300))
        assertNotNull(result.arrived)
        assertEquals(start, result.arrived!!.arrivedAt)
        assertEquals(result.arrived!!.id, result.state.visitId)
        assertNull(engine.consume(result.state, point(360)).arrived)
    }
    @Test fun departureNeedsSustainedEvidenceAndBriefExcursionDoesNotSplitVisit() {
        val engine = VisitDetector()
        val candidate = engine.consume(DwellState(), point(0)).state
        val open = engine.consume(candidate, point(300)).state
        val away = Coordinate(18.4755, -66.1057)
        val excursion = engine.consume(open, point(360, away))
        assertNull(excursion.departedId)
        val returned = engine.consume(excursion.state, point(400)).state
        assertNull(returned.departureSince)
        val leaving = engine.consume(returned, point(500, away)).state
        val departed = engine.consume(leaving, point(620, away))
        assertEquals(open.visitId, departed.departedId)
        assertEquals(start.plusSeconds(500), departed.departedAt)
    }
    @Test fun staleAndPoorQualityFixesDoNotAdvanceState() {
        val engine = VisitDetector()
        val state = engine.consume(DwellState(), point(100)).state
        assertEquals(state, engine.consume(state, point(99)).state)
        assertEquals(state, engine.consume(state, point(200, accuracy = 101.0)).state)
        assertEquals(state, engine.consume(state, point(200).copy(outlier = true)).state)
    }
    @Test fun longGapDoesNotInventMultiDayPresence() {
        val engine = VisitDetector()
        val open = engine.consume(engine.consume(DwellState(), point(0)).state, point(300)).state
        val result = engine.consume(open, point(86_400))
        assertEquals(start.plusSeconds(300), result.departedAt)
        assertEquals(open.visitId, result.departedId)
        assertNull(result.state.visitId)
    }
    @Test fun outAndBackFlagsMiddlePointAndTeleportFlagsNewPoint() {
        val a = point(0)
        val b = point(10, Coordinate(18.4755, -66.1057))
        val c = point(20)
        assertTrue(b.id in TrackQuality.flaggedIds(listOf(a), b))
        assertTrue(b.id in TrackQuality.flaggedIds(listOf(a, b), c))
    }
    @Test fun correctionsKeepFirstOriginalThroughRepeatedEdits() {
        val original = Visit(coordinate = origin, arrivedAt = start, name = "Detected", address = "Original address")
        val edited = original.correct(Coordinate(19.0, -66.0), "Cafe", "New address", start.plusSeconds(1))
            .correct(Coordinate(20.0, -66.0), "Park", null, start.plusSeconds(2))
        assertEquals(origin, edited.originalCoordinate)
        val undone = edited.undoCorrection(start.plusSeconds(3))
        assertEquals(origin, undone.coordinate)
        assertEquals("Detected", undone.name)
        assertEquals("Original address", undone.address)
    }
    @Test fun inferenceExcludesExactSessionsAndSplitsAtGaps() {
        val points = listOf(point(0), point(60), point(3600), point(3660), point(7200))
        val sessions = listOf(RecordingSession(startedAt = start.plusSeconds(3600), endedAt = start.plusSeconds(3660)))
        val outings = OutingInference.infer(points, sessions, InferenceRules(minimumPoints = 2))
        assertEquals(1, outings.size)
        assertEquals(2, outings.single().points.size)
    }
    @Test fun countryDaysUsesGeographicTimeZoneAndDeduplicatesManualOverlap() {
        val visit = Visit(coordinate = origin, arrivedAt = Instant.parse("2026-10-26T02:00:00Z"), departedAt = Instant.parse("2026-10-26T06:00:00Z"), countryCode = "US", timeZone = "America/New_York")
        val stay = CountryStay(countryCode = "US", firstDay = LocalDate.parse("2026-10-26"), lastDay = LocalDate.parse("2026-10-27"))
        val days = CountryDays.calculate(listOf(visit), emptyList(), listOf(stay), start)
        assertEquals(listOf("2026-10-25", "2026-10-26", "2026-10-27"), days.map { it.day.toString() })
        assertTrue(days[1].manual && days[1].recorded)
    }
    @Test fun inferredDepartureAndUnknownZoneContributeNoInventedDays() {
        val visit = Visit(coordinate = origin, arrivedAt = start.minus(Duration.ofDays(5)), departedAt = start, departureInferred = true, countryCode = "US", timeZone = "America/New_York")
        assertEquals(1, CountryDays.calculate(listOf(visit), emptyList(), emptyList(), start).size)
        assertTrue(CountryDays.calculate(listOf(visit.copy(timeZone = null)), emptyList(), emptyList(), start).isEmpty())
    }
    @Test fun nineAndTwentyThreeHourSchedulesResetAtDailyAnchor() {
        val zone = ZoneId.of("America/Los_Angeles")
        val after = OffsetDateTime.parse("2026-05-04T21:00:00-07:00").toInstant()
        assertEquals(OffsetDateTime.parse("2026-05-05T03:00:00-07:00").toInstant(), ExportSchedule(9, 0, 9).next(after, zone))
        val previous = OffsetDateTime.parse("2026-05-05T08:30:00-07:00").toInstant()
        assertEquals(OffsetDateTime.parse("2026-05-05T09:00:00-07:00").toInstant(), ExportSchedule(9, 0, 23).next(previous, zone))
    }
    @Test fun daylightSavingOccurrencesAreDistinctAndNextIsStrictlyLater() {
        val zone = ZoneId.of("America/New_York")
        for (day in listOf(LocalDate.parse("2026-03-08"), LocalDate.parse("2026-11-01"))) {
            val schedule = ExportSchedule(0, 0, 1)
            val events = schedule.occurrences(day, zone)
            assertEquals(events.distinct(), events)
            events.forEach { assertTrue(schedule.next(it, zone) > it) }
        }
    }
    @Test fun midnightDepartureDoesNotAddAnotherCountryDay() {
        val visit=Visit(coordinate=origin,arrivedAt=Instant.parse("2026-10-25T20:00:00Z"),departedAt=Instant.parse("2026-10-26T04:00:00Z"),countryCode="US",timeZone="America/New_York")
        assertEquals(listOf(LocalDate.parse("2026-10-25")),CountryDays.calculate(listOf(visit),emptyList(),emptyList(),start).map { it.day })
        assertTrue(CountryDays.calculate(listOf(visit.copy(arrivedAt=start.plusSeconds(1),departedAt=null)),emptyList(),emptyList(),start).isEmpty())
    }
    @Test fun intervalHoursMatchElapsedHourAdditionAcrossDST() {
        val zone=ZoneId.of("America/Los_Angeles")
        val events=ExportSchedule(0,0,1).occurrences(LocalDate.parse("2026-11-01"),zone)
        assertEquals(24,events.size)
        assertEquals(2,events.count { it.atZone(zone).hour == 1 })
        events.zipWithNext().forEach { (a,b) -> assertEquals(3600,Duration.between(a,b).seconds) }
    }
}
