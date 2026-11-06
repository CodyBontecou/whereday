package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.unit.Density
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import androidx.window.layout.FoldingFeature
import androidx.window.testing.layout.FoldingFeature
import androidx.window.testing.layout.TestWindowLayoutInfo
import androidx.window.testing.layout.WindowLayoutInfoPublisherRule
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.time.*

/** Real component/modal entry paths, synthetic records, no owner history or permission requests. */
class WorkspaceCallerDialogTest {
    @get:Rule(order=0) val windows=WindowLayoutInfoPublisherRule()
    @get:Rule(order=1) val compose=createAndroidComposeRule<ComponentActivity>()
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val automation get()=InstrumentationRegistry.getInstrumentation().uiAutomation
    private val testDensity get()=if(InstrumentationRegistry.getArguments().getString("useSystemDensity")=="true") target.resources.displayMetrics.density else 1f
    private lateinit var graph:AppGraph
    private lateinit var model:JournalViewModel
    private val models=ViewModelStore()
    private val at=Instant.parse("2026-11-01T12:00:00Z")
    private val points=(0..2).map {TrackPoint("caller-$it",Coordinate(18.0+it*.001,-66.0),at.plusSeconds(60L*it),5.0)}
    @Before fun setup():Unit=runBlocking {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        graph=AppGraph.from(target);graph.journalClear.clear().join()
        graph.preferences.update {it.copy(onboarded=true,networkGeocoding=false,inferredEnabled=false)}
        graph.history.dao.putPoints(points.map {PointRow(it,null)})
        compose.activityRule.scenario.onActivity {model=JournalViewModel(graph,Clock.fixed(at.plusSeconds(3600),ZoneOffset.UTC));models.put("caller",model)}
    }
    @After fun cleanup():Unit=runBlocking {
        try {if(::graph.isInitialized) graph.journalClear.clear().join()} finally {compose.activityRule.scenario.onActivity {models.clear()}}
    }
    private fun frame(horizontal:Boolean=false,primary:Boolean=false,content:@Composable ()->Unit) {
        compose.setContent {MaterialTheme {CompositionLocalProvider(LocalDensity provides Density(testDensity)) {BoxWithConstraints {
            val w=constraints.maxWidth;val h=constraints.maxHeight
            val fold=if(horizontal) WorkspaceFold(WorkspaceRect(0,h/2-16,w,h/2+16),WorkspaceFoldAxis.horizontal,true)
                else WorkspaceFold(WorkspaceRect(w/2-16,0,w/2+16,h),WorkspaceFoldAxis.vertical,true)
            JournalWorkspaceFrame(true,folds=listOf(fold),primary={if(primary) content() else Text("Primary owner")},companion={if(!primary) content() else Text("Companion owner")})
        }}}}
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
    @Test fun mapLayersAndRecordedPointChooserStayInTheCompanion() {
        frame {JournalMap(points,emptyList())}
        compose.onNodeWithText(target.getString(R.string.map_layers)).performClick()
        owns(screen(),pane("workspace-companion"));capture("map-layers")
        compose.onNodeWithText(target.getString(R.string.map_layers_done)).performClick()
        compose.onNodeWithTag("map-point-browse").performClick()
        owns(screen(),pane("workspace-companion"))
        back();compose.onNodeWithTag("map-point-browse").assertExists()
        runBlocking {assertEquals(3L,graph.history.dao.totalPointCount())}
    }
    @Test fun customRangeCalendarAndInvalidIsoRemainInsideTabletopRegion() {
        var applied=false
        frame(horizontal=true) {CustomDateRange(LocalDate.of(2026,11,1),LocalDate.of(2026,11,1),{},latestDate=LocalDate.of(2026,11,2)) {_,_->applied=true}}
        owns(screen(),pane("workspace-companion"));capture("range-calendar")
        compose.onNodeWithText(target.getString(R.string.date_enter_iso)).performClick()
        compose.onNodeWithTag("date-range-from").performTextReplacement("2026-02-30")
        androidx.test.espresso.Espresso.closeSoftKeyboard()
        owns(screen(),pane("workspace-companion"));compose.onNodeWithText(target.getString(R.string.map_apply_dates)).assertIsNotEnabled()
        assertFalse(applied)
    }
    @Test fun replayScrubAndCloseKeepStoredPointsAndSafePane():Unit=runBlocking {
        var open=false
        frame {RouteReplayButton(graph,ReplayRequest.Range(HistoryRange(at,at.plusSeconds(121))),true,true,onShowingChange={open=it})}
        compose.onNodeWithText(target.getString(R.string.map_replay)).performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithTag("replay-progress").fetchSemanticsNodes().isNotEmpty()}
        owns(screen(),pane("workspace-companion"))
        compose.onNodeWithTag("replay-end").performScrollTo().performClick();capture("replay")
        compose.onNodeWithTag("replay-close").performClick();compose.waitUntil(5000) {!open};compose.onNodeWithTag("route-replay-content").assertDoesNotExist()
        assertEquals(points,graph.history.dao.exportFirstPoints(at,at.plusSeconds(121),null).map {it.value})
    }
    @Test fun photoPagerControlsAndUnavailableSyntheticImageRemainInBookRegion() {
        val open=mutableStateOf(true)
        val entries=listOf(PhotoLibraryEntry(PhotoAssetRow("content://synthetic/workspace-photo",at,null,true,false,at),"photo_gps"))
        frame {if(open.value) PhotoPager(graph,entries,0,{open.value=false})}
        owns(screen(),pane("workspace-companion"));compose.onNodeWithTag("photo-pager-content").assertIsDisplayed();capture("photo")
        compose.onNodeWithText(target.getString(R.string.photo_close)).performScrollTo().performClick()
        compose.onNodeWithTag("photo-pager-content").assertDoesNotExist();assertFalse(open.value)
    }
    @Test fun savedPlaceDirtyReviewIsPaneBoundAndDoesNotSaveOnCancel() {
        val place=SavedPlace("caller-place","Original place",Coordinate(18.0,-66.0),"Original address",150.0)
        var saves=0
        frame {PlaceEditor(place,{},{saves++},null)}
        compose.onNodeWithTag("saved-name").performTextReplacement("Unsaved place draft");androidx.test.espresso.Espresso.closeSoftKeyboard()
        compose.onNodeWithTag("saved-editor-cancel").performClick()
        owns(screen(),pane("workspace-companion"));capture("place-discard")
        compose.onNodeWithText(target.getString(R.string.visit_keep_editing)).performClick()
        compose.onNodeWithTag("saved-name").assertTextContains("Unsaved place draft");assertEquals(0,saves)
    }
    @Test fun rootPermissionReviewUsesFoldSafeScopeWithoutDuplicateWorkspaceTags() {
        val review=mutableStateOf<PermissionReview?>(null);var requests=0
        compose.setContent {CompositionLocalProvider(LocalDensity provides Density(testDensity)) {
            WheredayApp(graph,model,{},{},{},{},accessReview=review.value,onAccessDismiss={review.value=null},onAccessRequest={requests++;review.value=null})
        }}
        val fold=FoldingFeature(compose.activity,size=32,orientation=FoldingFeature.Orientation.VERTICAL)
        compose.runOnIdle {windows.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(fold)));review.value=PermissionReview(44,graph.journalClear.state.value.generation,0,PermissionAccessKind.location,true)}
        val rect=screen();assertTrue(rect.right<=fold.bounds.left || rect.left>=fold.bounds.right)
        compose.onAllNodesWithTag("journal-workspace").assertCountEquals(1);capture("permission")
        compose.onNodeWithTag("permission-review-confirm").performClick();assertEquals(1,requests)
    }
    @Test fun launcherConfirmationUsesPrimaryPaneAndCancelDoesNotStartRecording() {
        val action=mutableStateOf<LauncherAction?>(LauncherAction.start);var starts=0
        compose.setContent {CompositionLocalProvider(LocalDensity provides Density(testDensity)) {
            WheredayApp(graph,model,{},{},{},{},launcherAction=action.value,onLauncherActionHandled={action.value=null},onStartRecording={starts++})
        }}
        val fold=FoldingFeature(compose.activity,size=32,orientation=FoldingFeature.Orientation.VERTICAL)
        compose.runOnIdle {windows.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(fold)))}
        owns(screen(),pane("workspace-primary"));capture("launcher")
        compose.onNodeWithTag("launcher-recording-cancel").performClick();assertEquals(0,starts)
        runBlocking {assertFalse(graph.history.dao.recording()?.requested==true)}
    }
    @Test fun onboardingUsesOneSafeRegionBeforeJournalNavigationExists():Unit=runBlocking {
        graph.preferences.update {it.copy(onboarded=false)}
        compose.setContent {CompositionLocalProvider(LocalDensity provides Density(testDensity)) {WheredayApp(graph,model,{},{},{},{})}}
        val fold=FoldingFeature(compose.activity,size=32,orientation=FoldingFeature.Orientation.VERTICAL)
        compose.runOnIdle {windows.overrideWindowLayoutInfo(TestWindowLayoutInfo(listOf(fold)))}
        compose.waitUntil(10000) {runCatching {val rect=pane("onboarding-screen");rect.right<=fold.bounds.left || rect.left>=fold.bounds.right}.getOrDefault(false)}
        compose.onAllNodesWithTag("journal-workspace").assertCountEquals(1);capture("onboarding")
        assertEquals(3L,graph.history.dao.totalPointCount());assertFalse(graph.history.dao.recording()?.requested==true)
    }
    @Test fun choiceMenuSelectsExplicitlyInsideTheCompanion() {
        val value=mutableStateOf("metric")
        frame {Choice("Units",value.value,listOf("metric","imperial"),labelFor={it}) {value.value=it}}
        compose.onNodeWithText("metric").performClick();owns(screen(),pane("workspace-companion"));capture("choice")
        compose.onNodeWithText("imperial").performClick();compose.runOnIdle {assertEquals("imperial",value.value)}
        compose.onNodeWithTag("workspace-dialog-content").assertDoesNotExist()
    }
    @Test fun exportDeliverySheetStaysInPaneAndClosingDoesNotPrepareAFile() {
        var preparations=0
        frame {HistoryFileActions(graph,"Export synthetic outing",true) {preparations++;error("No delivery was requested")}}
        compose.waitUntil(10000) {runCatching {compose.onNodeWithTag("history-export-open").assertIsEnabled()}.isSuccess}
        compose.onNodeWithTag("history-export-open").performClick();owns(screen(),pane("workspace-companion"));capture("export-sheet")
        compose.onNodeWithText(target.getString(R.string.export_delivery_done)).performScrollTo().performClick()
        compose.onNodeWithTag("history-export-share").assertDoesNotExist();assertEquals(0,preparations)
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
