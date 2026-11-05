package com.bontecou.whereday

import android.content.Intent
import android.content.pm.ShortcutManager
import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*

class LauncherShortcutsTest {
    @get:Rule val compose=createComposeRule()
    private val context get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun guard() {check(context.packageName=="com.bontecou.whereday.mapqa")}
    @Test fun publicStartIntentNeedsExplicitConfirmationAndCancelDoesNothing() {
        var commands=0;var dismissed=0
        compose.setContent {MaterialTheme {LauncherRecordingConfirmation(LauncherAction.start,false,{dismissed++}) {commands++}}}
        compose.onNodeWithText("Start recording?").assertIsDisplayed()
        assertEquals(0,commands)
        compose.onNodeWithText("Cancel").performClick()
        assertEquals(1,dismissed);assertEquals(0,commands)
        compose.onNodeWithText("Start recording",useUnmergedTree=true).assertDoesNotExist()
        assertEquals(0,commands)
    }
    @Test fun alreadyActiveStartCannotBecomeToggleStop() {
        compose.setContent {MaterialTheme {LauncherRecordingConfirmation(LauncherAction.start,true,{}) {fail("Already-active start dispatched")}}}
        compose.onNodeWithText("Recording is already active").assertIsDisplayed()
        compose.onNodeWithText("Done").performClick()
        compose.onNodeWithText("Stop recording").assertDoesNotExist()
    }
    @Test fun stopRequiresConfirmationBeforeDispatch() {
        var start:Boolean?=null
        compose.setContent {MaterialTheme {LauncherRecordingConfirmation(LauncherAction.stop,true,{}) {start=it}}}
        compose.onNodeWithText("Stop recording?").assertIsDisplayed();assertNull(start)
        compose.onNodeWithText("Stop recording").performClick();assertEquals(false,start)
    }
    @Test fun navigationAndUnknownActionsCannotExecuteRecording() {
        compose.setContent {MaterialTheme {LauncherRecordingConfirmation(LauncherAction.export,false,{}) {fail("Navigation recording")}}}
        compose.onNodeWithText("Start recording?").assertDoesNotExist()
        assertNull(LauncherAction.fromAction("com.bontecou.whereday.automation.RUN"))
        assertNull(LauncherAction.fromAction(Intent.ACTION_VIEW))
        assertNull(LauncherAction.restore("untrusted"))
    }
    @Test fun publishedIntentsTargetOnlyInstalledQaPackageAndPreserveExistingTask() {
        val manager=requireNotNull(context.getSystemService(ShortcutManager::class.java))
        LauncherShortcuts.publish(context)
        val items=manager.dynamicShortcuts
        assertEquals(LauncherAction.entries.take(manager.maxShortcutCountPerActivity).map {it.id}.toSet(),items.map {it.id}.toSet())
        for(item in items) {
            val intent=requireNotNull(item.intent)
            assertEquals(context.packageName,intent.component?.packageName)
            assertEquals(MainActivity::class.java.name,intent.component?.className)
            assertNotNull(LauncherAction.fromAction(intent.action))
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_SINGLE_TOP!=0)
            assertTrue(intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TOP!=0)
            assertEquals(0,intent.flags and Intent.FLAG_ACTIVITY_CLEAR_TASK)
            assertNull(intent.data)
            assertTrue(intent.extras==null || intent.extras!!.isEmpty)
        }
    }
}
