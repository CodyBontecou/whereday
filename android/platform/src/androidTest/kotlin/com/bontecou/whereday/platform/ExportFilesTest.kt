package com.bontecou.whereday.platform

import android.content.Intent
import android.net.Uri
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
import java.time.Instant
import java.time.ZoneId
import java.util.UUID

class ExportFilesTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val authority="com.bontecou.whereday.export-test-documents"
    private val tree=DocumentsContract.buildTreeDocumentUri(authority,"root")
    private val root=DocumentsContract.buildDocumentUriUsingTree(tree,"root")
    private val time=Instant.parse("2026-03-08T05:00:00Z")
    private suspend fun fixture(onFileExported:()->Unit={},block:suspend (HistoryDatabase,ExportFiles)->Unit) {
        check(context.packageName!="com.bontecou.whereday")
        val name="export-synthetic-${UUID.randomUUID()}.db";val db=HistoryDatabase.create(context,name)
        val files=ExportFiles(context,db,{},onFileExported)
        try {
            context.grantUriPermission(context.packageName,tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            db.history().putPoints((0..1001).map {i ->PointRow(TrackPoint(id="synthetic-%04d".format(i),coordinate=Coordinate(18.0,-66.0),time=time,accuracy=5.0),null)})
            block(db,files)
        } finally {context.revokeUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION);files.clearCache();db.close();context.deleteDatabase(name);File(context.cacheDir,"synthetic-export-documents").deleteRecursively()}
    }
    @Test fun jsonLocationProtocolsIncludePre1970RoomRecordsAndVerifyActualFolderBytes()=runBlocking {
        fixture {db,files ->
            check(context.packageName=="com.bontecou.whereday.platform.test")
            val before=Instant.parse("1969-12-31T23:59:59.125Z");val after=Instant.parse("1970-01-01T00:00:00.125Z")
            db.history().putPoints(listOf(
                PointRow(TrackPoint(id="protocol-before",coordinate=Coordinate(18.0,-66.0),time=before,accuracy=2.5,altitude=-1.5,speed=3.75,outlier=true),null),
                PointRow(TrackPoint(id="protocol-after",coordinate=Coordinate(18.1,-66.1),time=after,accuracy=0.49,altitude=2.675),null)))
            db.history().put(VisitRow(Visit(id="protocol-visit",coordinate=Coordinate(18.0,-66.0),arrivedAt=before,departedAt=after,source=VisitSource.imported,confirmation=Confirmation.corrected,originalCoordinate=Coordinate(18.2,-66.2),originalName="Original <&>")))
            val formats=setOf(ExportFormat.geojson,ExportFormat.owntracks,ExportFormat.overland)
            val prepared=files.prepare(ExportFileRequest(formats,ExportOptions(until=after)))
            val delivered=files.saveToFolder(prepared,tree);assertEquals(3,delivered.size)
            for((file,uri) in prepared.files.zip(delivered)) {
                val bytes=context.contentResolver.openInputStream(uri)!!.use {it.readBytes()};assertArrayEquals(file.file.readBytes(),bytes)
                val json=JsonParser.parseString(bytes.toString(Charsets.UTF_8));assertEquals(2L,file.counts.points)
                when(file.format) {
                    ExportFormat.geojson->{
                        val root=json.asJsonObject;assertEquals("Whereday",root["generator"].asString)
                        val features=root["features"].asJsonArray;assertEquals(3,features.size());assertEquals(1L,file.counts.visits)
                        val visit=features[0].asJsonObject;val properties=visit["properties"].asJsonObject
                        assertEquals("visit",properties["kind"].asString);assertEquals("imported",properties["source"].asString)
                        assertEquals(18.2,properties["originalLatitude"].asDouble,0.0);assertFalse(properties.has("latitude"))
                        val point=features[1].asJsonObject["properties"].asJsonObject
                        assertEquals("point",point["kind"].asString);assertEquals(-0.875,point["timestampUnix"].asDouble,0.0)
                    }
                    ExportFormat.owntracks->{
                        val points=json.asJsonArray;assertEquals(2,points.size());assertEquals(0L,file.counts.visits)
                        val first=points[0].asJsonObject;assertEquals(0L,first["tst"].asLong);assertEquals(3,first["acc"].asInt)
                        assertEquals(-2,first["alt"].asInt);assertEquals(14,first["vel"].asInt);assertFalse(points[1].asJsonObject.has("vel"))
                    }
                    ExportFormat.overland->{
                        val root=json.asJsonObject;val locations=root["locations"].asJsonArray;assertEquals(2,locations.size());assertEquals(0L,file.counts.visits)
                        assertEquals(locations[1],root["current"])
                        val first=locations[0].asJsonObject;assertEquals(2,first["geometry"].asJsonObject["coordinates"].asJsonArray.size())
                        val properties=first["properties"].asJsonObject;assertEquals("isome",properties["device_id"].asString)
                        assertEquals(-1.5,properties["altitude"].asDouble,0.0);assertEquals("1969-12-31T23:59:59.125Z",properties["timestamp"].asString)
                    }
                    else->fail("Unexpected protocol format")
                }
            }
        }
    }
    @Test fun roomKmlDeliveryPreservesSessionGeometryAndOptionalVisitDataWithoutLeavingScratch()=runBlocking {
        fixture {db,files ->
            check(context.packageName=="com.bontecou.whereday.platform.test")
            val start=time.plusSeconds(1)
            db.history().putPoints(listOf(0L,600000L,1200001L).mapIndexed {index,offset->
                PointRow(TrackPoint(id="kml-$index",coordinate=Coordinate(18.123456789,-66.123456789),time=start.plusMillis(offset),accuracy=5.0,altitude=2.675),null)
            })
            db.history().put(VisitRow(Visit(id="kml-visit",coordinate=Coordinate(18.0,-66.0),arrivedAt=start.plusMillis(123),departedAt=start.plusMillis(90987),name="Café <&>",address="Road & Sea",notes="First\nSecond",source=VisitSource.manual,confirmation=Confirmation.corrected,originalCoordinate=Coordinate(18.1,-66.1),originalName="Original <&>",originalAddress="Old road",detectedCoordinate=Coordinate(18.2,-66.2),detectedName="Detected",detectedAddress="Found road",confirmedAt=start.plusSeconds(1),updatedAt=start.plusSeconds(2),placeSource="userEntered",placeDistanceMeters=42.25)))
            for(include in listOf(true,false)) {
                val options=ExportOptions(from=start,visitName=include,pointAltitude=include,visitAddress=include,visitNotes=include,visitDuration=include)
                val file=files.prepare(ExportFileRequest(setOf(ExportFormat.kml),options)).files.single()
                assertEquals(3L,file.counts.points);assertEquals(1L,file.counts.visits)
                val destination=DocumentsContract.createDocument(context.contentResolver,root,ExportFormat.kml.mime,"kml-parity-$include.kml")!!
                files.saveNewDocument(file,destination)
                val bytes=context.contentResolver.openInputStream(destination)!!.use {it.readBytes()};assertArrayEquals(file.file.readBytes(),bytes)
                val xml=javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {isNamespaceAware=true}.newDocumentBuilder().parse(bytes.inputStream())
                val kml="http://www.opengis.net/kml/2.2";val gx="http://www.google.com/kml/ext/2.2"
                assertEquals(3,xml.getElementsByTagNameNS(kml,"Placemark").length)
                assertEquals(2,xml.getElementsByTagNameNS(gx,"Track").length)
                assertEquals(1,xml.getElementsByTagNameNS(kml,"LineString").length)
                assertEquals(2,xml.getElementsByTagNameNS(kml,"Point").length)
                assertEquals("Whereday session 1",xml.getElementsByTagNameNS(kml,"name").item(2).textContent)
                val data=xml.getElementsByTagNameNS(kml,"Data")
                fun values(name:String)=(0 until data.length).map {data.item(it) as org.w3c.dom.Element}.filter {it.getAttribute("name")==name}.map {it.textContent}
                assertEquals(listOf("2","1"),values("pointCount"));assertEquals(listOf("visit","trackingSession","trackingSession"),values("kind"))
                assertEquals(if(include) listOf("Road & Sea") else emptyList<String>(),values("address"))
                assertEquals(if(include) listOf("1.51") else emptyList<String>(),values("durationMinutes"))
                assertEquals(listOf("manual"),values("source"));assertEquals(listOf("corrected"),values("confirmationStatus"))
                assertEquals(if(include) listOf("Original <&>") else emptyList<String>(),values("originalLocationName"))
                assertEquals(if(include) listOf("Found road") else emptyList<String>(),values("detectedAddress"))
                assertEquals(listOf("18.1"),values("originalLatitude"));assertEquals(listOf("42.25"),values("placeDistanceMeters"))
                assertEquals("-66.1234568 18.1234568 "+if(include) "2.67" else "0.00",xml.getElementsByTagNameNS(gx,"coord").item(0).textContent)
                assertTrue(files.preview(ExportFormat.kml,options).contains("Whereday session 2"))
                assertFalse(File(context.cacheDir,"exports").walkTopDown().any {it.isFile && it.name.startsWith("whereday-kml-")})
            }
        }
    }
    @Test fun roomGpxDeliveryPreservesFractionalGapAndSwiftNumbersThroughVerifiedSafWrite()=runBlocking {
        fixture {db,files ->
            val start=time.plusSeconds(1)
            db.history().putPoints(listOf(0L,600000L,1200001L).mapIndexed {index,offset->
                PointRow(TrackPoint(id="gpx-$index",coordinate=Coordinate(18.123456789,-66.123456789),time=start.plusMillis(offset),accuracy=2.675,altitude=-0.125,speed=1.005,outlier=index==2),null)
            })
            db.history().put(VisitRow(Visit(id="gpx-visit",coordinate=Coordinate(18.0,-66.0),arrivedAt=start.plusMillis(123),departedAt=start.plusMillis(90987),name="Café <&>",address=" ",notes=" ")))
            val prepared=files.prepare(ExportFileRequest(setOf(ExportFormat.gpx),ExportOptions(from=start)))
            val file=prepared.files.single();assertEquals(3L,file.counts.points);assertEquals(1L,file.counts.visits)
            val destination=DocumentsContract.createDocument(context.contentResolver,root,ExportFormat.gpx.mime,"gpx-parity.gpx")!!
            files.saveNewDocument(file,destination)
            val bytes=context.contentResolver.openInputStream(destination)!!.use {it.readBytes()}
            assertArrayEquals(file.file.readBytes(),bytes)
            val xml=javax.xml.parsers.DocumentBuilderFactory.newInstance().apply {isNamespaceAware=true}.newDocumentBuilder().parse(bytes.inputStream())
            val gpx="http://www.topografix.com/GPX/1/1";val isome="https://isome.isolated.tech/gpx/1.0"
            assertEquals(2,xml.getElementsByTagNameNS(gpx,"trkseg").length)
            assertEquals("Whereday export",xml.getElementsByTagNameNS(gpx,"name").item(0).textContent)
            assertEquals("Café <&>",xml.getElementsByTagNameNS(gpx,"name").item(1).textContent)
            assertEquals("Whereday track",xml.getElementsByTagNameNS(gpx,"name").item(2).textContent)
            assertEquals("  —  ",xml.getElementsByTagNameNS(gpx,"desc").item(0).textContent)
            assertEquals("2.67",xml.getElementsByTagNameNS(isome,"horizontalAccuracy").item(0).textContent)
            assertEquals("1.00",xml.getElementsByTagNameNS(isome,"speed").item(0).textContent)
            assertEquals("-0.12",xml.getElementsByTagNameNS(gpx,"ele").item(0).textContent)
            assertEquals("1.51",xml.getElementsByTagNameNS(isome,"durationMinutes").item(0).textContent)
            assertEquals(1,xml.getElementsByTagNameNS(isome,"isOutlier").length)
        }
    }
    @Test fun completeJSONIncludesAllEqualTimestampPagesAndEveryDeliveryChecksPaidAccess()=runBlocking {
        fixture {db,files ->
            val prepared=files.prepare(ExportFileRequest(setOf(ExportFormat.json),ExportOptions(kind=DataKind.points)))
            val file=prepared.files.single()
            assertEquals(1002L,file.counts.points)
            val json=JsonParser.parseString(file.file.readText()).asJsonObject
            assertEquals(1002,json["points"].asJsonArray.size());assertEquals(1002,json["totalPoints"].asInt)
            var deniedCalls=0
            val denied=ExportFiles(context,db) {deniedCalls++;error("Verified lifetime export access is required.")}
            try {denied.prepare(ExportFileRequest(setOf(ExportFormat.json)));fail()} catch(_:IllegalStateException) {}
            try {denied.share(prepared);fail()} catch(_:IllegalStateException) {}
            try {denied.saveToFolder(prepared,tree);fail()} catch(_:IllegalStateException) {}
            val empty=DocumentsContract.createDocument(context.contentResolver,root,"application/json","denied.json")!!
            try {denied.saveNewDocument(file,empty);fail()} catch(_:IllegalStateException) {}
            assertEquals(4,deniedCalls)
            assertNull(androidx.documentfile.provider.DocumentFile.fromTreeUri(context,tree)?.findFile("denied.json"))
            val preview=denied.preview(ExportFormat.json,ExportOptions(kind=DataKind.points))
            assertTrue(preview.contains("exportDate"));assertTrue(preview.length<=100_000);assertEquals(4,deniedCalls)
            val intent=files.share(prepared)
            assertEquals(Intent.ACTION_SEND,intent.action)
            assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION!=0)
            assertEquals("content",intent.clipData!!.getItemAt(0).uri.scheme)
        }
    }
    @Test fun newDestinationIsReadBackAndCorruptPartialIsRemovedWithoutDamagingExistingFile()=runBlocking {
        fixture {_,files ->
            val prepared=files.prepare(ExportFileRequest(setOf(ExportFormat.json)))
            val good=DocumentsContract.createDocument(context.contentResolver,root,"application/json","good.json")!!
            files.saveNewDocument(prepared.files.single(),good)
            assertEquals(prepared.files.single().file.readText(),context.contentResolver.openInputStream(good)!!.bufferedReader().use {it.readText()})
            val corrupt=DocumentsContract.createDocument(context.contentResolver,root,"application/json","corrupt.json")!!
            try {files.saveNewDocument(prepared.files.single(),corrupt);fail()} catch(_:IllegalStateException) {}
            assertTrue(context.contentResolver.openInputStream(good)!!.use {it.read()>0})
            assertNull(androidx.documentfile.provider.DocumentFile.fromTreeUri(context,tree)?.findFile("corrupt.json"))
            files.saveToFolder(prepared,tree);files.saveToFolder(prepared,tree)
            val names=androidx.documentfile.provider.DocumentFile.fromTreeUri(context,tree)!!.listFiles().map {it.name}
            val original=prepared.files.single().relativePath
            assertTrue(names.contains(original));assertTrue(names.contains(original.removeSuffix(".json")+" (2).json"))
        }
    }
    @Test fun onlyVerifiedDeliveriesSignalReviewAndDuplicatePickerReceiptsDoNotSignalAgain()=runBlocking {
        var completed=0
        fixture(onFileExported={completed++}) {_,files->
            files.preview(ExportFormat.json,ExportOptions());assertEquals(0,completed)
            val prepared=files.prepare(ExportFileRequest(setOf(ExportFormat.json)));files.share(prepared);assertEquals(0,completed)
            val good=DocumentsContract.createDocument(context.contentResolver,root,"application/json","good.json")!!
            files.saveNewDocument(prepared.files.single(),good);assertEquals(1,completed)
            val corrupt=DocumentsContract.createDocument(context.contentResolver,root,"application/json","corrupt.json")!!
            try {files.saveNewDocument(prepared.files.single(),corrupt);fail()} catch(_:IllegalStateException) {}
            assertEquals(1,completed)
            files.saveToFolder(prepared,tree);assertEquals(2,completed)
            val request=files.beginPicker(prepared)
            val destination=DocumentsContract.createDocument(context.contentResolver,root,"application/json","picked.json")!!
            files.pickerResult(request.id,destination,0);assertEquals(3,completed)
            files.pickerResult(request.id,destination,0);files.resumePicker(request.id);assertEquals(3,completed)
        }
    }
    @Test fun aReviewObserverFailureDoesNotDeleteOrMisreportASuccessfulFileExport()=runBlocking {
        fixture(onFileExported={error("Synthetic optional observer failure")}) {_,files->
            val prepared=files.prepare(ExportFileRequest(setOf(ExportFormat.json)))
            val good=DocumentsContract.createDocument(context.contentResolver,root,"application/json","good.json")!!
            files.saveNewDocument(prepared.files.single(),good)
            assertEquals(prepared.files.single().file.readText(),context.contentResolver.openInputStream(good)!!.bufferedReader().use {it.readText()})
        }
    }
    @Test fun daySplitsUseCivilMidnightsAcrossDSTAndResolveTemplateCollisions()=runBlocking {
        fixture {db,files ->
            db.history().put(PointRow(TrackPoint(id="next-day",coordinate=Coordinate(18.0,-66.0),time=Instant.parse("2026-03-09T04:00:00Z"),accuracy=5.0),null))
            val prepared=files.prepare(ExportFileRequest(setOf(ExportFormat.json,ExportFormat.owntracks),ExportOptions(kind=DataKind.points,zone=ZoneId.of("America/New_York")),"same-name",true))
            assertEquals(4,prepared.files.size);assertEquals(4,prepared.files.map {it.relativePath}.distinct().size)
            val json=prepared.files.filter {it.format==ExportFormat.json}
            assertEquals(listOf(1002L,1L),json.map {it.counts.points})
            assertEquals(1003L,json.sumOf {it.counts.points})
        }
    }
    @Test fun cancellationDeletesPartialPreparationAndClearDataInvalidatesPreparedFiles()=runBlocking {
        fixture {_,files ->
            val job=async {val taskContext=currentCoroutineContext();files.prepare(ExportFileRequest(setOf(ExportFormat.csv))) {taskContext.cancel()}}
            try {job.await();fail()} catch(_:CancellationException) {}
            assertTrue(File(context.cacheDir,"exports").listFiles().orEmpty().isEmpty())
            val prepared=files.prepare(ExportFileRequest(setOf(ExportFormat.json)))
            files.clearCache()
            try {files.share(prepared);fail()} catch(_:IllegalArgumentException) {}
        }
    }
    @Test fun scheduledPreparationCapturesExecutionWindowFilenameAndPaidCapability()=runBlocking {
        fixture {db,files ->
            val zone=ZoneId.of("America/New_York")
            val now=Instant.parse("2026-03-08T06:00:00Z")
            for((id,instant) in listOf("previous-day" to time.minusSeconds(1),"after-run" to now.plusSeconds(1))) {
                db.history().put(PointRow(TrackPoint(id=id,coordinate=Coordinate(18.0,-66.0),time=instant,accuracy=5.0),null))
            }
            val plan=ScheduledExportPlan.resolve(ExportFormat.json,ExportOptions(kind=DataKind.points,zone=zone),"track-{datetime}",ExportSchedule(21),ScheduledFileMode.append,null,now)
            val first=files.prepareScheduled(plan).files.single()
            assertEquals("track-2026-03-08.json",first.relativePath)
            assertEquals(1002L,first.counts.points)
            val json=JsonParser.parseString(first.file.readText()).asJsonObject
            assertEquals(now,Instant.parse(json["exportDate"].asString))
            assertEquals(1002,json["points"].asJsonArray.size())
            val retry=files.prepareScheduled(plan).files.single()
            assertEquals(first.relativePath,retry.relativePath)
            assertEquals(first.sha256,retry.sha256)
            val calls=mutableListOf<com.bontecou.whereday.billing.Capability>()
            val denied=ExportFiles(context,db) {capability ->calls+=capability;if(capability==com.bontecou.whereday.billing.Capability.scheduledExport) error("Scheduled export access is required")}
            try {denied.prepareScheduled(plan);fail()} catch(_:IllegalStateException) {}
            assertEquals(listOf(com.bontecou.whereday.billing.Capability.scheduledExport),calls)
            val before=File(context.cacheDir,"exports").listFiles().orEmpty().map {it.name}.toSet()
            var scheduleChecks=0
            val revoked=ExportFiles(context,db) {capability ->
                if(capability==com.bontecou.whereday.billing.Capability.scheduledExport && ++scheduleChecks==2) error("Scheduled export access expired")
            }
            try {revoked.prepareScheduled(plan);fail()} catch(_:IllegalStateException) {}
            assertEquals(2,scheduleChecks)
            assertEquals(before,File(context.cacheDir,"exports").listFiles().orEmpty().map {it.name}.toSet())
        }
    }
}
