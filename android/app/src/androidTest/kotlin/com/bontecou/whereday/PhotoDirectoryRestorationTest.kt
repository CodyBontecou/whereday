package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.StateRestorationTester
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/** Real immutable directories and saved state; image/access eligibility is synthetic. */
class PhotoDirectoryRestorationTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private lateinit var graph:AppGraph
    private val at=Instant.parse("2026-11-02T12:00:00Z")
    private val range=HistoryRange(at,at.plusSeconds(4000))
    private fun uri(i:Int)="content://synthetic/directory-restore-${i.toString().padStart(3,'0')}"
    @Before fun setup():Unit=runBlocking {
        val context=InstrumentationRegistry.getInstrumentation().targetContext;check(context.packageName=="com.bontecou.whereday.mapqa")
        graph=AppGraph.from(context);graph.journalClear.clear().join()
        graph.preferences.update {it.copy(showPhotoMarkers=true,showPhotoImages=false)}
    }
    @After fun cleanup():Unit=runBlocking {graph.journalClear.clear().join()}
    private suspend fun seed(count:Int,distinct:Boolean) {
        graph.database.writeTransaction {repeat(count) {i->
            val coordinate=Coordinate(18.0,-66.0+if(distinct) i*.01 else 0.0)
            graph.history.dao.put(PhotoAssetRow(uri(i),at.plusSeconds(i.toLong()),coordinate,true,true,at))
            graph.history.dao.put(PhotoRow(uri(i),at.plusSeconds(i.toLong()),coordinate,"photo_gps",at))
        }}
    }
    private fun awaitText(text:String)=compose.waitUntil(15000) {runCatching {compose.onNodeWithText(text).assertExists()}.isSuccess}
    @Test fun directoryPageAndPlaceBackNavigationRestoreIntoANewEqualDirectory():Unit=runBlocking {
        seed(52,true)
        val original=graph.photoPlaces.prepare(range);val counts=original.counts();var replacement:PhotoPlaceDirectory?=null
        val restoration=StateRestorationTester(compose)
        restoration.setContent {MaterialTheme {PhotoPlaceBrowser(graph,replacement ?: original,counts,{}) {_,modifier,_->Box(modifier)}}}
        awaitText("Page 1");compose.onNodeWithTag("photo-places-next").performClick();awaitText("Page 2")
        replacement=graph.photoPlaces.prepare(range);assertNotSame(original,replacement);assertEquals(original.identity,replacement!!.identity)
        restoration.emulateSavedInstanceStateRestore();awaitText("Page 2")
        compose.waitUntil(5000) {compose.onAllNodesWithTag("photo-place-card").fetchSemanticsNodes().size==2}
        compose.onAllNodesWithTag("photo-place-card").onFirst().performClick();awaitText("Photos Here")
        compose.onNodeWithTag("photo-places-close").performClick();awaitText("Page 2")
        compose.onNodeWithTag("photo-places-previous").performClick();awaitText("Page 1")
        assertEquals(52L,graph.history.dao.photoAssetCount())
    }
    @Test fun collectionPageAndCurrentlyViewedUriRestoreTogether():Unit=runBlocking {
        seed(52,false)
        val original=graph.photoPlaces.prepare(range);val counts=original.counts();var replacement:PhotoPlaceDirectory?=null
        val initial=original.places().single()
        val restoration=StateRestorationTester(compose)
        restoration.setContent {MaterialTheme {PhotoPlaceBrowser(graph,replacement ?: original,counts,{},initialPlace=initial) {photo,modifier,_->Box(modifier.testTag(photo.uri))}}}
        awaitText("Page 1");compose.onNodeWithTag("photo-places-next").performClick();awaitText("Page 2")
        compose.waitUntil(5000) {compose.onAllNodesWithText("Open photo").fetchSemanticsNodes().size==2}
        compose.onAllNodesWithText("Open photo").onFirst().performClick();awaitText("Photo 51 of 52")
        compose.onNodeWithTag("photo-pager-next").performScrollTo().performClick();awaitText("Photo 52 of 52")
        replacement=graph.photoPlaces.prepare(range)
        restoration.emulateSavedInstanceStateRestore();awaitText("Photo 52 of 52")
        compose.onNode(hasTestTag(uri(51)) and hasAnyAncestor(hasTestTag("photo-pager-content"))).assertExists()
        compose.onNodeWithTag("photo-pager-close").performScrollTo().performClick();awaitText("Page 2")
        compose.onNodeWithTag("photo-places-previous").performClick();awaitText("Page 1")
        assertEquals(52L,graph.history.dao.photoAssetCount())
    }
    @Test fun changedDirectoryCannotRestoreAPlaceJustBecauseItsNumericIdWasReused():Unit=runBlocking {
        seed(1,false)
        val original=graph.photoPlaces.prepare(range);var replacement:PhotoPlaceDirectory?=null;val open=mutableStateOf(true)
        val counts=original.counts();val initial=original.places().single()
        val restoration=StateRestorationTester(compose)
        restoration.setContent {MaterialTheme {if(open.value) PhotoPlaceBrowser(graph,replacement ?: original,counts,{open.value=false},initialPlace=initial) {photo,modifier,_->Box(modifier.testTag(photo.uri))}}}
        awaitText("Photos Here")
        graph.photos.remove(uri(0))
        graph.history.dao.put(PhotoAssetRow("content://synthetic/replacement",at,Coordinate(18.0,-66.0),true,true,at))
        graph.history.dao.put(PhotoRow("content://synthetic/replacement",at,Coordinate(18.0,-66.0),"photo_gps",at))
        replacement=graph.photoPlaces.prepare(range);assertEquals(initial.id,replacement!!.places().single().id);assertNotEquals(original.identity,replacement!!.identity)
        restoration.emulateSavedInstanceStateRestore();compose.waitUntil(10000) {!open.value}
        compose.onNodeWithTag("photo-place-browser-content").assertDoesNotExist()
        assertEquals(1L,graph.history.dao.photoAssetCount())
    }
    @Test fun mapOpeningAndHeldRangeRestoreAfterLiveClockAdvanceAndFailureDoesNotResurrectIt():Unit=runBlocking {
        seed(52,true)
        var live by mutableStateOf(range);var failure by mutableStateOf(false)
        val layer=AtomicReference<PhotoMapLayer>();val held=AtomicReference<HistoryRange>()
        val restoration=StateRestorationTester(compose)
        restoration.setContent {MaterialTheme {
            val browsing=rememberPhotoBrowserRange("today",live)
            val value=photoMapLayer(graph,browsing.range,true,onBrowserRange=browsing.hold,validationFailed=failure) {_,modifier,_->Box(modifier)}
            SideEffect {layer.set(value);held.set(browsing.range)}
            TextButton(enabled=value.browsePlaces!=null,onClick={value.browsePlaces?.invoke()}) {Text("Browse test places")}
        }}
        compose.waitUntil(15000) {layer.get()?.browsePlaces!=null};compose.onNodeWithText("Browse test places").performClick()
        awaitText("Page 1");compose.onNodeWithTag("photo-places-next").performClick();awaitText("Page 2")
        val oldMarker=layer.get().clusters.first().id
        compose.runOnIdle {live=HistoryRange(range.from,range.until.plusSeconds(3600))};assertEquals(range,held.get())
        restoration.emulateSavedInstanceStateRestore();awaitText("Page 2")
        assertEquals(range,held.get());assertNotEquals(oldMarker,layer.get().clusters.first().id)
        compose.runOnIdle {failure=true};compose.waitUntil(10000) {held.get()==live}
        compose.onNodeWithTag("photo-place-browser-content").assertDoesNotExist()
        compose.runOnIdle {failure=false};compose.waitUntil(10000) {layer.get()?.browsePlaces!=null}
        compose.onNodeWithTag("photo-place-browser-content").assertDoesNotExist()
    }
}
