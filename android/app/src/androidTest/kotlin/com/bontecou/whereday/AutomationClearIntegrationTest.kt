package com.bontecou.whereday

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.platform.*
import com.bontecou.whereday.domain.HistoryRange
import com.bontecou.whereday.data.PhotoPlaceDirectory
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Clear from the actual Settings UI; never target the owner application or request GPS. */
@RunWith(AndroidJUnit4::class)
class AutomationClearIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    @Test fun settingsClearRevokesResultAccessAndCancelsAnActiveAutomationWrite():Unit=runBlocking {supervisorScope {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.mapqa")
        val graph=AppGraph.from(context)
        var pending:Deferred<Result<AutomationResult>>?=null
        var photoDirectory:PhotoPlaceDirectory?=null
        try {
            graph.preferences.update {it.copy(onboarded=true)}
            val directory=graph.photoPlaces.prepare(HistoryRange.all)
            photoDirectory=directory
            assertTrue(directory.active.value)
            assertTrue(java.io.File(context.cacheDir,"photo-place-directories").listFiles()!!.isNotEmpty())
            graph.automationAccess.approve(requireNotNull(graph.automationAccess.installed(context.packageName)),setOf(AutomationScope.summary))
            val result=graph.automationResults.create(context.packageName,AutomationScope.summary,"synthetic.json","application/json") {it.write("{}".toByteArray())}
            assertEquals(2L,graph.automationResults.info(context.packageName,result.id).bytes)
            val entered=CompletableDeferred<Unit>()
            val write=async {runCatching {graph.automationResults.create(context.packageName,AutomationScope.summary,"pending.json","application/json") {
                it.write("partial".toByteArray());entered.complete(Unit);awaitCancellation()
            }}}
            pending=write
            entered.await()
            compose.waitUntil(10_000) {compose.onAllNodesWithTag("map-header").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
            compose.openSettings()
            compose.onNode(hasScrollAction()).performScrollToNode(hasText("Clear all journal data"))
            compose.onNodeWithText("Clear all journal data").performClick()
            compose.onNodeWithText("Clear all journal data?").assertIsDisplayed()
            graph.activityLog.record(AppEvent.automationCompleted)
            assertTrue(graph.activityLog.snapshot().isNotEmpty())
            val clearAttempt=graph.journalClear.state.value.attempt
            compose.onNodeWithText("Clear all data").performClick()
            compose.waitUntil(15_000) {compose.onAllNodesWithText("Clear all journal data?").fetchSemanticsNodes(atLeastOneRootRequired=false).isEmpty()}
            val cleared=withTimeout(15000) {graph.journalClear.state.first {it.attempt>clearAttempt && !it.busy}}
            assertEquals(JournalClearPhase.complete,cleared.phase)
            assertTrue(withTimeout(5000) {write.await()}.exceptionOrNull() is CancellationException)
            assertNull(graph.automationAccess.approval())
            assertTrue(graph.activityLog.snapshot().isEmpty())
            assertFalse(RecordingRuntime.active.value)
            assertFalse(directory.active.value)
            assertTrue(java.io.File(context.cacheDir,"photo-place-directories").listFiles()!!.isEmpty())
            try {directory.counts();fail("Clear left a photo directory readable")}
            catch(_:IllegalStateException) {}
            try {graph.automationResults.info(context.packageName,result.id);fail("Cleared result opened")}
            catch(_:AutomationResultUnavailable) { }
        } finally {
            photoDirectory?.close()
            graph.automationAccess.revoke();pending?.cancelAndJoin();graph.automationResults.clear()
        }
    }}
}
