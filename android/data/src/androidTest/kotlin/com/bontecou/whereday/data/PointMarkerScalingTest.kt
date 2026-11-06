package com.bontecou.whereday.data

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

/** Separate synthetic disk database; no owner package or journal is queried. */
class PointMarkerScalingTest {
    @Test fun millionPointDiskHistoryFindsUnsampledAndSelectedPointsWithBoundedMarkers()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName!="com.bontecou.whereday")
        val name="synthetic-point-markers-${java.util.UUID.randomUUID()}.db"
        val database=HistoryDatabase.create(context,name)
        val start=Instant.parse("2026-11-02T00:00:00Z")
        try {
            val history=HistoryRepository(database)
            database.writeTransaction {
                repeat(2000) {batch->history.dao.putPoints((0 until 500).map {offset->
                    val i=batch*500+offset
                    PointRow(TrackPoint(i.toString().padStart(7,'0'),if(i==1) Coordinate(40.0,60.0) else Coordinate(0.0,0.0),start.plusMillis(i.toLong()/2),5.0),null)
                })}
            }
            val before=android.os.SystemClock.elapsedRealtime()
            val markers=history.readPointMarkers(ReplayRequest.Range(HistoryRange.all),MapPointViewport(0.0,0.0,1.0,1.0,1800.0,1200.0),"0555555")
            val elapsed=android.os.SystemClock.elapsedRealtime()-before
            assertEquals(listOf("0000001","0555555"),markers.points.map {it.id})
            assertEquals("0555555",markers.selected?.id);assertTrue(markers.points.size<=160)
            val window=history.readWindow(HistoryRange.all)
            assertEquals(2500,window.points.size);assertFalse(window.points.any {it.id=="0000001"})
            assertEquals(1_000_000L,window.summary.count);assertEquals(1_000_000L,history.dao.totalPointCount())
            InstrumentationRegistry.getInstrumentation().sendStatus(2,android.os.Bundle().apply {
                putString("markerScale","million_point_read_ms=$elapsed markers=${markers.points.size} route_display=${window.points.size} total=${window.summary.count}")
            })
        } finally {database.close();context.deleteDatabase(name)}
    }
}
