package com.bontecou.whereday.data

import androidx.room.useWriterConnection
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class VisitEditingTest {
    private val now=Instant.parse("2026-10-27T12:00:00Z")
    private val coordinate=Coordinate(0.0,0.0)
    private fun manual()=VisitDraft("manual","Home","Address","",coordinate,now.minusSeconds(3600),now.minusSeconds(60)).build(null,now)
    private suspend fun fixture(block:suspend (HistoryDatabase,HistoryRepository)->Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName!="com.bontecou.whereday")
        val db=HistoryDatabase.create(context,null)
        try {
            db.history().put(PointRow(TrackPoint(id="preserved-point",coordinate=coordinate,time=Instant.EPOCH,accuracy=5.0),null))
            block(db,HistoryRepository(db,Clock.fixed(now,ZoneOffset.UTC)))
            assertEquals(1L,db.history().totalPointCount())
        } finally {db.close()}
    }
    @Test fun stableCreateRetriesAreIdempotentAndIdentityCollisionsCannotOverwrite()=runBlocking {fixture {db,repo->
        val visit=manual();repo.saveVisit(visit,null);repo.saveVisit(visit,null)
        assertEquals(listOf(visit),db.history().exportFirstVisits(Instant.EPOCH,now).map {it.value})
        try {repo.saveVisit(visit.copy(name="Collision"),null);fail()} catch(_:VisitChanged) {}
        assertEquals(visit,db.history().visit(visit.id)!!.value)
        try {repo.saveVisit(visit.copy(id="future",arrivedAt=now.plusSeconds(1),departedAt=now.plusSeconds(2)),null);fail()} catch(_:IllegalArgumentException) {}
        assertNull(db.history().visit("future"))
    }}
    @Test fun analysisOrRecordingChangesRejectStaleEditsAndDeletesWithoutLosingTheNewData()=runBlocking {fixture {db,repo->
        val visit=manual();repo.saveVisit(visit,null)
        val resolved=visit.copy(countryCode="PR",timeZone="America/Puerto_Rico",countryResolutionAttemptedAt=now)
        db.history().put(VisitRow(resolved))
        try {repo.saveVisit(visit.copy(notes="Stale"),visit);fail()} catch(_:VisitChanged) {}
        try {repo.deleteVisit(visit);fail()} catch(_:VisitChanged) {}
        assertEquals(resolved,db.history().visit(visit.id)!!.value)
        repo.deleteVisit(resolved)
        try {repo.saveVisit(visit,visit);fail()} catch(_:VisitChanged) {}
        assertNull(db.history().visit(visit.id))
    }}
    @Test fun manuallyClosingADetectedVisitResetsOnlyItsDetectorState()=runBlocking {fixture {db,repo->
        val visit=Visit("detected",coordinate,now.minusSeconds(3600),detectedCoordinate=coordinate)
        db.history().put(VisitRow(visit));db.history().put(DwellRow(state=DwellState(coordinate,visit.arrivedAt,now.minusSeconds(60),visit.id)))
        val closed=visit.copy(departedAt=now.minusSeconds(30),updatedAt=now)
        repo.saveVisit(closed,visit)
        assertEquals(closed,db.history().visit(visit.id)!!.value);assertEquals(DwellState(),db.history().dwell()!!.state)
    }}
    @Test fun deletingAnUnrelatedVisitKeepsActiveDwellAndDeletingTheActiveVisitClearsIt()=runBlocking {fixture {db,repo->
        val first=manual();val active=first.copy(id="active",departedAt=null)
        db.history().put(VisitRow(first));db.history().put(VisitRow(active))
        val state=DwellState(coordinate,active.arrivedAt,now.minusSeconds(60),active.id);db.history().put(DwellRow(state=state))
        repo.deleteVisit(first);assertEquals(state,db.history().dwell()!!.state)
        repo.deleteVisit(active);assertEquals(DwellState(),db.history().dwell()!!.state)
    }}
    @Test fun aDetectorWriteFailureRollsBackTheVisitEdit()=runBlocking {fixture {db,repo->
        val visit=Visit("detected",coordinate,now.minusSeconds(3600));val state=DwellState(coordinate,visit.arrivedAt,now.minusSeconds(60),visit.id)
        db.history().put(VisitRow(visit));db.history().put(DwellRow(state=state))
        db.useWriterConnection {connection->connection.usePrepared("CREATE TRIGGER fail_dwell BEFORE INSERT ON dwell WHEN new.state_visitId IS NULL BEGIN SELECT RAISE(ABORT,'synthetic dwell failure'); END") {it.step()}}
        try {repo.saveVisit(visit.copy(departedAt=now.minusSeconds(30)),visit);fail()} catch(_:Exception) {}
        assertEquals(visit,db.history().visit(visit.id)!!.value);assertEquals(state,db.history().dwell()!!.state)
    }}
    @Test fun subMillisecondAuditAndEventTimesDoNotBreakRetriesOrSnapshotChecks()=runBlocking {fixture {db,repo->
        val raw=manual().let {it.copy(arrivedAt=it.arrivedAt.plusNanos(123456),departedAt=it.departedAt!!.plusNanos(789123),confirmedAt=now.minusSeconds(1).plusNanos(123456),updatedAt=now.minusSeconds(1).plusNanos(654321))}
        val stored=repo.saveVisit(raw,null)
        assertEquals(0,stored.arrivedAt.nano%1_000_000);assertEquals(0,stored.updatedAt!!.nano%1_000_000)
        assertEquals(stored,repo.saveVisit(raw,null))
        val changed=raw.copy(notes="Changed",updatedAt=now.plusNanos(789123))
        val saved=repo.saveVisit(changed,raw)
        assertEquals("Changed",saved.notes);assertEquals(saved,db.history().visit(raw.id)!!.value)
        repo.deleteVisit(changed);assertNull(db.history().visit(raw.id))
    }}

}
