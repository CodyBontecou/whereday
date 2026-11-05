package com.bontecou.whereday.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class CurrentVisitLocationTest {
    private val coordinate=Coordinate(0.0,0.0)
    @Test fun freshnessAndAccuracyIncludeIosBoundariesAndRejectInvalidOrFutureFixes() {
        val fix=CurrentVisitLocation(coordinate,100.0,10)
        assertTrue(fix.isUsable(10));assertTrue(fix.isUsable(300_010));assertFalse(fix.isUsable(300_011))
        assertFalse(fix.isUsable(9));assertFalse(fix.copy(elapsedMillis=-1).isUsable(10))
        listOf(-1.0,100.001,Double.NaN,Double.POSITIVE_INFINITY).forEach {assertFalse(fix.copy(accuracyMeters=it).isUsable(10))}
        assertTrue(fix.copy(accuracyMeters=0.0).isUsable(10))
    }
    @Test fun currentPlaceCanBeOpenWhilePastVisitStillRequiresDeparture() {
        val now=Instant.parse("2026-10-27T12:00:00Z")
        val draft=VisitDraft("current","Home","","",coordinate,now,null)
        val open=draft.build(null,now,mode=ManualVisitMode.current)
        assertNull(open.departedAt);assertEquals(Confirmation.confirmed,open.confirmation);assertEquals(VisitSource.manual,open.source)
        try {draft.build(null,now);fail()} catch(_:IllegalArgumentException) {}
        val closed=draft.copy(arrivedAt=now.minusSeconds(60),departedAt=now).build(null,now,mode=ManualVisitMode.current)
        assertEquals(now,closed.departedAt)
        for(invalid in listOf(draft.copy(name=" "),draft.copy(coordinate=null),draft.copy(arrivedAt=now.plusSeconds(1)))) {
            try {invalid.build(null,now,mode=ManualVisitMode.current);fail()} catch(_:IllegalArgumentException) {}
        }
    }
    @Test fun savedSuggestionUsesNearestEligibleCenterAndEachPlacesOwnRadius() {
        val fix=CurrentVisitLocation(coordinate,5.0,0)
        val far=SavedPlace("far","Far",Coordinate(.005,0.0),radius=1000.0)
        val near=SavedPlace("near","Near",Coordinate(.001,0.0),radius=150.0)
        val outside=SavedPlace("outside","Outside its radius",Coordinate(.0005,0.0),radius=25.0)
        assertEquals(near,fix.savedPlaceSuggestion(listOf(far,outside,near),0))
        assertNull(fix.savedPlaceSuggestion(listOf(outside),0))
        assertNull(fix.savedPlaceSuggestion(emptyList(),0))
    }
    @Test fun savedSuggestionHonorsExactRadiusAndTakesFirstPlaceForEqualDistances() {
        val fix=CurrentVisitLocation(coordinate,100.0,10)
        val center=Coordinate(.001,0.0);val radius=center.distanceTo(coordinate)
        val first=SavedPlace("first","First",center,radius=radius)
        val second=first.copy(id="second",name="Second")
        assertEquals(first,fix.savedPlaceSuggestion(listOf(first,second),300_010))
        assertNull(fix.savedPlaceSuggestion(listOf(first.copy(radius=radius-.000001)),10))
        assertEquals(second,fix.savedPlaceSuggestion(listOf(second,first),10))
    }
    @Test fun savedSuggestionRejectsExpiredFutureAndInaccurateFixes() {
        val place=SavedPlace(name="Here",coordinate=coordinate)
        val fix=CurrentVisitLocation(coordinate,5.0,10)
        assertNull(fix.savedPlaceSuggestion(listOf(place),9))
        assertNull(fix.savedPlaceSuggestion(listOf(place),300_011))
        assertNull(fix.copy(accuracyMeters=101.0).savedPlaceSuggestion(listOf(place),10))
        assertNull(fix.copy(accuracyMeters=Double.NaN).savedPlaceSuggestion(listOf(place),10))
    }
    @Test fun savedSuggestionCrossesTheDateLineWithoutMovingTheSavedCenter() {
        val fix=CurrentVisitLocation(Coordinate(0.0,179.9999),5.0,0)
        val place=SavedPlace(name="Across date line",coordinate=Coordinate(0.0,-179.9999),radius=25.0)
        assertEquals(place,fix.savedPlaceSuggestion(listOf(place),0))
    }
}
