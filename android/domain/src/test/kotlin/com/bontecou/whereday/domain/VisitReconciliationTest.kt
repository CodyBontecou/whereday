package com.bontecou.whereday.domain

import com.google.gson.*
import java.time.Instant
import org.junit.Assert.*
import org.junit.Test

class VisitReconciliationTest {
    private val now=Instant.ofEpochSecond(20000)
    private fun visit(id:String="a")=Visit(id,Coordinate(18.0,-66.0),Instant.ofEpochSecond(1000),Instant.ofEpochSecond(2000))
    private fun JsonObject.text(key:String)=get(key).takeUnless {it.isJsonNull}?.asString
    private fun JsonObject.visit()=Visit(get("id").asString,Coordinate(get("latitude").asDouble,get("longitude").asDouble),
        Instant.ofEpochSecond(get("arrival").asLong),get("departure").takeUnless {it.isJsonNull}?.asLong?.let(Instant::ofEpochSecond),
        name=text("name"),address=text("address"),notes=text("notes"),departureInferred=get("inferred").asBoolean,confirmation=Confirmation.valueOf(get("confirmation").asString))
    @Test fun actualSwiftMethodsMatchAcrossSeventeenSyntheticCases() {
        val document=javaClass.getResourceAsStream("/ios-visit-reconciliation.json")!!.reader().use {JsonParser.parseReader(it).asJsonObject}
        val cases=document.getAsJsonArray("cases");assertEquals(17,cases.size())
        for(element in cases) {
            val case=element.asJsonObject;val target=case.getAsJsonObject("target").visit();val source=case.getAsJsonObject("source").visit()
            val time=Instant.ofEpochSecond(case.get("now").asLong)
            assertEquals(case.get("name").asString,case.get("matches").asBoolean,VisitReconciliation.shouldMerge(target,source,time) {_,_->case.get("distance").asDouble})
            val merged=VisitReconciliation.merge(target,source)
            assertEquals(case.get("name").asString,case.getAsJsonObject("merged").visit(),merged)
            val next=listOf(source,merged).filter {it.id!=source.id && it.arrivedAt>source.arrivedAt}.minOfOrNull {it.arrivedAt}
            assertEquals(Instant.ofEpochSecond(case.get("inferredDeparture").asLong),VisitReconciliation.inferredDeparture(source,next,time))
        }
    }
    @Test fun duplicateRepairLeavesManualAndImportedVisitsSeparate() {
        for(first in VisitSource.entries) for(second in VisitSource.entries) {
            assertEquals("$first/$second",first==VisitSource.automatic && second==VisitSource.automatic,
                VisitReconciliation.shouldMerge(visit().copy(source=first),visit("b").copy(source=second),now))
        }
    }
    @Test fun splitVisitPartsNamedAfterDifferentPlacesNeverMerge() {
        val first=visit().copy(name="Trader Joe's",confirmation=Confirmation.confirmed)
        val second=visit("b").copy(arrivedAt=first.departedAt!!,departedAt=first.departedAt!!.plusSeconds(1500),name="Marshalls",confirmation=Confirmation.corrected)
        assertFalse(VisitReconciliation.shouldMerge(first,second,now) {_,_->40.0})
        assertTrue(VisitReconciliation.shouldMerge(first,second.copy(confirmation=Confirmation.unconfirmed),now) {_,_->40.0})
        assertTrue(VisitReconciliation.shouldMerge(first,second.copy(name="TRADER JOE'S"),now) {_,_->40.0})
        assertTrue(VisitReconciliation.shouldMerge(first.copy(name="Café"),second.copy(name="cafe"),now) {_,_->40.0})
    }
    @Test fun targetCorrectionAndCountryProvenanceRemainIntact() {
        val corrected=visit().copy(confirmation=Confirmation.corrected,source=VisitSource.manual,
            originalCoordinate=Coordinate(18.1,-66.1),detectedCoordinate=Coordinate(18.2,-66.2),originalName="Original",detectedName="Detected",
            confirmedAt=now,updatedAt=now,countryCode="PR",timeZone="America/Puerto_Rico",countryResolutionAttemptedAt=now)
        val source=visit("b").copy(arrivedAt=Instant.ofEpochSecond(900),departedAt=null,name="Cafe",notes="note")
        assertEquals(corrected.copy(arrivedAt=source.arrivedAt,departedAt=null,name="Cafe",notes="note"),VisitReconciliation.merge(corrected,source))
    }
    @Test fun distanceAndGapThresholdsAreInclusiveAndIdentityNeverMergesWithItself() {
        val first=visit();val other=visit("b").copy(arrivedAt=Instant.ofEpochSecond(3800),departedAt=Instant.ofEpochSecond(4000))
        assertTrue(VisitReconciliation.shouldMerge(first,other,now) {_,_->100.0})
        assertFalse(VisitReconciliation.shouldMerge(first,other,now) {_,_->100.001})
        assertFalse(VisitReconciliation.shouldMerge(first,other.copy(arrivedAt=other.arrivedAt.plusSeconds(1)),now) {_,_->99.0})
        assertFalse(VisitReconciliation.shouldMerge(first,first,now))
    }
    @Test fun inferredDeparturesUseNextArrivalAndClampFutureFallback() {
        val first=visit().copy(departedAt=null)
        assertEquals(first.arrivedAt.plusSeconds(100),VisitReconciliation.inferredDeparture(first,first.arrivedAt.plusSeconds(100),now))
        assertEquals(first.arrivedAt,VisitReconciliation.inferredDeparture(first,null,Instant.EPOCH))
    }
}
