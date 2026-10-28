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
    @Before fun setup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.platform.test")
        root=File(context.cacheDir,"automation-controller-fixture-${UUID.randomUUID()}").apply {mkdirs()}
        access=AutomationAccess(File(root,"access")) {identity}
        results=AutomationResults(File(root,"results"),access,{10000},{1000},{1})
        controller=AutomationController(access,results,identity.packageName)
    }
    @After fun cleanup() {controller.close();root.deleteRecursively()}
    private suspend fun loaded()=withTimeout(5000) {controller.state.first {it.loaded && !it.busy}}
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
        entered.await();controller.approve(identity,setOf(AutomationScope.visits));cancelling.await()
        val clear=async {controller.clearAll()};yield();release.complete(Unit)
        withTimeout(5000) {clear.await();command.await()}
        assertNull(access.approval());assertFalse(controller.state.value.busy)
        controller.reload();loaded();assertNull(controller.state.value.approval)
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
