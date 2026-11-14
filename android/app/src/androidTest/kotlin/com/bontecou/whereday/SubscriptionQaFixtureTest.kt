package com.bontecou.whereday

import android.app.Activity
import android.app.Instrumentation.ActivityResult
import android.content.Intent
import android.content.IntentFilter
import android.net.Uri
import android.os.ParcelFileDescriptor
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.billing.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

/** Shell provider/real owner integration, not spoken TalkBack evidence. All external VIEW launches are blocked. */
class SubscriptionQaFixtureTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val instrumentation get()=InstrumentationRegistry.getInstrumentation()
    private val uri=Uri.parse("content://com.bontecou.whereday.mapqa.subscriptionpreview")
    @Before fun isolated() {check(instrumentation.targetContext.packageName=="com.bontecou.whereday.mapqa")}
    @After fun reset() {SubscriptionPreview.override.value=null}
    private fun shell(mode:String) {
        check(mode in setOf("renewing-yearly","offline-subscription","offline-lifetime","lifetime-renewing","clear"))
        val command="content call --uri $uri --method set --arg $mode"
        val output=ParcelFileDescriptor.AutoCloseInputStream(instrumentation.uiAutomation.executeShellCommand(command)).bufferedReader().use {it.readText()}
        assertTrue(output,output.contains("presentation=$mode"))
    }
    private fun click(tag:String)=compose.onNodeWithTag(tag).performScrollTo().performClick()

    @Test fun applicationUidCannotConfigureTheShellOnlyProvider() {
        val original=SubscriptionPreview.override.value
        try {instrumentation.targetContext.contentResolver.call(uri,"set","renewing-yearly",null);fail("App UID must be denied")}
        catch(_:SecurityException) {}
        assertSame(original,SubscriptionPreview.override.value)
    }

    @Test fun shellFixtureDrivesSettingsAndExportWithoutChangingLiveBillingOrLaunchingPlay():Unit=runBlocking {
        val graph=AppGraph.from(instrumentation.targetContext)
        val preferences=graph.preferences.settings.first()
        val live=withTimeout(5000) {graph.purchases.state.first {it.access!=AccessState.checking && !it.busy}}
        val history=graph.purchases.planHistory.value
        val monitor=instrumentation.addMonitor(IntentFilter(Intent.ACTION_VIEW),ActivityResult(Activity.RESULT_CANCELED,null),true)
        try {
            shell("renewing-yearly")
            graph.preferences.update {it.copy(onboarded=true)}
            compose.openSettings()
            click("settings-plan")
            compose.onNodeWithTag("subscription-plan-title").assertTextEquals("Yearly subscription")
            click("subscription-manage-or-cancel");click("before-you-go-continue")
            compose.onNodeWithTag("before-you-go").assertDoesNotExist()
            click("subscription-back")
            if(compose.onAllNodesWithTag("settings-back").fetchSemanticsNodes().isNotEmpty()) click("settings-back")
            compose.onNodeWithTag("navigation-archive").performClick()
            click("export-access-manage")
            compose.onNodeWithTag("before-you-go").assertExists()
            click("before-you-go-keep")
            click("export-access-manage");click("before-you-go-continue")
            compose.onNodeWithTag("before-you-go").assertDoesNotExist()
            assertEquals("Fixture must not dispatch a real management URI",0,monitor.hits)
            assertEquals(live,graph.purchases.state.value)
            assertEquals(history,graph.purchases.planHistory.value)
            shell("clear")
            compose.waitForIdle()
            assertNull(SubscriptionPreview.override.value)
        } finally {
            instrumentation.removeMonitor(monitor)
            shell("clear")
            graph.preferences.update {it.copy(onboarded=preferences.onboarded)}
        }
    }

    @Test fun shellOfflineAndLifetimeStatesStayNeutralInActualSettingsAndKeepExportManagement():Unit=runBlocking {
        val graph=AppGraph.from(instrumentation.targetContext)
        val preferences=graph.preferences.settings.first()
        val live=withTimeout(5000) {graph.purchases.state.first {it.access!=AccessState.checking && !it.busy}}
        val history=graph.purchases.planHistory.value
        try {
            graph.preferences.update {it.copy(onboarded=true)}
            for(mode in listOf("offline-subscription","offline-lifetime","lifetime-renewing")) {
                shell(mode)
                compose.openSettings();click("settings-plan")
                if(mode.startsWith("offline-")) {
                    compose.onNodeWithText("Using previously verified access. Connect to refresh purchase status.").assertExists()
                    compose.onNodeWithText("Paid once. Nothing renews, so there is nothing to cancel.").assertDoesNotExist()
                } else compose.onNodeWithText("A Whereday subscription is still renewing in Google Play. Lifetime does not cancel it.").assertExists()
                compose.onNodeWithTag("subscription-manage-or-cancel").assertDoesNotExist()
                compose.onNodeWithTag("subscription-manage-play").performScrollTo().assertIsDisplayed()
                click("subscription-back")
                if(compose.onAllNodesWithTag("settings-back").fetchSemanticsNodes().isNotEmpty()) click("settings-back")
                compose.onNodeWithTag("navigation-archive").performClick()
                compose.onNodeWithTag("export-access-manage").performScrollTo().assertIsDisplayed()
            }
            assertEquals(live,graph.purchases.state.value)
            assertEquals(history,graph.purchases.planHistory.value)
        } finally {shell("clear");graph.preferences.update {it.copy(onboarded=preferences.onboarded)}}
    }
}
