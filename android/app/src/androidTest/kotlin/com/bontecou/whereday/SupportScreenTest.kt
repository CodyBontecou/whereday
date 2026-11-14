package com.bontecou.whereday

import android.content.Intent
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

class SupportScreenTest {
    @get:Rule val compose=createComposeRule()
    private val info=SupportInfo("0.1.0-test (1)",35,"QA device")
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    @Test fun emptyLogsDisableCopyAndClear() {
        compose.setContent {MaterialTheme {ActivityLogsScreen(emptyList(),info,{},{}) {fail("Empty copy")}}}
        compose.onNodeWithText("No log entries.").assertExists()
        compose.onNodeWithText("Copy logs").assertIsNotEnabled()
        compose.onNodeWithText("Clear logs").assertIsNotEnabled()
    }
    @Test fun copyIncludesOnlyReviewedMetadataAndFixedSnapshotAndClearNeedsConfirmation() {
        val log=ActivityLog();log.record(AppEvent.appOpened)
        var copied:String?=null
        compose.setContent {val entries by log.entries.collectAsState();MaterialTheme {ActivityLogsScreen(entries,info,{},log::clear) {copied=it}}}
        compose.onNodeWithText("Copy logs").performClick()
        assertEquals(info.reportHeader+"\n\n"+log.exportText(),copied)
        compose.onNodeWithText("Clear logs").performClick()
        compose.onNodeWithText("Clear activity logs?").assertIsDisplayed();assertEquals(1,log.snapshot().size)
        compose.onNodeWithText("Cancel").performClick();assertEquals(1,log.snapshot().size)
        compose.onNodeWithText("Clear logs").performClick()
        compose.onAllNodesWithText("Clear logs").filter(hasClickAction()).onLast().performClick()
        compose.onNodeWithText("No log entries.").assertExists()
        assertTrue(log.snapshot().isEmpty())
        assertNotNull(copied) // Clearing cannot withdraw the earlier copy.
    }
    @Test fun supportLinksAndFeedbackUseExplicitUserActions() {
        var log=0;var release=0;var feedback=0;var email=0;val links=mutableListOf<SupportLink>()
        compose.setContent {MaterialTheme {SupportForm(info,false,{},{log++},{release++},{links+=it},{feedback++},{email++})}}
        assertEquals(0,feedback);assertTrue(links.isEmpty())
        compose.onNodeWithText("View activity logs").performScrollTo().performClick()
        compose.onNodeWithText("Release notes").performScrollTo().performClick()
        compose.onNodeWithText("Send feedback").performScrollTo().performClick()
        compose.onNodeWithText("Copy support email address").performScrollTo().performClick()
        compose.onNodeWithText("Join the community").performScrollTo().performClick()
        compose.onNodeWithText("Whereday website").performScrollTo().performClick()
        compose.onNodeWithText("Privacy policy").performScrollTo().performClick()
        compose.onNodeWithText("Terms of service").performScrollTo().performClick()
        assertEquals(listOf(SupportLink.community,SupportLink.website,SupportLink.privacy,SupportLink.terms),links)
        assertEquals(1,log);assertEquals(1,release);assertEquals(1,feedback);assertEquals(1,email)
    }
    @Test fun feedbackDraftHasNoLogOrJournalAttachmentOrGrant() {
        val intent=SupportIntents.feedback(info)
        assertEquals(Intent.ACTION_SENDTO,intent.action);assertEquals("mailto",intent.data?.scheme)
        val decoded=android.net.Uri.decode(intent.data.toString())
        assertTrue(decoded.contains(SupportIntents.EMAIL));assertTrue(decoded.contains(info.reportHeader))
        assertNull(intent.clipData);assertNull(intent.extras)
        assertEquals(0,intent.flags and (Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION))
    }
    @Test fun darkLargeTextKeepsLogsAndConfirmationControlsReachable() {
        val log=ActivityLog();log.record(AppEvent.recordingStartFailed)
        var clear=0
        compose.setContent {val density=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(density.density,1.6f)) {
            MaterialTheme(colorScheme=darkColorScheme()) {ActivityLogsScreen(log.snapshot(),info,{}, {clear++},{})}
        }}
        compose.onNodeWithText("Clear logs").performScrollTo().performClick()
        compose.onNodeWithText("Clear activity logs?").assertIsDisplayed()
        compose.onNodeWithText("Cancel").performClick();assertEquals(0,clear)
    }
    @Test fun manualRatingIsDisabledForUnavailableBuildAndAnAvailableActionOnlyCallsTheListingHandler() {
        var available by mutableStateOf(false);var opened=0
        compose.setContent {MaterialTheme {SupportForm(info,false,{},{},{},{},{},{},available) {opened++}}}
        compose.onNodeWithTag("support-rate-app").performScrollTo().assertIsNotEnabled()
        compose.onNodeWithText("Google Play reviews are unavailable for this build.").assertExists()
        compose.runOnIdle {available=true}
        compose.onNodeWithTag("support-rate-app").performScrollTo().assertIsEnabled().performClick()
        assertEquals(1,opened)
        assertEquals("market://details?id=com.bontecou.whereday",SupportIntents.review().data.toString())
        assertEquals("https://play.google.com/store/apps/details?id=com.bontecou.whereday",SupportIntents.review(true).data.toString())
    }
}
