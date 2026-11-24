package com.bontecou.whereday

import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.billing.*
import com.bontecou.whereday.platform.UsageAuthorization
import org.junit.*
import org.junit.Assert.*

/** The last onboarding step with a fake configured billing (synthetic Play products), routing between the tonight step,
 *  the free week and "You're in". No Play, verifier, journal or notification access is used. */
class OnboardingFreeWeekTest {
    @get:Rule val compose=createComposeRule()
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext

    private fun weekly(trial:Int=7)=StoreProduct("whereday_individual",StorePlan.weekly,"$0.99",990_000,"USD",trial)
    private fun yearly(trial:Int=7)=StoreProduct("whereday_individual",StorePlan.yearly,"$29.99",29_990_000,"USD",trial)
    private val lifetime=StoreProduct("whereday_export_lifetime",StorePlan.lifetime,"$49.99",49_990_000,"USD")
    private fun catalog(trial:Int=7)=listOf(weekly(trial),yearly(trial),lifetime)

    private var billing by mutableStateOf(BillingState(AccessState.free,catalog(),enforced=true))
    private var checkout by mutableStateOf(false)
    private var notifications by mutableStateOf(false)
    private val bought=mutableListOf<StorePlan>()
    private var restores=0
    private var retries=0
    private var notificationRequests=0
    private val finished=mutableListOf<OnboardingDecision>()

    private fun show(intents:Set<OnboardingIntent> = OnboardingIntent.DEFAULT,authorization:UsageAuthorization=UsageAuthorization.foreground) {
        compose.setContent {MaterialTheme {
            Onboarding({},{notificationRequests++},authorization=authorization,
                access=RecordingAccessState(if(authorization==UsageAuthorization.unknown) LocationPrecision.none else LocationPrecision.precise,notifications),
                initialIntents=intents,notificationRequestAvailable=true,
                offer=OnboardingOffer(billing,checkout,{bought+=it},{restores++},{retries++}),initialStep=OnboardingStep.tonight) {finished+=it}
        }}
    }
    private fun startTrial()=compose.onNodeWithTag("onboarding-start-trial").performScrollTo()

