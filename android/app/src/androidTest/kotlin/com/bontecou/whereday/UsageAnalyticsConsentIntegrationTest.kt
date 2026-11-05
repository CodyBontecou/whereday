package com.bontecou.whereday

import android.content.ContextWrapper
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.platform.*
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Production consent UI + controller + actual AtomicFile; fake cancellable transport only. */
class UsageAnalyticsConsentIntegrationTest {
    @get:Rule val compose=createComposeRule()
    private lateinit var root:File
    private lateinit var store:AndroidUsageAnalyticsStore
    private lateinit var controller:UsageAnalyticsController
    @Before fun setup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.mapqa")
        root=File(context.filesDir,"analytics-consent-test-${UUID.randomUUID()}")
        val isolated=object:ContextWrapper(context) {override fun getFilesDir()=root}
        store=AndroidUsageAnalyticsStore(isolated)
    }
    @After fun cleanup():Unit=runBlocking {
        if(this@UsageAnalyticsConsentIntegrationTest::controller.isInitialized) controller.clearAll()
        check(root.name.startsWith("analytics-consent-test-"));root.deleteRecursively();Unit
    }
    private fun screen(transport:UsageAnalyticsTransport,metadata:()->UsageMetadata={UsageMetadata("0.1.0","1")}) {
        controller=UsageAnalyticsController(UsageAnalyticsCore(store,transport,metadata),object:UsageAnalyticsJobs {
            override suspend fun reconcile() {}
            override suspend fun cancelAll() {}
        })
        compose.setContent {MaterialTheme {UsageAnalyticsScreen(controller) {}}}
        compose.waitUntil(5_000) {controller.state.value.loaded}
    }
    private fun agree() {
        compose.onNodeWithTag("usage-analytics-switch").performScrollTo().performClick()
        compose.onNodeWithText("Agree and share").performClick()
        compose.waitUntil(5_000) {controller.state.value.enabled && !controller.control.value.busy}
    }
    @Test fun actualConsentRevokesAnActiveSendAndReapprovalUsesNewIdentity():Unit=runBlocking {
        val entered=CompletableDeferred<Unit>();var metadataReads=0;var sent=0
        screen({sent++;entered.complete(Unit);awaitCancellation()}) {metadataReads++;UsageMetadata("0.1.0","1")}
        assertFalse(root.exists());assertEquals(0,metadataReads);assertEquals(0,sent)
        compose.onNodeWithTag("usage-analytics-switch").performClick()
        compose.onNodeWithText("Keep sharing off").performClick()
        assertFalse(root.exists());assertNull(store.read().consentId)
        agree()
        val initial=store.read().consentId;assertNotNull(initial);assertEquals(0,metadataReads)
        controller.track {UsageEvent(UsageEventName.stepViewed,step=UsageStep.features)}!!.join()
        val drain=async(Dispatchers.IO) {runCatching {controller.drain()}}
        withTimeout(5_000) {entered.await()}
        compose.onNodeWithTag("usage-analytics-switch").performClick()
        compose.waitUntil(5_000) {!controller.state.value.enabled && !controller.control.value.busy}
        assertTrue(withTimeout(5_000) {drain.await()}.exceptionOrNull() is CancellationException)
        assertNull(store.read().consentId);assertTrue(store.read().pending.isEmpty());assertEquals(1,sent)
        assertEquals(1,metadataReads)
        agree()
        assertNotEquals(initial,store.read().consentId);assertTrue(store.read().pending.isEmpty())
        controller.clearAll()
        assertNull(store.read().consentId);assertTrue(store.read().pending.isEmpty())
    }
    @Test fun actualCorruptStoreCannotApproveUntilExplicitResetToOff():Unit=runBlocking {
        File(root,"usage-analytics").mkdirs();File(root,"usage-analytics/state.json").writeText("invalid")
        screen({fail("Corrupt consent transmitted");UsageSendResult.accepted})
        compose.onNodeWithTag("usage-analytics-switch").assertIsNotEnabled()
        compose.onNodeWithText("Reset sharing to off").performScrollTo().performClick()
        compose.waitUntil(5_000) {!controller.control.value.busy && controller.state.value.error==null}
        compose.onNodeWithTag("usage-analytics-switch").performScrollTo().assertIsOff().assertIsEnabled()
        assertNull(store.read().consentId);assertTrue(store.read().pending.isEmpty())
    }
}
