package com.bontecou.whereday

import androidx.compose.runtime.*
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import java.time.Duration
import java.time.LocalDate
import java.time.ZoneId
import org.junit.*
import org.junit.Assert.*

class PlaceDetailTest {
    @get:Rule val compose=createComposeRule()
    @Before fun guard() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    private val zone=ZoneId.systemDefault()
    // Monday, November 2, 2026.
    private val monday=LocalDate.of(2026,11,2)
    private fun at(day:LocalDate,hour:Int)=day.atTime(hour,0).atZone(zone).toInstant()
    private val now=at(monday.plusDays(5),12)
    private val cafe=SavedPlace("cafe","Cafe Luna",Coordinate(40.70,-74.00),"1 Main St",100.0,SavedPlaceKind.cafe)
    private val studio=SavedPlace("studio","Studio",Coordinate(40.7005,-74.00),radius=150.0)
    private val visits=(0L until 4L).map {Visit("v$it",cafe.coordinate,at(monday.plusDays(it),18),at(monday.plusDays(it),20),name="Cafe Luna")} +
        Visit("s",studio.coordinate,at(monday,9),at(monday,10),name="Studio",placeSource="detected")

    private class Recorder {
        val saves=mutableListOf<Pair<SavedPlace,SavedPlace>>();val merges=mutableListOf<SavedPlaceMergePlan>();var edits=0;var backs=0;val opened=mutableListOf<String>()
    }

    private fun show(places:MutableState<List<SavedPlace>>,record:Recorder,selected:String="cafe") {
        val actions=PlaceDetailActions({change,expected->record.saves+=change to expected;places.value=places.value.map {if(it.id==change.id) change else it}},
            {kept,removed->SavedPlaceMergePlan.build(kept,removed,places.value,visits)}) {plan,kept,removed->record.merges+=plan;places.value=places.value.filter {it.id!=removed.id}.map {if(it.id==kept.id) SavedPlaceMergePlan.merged(plan,kept,removed) else it}}
        compose.setContent {WheredayTheme(false) {
            PlaceDetailContent(places.value.firstOrNull {it.id==selected},places.value,visits,now,zone,true,actions,{record.backs++},{record.edits++},openVisit={record.opened+=it.id})
        }}
    }

    @Test fun detailShowsKindStatsRhythmAndRecentVisits() {
        val record=Recorder();show(mutableStateOf(listOf(cafe,studio)),record)
        compose.onNodeWithTag("place-detail-kicker").assertTextEquals("CAFÉ · SAVED PLACE")
        compose.onNodeWithTag("place-detail-name").assertTextEquals("Cafe Luna")
        compose.onNodeWithTag("place-detail-map").assertContentDescriptionEquals("Map around Cafe Luna")
            .assert(SemanticsMatcher.expectValue(SemanticsProperties.StateDescription,"Match radius: 100 meters"))
        compose.onNode(hasText("4") and hasAnyAncestor(hasTestTag("place-detail-stats"))).assertExists()
        compose.onNode(hasText("2h") and hasAnyAncestor(hasTestTag("place-detail-stats"))).assertExists()
        compose.onNode(hasText("8h") and hasAnyAncestor(hasTestTag("place-detail-stats"))).assertExists()
        compose.onNodeWithTag("place-detail-rhythm-summary").assertTextEquals("mostly weekday evenings")
        compose.onNodeWithTag("place-detail-rhythm").assertContentDescriptionEquals("Visit rhythm by weekday and hour")
        compose.onAllNodesWithTag("place-detail-recent").assertCountEquals(4)[0].performScrollTo().performClick()
        compose.onNodeWithTag("place-detail-edit").performClick();compose.onNodeWithTag("place-detail-back").performClick()
        compose.runOnIdle {assertEquals(listOf("v3"),record.opened);assertEquals(1,record.edits);assertEquals(1,record.backs)}
    }

