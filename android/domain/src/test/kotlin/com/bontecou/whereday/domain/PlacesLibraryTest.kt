package com.bontecou.whereday.domain

import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.*
import org.junit.Test

class PlacesLibraryTest {
    private fun at(hours: Long) = Instant.parse("2026-11-02T00:00:00Z").plusSeconds(hours * 3600)
    private val name: (Visit) -> String = { it.name ?: "Visit" }
    private fun visit(id: String, lat: Double, lon: Double, hour: Long, label: String? = null, address: String? = null) =
        Visit(id, Coordinate(lat, lon), at(hour), at(hour + 1), name = label, address = address)

    @Test fun suggestionsGroupThreeOrMoreUnmatchedVisitsOnTheGrid() {
        // Newest first, as the journal lists visits.
        val spot = listOf(visit("a", 40.00005, -74.0, 30, "Bakery"), visit("b", 40.0, -74.0001, 20, "Corner", "5 Elm St"), visit("c", 40.0, -74.0, 10, "Bakery"))
        val rare = listOf(visit("d", 41.0, -74.0, 5, "Rare"), visit("e", 41.0, -74.0, 4, "Rare"))
        val suggestions = UnsavedPlaceSuggestion.suggestions(spot + rare, name)
        val only = suggestions.single()
        assertEquals("Bakery", only.name)
        assertEquals(3, only.visitCount)
        assertEquals(at(30), only.lastVisit)
        assertEquals("5 Elm St", only.address)
        assertEquals(40.0000167, only.coordinate.latitude, 1e-6)
        assertEquals(2, UnsavedPlaceSuggestion.distinctSpotCount(spot + rare))
        assertEquals(listOf("a", "b", "c"), only.visits(emptyList(), spot + rare).map { it.id })
        // Ties in name frequency take the first name in code-point order.
        assertEquals("Alpha", UnsavedPlaceSuggestion.suggestions(listOf(visit("x", 0.0, 0.0, 3, "Beta"), visit("y", 0.0, 0.0, 2, "Alpha"), visit("z", 0.0, 0.0, 1, "Gamma"),
            visit("w", 0.0, 0.0, 0, "Beta"), visit("v", 0.0, 0.0, 0, "Alpha")), name).single().name)
    }

    @Test fun gridKeysRoundHalvesAwayFromZeroLikeSwift() {
        assertEquals("1:-1", UnsavedPlaceSuggestion.gridKey(visit("h", 0.0007, -0.0007, 0)))
        assertEquals("0:0", UnsavedPlaceSuggestion.gridKey(visit("l", 0.0006, -0.0006, 0)))
    }

    @Test fun sortingPinsHomeAndOrdersByVisitsOrRecency() {
        val cafe = SavedPlace("cafe", "Cafe", Coordinate(10.0, 10.0))
        val gym = SavedPlace("gym", "Gym", Coordinate(20.0, 20.0))
        val home = SavedPlace("home", "Home", Coordinate(30.0, 30.0), kind = SavedPlaceKind.home)
        val visits = listOf(visit("g2", 20.0, 20.0, 50), visit("c1", 10.0, 10.0, 40), visit("g1", 20.0, 20.0, 10), visit("h1", 30.0, 30.0, 5), visit("h2", 30.0, 30.0, 30))
        val library = PlacesLibrary(listOf(cafe, gym, home), visits, name)
        assertEquals(listOf("home", "gym", "cafe"), library.sorted(PlacesFilter.mostVisited).map { it.id })
        assertEquals(listOf("home", "gym", "cafe"), library.sorted(PlacesFilter.recent).map { it.id })
        assertEquals(listOf("home", "cafe", "gym"), library.sorted(PlacesFilter.alphabetical).map { it.id })
        assertEquals(listOf("cafe"), library.sorted(PlacesFilter.alphabetical) { it.name.startsWith("C") }.map { it.id })
        assertEquals(3, library.visitedPlaceCount)
        assertTrue(library.suggestions.isEmpty())
        assertEquals(2, PlacesLibrary.dayCount(library.index.visits(home), ZoneOffset.UTC))
    }
}
