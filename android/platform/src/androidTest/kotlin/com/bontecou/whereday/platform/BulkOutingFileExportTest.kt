package com.bontecou.whereday.platform

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.*
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.*
import java.util.UUID

class BulkOutingFileExportTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val start=Instant.parse("2020-01-01T10:00:00Z")
    private val options=ExportOptions(from=start,until=start.plusSeconds(3600),zone=ZoneOffset.UTC,completedOnly=true,
        excludeOutliers=true,maximumAccuracy=1.0,kind=DataKind.points)
    private suspend fun fixture(block:suspend (HistoryDatabase,ExportFiles)->Unit) {
        check(context.packageName!="com.bontecou.whereday")
        val name="bulk-outing-${UUID.randomUUID()}.db";val db=HistoryDatabase.create(context,name);val files=ExportFiles(context,db) {}
        fun p(id:String,seconds:Long,outlier:Boolean=false)=PointRow(TrackPoint(id,Coordinate(0.0,0.0),start.plusSeconds(seconds),5.0,outlier=outlier),null)
        try {
            db.history().put(SessionRow(RecordingSession("old",start.minusSeconds(86400),start.minusSeconds(82800))))
            db.history().put(SessionRow(RecordingSession("a",start,start.plusSeconds(3600),"Same")))
            db.history().put(SessionRow(RecordingSession("b",start.plusSeconds(1800),start.plusSeconds(5400),"Same")))
            db.history().putPoints((0..599).map {p("equal-%04d".format(it),2700,outlier=it%2==0)})
            db.history().putPoints(listOf(p("start",0),p("end-a",3600),p("end-b",5400),p("before-1",-600),p("before-2",-540),p("after-1",5401),p("after-2",5460),p("old-point",-86000)))
            db.history().put(VisitRow(Visit(id="completed",coordinate=Coordinate(0.0,0.0),arrivedAt=start.plusSeconds(900),departedAt=start.plusSeconds(1200))))
            db.history().put(VisitRow(Visit(id="open",coordinate=Coordinate(0.0,0.0),arrivedAt=start.plusSeconds(2700))))
            db.history().put(VisitRow(Visit(id="late",coordinate=Coordinate(0.0,0.0),arrivedAt=start.plusSeconds(3900),departedAt=start.plusSeconds(4200))))
            block(db,files)
        } finally {files.clearCache();db.close();context.deleteDatabase(name)}
    }
    private fun request(formats:Set<ExportFormat> =setOf(ExportFormat.json),split:Boolean=false,o:ExportOptions=options,inference:InferenceRules?=InferenceRules())=
        ExportFileRequest(formats,o,"{year}/{title}",split,generatedAt=start.plusSeconds(7200),outings=true,inference=inference)
    private fun rows(prepared:PreparedExport)=JsonParser.parseString(prepared.files.single().file.readText()).asJsonObject["outings"].asJsonArray

    @Test fun startFiltersKeepCompleteRoutesAndAggregateRouteDeduplicatesOverlappingSessions()=runBlocking {
        fixture {_,files ->
            val summaries=rows(files.prepare(request()))
            assertEquals(2,summaries.size());assertEquals(listOf("a","b"),summaries.map {it.asJsonObject["id"].asString})
            assertEquals(listOf(3,4),summaries.map {it.asJsonObject["sequenceNumber"].asInt})
            assertEquals(listOf(602,602),summaries.map {it.asJsonObject["pointCount"].asInt})
            assertEquals(listOf(1,0),summaries.map {it.asJsonObject["visitCount"].asInt})
            for(format in listOf(ExportFormat.gpx,ExportFormat.kml,ExportFormat.geojson,ExportFormat.owntracks,ExportFormat.overland)) {
                val file=files.prepare(request(setOf(format))).files.single()
                assertEquals(ExportCounts(0,603),file.counts)
                if(format==ExportFormat.gpx) assertEquals(603,Regex("<trkpt ").findAll(file.file.readText()).count())
            }
            val time=options.copy(startTime=LocalTime.of(10,0),endTime=LocalTime.of(10,20))
            val single=rows(files.prepare(request(o=time))).single().asJsonObject
            assertEquals("a",single["id"].asString);assertEquals(602,single["pointCount"].asInt)
        }
    }
    @Test fun splitsAllFormatsKeepFullLinkedVisitsAndUniqueNamesAcrossOutingsAndJsonExtensions()=runBlocking {
        fixture {_,files ->
            val prepared=files.prepare(request(ExportFormat.entries.toSet(),split=true))
            assertEquals(16,prepared.files.size);assertEquals(16,prepared.files.map {it.relativePath}.toSet().size)
            val json=prepared.files.filter {it.format==ExportFormat.json}
            assertEquals(listOf("2020/Same.json","2020/2020-01-01_Same.json"),json.map {it.relativePath})
            json.forEach {file ->val row=JsonParser.parseString(file.file.readText()).asJsonObject["outings"].asJsonArray.single().asJsonObject
                assertEquals(2,row["visitCount"].asInt);assertEquals(602,row["pointCount"].asInt)
            }
            val owntracks=prepared.files.filter {it.format==ExportFormat.owntracks}
            assertEquals("2020/Same-owntracks.json",owntracks.first().relativePath)
            assertEquals(602,JsonParser.parseString(owntracks.first().file.readText()).asJsonArray.size())
            try {files.beginPicker(prepared);fail("Bundles must use share/folder delivery")} catch(_:IllegalStateException) {}
            assertEquals(android.content.Intent.ACTION_SEND_MULTIPLE,files.share(prepared).action)
        }
    }
    @Test fun inferencePreferencesEmptyResultsAndFreeBoundedPreviewUseTheSameSelection()=runBlocking {
        fixture {db,files ->
            val all=options.copy(from=start.minusSeconds(1000),until=start.plusSeconds(7200))
            assertEquals(4,rows(files.prepare(request(o=all))).size())
            assertEquals(2,rows(files.prepare(request(o=all,inference=null))).size())
            val empty=options.copy(from=start.plusSeconds(8000),until=start.plusSeconds(9000))
            assertEquals(0,rows(files.prepare(request(o=empty))).size())
            assertTrue(files.prepare(request(split=true,o=empty)).files.isEmpty())
            val locked=ExportFiles(context,db) {error("No paid access")}
            val preview=locked.preview(ExportFormat.json,options,outings=true)
            assertEquals(2,JsonParser.parseString(preview).asJsonObject["totalOutings"].asInt)
            try {locked.prepare(request());fail()} catch(_:IllegalStateException) {}
        }
    }
    @Test fun failedFinalAuthorizationDiscardsEveryPreparedSplitAndFormat()=runBlocking {
        fixture {db,_ ->
            var calls=0;val locked=ExportFiles(context,db) {if(++calls==2) error("Access expired")}
            try {locked.prepare(request(setOf(ExportFormat.json,ExportFormat.markdown),true));fail()} catch(_:IllegalStateException) {}
            assertEquals(2,calls);assertTrue(File(context.cacheDir,"exports").listFiles().orEmpty().none {it.isDirectory})
        }
    }
    @Test fun previewCapsOutingCountWithoutCappingTheCompleteFileAndCancellationRemovesPartialBundles()=runBlocking {
        fixture {db,files ->
            repeat(155) {i ->val time=start.plusSeconds(60L*(i+1));db.history().put(SessionRow(RecordingSession("empty-$i",time,time.plusMillis(1))))}
            val range=options.copy(from=start,until=start.plusSeconds(10000))
            val locked=ExportFiles(context,db) {error("No paid access")}
            val preview=JsonParser.parseString(locked.preview(ExportFormat.json,range,outings=true,inference=null)).asJsonObject
            assertEquals(100,preview["totalOutings"].asInt)
            assertEquals(157,rows(files.prepare(request(o=range,inference=null))).size())
            files.clearCache()
            val job=launch {files.prepare(request(setOf(ExportFormat.json,ExportFormat.csv),split=true,o=range,inference=null)) {cancel()}}
            job.join();assertTrue(job.isCancelled)
            assertTrue(File(context.cacheDir,"exports").listFiles().orEmpty().none {it.isDirectory})
        }
    }
}
