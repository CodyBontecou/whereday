package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*

/** No service dispatch: holds actual semantics callbacks to exercise stale UI approvals. */
class LauncherConfirmationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val action=mutableStateOf<LauncherAction?>(LauncherAction.start)
    private val recording=mutableStateOf(false)
    private val visible=mutableStateOf(true)
    private val calls=mutableListOf<Boolean>()
    private var dismissals=0
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    private fun show() {compose.setContent {MaterialTheme {if(visible.value) LauncherRecordingConfirmation(action.value,recording.value,{dismissals++}) {calls.add(it)}}}}
    private fun held(text:String)=compose.onNode(hasText(text) and hasClickAction()).fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
    @Test fun canceledConfirmationCannotBeUsedByAHeldCallback() {
        show();val old=held("Start recording")
        compose.onNodeWithText("Cancel").performClick()
        compose.runOnIdle {old()}
        assertTrue(calls.isEmpty());assertEquals(1,dismissals)
    }
    @Test fun duplicatePositiveOnlyDispatchesOnceEvenWhenCallerHasNotRecomposed() {
        show();val old=held("Start recording")
        compose.runOnIdle {old();old()}
        assertEquals(listOf(true),calls)
    }
    @Test fun changedRecordingStateRejectsTheOldApproval() {
        show();val old=held("Start recording")
        compose.runOnIdle {recording.value=true};compose.waitForIdle()
        compose.runOnIdle {old()}
        assertTrue(calls.isEmpty())
        compose.onNodeWithText("Recording is already active").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick();assertEquals(1,dismissals)
    }
    @Test fun replacedActionCannotUseOrDismissTheNewConfirmation() {
        show();val old=held("Start recording");val cancel=held("Cancel")
        compose.runOnIdle {action.value=LauncherAction.stop;recording.value=true};compose.waitForIdle()
        compose.runOnIdle {old();cancel()}
        assertTrue(calls.isEmpty());assertEquals(0,dismissals)
        compose.onNodeWithText("Stop recording?").assertIsDisplayed()
        compose.onNodeWithText("Stop recording",useUnmergedTree=true).performClick();assertEquals(listOf(false),calls)
    }
    @Test fun backgroundRejectsOldApprovalAndResumeNeedsAFreshChoice() {
        show();val old=held("Start recording")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED);compose.waitForIdle()
        compose.runOnIdle {old()};assertTrue(calls.isEmpty())
        compose.onNodeWithText("Start recording?").assertIsDisplayed()
        compose.onNodeWithText("Start recording",useUnmergedTree=true).performClick();assertEquals(listOf(true),calls)
    }
    @Test fun disposedScreenCannotApproveAfterItsReplacementAppears() {
        show();val old=held("Start recording")
        compose.runOnIdle {visible.value=false};compose.waitForIdle()
        compose.runOnIdle {visible.value=true};compose.waitForIdle()
        compose.runOnIdle {old()};assertTrue(calls.isEmpty())
        compose.onNodeWithText("Start recording",useUnmergedTree=true).performClick();assertEquals(listOf(true),calls)
    }
}
