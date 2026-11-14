package com.bontecou.whereday

import androidx.compose.runtime.Composable
import com.bontecou.whereday.billing.BillingState
import com.bontecou.whereday.billing.PlanHistory

/** Production has no QA dispatch policy or fixture state to consult. */
internal fun dispatchSubscriptionStoreAction(action:()->Unit)=action()

/** Production has no synthetic subscription override or writer, even if a QA process exists elsewhere. */
internal fun subscriptionPresentation(live:BillingState,history:PlanHistory)=SubscriptionPresentation(live,history)
@Suppress("UNUSED_PARAMETER")
@Composable internal fun rememberSubscriptionPresentation(live:BillingState,history:PlanHistory,
    owner:SubscriptionPresentationOwner=SubscriptionPresentationOwner.settings)=subscriptionPresentation(live,history)
