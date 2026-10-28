package com.bontecou.whereday.platform

import android.util.AtomicFile
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.io.File
import java.util.UUID

/** Private synthetic library-test directory only; never AppGraph or an installed owner's settings. */
class UsageAnalyticsStoreTest {
    private lateinit var directory:File
    private lateinit var store:AndroidUsageAnalyticsStore
    @Before fun setup() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.platform.test")
        directory=File(context.filesDir,"analytics-native-${UUID.randomUUID()}");store=AndroidUsageAnalyticsStore(directory)
    }
    @After fun cleanup() {check(directory.name.startsWith("analytics-native-"));directory.deleteRecursively()}
    private fun approved()=UsageConsentState(UUID.randomUUID(),listOf(UsagePending(UUID.randomUUID(),UsageEvent(UsageEventName.stepViewed,UsageStep.features),UsageMetadata("0.1.0","1"))))
    @Test fun freshReadDefaultsOffWithoutCreatingAnIdentifierOrFile():Unit=runBlocking {
        assertEquals(UsageConsentState(),store.read());assertFalse(directory.exists())
    }
    @Test fun committedConsentAndExactPendingReceiptSurviveAnotherStoreOwner():Unit=runBlocking {
        val value=approved();store.write(value)
        assertEquals(value,AndroidUsageAnalyticsStore(directory).read())
        store.write(UsageConsentState());assertEquals(UsageConsentState(),AndroidUsageAnalyticsStore(directory).read())
    }
    @Test fun interruptedAtomicReplacementKeepsTheLastCommittedState():Unit=runBlocking {
        val value=approved();store.write(value)
        val file=AtomicFile(File(directory,"state.json"));val pending=file.startWrite()
        pending.write("partial".toByteArray());pending.close() // Simulate death before finishWrite.
        assertEquals(value,AndroidUsageAnalyticsStore(directory).read())
    }
    @Test fun corruptAndOversizedStateFailClosedAndExplicitDisableCanReset():Unit=runBlocking {
        directory.mkdirs();val file=File(directory,"state.json")
        for(raw in listOf("{\"version\":99,\"consentId\":null,\"events\":[]}","x".repeat(65_537))) {
            file.writeText(raw)
            val core=UsageAnalyticsCore(store,{fail("Corrupt settings sent data");UsageSendResult.accepted}) {error("Metadata collected from corrupt consent")}
            core.load();assertEquals(UsageAnalyticsError.storage,core.state.value.error)
            assertFalse(core.record(UsageEvent(UsageEventName.onboardingStarted),core.captureConsent()));assertNull(core.sendNext())
            core.setConsent(false);assertEquals(UsageConsentState(),store.read())
        }
    }
}
