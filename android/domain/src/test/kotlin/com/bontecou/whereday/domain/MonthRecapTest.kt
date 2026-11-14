package com.bontecou.whereday.domain

import java.time.Duration
import java.time.LocalDate
import java.time.YearMonth
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class MonthRecapTest {
    private val zone = ZoneOffset.UTC
    private val november = YearMonth.of(2026, 11)
    private fun at(day: Int, hour: Int, month: YearMonth = november) = month.atDay(day).atTime(hour, 0).toInstant(zone)
    private val name: (Visit) -> String = { it.name ?: "Visit" }
    private val now = at(20, 12)
    private fun visit(id: String, day: Int, from: Int, until: Int, label: String, country: String? = null, month: YearMonth = november) =
        Visit(id, Coordinate(40.0, -74.0), at(day, from, month), at(day, until, month), name = label, countryCode = country)
    private fun point(id: String, day: Int, hour: Int, latitude: Double, outlier: Boolean = false) =
        TrackPoint(id, Coordinate(latitude, -74.0), at(day, hour), 5.0, outlier = outlier)

    @Test fun visitsAloneCountDaysPlacesTopPlacesNewPlacesAndTheBusiestDay() {
        val visits = listOf(
            visit("old", 3, 9, 10, "Cafe", month = YearMonth.of(2026, 10)),
            visit("a", 2, 9, 17, "Studio", "US"), visit("b", 2, 18, 19, "Cafe", "US"),
            visit("c", 5, 8, 9, "Gym", "US"), visit("d", 5, 10, 12, "Studio", "US"),
            visit("open", 20, 9, 9, "Park").copy(departedAt = null),
        )
        val recap = MonthRecap(visits, null, november, zone, name, now)
        assertEquals(30, recap.dayCount)
        assertEquals(5, recap.visitCount)
        assertEquals(4, recap.placeCount)
        assertEquals(3, recap.recordedDays)
        assertEquals(1, recap.countryCount)
        assertEquals(MonthRecap.Closing.countries to 1, recap.closing)
        // Studio, Gym and Park first appear in November; Cafe first appeared in October.
        assertEquals(3, recap.newPlaces)
        // Ties for the most places in a day (2 on the 2nd and the 5th) keep the first.
        assertEquals(LocalDate.of(2026, 11, 2), recap.biggestDay)
        assertEquals(listOf("Studio" to Duration.ofHours(10), "Park" to Duration.ofHours(3), "Cafe" to Duration.ofHours(1)), recap.topPlaces.map { it.name to it.duration })
        assertFalse(recap.hasDistance)
        assertEquals(2.0, recap.barValues[1], 0.0)
        assertEquals(2.0, recap.highlightThreshold, 0.0)
    }

    @Test fun distanceIsAttributedWithinADayAndSkipsOutliersAndOtherMonths() {
        val distance = MonthDistance(november, zone)
        listOf(point("p1", 4, 9, 40.0), point("p2", 4, 10, 40.01), point("x", 4, 11, 45.0, outlier = true), point("p3", 4, 12, 40.02),
            point("p4", 5, 9, 41.0), point("p5", 5, 10, 41.001)).forEach(distance::add)
        distance.add(TrackPoint("dec", Coordinate(10.0, 10.0), at(1, 1, YearMonth.of(2026, 12)), 5.0))
        val day4 = Coordinate(40.0, -74.0).distanceTo(Coordinate(40.01, -74.0)) + Coordinate(40.01, -74.0).distanceTo(Coordinate(40.02, -74.0))
        assertEquals(day4, distance.perDay[3], 1e-6)
        // The jump across midnight to the 5th is not counted.
        assertEquals(Coordinate(41.0, -74.0).distanceTo(Coordinate(41.001, -74.0)), distance.perDay[4], 1e-6)
        assertEquals(setOf(3, 4), distance.pointDays)
        val recap = MonthRecap(emptyList(), distance, november, zone, name, now)
        assertTrue(recap.hasDistance)
        assertEquals(2, recap.recordedDays)
        assertEquals(LocalDate.of(2026, 11, 4), recap.biggestDay)
        assertEquals(MonthRecap.Closing.visits to 0, recap.closing)
        assertEquals(day4, recap.highlightThreshold, 1e-6)
    }

    @Test fun knownCitiesTakeTheClosingLine() {
        val visits = listOf(visit("a", 2, 9, 10, "A", "PT"), visit("b", 3, 9, 10, "B", "ES"))
        val recap = MonthRecap(visits, null, november, zone, name, now) { "Lisbon" }
        assertEquals(MonthRecap.Closing.cities to 1, recap.closing)
        assertEquals(MonthRecap.Closing.countries to 2, MonthRecap(visits, null, november, zone, name, now).closing)
    }
}
