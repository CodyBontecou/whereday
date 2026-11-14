package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.time.Instant

internal class KmlCountingWriter:Writer() {
    var characters=0L;var whens=0L;var coordinates=0L;var sessions=0L;var maxWrite=0
    var afterWrite:()->Unit={}
    override fun write(buffer:CharArray,offset:Int,length:Int) {
        characters+=length;maxWrite=maxOf(maxWrite,length)
        val text=String(buffer,offset,length)
        if(text.startsWith("          <when>")) whens++
        if(text.startsWith("          <gx:coord>")) coordinates++
        if(text.contains("<name>Whereday session ")) sessions++
        afterWrite()
    }
    override fun flush() {}
    override fun close() {}
}
class KmlStreamingTest {
    private val start=Instant.parse("2026-10-28T00:00:00Z")
    private suspend fun fixture(block:suspend (File)->Unit) {
        val scratch=Files.createTempDirectory("kml-streaming-").toFile()
        try {block(scratch)} finally {scratch.deleteRecursively()}
    }
    private fun source(count:Int,interval:Long=1,consumePoint:(Int)->Unit={}):ExportRecordSource=object:ExportRecordSource {
        override suspend fun visits(consume:suspend (Visit)->Unit) {error("points-only source")}
        override suspend fun points(consume:suspend (TrackPoint)->Unit) {
            repeat(count) {i->consumePoint(i);consume(TrackPoint(id=i.toString(),coordinate=Coordinate(18.0,-66.0),time=start.plusSeconds(i*interval),accuracy=5.0))}
        }
    }
    @Test fun manySingletonSessionsReuseOneSpoolWithoutAnAccumulatedSessionList()=runBlocking {fixture {scratch->
        val sink=KmlCountingWriter();var maxFiles=0
        val counts=StreamingHistoryExporter.write(sink,ExportFormat.kml,source(2048,601) {maxFiles=maxOf(maxFiles,scratch.listFiles().orEmpty().size)},ExportOptions(kind=DataKind.points),start,scratch)
        assertEquals(2048L,counts.points);assertEquals(2048L,sink.sessions);assertEquals(2048L,sink.whens);assertEquals(2048L,sink.coordinates)
        assertEquals(1,maxFiles);assertTrue(scratch.listFiles().orEmpty().isEmpty())
    }}
    @Test fun sourceAndOutputFailuresDeletePrivateScratchAndNeverReturnSuccess()=runBlocking {fixture {scratch->
        for(stage in listOf("source","output")) {
            val sink=KmlCountingWriter().apply {afterWrite={if(stage=="output" && characters>20_000) throw IOException("synthetic sink full")}}
            try {
                StreamingHistoryExporter.write(sink,ExportFormat.kml,source(2000) {if(stage=="source" && it==501) throw IOException("synthetic source failure")},ExportOptions(kind=DataKind.points),start,scratch)
                fail("Failure must propagate at $stage")
            } catch(_:IOException) {}
            assertTrue("Scratch survived $stage failure",scratch.listFiles().orEmpty().isEmpty())
        }
    }}
    @Test fun cancellationWhileFillingOrReplayingDeletesScratchAndStopsOutput()=runBlocking {fixture {scratch->
        for(stage in listOf("fill","replay")) {
            var consumed=0
            val task=async {
                val job=currentCoroutineContext()[Job]!!
                val sink=KmlCountingWriter().apply {afterWrite={if(stage=="replay" && characters>20_000) job.cancel()}}
                StreamingHistoryExporter.write(sink,ExportFormat.kml,source(2000) {consumed++;if(stage=="fill" && it==501) job.cancel()},ExportOptions(kind=DataKind.points),start,scratch)
            }
            try {task.await();fail("Cancellation must propagate at $stage")} catch(_:CancellationException) {}
            if(stage=="fill") assertTrue(consumed<=502) else assertEquals(2000,consumed)
            assertTrue("Scratch survived $stage cancellation",scratch.listFiles().orEmpty().isEmpty())
        }
    }}
    @Test fun unavailableScratchStorageFailsWithoutReadingTheSource()=runBlocking {fixture {directory->
        val file=File(directory,"not-a-directory").apply {writeText("synthetic")};var reads=0
        try {
            StreamingHistoryExporter.write(KmlCountingWriter(),ExportFormat.kml,source(1) {reads++},ExportOptions(kind=DataKind.points),start,file)
            fail("No scratch storage")
        } catch(_:IOException) {}
        assertEquals(0,reads);assertEquals(listOf(file),directory.listFiles()!!.toList())
    }}
}
