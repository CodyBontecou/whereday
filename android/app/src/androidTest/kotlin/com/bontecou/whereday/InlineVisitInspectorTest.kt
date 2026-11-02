package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import java.time.*

/** One actual app editor across synthetic pane policies; physical/posture/nested-window placement is separate. */
class InlineVisitInspectorTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var graph:AppGraph
    private lateinit var model:JournalViewModel
    private val models=ViewModelStore()
    private val density=mutableStateOf(1f)
    private val pending=mutableStateOf<VisitLinkRequest?>(null)
    private val target get()=InstrumentationRegistry.getInstrumentation().targetContext
    private val at=Instant.parse("2026-11-01T12:00:00Z")
    private val original=Visit("123e4567-e89b-12d3-a456-426614174070",Coordinate(18.0,-66.0),at,at.plusSeconds(60),name="Inline first",notes="Original note",source=VisitSource.manual,confirmation=Confirmation.confirmed)
    private val next=original.copy(id="123e4567-e89b-12d3-a456-426614174071",coordinate=Coordinate(18.01,-66.01),arrivedAt=at.plusSeconds(120),departedAt=at.plusSeconds(180),name="Inline next")
    @Before fun setup()=runBlocking {
        check(target.packageName=="com.bontecou.whereday.mapqa")
        if(InstrumentationRegistry.getArguments().getString("useSystemDensity")=="true") density.value=target.resources.displayMetrics.density
        graph=AppGraph.from(target);graph.journalClear.clear().join()
        graph.preferences.update {it.copy(onboarded=true,networkGeocoding=false,inferredEnabled=false)}
        graph.history.dao.put(VisitRow(original));graph.history.dao.put(VisitRow(next))
        graph.history.dao.put(PointRow(TrackPoint("inline-point",original.coordinate,at,5.0),null))
        compose.activityRule.scenario.onActivity {model=JournalViewModel(graph,Clock.fixed(at.plusSeconds(3600),ZoneOffset.UTC));models.put("inline",model)}
        Unit
    }
    @After fun cleanup()=runBlocking {try {if(::graph.isInitialized) graph.journalClear.clear().join()} finally {compose.activityRule.scenario.onActivity {models.clear()}};Unit}
    private fun show():StateRestorationTester=StateRestorationTester(compose).also {tester->tester.setContent {
        CompositionLocalProvider(LocalDensity provides Density(density.value)) {
            WheredayApp(graph,model,{},{},{},{},visitLink=pending.value,onVisitLinkHandled={id->if(pending.value?.id==id) pending.value=null})
        }
    }}
    private fun awaitName(name:String=original.name!!) {compose.waitUntil(10000) {runCatching {compose.onNodeWithTag("visit-name").assertTextContains(name)}.isSuccess}}
    private fun open() {
        compose.waitUntil(10000) {compose.onAllNodesWithTag("timeline-content").fetchSemanticsNodes().size==1}
        compose.onNodeWithText(original.name!!).performScrollTo().performClick();awaitName()
    }
    private fun compact() {compose.runOnIdle {density.value=maxOf(target.resources.displayMetrics.density,compose.activity.window.decorView.width/600f)}}
    private fun expanded() {compose.runOnIdle {density.value=if(InstrumentationRegistry.getArguments().getString("useSystemDensity")=="true") target.resources.displayMetrics.density else 1f}}
    private fun closeKeyboard() {androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot()).perform(androidx.test.espresso.action.ViewActions.closeSoftKeyboard())}
    private fun back() {compose.onNodeWithTag("visit-inspector-back").performClick()}
    @Test fun mapPrimaryShowsOneInlineInspectorAndRemovesTheCoveredTimelineFromInteraction() {
        show();open()
        compose.onNodeWithTag("visit-inline-inspector").assertIsDisplayed();compose.onNodeWithTag("timeline-content").assertDoesNotExist()
        compose.onNodeWithTag("journal-native-map").assertExists()
        assertEquals(1,compose.onAllNodesWithTag("visit-editor-fields").fetchSemanticsNodes().size)
        assertEquals(0,compose.onAllNodes(isDialog()).fetchSemanticsNodes().size)
        capture("map-primary")
        back();compose.onNodeWithTag("visit-inline-inspector").assertDoesNotExist();compose.onNodeWithTag("timeline-content").assertIsDisplayed()
    }
    @Test fun dirtyFieldsAndOneEditorOwnerSurviveBothPresentationDirectionsAndRestoration() {
        val tester=show();open()
        compose.onNodeWithTag("visit-name").performTextReplacement("Unfinished inline draft");closeKeyboard()
        compact();awaitName("Unfinished inline draft");compose.onNodeWithTag("visit-inline-inspector").assertDoesNotExist()
        assertEquals(1,compose.onAllNodes(isDialog()).fetchSemanticsNodes().size)
        expanded();awaitName("Unfinished inline draft");compose.onNodeWithTag("visit-inline-inspector").assertIsDisplayed()
        tester.emulateSavedInstanceStateRestore();awaitName("Unfinished inline draft")
        assertEquals(1,compose.onAllNodesWithTag("visit-editor-fields").fetchSemanticsNodes().size)
        runBlocking {assertEquals(original,graph.history.dao.visit(original.id)!!.value)}
    }
    @Test fun nestedInvalidDateDraftRemainsPendingAcrossTheDialogAndPaneHandoff() {
        val tester=show();open()
        compose.onNode(hasTestTag("visit-timestamp-date") and hasAnyAncestor(hasTestTag("visit-arrival-time"))).performScrollTo().performClick()
        compose.onNodeWithText(target.getString(R.string.date_enter_iso)).performScrollTo().performClick()
        compose.onNodeWithTag("visit-timestamp-date-input").performTextReplacement("2026-02-30")
        compact();compose.onNodeWithTag("visit-timestamp-date-input").assertTextContains("2026-02-30")
        compose.onNodeWithText(target.getString(R.string.visit_apply_date)).assertIsNotEnabled()
        expanded();compose.onNodeWithTag("visit-timestamp-date-input").assertTextContains("2026-02-30")
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithTag("visit-timestamp-date-input").assertTextContains("2026-02-30")
        compose.onNodeWithText(target.getString(R.string.visit_apply_date)).assertIsNotEnabled()
        runBlocking {assertEquals(original,graph.history.dao.visit(original.id)!!.value)}
    }
    @Test fun discardReviewSurvivesTheHandoffAndCancelRetainsTheSameUnsavedDraft() {
        show();open();compose.onNodeWithTag("visit-name").performTextReplacement("Keep this draft");closeKeyboard();back()
        compose.onNodeWithText(target.getString(R.string.visit_discard_title)).assertExists()
        compact();compose.onNodeWithText(target.getString(R.string.visit_discard_title)).assertExists()
        expanded();compose.onNodeWithText(target.getString(R.string.visit_keep_editing)).performClick();awaitName("Keep this draft")
        runBlocking {assertEquals(original,graph.history.dao.visit(original.id)!!.value)}
    }
    @Test fun independentEditCannotReplaceTheOriginalBaselineDuringHandoff()=runBlocking {
        val tester=show();open();compose.onNodeWithTag("visit-name").performTextReplacement("Stale draft");closeKeyboard()
        val updated=original.copy(notes="Independent edit",updatedAt=at.plusSeconds(600));graph.history.dao.put(VisitRow(updated))
        compose.onNodeWithText(target.getString(R.string.map_visits)).performScrollTo().performClick()
        compose.onNode(hasText(original.name!!) and hasAnyAncestor(isDialog())).performClick();awaitName("Stale draft")
        compact();expanded();tester.emulateSavedInstanceStateRestore();awaitName("Stale draft")
        compose.onNodeWithTag("visit-save").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithTag("visit-editor-error").fetchSemanticsNodes().size==1}
        assertEquals(updated,graph.history.dao.visit(original.id)!!.value)
    }
    @Test fun selectingAnotherMapVisitWaitsForExplicitCloseAndLoadsItsFreshSavedSnapshot()=runBlocking {
        show();open();compose.onNodeWithTag("visit-name").performTextReplacement("Keep first draft");closeKeyboard()
        compose.onNodeWithText(target.getString(R.string.map_visits)).performScrollTo().performClick()
        compose.onNode(hasText(next.name!!) and hasAnyAncestor(isDialog())).performClick();awaitName("Keep first draft")
        val newer=next.copy(name="Fresh second visit",updatedAt=at.plusSeconds(600));graph.history.dao.put(VisitRow(newer))
        back();compose.onNodeWithText(target.getString(R.string.visit_discard)).performClick();awaitName(newer.name!!)
        assertEquals(original,graph.history.dao.visit(original.id)!!.value)
        assertEquals(newer,graph.history.dao.visit(next.id)!!.value)
        assertEquals(2L,graph.history.dao.totalVisitCount())
    }
    @Test fun externalVisitLinkWaitsForInlineAndCompactInspectionToClose()=runBlocking {
        show();open()
        compose.runOnIdle {pending.value=VisitLinkRequest.receive("whereday://check-in?name=Queued%20inline&latitude=18&longitude=-66",graph.journalClear.state.value.generation.orEmpty())}
        compact();expanded();awaitName();assertNotNull(pending.value)
        back();awaitName("Queued inline");compose.waitUntil(5000) {pending.value==null}
        assertEquals(2L,graph.history.dao.totalVisitCount())
    }
    @Test fun manualCreationCannotOpenASecondEditorUntilTheInspectorCloses() {
        show();open()
        val current=compose.onNodeWithText(target.getString(R.string.map_save_current))
        current.assertIsNotEnabled().performScrollTo().performTouchInput {click()}
        compose.onNodeWithText(target.getString(R.string.map_add_past)).assertIsNotEnabled()
        awaitName();assertEquals(1,compose.onAllNodesWithTag("visit-name").fetchSemanticsNodes().size)
        back();current.performScrollTo().assertIsEnabled().performClick()
        compose.onNodeWithTag("visit-inline-inspector").assertDoesNotExist()
        compose.onNodeWithTag("visit-name").assertTextContains("")
        assertEquals(1,compose.onAllNodesWithTag("visit-name").fetchSemanticsNodes().size)
        runBlocking {assertEquals(2L,graph.history.dao.totalVisitCount())}
    }
    @Test fun timelinePrimaryInspectorOccupiesItsPaneAndReturnsToTheRetainedTimeline() {
        show();compose.waitUntil(10000) {compose.onAllNodesWithTag("navigation-timeline").fetchSemanticsNodes().size==1}
        compose.onNodeWithTag("navigation-timeline").performClick();open()
        val owner=compose.onNodeWithTag("workspace-primary").fetchSemanticsNode().boundsInWindow
        assertEquals(owner,compose.onNodeWithTag("visit-inline-inspector").fetchSemanticsNode().boundsInWindow)
        compose.onNodeWithTag("timeline-content").assertDoesNotExist();compose.onNodeWithTag("journal-native-map").assertExists()
        capture("timeline-primary")
        back();compose.onNodeWithTag("timeline-content").assertIsDisplayed();compose.onNodeWithTag("navigation-timeline").assertIsSelected()
    }

    private fun capture(stage:String) {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val control=compose.onNodeWithText(target.getString(R.string.map_save_current));control.assertIsNotEnabled()
        compose.onNodeWithText(target.getString(R.string.map_add_past)).assertIsNotEnabled()
        compose.mainClock.advanceTimeBy(300);compose.waitForIdle();compose.mainClock.advanceTimeByFrame();compose.waitForIdle()
        instrumentation.waitForIdleSync();instrumentation.uiAutomation.waitForIdle(700,5000)
        val bounds=control.fetchSemanticsNode().boundsInWindow
        val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
        try {
            var enabledColorPixels=0
            for(y in bounds.top.toInt().coerceAtLeast(0) until bounds.bottom.toInt().coerceAtMost(bitmap.height)) for(x in bounds.left.toInt().coerceAtLeast(0) until bounds.right.toInt().coerceAtMost(bitmap.width)) {
                val color=bitmap.getPixel(x,y)
                for(rgb in listOf(Triple(40,93,66),Triple(168,216,184))) if(kotlin.math.abs(android.graphics.Color.red(color)-rgb.first)<=3 && kotlin.math.abs(android.graphics.Color.green(color)-rgb.second)<=3 && kotlin.math.abs(android.graphics.Color.blue(color)-rgb.third)<=3) enabledColorPixels++
            }
            assertEquals("Disabled creation label must not retain its enabled primary color",0,enabledColorPixels)
            if(InstrumentationRegistry.getArguments().getString("captureInlineInspector")!="true") return
            val values=android.content.ContentValues().apply {
                put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-inline-inspector-$stage-20261101.png")
                put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
            }
            val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
            target.contentResolver.openOutputStream(uri)!!.use {output->check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output))}
        } finally {bitmap.recycle()}
    }
    @Test fun timelinePrimaryInspectorDisablesManualCreationInTheCompanionMap() {
        show();compose.waitUntil(10000) {compose.onAllNodesWithTag("navigation-timeline").fetchSemanticsNodes().size==1}
        compose.onNodeWithTag("navigation-timeline").performClick();open()
        compose.onNodeWithText(target.getString(R.string.map_save_current)).assertIsNotEnabled().performTouchInput {click()}
        compose.onNodeWithText(target.getString(R.string.map_add_past)).assertIsNotEnabled()
        awaitName();assertEquals(1,compose.onAllNodesWithTag("visit-name").fetchSemanticsNodes().size)
    }
    @Test fun explicitInlineSaveUpdatesTheVisitAndReturnsToTheTimelineWithoutChangingItsIdentity():Unit=runBlocking {
        show();open();compose.onNodeWithTag("visit-name").performTextReplacement("Saved inline name")
        compose.onNodeWithTag("visit-notes").performScrollTo().performTextReplacement("Saved inline note");closeKeyboard()
        compose.onNodeWithTag("visit-save").performClick()
        compose.waitUntil(10000) {compose.onAllNodesWithTag("timeline-content").fetchSemanticsNodes().size==1}
        val saved=graph.history.dao.visit(original.id)!!.value
        assertEquals(original.id,saved.id);assertEquals("Saved inline name",saved.name);assertEquals("Saved inline note",saved.notes)
        assertEquals(original.coordinate,saved.coordinate);assertEquals(original.arrivedAt,saved.arrivedAt);assertEquals(original.departedAt,saved.departedAt)
        assertEquals(2L,graph.history.dao.totalVisitCount());assertEquals(next,graph.history.dao.visit(next.id)!!.value)
        compose.onNodeWithText("Saved inline name").performScrollTo().assertIsDisplayed()
    }

}
