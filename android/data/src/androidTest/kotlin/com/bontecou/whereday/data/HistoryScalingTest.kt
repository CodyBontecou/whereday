package com.bontecou.whereday.data

import androidx.room.Room
import androidx.room.withTransaction
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import java.time.Instant

/** Synthetic disk-backed fixture: no production journal or physical location is read. */
@RunWith(AndroidJUnit4::class)
class HistoryScalingTest {
    @Test fun millionPointHistoryKeepsBoundedPreviewAndCompleteStatistics() = runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val name="scaling-${java.util.UUID.randomUUID()}.db"
        val database=HistoryDatabase.create(context,name)
        try {
            val dao=database.history()
            val start=Instant.parse("2026-01-01T00:00:00Z")
            database.writeTransaction {
                repeat(2000) { batch ->
                    dao.putPoints((0 until 500).map { offset ->
                        val index=batch*500+offset
                        PointRow(TrackPoint(id=index.toString().padStart(7,'0'),coordinate=Coordinate(18.0+(index%10000)*0.000001,-66.0),time=start.plusSeconds(index.toLong()),accuracy=5.0),null)
                    })
                }
            }
            val before=android.os.SystemClock.elapsedRealtime()
            val window=HistoryRepository(database).readWindow(HistoryRange.all)
            val elapsed=android.os.SystemClock.elapsedRealtime()-before
            assertEquals(1_000_000L,window.summary.count)
            assertEquals(2500,window.points.size)
            assertEquals("0000000",window.points.first().id)
            assertEquals("0999999",window.points.last().id)
            assertEquals(start,window.summary.firstTime)
            assertEquals(start.plusSeconds(999999),window.summary.lastTime)
            android.util.Log.i("WheredayQa","million_point_window_read_ms=$elapsed displayed=${window.points.size} total=${window.summary.count}")
            Unit
        } finally { database.close(); context.deleteDatabase(name) }
    }
}
