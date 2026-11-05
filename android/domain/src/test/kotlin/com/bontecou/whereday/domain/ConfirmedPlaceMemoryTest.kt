package com.bontecou.whereday.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.Locale

class ConfirmedPlaceMemoryTest {
    private val at=Instant.parse("2026-10-29T12:00:00Z")
    private val origin=Coordinate(0.0,0.0)
    private val visit=Visit(coordinate=origin,arrivedAt=at,name="  Cafe  ",address="  Street  ",confirmation=Confirmation.confirmed)
    private fun remember(value:Visit=visit,places:List<SavedPlace> = emptyList())=confirmedPlaceToRemember(value,places,"new",{a,b->a.distanceTo(b)},{it.trim().lowercase(Locale.ROOT)})
    @Test fun reviewedPlacesUseTrimmedMetadataAndTheReferenceDefaultRadius() {
        assertEquals(SavedPlace("new","Cafe",origin,"Street",150.0),remember())
        assertNull(remember(visit.copy(confirmation=Confirmation.unconfirmed)))
        assertNull(remember(visit.copy(name=" ",address=null)))
        assertEquals("Street",remember(visit.copy(name=null))!!.name)
    }
    @Test fun matchingManagedPlacesKeepTheirExactCoordinatesAddressAndRadius() {
        val small=SavedPlace("small","CAFE",Coordinate(.001,0.0),"Managed",25.0)
        val large=SavedPlace("large","Cafe",Coordinate(.004,0.0),"Large managed",500.0)
        assertNull(remember(places=listOf(small)));assertNull(remember(places=listOf(large)))
        assertEquals(Coordinate(.001,0.0),small.coordinate);assertEquals(25.0,small.radius,0.0);assertEquals("Managed",small.address)
        assertNotNull(remember(places=listOf(large.copy(coordinate=Coordinate(.006,0.0)))))
        assertNotNull(remember(places=listOf(small.copy(name="Other"))))
    }
    @Test fun onlyExistingPlaceReviewCommandsRememberAutomatically() {
        val draft=VisitDraft(visit.id,"Cafe","Street","Note",origin,at,at.plusSeconds(60))
        val same=visit.copy(name="Cafe",address="Street",departedAt=at.plusSeconds(60))
        assertFalse(draft.remembersPlace(null,true))
        assertFalse(draft.remembersPlace(same,false));assertFalse(draft.remembersPlace(same,true))
        assertTrue(draft.remembersPlace(same.copy(confirmation=Confirmation.unconfirmed),true))
        assertTrue(draft.copy(name="New").remembersPlace(same,false))
        assertTrue(draft.copy(placeSelectionChanged=true).remembersPlace(same,false))
        assertFalse(draft.copy(notes="Other",departedAt=at.plusSeconds(30)).remembersPlace(same,false))
    }
    @Test fun cancellationIsObservedWhileScanningTheLibrary() {
        val places=listOf(SavedPlace("other","Other",origin))
        assertThrows(IllegalStateException::class.java) {
            confirmedPlaceToRemember(visit,places,"new",{a,b->a.distanceTo(b)},{it}) {throw IllegalStateException("canceled")}
        }
    }
}
