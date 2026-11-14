package com.bontecou.whereday.platform

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.HistoryDatabase
import com.bontecou.whereday.data.PointRow
import com.bontecou.whereday.export.ExportFormat
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.InputStream
import java.time.*
import java.util.*
import java.util.concurrent.atomic.AtomicLong

/** Disposable platform-test storage only; never targets or instruments the owner app. */
class DiskHistoryImportScaleTest {
    private class Fragments(parts:Sequence<String>):InputStream() {
        private val parts=parts.iterator();private var bytes=ByteArray(0);private var offset=0
        var delivered=0L;private set
        override fun read():Int {val byte=ByteArray(1);return if(read(byte,0,1)<0) -1 else byte[0].toInt() and 255}
        override fun read(buffer:ByteArray,start:Int,length:Int):Int {
            if(length==0) return 0
            while(offset==bytes.size) {if(!parts.hasNext()) return -1;bytes=parts.next().toByteArray(Charsets.UTF_8);offset=0}
            val size=minOf(length,bytes.size-offset);bytes.copyInto(buffer,start,offset,offset+size);offset+=size;delivered+=size;return size
        }
    }
    @Test fun millionJsonPointsReviewCommitDeduplicateAndClear()=runBlocking {exercise(ExportFormat.json)}
    @Test fun millionCsvPointsReviewCommitDeduplicateAndClear()=runBlocking {exercise(ExportFormat.csv)}
    @Test fun millionMarkdownPointsReviewCommitDeduplicateAndClear()=runBlocking {exercise(ExportFormat.markdown)}
    @Test fun tenThousandJsonPointsReviewCommitDeduplicateAndClear()=runBlocking {exercise(ExportFormat.json,10_000)}
    private suspend fun exercise(format:ExportFormat,count:Int=1_000_000)=coroutineScope {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;check(context.packageName=="com.bontecou.whereday.platform.test")
        val name="import-scale-${UUID.randomUUID()}.db";val directory=File(context.cacheDir,"import-scale-${UUID.randomUUID()}")
        val db=HistoryDatabase.create(context,name)
        val actualTarget=DatabaseHistoryImportTarget(db)
        val timedTarget=object:StagedHistoryImportTarget by actualTarget {
            override suspend fun inspectPlan(stage:HistoryImportStage,check:()->Unit):HistoryImportInspection {
                val started=System.nanoTime()
                return try {actualTarget.inspectPlan(stage,check)}
                finally {println("$format inspect: ${(System.nanoTime()-started)/1_000_000} ms")}
            }
            override suspend fun apply(stage:HistoryImportStage,policy:HistoryImportPolicy,check:()->Unit):HistoryImportCounts {
                var verificationNanos=0L
                val timedStage=object:HistoryImportStage by stage {
                    override fun verify(check:()->Unit) {
                        val started=System.nanoTime()
                        try {stage.verify(check)} finally {verificationNanos+=System.nanoTime()-started}
                    }
                }
                val started=System.nanoTime()
                return try {actualTarget.apply(timedStage,policy,check)}
                finally {println("$format apply: ${(System.nanoTime()-started)/1_000_000} ms; verification: ${verificationNanos/1_000_000} ms")}
            }
        }
        val controller=HistoryImportController(timedTarget,DiskHistoryImportWorkspaceFactory(directory))
        val epoch=Instant.parse("2026-10-26T00:00:00Z")
        fun input()=Fragments(sequence {
            yield(when(format) {ExportFormat.json->"{\"points\":[";ExportFormat.csv->"timestamp,latitude,longitude\n";else->"| Time | Lat | Lon |\n| --- | --- | --- |\n"})
            repeat(count) {i->val time=epoch.plusSeconds(i.toLong())
                yield(when(format) {
                    ExportFormat.json->(if(i==0) "" else ",")+"{\"timestamp\":\"$time\",\"latitude\":18,\"longitude\":-66}"
                    ExportFormat.csv->"$time,18,-66\n"
                    else->"| $time | 18 | -66 |\n"
                })
            }
            if(format==ExportFormat.json) yield("]}")
        })
        val input=input();val peak=AtomicLong()
        val sampler=launch(Dispatchers.Default) {
            while(isActive) {val runtime=Runtime.getRuntime();peak.accumulateAndGet(runtime.totalMemory()-runtime.freeMemory(),::maxOf);delay(100)}
        }
        val started=System.nanoTime()
        try {
            withTimeout(20*60_000L) {
                controller.select(object:HistoryImportInput {override fun open()=input},Locale.US,ZoneOffset.UTC)!!.join()
                val reviewAt=System.nanoTime();val state=controller.state.value
                assertEquals(state.error,HistoryImportPhase.review,state.phase)
                if(count==1_000_000) assertTrue(input.delivered>25L*1024*1024)
                assertEquals(count,state.review!!.points)
                assertEquals(0L,db.history().totalPointCount());assertEquals(0,state.review!!.existing)
                println("$format review: ${input.delivered} bytes, $count points, ${(reviewAt-started)/1_000_000} ms")
                controller.commit(state.review!!.id,HistoryImportPolicy.keepExisting)!!.join()
                assertEquals(controller.state.value.error,HistoryImportPhase.complete,controller.state.value.phase)
                assertEquals(HistoryImportCounts(points=count),controller.state.value.counts)
                assertEquals(count.toLong(),db.history().totalPointCount())
                println("$format commit: $count points, ${(System.nanoTime()-reviewAt)/1_000_000} ms")
                assertFalse(directory.walkTopDown().any {it.name=="source"})
                val local=db.history().exportFirstPoints(Instant.EPOCH,Instant.ofEpochMilli(Long.MAX_VALUE),null).first()
                db.history().put(PointRow(local.value.copy(accuracy=9.0),"local-session"))
                controller.discard()
                val repeated=input()
                controller.select(object:HistoryImportInput {override fun open()=repeated},Locale.US,ZoneOffset.UTC)!!.join()
                val again=controller.state.value
                assertEquals(again.error,HistoryImportPhase.review,again.phase);assertEquals(count,again.review!!.existing)
                controller.commit(again.review!!.id,HistoryImportPolicy.keepExisting)!!.join()
                assertEquals(controller.state.value.error,HistoryImportPhase.complete,controller.state.value.phase)
                assertEquals(HistoryImportCounts(kept=count),controller.state.value.counts)
                assertEquals(count.toLong(),db.history().totalPointCount())
                assertEquals("local-session",db.history().point(local.value.id)!!.sessionId)
                assertEquals(9.0,db.history().point(local.value.id)!!.value.accuracy,0.0)
                println("$format reimport: $count existing records kept; no duplicate writes")
                controller.clearAll {db.clearAllTables()}
                assertEquals(0L,db.history().totalPointCount());assertTrue(directory.listFiles().orEmpty().isEmpty())
                println("$format complete: ${(System.nanoTime()-started)/1_000_000} ms; sampled Java peak ${peak.get()}; heap limit ${Runtime.getRuntime().maxMemory()}")
            }
        } finally {sampler.cancelAndJoin();controller.close();db.close();context.deleteDatabase(name);directory.deleteRecursively()}
    }
}