    @Test fun inlineRenameAndNoteSaveAgainstTheShownPlace() {
        val places=mutableStateOf(listOf(cafe,studio));val record=Recorder();show(places,record)
        compose.onNodeWithTag("place-detail-rename").performScrollTo().performClick()
        compose.onNodeWithTag("place-detail-rename-field").performTextReplacement("  Luna  ")
        compose.onNodeWithTag("place-detail-rename-save").performClick()
        compose.onNodeWithTag("place-detail-name").assertTextEquals("Luna")
        compose.onNodeWithTag("place-detail-note-action").performScrollTo().assertTextEquals("Add note").performClick()
        compose.onNodeWithTag("place-detail-note-field").performTextInput("Window seat")
        compose.onNodeWithTag("place-detail-note-save").performScrollTo().performClick()
        compose.onNode(hasText("Window seat") and hasAnyAncestor(hasTestTag("place-detail-note")),useUnmergedTree=true).assertExists()
        compose.onNodeWithTag("place-detail-note-action").performScrollTo().assertTextEquals("Edit note")
        compose.runOnIdle {
            assertEquals(listOf(cafe.copy(name="Luna") to cafe,cafe.copy(name="Luna",notes="Window seat") to cafe.copy(name="Luna")),record.saves)
        }
    }

    @Test fun mergeConfirmsWhatChangesBeforeFoldingThePickedPlace() {
        val places=mutableStateOf(listOf(cafe,studio));val record=Recorder();show(places,record)
        compose.onNodeWithTag("place-detail-merge").performScrollTo().performClick()
        compose.onAllNodesWithTag("place-merge-candidate").assertCountEquals(1)[0].assertTextContains("Studio",substring=true).assertTextContains("1 visit",substring=true).performClick()
        compose.onNodeWithTag("place-merge-message").assertTextContains("Studio is removed and its 1 visit becomes Cafe Luna.",substring=true)
            .assertTextContains("The match radius grows",substring=true).assertTextContains("This can't be undone.",substring=true)
        compose.runOnIdle {assertTrue(record.merges.isEmpty())}
        compose.onNodeWithTag("place-merge-confirm").performClick()
        compose.onNodeWithTag("place-merge-picker").assertDoesNotExist()
        compose.onNodeWithTag("place-detail-merge").performScrollTo().assertIsNotEnabled()
        compose.runOnIdle {assertEquals(listOf("cafe"),places.value.map {it.id});assertEquals(setOf("s"),record.merges.single().movedVisitIds)}
    }

    @Test fun aRemovedPlaceShowsItsRemovalWithoutActions() {
        val record=Recorder();show(mutableStateOf(listOf(studio)),record,"cafe")
        compose.onNodeWithTag("place-detail-removed").assertExists()
        compose.onNodeWithTag("place-detail-rename").assertDoesNotExist()
    }

    @Test fun savedHomeAnchorsTodayAndReplacesTheSaveHomeLink() {
        val visit=Visit(coordinate=cafe.coordinate,arrivedAt=at(monday,9),name="Cafe Luna")
        val today=at(monday,15)
        fun inputs(home:SavedPlace?,visits:List<Visit>)=TodayInputs(today,zone,TodayDay.build(visits,emptyList(),today,zone,{it.name ?: "Visit"},home),0.0,0,null,TodayListening.full,null,null,home!=null)
        var current by mutableStateOf(inputs(null,emptyList()))
        var saves=0
        compose.setContent {WheredayTheme(false) {TodayContent(current,true,{},{},{},{},{},{},{},onSaveHome={saves++})}}
        compose.onNodeWithTag("today-save-home").assertTextContains("Save Home so days start there",substring=true).performScrollTo().performClick()
        compose.runOnIdle {assertEquals(1,saves)}
        val home=SavedPlace("home","Home",Coordinate(40.80,-74.00),kind=SavedPlaceKind.home)
        compose.runOnIdle {current=inputs(home,emptyList())}
        compose.onNodeWithTag("today-save-home").assertDoesNotExist();compose.onNodeWithTag("today-import").assertExists()
        compose.runOnIdle {current=inputs(home,listOf(visit))}
        compose.onNodeWithTag("today-away").assertTextContains("AWAY FROM HOME",substring=true).assertTextContains("6h 00m",substring=true)
    }
}
