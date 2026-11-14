package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.unit.dp
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.*
import org.junit.Assert.*
import java.time.Instant

/** Native modal bounds and owning-pane bounds must be compared in screen coordinates. */
class WorkspaceDialogTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val testDensity get()=if(InstrumentationRegistry.getArguments().getString("useSystemDensity")=="true") InstrumentationRegistry.getInstrumentation().targetContext.resources.displayMetrics.density else 1f
    private val automation get()=InstrumentationRegistry.getInstrumentation().uiAutomation
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    private fun screen(expectedOwner:String?=null):android.graphics.Rect {
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
            val bounds=found
            if(bounds==null || bounds.isEmpty) false
            else if(expectedOwner==null) true
            else {
                val owner=pane(expectedOwner)
                // Native accessibility bounds can still describe the preceding
                // posture after Compose has measured the new owning pane.
                owner.contains(bounds)
            }
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
    @Test fun confirmationStaysInBookAndTabletopCompanionWithInsetOrigin() {
        val axis=mutableStateOf(WorkspaceFoldAxis.vertical)
        compose.setContent {MaterialTheme {CompositionLocalProvider(LocalDensity provides Density(testDensity)) {
            Box(Modifier.fillMaxSize().padding(start=40.dp,top=36.dp)) {BoxWithConstraints {
                val w=constraints.maxWidth;val h=constraints.maxHeight
                val x=with(LocalDensity.current) {40.dp.roundToPx()};val y=with(LocalDensity.current) {36.dp.roundToPx()}
                val bounds=if(axis.value==WorkspaceFoldAxis.vertical) WorkspaceRect(w/2-16+x,y,w/2+16+x,h+y)
                    else WorkspaceRect(x,h/2-16+y,w+x,h/2+16+y)
                JournalWorkspaceFrame(true,folds=listOf(WorkspaceFold(bounds,axis.value,true)),primary={Text("Primary")},companion={
                    WorkspaceAlertDialog({},confirmButton={TextButton({}) {Text("Confirm")}},title={Text("Review")},text={Text("Pending review")})
                })
            }}
        }}}
        for(value in WorkspaceFoldAxis.entries) {
            compose.runOnIdle {axis.value=value}
            owns(screen("workspace-companion"),pane("workspace-companion"));compose.onNodeWithText("Confirm").assertIsDisplayed()
        }
    }
    @Test fun rtlPrimaryAndSingleRegionFallbackKeepModalOutOfBookHinge() {
        val paired=mutableStateOf(true)
        compose.setContent {MaterialTheme {CompositionLocalProvider(LocalDensity provides Density(testDensity),LocalLayoutDirection provides LayoutDirection.Rtl) {
            BoxWithConstraints {val w=constraints.maxWidth;val h=constraints.maxHeight
                JournalWorkspaceFrame(paired.value,folds=listOf(WorkspaceFold(WorkspaceRect(w/2-16,0,w/2+16,h),WorkspaceFoldAxis.vertical,true)),
                    primary={WorkspaceAlertDialog({},confirmButton={TextButton({}) {Text("Continue")}},title={Text("Primary review")})},companion={Text("Other")})
            }
        }}}
        owns(screen(),pane("workspace-primary"));compose.runOnIdle {paired.value=false}
        compose.onNodeWithTag("workspace-companion").assertDoesNotExist();owns(screen(),pane("workspace-primary"))
    }
    @Test fun outsideTapAndBackDismissWithoutActivatingUnderlyingPane() {
        val open=mutableStateOf(true);var taps=0
        compose.setContent {MaterialTheme {CompositionLocalProvider(LocalDensity provides Density(testDensity)) {
            JournalWorkspaceFrame(true,folds=emptyList(),primary={Button({taps++}) {Text("Underlying")}},companion={
                if(open.value) WorkspaceAlertDialog({open.value=false},confirmButton={TextButton({open.value=false}) {Text("Close review")}},title={Text("Review")})
            })
        }}}
        val underlying=pane("workspace-primary");screen()
        click(underlying.centerX(),underlying.centerY());compose.waitForIdle()
        compose.onNodeWithText("Review").assertDoesNotExist();assertEquals(0,taps)
        compose.runOnIdle {open.value=true};screen();back();compose.waitForIdle()
        compose.onNodeWithText("Review").assertDoesNotExist();assertEquals(0,taps)
    }
    @Test fun dateCalendarAndInvalidIsoReviewStayInsideOwnersPane() {
        var changed:Instant?=null
        compose.setContent {MaterialTheme {CompositionLocalProvider(LocalDensity provides Density(testDensity)) {
            BoxWithConstraints {val w=constraints.maxWidth;val h=constraints.maxHeight
                JournalWorkspaceFrame(true,folds=listOf(WorkspaceFold(WorkspaceRect(0,h/2-16,w,h/2+16),WorkspaceFoldAxis.horizontal,true)),primary={Text("Map")},companion={VisitDateTimeControl("Arrival",Instant.parse("2026-11-01T12:00:00Z")) {changed=it}})
            }
        }}}
        compose.onNodeWithTag("visit-timestamp-date").performClick();owns(screen(),pane("workspace-companion"));capture("calendar")
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        compose.onNodeWithText(context.getString(R.string.date_enter_iso)).performScrollTo().performClick()
        compose.onNodeWithTag("visit-timestamp-date-input").performTextReplacement("2026-02-30")
        androidx.test.espresso.Espresso.closeSoftKeyboard();owns(screen(),pane("workspace-companion"))
        compose.onNodeWithText(context.getString(R.string.visit_apply_date)).assertIsNotEnabled();assertNull(changed);capture("iso-invalid")
        back();compose.onNodeWithTag("visit-timestamp-date-input").assertDoesNotExist();assertNull(changed)
    }
    @Test fun unknownPostureKeepsRootModalHiddenUntilItsPrimarySafeRegionIsKnown() {
        val folds=mutableStateOf<List<WorkspaceFold>?>(null)
        compose.setContent {MaterialTheme {CompositionLocalProvider(LocalDensity provides Density(testDensity)) {
            JournalWorkspaceFrame(true,folds=folds.value,modal={WorkspaceAlertDialog({},confirmButton={TextButton({}) {Text("Accept root")}},title={Text("Root review")})},primary={Text("Primary")},companion={Text("Companion")})
        }}}
        compose.onAllNodes(isDialog()).assertCountEquals(0)
        compose.runOnIdle {folds.value=emptyList()}
        owns(screen(),pane("workspace-primary"));compose.onNodeWithText("Accept root").assertIsDisplayed()
    }

    @Test fun nestedPlaceSearchAndMapPickerRemainInTheInspectorRegionWithoutProviderRequests() {
        val allowed=kotlinx.coroutines.flow.MutableStateFlow(false)
        var requests=0;var selected:com.bontecou.whereday.domain.PlaceLocationSelection?=null
        val origin=com.bontecou.whereday.domain.Coordinate(18.25,-66.5)
        val provider=object:com.bontecou.whereday.domain.PlaceLookupProvider {
            override suspend fun search(query:String,language:String):List<com.bontecou.whereday.domain.PlaceLookupRecord> {requests++;error("Network is disabled")}
            override suspend fun reverse(coordinate:com.bontecou.whereday.domain.Coordinate,language:String):List<com.bontecou.whereday.domain.PlaceLookupRecord> {requests++;error("Network is disabled")}
        }
        val lookup=com.bontecou.whereday.domain.PlaceLookupService(provider,allowed,{"en"},minimumRequestIntervalMillis=0)
        compose.setContent {MaterialTheme {CompositionLocalProvider(LocalDensity provides Density(testDensity)) {
            BoxWithConstraints {val w=constraints.maxWidth;val h=constraints.maxHeight
            JournalWorkspaceFrame(true,folds=listOf(WorkspaceFold(WorkspaceRect(w/2-16,0,w/2+16,h),WorkspaceFoldAxis.vertical,true)),inspectionActive=true,inspection={
                PlaceLocationPicker(origin,lookup,allowed,{},{selected=it})
            },primary={Text("Map owner")},companion={})
            }
        }}}
        owns(screen(),pane("workspace-companion"));compose.onNodeWithTag("place-pin-use").assertIsEnabled();capture("picker")
        compose.onNodeWithTag("place-pin-search").performScrollTo().performClick()
        compose.onNodeWithTag("place-search-query").performTextReplacement("Unsent search draft")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        owns(screen(),pane("workspace-companion"));compose.onNodeWithTag("place-search-submit").assertIsNotEnabled();capture("search")
        compose.onNodeWithTag("place-search-close").performClick()
        compose.onNodeWithTag("place-pin-use").performClick()
        assertEquals(origin,selected?.coordinate);assertEquals(0,requests)
    }
    private fun capture(stage:String) {
        if(InstrumentationRegistry.getArguments().getString("captureWorkspaceDialogs")!="true") return
        compose.mainClock.advanceTimeBy(300);compose.waitForIdle()
        automation.waitForIdle(500,5000)
        val image=checkNotNull(automation.takeScreenshot())
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        try {
            val values=android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-workspace-dialog-$stage-20261102.png")
                put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
            }
            val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
            target.contentResolver.openOutputStream(uri)!!.use {check(image.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it))}
        } finally {image.recycle()}
    }

}
