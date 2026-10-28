package com.bontecou.whereday

import androidx.compose.material3.*
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.unit.Density
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.export.ExportFormat
import com.bontecou.whereday.platform.*
import org.junit.*
import org.junit.Assert.*
import java.time.Instant

class HistoryImportScreenTest {
    @get:Rule val compose=createComposeRule()
    private val review=HistoryImportReview("review",ExportFormat.markdown,1,2,1,0,1,listOf("Row 9: Missing latitude"),Instant.EPOCH,Instant.EPOCH,"en-US","UTC")
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    @Test fun reviewDefaultsToKeepingRecordsAndDoesNotAutomaticallyWrite() {
        val choices=mutableListOf<HistoryImportPolicy>()
        compose.setContent {MaterialTheme {HistoryImportForm(HistoryImportState(HistoryImportPhase.review,review),{},{}) {_,policy->choices+=policy}}}
        compose.onNodeWithText("Your journal has not changed. Review these records before importing.").assertExists()
        compose.onNodeWithTag("history-import-keep").performScrollTo().assertIsSelected()
        assertTrue(choices.isEmpty())
        compose.onNodeWithText("Import valid records").performScrollTo().performClick()
        assertEquals(listOf(HistoryImportPolicy.keepExisting),choices)
    }
    @Test fun replacementNeedsASeparateConfirmationAndCanBeCanceled() {
        val choices=mutableListOf<HistoryImportPolicy>()
        compose.setContent {MaterialTheme {HistoryImportForm(HistoryImportState(HistoryImportPhase.review,review),{},{}) {_,policy->choices+=policy}}}
        compose.onNodeWithTag("history-import-replace").performScrollTo().performClick()
        compose.onNodeWithText("Import valid records").performScrollTo().performClick()
        compose.onNodeWithText("Replace matching journal records?").assertIsDisplayed();assertTrue(choices.isEmpty())
        compose.onNodeWithText("Keep reviewing").performClick();assertTrue(choices.isEmpty())
        compose.onNodeWithText("Import valid records").performClick();compose.onNodeWithText("Replace and import").performClick()
        assertEquals(listOf(HistoryImportPolicy.replaceExisting),choices)
    }
    @Test fun recreationNeverRestoresAReplacementChoice() {
        val restoration=StateRestorationTester(compose);val choices=mutableListOf<HistoryImportPolicy>()
        restoration.setContent {MaterialTheme {HistoryImportForm(HistoryImportState(HistoryImportPhase.review,review),{},{}) {_,policy->choices+=policy}}}
        compose.onNodeWithTag("history-import-replace").performScrollTo().performClick().assertIsSelected()
        restoration.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("history-import-keep").performScrollTo().assertIsSelected()
        assertTrue(choices.isEmpty())
        compose.onNodeWithText("Import valid records").performScrollTo().performClick()
        assertEquals(listOf(HistoryImportPolicy.keepExisting),choices)
    }
    @Test fun activeImportOffersCancellationWithoutClaimingItAlreadyRolledBack() {
        var canceled=0
        compose.setContent {MaterialTheme {HistoryImportForm(HistoryImportState(HistoryImportPhase.applying,review),{canceled++},{}) {_,_->fail("Unexpected commit")}}}
        compose.onNodeWithText("Importing reviewed records…").assertExists()
        compose.onNodeWithText("Cancel import").performClick();assertEquals(1,canceled)
        compose.onNodeWithText("Import canceled. No records from this operation were written.").assertDoesNotExist()
    }
    @Test fun darkLargeTextKeepsErrorsPolicyAndDiscardReachable() {
        var discarded=0
        compose.setContent {val density=LocalDensity.current;CompositionLocalProvider(LocalDensity provides Density(density.density,1.6f)) {
            MaterialTheme(colorScheme=darkColorScheme()) {HistoryImportForm(HistoryImportState(HistoryImportPhase.review,review.copy(errors=List(10) {"Invalid row: "+"x".repeat(180)})),{},{discarded++}) {_,_->}}
        }}
        compose.onNodeWithTag("history-import-keep").performScrollTo().assertIsSelected()
        compose.onNodeWithText("Discard review").performScrollTo().performClick();assertEquals(1,discarded)
    }
}
