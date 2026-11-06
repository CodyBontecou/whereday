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
import java.time.*
import java.util.UUID

class ScheduledExportCoordinatorTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val tree=DocumentsContract.buildTreeDocumentUri("com.bontecou.whereday.export-test-documents","root")
    private val day=Instant.parse("2026-10-27T00:00:00Z")
    private inner class Fixture(val db:HistoryDatabase) {
        @Volatile var now=day.plusSeconds(12*3600+1800)
        @Volatile var allowed=true
        @Volatile var pattern="track-{datetime}"
        @Volatile var inference:InferenceRules?=null
        val files=ExportFiles(context,db) {check(allowed) {"Verified export access is required"}}
        fun coordinator(checkpoint:(AutomaticExportCheckpoint)->Unit={})=ScheduledExportCoordinator(context,files,
            {ScheduledExportInputs(tree.toString(),pattern,inference)},{check(allowed) {"Verified schedule access is required"}},{now},{ZoneOffset.UTC},checkpoint)
        val config=AutomaticExportSettings(true,ExportSchedule(9,0,3),ExportFormat.json,DataKind.points,ScheduledFileMode.append)
        fun document(name:String):Uri?=androidx.documentfile.provider.DocumentFile.fromTreeUri(context,tree)!!.findFile(name)?.uri
        fun json(name:String)=context.contentResolver.openInputStream(requireNotNull(document(name)))!!.bufferedReader().use {JsonParser.parseReader(it).asJsonObject}
    }
    private suspend fun fixture(block:suspend (Fixture)->Unit) {
        check(context.packageName=="com.bontecou.whereday.platform.test")
        val name="schedule-coordinator-${UUID.randomUUID()}.db";val db=HistoryDatabase.create(context,name);val f=Fixture(db)
        try {
            ExportTestDocuments.rejectWrites.set(false)
            context.grantUriPermission(context.packageName,tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            f.coordinator().clear();f.files.clearCache()
            db.history().putPoints(listOf(0L,9*3600L,12*3600L,12*3600L+1800,15*3600L,18*3600L,24*3600L).mapIndexed {i,seconds ->PointRow(TrackPoint(id="synthetic-$i",coordinate=Coordinate(18.0,-66.0),time=day.plusSeconds(seconds),accuracy=5.0),null)})
            block(f)
        } finally {
            ExportTestDocuments.rejectWrites.set(false)
            f.coordinator().clear();f.files.clearCache();db.close();context.deleteDatabase(name)
            context.revokeUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            File(context.cacheDir,"synthetic-export-documents").deleteRecursively()
        }
    }
    private suspend fun interrupted(block:suspend ()->Unit) {try {block();fail("Expected checkpoint interruption")} catch(_:CancellationException) {}}

    @Test fun legacyFeedbackUpgradesWithoutChangingTheCapturedRequest()=runBlocking {
        fixture {f->
            val first=f.coordinator {if(it==AutomaticExportCheckpoint.captured) throw CancellationException("synthetic death")}
            first.configure(f.config);interrupted {first.runIfDue()}
            val captured=first.snapshot().pending!!
            val receipt=File(context.filesDir,"automatic-exports/state.json")
            val json=JsonParser.parseString(receipt.readText()).asJsonObject
            json.remove("lastFailureCode");json.remove("lastNoticeCode")
            json.addProperty("lastNotice",AutomaticExportNotice.missingDay.diagnostic)
            json.addProperty("lastError","content://synthetic-private-token")
            json["pending"].asJsonObject.apply {remove("noticeCode");addProperty("notice",AutomaticExportNotice.jsonRewritten.diagnostic)}
            receipt.writeText(json.toString())
            val restored=f.coordinator();val before=restored.snapshot()
            assertEquals(captured.id,before.pending!!.id);assertEquals(captured.plan,before.pending!!.plan)
            assertEquals(AutomaticExportFailure.execution,before.lastFailure);assertFalse(before.lastError!!.contains("synthetic-private-token"))
            assertEquals(AutomaticExportNotice.missingDay,before.lastStatus);assertEquals(AutomaticExportNotice.jsonRewritten,before.pending!!.status)
            restored.configure(f.config.copy(enabled=false))
            val saved=JsonParser.parseString(receipt.readText()).asJsonObject
            assertEquals(2,saved["version"].asInt);assertEquals("missingDay",saved["lastNoticeCode"].asString)
            assertEquals("jsonRewritten",saved["pending"].asJsonObject["noticeCode"].asString)
            assertEquals(captured.plan,restored.snapshot().pending!!.plan)
        }
    }
    @Test fun unknownFeedbackCodesCannotMasqueradeAsVerifiedSuccessOrDiscardARetry()=runBlocking {
        fixture {f->
            val first=f.coordinator {if(it==AutomaticExportCheckpoint.captured) throw CancellationException("synthetic death")}
            first.configure(f.config);interrupted {first.runIfDue()};val captured=first.snapshot().pending!!
            val receipt=File(context.filesDir,"automatic-exports/state.json")
            val json=JsonParser.parseString(receipt.readText()).asJsonObject
            json.addProperty("lastFailureCode","future-success");json.addProperty("lastError","synthetic-private-token")
            json.addProperty("lastNoticeCode","verified");json.addProperty("lastNotice","Export saved and verified.")
            json["pending"].asJsonObject.apply {addProperty("noticeCode","verified");addProperty("notice","synthetic-private-token")}
            receipt.writeText(json.toString())
            val restored=f.coordinator();val state=restored.snapshot()
            assertEquals(AutomaticExportFailure.execution,state.lastFailure);assertEquals(AutomaticExportNotice.previousStatus,state.lastStatus)
            assertEquals(AutomaticExportNotice.previousStatus,state.pending!!.status);assertEquals(captured.id,state.pending!!.id);assertEquals(captured.plan,state.pending!!.plan)
            assertFalse(state.lastError!!.contains("synthetic-private-token"));assertFalse(state.lastNotice!!.contains("verified"))
            assertNull(state.lastSuccess);assertNull(state.appendCursor)
            assertEquals(AutomaticRunOutcome.exported,restored.retryPending())
            assertEquals(4,f.json("track-2026-10-27.json")["points"].asJsonArray.size());assertEquals(captured.plan.filenameDate,restored.snapshot().lastSuccess)
        }
    }
    @Test fun currentDayOnlyAndDuplicateWakesDoNotRepeatCompletedExports()=runBlocking {
        fixture {f ->
            val c=f.coordinator();c.configure(f.config.copy(schedule=ExportSchedule(9),fileMode=ScheduledFileMode.rewrite))
            f.now=day.plusSeconds(8*3600)
            assertEquals(AutomaticRunOutcome.notDue,c.runIfDue());assertNull(c.snapshot().lastSuccess)
            f.now=day.plusSeconds(12*3600+1800)
            val outcomes=awaitAll(async {c.runIfDue()},async {f.coordinator().runIfDue()})
            assertEquals(1,outcomes.count {it==AutomaticRunOutcome.exported});assertEquals(1,outcomes.count {it==AutomaticRunOutcome.notDue})
            assertEquals(4,f.json("track-2026-10-27.json")["points"].asJsonArray.size())
            assertEquals(f.now,c.snapshot().lastSuccess);assertNull(c.snapshot().appendCursor)
            val opens=ExportTestDocuments.outputOpens.get();assertEquals(AutomaticRunOutcome.notDue,c.runIfDue());assertEquals(opens,ExportTestDocuments.outputOpens.get())
            f.now=day.plusSeconds(24*3600+8*3600);assertEquals(AutomaticRunOutcome.notDue,c.runIfDue())
            f.now=day.plusSeconds(24*3600+10*3600);assertEquals(AutomaticRunOutcome.exported,c.runIfDue())
            assertEquals(1,f.json("track-2026-10-28.json")["points"].asJsonArray.size());assertNull(f.document("track-2026-10-26.json"))
        }
    }
    @Test fun intervalAppendCommitsCursorOnceAndDailyRewriteLeavesItAlone()=runBlocking {
        fixture {f ->
            val c=f.coordinator();c.configure(f.config);f.now=day.plusSeconds(9*3600+1800)
            assertEquals(AutomaticRunOutcome.exported,c.runIfDue());assertEquals(f.now,c.snapshot().appendCursor)
            assertEquals(2,f.json("track-2026-10-27.json")["points"].asJsonArray.size())
            f.now=day.plusSeconds(12*3600+1800);assertEquals(AutomaticRunOutcome.exported,c.runIfDue())
            assertEquals(4,f.json("track-2026-10-27.json")["points"].asJsonArray.size())
            val cursor=c.snapshot().appendCursor
            c.configure(f.config.copy(schedule=ExportSchedule(9)))
            f.now=day.plusSeconds(18*3600);assertEquals(AutomaticRunOutcome.exported,c.runNow())
            assertEquals(6,f.json("track-2026-10-27.json")["points"].asJsonArray.size());assertEquals(cursor,c.snapshot().appendCursor)
        }
    }
    @Test fun deliveredReceiptRecoversAtomicSuccessAndCursorWithoutRewriting()=runBlocking {
        fixture {f ->
            val first=f.coordinator {if(it==AutomaticExportCheckpoint.delivered) throw CancellationException("synthetic death")}
            first.configure(f.config);interrupted {first.runIfDue()}
            val pending=first.snapshot().pending!!;assertEquals(f.now,pending.plan.filenameDate)
            assertNull(first.snapshot().lastSuccess);assertNull(first.snapshot().appendCursor)
            assertEquals(ScheduledDeliveryPhase.completed,f.files.scheduledDeliveryState(pending.id)!!.phase)
            val opens=ExportTestDocuments.outputOpens.get();val captured=f.now
            f.now=day.plusSeconds(24*3600+18*3600)
            val restored=f.coordinator();assertEquals(AutomaticRunOutcome.exported,restored.retryPending())
            val done=restored.snapshot();assertEquals(captured,done.lastSuccess);assertEquals(captured,done.appendCursor);assertNull(done.pending)
            assertEquals(opens,ExportTestDocuments.outputOpens.get());assertNull(f.document("track-2026-10-28.json"))
        }
    }
    @Test fun capturedWindowAndFilenameSurviveDeathAndLaterSettingsChanges()=runBlocking {
        fixture {f ->
            val first=f.coordinator {if(it==AutomaticExportCheckpoint.captured) throw CancellationException("synthetic death")}
            first.configure(f.config);interrupted {first.runIfDue()}
            val pending=first.snapshot().pending!!;val captured=f.now
            f.now=day.plusSeconds(24*3600+18*3600);f.pattern="different-{date}"
            assertEquals(AutomaticRunOutcome.exported,f.coordinator().retryPending())
            assertEquals(4,f.json("track-2026-10-27.json")["points"].asJsonArray.size())
            assertEquals(captured,f.coordinator().snapshot().appendCursor);assertNull(f.document("different-2026-10-28.json"))
            assertEquals(captured,pending.plan.options.until)
        }
    }
    @Test fun failedDeliveryDoesNotAdvanceSuccessOrCursorAndRetryReusesTheRun()=runBlocking {
        fixture {f ->
            val c=f.coordinator();c.configure(f.config)
            ExportTestDocuments.rejectWrites.set(true)
            assertEquals(AutomaticRunOutcome.failed,c.runIfDue())
            val failed=c.snapshot();assertNull(failed.lastSuccess);assertNull(failed.appendCursor);assertNotNull(failed.lastError)
            val id=failed.pending!!.id
            ExportTestDocuments.rejectWrites.set(false)
            assertEquals(AutomaticRunOutcome.exported,f.coordinator().retryPending())
            assertEquals(ScheduledDeliveryPhase.completed,f.files.scheduledDeliveryState(id)!!.phase)
            val done=c.snapshot();assertNull(done.pending);assertEquals(f.now,done.lastSuccess);assertEquals(f.now,done.appendCursor)
        }
    }
    @Test fun malformedJSONOrMissingDayFileRebuildsFullCapturedDay()=runBlocking {
        fixture {f ->
            val c=f.coordinator();c.configure(f.config);f.now=day.plusSeconds(9*3600+1800)
            assertEquals(AutomaticRunOutcome.exported,c.runIfDue())
            val uri=f.document("track-2026-10-27.json")!!
            context.contentResolver.openOutputStream(uri,"wt")!!.use {it.write("invalid JSON".toByteArray())}
            f.now=day.plusSeconds(12*3600+1800);assertEquals(AutomaticRunOutcome.exported,c.runIfDue())
            assertEquals(4,f.json("track-2026-10-27.json")["points"].asJsonArray.size());assertTrue(c.snapshot().lastNotice!!.contains("could not be merged"));assertEquals(AutomaticExportNotice.jsonRewritten,c.snapshot().lastStatus)
            assertTrue(DocumentsContract.deleteDocument(context.contentResolver,uri))
            f.now=day.plusSeconds(18*3600);assertEquals(AutomaticRunOutcome.exported,c.runIfDue())
            assertEquals(6,f.json("track-2026-10-27.json")["points"].asJsonArray.size());assertTrue(c.snapshot().lastNotice!!.contains("missing"));assertEquals(AutomaticExportNotice.missingDay,c.snapshot().lastStatus)
        }
    }
    @Test fun disablingCancellationAndClearPreventOldPendingWorkFromRunning()=runBlocking {
        fixture {f ->
            val first=f.coordinator {if(it==AutomaticExportCheckpoint.captured) throw CancellationException("synthetic death")}
            first.configure(f.config);interrupted {first.runIfDue()}
            val restored=f.coordinator()
            try {restored.configure(f.config.copy(format=ExportFormat.csv));fail()} catch(_:IllegalStateException) {}
            restored.configure(f.config.copy(enabled=false));assertEquals(AutomaticRunOutcome.disabled,restored.runIfDue())
            restored.cancelPending();assertNull(restored.snapshot().pending)
            restored.clear();assertEquals(AutomaticExportState(),restored.snapshot());assertEquals(AutomaticRunOutcome.disabled,restored.runIfDue())
            assertNull(f.document("track-2026-10-27.json"))
        }
    }
    @Test fun corruptSavedConfigurationRequiresExplicitResetAndUnverifiedAccessCannotEnable()=runBlocking {
        fixture {f ->
            val c=f.coordinator();f.allowed=false
            try {c.configure(f.config);fail()} catch(_:IllegalStateException) {}
            assertEquals(AutomaticExportState(),c.snapshot())
            val receipt=File(context.filesDir,"automatic-exports/state.json")
            receipt.writeText("{\"version\":99}")
            try {c.snapshot();fail()} catch(_:IllegalArgumentException) {}
            c.clear();assertEquals(AutomaticExportState(),c.snapshot())
        }
    }
    @Test fun cancelSkipsTheSameWakeWithoutAdvancingSuccessButAllowsNextOccurrence()=runBlocking {
        fixture {f ->
            val first=f.coordinator {if(it==AutomaticExportCheckpoint.captured) throw CancellationException("synthetic death")}
            first.configure(f.config);interrupted {first.runIfDue()}
            val c=f.coordinator();val canceled=c.snapshot().pending!!.occurrence
            c.cancelPending();assertEquals(canceled,c.snapshot().canceledOccurrence)
            assertEquals(AutomaticRunOutcome.notDue,c.runIfDue());assertNull(c.snapshot().lastSuccess);assertNull(c.snapshot().appendCursor)
            f.now=day.plusSeconds(15*3600);assertEquals(AutomaticRunOutcome.exported,c.runIfDue())
            assertEquals(f.now,c.snapshot().lastSuccess)
        }
    }
    @Test fun containerAppendAlwaysRewritesTheDayWithoutAdvancingACursor()=runBlocking {
        fixture {f ->
            val c=f.coordinator();c.configure(f.config.copy(format=ExportFormat.gpx))
            assertEquals(AutomaticRunOutcome.exported,c.runIfDue());assertNull(c.snapshot().appendCursor)
            f.now=day.plusSeconds(18*3600);assertEquals(AutomaticRunOutcome.exported,c.runIfDue());assertNull(c.snapshot().appendCursor)
            val uri=f.document("track-2026-10-27.gpx")!!
            val xml=context.contentResolver.openInputStream(uri)!!.bufferedReader().use {it.readText()}
            assertEquals(6,Regex("<trkpt ").findAll(xml).count())
        }
    }
    @Test fun explicitRunReplacesAFutureCursorWithActualCapturedExecutionTime()=runBlocking {
        fixture {f ->
            val c=f.coordinator();c.configure(f.config)
            val receipt=File(context.filesDir,"automatic-exports/state.json")
            val json=JsonParser.parseString(receipt.readText()).asJsonObject
            val future=f.now.plusSeconds(86_400)
            json.addProperty("lastSuccess",future.toString());json.addProperty("appendCursor",future.toString());receipt.writeText(json.toString())
            assertEquals(AutomaticRunOutcome.notDue,c.runIfDue())
            assertEquals(AutomaticRunOutcome.exported,c.runNow())
            assertEquals(f.now,c.snapshot().lastSuccess);assertEquals(f.now,c.snapshot().appendCursor)
        }
    }
    @Test fun outingSchedulesDeliverEveryFormatUsingCurrentDayStartSelectionAndCompleteRoutes()=runBlocking {
        fixture {f ->
            f.db.history().put(SessionRow(RecordingSession("one",day.plusSeconds(9*3600),day.plusSeconds(10*3600))))
            f.db.history().put(SessionRow(RecordingSession("two",day.plusSeconds(12*3600),day.plusSeconds(13*3600))))
            val c=f.coordinator()
            for(format in ExportFormat.entries) {
                c.configure(f.config.copy(schedule=ExportSchedule(9),format=format,outings=true))
                assertEquals(format.name,AutomaticRunOutcome.exported,c.runNow())
                val uri=f.document("track-2026-10-27.${format.extension}")!!
                val text=context.contentResolver.openInputStream(uri)!!.bufferedReader().use {it.readText()}
                when(format) {
                    ExportFormat.json -> assertEquals(2,JsonParser.parseString(text).asJsonObject["outings"].asJsonArray.size())
                    ExportFormat.csv -> assertTrue(text.startsWith("id,title,sequence_number,"))
                    ExportFormat.markdown -> assertTrue(text.contains("**Total Outings:** 2"))
                    ExportFormat.gpx -> assertEquals(3,Regex("<trkpt ").findAll(text).count())
                    ExportFormat.kml -> assertEquals(3,Regex("<gx:coord>").findAll(text).count())
                    ExportFormat.geojson -> assertEquals(3,JsonParser.parseString(text).asJsonObject["features"].asJsonArray.size())
                    ExportFormat.owntracks -> assertEquals(3,JsonParser.parseString(text).asJsonArray.size())
                    ExportFormat.overland -> assertEquals(3,JsonParser.parseString(text).asJsonObject["locations"].asJsonArray.size())
                }
                assertNull(c.snapshot().pending);assertNull(c.snapshot().appendCursor)
            }
        }
    }
    @Test fun intervalOutingJsonAppendMergesIdentitiesAndCommitsCapturedCursorOnlyAfterDelivery()=runBlocking {
        fixture {f ->
            f.db.history().put(SessionRow(RecordingSession("one",day.plusSeconds(9*3600),day.plusSeconds(10*3600))))
            f.db.history().put(SessionRow(RecordingSession("two",day.plusSeconds(12*3600),day.plusSeconds(13*3600))))
            val c=f.coordinator();c.configure(f.config.copy(outings=true));f.now=day.plusSeconds(9*3600+1800)
            assertEquals(AutomaticRunOutcome.exported,c.runIfDue());assertEquals(f.now,c.snapshot().appendCursor)
            assertEquals(listOf("one"),f.json("track-2026-10-27.json")["outings"].asJsonArray.map {it.asJsonObject["id"].asString})
            f.now=day.plusSeconds(12*3600+1800)
            assertEquals(AutomaticRunOutcome.exported,c.runIfDue())
            assertEquals(listOf("one","two"),f.json("track-2026-10-27.json")["outings"].asJsonArray.map {it.asJsonObject["id"].asString})
            assertEquals(f.now,c.snapshot().appendCursor)
            c.configure(f.config.copy(outings=false));assertNull(c.snapshot().appendCursor)
        }
    }
    @Test fun pendingOutingRetryRetainsInferenceAndWindowAcrossProcessRecreationAndPreferenceChanges()=runBlocking {
        fixture {f ->
            val rules=InferenceRules(Duration.ofMinutes(30),Duration.ZERO,2);f.inference=rules
            val first=f.coordinator {if(it==AutomaticExportCheckpoint.captured) throw CancellationException("synthetic interruption")}
            first.configure(f.config.copy(outings=true))
            interrupted {first.runIfDue()}
            val pending=first.snapshot().pending!!;assertTrue(pending.plan.outings);assertEquals(rules,pending.plan.inference)
            val captured=f.now;f.inference=null;f.now=day.plusSeconds(18*3600);f.pattern="changed-{date}"
            val restored=f.coordinator();assertEquals(rules,restored.snapshot().pending!!.plan.inference)
            assertEquals(AutomaticRunOutcome.exported,restored.retryPending())
            val outings=f.json("track-2026-10-27.json")["outings"].asJsonArray
            assertEquals(1,outings.size());assertTrue(outings.single().asJsonObject["isInferred"].asBoolean)
            assertEquals(2,outings.single().asJsonObject["pointCount"].asInt)
            assertEquals(captured,restored.snapshot().lastSuccess);assertEquals(captured,restored.snapshot().appendCursor)
            assertNull(f.document("changed-2026-10-27.json"))
        }
    }
    @Test fun legacyScheduleMigratesWithoutChangingSuccessCursorOrHistorySubject()=runBlocking {
        fixture {f ->
            val c=f.coordinator();c.configure(f.config);assertEquals(AutomaticRunOutcome.exported,c.runIfDue())
            val before=c.snapshot();val file=File(context.filesDir,"automatic-exports/state.json")
            val json=JsonParser.parseString(file.readText()).asJsonObject
            json.addProperty("version",1);json["settings"].asJsonObject.remove("outings");file.writeText(json.toString())
            val migrated=f.coordinator().snapshot()
            assertEquals(before,migrated);assertFalse(migrated.settings.outings)
            f.coordinator().configure(migrated.settings.copy(outings=true))
            assertNull(f.coordinator().snapshot().appendCursor)
            assertEquals(2,JsonParser.parseString(file.readText()).asJsonObject["version"].asInt)
        }
    }
    @Test fun legacyPendingHistoryTransactionResumesItsOriginalWindowWithNoInference()=runBlocking {
        fixture {f ->
            val first=f.coordinator {if(it==AutomaticExportCheckpoint.captured) throw CancellationException("synthetic interruption")}
            first.configure(f.config);interrupted {first.runIfDue()}
            val captured=f.now;val file=File(context.filesDir,"automatic-exports/state.json")
            val json=JsonParser.parseString(file.readText()).asJsonObject
            json.addProperty("version",1);json["settings"].asJsonObject.remove("outings")
            val pending=json["pending"].asJsonObject;pending.remove("inferenceEnabled")
            val selection=JsonParser.parseString(pending["selection"].asString).asJsonObject
            selection.addProperty("version",1);selection.remove("outings");pending.addProperty("selection",selection.toString());file.writeText(json.toString())
            f.now=day.plusSeconds(18*3600)
            val restored=f.coordinator();assertFalse(restored.snapshot().pending!!.plan.outings);assertNull(restored.snapshot().pending!!.plan.inference)
            assertEquals(AutomaticRunOutcome.exported,restored.retryPending());assertEquals(captured,restored.snapshot().lastSuccess)
            assertEquals(4,f.json("track-2026-10-27.json")["points"].asJsonArray.size())
        }
    }
    @Test fun corruptedCapturedOutingInferenceDoesNotSilentlyBroadenOrDeliver()=runBlocking {
        fixture {f ->
            f.inference=InferenceRules(minimumPoints=2)
            val first=f.coordinator {if(it==AutomaticExportCheckpoint.captured) throw CancellationException("synthetic interruption")}
            first.configure(f.config.copy(outings=true));interrupted {first.runIfDue()}
            val file=File(context.filesDir,"automatic-exports/state.json");val json=JsonParser.parseString(file.readText()).asJsonObject
            json["pending"].asJsonObject.addProperty("inferenceGap","PT-1S");file.writeText(json.toString())
            try {f.coordinator().snapshot();fail("Invalid rules must block recovery")} catch(_:IllegalArgumentException) {}
            assertNull(f.document("track-2026-10-27.json"))
        }
    }
}
