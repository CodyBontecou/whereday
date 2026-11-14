package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.time.*

class OutingExportTest {
    private val start=Instant.parse("2020-02-29T12:00:00Z")
    private fun point(index:Int,outlier:Boolean=false)=TrackPoint("p$index",Coordinate(0.0,index/1000.0),start.plusSeconds(index.toLong()),5.0,outlier=outlier)
    private val points=listOf(point(0),point(1,true),point(2))
    private val visit=Visit(coordinate=Coordinate(0.0,0.0),arrivedAt=start,name="A | B",notes="line one\nline two")
    private val source=object:ExportRecordSource {
        override suspend fun points(consume:suspend (TrackPoint)->Unit) {points.forEach {consume(it)}}
        override suspend fun visits(consume:suspend (Visit)->Unit) {consume(visit)}
    }
    private fun summary(session:RecordingSession=RecordingSession("outing",start,start.plusSeconds(60),notes="  notes  "))=
        OutingExportSummaryBuilder().apply {points.forEach(::add)}.build(session,3,false,start.plusSeconds(3600),1)
    private suspend fun render(format:ExportFormat,o:ExportOptions=ExportOptions()):Pair<String,ExportCounts> {
        val w=StringWriter();val counts=StreamingOutingExporter.write(w,summary(),source,format,o,start)
        return w.toString() to counts
    }
    @Test fun completeSummaryUsesSessionDurationAndLastFixForAnOpenOuting() {
        val s=summary();assertEquals(60.0,s.durationSeconds,0.0);assertEquals(s.distanceMeters/60,s.averageSpeedMetersPerSecond!!,0.0)
        assertEquals(3,s.sequenceNumber);assertEquals("Outing 3",s.title);assertEquals("notes",s.notes);assertEquals(1,s.outlierCount)
        val open=summary(RecordingSession("active",start));assertEquals(start.plusSeconds(2),open.effectiveEndAt);assertTrue(open.isActive)
        val empty=OutingExportSummaryBuilder().build(RecordingSession("empty",start),1,false,start.plusSeconds(30),0)
        assertEquals(30.0,empty.durationSeconds,0.0);assertNull(empty.averageSpeedMetersPerSecond);assertNull(empty.start)
    }
    @Test fun allOutlierRouteFallsBackToItsRawDistance() {
        val b=OutingExportSummaryBuilder();b.add(point(0,true));b.add(point(2,true))
        val s=b.build(RecordingSession("x",start,start.plusSeconds(10)),1,true,start,0)
        assertEquals(point(0).coordinate.distanceTo(point(2).coordinate),s.distanceMeters,0.0)
        assertEquals(2,s.outlierCount);assertTrue(s.isInferred);assertFalse(s.isActive)
    }
    @Test fun jsonUsesOutingRootContractAndOmitsNullOptionalFields()=runBlocking {
        val (text,counts)=render(ExportFormat.json);val root=JsonParser.parseString(text).asJsonObject
        assertEquals(1,root["totalOutings"].asInt);assertFalse(root.has("points"));assertFalse(root.has("visits"))
        val row=root["outings"].asJsonArray.single().asJsonObject
        assertEquals("2020-02-29T12:00:00.000Z",row["startedAt"].asString);assertEquals(3,row["pointCount"].asInt);assertEquals(1,row["visitCount"].asInt)
        assertEquals(ExportCounts(1,3),counts)
        val empty=OutingExportSummaryBuilder().build(RecordingSession("empty",start),1,false,start,0)
        val w=StringWriter();StreamingOutingExporter.write(w,empty,source,ExportFormat.json,generatedAt=start)
        val nulls=JsonParser.parseString(w.toString()).asJsonObject["outings"].asJsonArray.single().asJsonObject
        assertFalse(nulls.has("endedAt"));assertFalse(nulls.has("notes"));assertFalse(nulls.has("startLatitude"))
    }
    @Test fun outingCsvHasNineteenColumnsAndEscapesQuotesAndMultilineNotes()=runBlocking {
        val s=summary(RecordingSession("comma,id",start,start.plusSeconds(60),"Title \"quoted\"","hello,world\nnext"))
        val w=StringWriter();StreamingOutingExporter.write(w,s,source,ExportFormat.csv,generatedAt=start)
        assertEquals(19,w.toString().lineSequence().first().split(',').size)
        assertTrue(w.toString().contains("\"comma,id\",\"Title \"\"quoted\"\"\",3,"))
        assertTrue(w.toString().contains("\"hello,world\nnext\""));assertFalse(w.toString().contains('\r'))
        // Swift's current Character-based newline check misses a CRLF grapheme. Keep valid CSV here.
        val crlf=StringWriter();StreamingOutingExporter.write(crlf,s.copy(notes="first\r\nsecond"),source,ExportFormat.csv,generatedAt=start)
        assertTrue(crlf.toString().contains("\"first\r\nsecond\""))
    }
    @Test fun markdownContainsSummaryYamlLinkedVisitsAndCompleteRouteWithFieldChoices()=runBlocking {
        val (text,_)=render(ExportFormat.markdown,ExportOptions(pointSpeed=false,pointAltitude=false,pointAccuracy=false,pointOutlier=false,visitCoordinates=false))
        assertTrue(text.startsWith("---\ntype: \"outing\"\napp: \"Whereday\"\n"))
        assertTrue(text.contains("point_count: 3\noutlier_count: 1\nvisit_count: 1"));assertTrue(text.contains("  - \"A | B\""))
        assertTrue(text.contains("## Notes\n\nnotes"));assertTrue(text.contains("## Visits"));assertTrue(text.contains("A \\| B"));assertTrue(text.contains("line one<br>line two"))
        assertTrue(text.contains("## Route Points\n\n| Time | Lat | Lon |"));assertFalse(text.contains("| Speed |"))
        assertEquals(3,Regex("\\| 0\\.000000 \\| 0\\.00[012]000 \\|").findAll(text).count())
    }
    @Test fun defaultTitlesLocalizeNumbersWhileExplicitTitlesRemainLiteral()=runBlocking {
        val locale=java.util.Locale.forLanguageTag("ar-EG")
        val automatic=OutingExportSummaryBuilder().build(RecordingSession("auto",start,start.plusSeconds(60)),1000,false,start,0)
        val custom=OutingExportSummaryBuilder().build(RecordingSession("custom",start,start.plusSeconds(60),name="Outing 1000"),1000,false,start,0)
        for((value,expected) in listOf(automatic to "Outing ١٬٠٠٠",custom to "Outing 1000")) {
            val writer=StringWriter();StreamingOutingExporter.write(writer,value,source,ExportFormat.json,locale=locale)
            assertEquals(expected,JsonParser.parseString(writer.toString()).asJsonObject["outings"].asJsonArray[0].asJsonObject["title"].asString)
        }
    }
    @Test fun allFiveRouteFormatsContainEveryPointAndNoVisitRegardlessOfGlobalFilters()=runBlocking {
        val global=ExportOptions(kind=DataKind.visits,from=start.plusSeconds(300),until=start.plusSeconds(400),excludeOutliers=true,maximumAccuracy=1.0)
        for(format in listOf(ExportFormat.gpx,ExportFormat.kml,ExportFormat.geojson,ExportFormat.owntracks,ExportFormat.overland)) {
            val (text,counts)=render(format,global)
            assertEquals(format.name,ExportCounts(0,3),counts)
            when(format) {
                ExportFormat.gpx -> {assertEquals(3,Regex("<trkpt ").findAll(text).count());assertFalse(text.contains("<wpt"))}
                ExportFormat.kml -> assertEquals(3,Regex("<gx:coord>").findAll(text).count())
                ExportFormat.geojson -> assertEquals(3,JsonParser.parseString(text).asJsonObject["features"].asJsonArray.size())
                ExportFormat.owntracks -> assertEquals(3,JsonParser.parseString(text).asJsonArray.size())
                ExportFormat.overland -> assertEquals(3,JsonParser.parseString(text).asJsonObject["locations"].asJsonArray.size())
                else -> error("Unexpected format")
            }
        }
    }
}
