package com.bontecou.whereday.domain

import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

/** Mirrors IsoMeTests/SplitVisitTests.swift. */
class VisitSplitTest {
    private val arrival=Instant.ofEpochSecond(1_700_000_000)
    private val departure=arrival.plusSeconds(50*60)
    private val splitAt=arrival.plusSeconds(25*60)
    private val now=departure.plusSeconds(3600)
    // Two storefronts about 40 m apart.
    private val traderJoes=Coordinate(37.7750,-122.4194)
    private val marshalls=Coordinate(37.7753,-122.4196)
    private val marshallsPlace=SplitPlace("Marshalls","12 Main St",marshalls,"geoapify",40.0)
    private fun detected()=Visit("first",traderJoes,arrival,departure,name="Trader Joe's",address="10 Main St",notes="Groceries",
        detectedCoordinate=traderJoes,detectedName="Trader Joe's",detectedAddress="10 Main St",countryCode="US",timeZone="America/Los_Angeles",
        departureInferred=true)

    @Test fun splitProducesTwoBackToBackVisits() {
        val (first,second)=VisitSplit.split(detected(),splitAt,marshallsPlace,"second",now)
        assertEquals(detected().copy(departedAt=splitAt,departureInferred=false,confirmation=Confirmation.confirmed,confirmedAt=now,updatedAt=now),first)
        assertEquals("second",second.id)
        assertEquals(splitAt,second.arrivedAt);assertEquals(departure,second.departedAt)
        assertEquals("Marshalls",second.name);assertEquals("12 Main St",second.address);assertEquals(marshalls,second.coordinate)
        assertEquals(VisitSource.automatic,second.source);assertEquals(Confirmation.corrected,second.confirmation)
        assertEquals("geoapify",second.placeSource);assertEquals(40.0,second.placeDistanceMeters!!,0.0)
        assertNull(second.notes)
        assertEquals("US",second.countryCode);assertEquals("America/Los_Angeles",second.timeZone)
        // The original departure's provenance moves to the part that now owns it.
        assertTrue(second.departureInferred)
        // Undo correction on the second part returns it to what detection saw.
        assertEquals(traderJoes,second.originalCoordinate);assertEquals("Trader Joe's",second.originalName)
        val undone=second.undoCorrection(now)
        assertEquals("Trader Joe's",undone.name);assertEquals(traderJoes,undone.coordinate)
    }

    @Test fun splitPartsAreNotMergedBackTogether() {
        val (first,second)=VisitSplit.split(detected(),splitAt,marshallsPlace,"second",now)
        assertFalse(VisitReconciliation.shouldMerge(first,second,now))
        // Undoing the second part's correction makes it mergeable again: an undo of the split.
        assertTrue(VisitReconciliation.shouldMerge(first,second.undoCorrection(now),now))
    }

    @Test fun rangeKeepsAMinuteOnEachSideAndRejectsOpenOrShortVisits() {
        assertEquals(arrival.plusSeconds(60)..departure.minusSeconds(60),VisitSplit.range(detected()))
        // The midpoint, rounded down to a whole minute.
        val suggested=VisitSplit.suggestedTime(detected())!!
        assertEquals(0,suggested.epochSecond%60);assertTrue(suggested in arrival.plusSeconds(24*60)..arrival.plusSeconds(25*60))
        assertFalse(VisitSplit.canSplit(detected().copy(departedAt=null)))
        assertFalse(VisitSplit.canSplit(detected().copy(departedAt=arrival.plusSeconds(90))))
        assertTrue(VisitSplit.canSplit(detected().copy(departedAt=arrival.plusSeconds(120))))
        for(at in listOf(arrival,arrival.plusSeconds(30),departure,departure.minusSeconds(30),arrival.minusSeconds(600),departure.plusSeconds(600))) {
            assertEquals("$at",VisitSplitProblem.time,problem {VisitSplit.split(detected(),at,marshallsPlace,"second",now)})
        }
        assertEquals(VisitSplitProblem.time,problem {VisitSplit.split(detected().copy(departedAt=null),arrival.plusSeconds(120),marshallsPlace,"second",now)})
    }

    @Test fun splitRejectsTheSamePlaceAndBlankNames() {
        assertEquals(VisitSplitProblem.samePlace,problem {VisitSplit.split(detected(),splitAt,marshallsPlace.copy(name="  trader joe's "),"second",now)})
        assertEquals(VisitSplitProblem.samePlace,problem {VisitSplit.split(detected().copy(name="Café Lumière"),splitAt,marshallsPlace.copy(name="cafe lumiere"),"second",now)})
        assertEquals(VisitSplitProblem.placeName,problem {VisitSplit.split(detected(),splitAt,marshallsPlace.copy(name="   "),"second",now)})
    }

    @Test fun splittingAManualVisitKeepsBothPartsManualAndReviewed() {
        val manual=Visit("first",traderJoes,arrival,departure,name="Trader Joe's",source=VisitSource.manual,confirmation=Confirmation.confirmed,confirmedAt=arrival)
        val (first,second)=VisitSplit.split(manual,splitAt,marshallsPlace,"second",now)
        assertEquals(VisitSource.manual,second.source);assertNull(second.detectedCoordinate)
        assertEquals(traderJoes,second.originalCoordinate)
        assertEquals(Confirmation.confirmed,first.confirmation);assertEquals(arrival,first.confirmedAt)
        assertEquals(first.departedAt,second.arrivedAt)
    }

    private fun problem(block:()->Unit):VisitSplitProblem? = try {block();null} catch(e:InvalidVisitSplit) {e.problem}
}
