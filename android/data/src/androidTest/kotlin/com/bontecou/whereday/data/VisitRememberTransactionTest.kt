package com.bontecou.whereday.data

import androidx.room.useWriterConnection
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class VisitRememberTransactionTest {
    private val now=Instant.parse("2026-10-29T12:00:00Z")
    private val origin=Coordinate(18.0,-66.0)
    private val original=Visit("reviewed",origin,now.minusSeconds(3600),now.minusSeconds(60),"Cafe QA","Visit street")
    private suspend fun fixture(block:suspend (HistoryDatabase,HistoryRepository)->Unit) {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName=="com.bontecou.whereday.data.test")
        val db=HistoryDatabase.create(target,null)
        try {db.history().put(VisitRow(original));block(db,HistoryRepository(db,Clock.fixed(now,ZoneOffset.UTC)))} finally {db.close()}
    }
    @Test fun confirmationAndItsSavedPlaceCommitExactlyOnce()=runBlocking {fixture {db,repo->
        val value=original.copy(confirmation=Confirmation.confirmed,confirmedAt=now,updatedAt=now)
        repo.saveVisit(value,original,rememberConfirmedPlace=true)
        repo.saveVisit(value,original,rememberConfirmedPlace=true)
        assertEquals(value,db.history().visit(original.id)!!.value)
        val place=db.history().places().single().value
        assertEquals("Cafe QA",place.name);assertEquals(origin,place.coordinate);assertEquals(150.0,place.radius,0.0)
    }}
    @Test fun managedMatchIsReadFreshAndNeverMovedOrResized()=runBlocking {fixture {db,repo->
        val managed=SavedPlace("managed","CAFE QA",Coordinate(18.004,-66.0),"Managed address",500.0)
        db.history().put(PlaceRow(managed))
        val value=original.correct(origin,"cafe qa","Corrected visit address",now)
        repo.saveVisit(value,original,rememberConfirmedPlace=true)
        assertEquals(listOf(managed),db.history().places().map {it.value})
        assertEquals("Corrected visit address",db.history().visit(original.id)!!.value.address)
    }}
    @Test fun placeInsertFailureRollsBackTheExistingVisitCorrection()=runBlocking {fixture {db,repo->
        db.useWriterConnection {it.usePrepared("CREATE TRIGGER fail_review_place BEFORE INSERT ON places BEGIN SELECT RAISE(ABORT,'synthetic review failure'); END") {statement->statement.step()}}
        try {repo.saveVisit(original.correct(origin,"Reviewed",null,now),original,rememberConfirmedPlace=true);fail("Expected insert failure")}
        catch(_:androidx.sqlite.SQLiteException) {}
        assertEquals(original,db.history().visit(original.id)!!.value);assertTrue(db.history().places().isEmpty())
    }}
    @Test fun staleVisitCannotCreateASavedPlace()=runBlocking {fixture {db,repo->
        val latest=original.copy(notes="Concurrent note");db.history().put(VisitRow(latest))
        try {repo.saveVisit(original.correct(origin,"Reviewed",null,now),original,rememberConfirmedPlace=true);fail("Expected conflict")}
        catch(_:VisitChanged) {}
        assertEquals(latest,db.history().visit(original.id)!!.value);assertTrue(db.history().places().isEmpty())
    }}
    @Test fun notesAndUndoDoNotRememberOrDeleteLibraryEntries()=runBlocking {fixture {db,repo->
        val note=original.copy(notes="Only notes",updatedAt=now);repo.saveVisit(note,original)
        assertTrue(db.history().places().isEmpty())
        val corrected=note.correct(Coordinate(18.001,-66.0),"Business",null,now)
        repo.saveVisit(corrected,note,rememberConfirmedPlace=true)
        val places=db.history().places().map {it.value}
        val undone=corrected.undoCorrection(now.plusSeconds(1));repo.saveVisit(undone,corrected)
        assertEquals(places,db.history().places().map {it.value});assertEquals(origin,undone.originalCoordinate)
    }}
    @Test fun automaticRememberCannotBeRequestedForANewOrUnconfirmedVisit()=runBlocking {fixture {db,repo->
        val manual=VisitDraft("new","New","","",origin,now.minusSeconds(3600),now.minusSeconds(60)).build(null,now)
        for((value,expected) in listOf(manual to null,original.copy(notes="Still unconfirmed") to original)) {
            try {repo.saveVisit(value,expected,rememberConfirmedPlace=true);fail("Accepted invalid remember intent")}
            catch(_:IllegalArgumentException) {}
        }
        assertEquals(original,db.history().visit(original.id)!!.value);assertNull(db.history().visit("new"));assertTrue(db.history().places().isEmpty())
    }}
}
