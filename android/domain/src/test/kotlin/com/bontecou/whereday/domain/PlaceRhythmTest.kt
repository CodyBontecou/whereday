package com.bontecou.whereday.domain

import java.time.DayOfWeek
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class PlaceRhythmTest {
    private val zone = ZoneOffset.UTC
    // Monday, November 2, 2026.
    private val monday = LocalDate.of(2026, 11, 2)
    private fun at(day: LocalDate, hour: Int, minute: Int = 0) = day.atTime(hour, minute).toInstant(zone)
    private val cafe = SavedPlace("cafe", "Cafe Luna", Coordinate(40.70, -74.00), radius = 100.0)
    private val studio = SavedPlace("studio", "Studio", Coordinate(40.7005, -74.00), radius = 150.0)
    private fun visit(id: String, coordinate: Coordinate, day: LocalDate, from: Int, until: Int, name: String? = null, source: String? = null) =
        Visit(id, coordinate, at(day, from), at(day, until), name = name, placeSource = source)

    @Test fun visitsBelongToTheirNearestContainingPlace() {
        val nearCafe = visit("a", Coordinate(40.7001, -74.00), monday, 9, 10)
        val nearStudio = visit("b", Coordinate(40.7005, -74.00), monday, 11, 12)
        val outside = visit("c", Coordinate(41.0, -74.0), monday, 13, 14)
        val index = PlaceVisitIndex(listOf(cafe, studio), listOf(nearCafe, nearStudio, outside))
        assertEquals(listOf("a"), index.visits(cafe).map { it.id })
        assertEquals(listOf("b"), index.visits(studio).map { it.id })
        assertEquals(listOf("c"), index.unmatched.map { it.id })
        assertEquals(1, PlaceVisitIndex.ink(studio, listOf(cafe, studio)))
    }

    @Test fun rhythmCountsTypicalStayYearAndWeekdayEvenings() {
        val visits = (0L until 4L).map { visit("v$it", cafe.coordinate, monday.plusDays(it), 18, 20) } +
            visit("short", cafe.coordinate, monday.plusDays(4), 18, 19)
        val rhythm = PlaceRhythm(visits, at(monday.plusDays(6), 12), zone)
        assertEquals(5, rhythm.visitCount)
        assertEquals(Duration.ofHours(2), rhythm.typicalStay)
        assertEquals(Duration.ofHours(9), rhythm.timeThisYear)
        assertEquals(DayOfWeek.MONDAY to 18, rhythm.peak)
        assertEquals(PlaceRhythm.Summary(PlaceRhythm.Lean.weekday, PlaceRhythm.Part.evenings), rhythm.summary)
        assertEquals(3, rhythm.level(DayOfWeek.MONDAY, 18))
        assertEquals(0, rhythm.level(DayOfWeek.MONDAY, 9))
        assertEquals(DayOfWeek.SUNDAY, PlaceRhythm(visits, at(monday, 12), zone, DayOfWeek.SUNDAY).weekdayOrder.first())
    }

    @Test fun rhythmNeedsThreeVisitsAndClipsOpenVisitsAtNowAndAWeek() {
        val open = Visit("open", cafe.coordinate, at(monday.minusDays(20), 8))
        val rhythm = PlaceRhythm(listOf(open), at(monday, 8), zone)
        assertNull(rhythm.typicalStay)
        assertNull(rhythm.summary)
        assertEquals(Duration.ofDays(20), rhythm.timeThisYear)
        assertTrue(PlaceRhythm(emptyList(), at(monday, 8), zone).peak == null)
        // Time this year starts at January 1 even when the visit began in December.
        val newYear = Visit("ny", cafe.coordinate, LocalDate.of(2026, 12, 31).atTime(22, 0).toInstant(zone), LocalDate.of(2027, 1, 1).atTime(2, 0).toInstant(zone))
        assertEquals(Duration.ofHours(2), PlaceRhythm(listOf(newYear), LocalDate.of(2027, 1, 2).atStartOfDay(zone).toInstant(), zone).timeThisYear)
    }

    @Test fun mergeWidensRadiusRenamesVisitsAndFillsGaps() {
        val kept = cafe.copy(notes = "Window seat")
        val removed = studio.copy(address = "12 Mill St", kind = SavedPlaceKind.work, notes = " Back door ")
        val inside = visit("in", studio.coordinate, monday, 9, 10, name = "Studio", source = "detected")
        val named = visit("named", Coordinate(10.0, 10.0), monday, 11, 12, name = "studio", source = "userEntered")
        val autoElsewhere = visit("auto", Coordinate(10.0, 10.0), monday, 13, 14, name = "Studio", source = "detected")
        val detected = visit("det", studio.coordinate, monday, 15, 16, name = "Desk").copy(detectedName = "Studio")
        val visits = listOf(inside, named, autoElsewhere, detected)
        val plan = SavedPlaceMergePlan.build(kept, removed, listOf(kept, removed), visits)
        assertEquals(setOf("in", "det"), plan.movedVisitIds)
        assertEquals(setOf("in", "named", "det"), plan.renamedVisitIds)
        assertEquals(3, plan.affectedVisitCount)
        assertTrue(plan.radiusGrows); assertTrue(plan.coversRemovedPlace)
        assertEquals(kotlin.math.ceil(kept.coordinate.distanceTo(removed.coordinate) + removed.radius), plan.mergedRadius, 0.0)
        val merged = SavedPlaceMergePlan.merged(plan, kept, removed)
        assertEquals(SavedPlaceKind.work, merged.kind)
        assertEquals("12 Mill St", merged.address)
        assertEquals("Window seat\n\nBack door", merged.notes)
        val now = at(monday, 20)
        val renamed = SavedPlaceMergePlan.renamed(plan, kept, visits, now).associateBy { it.id }
        assertEquals("Cafe Luna", renamed.getValue("in").name)
        assertEquals("Cafe Luna", renamed.getValue("named").name)
        assertEquals("Desk", renamed.getValue("det").name); assertEquals("Cafe Luna", renamed.getValue("det").detectedName)
        assertTrue(renamed.values.all { it.updatedAt == now })
        assertFalse("auto" in renamed)
    }

    @Test fun mergeOfFarPlacesCapsTheRadiusAndKeepsTheKeptKind() {
        val far = studio.copy(coordinate = Coordinate(41.0, -74.0), kind = SavedPlaceKind.gym)
        val plan = SavedPlaceMergePlan.build(cafe.copy(kind = SavedPlaceKind.home), far, listOf(cafe, far), emptyList())
        assertFalse(plan.coversRemovedPlace)
        assertEquals(SavedPlaceMergePlan.MAXIMUM_RADIUS, plan.mergedRadius, 0.0)
        assertEquals(SavedPlaceKind.home, SavedPlaceMergePlan.merged(plan, cafe.copy(kind = SavedPlaceKind.home), far).kind)
        assertNull(SavedPlaceMergePlan.merged(plan, cafe, far.copy(notes = "  ")).notes)
    }
}
