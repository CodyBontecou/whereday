package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import kotlinx.coroutines.runBlocking
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.time.Instant

class KmlStreamingBudgetTest {
    @Test fun oneMillionFixesUseOneSourceReadAndBoundedHeapWithPrivateDiskReplay()=runBlocking {
        assertTrue("Run streamingKmlBudgetTest with its 96 MiB heap",Runtime.getRuntime().maxMemory()<=96L*1024*1024)
        val scratch=Files.createTempDirectory("kml-budget-").toFile();val start=Instant.parse("2026-10-28T00:00:00Z")
        var reads=0;var emitted=0L;var maxScratchBytes=0L;var maxFiles=0
        try {
            val source=object:ExportRecordSource {
                override suspend fun visits(consume:suspend (Visit)->Unit) {error("points-only KML must not read visits")}
                override suspend fun points(consume:suspend (TrackPoint)->Unit) {
                    reads++
                    repeat(1_000_000) {i->
                        consume(TrackPoint(id=i.toString(),coordinate=Coordinate(18.123456789,-66.123456789),time=start.plusSeconds(i.toLong()),accuracy=5.0,altitude=2.675));emitted++
                    }
                }
            }
            val sink=KmlCountingWriter()
            val counts=StreamingHistoryExporter.write(sink,ExportFormat.kml,source,ExportOptions(kind=DataKind.points),start,scratch) {
                val files=scratch.listFiles().orEmpty();maxFiles=maxOf(maxFiles,files.size);maxScratchBytes=maxOf(maxScratchBytes,files.sumOf {it.length()})
            }
            assertEquals(1,reads);assertEquals(1_000_000L,emitted);assertEquals(1_000_000L,counts.points);assertEquals(0L,counts.visits)
            assertEquals(1L,sink.sessions);assertEquals(1_000_000L,sink.whens);assertEquals(1_000_000L,sink.coordinates)
            val sample=requireNotNull(javaClass.getResourceAsStream("/ios-kml.json")).bufferedReader().use {reader->
                JsonParser.parseReader(reader).asJsonObject["cases"].asJsonArray.first {it.asJsonObject["name"].asString=="budget-two-fixes"}.asJsonObject["text"].asString
            }
            // Same fixed-width times and coordinates as the actual Swift two-fix fixture.
            val perFix=listOf("            -66.","          <when>","          <gx:coord>").sumOf {prefix->
                sample.lineSequence().first {it.startsWith(prefix)}.length+1
            }
            val expectedCharacters=sample.length+(1_000_000L-2)*perFix+("1000000".length-"2".length)
            assertEquals(expectedCharacters,sink.characters);assertTrue(sink.maxWrite<1024)
            assertEquals(1,maxFiles);assertTrue(maxScratchBytes in 30_000_000L..37_000_000L)
            assertTrue(scratch.listFiles().orEmpty().isEmpty())
        } finally {scratch.deleteRecursively()}
    }
}
