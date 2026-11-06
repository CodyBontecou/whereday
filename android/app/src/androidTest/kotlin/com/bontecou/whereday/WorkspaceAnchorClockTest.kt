package com.bontecou.whereday

import android.widget.TimePicker
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.ui.Modifier
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.semantics.SemanticsActions
import androidx.lifecycle.Lifecycle
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.espresso.Espresso
import androidx.test.espresso.matcher.RootMatchers.isDialog
import androidx.test.espresso.matcher.ViewMatchers
import com.bontecou.whereday.export.*
import com.bontecou.whereday.domain.ExportSchedule
import com.bontecou.whereday.platform.*
import org.junit.*
import org.junit.Assert.*

class WorkspaceAnchorClockTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val automation get()=InstrumentationRegistry.getInstrumentation().uiAutomation
    private val initial=AutomaticExportSettings(schedule=ExportSchedule(9,17,0))
    private val ui=mutableStateOf(AutomaticExportUiState(loaded=true,state=AutomaticExportState(settings=initial)))
    private val updates=mutableListOf<AutomaticExportSettings>()
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    private fun show() {
        val actions=AutomaticExportActions({change->val value=change(ui.value.state.settings);updates.add(value);ui.value=ui.value.copy(state=ui.value.state.copy(settings=value))},{},{},{},{},{},{})
        compose.setContent {MaterialTheme {BoxWithConstraints {
            check(constraints.maxWidth/LocalDensity.current.density>=840)
            val w=constraints.maxWidth;val h=constraints.maxHeight
            JournalWorkspaceFrame(true,folds=listOf(WorkspaceFold(WorkspaceRect(w/2-16,0,w/2+16,h),WorkspaceFoldAxis.vertical,true)),primary={Text("Primary owner")},companion={AutomaticExportsContent(ui.value,false,actions,{})})
        }}}
        compose.onNodeWithTag("automatic-export-anchor").performScrollTo().performClick()
        owns(screen(),pane("workspace-companion"))
    }
    private fun edit(hour:Int,minute:Int) {
        Espresso.onView(ViewMatchers.isAssignableFrom(TimePicker::class.java)).inRoot(isDialog()).perform(object:androidx.test.espresso.ViewAction {
            override fun getConstraints():org.hamcrest.Matcher<android.view.View> = ViewMatchers.isAssignableFrom(TimePicker::class.java)
            override fun getDescription()="Edit the synthetic adaptive clock"
            override fun perform(controller:androidx.test.espresso.UiController,view:android.view.View) {(view as TimePicker).apply {this.hour=hour;this.minute=minute};controller.loopMainThreadUntilIdle()}
        })
    }
    private fun screen():android.graphics.Rect {
        compose.waitForIdle()
        var found:android.graphics.Rect?=null
        compose.waitUntil(5000) {
            fun find(node:android.view.accessibility.AccessibilityNodeInfo):android.graphics.Rect? {
                if(node.viewIdResourceName=="workspace-dialog-content") return android.graphics.Rect().also {node.getBoundsInScreen(it)}
                for(i in 0 until node.childCount) {
                    val child=node.getChild(i) ?: continue
                    try {find(child)?.let {return it}} finally {child.recycle()}
                }
                return null
            }
            val root=automation.rootInActiveWindow
            found=if(root==null) null else try {find(root)} finally {root.recycle()}
            found?.isEmpty==false
        }
        return checkNotNull(found)
    }
    private fun click(x:Int,y:Int) {
        val now=android.os.SystemClock.uptimeMillis()
        for(action in listOf(android.view.MotionEvent.ACTION_DOWN,android.view.MotionEvent.ACTION_UP)) {
            val event=android.view.MotionEvent.obtain(now,android.os.SystemClock.uptimeMillis(),action,x.toFloat(),y.toFloat(),0)
            event.source=android.view.InputDevice.SOURCE_TOUCHSCREEN
            try {check(automation.injectInputEvent(event,true))} finally {event.recycle()}
        }
    }
    private fun back() {InstrumentationRegistry.getInstrumentation().sendKeyDownUpSync(android.view.KeyEvent.KEYCODE_BACK)}
    private fun pane(tag:String):android.graphics.Rect {
        val coords=compose.onNodeWithTag(tag).fetchSemanticsNode().layoutInfo.coordinates
        val start=coords.localToScreen(Offset.Zero)
        return android.graphics.Rect(start.x.toInt(),start.y.toInt(),(start.x+coords.size.width).toInt(),(start.y+coords.size.height).toInt())
    }
    private fun owns(dialog:android.graphics.Rect,pane:android.graphics.Rect) {
        assertTrue("$dialog must be inside $pane",pane.contains(dialog));assertTrue(dialog.width()>0 && dialog.height()>0)
    }
    @Test fun frameworkClockAppliesOnlyTheExplicitSelectedHourAndMinute() {
        show();edit(23,45);capture("clock");compose.onNodeWithTag("automatic-anchor-confirm").assertIsDisplayed().performClick()
        compose.runOnIdle {assertEquals(1,updates.size);assertEquals(23,updates.single().schedule.hour);assertEquals(45,updates.single().schedule.minute);assertEquals(initial.copy(schedule=initial.schedule.copy(hour=23,minute=45)),updates.single())}
        compose.onNodeWithTag("automatic-anchor-clock").assertDoesNotExist()
    }
    @Test fun cancelAndBackgroundRejectUnsavedAndStaleClockApprovals() {
        show();edit(22,30);compose.onNodeWithTag("automatic-anchor-cancel").performClick();compose.runOnIdle {assertTrue(updates.isEmpty())}
        compose.onNodeWithTag("automatic-export-anchor").performScrollTo().performClick()
        val old=compose.onNodeWithTag("automatic-anchor-confirm").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.activityRule.scenario.moveToState(Lifecycle.State.CREATED)
        InstrumentationRegistry.getInstrumentation().runOnMainSync {old();assertTrue(updates.isEmpty());assertEquals(initial,ui.value.state.settings)}
        compose.activityRule.scenario.moveToState(Lifecycle.State.RESUMED)
        compose.onNodeWithTag("automatic-anchor-clock").assertDoesNotExist()
    }    @Test fun exportClockRejectsChangedTimeAndKeepsUnrelatedFilterEdits() {
        val options=mutableStateOf(ExportOptions(startTime=java.time.LocalTime.of(8,0),endTime=java.time.LocalTime.of(18,0)))
        compose.setContent {MaterialTheme {BoxWithConstraints {
            val w=constraints.maxWidth;val h=constraints.maxHeight
            JournalWorkspaceFrame(true,folds=listOf(WorkspaceFold(WorkspaceRect(w/2-16,0,w/2+16,h),WorkspaceFoldAxis.vertical,true)),primary={Text("Primary owner")},companion={Column(Modifier.verticalScroll(rememberScrollState())) {ExportFilters(options.value,{options.value=it},options.value.kind)}})
        }}}
        compose.onNodeWithTag("export-time-from").performScrollTo().performClick();owns(screen(),pane("workspace-companion"))
        edit(22,15)
        val old=compose.onNodeWithTag("export-time-from-confirm").fetchSemanticsNode().config[SemanticsActions.OnClick].action!!
        compose.runOnIdle {options.value=options.value.copy(startTime=java.time.LocalTime.of(9,0))}
        compose.onNodeWithTag("export-time-from-clock").assertDoesNotExist()
        compose.runOnIdle {old();assertEquals(java.time.LocalTime.of(9,0),options.value.startTime)}
        compose.onNodeWithTag("export-time-from").performScrollTo().performClick();edit(6,45)
        compose.runOnIdle {options.value=options.value.copy(maximumAccuracy=25.0)}
        compose.onNodeWithTag("export-time-from-confirm").performClick()
        compose.runOnIdle {assertEquals(java.time.LocalTime.of(6,45),options.value.startTime);assertEquals(java.time.LocalTime.of(18,0),options.value.endTime);assertEquals(25.0,options.value.maximumAccuracy!!,0.0)}
    }
    private fun textClock(hour:String,minute:String) {
        Espresso.onView(ViewMatchers.isAssignableFrom(TimePicker::class.java)).inRoot(isDialog()).perform(object:androidx.test.espresso.ViewAction {
            override fun getConstraints():org.hamcrest.Matcher<android.view.View> = ViewMatchers.isAssignableFrom(TimePicker::class.java)
            override fun getDescription()="Edit actual framework text-mode fields"
            override fun perform(controller:androidx.test.espresso.UiController,view:android.view.View) {
                val picker=view as TimePicker
                val hourId=picker.resources.getIdentifier("input_hour","id","android")
                val minuteId=picker.resources.getIdentifier("input_minute","id","android")
                val field=picker.findViewById<android.widget.EditText>(hourId)
                if(!field.isShown) {
                    val toggleId=picker.resources.getIdentifier("toggle_mode","id","android")
                    check(picker.findViewById<android.view.View>(toggleId).performClick());controller.loopMainThreadForAtLeast(350)
                }
                picker.findViewById<android.widget.EditText>(hourId).setText(hour)
                picker.findViewById<android.widget.EditText>(minuteId).setText(minute)
                controller.loopMainThreadUntilIdle()
                assertEquals(hour.isNotEmpty() && hour.toIntOrNull() in 1..12 && minute.toIntOrNull() in 0..59,picker.validateInput())
            }
        })
    }
    @Test fun invalidNativeKeyboardTimeCannotApplyOrConsumeThePendingApproval() {
        show();textClock("99","71")
        compose.onNodeWithTag("automatic-anchor-confirm").performClick()
        compose.runOnIdle {assertEquals("Invalid native text must not update the anchor",0,updates.size)}
        compose.onNodeWithTag("automatic-anchor-clock").assertExists()
        textClock("99","17");compose.onNodeWithTag("automatic-anchor-confirm").performClick()
        compose.runOnIdle {assertTrue(updates.isEmpty())};capture("clock-invalid")
        textClock("11","42");compose.onNodeWithTag("automatic-anchor-confirm").performClick()
        compose.runOnIdle {assertEquals(1,updates.size);assertEquals(11,updates.single().schedule.hour);assertEquals(42,updates.single().schedule.minute)}
    }
    @Test fun radialClockFaceIsFullyVisibleInsideTheModal() {
        show()
        Espresso.onView(ViewMatchers.isAssignableFrom(TimePicker::class.java)).inRoot(isDialog()).perform(object:androidx.test.espresso.ViewAction {
            override fun getConstraints():org.hamcrest.Matcher<android.view.View> = ViewMatchers.isAssignableFrom(TimePicker::class.java)
            override fun getDescription()="Check unclipped framework clock bounds"
            override fun perform(controller:androidx.test.espresso.UiController,view:android.view.View) {
                val id=view.resources.getIdentifier("radial_picker","id","android")
                val radial=view.findViewById<android.view.View>(id)
                val visible=android.graphics.Rect();assertTrue(radial.getGlobalVisibleRect(visible))
                assertEquals("Clock face clipped horizontally",radial.width,visible.width())
                assertEquals("Clock face clipped vertically",radial.height,visible.height())
            }
        })
    }
    private fun capture(stage:String) {
        if(InstrumentationRegistry.getArguments().getString("captureWorkspaceCallers")!="true") return
        compose.mainClock.advanceTimeBy(300);compose.waitForIdle()
        automation.waitForIdle(500,5000)
        val image=checkNotNull(automation.takeScreenshot())
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        try {
            val values=android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-workspace-caller-$stage-20261102.png")
                put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
            }
            val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
            target.contentResolver.openOutputStream(uri)!!.use {check(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
        } finally {image.recycle()}
    }

}
