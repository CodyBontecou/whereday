package com.bontecou.whereday.data

import androidx.room.Room
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.IOException
import java.time.*

@RunWith(AndroidJUnit4::class)
class CountryAnalysisTest {
    private lateinit var db: HistoryDatabase
    private val coordinate=Coordinate(18.0,-66.0)
    private val clock=MutableClock(Instant.parse("2026-10-26T12:00:00Z"))
    private val location=CountryLocation("PR","America/Puerto_Rico")
    @Before fun setup() { db=HistoryDatabase.create(InstrumentationRegistry.getInstrumentation().targetContext,null) }
    @After fun teardown() { db.close() }
    private fun engine(lookup: suspend (Coordinate)->CountryLocation?)=CountryAnalysis(db,object: CountryLookup {
        override suspend fun lookup(coordinate: Coordinate)=lookup.invoke(coordinate)
    },"fixture-v1",clock,Duration.ZERO)
    private fun point(id: String,coordinate: Coordinate=this.coordinate)=PointRow(TrackPoint(id=id,coordinate=coordinate,time=clock.now.minusSeconds(1),accuracy=5.0),null)

    @Test fun boundedBatchesCacheExactCoordinatesAndExcludeFutureOutlierAndResolvedRecords()=runBlocking {
        val dao=db.history()
        dao.putPoints((0 until 205).map { point("point-$it") })
        dao.put(VisitRow(Visit(coordinate=coordinate,arrivedAt=clock.now.minusSeconds(1),name="Preserve this name")))
        dao.put(point("adjacent",Coordinate(18.000001,-66.0)))
        dao.put(point("outlier").let { it.copy(value=it.value.copy(outlier=true)) })
        dao.put(point("future").let { it.copy(value=it.value.copy(time=clock.now.plusSeconds(1))) })
        dao.put(point("resolved").let { it.copy(value=it.value.copy(countryCode="US",timeZone="America/New_York")) })
        var calls=0
        val result=engine { calls++; location }.run({ true },{})
        assertEquals(207L,result.resolved); assertEquals(2,calls)
        assertEquals("Preserve this name",dao.openVisits().single().value.name)
        assertNull(dao.point("outlier")!!.value.countryCode)
        assertNull(dao.point("future")!!.value.countryCode)
        assertEquals("US",dao.point("resolved")!!.value.countryCode)
    }
    @Test fun unavailableResultRetriesAfterAnHourAcrossNewRuns()=runBlocking {
        db.history().put(point("point"))
        var calls=0
        engine { calls++; null }.run({ true },{})
        engine { calls++; location }.run({ true },{})
        assertEquals(1,calls); assertNull(db.history().point("point")!!.value.countryCode)
        clock.now=clock.now.plusSeconds(3601)
        engine { calls++; location }.run({ true },{})
        assertEquals(2,calls); assertEquals("PR",db.history().point("point")!!.value.countryCode)
    }
    @Test fun disablingNetworkStopsAfterDurableResultsAndResumeSkipsCompletedWork()=runBlocking {
        db.history().put(point("a")); db.history().put(point("b",Coordinate(19.0,-66.0)))
        var allowed=true; var calls=0
        try { engine { calls++; location }.run({ allowed }) { if(it.completed==1L) allowed=false }; fail("Expected cancellation") }
        catch(expected:CancellationException) { }
        assertEquals(1,calls); assertEquals("PR",db.history().point("a")!!.value.countryCode); assertNull(db.history().point("b")!!.value.countryCode)
        engine { calls++; location }.run({ true },{})
        assertEquals(2,calls); assertEquals("PR",db.history().point("b")!!.value.countryCode)
    }
    @Test fun cancellingInFlightLookupCommitsNothingAndCanResume()=runBlocking {
        db.history().put(point("point"))
        val started=CompletableDeferred<Unit>()
        val job=launch { engine { started.complete(Unit); awaitCancellation() }.run({ true },{}) }
        started.await(); job.cancelAndJoin()
        assertNull(db.history().point("point")!!.value.countryResolutionAttemptedAt)
        engine { location }.run({ true },{})
        assertEquals("PR",db.history().point("point")!!.value.countryCode)
    }
    @Test fun editsDuringLookupPreserveCorrectedCoordinatesNamesAndTimes()=runBlocking {
        val visit=Visit(coordinate=coordinate,arrivedAt=clock.now.minusSeconds(10),name="Original")
        val moved=Coordinate(40.7128,-74.006)
        db.history().put(VisitRow(visit))
        var calls=0
        engine { requested ->
            calls++
            if(requested==coordinate) {
                db.history().put(VisitRow(visit.correct(moved,"Corrected",null,clock.now).copy(notes="Keep notes",arrivedAt=clock.now.minusSeconds(20))))
                location
            } else CountryLocation("US","America/New_York")
        }.run({ true },{})
        val saved=db.history().visit(visit.id)!!.value
        assertEquals(2,calls); assertEquals(moved,saved.coordinate); assertEquals("Corrected",saved.name)
        assertEquals("Keep notes",saved.notes); assertEquals(clock.now.minusSeconds(20),saved.arrivedAt)
        assertEquals("US",saved.countryCode); assertEquals(Confirmation.corrected,saved.confirmation)
        assertNull(db.history().countryLookup(coordinate,"fixture-v1"))
    }
    @Test fun clearingJournalDuringLookupDoesNotRestoreDataOrCaches()=runBlocking {
        db.history().put(point("point"))
        try {
            engine { withContext(Dispatchers.IO) { db.clearAllTables() }; location }.run({ true },{})
            fail("Expected reset cancellation")
        } catch(expected:CancellationException) { }
        assertEquals(0L,db.history().totalPointCount())
        assertNull(db.history().countryLookup(coordinate,"fixture-v1")); assertNull(db.history().countryAnalysis())
    }
    @Test fun failedRequestKeepsRateLimitAcrossRunAndEngineRestart()=runBlocking {
        db.history().put(point("point"))
        val source=object:CountryLookup { override suspend fun lookup(coordinate: Coordinate): CountryLocation? = throw IOException("fixture unavailable") }
        val initial=clock.now
        try { CountryAnalysis(db,source,"fixture-v1",clock,Duration.ofMinutes(1)).run({ true },{}); fail("Expected failure") }
        catch(expected:IOException) { }
        assertEquals(initial.plusSeconds(60),db.history().countryAnalysis()!!.nextLookupAt)
        var waited=0L
        val retry=CountryAnalysis(db,object:CountryLookup { override suspend fun lookup(coordinate: Coordinate)=location },"fixture-v1",clock,Duration.ofMinutes(1),wait={ millis -> waited+=millis; clock.now=clock.now.plusMillis(millis) })
        retry.run({ true },{})
        assertEquals(60_000L,waited); assertEquals("PR",db.history().point("point")!!.value.countryCode)
    }
    private class MutableClock(var now: Instant):Clock() {
        override fun getZone():ZoneId=ZoneOffset.UTC
        override fun withZone(zone: ZoneId):Clock=this
        override fun instant()=now
    }
}
