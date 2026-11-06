package com.bontecou.whereday

import android.Manifest
import android.content.pm.PackageManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.platform.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*

/** Actual MainActivity/AppGraph, denied QA permissions only; never starts a service or opens Settings. */
class RecordingAccessIntegrationTest {
    @get:Rule val compose=createAndroidComposeRule<MainActivity>()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val graph get()=AppGraph.from(target)
    private var onboarded=false
    @Before fun setup():Unit=runBlocking {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        assertEquals(PackageManager.PERMISSION_DENIED,target.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION))
        assertEquals(PackageManager.PERMISSION_DENIED,target.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
        graph.journalClear.awaitReady();onboarded=graph.preferences.settings.first().onboarded
        graph.preferences.update {it.copy(onboarded=true)}
        compose.waitUntil(15000) {compose.onAllNodesWithTag("map-header").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
        assertEquals(LocationPrecision.none,recordingAccess(target).location)
        assertFalse(RecordingRuntime.active.value)
    }
    @After fun cleanup():Unit=runBlocking {graph.preferences.update {it.copy(onboarded=onboarded)}}
    // On the map home the access notice is the compact pill above the recording bar; it opens the same review.
    private fun options() {compose.onNodeWithTag("recording-access-pill").performClick();compose.onNodeWithTag("permission-access-review").assertExists()}
    @Test fun recreationDiscardsAnUnacceptedReviewAndReturningNeverStartsRecording():Unit=runBlocking {
        options();val old=compose.onNodeWithTag("permission-review-confirm").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.activityRule.scenario.recreate()
        compose.waitUntil(15000) {compose.onAllNodesWithTag("map-header").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
        compose.runOnIdle {old()}
        compose.onNodeWithTag("permission-access-review").assertDoesNotExist()
        assertFalse(RecordingRuntime.active.value);assertTrue(graph.history.dao.recording()?.requested!=true)
        options();compose.onNodeWithTag("permission-review-dismiss").performClick()
        assertFalse(RecordingRuntime.active.value)
    }
    @Test fun explicitStopInvalidatesARetainedPermissionReview():Unit=runBlocking {
        options();val old=compose.onNodeWithTag("permission-review-confirm").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.activityRule.scenario.onActivity {activity->
            val original=activity.intent
            // Deliver the real legacy Stop callback, then retain ActivityScenario's launch identity.
            InstrumentationRegistry.getInstrumentation().callActivityOnNewIntent(activity,android.content.Intent(android.content.Intent.ACTION_VIEW,android.net.Uri.parse("isome://stop")))
            activity.intent=original
        }
        compose.waitUntil(15000) {compose.onAllNodesWithTag("permission-access-review").fetchSemanticsNodes(atLeastOneRootRequired=false).isEmpty()}
        compose.runOnIdle {old()}
        assertFalse(RecordingRuntime.active.value)
        assertEquals(PackageManager.PERMISSION_DENIED,target.checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION))
        assertEquals(PackageManager.PERMISSION_DENIED,target.checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION))
        withTimeout(15000) {while(graph.history.dao.recording()?.requested==true) delay(25)}
    }
    @Test fun actualJournalClearInvalidatesARetainedPermissionReview():Unit=runBlocking {
        options();val old=compose.onNodeWithTag("permission-review-confirm").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        val attempt=graph.journalClear.state.value.attempt
        graph.journalClear.clear().join()
        assertEquals(JournalClearPhase.complete,graph.journalClear.state.value.phase);assertTrue(graph.journalClear.state.value.attempt>attempt)
        compose.waitUntil(15000) {compose.onAllNodesWithTag("settings-screen").fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
        compose.runOnIdle {old()}
        compose.onNodeWithTag("permission-access-review").assertDoesNotExist()
        assertFalse(RecordingRuntime.active.value);assertTrue(graph.history.dao.recording()?.requested!=true)
        assertEquals(0L,graph.history.dao.totalPointCount())
    }
}
