package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.Density
import androidx.compose.ui.test.*
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

/** Real app/Room/native maps, synthetic density-driven arrangements; not physical resizing or hinge QA. */
class JournalWorkspaceShellTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var graph:AppGraph
    private lateinit var model:JournalViewModel
    private val models=ViewModelStore()
    private val density=mutableStateOf(1f)
    private val at=Instant.parse("2026-11-01T12:00:00Z")
    private val visit=Visit("workspace-visit",Coordinate(18.0,-66.0),at,at.plusSeconds(60),name="Workspace visit",source=VisitSource.manual,confirmation=Confirmation.confirmed)
    private val session=RecordingSession("workspace-outing",at,at.plusSeconds(60),name="Workspace outing")
    @Before fun setup()=runBlocking {
        val target=InstrumentationRegistry.getInstrumentation().targetContext;check(target.packageName=="com.bontecou.whereday.mapqa")
        graph=AppGraph.from(target);graph.journalClear.clear().join()
        graph.preferences.update {it.copy(onboarded=true,networkGeocoding=false,inferredEnabled=false)}
        graph.history.dao.put(VisitRow(visit));graph.history.dao.put(SessionRow(session))
        graph.history.dao.putPoints(listOf(PointRow(TrackPoint("workspace-first",visit.coordinate,at,5.0),session.id),PointRow(TrackPoint("workspace-last",Coordinate(18.001,-66.001),at.plusSeconds(60),5.0),session.id)))
        compose.activityRule.scenario.onActivity {model=JournalViewModel(graph,Clock.fixed(at.plusSeconds(3600),ZoneOffset.UTC));models.put("workspace",model)}
        Unit
    }
    @After fun cleanup()=runBlocking {try {if(::graph.isInitialized) graph.journalClear.clear().join()} finally {compose.activityRule.scenario.onActivity {models.clear()}};Unit}
    private fun show() {
        compose.setContent {CompositionLocalProvider(LocalDensity provides Density(density.value)) {WheredayApp(graph,model,{},{},{},{})}}
        compose.waitUntil(10000) {compose.onAllNodesWithTag("workspace-companion").fetchSemanticsNodes().size==1 && compose.onAllNodesWithTag("timeline-content").fetchSemanticsNodes().size==1 && compose.onAllNodesWithTag("journal-native-map").fetchSemanticsNodes().size==1}
    }
    @Test fun realMapAndTimelineSwapRolesWithoutReplacingTheNativeMap() {
        show();val original=compose.runOnIdle {checkNotNull(findNativeMapView())}
        compose.openTimeline()
        compose.onNodeWithTag("timeline-content").assertExists()
        compose.onNodeWithTag("timeline-content").assertIsDisplayed()
        compose.runOnIdle {assertSame(original,findNativeMapView())}
        compose.onNodeWithTag("timeline-close").performClick()
        compose.runOnIdle {assertSame(original,findNativeMapView())}
    }
    @Test fun showOnMapUpdatesTheCompanionAndRetainsTimelineOutingNavigation() {
        show();compose.openTimeline()
        compose.onNodeWithText(session.name!!).performScrollTo().performClick()
        compose.onNodeWithTag("outing-title").assertTextEquals(session.name!!)
        compose.onNodeWithText(compose.activity.getString(R.string.outing_show_map)).performScrollTo().performClick()
        compose.onNodeWithTag("timeline-content").assertExists()
        compose.onNodeWithTag("outing-title").assertExists()
        compose.runOnIdle {assertNotNull(model.mapSelection.value.outingRange)}
    }
    @Test fun sharedVisitFocusAndUnsavedEditorSurviveCompactAndExpandedPolicyTransitions() {
        show();compose.onNodeWithText(visit.name!!).performScrollTo().performClick()
        compose.waitUntil(5000) {model.mapSelection.value.visitFocus?.id==visit.id}
        compose.onNodeWithTag("visit-name").performTextReplacement("Unfinished workspace visit")
        compose.runOnIdle {density.value=maxOf(compose.activity.resources.displayMetrics.density,compose.activity.window.decorView.width/600f)}
        compose.onNodeWithTag("workspace-companion").assertDoesNotExist()
        compose.onNodeWithTag("visit-name").assertTextContains("Unfinished workspace visit")
        compose.runOnIdle {density.value=1f}
        compose.onNodeWithTag("workspace-companion").assertExists()
        compose.onNodeWithTag("visit-name").assertTextContains("Unfinished workspace visit")
        runBlocking {assertEquals(visit,graph.history.dao.visit(visit.id)!!.value)}
    }
    private fun capture(stage:String) {
        val args=InstrumentationRegistry.getArguments()
        if(args.getString("captureWorkspace")!="true") return
        val instrumentation=InstrumentationRegistry.getInstrumentation();val target=instrumentation.targetContext
        compose.waitForIdle();instrumentation.waitForIdleSync();instrumentation.uiAutomation.waitForIdle(700,5000)
        val mode=if(args.getString("expectedWorkspaceExpanded")=="true") "expanded" else "compact"
        val values=android.content.ContentValues().apply {
            put(android.provider.MediaStore.Downloads.DISPLAY_NAME,"whereday-workspace-$mode-$stage-20261101.png")
            put(android.provider.MediaStore.Downloads.MIME_TYPE,"image/png");put(android.provider.MediaStore.Downloads.RELATIVE_PATH,"Download/")
        }
        val uri=checkNotNull(target.contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI,values))
        target.contentResolver.openOutputStream(uri)!!.use {output->
            val bitmap=checkNotNull(instrumentation.uiAutomation.takeScreenshot())
            try {check(bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,output))} finally {bitmap.recycle()}
        }
    }
    @Test fun systemWindowUsesItsActualDensityForCompactOrExpandedNavigation() {
        val expected=InstrumentationRegistry.getArguments().getString("expectedWorkspaceExpanded")=="true"
        compose.setContent {WheredayApp(graph,model,{},{},{},{})}
        compose.waitUntil(10000) {compose.onAllNodesWithTag("journal-native-map").fetchSemanticsNodes().size==1}
        if(expected) {
            compose.onNodeWithTag("workspace-companion").assertIsDisplayed();compose.onNodeWithTag("timeline-content").assertIsDisplayed()
        } else compose.onNodeWithTag("workspace-companion").assertDoesNotExist()
        compose.onNodeWithTag("journal-native-map").performScrollTo()
        val view=compose.runOnIdle {checkNotNull(findNativeMapView())}
        val map=java.util.concurrent.atomic.AtomicReference<org.maplibre.android.maps.MapLibreMap>()
        compose.runOnUiThread {view.getMapAsync {map.set(it)}}
        compose.waitUntil(10000) {compose.runOnUiThread {map.get()?.style?.isFullyLoaded==true}}
        compose.awaitNativeMapFrame(view,map.get());capture("map-primary")
        compose.openTimeline();compose.onNodeWithTag("timeline-content").assertExists()
        compose.onNodeWithTag("timeline-content").assertIsDisplayed()
        if(expected) compose.runOnIdle {assertSame(view,findNativeMapView())}
        else compose.onNodeWithTag("journal-native-map").assertDoesNotExist()
        capture("timeline-primary")
    }

}
