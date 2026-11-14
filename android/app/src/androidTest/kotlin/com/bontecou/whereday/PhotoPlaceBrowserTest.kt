package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.*
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.platform.AppGraph
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.Instant

@OptIn(ExperimentalTestApi::class,kotlinx.coroutines.ExperimentalCoroutinesApi::class)
@RunWith(AndroidJUnit4::class)
abstract class PhotoPlaceBrowserFixture {
    private val effects=kotlinx.coroutines.test.StandardTestDispatcher()
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>(effectContext=effects)
    // Compose 1.8.3's idle check considers recomposition/frame awaiters, but not ready
    // StandardTestDispatcher work. Flush only ready work on UI; never advance virtual time.
    private val readyEffects=object:IdlingResource {
        override val isIdleNow:Boolean get() {
            compose.runOnUiThread {effects.scheduler.runCurrent()}
            return true
        }
    }
    protected lateinit var graph:AppGraph
    private var originalMarkers=false
    private var originalImages=true
    @Before fun setup() {runBlocking {
        // Both prepared pages and production marker dialogs publish results after real IO.
        compose.registerIdlingResource(readyEffects);compose.mainClock.advanceTimeByFrame()
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.mapqa")
        graph=AppGraph.from(context)
        val settings=graph.preferences.settings.first()
        originalMarkers=settings.showPhotoMarkers;originalImages=settings.showPhotoImages
        graph.photoPlaces.clearCache {withContext(Dispatchers.IO) {graph.database.clearAllTables()}}
    }}
    @After fun cleanup() {runBlocking {
        try {
            if(::graph.isInitialized) {
                val clearing=async(Dispatchers.IO) {
                    graph.preferences.update {it.copy(showPhotoMarkers=originalMarkers,showPhotoImages=originalImages)}
                    graph.photoPlaces.clearCache {graph.database.clearAllTables()}
                }
                // Failed assertions can leave an active directory whose cancellation
                // must run on this fixture's queued UI effects before clear can join it.
                compose.waitUntil(15_000) {
                    compose.runOnUiThread {effects.scheduler.runCurrent()}
                    clearing.isCompleted
                }
                clearing.await()
            }
        } finally {compose.unregisterIdlingResource(readyEffects)}
    }}
    protected suspend fun seed(count:Int,distinct:Boolean) {
        graph.database.writeTransaction {
            repeat(count) {index->
                val uri="content://synthetic/directory-${index.toString().padStart(5,'0')}"
                val time=Instant.parse("2026-10-28T12:00:00Z").plusSeconds(index*60L)
                val coordinate=Coordinate(18.0,-66.0+if(distinct) index*.01 else 0.0)
                graph.database.history().put(PhotoAssetRow(uri,time,coordinate,true,true,Instant.EPOCH,null))
                graph.database.history().put(PhotoRow(uri,time,coordinate,"photo_gps",Instant.EPOCH))
            }
        }
    }
    protected suspend fun seedAreaHistory() {
        graph.database.writeTransaction {
            repeat(1001) {index->
                val coordinate=if(index<120) Coordinate(18.0,-66.0+index*.0005) else (index-120).let {i->Coordinate(-70.0+(i/40)*6.0,-175.0+(i%40)*8.0)}
                val uri="content://synthetic/area-${index.toString().padStart(5,'0')}";val time=Instant.EPOCH.plusSeconds(index*60L)
                graph.database.history().put(PhotoAssetRow(uri,time,coordinate,true,true,Instant.EPOCH,null))
                graph.database.history().put(PhotoRow(uri,time,coordinate,"photo_gps",Instant.EPOCH))
            }
        }
    }
}

