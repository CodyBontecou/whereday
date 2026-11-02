package com.bontecou.whereday.domain

import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class VisitDraftTest {
    private val now=Instant.parse("2026-10-27T12:00:00Z")
    private val coordinate=Coordinate(0.0,0.0)
    private fun draft()=VisitDraft("stable-id","  Home  ","  Address  ","  indented note  ",coordinate,now.minusSeconds(3600),now.minusSeconds(60))
    @Test fun namedPastVisitHasStableIdentityConfirmationAndAuditTimes() {
        val result=draft().copy(placeSource="coreLocationGeocode").build(null,now)
        assertEquals("stable-id",result.id);assertEquals("Home",result.name);assertEquals("Address",result.address)
        assertEquals("  indented note  ",result.notes);assertEquals(VisitSource.manual,result.source);assertEquals(Confirmation.confirmed,result.confirmation)
        assertEquals(now,result.updatedAt);assertEquals(now,result.confirmedAt);assertEquals("coreLocationGeocode",result.placeSource)
        assertNull(result.originalCoordinate);assertNull(result.countryCode);assertFalse(result.departureInferred)
    }
    @Test fun pastVisitRequiresANameLocationAndPastOrderedTimes() {
        val invalid=listOf(draft().copy(name="  "),draft().copy(coordinate=null),draft().copy(departedAt=null),draft().copy(arrivedAt=now.plusSeconds(1)),draft().copy(departedAt=now.plusSeconds(1)),draft().copy(departedAt=now.minusSeconds(4000)))
        invalid.forEach {try {it.build(null,now);fail("Accepted $it")} catch(_:IllegalArgumentException) {}}
    }
    @Test fun validationReportsStableProblemsWithoutChangingLegacyDescriptions() {
        val cases=listOf(
            VisitDraftProblem.coordinate to draft().copy(coordinate=null),VisitDraftProblem.placeName to draft().copy(name=" "),
            VisitDraftProblem.departure to draft().copy(departedAt=null),VisitDraftProblem.times to draft().copy(arrivedAt=now.plusSeconds(1)),
        )
        for((problem,input) in cases) {
            val error=assertThrows(InvalidVisitDraft::class.java) {input.build(null,now)}
            assertEquals(problem,error.problem);assertEquals(problem.description,error.message)
        }
        val original=Visit("other-id",coordinate,now.minusSeconds(3600))
        assertEquals(VisitDraftProblem.identity,assertThrows(InvalidVisitDraft::class.java) {draft().build(original,now)}.problem)
    }
    @Test fun notesAndTimeEditsPreserveDetectedPlaceConfirmationAndInferenceUnlessDepartureChanges() {
        val original=Visit("stable-id",coordinate,now.minusSeconds(3600),now.minusSeconds(60),"Home","Address","Old note",placeSource="detected",placeDistanceMeters=12.0,countryCode="PR",timeZone="America/Puerto_Rico",departureInferred=true)
        val result=draft().build(original,now)
        assertEquals(Confirmation.unconfirmed,result.confirmation);assertNull(result.originalCoordinate);assertNull(result.confirmedAt)
        assertEquals("detected",result.placeSource);assertEquals(original.placeDistanceMeters,result.placeDistanceMeters);assertEquals("PR",result.countryCode);assertTrue(result.departureInferred)
        assertFalse(draft().copy(departedAt=now.minusSeconds(30)).build(original,now).departureInferred)
    }
    @Test fun confirmingAVisitDoesNotInventACorrectionSnapshot() {
        val original=Visit("stable-id",coordinate,now.minusSeconds(3600),now.minusSeconds(60),"Home","Address")
        val result=draft().build(original,now,true)
        assertEquals(Confirmation.confirmed,result.confirmation);assertEquals(now,result.confirmedAt);assertNull(result.originalCoordinate)
    }
    @Test fun locationCorrectionsRetainOriginalAndDetectedValuesAndInvalidateGeography() {
        val original=Visit("stable-id",coordinate,now.minusSeconds(3600),now.minusSeconds(60),"Detected","Old address",detectedCoordinate=coordinate,detectedName="Detected",placeDistanceMeters=40.0,countryCode="PR",timeZone="America/Puerto_Rico",countryResolutionAttemptedAt=now.minusSeconds(100))
        val first=draft().copy(coordinate=Coordinate(1.0,1.0),placeSource="coreLocationGeocode").build(original,now)
        val second=draft().copy(coordinate=Coordinate(2.0,2.0),name="Other").build(first,now.plusSeconds(1))
        assertEquals(Confirmation.corrected,second.confirmation);assertEquals(coordinate,second.originalCoordinate);assertEquals("Detected",second.originalName)
        assertEquals("Old address",second.originalAddress);assertEquals(coordinate,second.detectedCoordinate);assertEquals("Detected",second.detectedName)
        assertNull(second.placeDistanceMeters);assertNull(second.countryCode);assertNull(second.timeZone);assertNull(second.countryResolutionAttemptedAt)
        assertEquals("coreLocationGeocode",first.placeSource)
    }
    @Test fun anExistingOpenVisitCanRemainOpenAndCannotChangeIdentity() {
        val original=Visit("stable-id",coordinate,now.minusSeconds(3600),name="Home",address="Address")
        assertNull(draft().copy(departedAt=null).build(original,now).departedAt)
        try {draft().copy(id="different").build(original,now);fail()} catch(_:IllegalArgumentException) {}
    }
    @Test fun firstSelectionKeepsTypedFallbacksAndMovingAnExistingPinDropsOldMetadata() {
        val typed=draft().copy(coordinate=null)
        val pin=PlaceLocationSelection(coordinate,true)
        val first=typed.selectLocation(pin)
        assertEquals(typed.name,first.name);assertEquals(typed.address,first.address);assertEquals("userEntered",first.placeSource)
        val moved=first.selectLocation(PlaceLocationSelection(Coordinate(1.0,1.0),true))
        assertEquals("",moved.name);assertEquals("",moved.address)
        val incompleteProvider=typed.selectLocation(PlaceLocationSelection(coordinate,true,"Provider",null,"coreLocationGeocode"))
        assertEquals("Provider",incompleteProvider.name);assertEquals(typed.address,incompleteProvider.address);assertEquals("userEntered",incompleteProvider.placeSource)
        val resolved=first.selectLocation(PlaceLocationSelection(Coordinate(2.0,2.0),true,"New place","New address","coreLocationGeocode"))
        assertEquals("New place",resolved.name);assertEquals("New address",resolved.address);assertEquals("coreLocationGeocode",resolved.placeSource)
        assertEquals(resolved,resolved.selectLocation(PlaceLocationSelection(resolved.coordinate!!)))
    }

    @Test fun nearbySelectionRetainsProviderDistanceAndOriginalCorrectionSnapshot() {
        val original=Visit("stable-id",coordinate,now.minusSeconds(3600),now.minusSeconds(60),"Detected","Old",placeSource="coreLocationGeocode",placeDistanceMeters=90.0)
        val suggestion=NearbyPlaceSuggestion("business","Business","New street",Coordinate(.001,0.0),111.0)
        val selected=draft().selectNearby(suggestion,"geoapify")
        val saved=selected.build(original,now)
        assertEquals(suggestion.coordinate,saved.coordinate);assertEquals("Business",saved.name);assertEquals("New street",saved.address)
        assertEquals("geoapify",saved.placeSource);assertEquals(111.0,saved.placeDistanceMeters!!,0.0)
        assertEquals(coordinate,saved.originalCoordinate);assertEquals("Detected",saved.originalName);assertEquals("Old",saved.originalAddress)
        assertEquals(Confirmation.corrected,saved.confirmation)
        val undone=saved.undoCorrection(now.plusSeconds(1))
        assertEquals(original.coordinate,undone.coordinate);assertEquals(original.name,undone.name);assertEquals(original.address,undone.address)
        assertNull(undone.placeSource);assertNull(undone.placeDistanceMeters)
        val created=selected.build(null,now)
        assertEquals(111.0,created.placeDistanceMeters!!,0.0);assertEquals("geoapify",created.placeSource)
    }
    @Test fun choosingAnIdenticalBusinessStillRecordsExplicitCorrectionProvenance() {
        val original=Visit("stable-id",coordinate,now.minusSeconds(3600),now.minusSeconds(60),"Home","Address")
        val selected=draft().selectNearby(NearbyPlaceSuggestion("same","Home","Address",coordinate,0.0),"geoapify")
        val saved=selected.build(original,now)
        assertEquals(Confirmation.corrected,saved.confirmation);assertEquals("geoapify",saved.placeSource)
        assertEquals(0.0,saved.placeDistanceMeters!!,0.0);assertEquals(coordinate,saved.originalCoordinate)
        val notes=VisitDraft(saved.id,saved.name!!,saved.address!!,"Only notes",saved.coordinate,saved.arrivedAt,saved.departedAt,saved.placeSource!!,saved.placeDistanceMeters).build(saved,now.plusSeconds(1))
        assertEquals(saved.placeDistanceMeters,notes.placeDistanceMeters);assertEquals(saved.confirmedAt,notes.confirmedAt)
    }
    @Test fun movingAPinInvalidatesNearbyProvenanceButAnUnchangedPinPreservesIt() {
        val selected=draft().selectNearby(NearbyPlaceSuggestion("business","Business",null,coordinate,12.0),"geoapify")
        assertEquals(selected,selected.selectLocation(PlaceLocationSelection(coordinate)))
        val moved=selected.selectLocation(PlaceLocationSelection(Coordinate(1.0,1.0)))
        assertNull(moved.placeDistanceMeters);assertEquals("userEntered",moved.placeSource);assertFalse(moved.placeSelectionChanged)
        val reverse=selected.selectLocation(PlaceLocationSelection(coordinate,true,"Address name","Street","coreLocationGeocode"))
        assertNull(reverse.placeDistanceMeters);assertFalse(reverse.placeSelectionChanged)
        for(distance in listOf(-1.0,Double.NaN,Double.POSITIVE_INFINITY)) assertThrows(IllegalArgumentException::class.java) {selected.copy(placeDistanceMeters=distance)}
    }

}
