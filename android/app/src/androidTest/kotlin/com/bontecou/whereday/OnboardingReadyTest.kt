package com.bontecou.whereday

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.RecordingDetail
import com.bontecou.whereday.platform.*
import kotlinx.coroutines.CompletableDeferred
import org.junit.*
import org.junit.Assert.*

class OnboardingReadyTest {
    @get:Rule val compose=createComposeRule()
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    private fun remember() {repeat(2) {compose.onNodeWithTag("onboarding-continue").performClick()}}

    @Test fun threeStepsReadAsIosAndStepIndicatorsCount() {
        compose.setContent {WheredayTheme(false) {Onboarding({},{},onImportSelected={}) {}}}
        compose.onNodeWithTag("onboarding-steps").assertContentDescriptionEquals("Step 1 of 3")
        compose.onNodeWithTag("onboarding-title").assertTextEquals("Every day\nis a page.")
        compose.onNodeWithTag("onboarding-dial").assertExists()
        compose.onNodeWithTag("onboarding-import").assertTextContains("I already have data — import",substring=true)
        compose.onNodeWithTag("onboarding-continue").performClick()
        compose.onNodeWithTag("onboarding-steps").assertContentDescriptionEquals("Step 2 of 3")
        compose.onNodeWithTag("onboarding-title").assertTextEquals("It stays\non this phone.")
        compose.onAllNodesWithTag("onboarding-privacy-row").assertCountEquals(3)[1].assertTextContains("No server keeps a copy",substring=true)
        compose.onNodeWithTag("onboarding-back").performClick()
        compose.onNodeWithTag("onboarding-steps").assertContentDescriptionEquals("Step 1 of 3")
    }

    @Test fun withoutPermissionAsksForLocationOrFinishesWithoutIt() {
        val finished=mutableListOf<OnboardingDecision>();var locations=0
        compose.setContent {WheredayTheme(false) {Onboarding({locations++},{}) {finished+=it}}}
        remember()
        compose.onNodeWithTag("onboarding-title").assertTextEquals("How much\nshould it remember?")
        compose.onNodeWithTag("onboarding-detail-routes").assertIsSelected()
        compose.onNodeWithTag("onboarding-note").assertTextEquals("You can change this anytime.")
        compose.onNodeWithTag("onboarding-continue").assertTextContains("Allow location",substring=true).performClick()
        compose.runOnIdle {assertEquals(1,locations);assertTrue(finished.isEmpty())}
        compose.onNodeWithTag("onboarding-finish").assertTextContains("Finish without location",substring=true).performClick()
        compose.waitUntil {finished.isNotEmpty()}
        assertFalse(finished.single().start);assertEquals(RecordingDetail.routes,finished.single().detail)
    }

    @Test fun grantedOffersAnExplicitStartAndKeepsThePresetThroughRestoration() {
        val finished=mutableListOf<OnboardingDecision>()
        val restoration=StateRestorationTester(compose)
        restoration.setContent {WheredayTheme(false) {Onboarding({},{},authorization=UsageAuthorization.foreground) {finished+=it}}}
        remember()
        compose.onNodeWithTag("onboarding-detail-everyStep").performScrollTo().performClick().assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("onboarding-detail-everyStep").assertIsSelected()
        compose.onNodeWithTag("onboarding-detail-routes").assertIsNotSelected()
        assertTrue(finished.isEmpty()) // Restoring a choice is not permission to execute it.
        compose.onNodeWithTag("onboarding-continue").assertTextContains("Start recording",substring=true).performClick()
        compose.waitUntil {finished.isNotEmpty()}
        assertTrue(finished.single().start);assertEquals(RecordingDetail.everyStep,finished.single().detail)
    }

    @Test fun revokingPermissionBeforeFinishCannotStartRecording() {
        var authorization by mutableStateOf(UsageAuthorization.foreground)
        val finished=mutableListOf<Boolean>()
        compose.setContent {WheredayTheme(false) {Onboarding({},{},authorization=authorization) {finished+=it.start}}}
        remember();compose.onNodeWithTag("onboarding-continue").assertTextContains("Start recording",substring=true)
        compose.runOnIdle {authorization=UsageAuthorization.unknown}
        compose.onNodeWithTag("onboarding-continue").assertTextContains("Allow location",substring=true)
        compose.onNodeWithTag("onboarding-finish").performClick()
        compose.waitUntil {finished.isNotEmpty()};assertEquals(listOf(false),finished)
    }

    @Test fun busyStartCannotBeSubmittedTwiceAndFailureCanFinishWithoutRecording() {
        val entered=mutableListOf<Boolean>();val acknowledgment=CompletableDeferred<Unit>()
        compose.setContent {WheredayTheme(false) {Onboarding({},{},authorization=UsageAuthorization.foreground) {
            entered+=it.start;if(it.start) {acknowledgment.await();throw OnboardingStartFailed()}
        }}}
        remember();compose.onNodeWithTag("onboarding-continue").performClick()
        compose.onNodeWithTag("onboarding-continue").assertIsNotEnabled()
        compose.onNodeWithTag("onboarding-finish").assertIsNotEnabled()
        assertEquals(listOf(true),entered)
        acknowledgment.complete(Unit)
        compose.waitUntil {compose.onAllNodesWithTag("onboarding-error").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("onboarding-error").assertTextEquals("Recording could not start. Try again, or choose Not now to finish setup.")
        compose.onNodeWithTag("onboarding-finish").assertTextContains("Not now",substring=true).performClick()
        compose.waitUntil {entered.size==2};assertEquals(listOf(true,false),entered)
    }

    @Test fun deniedOpensSettingsAndDarkLargeTextKeepsActionsReachable() {
        val options=mutableListOf<PermissionAccessKind>();var finished:Boolean?=null
        compose.setContent {val density=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(density.density,1.6f)) {
            WheredayTheme(true) {Onboarding({},{},authorization=UsageAuthorization.denied,onAccessOptions={options+=it}) {finished=it.start}}
        }}
        remember()
        compose.onNodeWithTag("onboarding-note").performScrollTo().assertTextContains("Location access is off",substring=true)
        compose.onNodeWithTag("onboarding-continue").assertIsDisplayed().assertTextContains("Open Android Settings",substring=true).performClick()
        compose.runOnIdle {assertEquals(listOf(PermissionAccessKind.location),options)}
        compose.onNodeWithTag("onboarding-finish").assertIsDisplayed().performClick()
        compose.waitUntil {finished!=null};assertEquals(false,finished)
    }

    @Test fun photoMatchingAndNotificationRequestsAreExplicit() {
        var photos by mutableStateOf(false);val toggles=mutableListOf<Boolean>();var notifications=0
        compose.setContent {WheredayTheme(false) {Onboarding({},{notifications++},authorization=UsageAuthorization.foreground,
            access=RecordingAccessState(LocationPrecision.precise,false),photos=OnboardingPhotos(photos,false) {toggles+=it;photos=it}) {}}}
        remember()
        compose.onNodeWithTag("onboarding-photos").performScrollTo().assertIsOff().performClick().assertIsOn()
        compose.onNodeWithTag("onboarding-notifications").performScrollTo().performClick()
        compose.runOnIdle {assertEquals(listOf(true),toggles);assertEquals(1,notifications)}
    }
}
