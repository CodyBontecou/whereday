package com.bontecou.whereday.data

import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID

class HistorySnapshotTest {
    @Test(timeout=15_000) fun cancellationReachesSuspendedReaderAndReleasesItsTransaction()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName!="com.bontecou.whereday")
        for(disk in listOf(false,true)) {
            val name=if(disk) "snapshot-cancel-${UUID.randomUUID()}.db" else null
            val db=HistoryDatabase.create(context,name)
            try {
                val entered=CompletableDeferred<Unit>();val released=CompletableDeferred<Unit>()
                val reader=async {db.readSnapshot {
                    try {entered.complete(Unit);awaitCancellation()} finally {released.complete(Unit)}
                }}
                entered.await()
                withTimeout(5000) {reader.cancelAndJoin();released.await()}
                assertTrue(reader.isCancelled)
                val point=TrackPoint(id="after-cancel",coordinate=Coordinate(0.0,0.0),time=Instant.EPOCH,accuracy=5.0)
                withTimeout(5000) {db.history().put(PointRow(point,null));assertEquals(1L,db.readSnapshot {it.totalPointCount()})}
            } finally {db.close();if(name!=null) context.deleteDatabase(name)}
        }
    }
    @Test fun callbackFailureReleasesSnapshotAndNestedReadsUseTheSameTransaction()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName!="com.bontecou.whereday")
        val db=HistoryDatabase.create(context,null)
        try {
            try {db.readSnapshot {error("synthetic reader failure")};fail()} catch(e:IllegalStateException) {assertEquals("synthetic reader failure",e.message)}
            db.history().put(PointRow(TrackPoint(id="preserved",coordinate=Coordinate(0.0,0.0),time=Instant.EPOCH,accuracy=5.0),null))
            db.readSnapshot {outer->
                assertEquals(1L,outer.totalPointCount())
                assertEquals(1L,db.readSnapshot {it.totalPointCount()})
            }
        } finally {db.close()}
    }
    @Test fun exportReaderRetainsSnapshotAcrossPagesWhileRecordingWriterCommits()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName!="com.bontecou.whereday")
        val name="snapshot-test-${UUID.randomUUID()}.db"
        val db=HistoryDatabase.create(context,name)
        val writerScope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
        try {
            val time=Instant.parse("2026-10-27T00:00:00Z");val from=time.minusSeconds(1);val until=time.plusSeconds(1)
            db.history().putPoints((0..1001).map {PointRow(TrackPoint(id="point-%04d".format(it),coordinate=Coordinate(18.0,-66.0),time=time,accuracy=5.0),null)})
            repeat(1002) {db.history().put(VisitRow(Visit(id="visit-%04d".format(it),coordinate=Coordinate(18.0,-66.0),arrivedAt=time)))}
            db.readSnapshot { dao ->
                val first=dao.exportFirstPoints(from,until,null);assertEquals(500,first.size)
                withTimeout(5000) {writerScope.async {
                    db.history().put(PointRow(TrackPoint(id="new-recorded-point",coordinate=Coordinate(19.0,-67.0),time=time,accuracy=5.0),null))
                    db.history().deletePointsById(listOf("point-0700"))
                    db.history().deleteVisit("visit-0700")
                }.await()}
                val ids=mutableListOf<String>();var page=first
                while(page.isNotEmpty()) {ids+=page.map {it.value.id};val last=page.last().value;page=dao.exportNextPoints(last.time,last.id,until,null)}
                assertEquals(1002,ids.size);assertEquals(1002,ids.distinct().size)
                assertTrue(ids.contains("point-0700"));assertFalse(ids.contains("new-recorded-point"))
                var visitPage=dao.exportFirstVisits(from,until);var count=0
                while(visitPage.isNotEmpty()) {count+=visitPage.size;val last=visitPage.last().value;visitPage=dao.exportNextVisits(last.arrivedAt,last.id,until)}
                assertEquals(1002,count)
                assertEquals(first,dao.exportFirstPoints(from,until,null)) // A second format/count pass sees identical data.
            }
            assertNotNull(db.history().point("new-recorded-point"));assertNull(db.history().point("point-0700"));assertNull(db.history().visit("visit-0700"))
        } finally {writerScope.cancel();db.close();context.deleteDatabase(name)}
    }
}
