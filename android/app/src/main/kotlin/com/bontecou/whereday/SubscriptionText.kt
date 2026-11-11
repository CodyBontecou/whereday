package com.bontecou.whereday

import androidx.annotation.StringRes
import com.bontecou.whereday.billing.BillingState
import com.bontecou.whereday.billing.PlanHistory
import com.bontecou.whereday.billing.StorePlan
import com.bontecou.whereday.billing.SubscriptionStatus
import com.bontecou.whereday.billing.SubscriptionSummary
import kotlinx.coroutines.flow.MutableStateFlow

/** The Settings plan card's three lines. */
internal data class PlanCardText(@StringRes val kicker:Int,@StringRes val title:Int,@StringRes val body:Int)

/** The Subscription page for a plan this app can describe: renewing, ending or Lifetime. */
internal data class SubscriptionPageText(@StringRes val kicker:Int,@StringRes val title:Int,@StringRes val body:Int,
    @StringRes val planValue:Int,@StringRes val statusValue:Int,
    /** Next renewal is a row only while something renews; its value is always "Shown in Google Play". */
    val showsRenewal:Boolean,@StringRes val footnote:Int?)

internal object SubscriptionText {
    @StringRes fun planTitle(plan:StorePlan?)=when(plan) {
        StorePlan.weekly->R.string.subscription_title_weekly
        StorePlan.yearly->R.string.subscription_title_yearly
        StorePlan.lifetime->R.string.paywall_plan_lifetime
        null->R.string.settings_plan_title_subscription
    }

    /** Lapsed users get the ended card. A free week is mentioned only while Play offers this account one. */
    fun card(summary:SubscriptionSummary,trialAvailable:Boolean)=when(summary.status) {
        SubscriptionStatus.renewing->PlanCardText(R.string.settings_plan_kicker,planTitle(summary.plan),R.string.settings_plan_body_subscription)
        SubscriptionStatus.ending->PlanCardText(R.string.settings_plan_kicker_ending,planTitle(summary.plan),R.string.settings_plan_body_ending)
        SubscriptionStatus.lifetime->PlanCardText(R.string.settings_plan_kicker,R.string.paywall_plan_lifetime,R.string.paywall_plan_lifetime_detail)
        SubscriptionStatus.lapsed->PlanCardText(R.string.settings_plan_kicker_none,R.string.subscription_ended_title,R.string.subscription_ended_body)
        else->PlanCardText(R.string.settings_plan_kicker_none,R.string.settings_plan_title_none,
            if(trialAvailable) R.string.settings_plan_body_none else R.string.settings_plan_body_none_no_trial)
    }

    /** The card opens the Subscription page for a known plan or a lapse; otherwise the plans carousel, as before. */
    fun cardOpensSubscription(summary:SubscriptionSummary)=summary.status in setOf(SubscriptionStatus.renewing,SubscriptionStatus.ending,
        SubscriptionStatus.lifetime,SubscriptionStatus.lapsed)

    fun page(summary:SubscriptionSummary):SubscriptionPageText?=when(summary.status) {
        SubscriptionStatus.renewing->SubscriptionPageText(R.string.settings_plan_kicker,planTitle(summary.plan),R.string.subscription_body_renewing,
            planValue(summary.plan),R.string.subscription_status_renewing,true,R.string.subscription_footnote_renewing)
        SubscriptionStatus.ending->SubscriptionPageText(R.string.settings_plan_kicker_ending,planTitle(summary.plan),R.string.subscription_body_ending,
            planValue(summary.plan),R.string.subscription_status_ending,false,R.string.subscription_footnote_ending)
        /** "Nothing renews" would be false beside the still-renewing warning, so the body names that subscription instead. */
        SubscriptionStatus.lifetime->SubscriptionPageText(R.string.settings_plan_kicker,R.string.paywall_plan_lifetime,
            if(summary.subscriptionAlsoRenewing) R.string.subscription_body_lifetime_also_renewing else R.string.subscription_body_lifetime,
            R.string.paywall_plan_lifetime,R.string.subscription_status_lifetime,false,null)
        else->null
    }

    @StringRes private fun planValue(plan:StorePlan?)=when(plan) {
        StorePlan.weekly->R.string.paywall_plan_weekly
        StorePlan.yearly->R.string.paywall_plan_yearly
        StorePlan.lifetime->R.string.paywall_plan_lifetime
        null->R.string.subscription_value_in_play
    }

    @StringRes fun endedFootnote(trialAvailable:Boolean)=if(trialAvailable) R.string.subscription_ended_plans_trial else R.string.subscription_ended_plans
}

/**
 * A debug-only stand-in for Play billing, set from the ADB shell by the debug source set's provider.
 * Release builds contain no writer, so this stays null there and Settings shows the real state.
 */
internal object SubscriptionPreview {
    data class Value(val billing:BillingState,val history:PlanHistory)
    val override=MutableStateFlow<Value?>(null)
}
