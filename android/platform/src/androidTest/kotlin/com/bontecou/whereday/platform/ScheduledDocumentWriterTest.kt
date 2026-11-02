package com.bontecou.whereday.platform

import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.export.*
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class ScheduledDocumentWriterTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private val tree=DocumentsContract.buildTreeDocumentUri("com.bontecou.whereday.export-test-documents","root")
    private val parent=DocumentsContract.buildDocumentUriUsingTree(tree,"root")
    private fun writer(checkpoint:(ScheduledDeliveryPhase)->Unit={})=ScheduledDocumentWriter(context,{},checkpoint)
    private suspend fun fixture(block:suspend (File)->Unit) {
        check(context.packageName!="com.bontecou.whereday")
        val folder=File(context.cacheDir,"replacement-fixture-${UUID.randomUUID()}").apply {mkdirs()}
        try {
            ExportTestDocuments.rejectDeletes.set(false)
            writer().clear()
            context.grantUriPermission(context.packageName,tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_PREFIX_URI_PERMISSION)
            block(folder)
        } finally {
            ExportTestDocuments.rejectDeletes.set(false)
            writer().clear();folder.deleteRecursively()
            context.revokeUriPermission(tree,Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            File(context.cacheDir,"synthetic-export-documents").deleteRecursively()
        }
    }
    private fun fingerprint(text:String):DestinationFingerprint {
        val bytes=text.toByteArray(Charsets.UTF_8)
        return DestinationFingerprint(bytes.size.toLong(),MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") {"%02x".format(it.toInt() and 255)})
    }
    private fun payload(folder:File,text:String,name:String="day.json"):PreparedExportFile {
        val hash=fingerprint(text);val file=File(folder,name).apply {writeText(text)}
        return PreparedExportFile(file,name,ExportFormat.json,ExportCounts(0,1),hash.bytes,hash.sha256)
    }
    private fun create(name:String,text:String):Uri=DocumentsContract.createDocument(context.contentResolver,parent,"application/json",name)!!.also {write(it,text)}
    private fun write(uri:Uri,text:String) {context.contentResolver.openOutputStream(uri,"wt")!!.use {it.write(text.toByteArray())}}
    private fun read(uri:Uri)=context.contentResolver.openInputStream(uri)!!.bufferedReader().use {it.readText()}
    private suspend fun interrupted(block:suspend ()->Unit) {try {block();fail("Expected a simulated interruption")} catch(_:CancellationException) {}}

    @Test fun verifiedBackupPrecedesReplacementAndCompletedReceiptNeverRewrites()=runBlocking {
        fixture {folder ->
            val old="original good export";val fresh="new verified export";val uri=create("day.json",old);val id=UUID.randomUUID().toString()
            val first=writer()
            first.start(id,payload(folder,fresh),parent,existing=uri,expected=fingerprint(old))
            assertTrue(File(folder,"day.json").delete()) // Durable payload survives cache eviction.
            val result=first.resume(id)
            assertEquals(ScheduledDeliveryPhase.completed,result.phase);assertNull(result.backup);assertEquals(fresh,read(uri))
            assertFalse(File(context.filesDir,"scheduled-delivery/$id/payload").exists())
            val opens=ExportTestDocuments.outputOpens.get()
            val denied=ScheduledDocumentWriter(context,{error("No paid access")})
            assertEquals(ScheduledDeliveryPhase.completed,denied.resume(id).phase)
            assertEquals(opens,ExportTestDocuments.outputOpens.get())
        }
    }
    @Test fun capturedPartialWriteResumesAfterCoordinatorRecreation()=runBlocking {
        fixture {folder ->
            val old="original good export";val fresh="new verified export with more bytes";val uri=create("day.json",old);val id=UUID.randomUUID().toString()
            val first=writer {if(it==ScheduledDeliveryPhase.writing) throw CancellationException("synthetic death")}
            first.start(id,payload(folder,fresh),parent,existing=uri,expected=fingerprint(old))
            interrupted {first.resume(id)}
            val saved=writer().state(id)!!
            assertEquals(ScheduledDeliveryPhase.writing,saved.phase);assertEquals(old,read(Uri.parse(saved.backup)))
            write(uri,fresh.take(7))
            assertEquals(ScheduledDeliveryPhase.completed,writer().resume(id).phase)
            assertEquals(fresh,read(uri))
        }
    }
    @Test fun verifiedCheckpointCommitsOnRecoveryWithoutAnotherWrite()=runBlocking {
        fixture {folder ->
            val id=UUID.randomUUID().toString();val fresh="complete bytes"
            val first=writer {if(it==ScheduledDeliveryPhase.verified) throw CancellationException("synthetic death")}
            first.start(id,payload(folder,fresh),parent)
            interrupted {first.resume(id)}
            val saved=first.state(id)!!;assertEquals(ScheduledDeliveryPhase.verified,saved.phase)
            assertEquals(fresh,read(Uri.parse(saved.destination)))
            val opens=ExportTestDocuments.outputOpens.get()
            assertEquals(ScheduledDeliveryPhase.completed,writer().resume(id).phase)
            assertEquals(opens,ExportTestDocuments.outputOpens.get())
        }
    }
    @Test fun changedDestinationStopsRecoveryAndCancellationPreservesBackup()=runBlocking {
        fixture {folder ->
            val old="old export";val uri=create("day.json",old);val id=UUID.randomUUID().toString()
            val first=writer {if(it==ScheduledDeliveryPhase.backedUp) throw CancellationException("synthetic death")}
            first.start(id,payload(folder,"new export"),parent,existing=uri,expected=fingerprint(old));interrupted {first.resume(id)}
            val backup=Uri.parse(first.state(id)!!.backup);assertEquals(old,read(backup))
            write(uri,"foreign edit")
            try {writer().resume(id);fail()} catch(_:IllegalStateException) {}
            assertEquals("foreign edit",read(uri));assertEquals(old,read(backup))
            writer().cancel(id)
            try {writer().resume(id);fail()} catch(_:IllegalStateException) {}
            assertEquals(ScheduledDeliveryPhase.canceled,writer().state(id)!!.phase)
            assertEquals("foreign edit",read(uri));assertEquals(old,read(backup))
        }
    }
    @Test fun changedBackupIsNeverDeletedOrUsedToApproveReplacement()=runBlocking {
        fixture {folder ->
            val old="old export";val uri=create("day.json",old);val id=UUID.randomUUID().toString()
            val first=writer {if(it==ScheduledDeliveryPhase.backedUp) throw CancellationException("synthetic death")}
            first.start(id,payload(folder,"new export"),parent,existing=uri,expected=fingerprint(old));interrupted {first.resume(id)}
            val backup=Uri.parse(first.state(id)!!.backup);write(backup,"foreign backup edit")
            try {writer().resume(id);fail()} catch(_:IllegalStateException) {}
            assertEquals(old,read(uri));assertEquals("foreign backup edit",read(backup))
            writer().clear()
            assertNull(writer().state(id));assertEquals("foreign backup edit",read(backup))
        }
    }
    @Test fun corruptProviderIsNeverMarkedSuccessfulAndLostAccessStopsWrites()=runBlocking {
        fixture {folder ->
            val id=UUID.randomUUID().toString();val file=payload(folder,"new export","corrupt.json")
            val first=writer();first.start(id,file,parent)
            try {first.resume(id);fail()} catch(_:IllegalStateException) {}
            assertEquals(ScheduledDeliveryPhase.writing,first.state(id)!!.phase)
            assertNotNull(first.state(id)!!.error)
            val opens=ExportTestDocuments.outputOpens.get()
            val denied=ScheduledDocumentWriter(context,{error("No paid access")})
            try {denied.resume(id);fail()} catch(_:IllegalStateException) {}
            assertEquals(opens,ExportTestDocuments.outputOpens.get())
            val another=UUID.randomUUID().toString()
            try {denied.start(another,file,parent);fail()} catch(_:IllegalStateException) {}
            assertNull(first.state(another))
        }
    }
    @Test fun clearInvalidatesActiveWriteAndPreventsLateResume()=runBlocking {
        fixture {folder ->
            val old="old export";val uri=create("day.json",old);val id=UUID.randomUUID().toString()
            lateinit var first:ScheduledDocumentWriter
            first=writer {if(it==ScheduledDeliveryPhase.writing) first.invalidate()}
            first.start(id,payload(folder,"new export"),parent,existing=uri,expected=fingerprint(old))
            interrupted {first.resume(id)}
            val backup=Uri.parse(first.state(id)!!.backup)
            assertEquals(old,read(uri));assertEquals(old,read(backup))
            first.clear()
            try {first.resume(id);fail()} catch(_:IllegalStateException) {}
            assertNull(first.state(id));assertEquals(old,read(uri));assertEquals(old,read(backup))
        }
    }
    @Test fun perDestinationLockRejectsAnotherPendingRunAndStaleFingerprint()=runBlocking {
        fixture {folder ->
            val old="old export";val uri=create("day.json",old);val file=payload(folder,"new export");val id=UUID.randomUUID().toString()
            val first=writer();first.start(id,file,parent,existing=uri,expected=fingerprint(old))
            try {writer().start(UUID.randomUUID().toString(),file,parent,existing=uri,expected=fingerprint(old));fail()} catch(_:IllegalStateException) {}
            first.cancel(id)
            val stale=UUID.randomUUID().toString()
            try {writer().start(stale,file,parent,existing=uri,expected=fingerprint("wrong old bytes"));fail()} catch(_:IllegalStateException) {}
            assertNull(first.state(stale));assertEquals(old,read(uri))
        }
    }
    @Test fun cleanupFailureDoesNotDowngradeCompletionOrDeleteChangedBackup()=runBlocking {
        fixture {folder ->
            val old="old export";val uri=create("day.json",old);val id=UUID.randomUUID().toString()
            val first=writer();first.start(id,payload(folder,"new export"),parent,existing=uri,expected=fingerprint(old))
            ExportTestDocuments.rejectDeletes.set(true)
            val result=first.resume(id)
            assertEquals(ScheduledDeliveryPhase.completed,result.phase);assertNotNull(result.backup)
            val backup=Uri.parse(result.backup);assertEquals(old,read(backup))
            write(backup,"foreign edit after completion");ExportTestDocuments.rejectDeletes.set(false)
            val opens=ExportTestDocuments.outputOpens.get()
            assertEquals(ScheduledDeliveryPhase.completed,writer().resume(id).phase)
            assertEquals(opens,ExportTestDocuments.outputOpens.get())
            assertEquals("foreign edit after completion",read(backup));assertEquals("new export",read(uri))
        }
    }
    @Test fun newDocumentCreatedBeforePhaseCommitCanBeResumedWithoutCreatingAnother()=runBlocking {
        fixture {folder ->
            val id=UUID.randomUUID().toString()
            val first=writer {if(it==ScheduledDeliveryPhase.writing) throw CancellationException("synthetic death")}
            first.start(id,payload(folder,"new export"),parent);interrupted {first.resume(id)}
            val saved=first.state(id)!!;val uri=Uri.parse(saved.destination);assertEquals("",read(uri))
            // Simulate death after committing the created URI, before committing the writing phase.
            val receipt=File(context.filesDir,"scheduled-delivery/$id/state.json")
            val json=com.google.gson.JsonParser.parseString(receipt.readText()).asJsonObject
            json.addProperty("phase",ScheduledDeliveryPhase.backedUp.name);receipt.writeText(json.toString())
            assertEquals(ScheduledDeliveryPhase.completed,writer().resume(id).phase)
            assertEquals("new export",read(uri));assertEquals(saved.destination,writer().state(id)!!.destination)
        }
    }
}
