package com.bontecou.whereday

import android.view.WindowManager
import android.widget.TimePicker
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.test.espresso.Espresso
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.time.Instant

/** Owned-AVD driver enables software IME and restores that device setting outside instrumentation. */
class WorkspaceKeyboardTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    @Before fun setup() {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        compose.activityRule.scenario.onActivity {activity->
            WindowCompat.setDecorFitsSystemWindows(activity.window,false)
            activity.window.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_NOTHING)
        }
    }
    private fun imeBottom(settle:Boolean=false):Int {
        var bottom=0
        Espresso.onView(ViewMatchers.isRoot()).inRoot(isDialog()).perform(object:androidx.test.espresso.ViewAction {
            override fun getConstraints()=ViewMatchers.isRoot()
            override fun getDescription()="Read actual dialog IME insets"
            override fun perform(controller:androidx.test.espresso.UiController,view:android.view.View) {
                if(settle) controller.loopMainThreadForAtLeast(500)
                val insets=ViewCompat.getRootWindowInsets(view)
                if(insets?.isVisible(WindowInsetsCompat.Type.ime())==true) bottom=insets.getInsets(WindowInsetsCompat.Type.ime()).bottom
            }
        })
        return bottom
    }
    private fun screenBounds(tag:String):Rect {
        val coordinates=compose.onNodeWithTag(tag).fetchSemanticsNode().layoutInfo.coordinates
        val origin=coordinates.localToScreen(Offset.Zero)
        return Rect(origin,androidx.compose.ui.geometry.Size(coordinates.size.width.toFloat(),coordinates.size.height.toFloat()))
    }
    private fun assertDialogInside(tag:String) {
        val pane=screenBounds(tag);val dialog=screenBounds("workspace-dialog-content")
        assertTrue("$dialog must stay within $pane without crossing the fold",dialog.width>0 && dialog.height>0 &&
            dialog.left>=pane.left-1 && dialog.right<=pane.right+1 && dialog.top>=pane.top-1 && dialog.bottom<=pane.bottom+1)
    }
    private fun capture(stage:String) {
        if(InstrumentationRegistry.getArguments().getString("captureWorkspaceKeyboard")!="true") return
        compose.waitForIdle()
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        instrumentation.uiAutomation.waitForIdle(500,5000)
        val image=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            val values=android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-adaptive-keyboard-$stage-20261102.png")
                put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
            }
            val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
            target.contentResolver.openOutputStream(uri)!!.use {check(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
        } finally {image.recycle()}
    }
    @Test fun tabletopIsoDraftAndActionsRemainReachableWithActualKeyboardOpen() {
        var changes=0
        compose.setContent {MaterialTheme {BoxWithConstraints {
            val width=constraints.maxWidth;val height=constraints.maxHeight
            val band=remember {WorkspaceFold(WorkspaceRect(0,height/2-16,width,height/2+16),WorkspaceFoldAxis.horizontal,true)}
            JournalWorkspaceFrame(true,folds=listOf(band),primary={Text("Upper safe region")},companion={
                VisitDateTimeControl("Arrival",Instant.parse("2026-11-01T12:00:00Z")) {changes++}
            })
        }}}
        compose.onNodeWithTag("visit-timestamp-date").performClick()
        compose.onNodeWithText(target.getString(R.string.date_enter_iso)).performScrollTo().performClick()
        compose.onNodeWithTag("visit-timestamp-date-input").performClick().performTextReplacement("2026-02-30")
        compose.waitUntil(10000) {imeBottom()>0}
        assertTrue("Software keyboard must actually be visible",imeBottom(settle=true)>0)
        assertDialogInside("workspace-primary");capture("tabletop-ime")
        val input=compose.onNodeWithTag("visit-timestamp-date-input").assertIsDisplayed().assertTextContains("2026-02-30")
        val node=input.fetchSemanticsNode()
        assertTrue("Keyboard must not clip the focused date field",node.boundsInWindow.height>=node.layoutInfo.coordinates.size.height-1)
        compose.onNodeWithText(target.getString(R.string.visit_apply_date)).assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText(target.getString(R.string.visit_cancel)).assertIsDisplayed()
        assertEquals(0,changes)
        Espresso.closeSoftKeyboard();compose.waitUntil(10000) {imeBottom()==0}
        compose.onNodeWithTag("visit-timestamp-date-input").assertTextContains("2026-02-30");assertDialogInside("workspace-companion");capture("tabletop-restored")
        assertEquals(0,changes)
    }
    private fun textClock(hour:String,minute:String,expectInvalid:Boolean) {
        Espresso.onView(ViewMatchers.isAssignableFrom(TimePicker::class.java)).inRoot(isDialog()).perform(object:androidx.test.espresso.ViewAction {
            override fun getConstraints()=ViewMatchers.isAssignableFrom(TimePicker::class.java)
            override fun getDescription()="Edit real framework clock text fields"
            override fun perform(controller:androidx.test.espresso.UiController,view:android.view.View) {
                val picker=view as TimePicker
                val hourField=picker.findViewById<android.widget.EditText>(picker.resources.getIdentifier("input_hour","id","android"))
                if(!hourField.isShown) {check(picker.findViewById<android.view.View>(picker.resources.getIdentifier("toggle_mode","id","android")).performClick());controller.loopMainThreadForAtLeast(350)}
                hourField.setText(hour)
                picker.findViewById<android.widget.EditText>(picker.resources.getIdentifier("input_minute","id","android")).setText(minute)
                assertEquals(!expectInvalid,picker.validateInput())
            }
        })
    }
    private fun assertClockDraft() {
        Espresso.onView(ViewMatchers.isAssignableFrom(TimePicker::class.java)).inRoot(isDialog()).perform(object:androidx.test.espresso.ViewAction {
            override fun getConstraints()=ViewMatchers.isAssignableFrom(TimePicker::class.java)
            override fun getDescription()="Verify pending native keyboard mode and invalid text survived"
            override fun perform(controller:androidx.test.espresso.UiController,view:android.view.View) {
                val picker=view as TimePicker
                val hour=picker.findViewById<android.widget.EditText>(picker.resources.getIdentifier("input_hour","id","android"))
                val minute=picker.findViewById<android.widget.EditText>(picker.resources.getIdentifier("input_minute","id","android"))
                assertTrue("Native text mode must survive presentation handoff",hour.isShown)
                assertEquals("99",hour.text.toString());assertEquals("71",minute.text.toString());assertFalse(picker.validateInput())
            }
        })
    }
    @Test fun invalidNativeClockTextAndModeSurviveBothDialogPresentationDirections() {
        val folded=mutableStateOf(false);val open=mutableStateOf(true);val selected=mutableListOf<Pair<Int,Int>>()
        compose.setContent {MaterialTheme {BoxWithConstraints {
            val w=constraints.maxWidth;val h=constraints.maxHeight
            JournalWorkspaceFrame(false,folds=if(folded.value) listOf(WorkspaceFold(WorkspaceRect(w/2-16,0,w/2+16,h),WorkspaceFoldAxis.vertical,true)) else emptyList(),
                modal={if(open.value) WorkspaceClockDialog(9,17,false,"handoff",title={_,_->Text("Pending clock draft")},dismiss={open.value=false},confirm={hour,minute->selected.add(hour to minute);open.value=false})},primary={Text("Clock owner")},companion={})
        }}}
        textClock("99","71",true)
        compose.runOnIdle {folded.value=true};assertClockDraft()
        compose.onNodeWithTag("handoff-confirm").performClick();compose.runOnIdle {assertTrue(selected.isEmpty())};capture("clock-folded")
        compose.runOnIdle {folded.value=false};assertClockDraft()
        compose.onNodeWithTag("handoff-confirm").performClick();compose.runOnIdle {assertTrue(selected.isEmpty())}
        textClock("11","42",false);compose.onNodeWithTag("handoff-confirm").performClick()
        compose.runOnIdle {assertEquals(listOf(11 to 42),selected)}
    }
}
