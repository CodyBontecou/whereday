package com.bontecou.whereday

import android.content.Intent
import android.content.pm.PackageManager
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createEmptyComposeRule
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.runner.lifecycle.ActivityLifecycleMonitorRegistry
import androidx.test.runner.lifecycle.Stage
import com.bontecou.whereday.platform.AppGraph
import com.bontecou.whereday.platform.RecordingRuntime
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*

class LauncherLifecycleTest {
    @get:Rule val compose=createEmptyComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun setup()=runBlocking {
        check(context.packageName=="com.bontecou.whereday.mapqa")
        AppGraph.from(context).preferences.update {it.copy(onboarded=true)}
        assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION))
    }
    private fun launch(action:LauncherAction)=ActivityScenario.launch<MainActivity>(Intent(context,MainActivity::class.java).setAction(action.action))
    private fun await(text:String) {compose.waitUntil(15_000) {compose.onAllNodesWithText(text).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}}
    @Test fun startConfirmationSurvivesRecreationButDismissedRequestDoesNotReturn() {
        launch(LauncherAction.start).use {scenario ->
            await("Start recording?");assertFalse(RecordingRuntime.active.value)
            scenario.recreate();await("Start recording?")
            assertFalse(RecordingRuntime.active.value)
            compose.onNodeWithText("Cancel").performClick();compose.waitForIdle()
            scenario.recreate();await("Your location journal")
            compose.onNodeWithText("Start recording?").assertDoesNotExist()
            assertFalse(RecordingRuntime.active.value)
            assertEquals(PackageManager.PERMISSION_DENIED,context.checkSelfPermission(android.Manifest.permission.ACCESS_COARSE_LOCATION))
        }
    }
    @Test fun onboardingDefersRecordingShortcutWithoutGrantOrCommand()=runBlocking {
        AppGraph.from(context).preferences.update {it.copy(onboarded=false)}
        launch(LauncherAction.start).use {
            compose.waitUntil(15_000) {compose.onAllNodesWithText("Start recording?").fetchSemanticsNodes(atLeastOneRootRequired=false).isEmpty() && compose.onAllNodes(hasText("Continue")).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
            assertFalse(RecordingRuntime.active.value)
            AppGraph.from(context).preferences.update {it.copy(onboarded=true)}
            await("Start recording?")
            compose.onNodeWithText("Cancel").performClick()
            assertFalse(RecordingRuntime.active.value)
        }
    }
    @Test fun exportDestinationSurvivesRecreationWithoutStarting() {
        launch(LauncherAction.export).use {scenario ->
            compose.waitUntil(15_000) {compose.onAllNodes(hasText("Export") and isSelected()).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
            scenario.recreate()
            compose.onNode(hasText("Export") and isSelected()).assertExists()
            assertFalse(RecordingRuntime.active.value)
        }
    }
    @Test fun warmNavigationAndIdleStopReachTheirOwnDestinationWithoutStarting() {
        // ActivityScenario matches the original launch Intent and cannot follow changed onNewIntent
        // identity. Use the real lifecycle monitor for this warm-delivery + recreation case.
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val activity=instrumentation.startActivitySync(Intent(context,MainActivity::class.java)
            .setAction(LauncherAction.timeline.action).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as MainActivity
        fun resumed():MainActivity=ActivityLifecycleMonitorRegistry.getInstance()
            .getActivitiesInStage(Stage.RESUMED).filterIsInstance<MainActivity>().single()
        try {
            compose.waitUntil(15_000) {compose.onAllNodes(hasText("Timeline") and isSelected()).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
            instrumentation.runOnMainSync {activity.startActivity(Intent(activity,MainActivity::class.java).setAction(LauncherAction.export.action)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))}
            compose.waitUntil(15_000) {compose.onAllNodes(hasText("Export") and isSelected()).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
            instrumentation.runOnMainSync {resumed().recreate()}
            compose.waitUntil(15_000) {compose.onAllNodes(hasText("Export") and isSelected()).fetchSemanticsNodes(atLeastOneRootRequired=false).isNotEmpty()}
            instrumentation.runOnMainSync {val current=resumed();current.startActivity(Intent(current,MainActivity::class.java).setAction(LauncherAction.stop.action)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP))}
            await("Recording is stopped")
            compose.onNodeWithText("Done").performClick()
            assertFalse(RecordingRuntime.active.value)
        } finally {
            instrumentation.runOnMainSync {
                Stage.values().flatMap {ActivityLifecycleMonitorRegistry.getInstance().getActivitiesInStage(it)}
                    .filterIsInstance<MainActivity>().distinct().forEach {it.finish()}
            }
        }
    }
}
