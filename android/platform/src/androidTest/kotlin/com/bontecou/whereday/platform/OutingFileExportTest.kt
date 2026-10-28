package com.bontecou.whereday.platform

import android.content.Intent
import android.provider.DocumentsContract
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

class OutingFileExportTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val start=Instant.parse("2020-02-29T12:00:00Z")
    private suspend fun fixture(block:suspend (HistoryDatabase,ExportFiles)->Unit) {
        check(context.packageName!="com.bontecou.whereday")
        val name="outing-export-${UUID.randomUUID()}.db";val db=HistoryDatabase.create(context,name);val files=ExportFiles(context,db) {}
        try {
            db.history().put(SessionRow(RecordingSession("recorded",start,start.plusSeconds(60),"Walk / café","  A note  ")))
            db.history().putPoints((0..3000).map {i ->PointRow(TrackPoint("p%04d".format(i),Coordinate(0.0,i/1000000.0),start.plusSeconds(20),5.0),if(i%2==0) "recorded" else null)})
            db.history().put(PointRow(TrackPoint("end",Coordinate(0.0,.004),start.plusSeconds(60),5.0),null))
            db.history().put(PointRow(TrackPoint("after",Coordinate(0.0,.005),start.plusMillis(60001),5.0),null))
            db.history().put(VisitRow(Visit(id="overlap",coordinate=Coordinate(0.0,0.0),arrivedAt=start.minusSeconds(10),departedAt=start.plusSeconds(10))))
            db.history().put(VisitRow(Visit(id="inside",coordinate=Coordinate(0.0,0.0),arrivedAt=start.plusSeconds(30),departedAt=start.plusSeconds(120))))
            db.history().put(VisitRow(Visit(id="end",coordinate=Coordinate(0.0,0.0),arrivedAt=start.plusSeconds(60))))
            block(db,files)
        } finally {files.clearCache();db.close();context.deleteDatabase(name);File(context.cacheDir,"synthetic-export-documents").deleteRecursively()}
    }
    @Test fun exactExportUsesCompleteClosedWindowIncludingUnassignedImportedPointsAndArrivalLinkedVisits()=runBlocking {
        fixture {_,files ->
            val json=files.prepareOuting("recorded",ExportFormat.json,sequence=4,generatedAt=start.plusSeconds(100)).files.single()
            val root=JsonParser.parseString(json.file.readText()).asJsonObject
            val row=root["outings"].asJsonArray.single().asJsonObject
            assertEquals(3002,row["pointCount"].asInt);assertEquals(2,row["visitCount"].asInt);assertEquals(4,row["sequenceNumber"].asInt)
            assertEquals("Walk / café",row["title"].asString);assertEquals("A note",row["notes"].asString)
            val gpx=files.prepareOuting("recorded",ExportFormat.gpx,ExportOptions(kind=DataKind.visits,from=start.plusSeconds(100)),filenamePattern="{year}/{title}").files.single()
            assertEquals("2020/Walk - café.gpx",gpx.relativePath)
            val text=gpx.file.readText();assertEquals(3002,Regex("<trkpt ").findAll(text).count());assertFalse(text.contains("<wpt"));assertFalse(text.contains("12:01:00.001"))
            val markdown=files.prepareOuting("recorded",ExportFormat.markdown).files.single().file.readText()
            assertTrue(markdown.contains("point_count: 3002"));assertTrue(markdown.contains("visit_count: 2"))
            assertEquals(3002,Regex("\\| 0\\.000000 \\|").findAll(markdown.substringAfter("## Route Points")).count())
        }
    }
    @Test fun inferredExportExcludesEveryExactSessionWindowIncludingClosedEndpoints()=runBlocking {
        fixture {db,files ->
            val last=start.plusSeconds(100)
            db.history().put(PointRow(TrackPoint("unassigned-later",Coordinate(0.0,.006),last,5.0),null))
            val header=InferredOutingHeader("inferred:legacy",start,last,PointSummary())
            val json=files.prepareOuting(header.id,ExportFormat.json,inferred=header).files.single().file.readText()
            val row=JsonParser.parseString(json).asJsonObject["outings"].asJsonArray.single().asJsonObject
            assertEquals(2,row["pointCount"].asInt);assertTrue(row["isInferred"].asBoolean);assertFalse(row["isActive"].asBoolean)
            val route=files.prepareOuting(header.id,ExportFormat.geojson,inferred=header).files.single().file.readText()
            assertEquals(2,JsonParser.parseString(route).asJsonObject["features"].asJsonArray.size())
            db.history().put(SessionRow(RecordingSession("covers-all",start,last)))
            try {files.prepareOuting(header.id,ExportFormat.json,inferred=header);fail("Stale inferred outing must fail")} catch(_:IllegalArgumentException) {}
        }
    }
    @Test fun freeOutingRoutePreviewStopsAt2500WhileCompleteFileRetainsAllPoints()=runBlocking {
        fixture {db,files ->
            val locked=ExportFiles(context,db) {error("No paid access")}
            val preview=locked.preview(ExportFormat.gpx,ExportOptions(from=start,until=start.plusSeconds(100)),outings=true,inference=null)
            assertTrue(preview.length<=100_000)
            // The character limit can truncate GPX before its point limit; it never exceeds either.
            assertTrue(Regex("<trkpt ").findAll(preview).count() in 1..2500)
            val owntracks=locked.preview(ExportFormat.owntracks,ExportOptions(from=start,until=start.plusSeconds(100),pointAltitude=false,pointSpeed=false,pointAccuracy=false),outings=true,inference=null)
            assertTrue(Regex("\"_type\"").findAll(owntracks).count()<=2500)
            assertEquals(3002,files.prepareOuting("recorded",ExportFormat.gpx).files.single().counts.points)
        }
    }
    @Test fun activeEffectiveEndUsesLastFixAndExcludesVisitsArrivingAfterItAndEmptyUsesCapturedNow()=runBlocking {
        fixture {db,files ->
            db.history().put(SessionRow(RecordingSession("active",start)))
            db.history().put(VisitRow(Visit(id="too-late",coordinate=Coordinate(0.0,0.0),arrivedAt=start.plusSeconds(70))))
            fun row(prepared:PreparedExport)=JsonParser.parseString(prepared.files.single().file.readText()).asJsonObject["outings"].asJsonArray.single().asJsonObject
            val active=row(files.prepareOuting("active",ExportFormat.json,generatedAt=start.plusSeconds(100)))
            assertTrue(active["isActive"].asBoolean);assertFalse(active.has("endedAt"));assertEquals("2020-02-29T12:01:00.001Z",active["effectiveEndAt"].asString)
            assertEquals(2,active["visitCount"].asInt)
            db.history().put(SessionRow(RecordingSession("empty",start.plusSeconds(200))))
            val empty=row(files.prepareOuting("empty",ExportFormat.json,generatedAt=start.plusSeconds(230)))
            assertEquals(0,empty["pointCount"].asInt);assertEquals(30.0,empty["durationSeconds"].asDouble,0.0);assertFalse(empty.has("startLatitude"))
        }
    }
    @Test fun outingUsesVerifiedDeliveryAndDeniedFinalGateLeavesNoStagedPayload()=runBlocking {
        fixture {db,files ->
            val tree=DocumentsContract.buildTreeDocumentUri("com.bontecou.whereday.export-test-documents","root")
            val flags=Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION
            context.grantUriPermission(context.packageName,tree,flags)
            try {
                val prepared=files.prepareOuting("recorded",ExportFormat.csv)
                val expected=prepared.files.single().file.readText()
                assertEquals("text/csv",files.share(prepared).type)
                val parent=DocumentsContract.buildDocumentUriUsingTree(tree,"root")
                val target=DocumentsContract.createDocument(context.contentResolver,parent,"text/csv","outing.csv")!!
                val picker=files.beginPicker(prepared)
                assertEquals(ExportPickerPhase.completed,files.pickerResult(picker.id,target,0)!!.phase)
                assertEquals(expected,context.contentResolver.openInputStream(target)!!.bufferedReader().use {it.readText()})
                val existing=File(context.cacheDir,"exports").listFiles().orEmpty().map {it.name}.toSet()
                var gates=0;val denied=ExportFiles(context,db) {if(++gates==2) error("Access expired")}
                try {denied.prepareOuting("recorded",ExportFormat.markdown);fail()} catch(_:IllegalStateException) {}
                assertEquals(2,gates);assertEquals(existing,File(context.cacheDir,"exports").listFiles().orEmpty().map {it.name}.toSet())
                try {files.prepareOuting("deleted",ExportFormat.json);fail()} catch(_:IllegalStateException) {}
            } finally {context.revokeUriPermission(tree,flags)}
        }
    }
}
