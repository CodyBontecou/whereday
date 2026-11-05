package com.bontecou.whereday.data

import androidx.room.useWriterConnection
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.time.*
import java.util.UUID

class VisitDetectorHandoffTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db:HistoryDatabase
    private lateinit var repo:HistoryRepository
    private lateinit var name:String
    private val start=Instant.parse("2026-10-29T12:00:00Z")
    private val first=Coordinate(18.0,-66.0)
    private val second=Coordinate(18.01,-66.0)
    @Before fun setup() {
        check(context.packageName=="com.bontecou.whereday.data.test")
        name="visit-handoff-${UUID.randomUUID()}.db"
        db=HistoryDatabase.create(context,name)
        repo=HistoryRepository(db,Clock.fixed(start,ZoneOffset.UTC))
    }
    @After fun close() {db.close();context.deleteDatabase(name)}
    private fun point(seconds:Long,where:Coordinate=first)=TrackPoint(coordinate=where,time=start.plusSeconds(seconds),accuracy=5.0)
    private fun later() {repo=HistoryRepository(db,Clock.fixed(start.plusSeconds(1000),ZoneOffset.UTC))}
    private suspend fun open():Visit {
        repo.start(0);later();repo.ingest(listOf(point(0),point(300)))
        return repo.dao.openVisits().single().value
    }

    @Test fun nextStopSurvivesDatabaseReopenWithItsFirstEvidenceAndSavedPlaceStamp()=runBlocking {
        val old=open()
        repo.dao.put(PlaceRow(SavedPlace(name="Synthetic second stop",coordinate=second,address="QA only")))
        repo.ingest(listOf(point(360,second)))
        val expected=repo.dao.dwell()!!.state
        assertEquals(second,expected.nextAnchor);assertEquals(start.plusSeconds(360),expected.nextCandidateSince)
        db.close();db=HistoryDatabase.create(context,name);later()
        assertEquals(expected,repo.dao.dwell()!!.state)
        repo.ingest(listOf(point(480,second)))
        assertEquals(start.plusSeconds(360),repo.dao.visit(old.id)!!.value.departedAt)
        db.close();db=HistoryDatabase.create(context,name);later()
        repo.ingest(listOf(point(660,second)))
        val next=repo.dao.openVisits().single().value
        assertNotEquals(old.id,next.id);assertEquals(start.plusSeconds(360),next.arrivedAt)
        assertEquals("Synthetic second stop",next.name);assertEquals("QA only",next.address)
        assertEquals(Confirmation.confirmed,next.confirmation)
        assertEquals(next.id,repo.dao.dwell()!!.state.visitId)
        assertEquals(5L,repo.dao.totalPointCount())
    }

    @Test fun batchedDepartureAndArrivalCommitTogetherAndRepeatedBatchDoesNotDuplicate()=runBlocking {
        val old=open()
        val batch=listOf(point(660,second),point(360,second))
        repo.ingest(batch)
        assertEquals(start.plusSeconds(360),repo.dao.visit(old.id)!!.value.departedAt)
        val next=repo.dao.openVisits().single().value
        assertEquals(start.plusSeconds(360),next.arrivedAt)
        assertEquals(next.id,repo.dao.dwell()!!.state.visitId)
        repo.ingest(batch)
        assertEquals(2L,repo.dao.totalVisitCount());assertEquals(4L,repo.dao.totalPointCount())
        assertEquals(next,repo.dao.openVisits().single().value)
    }

    @Test fun failureSavingNextArrivalRollsBackOldDeparturePointsAndDetectorThenRetrySucceeds()=runBlocking {
        val old=open()
        repo.ingest(listOf(point(360,second)))
        val state=repo.dao.dwell()!!.state;val recording=repo.dao.recording()
        db.useWriterConnection {it.usePrepared("CREATE TRIGGER reject_next_stop BEFORE INSERT ON visits WHEN NEW.value_coordinate='18.01|-66.0' BEGIN SELECT RAISE(ABORT,'synthetic handoff failure'); END") {statement->statement.step()}}
        val last=point(660,second)
        var failed=false
        try {repo.ingest(listOf(last))} catch(_:Exception) {failed=true}
        assertTrue(failed);assertEquals(old,repo.dao.visit(old.id)!!.value)
        assertEquals(state,repo.dao.dwell()!!.state);assertEquals(recording,repo.dao.recording())
        assertEquals(1L,repo.dao.totalVisitCount());assertEquals(3L,repo.dao.totalPointCount())
        db.useWriterConnection {it.usePrepared("DROP TRIGGER reject_next_stop") {statement->statement.step()}}
        repo.ingest(listOf(last))
        assertEquals(2L,repo.dao.totalVisitCount());assertEquals(4L,repo.dao.totalPointCount())
        assertEquals(start.plusSeconds(360),repo.dao.openVisits().single().value.arrivedAt)
    }
}
