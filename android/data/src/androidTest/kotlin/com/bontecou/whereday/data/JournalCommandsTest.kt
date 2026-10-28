package com.bontecou.whereday.data

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.*
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class JournalCommandsTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var db:HistoryDatabase
    private lateinit var history:HistoryRepository
    private lateinit var commands:JournalCommands
    private lateinit var databaseName:String
    private val now=Instant.parse("2026-10-28T12:00:00Z")
    private val coordinate=Coordinate(18.0,-66.0)
    private val range get()=JournalDateRange(now,now.plusSeconds(10))
    @Before fun setup() {
        check(context.packageName=="com.bontecou.whereday.data.test")
        databaseName="command-fixture-${UUID.randomUUID()}.db"
        db=HistoryDatabase.create(context,databaseName)
        history=HistoryRepository(db,Clock.fixed(now,ZoneOffset.UTC))
        commands=JournalCommands(history,Clock.fixed(now,ZoneOffset.UTC))
    }
    @After fun close() {db.close();context.deleteDatabase(databaseName)}
    private fun visit(id:String,at:Instant=now)=Visit(id,coordinate,at)
    private suspend fun seed(count:Int) {db.writeTransaction {
        repeat(count) {i ->
            val id="fixture-%04d".format(i)
            db.history().put(VisitRow(visit(id)))
            db.history().put(SessionRow(RecordingSession(id,now,now.plusSeconds(5))))
        }
    }}
    private suspend inline fun <reified T:Throwable> fails(noinline operation:suspend ()->Unit) {
        try {operation();fail("Expected ${T::class.java.simpleName}")} catch(e:Throwable) {if(e !is T) throw e}
    }

    @Test fun eventStartReadsRetainTiesAndBothInclusiveBoundariesAcrossPages()=runBlocking {
        seed(1201)
        db.history().put(VisitRow(visit("overlap",now.minusSeconds(1))))
        db.history().put(VisitRow(visit("end",range.through)))
        db.history().put(VisitRow(visit("later",range.through.plusMillis(1))))
        db.history().put(SessionRow(RecordingSession("overlap",now.minusSeconds(1))))
        db.history().put(SessionRow(RecordingSession("end",range.through)))
        db.history().put(SessionRow(RecordingSession("later",range.through.plusMillis(1))))
        val visits=mutableListOf<String>();val outings=mutableListOf<String>()
        commands.forEachVisit(range) {visits+=it.id}
        commands.forEachOuting(range) {outings+=it.id}
        assertEquals(1202,visits.size);assertEquals(1202,outings.size)
        assertEquals(1202,visits.toSet().size);assertEquals(visits,outings)
        assertEquals("end",visits.last());assertFalse(visits.contains("overlap"))
    }

    @Test fun subMillisecondBoundsRoundInwardIncludingBeforeEpoch()=runBlocking {
        val epoch=Instant.EPOCH
        for(ms in -2L..2L) db.history().put(VisitRow(visit("$ms",epoch.plusMillis(ms))))
        val ids=mutableListOf<String>()
        commands.forEachVisit(JournalDateRange(epoch.minusNanos(1_500_000),epoch.plusNanos(500_000))) {ids+=it.id}
        assertEquals(listOf("-1","0"),ids)
        ids.clear()
        commands.forEachVisit(JournalDateRange(epoch.plusNanos(1),epoch.plusNanos(2))) {ids+=it.id}
        assertTrue(ids.isEmpty())
        fails<IllegalArgumentException> {JournalDateRange(now,now.minusMillis(1));Unit}
    }

    @Test fun civilDayUsesItsZoneAndExcludesNextMidnightOnDstDay()=runBlocking {
        val zone=ZoneId.of("America/New_York")
        val day=LocalDate.of(2026,3,8);val bounds=JournalDateRange.day(day,zone)
        assertEquals(Duration.ofHours(23),Duration.between(bounds.from,bounds.through.plusNanos(1)))
        db.history().put(VisitRow(visit("first",bounds.from)))
        db.history().put(VisitRow(visit("last",bounds.storageThrough)))
        db.history().put(VisitRow(visit("tomorrow",bounds.through.plusNanos(1))))
        val result=mutableListOf<String>();commands.forEachVisit(bounds) {result+=it.id}
        assertEquals(listOf("first","last"),result)
        assertEquals(LocalDate.of(2026,10,28),commands.today(ZoneOffset.UTC).from.atZone(ZoneOffset.UTC).toLocalDate())
    }

    @Test fun statisticsStreamAllPointsAndCountArrivalRatherThanOverlappingVisits()=runBlocking {
        db.writeTransaction {
            repeat(1201) {i ->db.history().put(PointRow(TrackPoint("p-%04d".format(i),Coordinate(18.0+i*.000001,-66.0),now,5.0,outlier=i==600),null))}
            db.history().put(VisitRow(visit("inside")))
            db.history().put(VisitRow(visit("before",now.minusMillis(1))))
        }
        val stats=commands.statistics(range)
        assertEquals(1201L,stats.points.count);assertEquals(1L,stats.points.outlierCount)
        assertEquals(1L,stats.visitCount)
        assertEquals(coordinate.distanceTo(Coordinate(18.0012,-66.0)),stats.points.distanceMeters,.001)
    }

    @Test fun renameVisitChangesOnlyDisplayNameAndUpdateTime()=runBlocking {
        val old=visit("visit",now.minusSeconds(60)).copy(name="Detected",address="Synthetic address",notes="Notes",confirmation=Confirmation.corrected,
            originalCoordinate=Coordinate(19.0,-65.0),originalName="Original",detectedName="Detector",countryCode="PR",timeZone="America/Puerto_Rico",confirmedAt=now.minusSeconds(1))
        db.history().put(VisitRow(old))
        val renamed=commands.renameVisit(old.id,"  New name\n")
        assertEquals(old.copy(name="New name",updatedAt=now),renamed)
        assertEquals(renamed,db.history().visit(old.id)!!.value)
        fails<IllegalArgumentException> {commands.renameVisit(old.id," \n ");Unit}
        fails<JournalItemNotFound> {commands.renameVisit("missing","Name");Unit}
        assertEquals(renamed,db.history().visit(old.id)!!.value)
    }

    @Test fun renameCurrentSelectsNewestOpenAndPreservesNotesAndTimes()=runBlocking {
        val older=RecordingSession("older",now.minusSeconds(60),name="Old",notes="Keep older notes")
        val current=RecordingSession("current",now.minusSeconds(30),notes="Keep current notes")
        val closed=RecordingSession("closed",now.minusSeconds(10),now)
        listOf(older,current,closed).forEach {db.history().put(SessionRow(it))}
        assertEquals(current,commands.currentOuting())
        assertEquals(current.copy(name="Walk"),commands.renameCurrentOuting(" Walk "))
        assertEquals(older,db.history().session(older.id)!!.value)
        assertEquals(closed.copy(name="Archived"),commands.renameOuting(closed.id,"Archived"))
        fails<IllegalArgumentException> {commands.renameOuting("missing"," ");Unit}
        fails<JournalItemNotFound> {commands.renameOuting("missing","Name");Unit}
        db.history().put(SessionRow(older.copy(endedAt=now)));db.history().put(SessionRow(current.copy(endedAt=now)))
        fails<NoCurrentOuting> {commands.currentOuting();Unit}
        fails<NoCurrentOuting> {commands.renameCurrentOuting("Name");Unit}
    }

    @Test fun cancelledConsumerStopsWithoutMutatingHistoryAndReaderCanBeReused()=runBlocking {
        seed(1201);var count=0
        fails<CancellationException> {commands.forEachVisit(range) {if(++count==501) throw CancellationException("fixture")}}
        assertEquals(501,count);assertEquals(1201L,db.history().totalVisitCount())
        assertEquals(1201L,commands.statistics(range).visitCount)
    }

    @Test fun automationRenameInvalidatesAStaleVisitEditorWithoutOverwritingItsOtherFields()=runBlocking {
        val old=visit("visit",now.minusSeconds(1)).copy(name="Before",notes="Saved notes")
        db.history().put(VisitRow(old))
        val renamed=commands.renameVisit(old.id,"Automation name")
        fails<VisitChanged> {history.saveVisit(old.copy(notes="Stale editor notes"),old);Unit}
        assertEquals(renamed,db.history().visit(old.id)!!.value)
    }

    @Test fun streamedReadKeepsOneSnapshotWhileIndependentWriterCommits()=runBlocking {
        seed(1201)
        val entered=CompletableDeferred<Unit>();val committed=CompletableDeferred<Unit>()
        val writer=launch(Dispatchers.IO) {
            entered.await()
            db.history().put(VisitRow(visit("new-after-snapshot")))
            committed.complete(Unit)
        }
        val ids=mutableListOf<String>()
        withTimeout(15_000) {commands.forEachVisit(range) {
            ids+=it.id
            if(ids.size==1) {entered.complete(Unit);committed.await()}
        }}
        writer.join()
        assertEquals(1201,ids.size);assertFalse(ids.contains("new-after-snapshot"))
        assertEquals(1202L,commands.statistics(range).visitCount)
    }
}
