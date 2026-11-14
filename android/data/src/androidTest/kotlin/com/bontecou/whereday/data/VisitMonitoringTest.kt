package com.bontecou.whereday.data

import androidx.room.useWriterConnection
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.time.*
import java.util.UUID

class VisitMonitoringTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db:HistoryDatabase
    private lateinit var repo:HistoryRepository
    private lateinit var name:String
    private val clock=MutableClock(Instant.parse("2026-10-31T12:00:00Z"))
    private val origin=clock.instant()
    private val first=Coordinate(18.0,-66.0)
    private val second=Coordinate(18.01,-66.0)

    @Test fun bootEvidenceSurvivesReopenAndIdempotentStartsRetainIt()=runBlocking {
        val route=repo.start(0,23);val visits=repo.startVisitMonitoring(23)
        assertEquals(23,repo.start(0,24).startedBootCount)
        assertEquals(23,repo.startVisitMonitoring(24).startedBootCount)
        db.close();db=HistoryDatabase.create(context,name);repo=HistoryRepository(db,clock)
        assertEquals(route,repo.dao.recording());assertEquals(visits,repo.dao.visitMonitoring())
        repo.stop();repo.stopVisitMonitoring(visits.generation!!)
        assertEquals(24,repo.start(0,24).startedBootCount)
        assertEquals(24,repo.startVisitMonitoring(24).startedBootCount)
    }

    @Test fun staleRecoveryCannotInterruptReplacementLocationOwners()=runBlocking {
        val oldRoute=repo.start(0,23);val oldVisits=repo.startVisitMonitoring(23)
        repo.stop();repo.stopVisitMonitoring(oldVisits.generation!!)
        val currentRoute=repo.start(0,23);val currentVisits=repo.startVisitMonitoring(23)
        repo.markInterrupted(RecordingInterruption.phoneRestarted.code,oldRoute)
        repo.interruptVisitMonitoring("visits.restarted",oldVisits)
        assertEquals(currentRoute,repo.dao.recording());assertEquals(currentVisits,repo.dao.visitMonitoring())
        assertEquals(1L,repo.dao.openSessionCount())
        repo.markInterrupted(RecordingInterruption.processInterrupted.code,currentRoute)
        repo.interruptVisitMonitoring("visits.interrupted",currentVisits)
        assertFalse(repo.dao.recording()!!.requested);assertFalse(repo.dao.visitMonitoring()!!.requested)
        assertEquals(0L,repo.dao.openSessionCount())
    }
    @Before fun setup() {
        check(context.packageName=="com.bontecou.whereday.data.test")
        name="visit-monitoring-${UUID.randomUUID()}.db"
        db=HistoryDatabase.create(context,name);repo=HistoryRepository(db,clock)
    }
    @After fun close() {db.close();context.deleteDatabase(name)}
    private fun point(seconds:Long,where:Coordinate=first,accuracy:Double=5.0)=
        TrackPoint(coordinate=where,time=origin.plusSeconds(seconds),accuracy=accuracy)
    private fun advance(seconds:Long) {clock.now=origin.plusSeconds(seconds)}
    private suspend fun lease()=requireNotNull(repo.startVisitMonitoring().generation)
    private suspend fun open(lease:String):Visit {
        advance(600);repo.ingestVisits(lease,listOf(point(0),point(300)))
        return repo.dao.openVisits().single().value
    }

    @Test fun defaultsOffAndUnknownCallbacksCannotCreateHistory()=runBlocking {
        assertNull(repo.dao.visitMonitoring())
        repo.ingestVisits(UUID.randomUUID().toString(),listOf(point(0),point(300)))
        assertEquals(0L,repo.dao.totalPointCount());assertEquals(0L,repo.dao.totalVisitCount())
        assertNull(repo.dao.dwell());assertNull(repo.dao.visitMonitoring())
    }

    @Test fun savedPlaceArrivalAndDepartureCreateNoRouteOrWebhookPoints()=runBlocking {
        val revision=UUID.randomUUID().toString()
        db.webhooks().put(WebhookPolicyRow(revision=revision,enabled=true,capture=true,includeOutliers=false,createdAt=origin))
        repo.dao.put(PlaceRow(SavedPlace(name="Synthetic stop",coordinate=first,address="QA only")))
        val lease=lease();val old=open(lease)
        assertEquals("Synthetic stop",old.name);assertEquals(Confirmation.confirmed,old.confirmation)
        advance(1000);repo.ingestVisits(lease,listOf(point(660,second),point(360,second)))
        assertEquals(origin.plusSeconds(360),repo.dao.visit(old.id)!!.value.departedAt)
        assertEquals(origin.plusSeconds(360),repo.dao.openVisits().single().value.arrivedAt)
        assertEquals(0L,repo.dao.totalPointCount());assertEquals(0L,repo.dao.openSessionCount())
        assertTrue(repo.dao.rangeSessions(origin,clock.now).isEmpty())
        assertEquals(0L,db.webhooks().batchCount(revision))
        assertEquals(0L,db.webhooks().pointCount(revision))
    }

    @Test fun routeStartStopHandsOffOneContinuingVisitAndPassiveFixesCannotWriteDuringRoute()=runBlocking {
        val lease=lease();val visit=open(lease)
        val route=repo.start(0)
        advance(1000);repo.ingest(listOf(point(660)))
        val state=repo.dao.dwell()!!.state;val monitoring=repo.dao.visitMonitoring()
        repo.ingestVisits(lease,listOf(point(700,second)))
        assertEquals(state,repo.dao.dwell()!!.state);assertEquals(monitoring,repo.dao.visitMonitoring())
        repo.stop()
        assertNull(repo.dao.visit(visit.id)!!.value.departedAt)
        assertEquals(visit.id,repo.dao.dwell()!!.state.visitId)
        repo.ingestVisits(lease,listOf(point(780)))
        assertEquals(visit.id,repo.dao.openVisits().single().value.id)
        assertEquals(1L,repo.dao.totalPointCount())
        assertNotNull(repo.dao.session(route.sessionId!!)!!.value.endedAt)
    }

    @Test fun stoppingMonitoringWhileRouteOwnsDetectorDoesNotCloseItsVisit()=runBlocking {
        val lease=lease();val visit=open(lease);repo.start(0)
        repo.stopVisitMonitoring(lease)
        assertNull(repo.dao.visit(visit.id)!!.value.departedAt)
        advance(1000);repo.ingest(listOf(point(660)));repo.stop()
        assertNotNull(repo.dao.visit(visit.id)!!.value.departedAt)
        assertEquals(DwellState(),repo.dao.dwell()!!.state)
    }

    @Test fun candidateStartedWithoutARouteCanBeConfirmedByRouteAndContinueAfterRouteStop()=runBlocking {
        val lease=lease();repo.ingestVisits(lease,listOf(point(0)))
        advance(120);repo.start(0)
        advance(600);repo.ingest(listOf(point(300)))
        val visit=repo.dao.openVisits().single().value
        assertEquals(origin,visit.arrivedAt)
        repo.stop();repo.ingestVisits(lease,listOf(point(600)))
        assertEquals(visit.id,repo.dao.openVisits().single().value.id)
        assertEquals(origin.plusSeconds(600),repo.dao.dwell()!!.state.lastTime)
        assertEquals(1L,repo.dao.totalVisitCount());assertEquals(1L,repo.dao.totalPointCount())
    }

    @Test fun longUnobservedGapClosesOldPlaceAtLastEvidenceAndStartsNewPlaceAtItsFirstFix()=runBlocking {
        val lease=lease();val old=open(lease)
        advance(1800);repo.ingestVisits(lease,listOf(point(1800,second)))
        assertEquals(origin.plusSeconds(300),repo.dao.visit(old.id)!!.value.departedAt)
        assertTrue(repo.dao.openVisits().isEmpty())
        advance(2100);repo.ingestVisits(lease,listOf(point(2100,second)))
        val next=repo.dao.openVisits().single().value
        assertNotEquals(old.id,next.id);assertEquals(origin.plusSeconds(1800),next.arrivedAt)
    }

    @Test fun manuallyClosedVisitCannotBeChangedByReplayedEarlierFixesAfterDetectorReset()=runBlocking {
        val lease=lease();val old=open(lease)
        val corrected=old.copy(departedAt=origin.plusSeconds(120),departureInferred=false,updatedAt=clock.now)
        repo.saveVisit(corrected,old)
        repo.ingestVisits(lease,listOf(point(0),point(200),point(300)))
        assertEquals(corrected,repo.dao.visit(old.id)!!.value)
        assertEquals(DwellState(),repo.dao.dwell()!!.state)
        assertEquals(1L,repo.dao.totalVisitCount())
    }

    @Test fun activeRouteDeletionAndInterruptionPreserveIndependentVisitOwnership()=runBlocking {
        val lease=lease();val visit=open(lease);val route=repo.start(0)
        repo.deleteSession(route.sessionId!!)
        assertNull(repo.dao.visit(visit.id)!!.value.departedAt)
        repo.start(0);repo.markInterrupted("synthetic process interruption")
        assertNull(repo.dao.visit(visit.id)!!.value.departedAt)
        repo.interruptVisitMonitoring("synthetic process interruption")
        assertEquals(origin.plusSeconds(300),repo.dao.visit(visit.id)!!.value.departedAt)
        assertFalse(repo.dao.recording()!!.requested);assertNull(repo.dao.recording()!!.deadline)
        assertFalse(repo.dao.visitMonitoring()!!.requested);assertNull(repo.dao.visitMonitoring()!!.generation)
    }

    @Test fun staleLeaseCannotIngestOrStopReplacementAfterDisableOrClear()=runBlocking {
        val old=lease();open(old);repo.stopVisitMonitoring(old)
        advance(1000);val replacement=lease();assertNotEquals(old,replacement)
        repo.ingestVisits(old,listOf(point(1000)))
        repo.stopVisitMonitoring(old)
        assertEquals(replacement,repo.dao.visitMonitoring()!!.generation)
        assertEquals(DwellState(),repo.dao.dwell()!!.state)
        withContext(Dispatchers.IO) {db.clearAllTables()}
        repo.ingestVisits(replacement,listOf(point(1000)))
        assertNull(repo.dao.visitMonitoring());assertNull(repo.dao.dwell())
        assertEquals(0L,repo.dao.totalVisitCount())
        val fresh=lease();assertNotEquals(replacement,fresh)
        repo.ingestVisits(replacement,listOf(point(1000)))
        assertNull(repo.dao.dwell())
    }

    @Test fun persistedLeaseWatermarkAndDepartureEvidenceSurviveReopen()=runBlocking {
        val lease=lease();val visit=open(lease)
        repo.ingestVisits(lease,listOf(point(360,second)))
        val monitoring=repo.dao.visitMonitoring();val dwell=repo.dao.dwell()
        db.close();db=HistoryDatabase.create(context,name);repo=HistoryRepository(db,clock)
        assertEquals(monitoring,repo.dao.visitMonitoring());assertEquals(dwell,repo.dao.dwell())
        advance(1000);repo.ingestVisits(lease,listOf(point(660,second),point(360,second),point(0)))
        assertEquals(2L,repo.dao.totalVisitCount())
        assertEquals(origin.plusSeconds(360),repo.dao.visit(visit.id)!!.value.departedAt)
        assertEquals(origin.plusSeconds(360),repo.dao.openVisits().single().value.arrivedAt)
    }

    @Test fun staleFuturePoorAndExplicitOutlierFixesCannotEstablishDwell()=runBlocking {
        val lease=lease();advance(2000)
        repo.ingestVisits(lease,listOf(point(-1),point(100),point(2061),point(1800,accuracy=101.0),point(1900).copy(outlier=true)))
        assertEquals(0L,repo.dao.totalVisitCount());assertEquals(DwellState(),repo.dao.dwell()!!.state)
        assertEquals(origin.plusSeconds(1900),repo.dao.visitMonitoring()!!.lastFix)
        repo.ingestVisits(lease,listOf(point(1899),point(1900)))
        assertEquals(DwellState(),repo.dao.dwell()!!.state)
    }

    @Test fun stopClosesAtLastSupportedDwellSampleRatherThanLaterOutlierOrWallClock()=runBlocking {
        val lease=lease();val visit=open(lease)
        advance(1000);repo.ingestVisits(lease,listOf(point(400,Coordinate(40.0,-66.0))))
        repo.stopVisitMonitoring(lease)
        assertEquals(origin.plusSeconds(300),repo.dao.visit(visit.id)!!.value.departedAt)
        assertEquals(VisitObservation(),repo.dao.visitMonitoring()!!.recent)
        assertEquals(origin.plusSeconds(400),repo.dao.visitMonitoring()!!.lastFix)
    }

    @Test fun concurrentRepositoriesShareOneLeaseAndCommitArrivalOnlyOnce()=runBlocking {
        val other=HistoryRepository(db,clock)
        val starts=awaitAll(async(Dispatchers.IO) {repo.startVisitMonitoring()},async(Dispatchers.IO) {other.startVisitMonitoring()})
        assertEquals(starts[0],starts[1]);val lease=requireNotNull(starts[0].generation)
        advance(600);val batch=listOf(point(0),point(300))
        awaitAll(async(Dispatchers.IO) {repo.ingestVisits(lease,batch)},async(Dispatchers.IO) {other.ingestVisits(lease,batch)})
        assertEquals(1L,repo.dao.totalVisitCount());assertEquals(origin.plusSeconds(300),repo.dao.visitMonitoring()!!.lastFix)
        assertEquals(0L,repo.dao.totalPointCount())
    }

    @Test fun failedArrivalRollsBackDetectorAndWatermarkSoBatchCanBeRetried()=runBlocking {
        val lease=lease();advance(600);repo.ingestVisits(lease,listOf(point(0)))
        val before=repo.dao.visitMonitoring();val dwell=repo.dao.dwell()
        db.useWriterConnection {it.usePrepared("CREATE TRIGGER reject_visit BEFORE INSERT ON visits BEGIN SELECT RAISE(ABORT,'synthetic failure'); END") {s->s.step()}}
        assertTrue(runCatching {repo.ingestVisits(lease,listOf(point(300)))}.isFailure)
        assertEquals(before,repo.dao.visitMonitoring());assertEquals(dwell,repo.dao.dwell())
        assertEquals(0L,repo.dao.totalVisitCount())
        db.useWriterConnection {it.usePrepared("DROP TRIGGER reject_visit") {s->s.step()}}
        repo.ingestVisits(lease,listOf(point(300)))
        assertEquals(1L,repo.dao.totalVisitCount())
    }

    @Test fun oversizedBatchIsRejectedWithoutPartialChanges()=runBlocking {
        val lease=lease();advance(600);val before=repo.dao.visitMonitoring()
        assertTrue(runCatching {repo.ingestVisits(lease,List(65) {point(it.toLong())})}.isFailure)
        assertEquals(before,repo.dao.visitMonitoring());assertNull(repo.dao.dwell())
    }

    @Test fun serviceFailureEndsRouteAtDurableFixAndVisitAtLastSupportingFix()=runBlocking {
        val route=repo.start(0);advance(600)
        repo.ingest(listOf(point(0),point(300),point(400,Coordinate(40.0,-66.0))))
        val visit=repo.dao.openVisits().single().value
        assertEquals(origin.plusSeconds(300),repo.dao.dwell()!!.state.lastTime)
        advance(5000);repo.stop(RecordingInterruption.permissionRemoved.code)
        assertEquals(origin.plusSeconds(400),repo.dao.session(route.sessionId!!)!!.value.endedAt)
        assertEquals(origin.plusSeconds(300),repo.dao.visit(visit.id)!!.value.departedAt)
    }

    @Test fun relaunchRecoveryDoesNotTreatAnOutlierAsEvidenceOfContinuedPresence()=runBlocking {
        repo.start(0);advance(600)
        repo.ingest(listOf(point(0),point(300),point(400,Coordinate(40.0,-66.0))))
        val visit=repo.dao.openVisits().single().value
        advance(5000);repo.markInterrupted(RecordingInterruption.processInterrupted.code)
        assertEquals(origin.plusSeconds(300),repo.dao.visit(visit.id)!!.value.departedAt)
    }

    @Test fun interruptionOrderCannotExtendAVisitWhenNewRouteHasNoFixes()=runBlocking {
        val results=mutableListOf<Instant?>()
        for(monitorFirst in listOf(false,true)) {
            withContext(Dispatchers.IO) {db.clearAllTables()};advance(0)
            val lease=lease();val visit=open(lease)
            repo.start(0);advance(5000)
            if(monitorFirst) repo.interruptVisitMonitoring("visits.location")
            repo.markInterrupted(RecordingInterruption.permissionRemoved.code)
            if(!monitorFirst) repo.interruptVisitMonitoring("visits.location")
            results+=repo.dao.visit(visit.id)!!.value.departedAt
        }
        assertEquals(listOf(origin.plusSeconds(300),origin.plusSeconds(300)),results)
    }

    @Test fun failedStartWithoutAnyFixDoesNotInventRouteDuration()=runBlocking {
        val route=repo.start(0)
        advance(5000);repo.stop(RecordingInterruption.startFailed.code)
        assertEquals(origin,repo.dao.session(route.sessionId!!)!!.value.endedAt)
        assertEquals(0L,repo.dao.totalPointCount())
    }

    @Test fun explicitUserStopKeepsItsRequestedWallClockBoundary()=runBlocking {
        val route=repo.start(0);advance(600)
        repo.ingest(listOf(point(0),point(300)))
        val visit=repo.dao.openVisits().single().value
        advance(5000);repo.stop()
        assertEquals(clock.now,repo.dao.session(route.sessionId!!)!!.value.endedAt)
        assertEquals(clock.now,repo.dao.visit(visit.id)!!.value.departedAt)
    }

    private class MutableClock(var now:Instant):Clock() {
        override fun instant()=now
        override fun getZone():ZoneId=ZoneOffset.UTC
        override fun withZone(zone:ZoneId):Clock=this
    }
}
