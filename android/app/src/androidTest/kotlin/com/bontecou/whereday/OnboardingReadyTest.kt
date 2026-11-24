package com.bontecou.whereday

import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.platform.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*

class OnboardingReadyTest {
    @get:Rule val compose=createComposeRule()
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    private fun ready()=compose.walkOnboarding()
    @Test fun withoutPermissionFinishesWithoutAStartChoice() {
        val finished=mutableListOf<Boolean>()
        compose.setContent {MaterialTheme {Onboarding({},{}) {finished+=it.start}}}
        ready();compose.onNodeWithTag("onboarding-start-now").assertDoesNotExist()
        compose.onNodeWithText("Open Whereday").performScrollTo().performClick()
        compose.waitUntil {finished.isNotEmpty()};assertEquals(listOf(false),finished)
    }
    @Test fun permissionStartsRecordingByDefaultAndAnOffChoiceSurvivesBackNavigation() {
        val finished=mutableListOf<Boolean>()
        val restoration=StateRestorationTester(compose)
        restoration.setContent {MaterialTheme {Onboarding({},{},authorization=UsageAuthorization.foreground) {finished+=it.start}}}
        // On by default, as iOS's last step starts recording: the primary reads "Start my day".
        ready();compose.onNodeWithTag("onboarding-start-now").performScrollTo().assertIsOn()
        compose.onNodeWithTag("onboarding-continue").performScrollTo().assertTextEquals("Start my day")
        compose.onNodeWithTag("onboarding-start-now").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding-back").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding-continue").performScrollTo().performClick()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("onboarding-start-now").performScrollTo().assertIsOff()
        assertTrue(finished.isEmpty())
        compose.onNodeWithText("Open Whereday").performScrollTo().performClick()
        compose.waitUntil {finished.isNotEmpty()};assertEquals(listOf(false),finished)
    }
    @Test fun revokingPermissionBeforeFinishCannotUseAnEarlierStartChoice() {
        var authorization by mutableStateOf(UsageAuthorization.foreground)
        val finished=mutableListOf<Boolean>()
        compose.setContent {MaterialTheme {Onboarding({},{},authorization=authorization) {finished+=it.start}}}
        ready();compose.onNodeWithTag("onboarding-start-now").performScrollTo().assertIsOn()
        compose.runOnIdle {authorization=UsageAuthorization.unknown}
        compose.onNodeWithTag("onboarding-start-now").assertDoesNotExist()
        compose.onNodeWithText("Open Whereday").performScrollTo().performClick()
        compose.waitUntil {finished.isNotEmpty()};assertEquals(listOf(false),finished)
    }
    @Test fun busyStartCannotBeSubmittedTwiceAndFailureCanBeFinishedWithRecordingOff() {
        val entered=mutableListOf<Boolean>();val acknowledgment=CompletableDeferred<Unit>()
        compose.setContent {MaterialTheme {Onboarding({},{},authorization=UsageAuthorization.foreground) {
            entered+=it.start;if(it.start) {acknowledgment.await();throw OnboardingStartFailed()}
        }}}
        ready();compose.onNodeWithText("Start my day").performScrollTo().performClick()
        compose.onNodeWithTag("onboarding-continue").assertIsNotEnabled()
        compose.onNodeWithTag("onboarding-start-now").performScrollTo().assertIsNotEnabled()
        assertEquals(listOf(true),entered)
        acknowledgment.complete(Unit)
        compose.waitUntil {compose.onAllNodesWithText("Recording could not start. Try again, or turn off immediate recording to finish setup.").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("onboarding-start-now").performScrollTo().performClick()
        compose.onNodeWithText("Open Whereday").performScrollTo().performClick()
        compose.waitUntil {entered.size==2};assertEquals(listOf(true,false),entered)
    }
    @Test fun darkLargeTextKeepsReadyChoiceAndFinishReachable() {
        var started=false
        compose.setContent {val density=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(density.density,1.6f)) {
            MaterialTheme(colorScheme=darkColorScheme()) {Onboarding({},{},authorization=UsageAuthorization.always) {started=it.start}}
        }}
        ready();compose.onNodeWithTag("onboarding-start-now").performScrollTo().assertIsOn()
        compose.onNodeWithText("Start my day").performScrollTo().performClick()
        compose.waitUntil {started}
    }
}
