package com.bontecou.whereday.platform

import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

class AutomationControllerTest {
    private lateinit var root:File
    private lateinit var access:AutomationAccess
    private lateinit var results:AutomationResults
    private lateinit var controller:AutomationController
    private val identity=AutomationIdentity("com.example.client",1234,1L,setOf("a".repeat(64)))
    @Volatile private var installed:AutomationIdentity?=identity
    @Volatile private var resolver:(()->AutomationIdentity?)?=null
    @Before fun setup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.platform.test")
        root=File(context.cacheDir,"automation-controller-fixture-${UUID.randomUUID()}").apply {mkdirs()}
        access=AutomationAccess(File(root,"access")) {val hook=resolver;if(hook!=null) hook() else installed}
        results=AutomationResults(File(root,"results"),access,{10000},{1000},{1})
        controller=AutomationController(access,results,identity.packageName)
    }
    @After fun cleanup():Unit=runBlocking {controller.close();root.deleteRecursively();Unit}
    private suspend fun loaded()=withTimeout(5000) {controller.state.first {it.loaded && !it.busy}}
    @Test fun anOlderReloadCannotPublishOverAnAcceptedConsentRevision():Unit=runBlocking {
        loaded()
        val entered=java.util.concurrent.CountDownLatch(1);val release=java.util.concurrent.CountDownLatch(1)
        val seen=java.util.concurrent.ConcurrentLinkedQueue<AutomationUiState>()
        val observer=launch(Dispatchers.Unconfined,start=CoroutineStart.UNDISPATCHED) {controller.state.collect {seen.add(it)}}
        try {
            resolver={entered.countDown();check(release.await(5,java.util.concurrent.TimeUnit.SECONDS));identity}
            controller.reload();check(entered.await(5,java.util.concurrent.TimeUnit.SECONDS))
            val before=seen.size
            controller.approve(identity,setOf(AutomationScope.summary))
            val accepted=controller.state.value.consentRevision
            assertTrue(controller.state.value.busy);assertTrue(accepted>0)
            resolver=null;release.countDown()
            withTimeout(5000) {controller.state.first {it.notice==AutomationNotice.saved && !it.busy}}
            assertTrue(seen.toList().drop(before).all {it.consentRevision>=accepted})
            assertEquals(setOf(AutomationScope.summary),access.approval()!!.scopes)
        } finally {resolver=null;release.countDown();observer.cancelAndJoin()}
    }
    @Test fun changedInstallationDeniesSaveAndExplicitDisablePublishesANewResetRevision():Unit=runBlocking {
        loaded();controller.approve(identity,setOf(AutomationScope.summary))
        val saved=withTimeout(5000) {controller.state.first {it.notice==AutomationNotice.saved && !it.busy}}
        installed=identity.copy(firstInstalled=2)
        controller.approve(identity,setOf(AutomationScope.visits))
        val failed=withTimeout(5000) {controller.state.first {it.failure==AutomationFailure.save && !it.busy}}
        assertTrue(failed.consentRevision>saved.consentRevision);assertEquals(saved.approval,failed.approval)
        try {access.authorize(identity.packageName,AutomationScope.summary) {};fail("Changed installation retained access")}
        catch(_:AutomationNotAuthorized) {}
        controller.disable()
        val reset=withTimeout(5000) {controller.state.first {it.notice==AutomationNotice.disabled && !it.busy}}
        assertTrue(reset.resetRevision>saved.resetRevision);assertTrue(reset.consentRevision>failed.consentRevision)
        assertEquals(installed,reset.installed);assertNull(reset.approval);assertNull(access.approval())
    }
    @Test fun disableWithoutAnApprovalStillResetsDraftIdentityAndReloadRemainsAvailable():Unit=runBlocking {
        loaded();val before=controller.state.value
        controller.clearAll();val disabled=controller.state.value
        assertEquals(AutomationNotice.disabled,disabled.notice);assertTrue(disabled.resetRevision>before.resetRevision)
        controller.reload()
        val refreshed=withTimeout(5000) {controller.state.first {it.notice==null && it.resetRevision==disabled.resetRevision && !it.busy}}
        assertEquals(disabled.consentRevision,refreshed.consentRevision);assertNull(refreshed.approval)
        controller.installUnavailable();assertEquals(AutomationFailure.install,controller.state.value.failure)
        File(root,"access").mkdirs();File(root,"access/approval.json").writeText("{synthetic-private-token")
        controller.reload();withTimeout(5000) {controller.state.first {it.failure==AutomationFailure.load}}
        controller.installUnavailable();assertEquals(AutomationFailure.load,controller.state.value.failure)
        assertFalse(controller.state.value.error!!.contains("synthetic-private-token"))
    }
    @Test fun openingDoesNotGrantAndExplicitConsentFreezesTheReviewedScopeSet():Unit=runBlocking {
        loaded();assertNull(access.approval());assertFalse(File(root,"access/approval.json").exists())
        val chosen=mutableSetOf(AutomationScope.summary)
        controller.approve(identity,chosen);chosen+=AutomationScope.exports
        withTimeout(5000) {controller.state.first {it.message=="Automation access saved."}}
        assertEquals(setOf(AutomationScope.summary),access.approval()!!.scopes)
    }
    @Test fun clearJoinsAnOlderConsentWriteAndCannotEnableItAfterward():Unit=runBlocking {supervisorScope {
        loaded();access.approve(identity,setOf(AutomationScope.summary))
        val entered=CompletableDeferred<Unit>();val cancelling=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val command=async {runCatching {access.authorize(identity.packageName,AutomationScope.summary) {
            entered.complete(Unit)
            try {awaitCancellation()} finally {withContext(NonCancellable) {cancelling.complete(Unit);release.await()}}
        }}}
        try {
        entered.await();controller.approve(identity,setOf(AutomationScope.visits));cancelling.await()
        val clear=async {controller.clearAll()};yield();release.complete(Unit)
        withTimeout(5000) {clear.await();command.await()}
        assertNull(access.approval());assertFalse(controller.state.value.busy)
        controller.reload();loaded();assertNull(controller.state.value.approval)
        } finally {release.complete(Unit);command.cancelAndJoin()}
    }}
    @Test fun corruptApprovalReportsAnErrorAndExplicitDisableResetsIt():Unit=runBlocking {
        loaded();File(root,"access").mkdirs();File(root,"access/approval.json").writeText("{broken")
        controller.reload()
        withTimeout(5000) {controller.state.first {it.error!=null}}
        controller.disable()
        withTimeout(5000) {controller.state.first {it.message=="Automation access disabled."}}
        assertNull(access.approval());assertNull(controller.state.value.error)
    }
}