    @Test fun unconfiguredBillingKeepsTheTonightStepAndStartsRecordingByDefault() {
        billing=BillingState(AccessState.unavailable,enforced=false)
        show()
        compose.onNodeWithTag("onboarding-draft").assertExists()
        // As on iOS the last step starts recording: the switch is on and the primary reads "Start my day".
        compose.onNodeWithTag("onboarding-start-now").performScrollTo().assertIsOn()
        compose.onNodeWithTag("onboarding-start-trial").assertDoesNotExist()
        compose.onNodeWithTag("onboarding-trial-timeline").assertDoesNotExist()
        compose.onNodeWithTag("onboarding-continue").performScrollTo().assertTextEquals(target.getString(R.string.onboarding_tonight_start)).performClick()
        compose.waitUntil {finished.isNotEmpty()}
        assertFalse(finished.single().trial);assertTrue(finished.single().start)
    }
    @Test fun unconfiguredBillingStillLetsSomeoneFinishWithoutRecording() {
        billing=BillingState(AccessState.unavailable,enforced=false)
        show()
        compose.onNodeWithTag("onboarding-start-now").performScrollTo().assertIsOn().performClick().assertIsOff()
        compose.onNodeWithTag("onboarding-continue").performScrollTo().assertTextEquals(target.getString(R.string.onboarding_finish)).performClick()
        compose.waitUntil {finished.isNotEmpty()}
        assertFalse(finished.single().start)
    }
    @Test fun existingAccessKeepsTheTonightStep() {
        billing=BillingState(AccessState.active,catalog(),enforced=true,activeProductId="whereday_export_lifetime")
        show()
        compose.onNodeWithTag("onboarding-draft").assertExists()
        compose.onNodeWithTag("onboarding-start-trial").assertDoesNotExist()
        compose.onNodeWithTag("onboarding-trial-done").assertDoesNotExist()
    }
    @Test fun freeWeekPreselectsYearlyThenAVerifiedCheckoutOpensYoureIn() {
        show()
        compose.onNodeWithTag("onboarding-trial-timeline").assert(hasContentDescription("$29.99",substring=true))
            .assert(hasContentDescription(target.getString(R.string.onboarding_trial_evening_body),substring=true))
            .assert(hasContentDescription(target.getString(R.string.onboarding_trial_when_evening,"9:00 PM"),substring=true,ignoreCase=true))
        compose.onNodeWithText(target.getString(R.string.onboarding_trial_title),useUnmergedTree=true).assertExists()
        compose.onNodeWithTag("onboarding-plan-yearly").assertIsSelected()
        compose.onNodeWithTag("onboarding-plan-weekly").assertIsNotSelected()
        compose.onNodeWithTag("onboarding-trial-disclosure").assert(hasText("$29.99",substring=true))
        startTrial().assertTextEquals(target.getString(R.string.onboarding_trial_start)).performClick()
        assertEquals(listOf(StorePlan.yearly),bought)
        // Play's sheet and verification are in flight; nothing finishes or schedules yet.
        compose.runOnIdle {checkout=true}
        startTrial().assertIsNotEnabled()
        assertTrue(finished.isEmpty());assertEquals(0,notificationRequests)
        compose.runOnIdle {checkout=false;billing=billing.copy(access=AccessState.active,notice=BillingNotice.verified,activeProductId="whereday_individual")}
        compose.onNodeWithTag("onboarding-trial-done").assertExists()
        compose.onNodeWithTag("onboarding-back").assertDoesNotExist()
        compose.onNodeWithTag("onboarding-recap-toggle").performScrollTo().assertIsOff()
        compose.onNodeWithText(target.getString(R.string.onboarding_done_title),useUnmergedTree=true).assertExists()
        compose.onNodeWithTag("onboarding-continue").performScrollTo().assertTextEquals(target.getString(R.string.onboarding_done_open)).performClick()
        compose.waitUntil {finished.isNotEmpty()}
        val decision=finished.single()
        assertTrue(decision.trial);assertTrue(decision.start);assertFalse(decision.recap)
    }
    @Test fun recapToggleAsksForNotificationsOnlyAfterThePurchase() {
        show()
        compose.onNodeWithTag("onboarding-recap-toggle").assertDoesNotExist()
        startTrial().performClick()
        compose.runOnIdle {billing=billing.copy(access=AccessState.active)}
        assertEquals(0,notificationRequests)
        compose.onNodeWithTag("onboarding-recap-toggle").performScrollTo().assertIsOff().performClick()
        assertEquals(1,notificationRequests)
        compose.runOnIdle {notifications=true}
        compose.onNodeWithTag("onboarding-recap-toggle").assertIsOn()
        compose.onNodeWithTag("onboarding-continue").performScrollTo().performClick()
        compose.waitUntil {finished.isNotEmpty()}
        assertTrue(finished.single().recap);assertTrue(finished.single().trial)
    }
    @Test fun lifetimeChangesTheActionAndTimeline() {
        show()
        compose.onNodeWithTag("onboarding-plan-lifetime").performScrollTo().performClick().assertIsSelected()
        startTrial().assertTextEquals(target.getString(R.string.onboarding_trial_buy_lifetime,"$49.99"))
        compose.onNodeWithTag("onboarding-trial-timeline").assert(hasContentDescription(target.getString(R.string.onboarding_trial_for_good_body,"$49.99"),substring=true))
        compose.onNodeWithTag("onboarding-trial-disclosure").assertTextEquals(target.getString(R.string.paywall_disclosure_lifetime,"$49.99"))
        startTrial().performClick()
        assertEquals(listOf(StorePlan.lifetime),bought)
    }
    @Test fun withoutAnEligibleTrialNothingPromisesOne() {
        billing=BillingState(AccessState.free,catalog(trial=0),enforced=true)
        show()
        startTrial().assertTextEquals(target.getString(R.string.paywall_cta_subscribe))
        compose.onAllNodesWithText(target.getString(R.string.onboarding_trial_start)).assertCountEquals(0)
        compose.onAllNodesWithText(target.getString(R.string.paywall_badge_trial,7)).assertCountEquals(0)
        compose.onAllNodesWithText(target.getString(R.string.onboarding_trial_kicker),ignoreCase=true).assertCountEquals(0)
        compose.onNodeWithTag("onboarding-trial-disclosure").assertTextEquals(
            "${target.getString(R.string.paywall_disclosure_yearly,"$29.99")} ${target.getString(R.string.onboarding_trial_cancel_plan)}")
    }
    @Test fun loadingAndFailedQueriesOfferRetry() {
        billing=BillingState(AccessState.checking,busy=true,enforced=true)
        show()
        compose.onNodeWithTag("onboarding-trial-loading").assertExists()
        startTrial().assertIsNotEnabled()
        compose.runOnIdle {billing=BillingState(AccessState.free,enforced=true)}
        compose.onNodeWithTag("onboarding-trial-failed").assertExists()
        compose.onNodeWithTag("onboarding-all-plans").assertDoesNotExist()
        compose.onNodeWithTag("onboarding-trial-retry").performScrollTo().performClick()
        assertEquals(1,retries)
        compose.runOnIdle {billing=BillingState(AccessState.free,catalog(),enforced=true)}
        startTrial().assertIsEnabled()
    }
    @Test fun canceledAndPendingCheckoutsStayOnTheFreeWeek() {
        show()
        startTrial().performClick()
        compose.runOnIdle {billing=billing.copy(notice=BillingNotice.canceled)}
        compose.onNodeWithTag("paywall-notice").assertTextEquals(target.getString(R.string.billing_canceled))
        startTrial().assertIsEnabled()
        compose.runOnIdle {billing=billing.copy(access=AccessState.pending,notice=BillingNotice.pendingPayment)}
        compose.onNodeWithTag("paywall-pending").assertExists()
        startTrial().assertIsNotEnabled()
        assertTrue(finished.isEmpty())
    }
    @Test fun restoredAccessLandsOnTheTonightStep() {
        show()
        compose.onNodeWithTag("paywall-restore").performScrollTo().performClick()
        assertEquals(1,restores)
        compose.runOnIdle {billing=billing.copy(access=AccessState.active)}
        compose.onNodeWithTag("onboarding-draft").assertExists()
        compose.onNodeWithTag("onboarding-trial-done").assertDoesNotExist()
    }
    @Test fun allPlansSheetLeadsWithTheStrongestIntentAndSharesTheChoice() {
        show(intents=setOf(OnboardingIntent.days,OnboardingIntent.routes,OnboardingIntent.notes))
        compose.onNodeWithTag("onboarding-all-plans").performScrollTo().performClick()
        compose.onNodeWithTag("paywall-sheet").assertExists()
        compose.onNodeWithTag("paywall-lead-notes").assertExists()
        compose.onNodeWithText(target.getString(R.string.onboarding_lead_notes_title)).assertExists()
        compose.onNodeWithTag("paywall-plan-yearly").assertIsSelected()
        compose.onNodeWithTag("paywall-plan-weekly").performScrollTo().performClick()
        compose.onNodeWithTag("paywall-plan-weekly").assertIsSelected()
        compose.onNodeWithTag("paywall-sheet-close").performClick()
        compose.waitUntil {compose.onAllNodesWithTag("paywall-sheet").fetchSemanticsNodes().isEmpty()}
        compose.onNodeWithTag("onboarding-plan-weekly").assertIsSelected()
        startTrial().performClick()
        assertEquals(listOf(StorePlan.weekly),bought)
    }
    @Test fun routesLeadWhenNotesAreNotPicked() {
        show(intents=setOf(OnboardingIntent.countries,OnboardingIntent.routes))
        compose.onNodeWithTag("onboarding-all-plans").performScrollTo().performClick()
        compose.onNodeWithTag("paywall-lead-routes").assertExists()
    }
    @Test fun withoutLocationTheTimelineSaysWhenRecordingStarts() {
        show(authorization=UsageAuthorization.unknown)
        compose.onNodeWithTag("onboarding-trial-timeline").assert(hasContentDescription(target.getString(R.string.onboarding_trial_today_no_location),substring=true))
        startTrial().performClick()
        compose.runOnIdle {billing=billing.copy(access=AccessState.active)}
        compose.onNodeWithText(target.getString(R.string.onboarding_done_body_no_location)).assertExists()
        compose.onNodeWithTag("onboarding-continue").performScrollTo().performClick()
        compose.waitUntil {finished.isNotEmpty()}
        assertFalse(finished.single().start)
    }
}
