package com.bontecou.whereday.data

import androidx.room.useReaderConnection
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.Instant

@RunWith(AndroidJUnit4::class)
class SessionSummaryTest {
    private lateinit var db:HistoryDatabase
    private lateinit var history:HistoryRepository
    private val start=Instant.parse("2026-10-27T10:00:00Z")
    @Before fun setup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName!="com.bontecou.whereday")
        db=HistoryDatabase.create(context,null);history=HistoryRepository(db)
    }
    @After fun close() {db.close()}
    private fun point(i:Int)=TrackPoint(id="fix-%05d".format(i),coordinate=Coordinate(18.0+i*0.00001,-66.0),time=start.plusSeconds((i/3).toLong()),accuracy=5.0,outlier=i%47==0)
    @Test fun completeSessionIncludesEqualTimestampPagesAndExcludesUnassignedAndOtherSessions()=runBlocking {
        val expected=PointSummaryBuilder()
        repeat(11) {batch->
            val points=(batch*500 until minOf(5001,(batch+1)*500)).map(::point)
            points.forEach(expected::add);history.dao.putPoints(points.map {PointRow(it,"owned")})
        }
        history.dao.put(PointRow(point(7000).copy(id="import",coordinate=Coordinate(0.0,0.0)),null))
        history.dao.put(PointRow(point(7001).copy(id="other",coordinate=Coordinate(1.0,1.0)),"other"))
        val actual=history.sessionSummary("owned")
        assertEquals(expected.build(),actual);assertEquals(5001L,actual.count)
        assertTrue(actual.distanceMeters>5000)
        assertEquals(PointSummary(),history.sessionSummary("missing"))
    }
    @Test fun sessionPagesUseTheSessionTimeIndexWithoutATemporarySort()=runBlocking {
        db.useReaderConnection {connection->
            for(sql in listOf(
                "SELECT * FROM points WHERE sessionId='owned' ORDER BY value_time,value_id LIMIT 500",
                "SELECT * FROM points WHERE sessionId='owned' AND (value_time,value_id)>(0,'cursor') ORDER BY value_time,value_id LIMIT 500"
            )) connection.usePrepared("EXPLAIN QUERY PLAN $sql") {statement->
                val details=buildList {while(statement.step()) add(statement.getText(3))}.joinToString(" ")
                assertTrue(details,details.contains("index_points_sessionId_value_time_value_id"))
                assertFalse(details,details.contains("TEMP B-TREE"))
            }
        }
    }
    @Test fun reflaggedAndDeletedFixesAreReflectedWithoutRetainingAnOldDistance()=runBlocking {
        val points=listOf(point(0).copy(outlier=false),point(1).copy(coordinate=Coordinate(18.01,-66.0),outlier=false),point(2).copy(outlier=false))
        history.dao.putPoints(points.map {PointRow(it,"owned")})
        val before=history.sessionSummary("owned")
        history.dao.flagPoint(points[1].id)
        val after=history.sessionSummary("owned")
        assertEquals(3L,after.count);assertEquals(1L,after.outlierCount)
        assertEquals(points.first().coordinate.distanceTo(points.last().coordinate),after.distanceMeters,0.0001)
        assertTrue(before.distanceMeters>after.distanceMeters*10)
        history.dao.deleteSessionPoints("owned")
        assertEquals(PointSummary(),history.sessionSummary("owned"))
    }
}
