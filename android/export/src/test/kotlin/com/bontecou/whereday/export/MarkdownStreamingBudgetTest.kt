package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.Writer
import java.nio.file.Files
import java.time.*
import java.util.Locale

class MarkdownStreamingBudgetTest {
    @Test fun individualOutingStreamsMillionVisitLinksRowsAndRoutePoints()=runBlocking {
        assertTrue(Runtime.getRuntime().maxMemory()<=100L*1024*1024)
        val count=1_000_000L;val at=Instant.parse("2026-01-01T00:00:00Z");var visitReads=0;var pointReads=0
        val source=object:ExportRecordSource {
            override suspend fun visits(consume:suspend (Visit)->Unit) {visitReads++;repeat(count.toInt()) {consume(Visit(id="",coordinate=Coordinate(18.0,-66.0),arrivedAt=at.plusSeconds(it.toLong()),name="Visit"))}}
            override suspend fun points(consume:suspend (TrackPoint)->Unit) {pointReads++;repeat(count.toInt()) {consume(TrackPoint(id="",coordinate=Coordinate(18.0,-66.0),time=at.plusSeconds(it.toLong()),accuracy=5.0))}}
        }
        val outing=ExportableOuting("outing","Large outing",1,at,at.plusSeconds(count),at.plusSeconds(count),count.toDouble(),0.0,null,count,0,count,false,false,null,null,null)
        var characters=0L;var rows=0L;var position=0;val linePrefix=StringBuilder()
        val writer=object:Writer() {
            override fun write(chars:CharArray,offset:Int,length:Int) {characters+=length
                for(i in offset until offset+length) {
                    val c=chars[i]
                    if(position<6) linePrefix.append(c)
                    if(position==5 && linePrefix.toString()=="| Jan ") rows++
                    if(c=='\n') {position=0;linePrefix.setLength(0)} else position++
                }
            }
            override fun flush() {};override fun close() {}
        }
        val counts=StreamingOutingExporter.write(writer,outing,source,ExportFormat.markdown,ExportOptions(zone=ZoneOffset.UTC),at,locale=Locale.US)
        assertEquals(ExportCounts(count,count),counts);assertEquals(count*2,rows);assertEquals(2,visitReads);assertEquals(1,pointReads);assertTrue(characters>100_000_000L)
        println("Outing Markdown: $rows data rows, $characters chars, heap limit ${Runtime.getRuntime().maxMemory()}; two visit/one point reads")
    }
    @Test fun millionVisitsAndPointsHaveBoundedReverseDayStaging()=runBlocking {
        assertTrue(Runtime.getRuntime().maxMemory()<=100L*1024*1024)
        val count=1_000_000;var visitsRead=0;var pointsRead=0;val at=Instant.parse("2026-01-01T00:00:00Z")
        val source=object:ExportRecordSource {
            override suspend fun visits(consume:suspend (Visit)->Unit) {visitsRead++;repeat(count) {consume(Visit(id="",coordinate=Coordinate(18.0,-66.0),arrivedAt=at.plusSeconds(it.toLong()*60)))}}
            override suspend fun points(consume:suspend (TrackPoint)->Unit) {pointsRead++;repeat(count) {consume(TrackPoint(id="",coordinate=Coordinate(18.0,-66.0),time=at.plusSeconds(it.toLong()),accuracy=5.0))}}
        }
        var rows=0L;var characters=0L;var linePosition=0;var pipe=false;val prefix=StringBuilder()
        val sink=object:Writer() {
            override fun write(chars:CharArray,offset:Int,length:Int) {characters+=length
                for(index in offset until offset+length) {val c=chars[index];if(prefix.length<1000) prefix.append(c)
                    if(linePosition==0) pipe=c=='|'
                    if(linePosition==2 && pipe && c.isDigit()) rows++
                    linePosition=if(c=='\n') 0 else linePosition+1
                }
            }
            override fun flush() {};override fun close() {}
        }
        val directory=Files.createTempDirectory("markdown-budget").toFile();val previous=Locale.getDefault()
        try {
            Locale.setDefault(Locale.US)
            val counts=StreamingHistoryExporter.write(sink,ExportFormat.markdown,source,ExportOptions(zone=ZoneOffset.UTC),at,directory)
            assertEquals(ExportCounts(count.toLong(),count.toLong()),counts);assertEquals(count.toLong()*2,rows)
            assertEquals(1,visitsRead);assertEquals(2,pointsRead);assertTrue(directory.listFiles()!!.isEmpty())
            assertTrue(prefix.toString().contains("**Total Visits:** 1000000"));assertTrue(characters>100_000_000L)
            println("Markdown: $rows data rows, $characters chars, heap limit ${Runtime.getRuntime().maxMemory()}; one visit/two point reads, scratch removed")
        } finally {Locale.setDefault(previous);directory.deleteRecursively()}
    }
}
