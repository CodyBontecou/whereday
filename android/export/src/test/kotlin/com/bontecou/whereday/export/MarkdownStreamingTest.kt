package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.*
import java.nio.file.Files
import java.time.*
import java.util.concurrent.CancellationException

class MarkdownStreamingTest {
    private val at=Instant.parse("2026-10-28T12:00:00Z")
    private val visit=Visit(coordinate=Coordinate(18.0,-66.0),arrivedAt=at,name="北 🗺️ | \\ \r\n")
    private fun source(failure:Throwable?=null)=object:ExportRecordSource {
        override suspend fun visits(consume:suspend (Visit)->Unit) {consume(visit);failure?.let {throw it};consume(visit.copy(arrivedAt=at.plusSeconds(86400)))}
        override suspend fun points(consume:suspend (TrackPoint)->Unit) {}
    }
    @Test fun privateSpoolIsRemovedAfterSuccessfulReverseDayReplay()=runBlocking {
        val directory=Files.createTempDirectory("markdown-test").toFile()
        try {
            val options=ExportOptions(kind=DataKind.visits,zone=ZoneOffset.UTC)
            val output=StringWriter();val counts=StreamingHistoryExporter.write(output,ExportFormat.markdown,source(),options,at,directory)
            assertEquals(2L,counts.visits);assertTrue(directory.listFiles()!!.isEmpty())
            assertEquals(HistoryExporter.render(ExportFormat.markdown,listOf(visit,visit.copy(arrivedAt=at.plusSeconds(86400))),emptyList(),options,at),output.toString())
            assertTrue(output.toString().contains("北 🗺️ \\| \\\\  "))
        } finally {directory.deleteRecursively()}
    }
    @Test fun sourceFailureAndCancellationRetireScratchAndDoNotWriteAnExportHeader()=runBlocking {
        for(failure in listOf(IOException("source"),CancellationException("cancelled"))) {
            val directory=Files.createTempDirectory("markdown-test").toFile();val output=StringWriter()
            try {
                try {StreamingHistoryExporter.write(output,ExportFormat.markdown,source(failure),scratchDirectory=directory);fail("Expected failure")} catch(actual:Throwable) {assertSame(failure,actual)}
                assertTrue(directory.listFiles()!!.isEmpty());assertEquals("",output.toString())
            } finally {directory.deleteRecursively()}
        }
    }
    @Test fun outputFailureDuringReverseReplayRetiresScratch()=runBlocking {
        val directory=Files.createTempDirectory("markdown-test").toFile();val failure=IOException("output")
        val output=object:Writer() {override fun write(chars:CharArray,offset:Int,length:Int) {if(String(chars,offset,length).contains("## Wednesday")) throw failure};override fun flush() {};override fun close() {}}
        try {
            try {StreamingHistoryExporter.write(output,ExportFormat.markdown,source(),ExportOptions(kind=DataKind.visits,zone=ZoneOffset.UTC),at,directory);fail("Expected failure")} catch(actual:Throwable) {assertSame(failure,actual)}
            assertTrue(directory.listFiles()!!.isEmpty())
        } finally {directory.deleteRecursively()}
    }
    @Test fun unavailableScratchDirectoryFailsBeforeReadingSource()=runBlocking {
        val file=Files.createTempFile("markdown-test",".file").toFile();var read=false
        val source=object:ExportRecordSource {override suspend fun visits(consume:suspend (Visit)->Unit) {read=true};override suspend fun points(consume:suspend (TrackPoint)->Unit) {read=true}}
        try {try {StreamingHistoryExporter.write(StringWriter(),ExportFormat.markdown,source,scratchDirectory=file);fail("Expected storage failure")} catch(_:IOException) {};assertFalse(read)} finally {file.delete()}
    }
}
