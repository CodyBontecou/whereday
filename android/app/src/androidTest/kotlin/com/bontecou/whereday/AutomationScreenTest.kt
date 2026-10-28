package com.bontecou.whereday

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.platform.*
import org.junit.*
import org.junit.Assert.*

class AutomationScreenTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val identity get()=requireNotNull(AppGraph.from(context).automationAccess.installed(context.packageName))
    @Before fun guard() {check(context.packageName=="com.bontecou.whereday.mapqa")}
    @Test fun openingAndChoosingDoesNotGrantUntilExplicitConfirmation() {
        var approved:Set<AutomationScope>?=null
        compose.setContent {MaterialTheme {AutomationForm(AutomationUiState(loaded=true,installed=identity),{}, {_,scopes ->approved=scopes},{},{},{})}}
        compose.onNodeWithText("Access disabled").assertExists()
        compose.onNodeWithTag("automation-scope-summary").performScrollTo().performClick()
        assertNull(approved)
        compose.onNodeWithText("Save automation access").performScrollTo().performClick()
        compose.onNodeWithText("Allow these Automate actions?").assertIsDisplayed()
        assertNull(approved)
        compose.onNodeWithText("Cancel").performClick();assertNull(approved)
        compose.onNodeWithText("Save automation access").performScrollTo().performClick()
        compose.onNodeWithText("Allow selected actions").performClick()
        assertEquals(setOf(AutomationScope.summary),approved)
    }
    @Test fun absentClientHasNoEnabledScopeOrSaveAndKeepsInstallAndReloadReachable() {
        var install=0;var reload=0
        compose.setContent {MaterialTheme {AutomationForm(AutomationUiState(loaded=true),{}, {_,_->fail("Absent client grant")},{},{reload++},{install++})}}
        compose.onNodeWithTag("automation-scope-summary").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Save automation access").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Get Automate").performScrollTo().performClick()
        compose.onNodeWithText("Check installed app").performScrollTo().performClick()
        assertEquals(1,install);assertEquals(1,reload)
    }
    @Test fun systemBackProtectsUnsavedChoicesAndDoesNotChangeSavedAccess() {
        var back=0
        compose.setContent {MaterialTheme {AutomationForm(AutomationUiState(loaded=true,installed=identity),{back++},{_,_->},{},{},{})}}
        compose.onNodeWithTag("automation-scope-visits").performScrollTo().performClick()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("Discard unsaved automation choices?").assertIsDisplayed()
        assertEquals(0,back)
        compose.onNodeWithText("Cancel").performClick()
        androidx.test.espresso.Espresso.pressBack()
        compose.onNodeWithText("Discard choices").performClick();assertEquals(1,back)
    }
    @Test fun corruptAccessCanResetAndRevokeStillRequiresConfirmation() {
        var revoked=0
        compose.setContent {MaterialTheme {AutomationForm(AutomationUiState(loaded=true,error="Corrupt fixture"),{},{_,_->},{revoked++},{},{})}}
        compose.onNodeWithText("Disable automation access").performScrollTo().performClick()
        compose.onNodeWithText("Disable automation access?").assertIsDisplayed();assertEquals(0,revoked)
        compose.onNodeWithText("Disable access").performClick();assertEquals(1,revoked)
    }
    @Test fun darkLargeTextKeepsScopeDescriptionsAndConfirmationActionsReachable() {
        var granted=false
        compose.setContent {
            val density=androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,1.6f)) {
                MaterialTheme(colorScheme=darkColorScheme()) {AutomationForm(AutomationUiState(loaded=true,installed=identity),{},{_,_->granted=true},{},{},{})}
            }
        }
        compose.onNodeWithTag("automation-scope-exports").performScrollTo().performClick()
        compose.onNodeWithText("Save automation access").performScrollTo().performClick()
        compose.onNodeWithText("Allow selected actions").assertIsDisplayed().performClick()
        assertTrue(granted)
    }
}
