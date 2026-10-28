package com.bontecou.whereday.platform

import com.bontecou.whereday.billing.BillingRefreshResult
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UsageFunnelTest {
    private class Store:UsageAnalyticsStore {
        @Volatile var value=UsageConsentState()
        override suspend fun read()=value
        override suspend fun write(value:UsageConsentState) {this.value=value}
    }
    private suspend fun fixture(block:suspend(Store,UsageAnalyticsController,UsageFunnel)->Unit) {
        val store=Store()
        val controller=UsageAnalyticsController(UsageAnalyticsCore(store,{UsageSendResult.accepted}) {UsageMetadata("0.1.0","1")},object:UsageAnalyticsJobs {
            override suspend fun reconcile() {}
            override suspend fun cancelAll() {}
        })
        try {withTimeout(2_000) {while(!controller.state.value.loaded) delay(1)};block(store,controller,UsageFunnel(controller))}
        finally {controller.close()}
    }
    private suspend fun entries(store:Store,count:Int):List<UsageEvent> {
        withTimeout(2_000) {while(store.value.pending.size!=count) delay(1)}
        return store.value.pending.map {it.event}
    }
    @Test fun asynchronousCallbacksCannotCollectActionsBeganBeforeConsentOrUnderAnOldIdentity():Unit=runBlocking {fixture {store,controller,funnel->
        val beforePermission=funnel.permission(UsageRequestKind.foreground,UsageAuthorization.unknown)
        val beforePurchase=funnel.purchase(UsagePaywall.export,"private-unreviewed-sku")
        assertNull(beforePermission);assertNull(beforePurchase)
        controller.setEnabled(true)!!.join()
        funnel.permissionFinished(beforePermission,UsageAuthorization.foreground)
        funnel.refreshFinished(beforePurchase,BillingRefreshResult.verified)
        assertTrue(store.value.pending.isEmpty())
        val old=funnel.permission(UsageRequestKind.notifications,UsageAuthorization.unknown)
        entries(store,1)
        controller.setEnabled(false)!!.join();controller.setEnabled(true)!!.join()
        funnel.permissionFinished(old,UsageAuthorization.authorized)
        assertTrue(store.value.pending.isEmpty())
        val stale=controller.capture()
        controller.setEnabled(false)!!.join();controller.setEnabled(true)!!.join()
        assertNull(controller.track(stale) {error("Old factory was evaluated")})
    }}
    @Test fun permissionCompletionIsOrderedSingleUseAndCoarseForBothPermissionKinds():Unit=runBlocking {fixture {store,controller,funnel->
        controller.setEnabled(true)!!.join()
        val location=funnel.permission(UsageRequestKind.foreground,UsageAuthorization.unknown)
        funnel.permissionFinished(location,UsageAuthorization.foreground)
        funnel.permissionFinished(location,UsageAuthorization.denied)
        val locationEvents=entries(store,2)
        assertEquals(listOf(UsageEventName.permissionRequested,UsageEventName.permissionCompleted),locationEvents.map {it.name})
        assertEquals(UsageAuthorization.foreground,locationEvents.last().authorization)
        val notifications=funnel.permission(UsageRequestKind.notifications,UsageAuthorization.unknown)
        funnel.permissionFinished(notifications,UsageAuthorization.authorized)
        val events=entries(store,4)
        assertEquals(UsageAuthorization.authorized,events.last().authorization)
        assertEquals(UsageRequestKind.notifications,events.last().request);assertEquals(UsageStep.notifications,events.last().step)
    }}
    @Test fun purchaseAndRestoreUseVerifiedOutcomesNeverRawProductsOrDuplicateCompletions():Unit=runBlocking {fixture {store,controller,funnel->
        controller.setEnabled(true)!!.join()
        for((index,result) in BillingRefreshResult.entries.withIndex()) {
            val attempt=funnel.purchase(UsagePaywall.webhook,"private-sku-from-configuration",restore=true)
            funnel.refreshFinished(attempt,result);funnel.refreshFinished(attempt,BillingRefreshResult.verified)
            val pair=entries(store,(index+1)*2).takeLast(2)
            assertEquals(listOf(UsageEventName.restoreStarted,UsageEventName.restoreFinished),pair.map {it.name})
            assertTrue(pair.all {it.product==null && it.paywall==UsagePaywall.webhook})
            assertEquals(when(result) {BillingRefreshResult.verified->UsageOutcome.restored;BillingRefreshResult.pending->UsageOutcome.pending;BillingRefreshResult.notFound->UsageOutcome.notFound;else->UsageOutcome.failed},pair.last().outcome)
        }
        val buy=funnel.purchase(UsagePaywall.export,UsageProduct.individualLifetime.wire)
        funnel.refreshFinished(buy,BillingRefreshResult.verified)
        val pair=entries(store,12).takeLast(2)
        assertEquals(UsageOutcome.succeeded,pair.last().outcome)
        assertEquals(UsageProduct.individualLifetime,pair.last().product)
        assertEquals(UsageEventName.purchaseFinished,pair.last().name)
    }}
    @Test fun cancelledPurchaseAndVisiblePaywallOnlyCollectWithConsent():Unit=runBlocking {fixture {store,controller,funnel->
        funnel.paywall(UsagePaywall.export);assertTrue(store.value.pending.isEmpty())
        controller.setEnabled(true)!!.join()
        funnel.paywall(UsagePaywall.export);entries(store,1)
        val attempt=funnel.purchase(UsagePaywall.export,UsageProduct.individualLifetime.wire)
        funnel.purchaseFinished(attempt,UsageOutcome.cancelled,UsageError.userCancelled)
        val result=entries(store,3).last()
        assertEquals(UsageOutcome.cancelled,result.outcome);assertEquals(UsageError.userCancelled,result.error)
    }}
}
