package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.time.*

class BulkOutingExportTest {
    private val start=Instant.parse("2020-02-29T12:00:00Z")
    private val options=ExportOptions(zone=ZoneOffset.UTC)
    private fun outing(id:String,time:Instant=start,title:String?=null)=OutingExportSummaryBuilder().build(RecordingSession(id,time,time.plusSeconds(60),title,"line | one\nnext"),1,false,start,0)
    private val empty=object:ExportRecordSource {
        override suspend fun visits(consume:suspend (Visit)->Unit) {}
        override suspend fun points(consume:suspend (TrackPoint)->Unit) {}
    }
    @Test fun aggregateSummaryIsChronologicalAndEmptyListsRetainValidContracts()=runBlocking {
        val later=outing("later",start.plusSeconds(120),"Later | name")
        val early=outing("early")
        val writer=StringWriter();StreamingOutingExporter.write(writer,listOf(later,early),empty,ExportFormat.markdown,options,start)
        val text=writer.toString();assertTrue(text.startsWith("# Whereday Outings Export"));assertTrue(text.contains("**Total Outings:** 2"))
        assertTrue(text.indexOf("Outing 1")<text.indexOf("Later \\| name"));assertTrue(text.contains("line \\| one<br>next"));assertFalse(text.contains("## Route Points"))
        for(format in listOf(ExportFormat.json,ExportFormat.csv,ExportFormat.markdown)) {
            val output=StringWriter();StreamingOutingExporter.write(output,emptyList(),empty,format,options,start)
            when(format) {
                ExportFormat.json -> assertEquals(0,JsonParser.parseString(output.toString()).asJsonObject["totalOutings"].asInt)
                ExportFormat.csv -> assertEquals(1,output.toString().lineSequence().count {it.isNotEmpty()})
                else -> assertTrue(output.toString().contains("**Total Outings:** 0"))
            }
        }
    }
    @Test fun splitNamesUseStartDateTitleSuffixAndIosCollisionFallback() {
        val used=mutableSetOf<String>();val o=outing("first",title="Café walk")
        assertEquals("2020/Outing - Café walk.json",OutingFilenamePlanner.path("{year}/Outing",ExportFormat.json,o,options,used))
        assertEquals("2020/2020-02-29_Outing - Café walk.json",OutingFilenamePlanner.path("{year}/Outing",ExportFormat.json,o,options,used))
        assertEquals("2020/2020-02-29_Outing - Café walk_2.json",OutingFilenamePlanner.path("{year}/Outing",ExportFormat.json,o,options,used))
        assertEquals("Café walk.csv",OutingFilenamePlanner.path("{title}",ExportFormat.csv,o,options,used))
        assertEquals("NAME - Café walk.json",OutingFilenamePlanner.path("NAME.JSON",ExportFormat.json,o,options,used))
    }
    @Test fun splitNamesBoundUnicodeComponentsAndRejectTraversal() {
        val o=outing("long",title="界".repeat(1000)+"/../../private")
        val path=OutingFilenamePlanner.path("{year}/"+"base".repeat(40),ExportFormat.gpx,o,options,mutableSetOf())
        assertEquals(2,path.split('/').size);assertTrue(path.substringAfter('/').toByteArray().size<255)
        try {OutingFilenamePlanner.path("../{title}",ExportFormat.csv,o,options,mutableSetOf());fail()} catch(_:IllegalArgumentException) {}
    }
}
