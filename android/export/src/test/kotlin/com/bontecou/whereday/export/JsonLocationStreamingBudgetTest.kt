package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.Writer
import java.time.Instant

class JsonLocationStreamingBudgetTest {
    @Test fun millionRecordsPerProtocolUseOneSourceReadAndRetainOnlyTheFinalOverlandFeature()=runBlocking {
        assertTrue("Run streamingJsonLocationBudgetTest with its 96 MiB heap",Runtime.getRuntime().maxMemory()<=96L*1024*1024)
        val start=Instant.parse("2026-10-28T00:00:00Z")
        for(format in listOf(ExportFormat.geojson,ExportFormat.owntracks,ExportFormat.overland)) {
            var reads=0;var emitted=0L
            val source=object:ExportRecordSource {
                override suspend fun visits(consume:suspend (Visit)->Unit) {error("points-only export must not read visits")}
                override suspend fun points(consume:suspend (TrackPoint)->Unit) {
                    reads++
                    repeat(1_000_000) {i->consume(TrackPoint(id=i.toString(),coordinate=Coordinate(18.123456789,-66.123456789),time=start.plusMillis(i*1001L),accuracy=2.5,speed=1.5,altitude=-1.5));emitted++}
                }
            }
            val sink=TokenSink()
            val counts=StreamingHistoryExporter.write(sink,format,source,ExportOptions(kind=DataKind.points),start)
            assertEquals(format.name,1,reads);assertEquals(1_000_000L,emitted);assertEquals(1_000_000L,counts.points);assertEquals(0L,counts.visits)
            when(format) {
                ExportFormat.geojson->{assertEquals(1_000_000L,sink.counts["\"kind\""]);assertEquals(1_000_000L,sink.counts["\"timestamp\""])}
                ExportFormat.owntracks->assertEquals(1_000_000L,sink.counts["\"_type\""])
                ExportFormat.overland->{assertEquals(1L,sink.counts["\"current\""]);assertEquals(1_000_001L,sink.counts["\"timestamp\""])}
                else->error("Unexpected format")
            }
            assertTrue(sink.characters>100_000_000L);assertTrue(sink.maxWrite<1024)
            assertEquals(0,sink.depth);assertFalse(sink.inString)
        }
    }
    /** Constant-state scanner for fixed JSON member names, including names split across writes. */
    private class TokenSink:Writer() {
        val counts=linkedMapOf("\"kind\"" to 0L,"\"timestamp\"" to 0L,"\"_type\"" to 0L,"\"current\"" to 0L)
        private val tokens=counts.keys.toTypedArray()
        private val partial=IntArray(tokens.size)
        var characters=0L;var maxWrite=0;var depth=0;var inString=false;private var escaped=false
        override fun write(buffer:CharArray,offset:Int,length:Int) {
            characters+=length;maxWrite=maxOf(maxWrite,length)
            for(i in offset until offset+length) {
                val c=buffer[i]
                for(index in tokens.indices) {
                    val token=tokens[index]
                    if(c==token[partial[index]]) partial[index]++ else partial[index]=if(c==token[0]) 1 else 0
                    if(partial[index]==token.length) {counts[token]=counts.getValue(token)+1;partial[index]=0}
                }
                if(inString) {if(escaped) escaped=false else if(c=='\\') escaped=true else if(c=='"') inString=false}
                else when(c) {'"'->inString=true;'[','{'->depth++;']','}'->{depth--;check(depth>=0)}}
            }
        }
        override fun flush() {}
        override fun close() {}
    }
}
