package com.bontecou.whereday.platform

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.time.Instant
import java.util.UUID

class ExportRecoveryTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val tree=DocumentsContract.buildTreeDocumentUri("com.bontecou.whereday.export-test-documents","root")
    private val root=DocumentsContract.buildDocumentUriUsingTree(tree,"root")
    private suspend fun fixture(block:suspend (HistoryDatabase,ExportFiles,ExportJournal)->Unit) {
        check(context.packageName!="com.bontecou.whereday")
        val name="recovery-synthetic-${UUID.randomUUID()}.db";val db=HistoryDatabase.create(context,name)
        val files=ExportFiles(context,db) {};val journal=ExportJournal(context,File(context.cacheDir,"exports"))
        try {
            context.grantUriPermission(context.packageName,tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            db.history().put(PointRow(TrackPoint(id="synthetic",coordinate=Coordinate(18.0,-66.0),time=Instant.parse("2026-10-27T00:00:00Z"),accuracy=5.0),null))
            ExportTestDocuments.outputOpens.set(0)
            block(db,files,journal)
        } finally {files.clearCache();db.close();context.deleteDatabase(name);context.revokeUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION);File(context.cacheDir,"synthetic-export-documents").deleteRecursively()}
    }
    private fun create(name:String)=requireNotNull(DocumentsContract.createDocument(context.contentResolver,root,"application/json",name))
    private fun read(uri:Uri)=context.contentResolver.openInputStream(uri)!!.use {it.readBytes()}
    private fun put(uri:Uri,bytes:ByteArray) {context.contentResolver.openOutputStream(uri,"wt")!!.use {it.write(bytes)}}
    private suspend fun prepare(files:ExportFiles)=files.prepare(ExportFileRequest(setOf(ExportFormat.json)))

    @Test fun awaitingPickerSurvivesNewCoordinatorAndDuplicateResultNeverRewritesCompletedDocument()=runBlocking {
        fixture {db,files,_ ->
            val prepared=prepare(files);val expected=prepared.files.single().file.readBytes();val pending=files.beginPicker(prepared)
            val restarted=ExportFiles(context,db) {}
            assertEquals(pending,restarted.pickerState())
            val intent=restarted.pickerIntent(pending.id)
            assertEquals(Intent.ACTION_CREATE_DOCUMENT,intent.action);assertTrue(intent.flags and Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION!=0)
            val uri=create("restored.json")
            assertEquals(ExportPickerPhase.completed,restarted.pickerResult(pending.id,uri,0)?.phase)
            assertArrayEquals(expected,read(uri));assertFalse(prepared.files.single().file.exists())
            val writes=ExportTestDocuments.outputOpens.get()
            assertEquals(ExportPickerPhase.completed,ExportFiles(context,db) {}.pickerResult(pending.id,uri,0)?.phase)
            assertEquals(writes,ExportTestDocuments.outputOpens.get())
            restarted.clearCache();assertNull(restarted.pickerState());assertArrayEquals(expected,read(uri))
        }
    }
    @Test fun partialOwnedPrefixCanResumeAndAlreadyVerifiedBytesAreNotTruncatedAfterRestart()=runBlocking {
        fixture {db,files,journal ->
            for(full in listOf(false,true)) {
                val prepared=prepare(files);val expected=prepared.files.single().file.readBytes();val pending=files.beginPicker(prepared)
                val uri=create(if(full) "full-before-receipt.json" else "partial-before-receipt.json")
                put(uri,if(full) expected else expected.copyOf(73))
                journal.put(pending.copy(phase=ExportPickerPhase.writing,destination=uri.toString())) // Process-death checkpoint.
                val before=ExportTestDocuments.outputOpens.get()
                assertEquals(ExportPickerPhase.completed,ExportFiles(context,db) {}.resumePicker(pending.id).phase)
                assertArrayEquals(expected,read(uri))
                assertEquals(before+if(full) 0 else 1,ExportTestDocuments.outputOpens.get())
            }
        }
    }
    @Test fun changedDestinationAndTamperedStagingArePreservedAndNeverReportedSuccessful()=runBlocking {
        fixture {db,files,journal ->
            val prepared=prepare(files);val expected=prepared.files.single().file.readBytes();val pending=files.beginPicker(prepared)
            val changed=expected.copyOf().also {it[0]='['.code.toByte()};val uri=create("changed.json");put(uri,changed)
            journal.put(pending.copy(phase=ExportPickerPhase.writing,destination=uri.toString()))
            val restarted=ExportFiles(context,db) {};val before=ExportTestDocuments.outputOpens.get()
            try {restarted.resumePicker(pending.id);fail()} catch(e:ExportDeliveryException) {assertEquals(ExportDeliveryNotice.destinationChanged,e.notice)}
            assertEquals(ExportPickerPhase.failed,restarted.pickerState()?.phase)
            val controller=ExportPickerController(ExportFiles(context,db) {})
            try {assertEquals(ExportDeliveryNotice.destinationChanged,withTimeout(5000) {controller.state.first {it.loaded && !it.busy}}.notice)} finally {controller.close()}
            assertEquals(before,ExportTestDocuments.outputOpens.get());assertArrayEquals(changed,read(uri))
            prepared.files.single().file.writeBytes(changed)
            try {restarted.pickerIntent(pending.id);fail()} catch(_:IllegalStateException) {}
            assertEquals(before,ExportTestDocuments.outputOpens.get());assertArrayEquals(changed,read(uri))
            restarted.cancelPicker(pending.id);assertEquals(ExportPickerPhase.canceled,restarted.pickerState()?.phase);assertArrayEquals(changed,read(uri))
        }
    }
    @Test fun authorizationIsRecheckedAfterPickerReturnAndCanceledRequestsCannotWriteLater()=runBlocking {
        fixture {db,files,_ ->
            val prepared=prepare(files);val pending=files.beginPicker(prepared);val uri=create("access-revoked.json")
            val denied=ExportFiles(context,db) {error("Verified lifetime export access is required.")}
            try {denied.pickerResult(pending.id,uri,0);fail()} catch(_:IllegalStateException) {}
            assertEquals(0,ExportTestDocuments.outputOpens.get());assertEquals(ExportPickerPhase.failed,files.pickerState()?.phase)
            assertEquals(ExportPickerPhase.completed,files.resumePicker(pending.id).phase)
            val another=prepare(files);val canceled=files.beginPicker(another)
            assertNull(files.pickerResult(canceled.id,null,0));assertEquals(ExportPickerPhase.canceled,files.pickerState()?.phase)
            val before=ExportTestDocuments.outputOpens.get()
            try {files.pickerResult(canceled.id,create("late-result.json"),0);fail()} catch(_:IllegalStateException) {}
            assertEquals(before,ExportTestDocuments.outputOpens.get());assertFalse(another.files.single().file.exists())
        }
    }
    @Test fun forgedManifestCannotEscapePreparedCache()=runBlocking {
        fixture {db,files,_ ->
            val prepared=prepare(files);val pending=files.beginPicker(prepared)
            val manifest=File(context.filesDir,"export-journal/bundles/${prepared.id}.json")
            val json=com.google.gson.JsonParser.parseString(manifest.readText()).asJsonObject
            json["files"].asJsonArray[0].asJsonObject.addProperty("path","../outside.json")
            manifest.writeText(json.toString())
            try {ExportFiles(context,db) {}.pickerIntent(pending.id);fail()} catch(_:IllegalArgumentException) {}
            assertEquals(0,ExportTestDocuments.outputOpens.get())
            files.cancelPicker(pending.id)
        }
    }
    @Test fun coldPickerCallbackResolvesDurableRequestWithoutCompositionState()=runBlocking {
        fixture {db,files,_ ->
            val prepared=prepare(files);val expected=prepared.files.single().file.readBytes();val pending=files.beginPicker(prepared)
            val uri=create("cold-callback.json")
            val controller=ExportPickerController(ExportFiles(context,db) {})
            try {
                controller.result(null,uri,0)
                val completed=withTimeout(5000) {controller.state.first {it.request?.phase==ExportPickerPhase.completed && !it.busy}}
                assertEquals(pending.id,completed.request?.id);assertArrayEquals(expected,read(uri))
            } finally {controller.close()}
        }
    }

    @Test fun legacyOrUnknownFailureNoticeNeverSurfacesRawErrorOrInventsSuccess()=runBlocking {
        fixture {db,files,journal ->
            val prepared=prepare(files);val pending=files.beginPicker(prepared)
            for(code in listOf(null,"future_error","saved","canceled")) {
                journal.put(pending.copy(phase=ExportPickerPhase.failed,error="private-provider://sensitive-token"))
                val receipt=File(context.filesDir,"export-journal/pickers/${pending.id}.json")
                val json=com.google.gson.JsonParser.parseString(receipt.readText()).asJsonObject
                code?.let {json.addProperty("notice",it)};receipt.writeText(json.toString())
                val controller=ExportPickerController(ExportFiles(context,db) {})
                try {
                    val state=withTimeout(5000) {controller.state.first {it.loaded && !it.busy}}
                    assertEquals(ExportPickerPhase.failed,state.request?.phase)
                    assertEquals(ExportDeliveryNotice.failed,state.notice)
                    assertFalse(state.message.orEmpty().contains("sensitive-token"))
                } finally {controller.close()}
            }
        }
    }
}
