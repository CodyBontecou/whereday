package com.bontecou.whereday

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.billing.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

/** Real app navigation with a presentation-only plan fixture; purchase access is unchanged. */
class SubscriptionSettingsIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()

    @Test fun settingsOpensTheCurrentPlanAndBackReturnsThroughTheSummary():Unit=runBlocking {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName=="com.bontecou.whereday.mapqa")
        val graph=AppGraph.from(target)
        val original=graph.preferences.settings.first()
        val oldPreview=SubscriptionPreview.override.value
        val access=withTimeout(5000) {graph.purchases.state.first {it.access!=AccessState.checking && !it.busy}}.access
        try {
            val active=BillingState(AccessState.active,activeProductId=graph.purchases.subscriptionId,
                activePurchaseHash="navigation-preview",autoRenewing=true,renewalKnown=true)
            val history=PlanHistory(true,StorePlan.yearly,"navigation-preview")
            SubscriptionPreview.override.value=SubscriptionPreview.Value(active,history)
            graph.preferences.update {it.copy(onboarded=true)}
            compose.waitUntil(15000) {compose.onAllNodesWithTag("map-header").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
            compose.openSettings()
            compose.onNodeWithTag("settings-plan").performScrollTo().performClick()
            compose.onNodeWithTag("subscription-plan-title").assertTextEquals("Yearly subscription")
            compose.onNodeWithTag("subscription-manage-or-cancel").performScrollTo().performClick()
            compose.onNodeWithTag("before-you-go").assertExists()
            Espresso.pressBack()
            compose.onNodeWithTag("before-you-go").assertDoesNotExist()
            compose.onNodeWithTag("subscription-screen").assertExists()
            Espresso.pressBack()
            compose.onNodeWithTag("settings-screen").assertExists()
            compose.onNodeWithTag("settings-plan").assertIsDisplayed()

            SubscriptionPreview.override.value=SubscriptionPreview.Value(active.copy(autoRenewing=false,renewalKnown=false),history)
            compose.waitUntil(5000) {compose.onAllNodesWithText("Using previously verified access. Connect to refresh purchase status.").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("settings-plan").performClick()
            compose.onNodeWithText("Using previously verified access. Connect to refresh purchase status.").assertExists()
            compose.onNodeWithTag("subscription-manage-or-cancel").assertDoesNotExist()
            compose.onNodeWithTag("subscription-manage-play").performScrollTo().assertIsDisplayed()
            compose.onNodeWithTag("subscription-back").performScrollTo().performClick()
            compose.onNodeWithTag("settings-screen").assertExists()
            assertEquals(access,graph.purchases.state.value.access)
        } finally {
            SubscriptionPreview.override.value=oldPreview
            graph.preferences.update {it.copy(onboarded=original.onboarded)}
        }
    }
}
