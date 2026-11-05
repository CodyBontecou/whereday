package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import org.junit.*
import org.junit.Assert.*

class SavedPlaceActionLifecycleTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val place=SavedPlace("visible-editor","Original place",Coordinate(18.0,-66.0),radius=150.0)
    private val visible=mutableStateOf(true)
    private val saved=mutableListOf<SavedPlace>()
    private var deleted=0
    @Before fun guard() {check(target.packageName=="com.bontecou.whereday.mapqa")}
    private fun show() {compose.setContent {MaterialTheme {if(visible.value) PlaceEditor(place,{visible.value=false},{saved.add(it)},{deleted++;visible.value=false})}}}
    private fun action(tag:String)=compose.onNodeWithTag(tag).fetchSemanticsNode().config[androidx.compose.ui.semantics.SemanticsActions.OnClick].action!!
    @Test fun saveCannotStartWhileStoppedOrFromAnOldResumedOrDisposedButton() {
        show();compose.onNodeWithTag("saved-name").performTextReplacement("Explicit new place")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        val old=action("saved-editor-save")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {old()}
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle();compose.runOnIdle {old();assertTrue(saved.isEmpty())}
        compose.onNodeWithTag("saved-name").assertTextContains("Explicit new place",substring=true)
        val current=action("saved-editor-save")
        compose.onNodeWithTag("saved-editor-save").performClick();compose.waitForIdle()
        compose.runOnIdle {assertEquals(listOf(place.copy(name="Explicit new place")),saved);current();assertEquals(1,saved.size)}
    }
    @Test fun deleteNeedsAFreshVisibleApprovalAfterStopAndResume() {
        show();compose.onNodeWithText(target.getString(R.string.saved_editor_delete)).performScrollTo().performClick()
        val old=action("saved-delete-confirm")
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {old()}
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.waitForIdle();compose.runOnIdle {old();assertEquals(0,deleted);assertTrue(saved.isEmpty())}
        compose.onNodeWithTag("saved-delete-confirm").performClick();compose.waitForIdle()
        compose.runOnIdle {assertEquals(1,deleted);old();assertEquals(1,deleted)}
    }
}
