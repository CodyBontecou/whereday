package com.bontecou.whereday.platform

import android.os.ParcelFileDescriptor
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AutomationResultsTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root:File
    private lateinit var directory:File
    private lateinit var access:AutomationAccess
    private lateinit var results:AutomationResults
    private val identity=AutomationIdentity("com.example.automation",12345,123L,setOf("a".repeat(64)))
    private var installed:AutomationIdentity?=identity
    private var wall=10_000L;private var elapsed=1_000L;private var boot=10
    private fun store()=AutomationResults(directory,access,{wall},{elapsed},{boot},1000)
    @Before fun setup()=runBlocking {
        check(context.packageName=="com.bontecou.whereday.platform.test")
        root=File(context.cacheDir,"automation-results-fixture-${UUID.randomUUID()}").apply {mkdirs()}
        directory=File(root,"results")
        access=AutomationAccess(File(root,"access")) {installed}
        access.approve(identity,setOf(AutomationScope.summary,AutomationScope.visits))
        results=store()
    }
    @After fun cleanup() {root.deleteRecursively()}
    private suspend fun create(scope:AutomationScope=AutomationScope.summary)=results.create(identity.packageName,scope,"result.json","application/json") {it.write("{\"synthetic\":true}".toByteArray())}
    private suspend inline fun <reified T:Throwable> fails(noinline operation:suspend ()->Unit) {
        try {operation();fail("Expected ${T::class.java.simpleName}")} catch(e:Throwable) {if(e !is T) throw e}
    }
    @Test fun immutableResultReopensThroughTheSameApprovalAndVerifiesItsBytes()=runBlocking {
        val result=create()
        assertEquals(result,store().info(identity.packageName,result.id))
        assertEquals(18L,result.bytes)
        val text=ParcelFileDescriptor.AutoCloseInputStream(store().open(identity.packageName,result.id)).bufferedReader().use {it.readText()}
        assertEquals("{\"synthetic\":true}",text)
        assertEquals(11_000L,result.expiresAt)
    }
    @Test fun unauthorizedScopeAndCallerCannotCreateOrReadAndRevocationInvalidatesOldFiles()=runBlocking {
        var wrote=false
        fails<AutomationNotAuthorized> {results.create(null,AutomationScope.summary,"x.json","application/json") {wrote=true}}
        fails<AutomationNotAuthorized> {results.create(identity.packageName,AutomationScope.exports,"x.json","application/json") {wrote=true}}
        assertFalse(wrote)
        val result=create(AutomationScope.visits)
        fails<AutomationNotAuthorized> {results.open("com.other.client",result.id);Unit}
        access.revoke()
        fails<AutomationNotAuthorized> {results.open(identity.packageName,result.id);Unit}
        access.approve(identity,setOf(AutomationScope.summary,AutomationScope.visits))
        fails<AutomationResultUnavailable> {results.open(identity.packageName,result.id);Unit}
    }
    @Test fun expirationStartsWhenAStreamingWriteFinishesRatherThanWhenItBegins()=runBlocking {
        val result=results.create(identity.packageName,AutomationScope.summary,"result.json","application/json") {
            it.write("{}".toByteArray());wall+=2000;elapsed+=2000
        }
        assertEquals(13_000L,result.expiresAt)
        assertEquals(result,results.info(identity.packageName,result.id))
    }
    @Test fun changedPackageCannotReadPreviouslyGrantedResult()=runBlocking {
        val result=create()
        installed=identity.copy(firstInstalled=456L)
        fails<AutomationNotAuthorized> {results.open(identity.packageName,result.id);Unit}
    }
    @Test fun expiryBootChangeAndEitherClockRollbackOrDeadlineDenyAccess()=runBlocking {
        val result=create()
        for(change in listOf<()->Unit>({wall=11_000},{elapsed=2_000},{wall=9_999},{elapsed=999},{boot=11})) {
            wall=10_000;elapsed=1_000;boot=10;change()
            fails<AutomationResultUnavailable> {results.info(identity.packageName,result.id);Unit}
        }
        wall=10_999;elapsed=1_999;boot=10
        assertEquals(result,results.info(identity.packageName,result.id))
    }
    @Test fun modifiedTruncatedAndMalformedResultFilesFailClosed()=runBlocking {
        val result=create();val body=File(directory,"${result.id}.body");val original=body.readBytes()
        body.writeBytes(original.copyOf().apply {this[3]='X'.code.toByte()})
        fails<AutomationResultUnavailable> {results.open(identity.packageName,result.id);Unit}
        body.writeText("short")
        fails<AutomationResultUnavailable> {results.info(identity.packageName,result.id);Unit}
        body.writeBytes(original)
        val metadata=File(directory,"${result.id}.json")
        metadata.writeText(metadata.readText().replace("\"version\":1","\"version\":1,\"version\":1"))
        fails<AutomationResultUnavailable> {results.open(identity.packageName,result.id);Unit}
        fails<AutomationResultUnavailable> {results.open(identity.packageName,"../access/approval");Unit}
    }
    @Test fun failedAndCancelledWritersLeaveNoBodyOrMetadata()=runBlocking {supervisorScope {
        fails<IllegalStateException> {results.create(identity.packageName,AutomationScope.summary,"result.json","application/json") {
            it.write("partial".toByteArray());error("fixture write failure")
        };Unit}
        assertTrue(directory.listFiles().orEmpty().isEmpty())
        val entered=CompletableDeferred<Unit>()
        val pending=async {runCatching {results.create(identity.packageName,AutomationScope.summary,"result.json","application/json") {
            it.write("partial".toByteArray());entered.complete(Unit);awaitCancellation()
        }}}
        entered.await();access.revoke()
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }}
    @Test fun cleanupRemovesOnlyOwnedResultsAndPreservesForeignFiles()=runBlocking {
        val first=create()
        val foreign=File(directory,"leave-this-fixture.txt").apply {writeText("foreign")}
        val orphan=File(directory,"${UUID.randomUUID()}.part").apply {writeText("partial")}
        wall+=1000;elapsed+=1000
        val second=create()
        assertFalse(File(directory,"${first.id}.body").exists());assertFalse(orphan.exists());assertTrue(foreign.exists())
        access.revoke();results.clear()
        assertFalse(File(directory,"${second.id}.body").exists());assertTrue(foreign.exists())
    }
    @Test fun productionBootCountCanBoundARealPrivateResult()=runBlocking {
        val live=AutomationResults(context,access)
        val result=live.create(identity.packageName,AutomationScope.summary,"fixture.json","application/json") {it.write("{}".toByteArray())}
        try {assertEquals(2L,live.info(identity.packageName,result.id).bytes)} finally {live.clear()}
    }
    @Test fun grantsRequireTheOriginalRecipientAndApprovalRevision()=runBlocking {
        val granted=mutableListOf<Pair<String,String>>()
        results=AutomationResults(directory,access,{wall},{elapsed},{boot},1000,grantResult={recipient,id ->granted.add(recipient to id)})
        val result=create()
        fails<AutomationNotAuthorized> {results.grantRead("com.other.client",result.id);Unit}
        assertTrue(granted.isEmpty())
        assertEquals(result,results.grantRead(identity.packageName,result.id))
        assertEquals(listOf(identity.packageName to result.id),granted)
        access.approve(identity,setOf(AutomationScope.summary))
        fails<AutomationResultUnavailable> {results.grantRead(identity.packageName,result.id);Unit}
        assertEquals(1,granted.size)
    }
    @Test fun expirySweepAndClearRevokeOnlyOwnedResultUris()=runBlocking {
        val revoked=mutableSetOf<String>()
        results=AutomationResults(directory,access,{wall},{elapsed},{boot},1000,revokeResult={revoked.add(it)})
        val first=create()
        val foreign=File(directory,"unrelated.json").apply {writeText("keep")}
        wall+=1000;elapsed+=1000
        val second=create()
        assertEquals(setOf(first.id),revoked)
        results.clear()
        assertEquals(setOf(first.id,second.id),revoked)
        assertEquals("keep",foreign.readText())
        assertEquals(listOf(foreign),directory.listFiles()!!.toList())
    }
    @Test fun failedGrantCleanupRetainsItsReceiptForExplicitRetry()=runBlocking {
        var failCleanup=true
        results=AutomationResults(directory,access,{wall},{elapsed},{boot},1000,revokeResult={check(!failCleanup)})
        val result=create()
        access.revoke()
        fails<IllegalStateException> {results.clear();Unit}
        assertTrue(File(directory,"${result.id}.json").exists())
        fails<AutomationNotAuthorized> {results.open(identity.packageName,result.id);Unit}
        failCleanup=false;results.clear()
        assertTrue(directory.listFiles()!!.isEmpty())
    }
}
