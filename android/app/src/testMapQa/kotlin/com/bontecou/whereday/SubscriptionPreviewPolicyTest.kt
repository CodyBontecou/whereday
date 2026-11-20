package com.bontecou.whereday

import com.bontecou.whereday.billing.*
import org.junit.*
import org.junit.Assert.*

class SubscriptionPreviewPolicyTest {
    private val qa="com.bontecou.whereday.mapqa"
    private val live=BillingState(AccessState.verificationRequired,activePurchaseHash=null)
    private val history=PlanHistory()
    @After fun clear() {SubscriptionPreview.override.value=null}
    private fun set(mode:String)=SubscriptionPreview.configure(qa,"mapQa",2000,"set",mode,false)
    private fun denied(action:()->Unit) {try {action();fail("Expected denial")} catch(_:SecurityException) {}}
    private fun malformed(action:()->Unit) {try {action();fail("Expected invalid request")} catch(_:IllegalArgumentException) {}}

    @Test fun onlyDedicatedPackageActualVariantAndShellMayWrite() {
        set("renewing-yearly");val fixture=SubscriptionPreview.override.value
        for(uid in listOf(0,1000,10000)) denied {SubscriptionPreview.configure(qa,"mapQa",uid,"clear",null,false)}
        for(pkg in listOf("com.bontecou.whereday","com.bontecou.whereday.mapqa.other")) denied {SubscriptionPreview.configure(pkg,"mapQa",2000,"clear",null,false)}
        for(variant in listOf("debug","release","mapqa")) denied {SubscriptionPreview.configure(qa,variant,2000,"clear",null,false)}
        assertSame(fixture,SubscriptionPreview.override.value)
    }
    @Test fun allModesAreFixedSyntheticPresentationAndClearRestoresLiveInputs() {
        for(mode in listOf("renewing-yearly","renewing-weekly","renewing-unknown","ending-yearly","lifetime","lifetime-renewing","offline-subscription","offline-lifetime","lapsed","none")) {
            set(mode)
            val p=SubscriptionPreview.presentation(qa,"mapQa",live,history)
            assertFalse(mode,p.storeActionsAllowed);assertNotSame(live,p.billing)
            assertEquals(AccessState.verificationRequired,live.access);assertNull(live.activePurchaseHash)
        }
        SubscriptionPreview.configure(qa,"mapQa",2000,"clear",null,false)
        val restored=SubscriptionPreview.presentation(qa,"mapQa",live,history)
        assertSame(live,restored.billing);assertSame(history,restored.history);assertTrue(restored.storeActionsAllowed)
        set("renewing-yearly");set("clear");assertNull(SubscriptionPreview.override.value)
    }
    @Test fun malformedAuthorizedRequestsClearRatherThanRetainAnOldFixture() {
        val cases=listOf<()->Unit>(
            {SubscriptionPreview.configure(qa,"mapQa",2000,"unknown","renewing-yearly",false)},
            {SubscriptionPreview.configure(qa,"mapQa",2000,"set",null,false)},
            {SubscriptionPreview.configure(qa,"mapQa",2000,"set","unknown",false)},
            {SubscriptionPreview.configure(qa,"mapQa",2000,"set","renewing-yearly",true)},
            {SubscriptionPreview.configure(qa,"mapQa",2000,"clear","unexpected",false)})
        for(action in cases) {set("renewing-yearly");malformed(action);assertNull(SubscriptionPreview.override.value)}
    }
    @Test fun wrongRenderingPackageOrVariantIgnoresEvenAnInMemoryOverride() {
        set("renewing-yearly")
        for((pkg,variant) in listOf(qa to "release","com.bontecou.whereday" to "mapQa",qa to "debug")) {
            val p=SubscriptionPreview.presentation(pkg,variant,live,history)
            assertSame(live,p.billing);assertSame(history,p.history);assertTrue(p.storeActionsAllowed)
        }
    }
    @Test fun fixturesCannotDispatchStoreCallbacksButLivePresentationCan() {
        var calls=0;set("renewing-yearly")
        SubscriptionPreview.presentation(qa,"mapQa",live,history).storeAction {calls++}
        assertEquals(0,calls)
        SubscriptionPreview.configure(qa,"mapQa",2000,"clear",null,false)
        SubscriptionPreview.presentation(qa,"mapQa",live,history).storeAction {calls++}
        assertEquals(1,calls)
    }
    @Test fun retainedLiveCallbacksAreDeniedAfterShellSelectionWithoutRecomposition() {
        val renderedLive=SubscriptionPreview.presentation(qa,"mapQa",live,history)
        val dispatched=mutableListOf<String>()
        // Retain purchase/restore and an already-open sheet's management continuation before selection.
        val callbacks=listOf("purchase","restore","management").map {name->
            {renderedLive.storeAction {dispatched+=name}}
        }
        set("renewing-yearly")
        callbacks.forEach {it()}
        assertTrue("Current fixture must revoke prior live callbacks",dispatched.isEmpty())
        assertSame("Render data remains immutable",live,renderedLive.billing)
    }
    @Test fun staleCollectorValueCannotAuthorizeANewLiveLookingCallback() {
        set("offline-subscription")
        // Explicit null models a lifecycle-aware collector which has not delivered the shell update yet.
        val stale=SubscriptionPreview.presentation(qa,"mapQa",live,history,value=null)
        assertTrue(stale.storeActionsAllowed)
        var dispatched=0
        stale.storeAction {dispatched++}
        assertEquals(0,dispatched)
    }
    @Test fun clearingAllowsFreshLiveCallbacksButCannotPromoteRetainedSyntheticCallbacks() {
        set("renewing-yearly")
        val synthetic=SubscriptionPreview.presentation(qa,"mapQa",live,history)
        var syntheticCalls=0
        val retained={synthetic.storeAction {syntheticCalls++}}
        SubscriptionPreview.configure(qa,"mapQa",2000,"clear",null,false)
        retained()
        var liveCalls=0
        SubscriptionPreview.presentation(qa,"mapQa",live,history).storeAction {liveCalls++}
        assertEquals(0,syntheticCalls);assertEquals(1,liveCalls)
    }
    @Test fun offlineAndLifetimeManagementPreserveNeutralRenewalPolicy() {
        fun summary(mode:String):SubscriptionSummary {
            set(mode);val p=SubscriptionPreview.presentation(qa,"mapQa",live,history)
            return PlanRetention.summary(p.billing,p.history,"whereday_individual")
        }
        assertTrue(summary("renewing-yearly").offersCancel)
        assertEquals(StorePlan.weekly,summary("renewing-weekly").plan)
        assertNull(summary("renewing-unknown").plan)
        assertEquals(SubscriptionStatus.ending,summary("ending-yearly").status)
        for(mode in listOf("offline-subscription","offline-lifetime")) {
            val s=summary(mode);assertFalse(s.renewalKnown);assertFalse(s.offersCancel);assertTrue(s.managesInPlay)
            assertEquals(BillingNotice.cached,SubscriptionPreview.override.value!!.billing.notice)
        }
        assertFalse(summary("lifetime").managesInPlay)
        assertTrue(summary("lifetime-renewing").subscriptionAlsoRenewing)
    }
}
