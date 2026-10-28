package com.bontecou.whereday.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class AutomationAccessTest {
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var directory:File
    private val original=AutomationIdentity("com.example.automation",12345,123L,setOf("a".repeat(64)))
    private var installed:AutomationIdentity?=original
    private lateinit var access:AutomationAccess
    @Before fun setup() {
        check(context.packageName=="com.bontecou.whereday.platform.test")
        directory=File(context.cacheDir,"automation-fixture-${UUID.randomUUID()}").apply {mkdirs()}
        access=AutomationAccess(directory) {name ->installed?.takeIf {it.packageName==name}}
    }
    @After fun cleanup() {directory.deleteRecursively()}
    private suspend inline fun <reified T:Throwable> fails(noinline operation:suspend ()->Unit) {
        try {operation();fail("Expected ${T::class.java.simpleName}")} catch(e:Throwable) {if(e !is T) throw e}
    }
    @Test fun defaultOffMissingCallerAndUnapprovedScopesNeverRunAnAction()=runBlocking {
        var called=0
        assertNull(access.approval())
        fails<AutomationNotAuthorized> {access.authorize(original.packageName,AutomationScope.summary) {called++}}
        access.approve(original,setOf(AutomationScope.summary))
        fails<AutomationNotAuthorized> {access.authorize(null,AutomationScope.summary) {called++}}
        fails<AutomationNotAuthorized> {access.authorize("com.other.client",AutomationScope.summary) {called++}}
        fails<AutomationNotAuthorized> {access.authorize(original.packageName,AutomationScope.visits) {called++}}
        assertEquals(0,called)
        assertEquals("ok",access.authorize(original.packageName,AutomationScope.summary) {"ok"})
    }
    @Test fun installedIdentityRequiresCurrentSignersUidAndInstallationTime()=runBlocking {
        access.approve(original,setOf(AutomationScope.summary))
        for(changed in listOf(original.copy(signers=setOf("b".repeat(64))),original.copy(uid=54321),original.copy(firstInstalled=124L),null)) {
            installed=changed
            fails<AutomationNotAuthorized> {access.authorize(original.packageName,AutomationScope.summary) {fail("Wrong identity ran")}}
        }
        installed=original
        assertTrue(access.authorize(original.packageName,AutomationScope.summary) {true})
    }
    @Test fun reviewedIdentityCannotApproveAnAppReplacedWhileConsentWasOpen()=runBlocking {
        installed=original.copy(signers=setOf("b".repeat(64)))
        fails<AutomationNotAuthorized> {access.approve(original,setOf(AutomationScope.visits))}
        assertFalse(File(directory,"approval.json").exists())
        access.revoke();assertNull(access.approval())
    }
    @Test fun approvalReopensStrictlyAndRevokePersistsAcrossInstances()=runBlocking {
        access.approve(original,setOf(AutomationScope.summary,AutomationScope.renameVisits))
        val saved=access.approval()!!
        val other=AutomationAccess(directory) {installed}
        assertEquals(saved,other.approval())
        assertEquals(saved.revision,other.authorize(original.packageName,AutomationScope.renameVisits) {it.revision})
        other.revoke()
        assertNull(access.approval())
        fails<AutomationNotAuthorized> {access.authorize(original.packageName,AutomationScope.summary) {fail("Revoked")}}
        access.approve(original,setOf(AutomationScope.summary))
        assertNotEquals(saved.revision,access.approval()!!.revision)
    }
    @Test fun corruptUnknownVersionFractionalVersionAndDuplicateKeysFailClosedUntilReset()=runBlocking {
        access.approve(original,setOf(AutomationScope.summary))
        val file=File(directory,"approval.json");val valid=file.readText()
        val variants=listOf("{",valid.replace("\"version\":1","\"version\":2"),valid.replace("\"version\":1","\"version\":1.0"),
            valid.replace("\"version\":1","\"version\":1,\"version\":1"),valid.replace("\"summary\"","\"unknownScope\""),valid.replace("\"uid\":12345","\"uid\":-1")," ".repeat(16385))
        for(raw in variants) {
            file.writeText(raw)
            fails<AutomationSettingsUnavailable> {access.authorize(original.packageName,AutomationScope.summary) {fail("Malformed approval")}}
        }
        access.revoke();assertNull(access.approval())
    }
    @Test fun revokeCancelsAndJoinsInFlightWorkAndFencesQueuedOldRequests()=runBlocking {supervisorScope {
        access.approve(original,setOf(AutomationScope.summary))
        val entered=CompletableDeferred<Unit>();var closed=false;var queuedRan=false
        val first=async {runCatching {access.authorize(original.packageName,AutomationScope.summary) {
            entered.complete(Unit)
            try {awaitCancellation()} finally {withContext(NonCancellable) {delay(40);closed=true}}
        }}}
        entered.await()
        val queued=async(start=CoroutineStart.UNDISPATCHED) {runCatching {access.authorize(original.packageName,AutomationScope.summary) {queuedRan=true}}}
        AutomationAccess(directory) {installed}.revoke()
        assertTrue(closed);assertFalse(queuedRan)
        assertTrue(first.await().exceptionOrNull() is CancellationException)
        assertTrue(queued.await().exceptionOrNull() is AutomationNotAuthorized)
        assertNull(access.approval())
    }}
    @Test fun consentScopesAreFrozenBeforeWaitingForAnOlderCancelledOperation()=runBlocking {supervisorScope {
        access.approve(original,setOf(AutomationScope.summary))
        val entered=CompletableDeferred<Unit>();val cancelling=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val old=async {runCatching {access.authorize(original.packageName,AutomationScope.summary) {
            entered.complete(Unit)
            try {awaitCancellation()} finally {withContext(NonCancellable) {cancelling.complete(Unit);release.await()}}
        }}}
        entered.await()
        val reviewed=mutableSetOf(AutomationScope.summary)
        val approval=async(start=CoroutineStart.UNDISPATCHED) {access.approve(original,reviewed)}
        cancelling.await();reviewed+=AutomationScope.exports;release.complete(Unit)
        approval.await();old.await()
        assertEquals(setOf(AutomationScope.summary),access.approval()!!.scopes)
    }}
    @Test fun failedSettingsWriteInhibitsTheOldGrantAndResetCanRecover()=runBlocking {
        access.approve(original,setOf(AutomationScope.summary))
        val backup=File(directory,"approval.json").readText()
        // Block AtomicFile's new-file path using a nonempty directory, only inside our fixture.
        val obstacle=File(directory,"approval.json.new").apply {mkdir()};File(obstacle,"fixture").writeText("block")
        fails<Exception> {access.revoke()}
        fails<AutomationNotAuthorized> {access.authorize(original.packageName,AutomationScope.summary) {fail("Old grant ran")}}
        assertEquals(backup,File(directory,"approval.json").readText())
        obstacle.deleteRecursively();access.revoke();assertNull(access.approval())
    }
    @Test fun packageManagerReadsActualTestPackageIdentityWithoutRequestExtras() {
        val identity=installedAutomationIdentity(context,context.packageName)!!
        assertEquals(context.packageName,identity.packageName)
        assertEquals(android.os.Process.myUid(),identity.uid)
        assertTrue(identity.signers.all {it.length==64})
        assertNull(installedAutomationIdentity(context,"com.example.not.installed.fixture"))
    }
}
