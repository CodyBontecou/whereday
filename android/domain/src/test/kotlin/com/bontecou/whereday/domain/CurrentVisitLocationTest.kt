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
}
