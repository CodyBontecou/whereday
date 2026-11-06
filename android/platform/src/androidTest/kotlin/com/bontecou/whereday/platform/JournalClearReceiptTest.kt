package com.bontecou.whereday.platform

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.security.MessageDigest
import java.util.UUID

class JournalClearReceiptTest {
    private lateinit var root:File
    private lateinit var store:AndroidJournalClearReceiptStore
    @Before fun setup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.platform.test")
        root=File(context.cacheDir,"clear-receipt-fixture-${UUID.randomUUID()}").apply {mkdirs()}
        store=AndroidJournalClearReceiptStore(root)
    }
    @After fun cleanup() {root.deleteRecursively()}
    @Test fun pendingAndCompletedReceiptsSurviveNewStoreInstances() {
        val id=UUID.randomUUID().toString();assertNull(store.read());store.begin(id)
        assertEquals(JournalClearReceipt(id,false),AndroidJournalClearReceiptStore(root).read())
        store.complete(id);assertTrue(File(root,"receipt").exists())
        assertEquals(JournalClearReceipt(id,true),AndroidJournalClearReceiptStore(root).read())
        store.complete(id);assertEquals(JournalClearReceipt(id,true),store.read())
    }
    @Test fun retainedCompletionTombstoneNeverReplaysDeletionOnANewController()=runBlocking {
        val id=UUID.randomUUID().toString();store.begin(id);store.complete(id);var calls=0
        val controller=JournalClearController(AndroidJournalClearReceiptStore(root),{calls++})
        try {controller.awaitReady();assertEquals(0,calls);assertFalse(controller.state.value.blocked)} finally {controller.close()}
    }
    @Test fun abandonedNewFileCannotReplaceACommittedPendingReceipt() {
        val id=UUID.randomUUID().toString();store.begin(id);File(root,"receipt.new").writeText("partial")
        assertEquals(JournalClearReceipt(id,false),AndroidJournalClearReceiptStore(root).read())
    }
    @Test fun onlyAnUncommittedFileFailsClosedRatherThanInventingConsent() {
        File(root,"receipt.new").writeText("partial")
        assertTrue(runCatching {store.read()}.isFailure)
        assertTrue(File(root,"receipt.new").exists())
        assertTrue(runCatching {AndroidJournalClearReceiptStore(root).read()}.isFailure)
    }
    @Test fun checksumTruncationUnknownVersionAndOversizedStateFailClosed() {
        val id=UUID.randomUUID().toString();store.begin(id);val original=File(root,"receipt").readText()
        for(text in listOf(original.dropLast(2),original.replace("pending","complete"),"x".repeat(257),encoded("whereday-clear-v2",id,"pending"),encoded("whereday-clear-v1","not-a-uuid","complete"))) {
            File(root,"receipt").writeText(text);assertTrue(runCatching {store.read()}.isFailure)
        }
    }
    @Test fun stagedRequestAfterAnOlderCompletionStaysFencedAcrossRepeatedReads() {
        val old=UUID.randomUUID().toString();store.begin(old);store.complete(old)
        File(root,"receipt.new").writeText("uncommitted newer request")
        repeat(2) {assertTrue(runCatching {AndroidJournalClearReceiptStore(root).read()}.isFailure)}
        assertTrue(File(root,"receipt.new").exists())
        val approved=UUID.randomUUID().toString();store.begin(approved)
        assertEquals(JournalClearReceipt(approved,false),store.read())
    }
    @Test fun staleCompletionCannotCompleteANewerExplicitClear() {
        val first=UUID.randomUUID().toString();val next=UUID.randomUUID().toString()
        store.begin(first);store.begin(next)
        assertTrue(runCatching {store.complete(first)}.isFailure)
        assertEquals(JournalClearReceipt(next,false),store.read())
    }
    @Test fun aFileInPlaceOfTheDirectoryCannotProduceACommittedIntent() {
        root.deleteRecursively();root.writeText("synthetic directory obstruction")
        assertTrue(runCatching {store.read()}.isFailure)
        assertTrue(runCatching {store.begin(UUID.randomUUID().toString())}.isFailure)
    }
    @Test fun anUnreplaceableBaseCannotBeReportedAsACommittedIntent() {
        File(root,"receipt").mkdirs()
        assertTrue(runCatching {store.read()}.isFailure)
        assertTrue(runCatching {store.begin(UUID.randomUUID().toString())}.isFailure)
    }
    @Test fun legacyAtomicBackupRetainsTheKnownPendingReceipt() {
        val id=UUID.randomUUID().toString();store.begin(id)
        File(root,"receipt").copyTo(File(root,"receipt.bak"));File(root,"receipt").delete()
        assertEquals(JournalClearReceipt(id,false),AndroidJournalClearReceiptStore(root).read())
    }
    private fun encoded(header:String,id:String,phase:String):String {
        val body="$header\n$id\n$phase\n"
        val digest=MessageDigest.getInstance("SHA-256").digest(body.toByteArray()).joinToString("") {"%02x".format(it.toInt() and 255)}
        return body+digest+"\n"
    }
}
