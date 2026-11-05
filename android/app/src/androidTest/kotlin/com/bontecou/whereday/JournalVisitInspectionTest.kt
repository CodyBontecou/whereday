package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.runtime.mutableStateOf
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

/** Actual shell selection/restoration, using only the disposable QA application's journal. */
class JournalVisitInspectionTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var graph:AppGraph
    private lateinit var model:JournalViewModel
    private val models=ViewModelStore()
    private val pending=mutableStateOf<VisitLinkRequest?>(null)
    private val now=Instant.ofEpochMilli(System.currentTimeMillis())
    private val original=Visit("shared-inspector-qa",Coordinate(18.0,-66.0),now.minusSeconds(3600),now.minusSeconds(1800),
        name="QA shared visit",notes="Original note",source=VisitSource.manual,confirmation=Confirmation.confirmed,confirmedAt=now.minusSeconds(1800))
    @Before fun setup()=runBlocking {
        val target=InstrumentationRegistry.getInstrumentation().targetContext
        check(target.packageName=="com.bontecou.whereday.mapqa")
        graph=AppGraph.from(target);graph.journalClear.clear().join()
        graph.preferences.update {it.copy(onboarded=true,networkGeocoding=false,inferredEnabled=false)}
        graph.history.dao.put(VisitRow(original))
        compose.activityRule.scenario.onActivity {model=JournalViewModel(graph,Clock.fixed(now,ZoneOffset.UTC),{ZoneOffset.UTC});models.put("inspection",model)}
        Unit
    }
    @After fun cleanup()=runBlocking {
        try {graph.journalClear.clear().join()}
        finally {compose.activityRule.scenario.onActivity {models.clear()}}
    }
    private fun show()=StateRestorationTester(compose).also {tester->tester.setContent {
        WheredayApp(graph,model,{},{},{},{},visitLink=pending.value,onVisitLinkHandled={token->if(pending.value?.id==token) pending.value=null})
    }}
    private fun awaitName(name:String) {compose.waitUntil(10000) {runCatching {compose.onNodeWithTag("visit-name").assertTextContains(name)}.isSuccess}}
    private fun open(timeline:Boolean) {
        compose.waitUntil(10000) {runCatching {compose.onNodeWithTag("map-day-peek").assertExists()}.isSuccess}
        if(timeline) {
            compose.openTimeline()
            compose.waitUntil(10000) {runCatching {compose.onNodeWithText(original.name!!).assertExists()}.isSuccess}
            compose.onNode(hasText(original.name!!) and hasAnyAncestor(hasTestTag("timeline-content"))).performScrollTo().performClick()
        } else {
            compose.waitUntil(10000) {runCatching {compose.onNodeWithTag("journal-native-map").assertExists()}.isSuccess}
            compose.onNodeWithTag("journal-native-map").performMapAction("Visits on map")
            compose.onNode(hasText(original.name!!) and hasAnyAncestor(isDialog())).performClick()
            compose.openVisitDetails()
        }
        awaitName(original.name!!)
    }
    private fun keyboard() {androidx.test.espresso.Espresso.onView(androidx.test.espresso.matcher.ViewMatchers.isRoot()).inRoot(androidx.test.espresso.matcher.RootMatchers.isDialog()).perform(androidx.test.espresso.action.ViewActions.closeSoftKeyboard())}
    private fun restoredEdit(timeline:Boolean)=runBlocking {
        val tester=show();open(timeline)
        compose.onNodeWithTag("visit-name").performTextReplacement("Unsaved shared edit");keyboard()
        assertEquals(original,graph.history.dao.visit(original.id)!!.value)
        tester.emulateSavedInstanceStateRestore();awaitName("Unsaved shared edit")
        assertEquals(1,compose.onAllNodesWithTag("visit-editor-fields").fetchSemanticsNodes().size)
        assertEquals(original,graph.history.dao.visit(original.id)!!.value)
        compose.onNodeWithTag("visit-save").performClick()
        compose.waitUntil(10000) {runBlocking {graph.history.dao.visit(original.id)?.value?.name=="Unsaved shared edit"}}
        assertEquals(1L,graph.history.dao.totalVisitCount());assertEquals(original.notes,graph.history.dao.visit(original.id)!!.value.notes)
    }
    @Test fun timelineSelectionKeepsOneUnsavedEditorAcrossRestoration()=restoredEdit(true)
    @Test fun mapChooserUsesTheSameRestorableEditorOwner()=restoredEdit(false)
    @Test fun restoredSharedInspectionRetainsItsOriginalConcurrentEditBaseline()=runBlocking {
        val tester=show();open(true)
        compose.onNodeWithTag("visit-name").performTextReplacement("Unsaved shared edit");keyboard()
        val newer=original.copy(notes="Changed independently",updatedAt=now)
        graph.history.dao.put(VisitRow(newer));tester.emulateSavedInstanceStateRestore();awaitName("Unsaved shared edit")
        compose.onNodeWithTag("visit-save").performClick()
        compose.waitUntil(10000) {runCatching {compose.onNodeWithTag("visit-editor-error").assertExists()}.isSuccess}
        compose.onNodeWithTag("visit-editor-error").assertTextEquals(VisitChanged().message!!)
        assertEquals(newer,graph.history.dao.visit(original.id)!!.value);assertEquals(1L,graph.history.dao.totalVisitCount())
    }
    @Test fun queuedLinkWaitsUntilTheSharedInspectionCloses()=runBlocking {
        show();open(true)
        compose.runOnIdle {pending.value=VisitLinkRequest.receive("whereday://check-in?name=Pending%20QA&latitude=18&longitude=-66",graph.journalClear.state.value.generation.orEmpty())}
        compose.waitForIdle();awaitName(original.name!!)
        assertNotNull(pending.value);assertEquals(original,graph.history.dao.visit(original.id)!!.value)
        compose.onNodeWithText("Cancel").performClick();awaitName("Pending QA")
        compose.waitUntil(5000) {pending.value==null}
        assertEquals(1L,graph.history.dao.totalVisitCount());assertEquals(original,graph.history.dao.visit(original.id)!!.value)
    }
}
