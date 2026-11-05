package com.bontecou.whereday.platform

import androidx.test.platform.app.InstrumentationRegistry
import androidx.room.useWriterConnection
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.*
import java.util.*

class ImportResourceBoundsTest {
    private val now=Instant.parse("2026-10-26T13:00:00Z")
    private fun visit(index:Int,notes:String?=null)=Visit(id=UUID(0,index.toLong()).toString(),coordinate=Coordinate(18.0,-66.0),arrivedAt=now,name="Synthetic",notes=notes)
    private suspend fun fixture(block:suspend(HistoryDatabase,DiskHistoryImportWorkspaceFactory,File)->Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;check(context.packageName=="com.bontecou.whereday.platform.test")
        val root=File(context.cacheDir,"resource-test-${UUID.randomUUID()}");val db=HistoryDatabase.create(context,null)
        try {block(db,DiskHistoryImportWorkspaceFactory(root),root)} finally {db.close();root.deleteRecursively()}
    }
    @Test fun stagePagesAndPendingFlushesObeyBytesAsWellAsRecordCounts():Unit=runBlocking {fixture {_,factory,_->
        factory.create().use {workspace->
            repeat(12) {i->val value=visit(i,"x".repeat(190_000));workspace.stage.add(ImportStageRow(ImportRecordKind.visit,value.id,ImportStageCodec.gson.toJson(value)))}
            workspace.stage.finishRecords();var count=0;var pages=0
            workspace.stage.batches({}) {rows->
                assertTrue(rows.size<12);assertTrue(rows.sumOf {it.payload.toByteArray(Charsets.UTF_8).size}<=ImportRecordLimits.batchBytes)
                count+=rows.size;pages++
            }
            assertEquals(12,count);assertTrue(pages>1)
            workspace.stage.seal {};workspace.stage.verify {}
        }
    }}
    @Test fun largeLocalCorrectionsSplitMatchLoadsBeforeJavaRowsAreAccumulated():Unit=runBlocking {fixture {db,factory,_->
        val values=(1..6).map {visit(it,"local "+"x".repeat(180_000))}
        values.forEach {db.history().put(VisitRow(it))}
        assertTrue(db.history().importVisitTextBytes(values.map {it.id})>ImportRecordLimits.batchBytes/2)
        factory.create().use {workspace->
            values.forEach {value->workspace.stage.add(ImportStageRow(ImportRecordKind.visit,value.id,ImportStageCodec.gson.toJson(value.copy(notes=null))))}
            workspace.stage.finishRecords();var maximum=0
            val observed=object:HistoryImportStage by workspace.stage {
                override fun baselines(rows:List<ImportStageRow>) {maximum=maxOf(maximum,rows.size);workspace.stage.baselines(rows)}
            }
            val target=DatabaseHistoryImportTarget(db)
            assertEquals(6,target.inspect(observed) {});assertTrue(maximum in 1..2)
            workspace.stage.seal {};assertEquals(HistoryImportCounts(kept=6),target.apply(observed,HistoryImportPolicy.keepExisting) {})
            values.forEach {assertEquals(it,db.history().visit(it.id)!!.value)}
        }
    }}
    @Test fun oversizedMatchingJournalTextRejectsReviewWithoutChangingThatRecord():Unit=runBlocking {fixture {db,factory,_->
        val value=visit(1,"x".repeat(1_200_000));db.history().put(VisitRow(value))
        factory.create().use {workspace->
            workspace.stage.add(ImportStageRow(ImportRecordKind.visit,value.id,ImportStageCodec.gson.toJson(value.copy(notes=null))));workspace.stage.finishRecords()
            try {DatabaseHistoryImportTarget(db).inspect(workspace.stage) {};fail("Unsafe match was loaded")} catch(error:IllegalArgumentException) {assertTrue(error.message!!.contains("matching journal record"))}
            assertEquals(value,db.history().visit(value.id)!!.value)
        }
    }}
    @Test fun validLargeUnicodeRecordsCanBeReviewedAgainAfterTheirFirstImport():Unit=runBlocking {fixture {db,factory,_->
        val controller=HistoryImportController(DatabaseHistoryImportTarget(db),factory)
        try {
            val text=HistoryExporter.render(ExportFormat.json,listOf(visit(1,"北".repeat(200_000))),emptyList())
            for(expected in listOf(0,1)) {
                controller.select(object:HistoryImportInput {override fun open()=text.byteInputStream()},Locale.US,ZoneOffset.UTC)!!.join()
                val review=controller.state.value
                assertEquals(review.error,HistoryImportPhase.review,review.phase);assertEquals(expected,review.review!!.existing)
                controller.commit(review.review!!.id,HistoryImportPolicy.keepExisting)!!.join()
                assertEquals(controller.state.value.error,HistoryImportPhase.complete,controller.state.value.phase)
                assertEquals(if(expected==0) HistoryImportCounts(visits=1) else HistoryImportCounts(kept=1),controller.state.value.counts)
                controller.discard()
            }
        } finally {controller.close()}
    }}
    @Test fun realScratchSqliteFullCannotCreateAnApprovedImport():Unit=runBlocking {fixture {db,_,root->
        val factory=HistoryImportWorkspaceFactory {
            val directory=kotlin.io.path.createTempDirectory(root.apply {mkdirs()}.toPath(),"limited-").toFile()
            HistoryImportWorkspace(directory,DiskHistoryImportStage(File(directory,"records.db"),maximumPages=16)) {}
        }
        val controller=HistoryImportController(DatabaseHistoryImportTarget(db),factory)
        try {
            val values=(1..16).map {visit(it,"x".repeat(10_000)).copy(arrivedAt=now.plusSeconds(it.toLong()*120))}
            val text=HistoryExporter.render(ExportFormat.json,values,emptyList())
            controller.select(object:HistoryImportInput {override fun open()=text.byteInputStream()},Locale.US,ZoneOffset.UTC)!!.join()
            assertEquals(HistoryImportPhase.failed,controller.state.value.phase);assertTrue(controller.state.value.error,controller.state.value.error!!.contains("storage"))
            assertTrue(db.history().exportFirstVisits(Instant.EPOCH,Instant.ofEpochMilli(Long.MAX_VALUE)).isEmpty())
            controller.clearAll {};assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {controller.close()}
    }}
    @Test fun realJournalSqliteFullRollsBackTheApprovedTransaction():Unit=runBlocking {fixture {db,factory,root->
        val sentinel=visit(99,"Protected").copy(arrivedAt=now.minusSeconds(3600));db.history().put(VisitRow(sentinel))
        db.useWriterConnection {connection->
            val pages=connection.usePrepared("PRAGMA page_count") {it.step();it.getLong(0)}
            connection.usePrepared("PRAGMA max_page_count=${pages+8}") {it.step()}
        }
        val controller=HistoryImportController(DatabaseHistoryImportTarget(db),factory)
        try {
            val values=(1..32).map {visit(it,"x".repeat(4096)).copy(arrivedAt=now.plusSeconds(it.toLong()*120))}
            val text=HistoryExporter.render(ExportFormat.json,values,emptyList())
            controller.select(object:HistoryImportInput {override fun open()=text.byteInputStream()},Locale.US,ZoneOffset.UTC)!!.join()
            val review=controller.state.value
            assertEquals(review.error,HistoryImportPhase.review,review.phase)
            controller.commit(review.review!!.id,HistoryImportPolicy.keepExisting)!!.join()
            assertEquals(HistoryImportPhase.failed,controller.state.value.phase);assertTrue(controller.state.value.error,controller.state.value.error!!.contains("storage"))
            assertEquals(listOf(sentinel),db.history().exportFirstVisits(Instant.EPOCH,Instant.ofEpochMilli(Long.MAX_VALUE)).map {it.value})
            controller.clearAll {};assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {controller.close()}
    }}
    @Test fun oversizedEncodedStagePayloadCannotReachHistoryWrites():Unit=runBlocking {fixture {db,factory,root->
        val controller=HistoryImportController(DatabaseHistoryImportTarget(db),factory)
        try {
            val text=HistoryExporter.render(ExportFormat.json,listOf(visit(1,"\u0000".repeat(230_000))),emptyList())
            assertTrue(text.toByteArray().size>ImportRecordLimits.payloadBytes)
            controller.select(object:HistoryImportInput {override fun open()=text.byteInputStream()},Locale.US,ZoneOffset.UTC)!!.join()
            assertEquals(HistoryImportPhase.failed,controller.state.value.phase);assertTrue(controller.state.value.error!!.contains("too much text"))
            assertNull(db.history().visit(visit(1).id));assertEquals(0L,db.history().totalPointCount())
            controller.clearAll {};assertTrue(root.listFiles().orEmpty().isEmpty())
        } finally {controller.close()}
    }}
}
