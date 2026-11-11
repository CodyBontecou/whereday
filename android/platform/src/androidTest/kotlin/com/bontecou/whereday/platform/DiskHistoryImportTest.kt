package com.bontecou.whereday.platform

import android.database.sqlite.SQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.*
import java.util.Locale
import java.util.UUID

class DiskHistoryImportTest {
    private val now=Instant.parse("2026-10-26T12:00:00Z")
    private val point=HistoryImporter.parse(HistoryExporter.render(ExportFormat.json,emptyList(),listOf(TrackPoint(coordinate=Coordinate(18.0,-66.0),time=now,accuracy=5.0))),ExportFormat.json).points.single()
    private val visit=HistoryImporter.parse(HistoryExporter.render(ExportFormat.json,listOf(Visit(coordinate=point.coordinate,arrivedAt=now,name="Local correction",notes="Keep local note")),emptyList()),ExportFormat.json).visits.single()
    private fun json(visits:List<Visit>,points:List<TrackPoint>):String {
        val root=com.google.gson.JsonParser.parseString(HistoryExporter.render(ExportFormat.json,visits,points)).asJsonObject
        // Export files omit UUIDs; these source-identity fixtures explicitly supply supported IDs.
        root.getAsJsonArray("visits").zip(visits).forEach {(row,value)->row.asJsonObject.addProperty("id",value.id)}
        root.getAsJsonArray("points").zip(points).forEach {(row,value)->row.asJsonObject.addProperty("id",value.id)}
        return root.toString()
    }
    private suspend fun fixture(block:suspend(HistoryDatabase,DiskHistoryImportWorkspaceFactory,File)->Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;check(context.packageName=="com.bontecou.whereday.platform.test")
        val directory=File(context.cacheDir,"import-test-${UUID.randomUUID()}")
        val db=HistoryDatabase.create(context,null)
        try {db.history().put(PointRow(point,"session"));db.history().put(VisitRow(visit));block(db,DiskHistoryImportWorkspaceFactory(directory),directory)}
        finally {db.close();directory.deleteRecursively()}
    }
    private fun prepare(factory:DiskHistoryImportWorkspaceFactory,text:String):HistoryImportWorkspace {
        val workspace=factory.create()
        try {text.byteInputStream().use {workspace.capture(it) {}};workspace.prepare(Locale.US,ZoneOffset.UTC) {};return workspace}
        catch(error:Throwable) {workspace.close();throw error}
    }
    @Test fun sealedChronologicalDigestRejectsMissingOrOrphanedRowsAndValidPayloadChanges():Unit=runBlocking {fixture {db,factory,directory->
        val changed=point.copy(accuracy=9.0)
        val mutations=listOf(
            "DELETE FROM google_order" to emptyArray<Any>(),
            "UPDATE google_order SET id='orphan'" to emptyArray<Any>(),
            "UPDATE google_order SET seconds=seconds+1" to emptyArray<Any>(),
            "UPDATE records SET payload=?" to arrayOf<Any>(ImportStageCodec.gson.toJson(changed.copy(accuracy=8.0))),
            "UPDATE records SET baseline='changed'" to emptyArray<Any>(),
            "UPDATE records SET provided_id=1-provided_id" to emptyArray<Any>())
        for((sql,args) in mutations) prepare(factory,json(emptyList(),listOf(changed))).use {workspace->
            workspace.stage.seal {}
            val file=directory.walkTopDown().single {it.name=="records.db"}
            SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use {it.execSQL(sql,args)}
            assertThrows(sql,IllegalStateException::class.java) {workspace.stage.verify {}}
            assertEquals(point,db.history().point(point.id)!!.value)
            assertEquals(1L,db.history().totalPointCount())
        }
    }}
    @Test fun pointAdmissionHandlesDescendingBucketsLaterDuplicatesAndReset():Unit=runBlocking {fixture {_,factory,_->
        fun candidate(seconds:Long,coordinate:Coordinate=point.coordinate)=point.copy(id=UUID.randomUUID().toString(),time=now.plusSeconds(seconds),coordinate=coordinate)
        val high=candidate(10);val low=candidate(5);val repeatedLow=candidate(5)
        val distinct=candidate(5,Coordinate(19.0,-66.0));val repeatedHigh=candidate(10);val newest=candidate(11)
        val values=listOf(high,low,repeatedLow,distinct,repeatedHigh,newest)
        fun row(value:TrackPoint)=ImportStageRow(ImportRecordKind.point,value.id,ImportStageCodec.gson.toJson(value))
        prepare(factory,json(emptyList(),values)).use {workspace->
            val stage=workspace.stage
            stage.resetAdmission()
            assertEquals(ImportStageDuplicates(points=1),stage.admitPreview(values.take(4).map(::row)) {})
            assertEquals(ImportStageDuplicates(points=1),stage.admitPreview(values.drop(4).map(::row)) {})
            stage.resetAdmission()
            assertEquals(ImportStageDuplicates(),stage.admitPreview(listOf(row(high))) {})
        }
    }}
    @Test fun batchedFreshPointsMatchPendingAndPreviousPageRowsWithoutLosingDistinctLocations():Unit=runBlocking {fixture {db,factory,_->
        withContext(Dispatchers.IO) {db.clearAllTables()}
        val values=(0..200).map {point.copy(id=UUID.randomUUID().toString(),time=now.plusSeconds(it.toLong()))}
        val duplicateInPage=values[100].copy(id=UUID.randomUUID().toString(),time=values[100].time.plusMillis(100))
        val duplicateAcrossPage=values[198].copy(id=UUID.randomUUID().toString(),time=values[198].time.plusMillis(100))
        val distinct=values.last().copy(id=UUID.randomUUID().toString(),time=values.last().time.plusMillis(100),coordinate=Coordinate(19.0,-66.0))
        prepare(factory,json(emptyList(),values+duplicateInPage+duplicateAcrossPage+distinct)).use {workspace->
            val target=DatabaseHistoryImportTarget(db)
            assertEquals(2,target.inspectPlan(workspace.stage) {}.repeated.points)
            workspace.stage.seal {}
            assertEquals(HistoryImportCounts(points=202,kept=2),target.apply(workspace.stage,HistoryImportPolicy.keepExisting) {})
            assertEquals(202L,db.history().totalPointCount())
            assertNotNull(db.history().importPoints(listOf(distinct.id)).singleOrNull())
            assertEquals(HistoryImportCounts(kept=204),target.apply(workspace.stage,HistoryImportPolicy.keepExisting) {})
            assertEquals(202L,db.history().totalPointCount())
        }
    }}
    @Test fun malformedAuditCannotProduceAnApprovedReviewOrChangeRoom():Unit=runBlocking {fixture {db,factory,directory->
        val text="""arrived_at,latitude,longitude,visit_metadata
2026-10-28T12:00:00.000Z,18,-66,"{""originalLocationName"":""\ud800""}"
"""
        val controller=HistoryImportController(DatabaseHistoryImportTarget(db),factory)
        try {
            controller.select(object:HistoryImportInput {override fun open()=text.byteInputStream()},Locale.US,ZoneOffset.UTC)!!.join()
            assertEquals(HistoryImportPhase.failed,controller.state.value.phase);assertNull(controller.state.value.review)
            assertEquals(1L,db.history().totalVisitCount());assertEquals(visit,db.history().visit(visit.id)!!.value)
            assertEquals(1L,db.history().totalPointCount());assertFalse(directory.walkTopDown().any {it.name=="source"})
        } finally {controller.close()}
    }}
    @Test fun jsonCsvAndMarkdownAuditSurviveReviewAndAtomicRoomCommit():Unit=runBlocking {fixture {db,factory,directory->
        val corrected=visit.copy(id="00000000-0000-0000-0000-000000000099",arrivedAt=now.plusSeconds(86400),departedAt=now.plusSeconds(86500),
            source=VisitSource.manual,confirmation=Confirmation.corrected,originalCoordinate=Coordinate(18.1,-66.1),originalName="Original | <br> \\ 北",originalAddress="Old\nRoad",
            detectedCoordinate=Coordinate(18.2,-66.2),detectedName="",detectedAddress="-",confirmedAt=now.plusSeconds(1),updatedAt=now.plusSeconds(2),placeSource="userEntered",placeDistanceMeters=42.25)
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val exports=ExportFiles(context,db) {}
        try {for(format in listOf(ExportFormat.json,ExportFormat.csv,ExportFormat.markdown)) {
            db.history().put(VisitRow(corrected))
            val prepared=exports.prepare(ExportFileRequest(setOf(format),ExportOptions(kind=DataKind.visits,zone=ZoneOffset.UTC,from=corrected.arrivedAt,until=corrected.departedAt!!)))
            assertEquals(1L,prepared.files.single().counts.visits)
            db.history().deleteVisit(corrected.id)
            val controller=HistoryImportController(DatabaseHistoryImportTarget(db),factory)
            try {
                controller.select(object:HistoryImportInput {override fun open()=prepared.files.single().file.inputStream()},Locale.US,ZoneOffset.UTC)!!.join()
                val state=controller.state.value;assertEquals(state.error,HistoryImportPhase.review,state.phase)
                assertEquals(1,state.review!!.visits);assertEquals(1L,db.history().totalVisitCount())
                controller.commit(state.review.id,HistoryImportPolicy.keepExisting)!!.join()
                assertEquals(controller.state.value.error,HistoryImportPhase.complete,controller.state.value.phase)
                val saved=db.history().exportFirstVisits(Instant.ofEpochMilli(Long.MIN_VALUE),Instant.ofEpochMilli(Long.MAX_VALUE)).single {it.value.id!=visit.id}.value
                assertEquals(corrected.copy(id=saved.id,departedAt=if(format==ExportFormat.markdown) corrected.departedAt?.truncatedTo(java.time.temporal.ChronoUnit.MINUTES) else corrected.departedAt),saved);assertEquals(1L,db.history().totalPointCount())
                db.history().deleteVisit(saved.id)
                assertFalse(directory.walkTopDown().any {it.name=="source"})
            } finally {controller.close();exports.discard(prepared)}
        }} finally {exports.clearCache()}
    }}
    @Test fun controllerReviewsAllFormatsWithoutWritingAndKeepsDuplicatesAndLocalRecords():Unit=runBlocking {fixture {db,factory,directory->
        val controller=HistoryImportController(DatabaseHistoryImportTarget(db),factory)
        try {
            for(format in listOf(ExportFormat.json,ExportFormat.csv,ExportFormat.markdown)) {
                val text=HistoryExporter.render(format,listOf(visit.copy(name="Imported")),listOf(point,point))
                controller.select(object:HistoryImportInput {override fun open()=text.byteInputStream()},Locale.US,ZoneOffset.UTC)!!.join()
                val state=controller.state.value
                assertEquals(state.error,HistoryImportPhase.review,state.phase)
                assertEquals(1,state.review!!.visits);assertEquals(1,state.review!!.points);assertEquals(1,state.review!!.repeated)
                assertEquals(1L,db.history().totalPointCount())
                assertFalse(directory.walkTopDown().any {it.name=="source"})
                controller.discard()
                withTimeout(3_000) {while(directory.listFiles().orEmpty().isNotEmpty()) delay(5)}
            }
            val text=json(listOf(visit.copy(name="Imported")),listOf(point.copy(accuracy=9.0)))
            controller.select(object:HistoryImportInput {override fun open()=text.byteInputStream()})!!.join()
            assertEquals(2,controller.state.value.review!!.existing)
            controller.commit(controller.state.value.review!!.id,HistoryImportPolicy.keepExisting)!!.join()
            assertEquals(HistoryImportCounts(kept=2),controller.state.value.counts)
            assertEquals(visit,db.history().visit(visit.id)!!.value);assertEquals("session",db.history().point(point.id)!!.sessionId)
        } finally {controller.close()}
    }}
    @Test fun replacementUsesStagedBaselinesAndPreservesSessionAndDetectorRules():Unit=runBlocking {fixture {db,factory,_->
        db.history().put(DwellRow(state=DwellState(visit.coordinate,now,now,visit.id)))
        prepare(factory,json(listOf(visit.copy(departedAt=now.plusSeconds(60))),listOf(point.copy(accuracy=9.0)))).use {workspace->
            val target=DatabaseHistoryImportTarget(db)
            assertEquals(2,target.inspect(workspace.stage) {});workspace.stage.seal {}
            assertEquals(HistoryImportCounts(visits=1,points=1),target.apply(workspace.stage,HistoryImportPolicy.replaceExisting) {})
            assertEquals("session",db.history().point(point.id)!!.sessionId);assertNull(db.history().dwell()!!.state.visitId)
            assertEquals(9.0,db.history().point(point.id)!!.value.accuracy,0.0)
        }
    }}
    @Test fun aStaleMatchInALaterBatchRollsBackEarlierNewRows():Unit=runBlocking {fixture {db,factory,_->
        val points=(1..300).map {point.copy(id=UUID(0,it.toLong()).toString(),time=now.plusSeconds(it.toLong()))}+point
        val newVisit=visit.copy(id=UUID.randomUUID().toString())
        prepare(factory,json(listOf(newVisit),points)).use {workspace->
            val target=DatabaseHistoryImportTarget(db);target.inspect(workspace.stage) {};workspace.stage.seal {}
            db.history().put(PointRow(point.copy(accuracy=11.0),"session"))
            try {target.apply(workspace.stage,HistoryImportPolicy.replaceExisting) {};fail("Stale stage committed")} catch(_:HistoryImportChanged) {}
            assertNull(db.history().visit(newVisit.id));assertEquals(1L,db.history().totalPointCount())
            assertEquals(11.0,db.history().point(point.id)!!.value.accuracy,0.0)
        }
    }}
    @Test fun cancellationAfterWritesStartRollsBackEveryBatch():Unit=runBlocking {fixture {db,factory,_->
        prepare(factory,json(emptyList(),(1..300).map {point.copy(id=UUID(0,it.toLong()).toString(),time=now.plusSeconds(it.toLong()))})).use {workspace->
            val target=DatabaseHistoryImportTarget(db);target.inspect(workspace.stage) {};workspace.stage.seal {}
            var verification=0;workspace.stage.verify {verification++};var ticks=0
            try {target.apply(workspace.stage,HistoryImportPolicy.keepExisting) {if(++ticks>verification+50) throw HistoryImportCanceled()};fail("Canceled stage committed")} catch(_:HistoryImportCanceled) {}
            assertTrue(ticks>verification);assertEquals(1L,db.history().totalPointCount())
        }
    }}
    @Test fun changedStageBytesCannotMutateHistoryAndOrphansCannotDeleteLiveReviews():Unit=runBlocking {fixture {db,factory,directory->
        val orphan=File(directory,"import-orphan").apply {mkdirs()};File(orphan,"source").writeText("synthetic")
        prepare(factory,HistoryExporter.render(ExportFormat.json,emptyList(),listOf(point.copy(accuracy=9.0)))).use {workspace->
            assertFalse(orphan.exists())
            val target=DatabaseHistoryImportTarget(db);target.inspect(workspace.stage) {};workspace.stage.seal {}
            val file=directory.walkTopDown().single {it.name=="records.db"}
            factory.create().use {assertTrue(file.exists())}
            SQLiteDatabase.openDatabase(file.path,null,SQLiteDatabase.OPEN_READWRITE).use {it.execSQL("UPDATE records SET payload='corrupt'")}
            try {target.apply(workspace.stage,HistoryImportPolicy.replaceExisting) {};fail("Corrupt stage accepted")} catch(_:IllegalStateException) {}
            assertEquals(point,db.history().point(point.id)!!.value)
        }
        factory.reap();assertTrue(directory.listFiles().orEmpty().isEmpty())
    }}
    @Test fun flushedIdentityIndexRejectsConflictsAndReconstructionReapsOnlyOrphans():Unit=runBlocking {fixture {_,factory,directory->
        factory.create().use {workspace->
            val rows=(1..300).map {i->val value=point.copy(id=UUID(0,i.toLong()).toString());ImportStageRow(ImportRecordKind.point,value.id,ImportStageCodec.gson.toJson(value))}
            rows.forEach {assertTrue(workspace.stage.add(it))}
            assertFalse(workspace.stage.add(rows.first()))
            try {workspace.stage.add(rows.first().copy(payload=ImportStageCodec.gson.toJson(rows.first().point().copy(accuracy=8.0))));fail("Conflicting source accepted")} catch(_:IllegalArgumentException) {}
            workspace.stage.finishRecords();assertEquals(200,workspace.stage.page(null).size)
            val orphan=File(directory,"import-orphan").apply {mkdirs()};File(orphan,"source").writeText("synthetic")
            DiskHistoryImportWorkspaceFactory(directory)
            assertFalse(orphan.exists());assertEquals(1,directory.listFiles().orEmpty().size)
        }
    }}
    @Test fun badEofAndInvalidUtf8LeaveNoReviewOrJournalWrites():Unit=runBlocking {fixture {db,factory,directory->
        val controller=HistoryImportController(DatabaseHistoryImportTarget(db),factory)
        try {
            val valid=HistoryExporter.render(ExportFormat.json,emptyList(),listOf(point))
            for(bytes in listOf((valid+" garbage").toByteArray(),byteArrayOf(0xff.toByte()))) {
                controller.select(object:HistoryImportInput {override fun open()=bytes.inputStream()})!!.join()
                assertEquals(HistoryImportPhase.failed,controller.state.value.phase);assertEquals(1L,db.history().totalPointCount())
                controller.discard();withTimeout(3_000) {while(directory.listFiles().orEmpty().isNotEmpty()) delay(5)}
            }
        } finally {controller.close()}
    }}
}
