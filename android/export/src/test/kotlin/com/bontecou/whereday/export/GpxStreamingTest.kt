package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.Writer
import java.time.Instant

class GpxStreamingTest {
    @Test fun millionGpxFixesUseOneForwardReadAndABoundedOutputSink()=runBlocking {
        assertTrue("Run through streamingGpxBudgetTest with its 96 MiB heap",Runtime.getRuntime().maxMemory()<=96L*1024*1024)
        var reads=0;var emitted=0L;var characters=0L;var maxWrite=0
        val start=Instant.parse("2026-10-28T00:00:00Z")
        val source=object:ExportRecordSource {
            override suspend fun visits(consume:suspend (Visit)->Unit) {error("A points-only GPX must not read visits")}
            override suspend fun points(consume:suspend (TrackPoint)->Unit) {
                reads++
                repeat(1_000_000) {i->
                    consume(TrackPoint(id=i.toString(),coordinate=Coordinate(18.123456789,-66.123456789),time=start.plusSeconds(i.toLong()),accuracy=5.6789))
                    emitted++
                }
            }
        }
        val sink=object:Writer() {
            override fun write(buffer:CharArray,offset:Int,length:Int) {characters+=length;maxWrite=maxOf(maxWrite,length)}
            override fun flush() {}
            override fun close() {}
        }
        val counts=StreamingHistoryExporter.write(sink,ExportFormat.gpx,source,ExportOptions(kind=DataKind.points),start)
        assertEquals(1,reads);assertEquals(1_000_000L,emitted);assertEquals(1_000_000L,counts.points)
        assertEquals(0L,counts.visits);assertTrue(characters>200_000_000L);assertTrue(maxWrite<1024)
    }
}
