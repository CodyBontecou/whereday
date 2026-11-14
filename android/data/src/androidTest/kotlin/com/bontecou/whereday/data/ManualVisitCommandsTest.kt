package com.bontecou.whereday.data

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.time.*
import java.util.UUID

class ManualVisitCommandsTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db:HistoryDatabase
    private lateinit var history:HistoryRepository
    private val now=Instant.parse("2026-10-31T12:00:00.125Z")
    private val clock=Clock.fixed(now,ZoneOffset.UTC)
    private val pin=Coordinate(18.0,-66.0)
    @Before fun setup() {
        check(context.packageName=="com.bontecou.whereday.data.test")
        db=HistoryDatabase.create(context,null);history=HistoryRepository(db,clock)
    }
    @After fun close() {db.close()}
    private suspend inline fun <reified T:Throwable> fails(noinline action:suspend ()->Unit):T {
        try {action();error("Expected ${T::class.java.simpleName}")} catch(e:Throwable) {if(e !is T) throw e;return e}
    }
    private suspend fun checkIn(name:String="Cafe",at:Instant=now,overlap:Boolean=false)=history.checkIn(name,null,pin,at,overlap)
    @Test fun coordinateCheckInPreservesManualProvenanceAndNeverStartsRecording()=runBlocking {
        val visit=checkIn("  Cafe\n",now.minusSeconds(30))
        assertEquals("Cafe",visit.name);assertEquals(pin,visit.coordinate);assertNull(visit.departedAt)
        assertEquals(VisitSource.manual,visit.source);assertEquals(Confirmation.confirmed,visit.confirmation)
        assertEquals(now,visit.confirmedAt);assertEquals(now,visit.updatedAt);assertEquals("userEntered",visit.placeSource)
        assertEquals(visit,db.history().visit(visit.id)!!.value)
        assertEquals(0L,db.history().openSessionCount());assertNull(db.history().recording())
        history.start(0);history.stop()
        assertNull(db.history().visit(visit.id)!!.value.departedAt)
    }
    @Test fun savedPlaceIsResolvedAtCommitWithNameFallbackAndNoPlaceMutation()=runBlocking {
        val place=SavedPlace("saved","  Library  ",pin,"  Street  ",250.0)
        db.history().put(PlaceRow(place))
        val visit=history.checkIn(" ",place.id,null,now)
        assertEquals("Library",visit.name);assertEquals("Street",visit.address);assertEquals(pin,visit.coordinate)
        assertEquals(place,db.history().place(place.id)!!.value)
        val override=history.checkIn(" Custom ",place.id,null,now,true)
        assertEquals("Custom",override.name)
        assertEquals(ManualVisitProblem.missingPlace,fails<ManualVisitCommandFailed> {history.checkIn(null,"missing",null,now);Unit}.problem)
        fails<IllegalArgumentException> {history.checkIn("Bad",place.id,pin,now);Unit}
        fails<IllegalArgumentException> {history.checkIn(" ",null,pin,now);Unit}
        assertEquals(2L,db.history().totalVisitCount())
    }
    @Test fun roomOverlapQueryMatchesAllActualSwiftPredicateReferences()=runBlocking {
        val fixture=InstrumentationRegistry.getInstrumentation().context.assets.open("ios-manual-visit-overlap.json")
            .bufferedReader().use {JsonParser.parseReader(it).asJsonObject}
        val future=Instant.ofEpochMilli(fixture["futureMillis"].asLong)
        assertEquals(Instant.parse("4001-01-01T00:00:00Z"),future)
        assertEquals(18,fixture["cases"].asJsonArray.size())
        for(element in fixture["cases"].asJsonArray) {
            val row=element.asJsonObject
            fun time(key:String)=row[key].takeUnless {it.isJsonNull}?.asLong?.let(Instant::ofEpochMilli)
            db.history().deleteVisit("candidate")
            db.history().put(VisitRow(Visit("candidate",pin,time("candidateFrom")!!,time("candidateThrough"))))
            val overlap=db.history().manualVisitOverlap(time("from")!!,time("through") ?: future,future)!=null
            assertEquals(row.toString(),row["overlap"].asBoolean,overlap)
        }
    }
    @Test fun overlapRequiresExplicitAdmissionAndAdjacentDeparturesAreAllowed()=runBlocking {
        db.history().put(VisitRow(Visit("prior",pin,now.minusSeconds(10),now)))
        val first=checkIn()
        assertEquals(ManualVisitProblem.overlap,fails<ManualVisitCommandFailed> {checkIn();Unit}.problem)
        val second=checkIn(overlap=true)
        assertNotEquals(first.id,second.id);assertEquals(3L,db.history().totalVisitCount())
    }
    @Test fun concurrentCheckInsAcrossRepositoriesAdmitOnlyOneWithoutOverlap()=runBlocking {
        val other=HistoryRepository(db,clock)
        val results=(0..9).map {i->async(Dispatchers.Default) {
            runCatching {(if(i%2==0) history else other).checkIn("Cafe",null,pin,now)}
        }}.awaitAll()
        assertEquals(1,results.count {it.isSuccess});assertEquals(1L,db.history().totalVisitCount())
        results.filter {it.isFailure}.forEach {assertEquals(ManualVisitProblem.overlap,(it.exceptionOrNull() as ManualVisitCommandFailed).problem)}
    }
    @Test fun checkoutRejectsAmbiguityAndRepeatedOrNonManualRecordsWithoutChangingOthers()=runBlocking {
        val auto=Visit("automatic",pin,now.minusSeconds(60))
        db.history().put(VisitRow(auto))
        assertEquals(ManualVisitProblem.noOpenVisit,fails<ManualVisitCommandFailed> {history.checkOut(null);Unit}.problem)
        val first=checkIn(at=now.minusSeconds(30),overlap=true);val second=checkIn(at=now.minusSeconds(20),overlap=true)
        assertEquals(ManualVisitProblem.ambiguousVisit,fails<ManualVisitCommandFailed> {history.checkOut(null);Unit}.problem)
        val metadata=first.copy(notes="Keep",countryCode="PR",timeZone="America/Puerto_Rico",departureInferred=true)
        db.history().put(VisitRow(metadata))
        val closed=history.checkOut(first.id)
        assertEquals(metadata.copy(departedAt=now,updatedAt=now,departureInferred=false),closed)
        assertEquals(second.id,history.checkOut(null).id)
        assertEquals(ManualVisitProblem.closedVisit,fails<ManualVisitCommandFailed> {history.checkOut(first.id);Unit}.problem)
        assertEquals(ManualVisitProblem.closedVisit,fails<ManualVisitCommandFailed> {history.checkOut(auto.id);Unit}.problem)
        fails<JournalItemNotFound> {history.checkOut("missing");Unit}
        assertEquals(auto,db.history().visit(auto.id)!!.value)
    }
    @Test fun invalidFutureAndPreArrivalTimesNeverCommitEvenBelowMillisecondPrecision()=runBlocking {
        fails<IllegalArgumentException> {checkIn(at=now.plusNanos(1));Unit}
        assertEquals(0L,db.history().totalVisitCount())
        val visit=checkIn(at=now.minusSeconds(10))
        fails<IllegalArgumentException> {history.checkOut(visit.id,visit.arrivedAt.minusNanos(1));Unit}
        fails<IllegalArgumentException> {history.checkOut(visit.id,now.plusNanos(1));Unit}
        assertNull(db.history().visit(visit.id)!!.value.departedAt)
        assertEquals(visit.arrivedAt,history.checkOut(visit.id,visit.arrivedAt).departedAt)
    }
    @Test(timeout=30_000) fun cancelledQueuedCheckInCannotWriteAfterTheWriterIsReleased()=runBlocking {
        val ready=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val holder=async(Dispatchers.IO) {db.writeTransaction {ready.complete(Unit);release.await()}}
        ready.await()
        val pending=async(Dispatchers.IO) {checkIn()}
        yield();pending.cancel();release.complete(Unit);holder.await();pending.join()
        assertEquals(0L,db.history().totalVisitCount())
    }
    @Test fun savedPlaceAndOpenManualQueriesRetainEveryTieAcrossPages()=runBlocking {
        db.writeTransaction {repeat(1201) {i->
            val id="fixture-%04d".format(i)
            db.history().put(PlaceRow(SavedPlace(id,"Same name",pin)))
            db.history().put(VisitRow(Visit(id,pin,now,source=VisitSource.manual)))
        }}
        db.history().put(VisitRow(Visit("auto",pin,now)))
        val commands=JournalCommands(history,clock);val places=mutableListOf<String>();val visits=mutableListOf<String>()
        commands.forEachSavedPlace {places+=it.id};commands.forEachOpenManualVisit {visits+=it.id}
        assertEquals(1201,places.size);assertEquals(1201,places.toSet().size)
        assertEquals(places.reversed(),visits)
    }
}
