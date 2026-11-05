package com.bontecou.whereday

import android.os.Bundle
import android.os.Parcel
import androidx.activity.ComponentActivity
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.saveable.SaverScope
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.lifecycle.ViewModelStore
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.time.Instant

class VisitEditorRestorationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var graph:AppGraph
    private lateinit var model:JournalViewModel
    private val models=ViewModelStore()
    @Before fun setup()=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.mapqa")
        graph=AppGraph.from(context)
        graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
        compose.activityRule.scenario.onActivity {model=JournalViewModel(graph);models.put("journal",model)}
        Unit
    }
    @After fun cleanup()=runBlocking {
        compose.activityRule.scenario.onActivity {models.clear()}
        graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
        Unit
    }
    private fun screen(timeline:Boolean=false):StateRestorationTester=StateRestorationTester(compose).also {tester->tester.setContent {
        MaterialTheme {
            if(timeline) TimelineScreen(graph,model,true,{})
            else JournalMapScreen(graph,model,false,null,true,{},false,{})
        }
    }}
    private fun awaitText(text:String) {compose.waitUntil(10000) {runCatching {compose.onNodeWithText(text).assertExists()}.isSuccess}}
    private fun manualDraft(label:String,timeline:Boolean=false)=runBlocking {
        val tester=screen(timeline)
        // On the map, manual stops start from the + menu; Timeline keeps its own Add past visit capsule.
        if(timeline) compose.onNodeWithText(label).performScrollTo().performClick()
        else {
            compose.waitUntil(10000) {compose.onAllNodesWithTag("map-add").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag("map-add").performClick()
            compose.onNodeWithTag(if(label=="Save current place") "map-add-current" else "map-add-past").performClick()
        }
        compose.onNodeWithText("Name").performTextInput("Restored QA visit")
        compose.onNodeWithText("Address").performTextInput("Synthetic address")
        compose.onNodeWithText("Latitude").performScrollTo().performTextInput("48.8584")
        compose.onNodeWithText("Longitude").performScrollTo().performTextInput("2.2945")
        compose.onNodeWithText("Notes").performScrollTo().performTextInput("Keep this unsaved note")
        assertEquals(0L,graph.history.dao.totalVisitCount())
        tester.emulateSavedInstanceStateRestore()
        assertFalse(graph.history.dao.recording()?.requested==true)
        compose.onNodeWithText("Name").performScrollTo().assertTextContains("Restored QA visit")
        compose.onNodeWithText("Address").performScrollTo().assertTextContains("Synthetic address")
        compose.onNodeWithText("Latitude").performScrollTo().assertTextContains("48.8584")
        compose.onNodeWithText("Longitude").performScrollTo().assertTextContains("2.2945")
        compose.onNodeWithText("Notes").performScrollTo().assertTextContains("Keep this unsaved note")
        assertEquals(0L,graph.history.dao.totalVisitCount())
        compose.onNodeWithText("Save",substring=false).performClick()
        compose.waitUntil(5000) {runBlocking {graph.history.dao.totalVisitCount()==1L}}
        val visit=graph.history.dao.rangeVisits(Instant.EPOCH,Instant.now().plusSeconds(1)).single().value
        assertEquals("Restored QA visit",visit.name);assertEquals("Keep this unsaved note",visit.notes)
        assertEquals(Coordinate(48.8584,2.2945),visit.coordinate)
        assertEquals(label=="Save current place",visit.departedAt==null)
    }
    @Test fun currentManualDraftRestoresWithoutStartingRecording()=manualDraft("Save current place")
    @Test fun pastManualDraftRestoresItsMode()=manualDraft("Add past visit")
    @Test fun timelineManualDraftRemainsOpenAfterRestoration()=manualDraft("Add past visit",true)

    @Test fun restoredExistingEditRetainsItsOriginalConcurrentChangeGuard()=runBlocking {
        val now=Instant.ofEpochMilli(System.currentTimeMillis())
        val original=Visit("restore-existing",Coordinate(48.8584,2.2945),now.minusSeconds(3600),now.minusSeconds(1800),name="Original QA visit")
        graph.history.dao.put(VisitRow(original))
        val tester=screen(true);awaitText("Original QA visit")
        compose.onNodeWithText("Original QA visit").performClick()
        compose.onNodeWithText("Name").performTextReplacement("My unsaved edit")
        val newer=original.copy(name="Changed elsewhere",notes="Do not overwrite")
        graph.history.dao.put(VisitRow(newer))
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Name").assertTextContains("My unsaved edit")
        compose.onNodeWithText("Save",substring=false).performClick()
        awaitText(VisitChanged().message!!)
        assertEquals(newer,graph.history.dao.visit(original.id)!!.value)
        compose.onNodeWithText("Name").performScrollTo().assertTextContains("My unsaved edit")
        Unit
    }

    @Test fun linkedOutingVisitEditorRemainsOpenWithItsDraft()=runBlocking {
        val now=Instant.ofEpochMilli(System.currentTimeMillis())
        val session=RecordingSession("restore-outing",now.minusSeconds(3600),now.minusSeconds(60))
        val visit=Visit("linked",Coordinate(48.8584,2.2945),now.minusSeconds(3000),now.minusSeconds(2000),name="Linked QA visit")
        graph.history.dao.put(SessionRow(session));graph.history.dao.put(VisitRow(visit))
        val tester=StateRestorationTester(compose)
        tester.setContent {MaterialTheme {OutingDetailScreen(graph,session.id,true,{},{})}}
        awaitText("Linked QA visit")
        compose.onNodeWithText("Open visit").performScrollTo().performClick()
        compose.onNodeWithText("Name").performTextReplacement("Linked unsaved edit")
        tester.emulateSavedInstanceStateRestore()
        awaitText("Edit visit")
        compose.onNodeWithText("Name").assertTextContains("Linked unsaved edit")
        assertEquals(visit,graph.history.dao.visit(visit.id)!!.value)
    }

    @Test fun mapSelectedVisitRestoresItsUnsavedDraft()=runBlocking {
        val now=Instant.ofEpochMilli(System.currentTimeMillis())
        val visit=Visit("map-selected",Coordinate(48.8584,2.2945),now.minusSeconds(600),now.minusSeconds(300),name="Map QA visit")
        graph.history.dao.put(VisitRow(visit))
        val tester=screen()
        compose.waitUntil(10000) {compose.onAllNodesWithTag("journal-native-map").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("journal-native-map").performMapAction("Visits on map")
        compose.onNodeWithText("Open visit").performClick()
        // The map opens the visit's quick view first; Details opens the full editor.
        compose.waitUntil(10000) {compose.onAllNodesWithTag("map-visit-details").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("map-visit-details").performClick()
        compose.onNodeWithText("Name").performTextReplacement("Map unsaved edit")
        tester.emulateSavedInstanceStateRestore()
        compose.onNodeWithText("Name").assertTextContains("Map unsaved edit")
        assertEquals(visit,graph.history.dao.visit(visit.id)!!.value)
    }

    @Test fun baselineBundlePreservesEveryVisitFieldAndRejectsUnknownVersions() {
        val at=Instant.parse("2026-10-29T12:00:00.123456789Z")
        val visits=listOf(Visit("minimal",Coordinate(0.0,180.0),at),Visit(
            "full",Coordinate(48.8584,2.2945),at,at.plusSeconds(100),"Name","Address","Notes",VisitSource.imported,Confirmation.corrected,
            Coordinate(1.0,2.0),"Original","Original address",Coordinate(3.0,4.0),"Detected","Detected address",at.plusSeconds(1),at.plusSeconds(2),"userEntered",12.5,"FR","Europe/Paris",true,at.plusSeconds(3),
        ))
        val scope=object:SaverScope {override fun canBeSaved(value:Any)=true}
        for(visit in visits) {
            val saved=with(VisitEditorVisitSaver) {scope.save(visit)}!!
            val parcel=Parcel.obtain()
            val restored=try {parcel.writeBundle(saved);parcel.setDataPosition(0);parcel.readBundle(javaClass.classLoader)!!} finally {parcel.recycle()}
            assertEquals(visit,VisitEditorVisitSaver.restore(restored))
            restored.putInt("version",2);assertNull(VisitEditorVisitSaver.restore(restored))
        }
        assertNull(VisitEditorVisitSaver.restore(Bundle()))
    }
}
