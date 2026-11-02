package com.bontecou.whereday.data

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.time.*

class VisitOverlapTest {
    private lateinit var db:HistoryDatabase
    private lateinit var repo:HistoryRepository
    private val now=Instant.parse("2026-10-31T12:00:00Z")
    private val pin=Coordinate(18.0,-66.0)
    private fun visit(id:String,from:Long,through:Long?,source:VisitSource=VisitSource.manual)=Visit(id,pin,now.plusSeconds(from),through?.let {now.plusSeconds(it)},id,
        source=source,confirmation=Confirmation.confirmed,confirmedAt=now,updatedAt=now)
    @Before fun setup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.data.test")
        db=HistoryDatabase.create(context,null);repo=HistoryRepository(db,Clock.fixed(now,ZoneOffset.UTC))
    }
    @After fun cleanup() {db.close()}
    private suspend fun rejected(block:suspend ()->Unit) {
        try {block();fail("An overlapping manual time mutation needs explicit admission")} catch(_:VisitOverlap) {}
    }
    @Test fun newManualVisitCannotSilentlyOverlapStoredHistory()=runBlocking {
        val existing=visit("existing",-7200,-3600);db.history().put(VisitRow(existing))
        val candidate=visit("candidate",-4000,-2000)
        rejected {repo.saveVisit(candidate,null)}
        assertNull(db.history().visit(candidate.id));assertEquals(existing,db.history().visit(existing.id)!!.value)
    }
    @Test fun manualTimeChangesCannotSilentlyOverlapAnotherVisit()=runBlocking {
        val original=visit("original",-7200,-3600);val next=visit("next",-3600,-1800)
        db.history().put(VisitRow(original));db.history().put(VisitRow(next))
        rejected {repo.saveVisit(original.copy(departedAt=now.minusSeconds(1700)),original)}
        assertEquals(original,db.history().visit(original.id)!!.value)
    }
    @Test fun notesAndPlaceOnlyEditsOfAlreadyOverlappingManualVisitsRemainAllowed()=runBlocking {
        val original=visit("original",-7200,-1800)
        db.history().put(VisitRow(original));db.history().put(VisitRow(visit("other",-3600,-1000)))
        val edited=original.copy(notes="Reviewed notes",name="Reviewed place")
        assertEquals(edited,repo.saveVisit(edited,original))
    }
    @Test fun detectedVisitTimeEditsDoNotAcquireManualOverlapRestrictions()=runBlocking {
        val original=visit("automatic",-7200,-3600,VisitSource.automatic)
        db.history().put(VisitRow(original));db.history().put(VisitRow(visit("other",-3600,-1000)))
        val edited=original.copy(departedAt=now.minusSeconds(1700))
        assertEquals(edited,repo.saveVisit(edited,original))
    }
    @Test fun explicitAdmissionKeepsBothVisitsAndExactRetriesRemainIdempotent()=runBlocking {
        val existing=visit("existing",-7200,-3600);db.history().put(VisitRow(existing))
        val candidate=visit("candidate",-4000,-2000)
        assertEquals(candidate,repo.saveVisit(candidate,null,allowOverlap=true))
        assertEquals(candidate,repo.saveVisit(candidate,null))
        assertEquals(2L,db.history().totalVisitCount());assertEquals(existing,db.history().visit(existing.id)!!.value)
        val newer=candidate.copy(notes="Concurrent change");db.history().put(VisitRow(newer))
        try {repo.saveVisit(candidate.copy(name="Stale"),candidate,allowOverlap=true);fail("Admission bypassed stale visit guard")} catch(_:VisitChanged) {}
        assertEquals(newer,db.history().visit(candidate.id)!!.value)
    }
    @Test fun selfIsExcludedAndAnAdjacentBoundaryDoesNotNeedAdmission()=runBlocking {
        val original=visit("original",-7200,-3600);val next=visit("next",-3600,-1800)
        db.history().put(VisitRow(original));db.history().put(VisitRow(next))
        val earlier=original.copy(arrivedAt=now.minusSeconds(8000))
        assertEquals(earlier,repo.saveVisit(earlier,original))
        try {repo.saveVisit(earlier.copy(departedAt=now.minusSeconds(3500)),earlier);fail()} catch(e:VisitOverlap) {assertEquals(next,e.conflicting)}
        assertEquals(earlier,db.history().visit(original.id)!!.value)
    }
    @Test fun rejectedOverlapDoesNotCreateAReusablePlaceAndAdmissionCommitsBothAtomically()=runBlocking {
        db.history().put(VisitRow(visit("existing",-7200,null)))
        val candidate=visit("candidate",-4000,-2000)
        val reuse=SavedPlaceReuseSave(SavedPlace("saved",candidate.name!!,pin),emptyList())
        rejected {repo.saveVisit(candidate,null,reuse)}
        assertNull(db.history().visit(candidate.id));assertTrue(db.history().places().isEmpty())
        repo.saveVisit(candidate,null,reuse,allowOverlap=true)
        assertEquals(candidate,db.history().visit(candidate.id)!!.value);assertEquals(1,db.history().places().size)
        repo.saveVisit(candidate,null,reuse);assertEquals(1,db.history().places().size)
    }
    @Test fun concurrentRepositoryEditorsCannotBothBypassAdmission()=runBlocking {
        val other=HistoryRepository(db,Clock.fixed(now,ZoneOffset.UTC))
        val results=(0..9).map {index->async(Dispatchers.Default) {
            try {(if(index%2==0) repo else other).saveVisit(visit("candidate-$index",-3600,-1000),null);true}
            catch(_:VisitOverlap) {false}
        }}.awaitAll()
        assertEquals(1,results.count {it});assertEquals(1L,db.history().totalVisitCount())
    }
    @Test fun originalSubMillisecondFutureTimesAndSourceChangesCannotCommit()=runBlocking {
        val candidate=visit("future",-1,null).copy(arrivedAt=now.plusNanos(1))
        try {repo.saveVisit(candidate,null,allowOverlap=true);fail("Rounded future arrival was admitted")} catch(_:IllegalArgumentException) {}
        assertNull(db.history().visit(candidate.id))
        val original=visit("original",-7200,-3600);db.history().put(VisitRow(original))
        try {repo.saveVisit(original.copy(source=VisitSource.automatic),original,allowOverlap=true);fail()} catch(_:IllegalArgumentException) {}
        assertEquals(original,db.history().visit(original.id)!!.value)
    }

}
