package com.bontecou.whereday.platform

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.*
import com.google.gson.stream.JsonReader
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.util.UUID

/** Explicit, isolated large-history acceptance case; excluded from the short CI suite. */
class ExportScalingTest {
    @Test fun millionPersistedPointsExportWithoutSamplingOrWholeFileAllocation()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName!="com.bontecou.whereday")
        check(context.cacheDir.usableSpace>1_000_000_000L) {"This synthetic test needs 1 GB of free device storage"}
        val name="export-million-synthetic-${UUID.randomUUID()}.db"
        val db=HistoryDatabase.create(context,name);val files=ExportFiles(context,db) {}
        try {
            val start=Instant.parse("2026-01-01T00:00:00Z")
            db.writeTransaction {
                repeat(2000) {batch -> db.history().putPoints((0 until 500).map {offset ->
                    val index=batch*500+offset
                    PointRow(TrackPoint(id="synthetic-%07d".format(index),coordinate=Coordinate(18.0,-66.0),time=start.plusMillis(index.toLong()),accuracy=5.0),null)
                })}
            }
            var sampledHeap=0L
            val before=android.os.SystemClock.elapsedRealtime()
            val prepared=files.prepare(ExportFileRequest(setOf(ExportFormat.json),ExportOptions(kind=DataKind.points))) {
                if(it.points%50_000==0L) sampledHeap=maxOf(sampledHeap,Runtime.getRuntime().totalMemory()-Runtime.getRuntime().freeMemory())
            }
            val elapsed=android.os.SystemClock.elapsedRealtime()-before
            val file=prepared.files.single();assertEquals(1_000_000L,file.counts.points)
            var parsed=0L;var declared=-1L
            JsonReader(file.file.bufferedReader()).use {reader ->
                reader.beginObject()
                while(reader.hasNext()) when(reader.nextName()) {
                    "points" -> {reader.beginArray();while(reader.hasNext()) {reader.skipValue();parsed++};reader.endArray()}
                    "totalPoints" -> declared=reader.nextLong()
                    else -> reader.skipValue()
                }
                reader.endObject()
            }
            assertEquals(1_000_000L,parsed);assertEquals(parsed,declared)
            android.util.Log.i("WheredayQa","million_point_export_ms=$elapsed bytes=${file.bytes} records=$parsed sampled_heap_bytes=$sampledHeap max_heap_bytes=${Runtime.getRuntime().maxMemory()}")
            Unit
        } finally {files.clearCache();db.close();context.deleteDatabase(name)}
    }
}
