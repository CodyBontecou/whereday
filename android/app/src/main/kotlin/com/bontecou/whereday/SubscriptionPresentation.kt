package com.bontecou.whereday

import com.bontecou.whereday.billing.BillingState
import com.bontecou.whereday.billing.PlanHistory

internal enum class SubscriptionPresentationOwner {settings,export}

/** Render inputs only. Never pass these values into an entitlement check or persist them as purchase history. */
internal data class SubscriptionPresentation(val billing:BillingState,val history:PlanHistory,val storeActionsAllowed:Boolean=true) {
    // Render-time denial stays sticky; variant policy also authorizes at dispatch, not just composition.
    fun storeAction(action:()->Unit) {if(storeActionsAllowed) dispatchSubscriptionStoreAction(action)}
}
