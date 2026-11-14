package com.bontecou.whereday.data

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.*

/** Synthetic disk history in the exact disposable data-test package. No physical data is read. */
class TimelineStatisticsScalingTest {
    @Test fun millionPointsAndFiveHundredOverlappingOutingsKeepCompleteMetricsAndBoundedPreview():Unit=runBlocking {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        check(context.packageName=="com.bontecou.whereday.data.test")
        val name="timeline-statistics-${java.util.UUID.randomUUID()}.db"
        val db=HistoryDatabase.create(context,name);val start=Instant.parse("2026-01-01T00:00:00Z")
        try {
            val repo=HistoryRepository(db,Clock.fixed(start.plusSeconds(2_000_000),ZoneOffset.UTC))
            val reference=OutingPointSummaryBuilder()
            db.writeTransaction {
                repeat(500) {repo.dao.put(SessionRow(RecordingSession("overlap-$it",start,start.plusSeconds(999_999))))}
                repeat(2000) {batch->repo.dao.putPoints((0 until 500).map {offset->
                    val i=batch*500+offset
                    val point=TrackPoint(i.toString().padStart(7,'0'),Coordinate(18.0+(i%1000)*0.000001,-66.0),start.plusSeconds(i.toLong()),5.0)
                    reference.add(point);PointRow(point,null)
                })}
            }
            val before=android.os.SystemClock.elapsedRealtime()
            val range=HistoryRange.day(LocalDate.of(2026,1,5),ZoneOffset.UTC)
            val window=repo.readWindow(range,inference=InferenceRules(),includeTimelineActivity=true)
            val elapsed=android.os.SystemClock.elapsedRealtime()-before
            val index=checkNotNull(window.timeline)
            assertEquals(500,index.outings.size);assertTrue(window.inferred.isEmpty())
            assertEquals(2500,window.points.size);assertEquals(86400L,window.summary.count)
            assertTrue(index.outings.all {it.summary.count==1_000_000L})
            assertEquals(reference.build().distanceMeters*500,index.distanceMeters,0.02)
            assertEquals(1_000_000L,repo.dao.totalPointCount())
            val memory=android.os.Debug.MemoryInfo();android.os.Debug.getMemoryInfo(memory)
            val runtime=Runtime.getRuntime()
            instrumentation.sendStatus(2,android.os.Bundle().apply {
                putString("timelineScale","million_points=1000000 overlapping_outings=500 read_ms=$elapsed displayed=${window.points.size} heap_used=${runtime.totalMemory()-runtime.freeMemory()} heap_max=${runtime.maxMemory()} total_pss_kib=${memory.totalPss}")
            })
        } finally {db.close();context.deleteDatabase(name)}
    }
}
