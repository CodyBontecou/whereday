package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.platform.AppGraph
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Unsaved fields only: no location request, storage write or owner-package access. */
@RunWith(AndroidJUnit4::class)
class EditorBackGuardTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    @Before fun isolated() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    private fun backTo(title:String) {
        androidx.test.espresso.Espresso.closeSoftKeyboard();compose.waitForIdle()
        androidx.test.espresso.Espresso.pressBack()
        compose.waitUntil(5000) {runCatching {compose.onNodeWithText(title).assertIsDisplayed()}.isSuccess}
    }
    @Test fun savedPlaceEditorProtectsEditsMadeAfterTheDialogOpened() {
        var dismissed=0;var writes=0
        compose.setContent {MaterialTheme {PlaceEditor(null,{dismissed++},{writes++},null)}}
        compose.onNodeWithText("Name").performTextInput("Unsaved QA place")
        backTo("Discard saved place changes?")
        compose.runOnIdle {assertEquals(0,dismissed);assertEquals(0,writes)}
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("Name").assertTextContains("Unsaved QA place")
    }
    @Test fun visitEditorProtectsEditsMadeAfterTheDialogOpened() {
        var dismissed=0;var writes=0
        val graph=AppGraph.from(InstrumentationRegistry.getInstrumentation().targetContext)
        compose.setContent {MaterialTheme {VisitEditor(graph,null,{dismissed++},{_,_,_,_->writes++},null)}}
        compose.onNodeWithText("Name").performTextInput("Unsaved QA visit")
        backTo("Discard visit changes?")
        compose.runOnIdle {assertEquals(0,dismissed);assertEquals(0,writes)}
        compose.onNodeWithText("Keep editing").performClick()
        compose.onNodeWithText("Name").assertTextContains("Unsaved QA visit")
    }
}
