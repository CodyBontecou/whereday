package com.bontecou.whereday.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class HistoryWindowTest {
    private val start=Instant.parse("2026-10-26T12:00:00Z")
    private fun point(index: Int) = TrackPoint(id=index.toString(),coordinate=Coordinate(18.0+index*0.000001,-66.0),time=start.plusSeconds(index.toLong()),accuracy=5.0)
    @Test fun samplerKeepsEndpointsAndNeverExceedsDisplayBudget() {
        val sampler=UniformPointSampler(10_001,2500)
        repeat(10_001) { sampler.add(point(it)) }
        val selected=sampler.points()
        assertEquals(2500,selected.size)
        assertEquals("0",selected.first().id)
        assertEquals("10000",selected.last().id)
        assertEquals(2500,selected.map { it.id }.distinct().size)
        assertTrue(selected.zipWithNext().all { (a,b) -> b.time > a.time })
    }
    @Test fun fullStatisticsAreIndependentOfDisplayedSample() {
        val summary=PointSummaryBuilder()
        val sampler=UniformPointSampler(6000,2500)
        val points=(0 until 6000).map(::point)
        points.forEach { summary.add(it); sampler.add(it) }
        assertEquals(6000L,summary.build().count)
        assertEquals(2500,sampler.points().size)
        assertEquals(TrackQuality.distance(points),summary.build().distanceMeters,0.00001)
    }
    @Test fun outliersDoNotInflateDistanceButRemainInTotalCount() {
        val summary=PointSummaryBuilder()
        summary.add(point(0))
        summary.add(point(1).copy(coordinate=Coordinate(80.0,80.0),outlier=true))
        summary.add(point(2))
        assertEquals(3L,summary.build().count)
        assertEquals(1L,summary.build().outlierCount)
        assertEquals(point(0).coordinate.distanceTo(point(2).coordinate),summary.build().distanceMeters,0.000001)
    }
    @Test fun localDaysUseHalfOpenBoundsAndDaylightSavingLength() {
        val zone=ZoneId.of("America/New_York")
        val spring=HistoryRange.day(LocalDate.parse("2026-03-08"),zone)
        val fall=HistoryRange.day(LocalDate.parse("2026-11-01"),zone)
        assertEquals(23L,Duration.between(spring.from,spring.until).toHours())
        assertEquals(25L,Duration.between(fall.from,fall.until).toHours())
        assertTrue(spring.contains(spring.from))
        assertFalse(spring.contains(spring.until))
        assertTrue(HistoryRange.day(LocalDate.parse("2026-03-09"),zone).contains(spring.until))
    }
    @Test fun streamingInferenceMatchesLegacyRulesWhileExcludingExactSessions() {
        val points=(0 until 100).map(::point)+listOf(point(4000),point(4100),point(9000))
        val sessions=listOf(RecordingSession(startedAt=point(4000).time,endedAt=point(4100).time))
        val rules=InferenceRules(minimumPoints=2)
        val expected=OutingInference.infer(points,sessions,rules)
        val streaming=StreamingOutingInference(sessions,rules)
        points.forEach(streaming::add)
        val actual=streaming.finish()
        assertEquals(expected.map { it.id },actual.map { it.id })
        assertEquals(expected.map { it.points.size.toLong() },actual.map { it.summary.count })
        assertEquals(expected.map { TrackQuality.distance(it.points) },actual.map { it.summary.distanceMeters })
        assertEquals(actual,streaming.finish())
    }
}
