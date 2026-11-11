package com.bontecou.whereday.billing

import org.junit.Assert.*
import org.junit.Test

class PlanRetentionTest {
    private val sub="whereday_individual"
    private val lifetime="whereday_export_lifetime"
    private fun active(product:String=sub,renewing:Boolean=true,hash:String?="hash-a",alsoRenewing:Boolean=false)=
        BillingState(AccessState.active,activeProductId=product,autoRenewing=renewing,activePurchaseHash=hash,subscriptionRenewing=alsoRenewing,renewalKnown=true)
    private fun summary(state:BillingState,history:PlanHistory=PlanHistory())=PlanRetention.summary(state,history,sub)

    @Test fun aVerifiedSubscriptionIsRememberedOnceAndSurvivesItsLapse() {
        var history=PlanHistory()
        history=PlanRetention.observe(history,BillingState(),sub)
        assertFalse("checking is not a subscription",history.hadSubscription)
        history=PlanRetention.observe(history,active(),sub)
        assertTrue(history.hadSubscription)
        val remembered=history
        // Play no longer reports the purchase: the memory stays, so the lapse is recognizable.
        history=PlanRetention.observe(history,BillingState(AccessState.free),sub)
        assertSame(remembered,history)
        assertEquals(SubscriptionStatus.lapsed,summary(BillingState(AccessState.free),history).status)
    }

    @Test fun lifetimeAndOtherStatesNeverCountAsAPastSubscription() {
        for(state in listOf(active(lifetime,renewing=false),BillingState(AccessState.pending),BillingState(AccessState.verificationRequired),
            BillingState(AccessState.unavailable,enforced=false),BillingState(AccessState.free)))
            assertFalse(state.toString(),PlanRetention.observe(PlanHistory(),state,sub).hadSubscription)
    }

    @Test fun aFirstVisitWithNothingOwnedIsNotALapse() {
        assertEquals(SubscriptionStatus.none,summary(BillingState(AccessState.free)).status)
    }

    @Test fun uncertainStatesAreNeverReportedAsALapse() {
        val had=PlanHistory(hadSubscription=true)
        assertEquals(SubscriptionStatus.unknown,summary(BillingState(AccessState.checking),had).status)
        assertEquals(SubscriptionStatus.unverified,summary(BillingState(AccessState.verificationRequired),had).status)
        assertEquals(SubscriptionStatus.pending,summary(BillingState(AccessState.pending),had).status)
        // Builds without Play billing configured don't describe plans at all.
        assertEquals(SubscriptionStatus.unknown,summary(BillingState(AccessState.free,enforced=false),had).status)
        assertEquals(SubscriptionStatus.unknown,summary(BillingState(AccessState.unavailable,enforced=false),had).status)
    }

    @Test fun renewingAndCanceledSubscriptionsMapToTheirStatusAndCancelPath() {
        val renewing=summary(active(renewing=true))
        assertEquals(SubscriptionStatus.renewing,renewing.status)
        assertTrue(renewing.offersCancel);assertTrue(renewing.managesInPlay)
        val ending=summary(active(renewing=false))
        assertEquals(SubscriptionStatus.ending,ending.status)
        assertFalse("already canceled: no Before you go",ending.offersCancel);assertTrue(ending.managesInPlay)
    }

    @Test fun lifetimeHasNoCancelPathUnlessASubscriptionStillRenews() {
        val alone=summary(active(lifetime,renewing=false))
        assertEquals(SubscriptionStatus.lifetime,alone.status);assertEquals(StorePlan.lifetime,alone.plan)
        assertFalse(alone.offersCancel);assertFalse(alone.managesInPlay)
        val both=summary(active(lifetime,renewing=false,alsoRenewing=true))
        assertTrue(both.subscriptionAlsoRenewing);assertFalse(both.offersCancel);assertTrue(both.managesInPlay)
    }

    @Test fun aCheckoutPlanIsShownOnlyForTheSamePurchase() {
        val bought=PlanRetention.checkoutCompleted(PlanHistory(),StorePlan.weekly,"hash-a")
        assertEquals(StorePlan.weekly,summary(active(hash="hash-a"),bought).plan)
        // A plan change in Play issues a new token: the old base plan is no longer known.
        assertNull(summary(active(hash="hash-b"),bought).plan)
        assertNull(summary(active(hash=null),bought).plan)
        // Restoring on a new install knows the product but not the base plan.
        assertNull(summary(active(hash="hash-a"),PlanHistory(true)).plan)
    }

    @Test fun lifetimeOrUnidentifiedCheckoutsKeepTheRememberedSubscriptionPlan() {
        val bought=PlanRetention.checkoutCompleted(PlanHistory(true),StorePlan.yearly,"hash-a")
        assertSame(bought,PlanRetention.checkoutCompleted(bought,StorePlan.lifetime,"hash-l"))
        assertSame(bought,PlanRetention.checkoutCompleted(bought,StorePlan.weekly,null))
        assertEquals(PlanHistory(true,StorePlan.weekly,"hash-b"),PlanRetention.checkoutCompleted(bought,StorePlan.weekly,"hash-b"))
    }

    @Test fun resubscribingAfterALapseReturnsToRenewing() {
        val history=PlanHistory(true)
        assertEquals(SubscriptionStatus.lapsed,summary(BillingState(AccessState.free),history).status)
        assertEquals(SubscriptionStatus.renewing,summary(active(),PlanRetention.observe(history,active(),sub)).status)
    }
}
