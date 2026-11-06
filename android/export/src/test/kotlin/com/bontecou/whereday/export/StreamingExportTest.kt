package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.io.Writer
import java.time.Instant

class StreamingExportTest {
    private val time=Instant.parse("2026-10-27T00:00:00Z")
    private val visits=listOf(Visit(coordinate=Coordinate(18.0,-66.0),arrivedAt=time,departedAt=time.plusSeconds(90),name="Cafe, \"North\" <&>",notes="A\nB | C",source=VisitSource.manual),
        Visit(coordinate=Coordinate(19.0,-67.0),arrivedAt=time.plusSeconds(4)))
    private val points=listOf(TrackPoint(coordinate=Coordinate(18.0,-66.0),time=time,accuracy=5.0,speed=1.5,altitude=2.0),
        TrackPoint(coordinate=Coordinate(18.001,-66.0),time=time.plusSeconds(900),accuracy=8.0,outlier=true))
    private fun source()=object:ExportRecordSource {
        override suspend fun visits(consume:suspend (Visit)->Unit) { visits.forEach { consume(it) } }
        override suspend fun points(consume:suspend (TrackPoint)->Unit) { points.forEach { consume(it) } }
    }
    @Test fun allFormatsMatchExistingContractAcrossKindsFiltersAndFieldChoices()=runBlocking {
        val choices=listOf(ExportOptions(),ExportOptions(kind=DataKind.visits),ExportOptions(kind=DataKind.points),
            ExportOptions(completedOnly=true,minimumVisitMinutes=1,excludeOutliers=true,maximumAccuracy=6.0),
            ExportOptions(visitCoordinates=false,visitName=false,visitAddress=false,visitNotes=false,visitDuration=false,pointAltitude=false,pointSpeed=false,pointAccuracy=false,pointOutlier=false),
            ExportOptions(from=time.plusSeconds(1),until=time.plusSeconds(899)))
        for(format in ExportFormat.entries) for(options in choices) {
            val legacy=HistoryExporter.render(format,visits,points,options,time)
            val output=StringWriter();StreamingHistoryExporter.write(output,format,source(),options,time)
            if(format in listOf(ExportFormat.json,ExportFormat.geojson,ExportFormat.owntracks,ExportFormat.overland)) assertEquals("$format/$options",JsonParser.parseString(legacy),JsonParser.parseString(output.toString()))
            else assertEquals("$format/$options",legacy,output.toString())
        }
    }
    @Test fun millionPointsStreamToBoundedSinkWithoutBuildingOutputOrRecordLists()=runBlocking {
        var sourceCount=0L;var characters=0L
        val source=object:ExportRecordSource {
            override suspend fun visits(consume:suspend (Visit)->Unit) { error("points-only export must not read visits") }
            override suspend fun points(consume:suspend (TrackPoint)->Unit) {
                repeat(1_000_000) { i -> sourceCount++;consume(TrackPoint(id=i.toString(),coordinate=Coordinate(18.0,-66.0),time=time.plusMillis(i.toLong()),accuracy=5.0)) }
            }
        }
        val sink=object:Writer() { override fun write(buffer:CharArray,offset:Int,length:Int) {characters+=length};override fun flush(){};override fun close(){} }
        val result=StreamingHistoryExporter.write(sink,ExportFormat.json,source,ExportOptions(kind=DataKind.points),time)
        assertEquals(1_000_000,sourceCount);assertEquals(1_000_000,result.points);assertTrue(characters>100_000_000)
    }
    @Test fun cancellationStopsSourceAndDoesNotReportSuccess()=runBlocking {
        var consumed=0
        val source=object:ExportRecordSource {
            override suspend fun visits(consume:suspend (Visit)->Unit) {}
            override suspend fun points(consume:suspend (TrackPoint)->Unit) { repeat(100_000) {consume(points.first());consumed++;if(consumed==501) currentCoroutineContext().cancel()} }
        }
        val task=async { StreamingHistoryExporter.write(StringWriter(),ExportFormat.gpx,source) }
        try {task.await();fail("Canceled export must not succeed")} catch(_:CancellationException) {}
        assertTrue(consumed in 501..502)
    }
}
