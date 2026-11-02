package com.bontecou.whereday

import com.bontecou.whereday.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class ReplayOpeningTest {
    private val at=Instant.parse("2026-11-02T12:00:00.123456789Z")
    @Test fun exactOpeningRoundTripsAllRequestKinds() {
        for(request in listOf(ReplayRequest.Range(HistoryRange(at,at.plusSeconds(10)),true),ReplayRequest.Outing("exact"),ReplayRequest.Inferred(InferredOutingHeader("inferred",at,at.plusSeconds(10),PointSummary(9,2,12.5,at,at.plusSeconds(9)))))) {
            val opening=ReplayOpening("opening-owner",request)
            assertEquals(opening,ReplayOpening.restored(ReplayOpening.saved(opening)))
        }
    }
    @Test fun invalidAndFutureSavedOpeningsDoNotOpenAReplay() {
        for(values in listOf(emptyList(),listOf(2,"x","outing","id"),listOf(1,"x","outing",""),listOf(1,"x","unknown"),listOf(1,"x","range",at.toString(),at.toString(),true),listOf(1,"x","range","bad",at.toString(),true))) assertNull(ReplayOpening.restored(values))
    }
    @Test fun inferredSummaryRetainsNullTimesAndRejectsInvalidCounts() {
        val opening=ReplayOpening("inferred",ReplayRequest.Inferred(InferredOutingHeader("id",at,at,PointSummary())))
        val saved=ReplayOpening.saved(opening)
        assertEquals(opening,ReplayOpening.restored(saved))
        assertNull(ReplayOpening.restored(saved.toMutableList().apply {this[7]=1L}))
        assertNull(ReplayOpening.restored(saved.toMutableList().apply {this[8]=Double.NaN}))
    }
}
