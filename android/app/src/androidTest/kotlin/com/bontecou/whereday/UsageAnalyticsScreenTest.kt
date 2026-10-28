package com.bontecou.whereday

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.platform.*
import org.junit.*
import org.junit.Assert.*

class UsageAnalyticsScreenTest {
    @get:Rule val compose=createComposeRule()
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    @Test fun showingScreenAndCancellingNeverApproveSharing() {
        val changes=mutableListOf<Boolean>()
        compose.setContent {MaterialTheme {UsageAnalyticsForm(UsageAnalyticsStatus(loaded=true,available=true),UsageControlState(),{}, {changes+=it})}}
        compose.onNodeWithTag("usage-analytics-switch").assertIsOff().performClick()
        compose.onNodeWithText("Share optional usage analytics?").assertIsDisplayed()
        assertTrue(changes.isEmpty())
        compose.onNodeWithText("Keep sharing off").performClick()
        compose.onNodeWithTag("usage-analytics-switch").assertIsOff()
        assertTrue(changes.isEmpty())
    }
    @Test fun explicitAgreementApprovesAndTurningOffIsImmediate() {
        val changes=mutableListOf<Boolean>()
        compose.setContent {var enabled by remember {mutableStateOf(false)};MaterialTheme {
            UsageAnalyticsForm(UsageAnalyticsStatus(loaded=true,available=true,enabled=enabled),UsageControlState(),{}, {changes+=it;enabled=it})
        }}
        compose.onNodeWithTag("usage-analytics-switch").performClick()
        assertTrue(changes.isEmpty())
        compose.onNodeWithText("Agree and share").performClick()
        compose.onNodeWithTag("usage-analytics-switch").assertIsOn().performClick()
        assertEquals(listOf(true,false),changes)
        compose.onNodeWithTag("usage-analytics-switch").assertIsOff()
    }
    @Test fun unavailableBuildCannotApproveButCanRevokeOldPreference() {
        var enabled by mutableStateOf(false);val changes=mutableListOf<Boolean>()
        compose.setContent {MaterialTheme {UsageAnalyticsForm(UsageAnalyticsStatus(loaded=true,enabled=enabled),UsageControlState(),{}, {changes+=it;enabled=it})}}
        compose.onNodeWithTag("usage-analytics-switch").assertIsNotEnabled()
        compose.runOnIdle {enabled=true}
        compose.onNodeWithTag("usage-analytics-switch").assertIsEnabled().performClick()
        assertEquals(listOf(false),changes)
    }
    @Test fun damagedSettingsCanOnlyResetOff() {
        val changes=mutableListOf<Boolean>()
        compose.setContent {MaterialTheme {UsageAnalyticsForm(UsageAnalyticsStatus(loaded=true,available=true,error=UsageAnalyticsError.storage),UsageControlState(),{}, {changes+=it})}}
        compose.onNodeWithTag("usage-analytics-switch").assertIsNotEnabled()
        compose.onNodeWithText("Reset sharing to off").performScrollTo().performClick()
        assertEquals(listOf(false),changes)
    }
    @Test fun darkLargeTextCanReachDisclosureAndConfirmation() {
        var agreed=false
        compose.setContent {val density=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(density.density,1.6f)) {
            MaterialTheme(colorScheme=darkColorScheme()) {UsageAnalyticsForm(UsageAnalyticsStatus(loaded=true,available=true),UsageControlState(),{}, {agreed=it})}
        }}
        compose.onNodeWithText("What you would share").performScrollTo().assertIsDisplayed()
        compose.onNodeWithTag("usage-analytics-switch").performScrollTo().performClick()
        compose.onNodeWithText("Agree and share").performClick()
        assertTrue(agreed)
    }
}
