package com.bontecou.whereday

import com.bontecou.whereday.billing.StorePlan
import com.bontecou.whereday.billing.SubscriptionStatus
import com.bontecou.whereday.billing.SubscriptionSummary
import org.junit.Assert.*
import org.junit.Test

class SubscriptionTextTest {
    private fun summary(status:SubscriptionStatus,plan:StorePlan?=null,alsoRenewing:Boolean=false)=SubscriptionSummary(status,plan,alsoRenewing)

    @Test fun planCardNamesTheKnownBasePlanAndFallsBackToSubscription() {
        assertEquals(R.string.subscription_title_yearly,SubscriptionText.card(summary(SubscriptionStatus.renewing,StorePlan.yearly),false).title)
        assertEquals(R.string.subscription_title_weekly,SubscriptionText.card(summary(SubscriptionStatus.ending,StorePlan.weekly),false).title)
        assertEquals(R.string.settings_plan_title_subscription,SubscriptionText.card(summary(SubscriptionStatus.renewing),false).title)
        assertEquals(R.string.settings_plan_kicker_ending,SubscriptionText.card(summary(SubscriptionStatus.ending),false).kicker)
        assertEquals(R.string.paywall_plan_lifetime,SubscriptionText.card(summary(SubscriptionStatus.lifetime,StorePlan.lifetime),false).title)
    }

    @Test fun lapsedCardSaysTheSubscriptionEndedWithoutAFreeWeek() {
        for(trial in listOf(true,false)) {
            val card=SubscriptionText.card(summary(SubscriptionStatus.lapsed),trial)
            assertEquals(R.string.subscription_ended_title,card.title)
            assertEquals(R.string.subscription_ended_body,card.body)
        }
    }

    @Test fun freeWeekCopyAppearsOnlyWhilePlayOffersATrial() {
        assertEquals(R.string.settings_plan_body_none,SubscriptionText.card(summary(SubscriptionStatus.none),true).body)
        assertEquals(R.string.settings_plan_body_none_no_trial,SubscriptionText.card(summary(SubscriptionStatus.none),false).body)
        assertEquals(R.string.subscription_ended_plans_trial,SubscriptionText.endedFootnote(true))
        assertEquals(R.string.subscription_ended_plans,SubscriptionText.endedFootnote(false))
    }

    @Test fun cardOpensTheSubscriptionPageOnlyForAKnownPlanOrALapse() {
        val opens=SubscriptionStatus.entries.filter {SubscriptionText.cardOpensSubscription(summary(it))}.toSet()
        assertEquals(setOf(SubscriptionStatus.renewing,SubscriptionStatus.ending,SubscriptionStatus.lifetime,SubscriptionStatus.lapsed),opens)
    }

    @Test fun pageNeverStatesARenewalDateAndShowsRenewalOnlyWhileRenewing() {
        val renewing=requireNotNull(SubscriptionText.page(summary(SubscriptionStatus.renewing,StorePlan.yearly)))
        assertTrue(renewing.showsRenewal)
        assertEquals(R.string.paywall_plan_yearly,renewing.planValue)
        assertEquals(R.string.subscription_footnote_renewing,renewing.footnote)
        assertFalse(requireNotNull(SubscriptionText.page(summary(SubscriptionStatus.ending))).showsRenewal)
        assertEquals(R.string.subscription_value_in_play,requireNotNull(SubscriptionText.page(summary(SubscriptionStatus.ending))).planValue)
        val lifetime=requireNotNull(SubscriptionText.page(summary(SubscriptionStatus.lifetime,StorePlan.lifetime)))
        assertFalse(lifetime.showsRenewal);assertNull(lifetime.footnote)
        for(status in listOf(SubscriptionStatus.none,SubscriptionStatus.lapsed,SubscriptionStatus.pending,SubscriptionStatus.unverified,SubscriptionStatus.unknown))
            assertNull(status.name,SubscriptionText.page(summary(status)))
    }

    @Test fun lifetimeBodySaysNothingRenewsOnlyWhenNoSubscriptionStillRenews() {
        val alone=requireNotNull(SubscriptionText.page(summary(SubscriptionStatus.lifetime,StorePlan.lifetime)))
        assertEquals(R.string.subscription_body_lifetime,alone.body)
        val alsoRenewing=requireNotNull(SubscriptionText.page(summary(SubscriptionStatus.lifetime,StorePlan.lifetime,alsoRenewing=true)))
        assertEquals(R.string.subscription_body_lifetime_also_renewing,alsoRenewing.body)
        assertEquals(R.string.paywall_plan_lifetime,alsoRenewing.title)
        assertEquals(R.string.subscription_status_lifetime,alsoRenewing.statusValue)
    }

    @Test fun beforeYouGoHeadingsUseCaseAndTrackingOnlyForCasedLeftToRightScripts() {
        assertTrue(eyebrowUsesCase("What stops",rtl=false))
        assertTrue(eyebrowUsesCase("Что прекращается",rtl=false))
        assertFalse(eyebrowUsesCase("ما الذي يتوقف",rtl=true))
        assertFalse(eyebrowUsesCase("What stops",rtl=true))
        assertFalse(eyebrowUsesCase("क्या रुकता है",rtl=false))
        assertFalse(eyebrowUsesCase("停止的功能",rtl=false))
    }
}
