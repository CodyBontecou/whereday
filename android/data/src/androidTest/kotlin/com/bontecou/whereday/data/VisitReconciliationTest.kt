package com.bontecou.whereday.data

import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.useWriterConnection
import com.bontecou.whereday.domain.*
import com.google.gson.*
import kotlinx.coroutines.*
import java.time.*
import org.junit.*
import org.junit.Assert.*

class VisitReconciliationTest {
    private lateinit var db:HistoryDatabase
    private lateinit var repo:HistoryRepository
    private val now=Instant.parse("2026-10-28T12:00:00Z")
    private val coordinate=Coordinate(18.0,-66.0)
    private val clock=Clock.fixed(now,ZoneOffset.UTC)
    @Before fun setup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.data.test")
        db=HistoryDatabase.create(context,null);repo=HistoryRepository(db,clock)
    }
    @After fun close() {db.close()}
    private fun visit(id:String,arrival:Long,end:Long?,where:Coordinate=coordinate)=Visit(id,where,now.plusSeconds(arrival),end?.let(now::plusSeconds))
    @Test fun duplicateRepairDoesNotConsumeManualOrImportedJournalEntries()=runBlocking {
        for(firstSource in VisitSource.entries) for(secondSource in VisitSource.entries) {
            db.clearAllTables()
            val first=visit("first",-1000,-900).copy(source=firstSource,notes="First")
            val second=visit("second",-999,-899).copy(source=secondSource,notes="Second")
            repo.dao.put(VisitRow(first));repo.dao.put(VisitRow(second))
            val automatic=firstSource==VisitSource.automatic && secondSource==VisitSource.automatic
            assertEquals("$firstSource/$secondSource",if(automatic) 1 else 0,repo.reconcileVisits())
            assertEquals(if(automatic) 1L else 2L,repo.dao.totalVisitCount())
            if(!automatic) {assertEquals(first,repo.dao.visit(first.id)!!.value);assertEquals(second,repo.dao.visit(second.id)!!.value)}
        }
    }
    @Test fun startupRepairLeavesManualAndImportedOpenVisitsUntouched()=runBlocking {
        val old=visit("old-auto",-1000,null)
        val manual=visit("manual",-800,null,Coordinate(19.0,-66.0)).copy(source=VisitSource.manual)
        val imported=visit("imported",-600,null,Coordinate(20.0,-66.0)).copy(source=VisitSource.imported)
        val latest=visit("latest-auto",-100,null,Coordinate(21.0,-66.0))
        listOf(old,manual,imported,latest).forEach {repo.dao.put(VisitRow(it))}
        assertEquals(1,repo.reconcileVisits())
        assertEquals(manual,repo.dao.visit(manual.id)!!.value);assertEquals(imported,repo.dao.visit(imported.id)!!.value)
        assertEquals(manual.arrivedAt,repo.dao.visit(old.id)!!.value.departedAt)
        assertNull(repo.dao.visit(latest.id)!!.value.departedAt)
        assertEquals(listOf(latest),repo.dao.openVisits().map {it.value})
        assertEquals(0,repo.reconcileVisits())
    }
    @Test fun staleOpenVisitsCloseAtTheNextStrictlyLaterArrivalAndReleaseDetectorOwnership()=runBlocking {
        val first=visit("first",-1000,null);val middle=visit("middle",-500,-400,Coordinate(19.0,-66.0));val latest=visit("latest",-100,null,Coordinate(20.0,-66.0))
        listOf(first,middle,latest).forEach {repo.dao.put(VisitRow(it))}
        repo.dao.put(DwellRow(state=DwellState(visitId=first.id)))
        assertEquals(1,repo.reconcileVisits())
        assertEquals(middle.arrivedAt,repo.dao.visit(first.id)!!.value.departedAt)
        assertTrue(repo.dao.visit(first.id)!!.value.departureInferred)
        assertNull(repo.dao.visit(latest.id)!!.value.departedAt);assertNull(repo.dao.dwell()!!.state.visitId)
        assertEquals(0,repo.reconcileVisits())
    }
    @Test fun chronologicalMergingCrossesPagesKeepsCorrectionsAndIsIdempotent()=runBlocking {
        val first=visit("0000",-60000,-59990).copy(name="Corrected",notes="kept",confirmation=Confirmation.corrected,originalCoordinate=Coordinate(18.1,-66.0),countryCode="PR",timeZone="America/Puerto_Rico")
        repo.dao.put(VisitRow(first))
        repeat(505) {i->repo.dao.put(VisitRow(visit("%04d".format(i+1),-59980+i*10L,-59970+i*10L).copy(notes=if(i==504) "last" else null)))}
        assertEquals(505,repo.reconcileVisits());assertEquals(1L,repo.dao.totalVisitCount())
        val merged=repo.dao.visit(first.id)!!.value
        assertEquals(first.copy(departedAt=now.plusSeconds(-54930),notes="kept\n\nlast"),merged)
        assertEquals(0,repo.reconcileVisits())
    }
    @Test fun automaticArrivalMergesAndSubsequentDepartureUpdatesTheSurvivingIdentity()=runBlocking {
        val start=now.minusSeconds(600)
        val live=HistoryRepository(db,Clock.fixed(start,ZoneOffset.UTC))
        val old=Visit("old",coordinate,start.minusSeconds(3600),start.minusSeconds(60),name="Corrected",confirmation=Confirmation.corrected)
        repo.dao.put(VisitRow(old));live.start(0)
        val ingest=HistoryRepository(db,clock)
        fun fix(seconds:Long,where:Coordinate=coordinate)=TrackPoint(coordinate=where,time=start.plusSeconds(seconds),accuracy=5.0)
        ingest.ingest(listOf(fix(0),fix(301)))
        assertEquals(1L,repo.dao.totalVisitCount());assertEquals(old.id,repo.dao.dwell()!!.state.visitId)
        assertNull(repo.dao.visit(old.id)!!.value.departedAt);assertEquals("Corrected",repo.dao.visit(old.id)!!.value.name)
        val outside=Coordinate(18.01,-66.0)
        ingest.ingest(listOf(fix(400,outside),fix(521,outside)))
        assertEquals(start.plusSeconds(400),repo.dao.visit(old.id)!!.value.departedAt)
        assertNull(repo.dao.dwell()!!.state.visitId)
    }
    @Test fun spatialPrefilterDoesNotLoseDateLinePairsAndSeparateStopsRemainSeparate()=runBlocking {
        val first=visit("a",-10000,-9900,Coordinate(0.0,179.9999))
        val near=visit("b",-9800,-9700,Coordinate(0.0,-179.9999))
        val remote=visit("c",-9600,-9500,Coordinate(0.1,-179.9999))
        val later=visit("d",-1000,-900,Coordinate(0.0,179.9999))
        listOf(first,near,remote,later).forEach {repo.dao.put(VisitRow(it))}
        assertEquals(1,repo.reconcileVisits());assertEquals(3L,repo.dao.totalVisitCount())
        assertEquals(near.departedAt,repo.dao.visit(first.id)!!.value.departedAt)
    }
    @Test fun aLaterDatabaseFailureRollsBackEarlierClosuresMergesAndDetectorRemapping()=runBlocking {
        val original=listOf(visit("a",-400,null),visit("b",-300,-200),visit("c",-100,null))
        original.forEach {repo.dao.put(VisitRow(it))}
        val dwell=DwellState(visitId="c");repo.dao.put(DwellRow(state=dwell))
        db.useWriterConnection {it.usePrepared("CREATE TRIGGER stop_second_merge BEFORE DELETE ON visits WHEN OLD.value_id='c' BEGIN SELECT RAISE(ABORT,'synthetic write failure'); END") {statement->statement.step()}}
        var failed=false
        try {repo.reconcileVisits()} catch(_:Exception) {failed=true}
        assertTrue(failed)
        assertEquals(original,repo.dao.exportFirstVisits(Instant.EPOCH,Instant.ofEpochMilli(Long.MAX_VALUE)).map {it.value})
        assertEquals(dwell,repo.dao.dwell()!!.state)
    }
    @Test fun androidEllipsoidalDistanceMatchesAllSwiftDuplicateDecisions()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val document=context.assets.open("ios-visit-reconciliation.json").reader().use {JsonParser.parseReader(it).asJsonObject}
        fun JsonObject.visit()=Visit(get("id").asString,Coordinate(get("latitude").asDouble,get("longitude").asDouble),
            Instant.ofEpochSecond(get("arrival").asLong),get("departure").takeUnless {it.isJsonNull}?.asLong?.let(Instant::ofEpochSecond))
        val cases=document.getAsJsonArray("cases");assertEquals(14,cases.size())
        for(element in cases) {
            val case=element.asJsonObject
            assertEquals(case.get("name").asString,case.get("matches").asBoolean,
                VisitReconciliation.shouldMerge(case.getAsJsonObject("target").visit(),case.getAsJsonObject("source").visit(),
                    Instant.ofEpochSecond(case.get("now").asLong),SavedPlaceRepository(db)::distance))
        }
    }
}
