package com.bontecou.whereday.billing

/**
 * What this installation remembers about its own plan, so Settings can tell a lapse from a first visit.
 * [hadSubscription] is set once a verified subscription was seen here and never cleared by a lapse.
 * [checkoutPlan] is the base plan bought through this app, valid only while [checkoutPurchaseHash] matches
 * the verified purchase: Play's client API doesn't report the base plan, and any plan change in Play issues a new token.
 */
data class PlanHistory(val hadSubscription:Boolean=false,val checkoutPlan:StorePlan?=null,val checkoutPurchaseHash:String?=null)

/** The Subscription page's state. Renewal and end dates are never known on the device; Google Play shows them. */
enum class SubscriptionStatus {
    /** Access is still being checked, or the build has no Play billing. */
    unknown,
    /** Never subscribed here, nothing owned. */
    none,
    pending,
    /** Verification failed (for example offline) without a usable cached grant. Not a lapse. */
    unverified,
    /** A valid cached subscription grants access, but renewal has not been refreshed in Play. */
    active,
    renewing,
    /** Canceled in Play; access continues until the period Play already charged for ends. */
    ending,
    lifetime,
    /** Had a verified subscription on this installation and Play now reports none. */
    lapsed,
}

data class SubscriptionSummary(val status:SubscriptionStatus,
    /** weekly or yearly when known, lifetime for Lifetime, null when the base plan can't be known. */
    val plan:StorePlan?=null,
    /** Lifetime is active but a subscription still renews in Play, which Lifetime doesn't cancel. */
    val subscriptionAlsoRenewing:Boolean=false,
    val renewalKnown:Boolean=true) {
    /** "Manage or cancel" goes through the Before you go summary only while a subscription renews. */
    val offersCancel get()=status==SubscriptionStatus.renewing
    /** Any Play subscription page link at all. Lifetime alone has nothing to manage or cancel. */
    val managesInPlay get()=status==SubscriptionStatus.renewing || status==SubscriptionStatus.ending || status==SubscriptionStatus.active ||
        subscriptionAlsoRenewing || (status==SubscriptionStatus.lifetime && !renewalKnown)
}

object PlanRetention {
    /** Folds a new billing state into the remembered history. Returns the same instance when nothing changed. */
    fun observe(history:PlanHistory,state:BillingState,subscriptionId:String):PlanHistory =
        if(!history.hadSubscription && state.access==AccessState.active && state.activeProductId==subscriptionId) history.copy(hadSubscription=true)
        else history

    /** Records a completed Play checkout. Lifetime doesn't replace the remembered subscription plan. */
    fun checkoutCompleted(history:PlanHistory,plan:StorePlan,purchaseHash:String?):PlanHistory =
        if(plan==StorePlan.lifetime || purchaseHash==null) history else history.copy(checkoutPlan=plan,checkoutPurchaseHash=purchaseHash)

    fun summary(state:BillingState,history:PlanHistory,subscriptionId:String):SubscriptionSummary {
        if(!state.enforced) return SubscriptionSummary(SubscriptionStatus.unknown)
        return when(state.access) {
            AccessState.active -> if(state.activeProductId==subscriptionId) {
                val plan=history.checkoutPlan?.takeIf {history.checkoutPurchaseHash!=null && history.checkoutPurchaseHash==state.activePurchaseHash}
                val status=when { !state.renewalKnown->SubscriptionStatus.active;state.autoRenewing->SubscriptionStatus.renewing;else->SubscriptionStatus.ending }
                SubscriptionSummary(status,plan,renewalKnown=state.renewalKnown)
            } else SubscriptionSummary(SubscriptionStatus.lifetime,StorePlan.lifetime,subscriptionAlsoRenewing=state.subscriptionRenewing,renewalKnown=state.renewalKnown)
            AccessState.checking,AccessState.unavailable -> SubscriptionSummary(SubscriptionStatus.unknown)
            AccessState.pending -> SubscriptionSummary(SubscriptionStatus.pending)
            AccessState.verificationRequired -> SubscriptionSummary(SubscriptionStatus.unverified)
            AccessState.free -> SubscriptionSummary(if(history.hadSubscription) SubscriptionStatus.lapsed else SubscriptionStatus.none)
        }
    }
}
