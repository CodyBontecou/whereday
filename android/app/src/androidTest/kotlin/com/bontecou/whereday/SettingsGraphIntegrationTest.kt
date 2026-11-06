package com.bontecou.whereday

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.platform.*
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import java.time.Instant

/** Actual application graph and disposable mapQa data only. No GPS, server, purchase or owner journal. */
class SettingsGraphIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private fun settings() {
        compose.waitUntil(15000) {compose.onAllNodesWithTag("map-header").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
        compose.openSettings();compose.onNodeWithTag("settings-screen").assertExists()
    }
    private fun click(text:String) {compose.onNodeWithTag("settings-screen").performScrollToNode(hasText(text));compose.onNodeWithText(text).performClick()}
    @Test fun preferenceCommandsReachDataStoreAndChildRecreationKeepsTheOverviewPosition():Unit=runBlocking {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        val graph=AppGraph.from(target);val original=graph.preferences.settings.first()
        try {
            graph.preferences.update {it.copy(onboarded=true)};settings()
            compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("settings-distance"))
            compose.onNode(hasText("${original.distanceFilter} m") and hasAnyAncestor(hasTestTag("settings-distance"))).performClick()
            compose.onNode(hasText("100 m") and hasAnyAncestor(isPopup())).performClick()
            withTimeout(5000) {graph.preferences.settings.first {it.distanceFilter==100}}
            compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("settings-theme"))
            compose.onNode(hasText("Dark") and hasAnyAncestor(hasTestTag("settings-theme"))).performClick()
            click("Support and about");compose.onNodeWithTag("support-screen").assertExists()
            withTimeout(5000) {graph.settingsActions.state.first {!it.busy};graph.preferences.settings.first {it.appearance=="dark" && it.distanceFilter==100}}
            compose.activityRule.scenario.recreate();compose.waitUntil(10000) {compose.onAllNodesWithTag("support-screen").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
            compose.onNodeWithTag("support-screen").assertExists()
            androidx.test.espresso.Espresso.pressBack();compose.onNodeWithTag("settings-screen").assertExists()
            compose.onNodeWithText("Support and about").assertIsDisplayed()
            assertEquals("dark",graph.preferences.settings.first().appearance);assertEquals(100,graph.preferences.settings.first().distanceFilter)
        } finally {
            withTimeout(5000) {graph.settingsActions.state.first {!it.busy}}
            graph.preferences.update {it.copy(onboarded=original.onboarded,appearance=original.appearance,distanceFilter=original.distanceFilter)}
        }
    }
    @Test fun acceptedClearSurvivesRecreationBehindAnAdmissionBarrierWithoutStartingADuplicate():Unit=runBlocking {supervisorScope {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        val graph=AppGraph.from(target);val original=graph.preferences.settings.first()
        val release=CompletableDeferred<Unit>();val entered=CompletableDeferred<Unit>()
        var writer:Deferred<Result<AutomationResult>>?=null
        try {
            graph.preferences.update {it.copy(onboarded=true)}
            graph.database.history().put(PointRow(TrackPoint("settings-clear-synthetic",Coordinate(0.0,0.0),Instant.parse("2026-10-28T10:00:00Z"),5.0),null))
            graph.automationAccess.approve(requireNotNull(graph.automationAccess.installed(target.packageName)),setOf(AutomationScope.summary))
            writer=async {runCatching {graph.automationResults.create(target.packageName,AutomationScope.summary,"settings-clear.json","application/json") {
                it.write("partial".toByteArray());entered.complete(Unit);withContext(NonCancellable) {release.await()}
            }}}
            withTimeout(5000) {entered.await()};settings()
            lateinit var journal:JournalViewModel
            compose.activityRule.scenario.onActivity {journal=androidx.lifecycle.ViewModelProvider(it)[JournalViewModel::class.java]}
            journal.mapSelection.value=MapSelection("All",outingRange=HistoryRange(Instant.parse("2010-01-01T00:00:00Z"),Instant.parse("2010-01-02T00:00:00Z")))
            journal.timelineDay.value=java.time.LocalDate.of(2010,1,1)
            click("Clear all journal data");compose.onNodeWithText("Clear all journal data?").assertIsDisplayed()
            val before=graph.journalClear.state.value.attempt
            compose.onNodeWithTag("settings-clear-confirm").performClick()
            val clearing=withTimeout(5000) {graph.journalClear.state.first {it.busy && it.attempt>before}}
            val operation=graph.journalClear.clear();assertEquals(clearing.attempt,graph.journalClear.state.value.attempt)
            assertTrue(graph.database.history().totalPointCount()>0)
            compose.onNodeWithTag("journal-clear-barrier").assertExists()
            compose.activityRule.scenario.recreate()
            compose.waitUntil(10000) {compose.onAllNodesWithTag("journal-clear-barrier").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
            compose.onNodeWithTag("settings-screen").assertDoesNotExist()
            compose.onNodeWithText("Map",useUnmergedTree=true).assertDoesNotExist()
            assertSame(operation,graph.journalClear.clear());assertEquals(clearing.attempt,graph.journalClear.state.value.attempt)
            release.complete(Unit)
            withTimeout(15000) {operation.join()}
            assertEquals(JournalClearPhase.complete,graph.journalClear.state.value.phase)
            assertEquals(clearing.attempt,graph.journalClear.state.value.attempt);assertNotNull(graph.journalClear.state.value.generation)
            withTimeout(5000) {journal.mapSelection.first {it==MapSelection()};journal.timelineDay.first {it==java.time.LocalDate.now()}}
            assertEquals(0L,graph.database.history().totalPointCount());assertFalse(RecordingRuntime.active.value)
            assertNull(graph.automationAccess.approval())
            assertTrue(withTimeout(5000) {writer!!.await()}.exceptionOrNull() is CancellationException)
            compose.waitUntil(10000) {compose.onAllNodesWithTag("settings-screen").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
            compose.onNodeWithTag("settings-screen").performScrollToNode(hasTestTag("settings-clear-result"))
            compose.onNodeWithTag("settings-clear-result").assertTextEquals("Journal data cleared.")
        } finally {
            release.complete(Unit);writer?.cancelAndJoin()
            withTimeout(15000) {graph.journalClear.state.first {!it.busy}}
            graph.automationAccess.revoke();graph.automationResults.clear()
            graph.preferences.update {it.copy(onboarded=original.onboarded)}
        }
    }}
}