/** Prepared pages retain real IO and use queued UI effects with ready-work synchronization. */
class PhotoPlaceBrowserTest:PhotoPlaceBrowserFixture() {
    @Test fun allPlacesDirectoryKeepsItsPageWhenReturningFromAPlaceOutsideFirstPage() {runBlocking {
        seed(52,true)
        val directory=graph.photoPlaces.prepare(HistoryRange.all);val counts=directory.counts()
        val open=mutableStateOf(true)
        compose.setContent {MaterialTheme {if(open.value) PhotoPlaceBrowser(graph,directory,counts,{open.value=false}) {_,modifier,_->Box(modifier)}}}
        compose.waitUntil(5000) {compose.onAllNodesWithText("52 places · 52 photos").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Next page").performClick()
        compose.onNodeWithText("Page 2").assertIsDisplayed()
        compose.waitUntil(5000) {compose.onAllNodesWithText("Open this place").fetchSemanticsNodes().size==2}
        compose.onAllNodesWithText("Open this place").onFirst().performClick()
        compose.onNodeWithText("Photos Here").assertIsDisplayed()
        compose.onNodeWithText("Back to photo places").performClick()
        compose.onNodeWithText("Page 2").assertIsDisplayed()
        compose.onNodeWithText("Previous page").performClick()
        compose.onNodeWithText("Page 1").assertIsDisplayed()
        compose.onNodeWithText("Close photo places").performClick()
        compose.runOnIdle {assertFalse(open.value)}
        directory.close()
    }}
    @Test fun placeCollectionAndViewerCrossPageBoundaryAndCloseOnInvalidation() {runBlocking {
        seed(52,false)
        val directory=graph.photoPlaces.prepare(HistoryRange.all);val counts=directory.counts()
        val open=mutableStateOf(true)
        compose.setContent {MaterialTheme {if(open.value) PhotoPlaceBrowser(graph,directory,counts,{open.value=false}) {photo,modifier,_->Box(modifier.testTag(photo.uri))}}}
        compose.waitUntil(5000) {compose.onAllNodesWithText("Open this place").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Open this place").performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("Open photo").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Next page").performClick()
        compose.onNodeWithText("Page 2").assertIsDisplayed()
        compose.waitUntil(5000) {compose.onAllNodesWithText("Open photo").fetchSemanticsNodes().size==2}
        compose.onAllNodesWithText("Open photo").onFirst().performClick()
        compose.waitUntil(5000) {compose.onAllNodesWithText("Photo 51 of 52").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Next photo").performClick()
        compose.onNodeWithText("Photo 52 of 52").assertIsDisplayed()
        compose.onNodeWithText("Next photo").assertIsNotEnabled()
        compose.onNodeWithText("Previous photo").performClick()
        // The queued effect context finishes the pager animation on subsequent clock frames.
        compose.waitUntil(5000) {compose.onAllNodesWithText("Photo 51 of 52").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Photo 51 of 52").assertIsDisplayed()
        compose.onNodeWithText("Close photo").performClick()
        compose.onNodeWithText("Page 2").assertIsDisplayed()
        directory.close()
        compose.waitUntil(5000) {!open.value}
        compose.onNodeWithText("All Photo Places").assertDoesNotExist()
    }}
    @Test fun darkLargeFontEmptyDirectoryKeepsCloseAndPageControlsVisible() {runBlocking {
        val directory=graph.photoPlaces.prepare(HistoryRange.all);val counts=directory.counts()
        val open=mutableStateOf(true)
        compose.setContent {
            val density=androidx.compose.ui.platform.LocalDensity.current
            CompositionLocalProvider(androidx.compose.ui.platform.LocalDensity provides androidx.compose.ui.unit.Density(density.density,1.8f)) {
                MaterialTheme(colorScheme=androidx.compose.material3.darkColorScheme()) {
                    if(open.value) PhotoPlaceBrowser(graph,directory,counts,{open.value=false}) {_,modifier,_->Box(modifier)}
                }
            }
        }
        compose.waitUntil(5000) {compose.onAllNodesWithText("No matched photo places in this date range.").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Close photo places").assertIsDisplayed()
        compose.onNodeWithText("Page 1").assertIsDisplayed()
        compose.onNodeWithText("Previous page").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Next page").assertIsDisplayed().assertIsNotEnabled()
        compose.onNodeWithText("Close photo places").performClick()
        compose.runOnIdle {assertFalse(open.value)}
        directory.close()
    }}
    @Test fun selectedAreaDirectoryPaginatesCompleteMembersAndPreservesItsScopeOnReturn() {runBlocking {
        seedAreaHistory()
        val directory=graph.photoPlaces.prepare(HistoryRange.all)
        val area=directory.markers().maxBy {it.placeCount}
        assertTrue(area.isArea);assertTrue(area.placeCount>50);assertTrue(area.placeCount<1001)
        val counts=PhotoPlaceCounts(area.photoCount,area.placeCount,1)
        val open=mutableStateOf(true)
        compose.setContent {MaterialTheme {if(open.value) PhotoPlaceBrowser(graph,directory,counts,{open.value=false},areas=setOf(area.id),title="Photos in This Area") {_,modifier,_->Box(modifier)}}}
        compose.waitUntil(5000) {compose.onAllNodesWithText("${area.placeCount} places · ${area.photoCount} photos").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Photos in This Area").assertIsDisplayed()
        compose.onNodeWithText("Next page").performClick()
        compose.onNodeWithText("Page 2").assertIsDisplayed()
        compose.waitUntil(5000) {compose.onAllNodesWithText("Open this place").fetchSemanticsNodes().isNotEmpty()}
        compose.onAllNodesWithText("Open this place").onFirst().performClick()
        compose.onNodeWithText("Photos Here").assertIsDisplayed()
        compose.onNodeWithText("Back to photo places").performClick()
        compose.onNodeWithText("Photos in This Area").assertIsDisplayed()
        compose.onNodeWithText("Page 2").assertIsDisplayed()
        compose.onNodeWithText("${area.placeCount} places · ${area.photoCount} photos").performScrollTo().assertIsDisplayed()
        directory.close()
    }}
}

/** IO-driven production dialogs require queued effects; keep this context scoped to that seam. */
class PhotoMapLayerBrowserTest:PhotoPlaceBrowserFixture() {
    @Test fun liveClockChangesPreserveAnOpenBrowserAndApplyAfterClose()=runBlocking {
        seed(3,false)
        graph.preferences.update {it.copy(showPhotoMarkers=true,showPhotoImages=false)}
        val at=Instant.parse("2026-10-28T12:00:00Z")
        val live=mutableStateOf(HistoryRange(at,at.plusSeconds(61)))
        val selection=mutableStateOf("Today")
        val eligible=mutableStateOf(true)
        var layer:PhotoMapLayer?=null
        compose.setContent {MaterialTheme {
            val browsing=rememberPhotoBrowserRange(selection.value,live.value)
            val current=photoMapLayer(graph,browsing.range,eligible.value,onBrowserRange=browsing.hold) {_,modifier,_->Box(modifier)}
            SideEffect {layer=current}
        }}
        compose.waitUntil(10000) {layer?.visibleClusters?.singleOrNull()?.photoCount==2L}
        compose.runOnIdle {layer!!.browsePlaces!!.invoke()}
        compose.onNodeWithText("All Photo Places").assertIsDisplayed()
        compose.runOnIdle {live.value=HistoryRange(at,at.plusSeconds(121))}
        compose.waitForIdle()
        compose.onNodeWithText("All Photo Places").assertIsDisplayed()
        assertEquals(2L,compose.runOnIdle {layer!!.visibleClusters.single().photoCount})
        compose.onNodeWithText("Close photo places").performClick()
        compose.waitUntil(10000) {layer?.visibleClusters?.singleOrNull()?.photoCount==3L}
        compose.runOnIdle {layer!!.browsePlaces!!.invoke()}
        compose.onNodeWithText("All Photo Places").assertIsDisplayed()
        compose.runOnIdle {eligible.value=false}
        compose.waitUntil(10000) {layer?.visibleClusters?.isEmpty()==true}
        compose.onNodeWithText("All Photo Places").assertDoesNotExist()
        compose.runOnIdle {eligible.value=true}
        compose.waitUntil(10000) {layer?.visibleClusters?.singleOrNull()?.photoCount==3L}
        compose.runOnIdle {layer!!.browsePlaces!!.invoke()}
        compose.onNodeWithText("All Photo Places").assertIsDisplayed()
        compose.runOnIdle {selection.value="Custom";live.value=HistoryRange(at,at.plusSeconds(1))}
        compose.waitUntil(10000) {layer?.visibleClusters?.singleOrNull()?.photoCount==1L}
        compose.onNodeWithText("All Photo Places").assertDoesNotExist()
    }

    @Test fun productionLayerRepresentsEveryPhotoAndRoutesAnAreaToItsOwnMemberDirectory() {runBlocking {
        seedAreaHistory()
        graph.preferences.update {it.copy(showPhotoMarkers=true,showPhotoImages=false)}
        var layer:PhotoMapLayer?=null
        compose.setContent {MaterialTheme {
            val current=photoMapLayer(graph,HistoryRange.all,true) {_,modifier,_->Box(modifier)}
            SideEffect {layer=current}
        }}
        compose.waitUntil(10000) {layer?.visibleClusters?.sumOf {it.photoCount}==1001L}
        val markers=compose.runOnIdle {layer!!.visibleClusters}
        assertTrue(markers.size<=500);assertEquals(1001L,markers.sumOf {it.placeCount})
        val area=markers.maxBy {it.placeCount};assertTrue(area.isArea)
        compose.runOnIdle {layer!!.open(listOf(area))}
        compose.waitUntil(5000) {compose.onAllNodesWithText("Photos in This Area").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("${area.placeCount} places · ${area.photoCount} photos").performScrollTo().assertIsDisplayed()
        compose.onNodeWithText("Close photo places").performClick()
        compose.runOnIdle {layer!!.browsePlaces!!.invoke()}
        compose.waitUntil(5000) {compose.onAllNodesWithText("1001 places · 1001 photos").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("All Photo Places").assertIsDisplayed()
        graph.preferences.update {it.copy(showPhotoMarkers=false)}
        compose.waitUntil(5000) {layer?.visibleClusters?.isEmpty()==true}
        compose.onNodeWithText("All Photo Places").assertDoesNotExist()
    }}
    @Test fun repeatedProductionPlaceDialogsKeepTheirCollectionAndCloseCleanly() {runBlocking {
        seed(52,false)
        graph.preferences.update {it.copy(showPhotoMarkers=true,showPhotoImages=false)}
        var layer:PhotoMapLayer?=null
        compose.setContent {MaterialTheme {
            val current=photoMapLayer(graph,HistoryRange.all,true) {_,modifier,_->Box(modifier)}
            SideEffect {layer=current}
        }}
        compose.waitUntil(10000) {layer?.visibleClusters?.singleOrNull()?.photoCount==52L}
        val marker=compose.runOnIdle {layer!!.visibleClusters.single()}
        repeat(30) {
            compose.runOnIdle {layer!!.open(listOf(marker))}
            compose.waitUntil(5000) {compose.onAllNodesWithText("Photos Here").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Photos Here").assertIsDisplayed()
            compose.onNodeWithText("Back to photo places").assertDoesNotExist()
            compose.onNodeWithText("Close photo places").performClick()
            compose.waitUntil(5000) {compose.onAllNodesWithText("Photos Here").fetchSemanticsNodes().isEmpty()}
            assertEquals(52L,compose.runOnIdle {layer!!.visibleClusters.single().photoCount})
        }
    }}
    @Test fun productionPlaceMarkerOpensItsWholeCollectionAndEligibilityLossDismissesIt() {runBlocking {
        seed(52,false)
        graph.preferences.update {it.copy(showPhotoMarkers=true,showPhotoImages=false)}
        val eligible=mutableStateOf(true)
        var layer:PhotoMapLayer?=null
        compose.setContent {MaterialTheme {
            val current=photoMapLayer(graph,HistoryRange.all,eligible.value) {_,modifier,_->Box(modifier)}
            SideEffect {layer=current}
        }}
        compose.waitUntil(10000) {layer?.visibleClusters?.singleOrNull()?.photoCount==52L}
        val marker=compose.runOnIdle {layer!!.visibleClusters.single()};assertFalse(marker.isArea)
        compose.runOnIdle {layer!!.open(listOf(marker))}
        compose.waitUntil(5000) {compose.onAllNodesWithText("Photos Here").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithText("Back to photo places").assertDoesNotExist()
        compose.runOnIdle {eligible.value=false}
        compose.waitUntil(5000) {layer?.visibleClusters?.isEmpty()==true}
        compose.onNodeWithText("Photos Here").assertDoesNotExist()
        compose.runOnIdle {eligible.value=true}
        compose.waitUntil(10000) {layer?.visibleClusters?.singleOrNull()?.photoCount==52L}
        val replacement=compose.runOnIdle {layer!!.visibleClusters.single()}
        assertNotEquals(marker.id,replacement.id)
        assertEquals(marker.areaId,replacement.areaId)
        compose.runOnIdle {layer!!.open(listOf(marker))}
        compose.waitForIdle()
        compose.onNodeWithText("Photos Here").assertDoesNotExist()
    }}
    @Test fun screenEligibilityAndMetadataChangesReplaceAndReleaseTheOwnedDirectory() {runBlocking {
        seed(1001,false)
        val eligible=mutableStateOf(true)
        var visible:PhotoDirectoryUi?=null
        compose.setContent {MaterialTheme {
            val state=photoDirectory(graph,HistoryRange.all,eligible.value)
            SideEffect {visible=state}
            state.counts?.let {androidx.compose.material3.Text("Directory has ${it.photos} photos")}
        }}
        compose.waitUntil(10000) {compose.onAllNodesWithText("Directory has 1001 photos").fetchSemanticsNodes().isNotEmpty()}
        val first=compose.runOnIdle {visible!!.directory!!}
        graph.photos.unavailable("content://synthetic/directory-01000")
        compose.waitUntil(10000) {compose.onAllNodesWithText("Directory has 1000 photos").fetchSemanticsNodes().isNotEmpty()}
        assertFalse(first.active.value)
        val second=compose.runOnIdle {visible!!.directory!!}
        compose.runOnIdle {eligible.value=false}
        compose.waitUntil(5000) {!second.active.value}
        compose.onNodeWithText("Directory has 1000 photos").assertDoesNotExist()
        compose.runOnIdle {eligible.value=true}
        compose.waitUntil(10000) {compose.onAllNodesWithText("Directory has 1000 photos").fetchSemanticsNodes().isNotEmpty()}
    }}
}
