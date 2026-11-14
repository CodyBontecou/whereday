package com.bontecou.whereday.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class VisitAuditLifecycleTest {
    private val at=Instant.parse("2026-10-29T12:00:00Z")
    private val origin=Coordinate(18.0,-66.0)
    @Test fun repeatedCorrectionsKeepTheFirstConfirmationAndAdvanceTheUpdateTime() {
        val confirmed=Visit(coordinate=origin,arrivedAt=at.minusSeconds(3600),name="Original",address="Original street",confirmation=Confirmation.confirmed,confirmedAt=at)
        val first=confirmed.correct(Coordinate(18.001,-66.0),"First","First street",at.plusSeconds(10))
        val second=first.correct(Coordinate(18.002,-66.0),"Second",null,at.plusSeconds(20))
        assertEquals(at,first.confirmedAt);assertEquals(at,second.confirmedAt)
        assertEquals(at.plusSeconds(20),second.updatedAt)
        assertEquals(origin,second.originalCoordinate);assertEquals("Original",second.originalName);assertEquals("Original street",second.originalAddress)
        val unconfirmed=confirmed.copy(confirmation=Confirmation.unconfirmed,confirmedAt=null)
        val newlyConfirmed=unconfirmed.correct(origin,"Reviewed",null,at.plusSeconds(30))
        assertEquals(at.plusSeconds(30),newlyConfirmed.confirmedAt)
    }
    @Test fun undoRetainsTheAuditSnapshotAndReconfirmationStartsANewConfirmationEpoch() {
        val original=Visit(coordinate=origin,arrivedAt=at.minusSeconds(3600),name=null,address=null,detectedCoordinate=origin,notes="Keep notes")
        val first=original.correct(Coordinate(18.001,-66.0),"Business","Street",at).copy(placeSource="geoapify",placeDistanceMeters=11.0)
        val undone=first.undoCorrection(at.plusSeconds(1))
        assertEquals(origin,undone.coordinate);assertNull(undone.name);assertNull(undone.address)
        assertEquals(origin,undone.originalCoordinate);assertNull(undone.originalName);assertNull(undone.originalAddress)
        assertEquals(Confirmation.unconfirmed,undone.confirmation);assertNull(undone.confirmedAt)
        assertNull(undone.placeSource);assertNull(undone.placeDistanceMeters)
        assertEquals("Keep notes",undone.notes);assertEquals(origin,undone.detectedCoordinate)
        val next=undone.correct(Coordinate(18.002,-66.0),"Next","Next street",at.plusSeconds(2))
        assertEquals(at.plusSeconds(2),next.confirmedAt);assertEquals(origin,next.originalCoordinate)
        assertNull(next.originalName);assertNull(next.originalAddress)
    }
    @Test fun aManualCorrectionCannotRetainAnOldProviderDistance() {
        val original=Visit(coordinate=origin,arrivedAt=at,placeSource="geoapify",placeDistanceMeters=99.0)
        val edited=original.correct(origin,"Manual",null,at.plusSeconds(1))
        assertEquals("userEntered",edited.placeSource);assertNull(edited.placeDistanceMeters)
    }
}
