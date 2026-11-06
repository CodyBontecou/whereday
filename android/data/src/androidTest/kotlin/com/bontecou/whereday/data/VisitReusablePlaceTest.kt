package com.bontecou.whereday.data

import androidx.room.useWriterConnection
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class VisitReusablePlaceTest {
    private val now=Instant.parse("2026-10-27T12:00:00Z")
    private val origin=Coordinate(0.0,0.0)
    private fun visit()=VisitDraft("visit","Home","Address","",origin,now.minusSeconds(3600),now.minusSeconds(60)).build(null,now)
    private fun request(existing:List<SavedPlace> = emptyList())=SavedPlaceReuseSave(SavedPlace("place","Home",origin,"Address",250.0),existing)
    private suspend fun fixture(block:suspend (HistoryDatabase,HistoryRepository)->Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;check(context.packageName!="com.bontecou.whereday")
        val db=HistoryDatabase.create(context,null)
        try {
            db.history().put(PointRow(TrackPoint(id="preserved",coordinate=origin,time=Instant.EPOCH,accuracy=5.0),null))
            block(db,HistoryRepository(db,Clock.fixed(now,ZoneOffset.UTC)))
            assertEquals(1L,db.history().totalPointCount())
        } finally {db.close()}
    }
    @Test fun createsBothAtomicallyAndExactRetryDoesNotDuplicateEitherRecord()=runBlocking {fixture {db,repo->
        val value=visit();val reuse=request()
        repo.saveVisit(value,null,reuse);repo.saveVisit(value,null,reuse)
        assertEquals(value,db.history().visit(value.id)!!.value);assertEquals(reuse.candidate,db.history().place("place")!!.value)
        assertEquals(1,db.history().places().size)
        repo.deleteVisit(value);assertNull(db.history().visit(value.id));assertEquals(reuse.candidate,db.history().place("place")!!.value)
    }}
    @Test fun updatesFirstMatchingPlaceUsingNativeDistanceAndPreservesEarlierHistory()=runBlocking {fixture {db,repo->
        val old=SavedPlace("old","HOME",Coordinate(.00135,0.0),"Old address",150.0)
        val nearer=SavedPlace("nearer","Home",origin,"Other",150.0)
        db.history().put(PlaceRow(old));db.history().put(PlaceRow(nearer))
        val historic=visit().copy(id="historic",name="Historical name");db.history().put(VisitRow(historic))
        val reuse=request(listOf(old,nearer)).copy(candidate=request().candidate.copy(radius=50.0))
        // This fixture deliberately keeps a second visit with the same interval.
        repo.saveVisit(visit(),null,reuse,allowOverlap=true)
        assertEquals(old.copy(coordinate=origin,address="Address",radius=50.0),db.history().place("old")!!.value)
        assertEquals(nearer,db.history().place("nearer")!!.value);assertEquals(historic,db.history().visit("historic")!!.value)
        repo.saveVisit(visit(),null,reuse);assertEquals(2,db.history().places().size)
    }}
    @Test fun staleLibraryAndVisitSnapshotsCannotCausePartialWrites()=runBlocking {fixture {db,repo->
        val added=SavedPlace("other","Unrelated",Coordinate(1.0,1.0));db.history().put(PlaceRow(added))
        try {repo.saveVisit(visit(),null,request());fail()} catch(_:SavedPlacesChanged) {}
        assertNull(db.history().visit("visit"));assertEquals(listOf(added),db.history().places().map {it.value})
        db.history().put(VisitRow(visit().copy(name="Concurrent visit")))
        try {repo.saveVisit(visit(),null,request(listOf(added)));fail()} catch(_:VisitChanged) {}
        assertEquals("Concurrent visit",db.history().visit("visit")!!.value.name);assertEquals(listOf(added),db.history().places().map {it.value})
    }}
    @Test fun failedPlaceInsertRollsBackTheNewVisit()=runBlocking {fixture {db,repo->
        db.useWriterConnection {connection->connection.usePrepared("CREATE TRIGGER fail_place BEFORE INSERT ON places BEGIN SELECT RAISE(ABORT,'synthetic insert failure'); END") {it.step()}}
        try {repo.saveVisit(visit(),null,request());fail()} catch(_:Exception) {}
        assertNull(db.history().visit("visit"));assertTrue(db.history().places().isEmpty())
    }}
    @Test fun failedPlaceUpdateRollsBackTheNewVisitAndPreservesTheOldPlace()=runBlocking {fixture {db,repo->
        val old=SavedPlace("old","Home",origin,"Old address");db.history().put(PlaceRow(old))
        db.useWriterConnection {connection->connection.usePrepared("CREATE TRIGGER fail_place BEFORE UPDATE ON places BEGIN SELECT RAISE(ABORT,'synthetic update failure'); END") {it.step()}}
        try {repo.saveVisit(visit(),null,request(listOf(old)));fail()} catch(_:Exception) {}
        assertNull(db.history().visit("visit"));assertEquals(old,db.history().place("old")!!.value)
    }}
    @Test fun retryCannotUndoLaterPlaceEditsAndUnrelatedReusablePayloadsAreRejected()=runBlocking {fixture {db,repo->
        val reuse=request();repo.saveVisit(visit(),null,reuse)
        val edited=reuse.candidate.copy(address="Later edit");db.history().put(PlaceRow(edited))
        try {repo.saveVisit(visit(),null,reuse);fail()} catch(_:VisitChanged) {}
        assertEquals(edited,db.history().place(edited.id)!!.value)
        val unrelated=visit().copy(id="other-visit")
        try {repo.saveVisit(unrelated,null,request(listOf(edited)).copy(candidate=reuse.candidate.copy(name="Unrelated")));fail()} catch(_:IllegalArgumentException) {}
        assertNull(db.history().visit(unrelated.id))
    }}
}
