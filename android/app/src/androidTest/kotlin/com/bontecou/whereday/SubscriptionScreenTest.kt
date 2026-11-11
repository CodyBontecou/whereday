package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.platform.UriHandler
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.billing.*
import org.junit.*
import org.junit.Assert.*

/** Presentation only: no store purchase, cancellation or entitlement is simulated. */
class SubscriptionScreenTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val opened=mutableListOf<String>()
    private var backs=0
    private var plans=0
    private val manage="https://play.google.com/store/account/subscriptions?sku=individual&package=com.bontecou.whereday"
    @Before fun isolated() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    private fun summary(product:String="individual",renewing:Boolean=false,known:Boolean=true,alsoRenewing:Boolean=false)=
        PlanRetention.summary(BillingState(AccessState.active,activeProductId=product,autoRenewing=renewing,
            activePurchaseHash="fixture",subscriptionRenewing=alsoRenewing,renewalKnown=known),
            PlanHistory(true,StorePlan.yearly,"fixture"),"individual")
    private fun show(summary:SubscriptionSummary) {
        compose.setContent {CompositionLocalProvider(LocalUriHandler provides object:UriHandler {
            override fun openUri(uri:String) {opened+=uri}
        }) {WheredayTheme(dark=false) {SubscriptionScreen(summary,false,manage,{backs++},{plans++})}}}
    }
    private fun click(tag:String)=compose.onNodeWithTag(tag).performScrollTo().performClick()
    @Test fun renewingPlanRequiresTheSummaryThenOpensExactlyTheProvidedPlayPage() {
        show(summary(renewing=true));click("subscription-manage-or-cancel")
        compose.onNodeWithTag("before-you-go").assertExists();assertTrue(opened.isEmpty())
        click("before-you-go-keep");compose.onNodeWithTag("before-you-go").assertDoesNotExist();assertTrue(opened.isEmpty())
        click("subscription-manage-or-cancel");click("before-you-go-continue")
        assertEquals(listOf(manage),opened);assertEquals(0,backs)
    }
    @Test fun backDismissesTheSummaryBeforeLeavingThePage() {
        show(summary(renewing=true));click("subscription-manage-or-cancel")
        Espresso.pressBack();compose.onNodeWithTag("before-you-go").assertDoesNotExist()
        assertEquals(0,backs);assertTrue(opened.isEmpty())
        Espresso.pressBack();compose.runOnIdle {assertEquals(1,backs)}
    }
    @Test fun offlineSubscriptionKeepsNeutralCopyAndPlayManagement() {
        show(summary(known=false))
        compose.onNodeWithText("Using previously verified access. Connect to refresh purchase status.").assertExists()
        compose.onNodeWithText("Canceled. Your plan stays active until the end of this period, and Google Play shows the date.").assertDoesNotExist()
        compose.onNodeWithTag("subscription-manage-or-cancel").assertDoesNotExist()
        click("subscription-manage-play");assertEquals(listOf(manage),opened)
    }
    @Test fun offlineLifetimeCannotClaimThatNoOtherSubscriptionRenews() {
        show(summary(product="lifetime",known=false))
        compose.onNodeWithText("Paid once. Nothing renews, so there is nothing to cancel.").assertDoesNotExist()
        compose.onNodeWithText("Using previously verified access. Connect to refresh purchase status.").assertExists()
        click("subscription-manage-play");assertEquals(listOf(manage),opened)
    }
    @Test fun lifetimeWithARenewingSubscriptionWarnsAndKeepsPlayReachable() {
        show(summary(product="lifetime",alsoRenewing=true))
        compose.onNodeWithText("A Whereday subscription is still renewing in Google Play. Lifetime does not cancel it.").assertExists()
        click("subscription-manage-play");assertEquals(listOf(manage),opened)
    }
    @Test fun aLapseOffersPlansWithoutOpeningPlayOrPromisingATrial() {
        show(SubscriptionSummary(SubscriptionStatus.lapsed))
        compose.onNodeWithTag("subscription-ended-title").assertExists()
        click("subscription-see-plans");assertEquals(1,plans);assertTrue(opened.isEmpty())
        compose.onNodeWithText("Weekly, Yearly or Lifetime. Google Play offers this account a free week on subscriptions.").assertDoesNotExist()
    }
}
