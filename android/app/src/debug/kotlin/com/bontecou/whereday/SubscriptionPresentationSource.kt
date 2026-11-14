package com.bontecou.whereday

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.billing.BillingState
import com.bontecou.whereday.billing.PlanHistory
import kotlinx.coroutines.flow.MutableStateFlow

/** Existing debug Settings-only developer preview; authority/modes/writer stay unchanged. */
internal object SubscriptionPreview {
    data class Value(val billing:BillingState,val history:PlanHistory)
    val override=MutableStateFlow<Value?>(null)
}

/** Preserve legacy debug dispatch behavior independently of the new mapQa policy. */
internal fun dispatchSubscriptionStoreAction(action:()->Unit)=action()

internal fun subscriptionPresentation(live:BillingState,history:PlanHistory,owner:SubscriptionPresentationOwner=SubscriptionPresentationOwner.settings,
    preview:SubscriptionPreview.Value?=SubscriptionPreview.override.value):SubscriptionPresentation =
    if(owner==SubscriptionPresentationOwner.settings && preview!=null) SubscriptionPresentation(preview.billing,preview.history)
    else SubscriptionPresentation(live,history)

@Composable internal fun rememberSubscriptionPresentation(live:BillingState,history:PlanHistory,
    owner:SubscriptionPresentationOwner=SubscriptionPresentationOwner.settings):SubscriptionPresentation {
    val preview by SubscriptionPreview.override.collectAsStateWithLifecycle()
    return subscriptionPresentation(live,history,owner,preview)
}
