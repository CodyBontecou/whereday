package com.bontecou.whereday.platform

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.billing.AccessState
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class PurchaseUsageIntegrationTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private fun guard() {
        check(context.packageName=="com.bontecou.whereday.platform.test")
        check(!PurchaseConfiguration().configured) // Never query a real store or verifier in this fixture.
    }
    @Test fun restoreStillRefreshesWithoutAnyAnalyticsObserver():Unit=runBlocking {
        guard();val purchases=PurchaseController(context)
        try {
            withTimeout(5_000) {requireNotNull(purchases.restore(UsagePaywall.export)).join()}
            assertEquals(AccessState.unavailable,purchases.state.value.access)
            assertEquals(com.bontecou.whereday.billing.BillingNotice.unavailable,purchases.state.value.notice)
        } finally {purchases.close();PurchaseGrantCache(context).clear()}
    }
    @Test fun explicitRestoreReportsUnavailableButAutomaticRefreshNeverCreatesRestoreEvents():Unit=runBlocking {
        guard()
        val backing=object:UsageAnalyticsStore {
            @Volatile var value=UsageConsentState()
            override suspend fun read()=value
            override suspend fun write(value:UsageConsentState) {this.value=value}
        }
        val analytics=UsageAnalyticsController(UsageAnalyticsCore(backing,{UsageSendResult.accepted}) {UsageMetadata("0.1.0","1")},object:UsageAnalyticsJobs {
            override suspend fun reconcile() {}
            override suspend fun cancelAll() {}
        })
        val funnel=UsageFunnel(analytics)
        val purchases=PurchaseController(context) {funnel}
        try {
            withTimeout(5_000) {while(!analytics.state.value.loaded) delay(1)}
            analytics.setEnabled(true)!!.join()
            purchases.refresh()
            withTimeout(5_000) {while(purchases.state.value.access!=AccessState.unavailable) delay(1)}
            assertTrue(backing.value.pending.isEmpty())
            withTimeout(5_000) {requireNotNull(purchases.restore(UsagePaywall.webhook)).join()}
            withTimeout(5_000) {while(backing.value.pending.size!=2) delay(1)}
            val events=backing.value.pending.map {it.event}
            assertEquals(listOf(UsageEventName.restoreStarted,UsageEventName.restoreFinished),events.map {it.name})
            assertEquals(UsagePaywall.webhook,events.last().paywall)
            assertEquals(UsageOutcome.failed,events.last().outcome);assertEquals(UsageError.storeUnavailable,events.last().error)
            analytics.setEnabled(false)!!.join()
            withTimeout(5_000) {requireNotNull(purchases.restore(UsagePaywall.export)).join()}
            assertTrue(backing.value.pending.isEmpty());assertNull(backing.value.consentId)
            assertEquals(AccessState.unavailable,purchases.state.value.access)
            assertEquals(com.bontecou.whereday.billing.BillingNotice.unavailable,purchases.state.value.notice)
        } finally {purchases.close();analytics.close();PurchaseGrantCache(context).clear()}
    }
}
