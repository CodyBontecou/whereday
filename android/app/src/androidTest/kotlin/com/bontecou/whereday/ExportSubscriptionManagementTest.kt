package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.espresso.Espresso
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.billing.*
import org.junit.*
import org.junit.Assert.*

/** Presentation callbacks only: these tests never open Play or alter billing access. */
class ExportSubscriptionManagementTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private var opens=0
    @Before fun isolated() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    private fun show(renewing:Boolean,known:Boolean=true) {
        val purchase=BillingState(AccessState.active,activeProductId="individual",autoRenewing=renewing,renewalKnown=known)
        compose.setContent {WheredayTheme(dark=false) {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                ExportAccessCard(purchase,false,{}, {},manage={opens++})
            }
        }}
    }
    private fun click(tag:String)=compose.onNodeWithTag(tag).performScrollTo().performClick()
    @Test fun renewingExportManagementRequiresSummaryAndExplicitContinuation() {
        show(renewing=true)
        click("export-access-manage")
        compose.onNodeWithTag("before-you-go").assertExists();assertEquals(0,opens)
        click("before-you-go-keep")
        compose.onNodeWithTag("before-you-go").assertDoesNotExist();assertEquals(0,opens)
        click("export-access-manage");click("before-you-go-continue")
        compose.onNodeWithTag("before-you-go").assertDoesNotExist();assertEquals(1,opens)
    }
    @Test fun backFromExportSummaryDoesNotOpenPlay() {
        show(renewing=true);click("export-access-manage")
        Espresso.pressBack()
        compose.onNodeWithTag("before-you-go").assertDoesNotExist();assertEquals(0,opens)
        compose.onNodeWithTag("export-access-manage").assertExists()
    }
    @Test fun cachedRenewalFlagDoesNotPresentCancellationAsKnown() {
        show(renewing=true,known=false);click("export-access-manage")
        compose.onNodeWithTag("before-you-go").assertDoesNotExist();assertEquals(1,opens)
    }
    @Test fun alreadyEndingSubscriptionOpensManagementDirectly() {
        show(renewing=false);click("export-access-manage")
        compose.onNodeWithTag("before-you-go").assertDoesNotExist();assertEquals(1,opens)
    }
}
