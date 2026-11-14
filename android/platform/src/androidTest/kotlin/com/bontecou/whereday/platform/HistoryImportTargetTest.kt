package com.bontecou.whereday.platform

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.ImportResult
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class HistoryImportTargetTest {
    private val now=Instant.parse("2026-10-26T12:00:00Z")
    private val point=TrackPoint(coordinate=Coordinate(18.0,-66.0),time=now,accuracy=5.0)
    private val visit=Visit(coordinate=point.coordinate,arrivedAt=now,name="Local correction",notes="Keep local note")
    private suspend fun fixture(block:suspend(HistoryDatabase,DatabaseHistoryImportTarget)->Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;check(context.packageName=="com.bontecou.whereday.platform.test")
        val db=HistoryDatabase.create(context,null)
        try {db.history().put(PointRow(point,"session"));db.history().put(VisitRow(visit));block(db,DatabaseHistoryImportTarget(db))} finally {db.close()}
    }
    @Test fun reviewDoesNotWriteAndDefaultPolicyKeepsExistingCorrectionsAndSessionMembership():Unit=runBlocking {fixture {db,target->
        val incoming=ImportResult(listOf(visit.copy(name="Imported")),listOf(point.copy(accuracy=9.0),point.copy(id="new-point")))
        val baseline=target.inspect(incoming) {}
        assertEquals(1L,db.history().totalPointCount());assertEquals(visit,db.history().visit(visit.id)!!.value)
        assertEquals(HistoryImportCounts(kept=3),target.apply(incoming,baseline,HistoryImportPolicy.keepExisting) {})
        assertEquals(visit,db.history().visit(visit.id)!!.value);assertEquals("session",db.history().point(point.id)!!.sessionId)
        assertEquals(1L,db.history().totalPointCount())
    }}
    @Test fun replacementPreservesPointSessionAndClosesDetectorOwnershipOfAClosedVisit():Unit=runBlocking {fixture {db,target->
        db.history().put(DwellRow(state=DwellState(visit.coordinate,now,now,visit.id)))
        val incoming=ImportResult(listOf(visit.copy(departedAt=now.plusSeconds(60))),listOf(point.copy(accuracy=9.0)))
        val baseline=target.inspect(incoming) {};target.apply(incoming,baseline,HistoryImportPolicy.replaceExisting) {}
        assertEquals(9.0,db.history().point(point.id)!!.value.accuracy,0.0)
        assertEquals("session",db.history().point(point.id)!!.sessionId);assertNull(db.history().dwell()!!.state.visitId)
    }}
    @Test fun changedMatchesRejectReplacementWithoutWritingAnyNewRows():Unit=runBlocking {fixture {db,target->
        val incoming=ImportResult(listOf(visit.copy(id="new-visit")),listOf(point.copy(accuracy=9.0)))
        val baseline=target.inspect(incoming) {};db.history().put(PointRow(point.copy(accuracy=11.0),"session"))
        try {target.apply(incoming,baseline,HistoryImportPolicy.replaceExisting) {};fail("Stale replacement accepted")} catch(_:HistoryImportChanged) {}
        assertNull(db.history().visit("new-visit"));assertEquals(11.0,db.history().point(point.id)!!.value.accuracy,0.0)
    }}
    @Test fun cancellationDuringWritesRollsBackTheWholeImport():Unit=runBlocking {fixture {db,target->
        val incoming=ImportResult(emptyList(),(1..300).map {point.copy(id="new-$it",time=now.plusSeconds(it.toLong()))})
        val baseline=target.inspect(incoming) {};var checks=0
        try {target.apply(incoming,baseline,HistoryImportPolicy.keepExisting) {if(++checks>20) throw HistoryImportCanceled()};fail("Canceled import committed")} catch(_:HistoryImportCanceled) {}
        assertEquals(1L,db.history().totalPointCount());assertEquals(point,db.history().point(point.id)!!.value)
    }}
}
