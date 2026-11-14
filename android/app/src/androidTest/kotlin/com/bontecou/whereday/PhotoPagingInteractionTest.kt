package com.bontecou.whereday

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.paging.*
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.data.PhotoRow
import com.bontecou.whereday.domain.Coordinate
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

/** No journal/AppGraph or real photo access. Exercises the same paged strip and viewer as outings. */
@RunWith(AndroidJUnit4::class)
class PhotoPagingInteractionTest {
    @get:Rule val compose=createAndroidComposeRule<ComponentActivity>()
    private val loads=CopyOnWriteArrayList<Int>()
    private fun expectedPhotoTime(time:Instant)=java.time.format.DateTimeFormatter.ofLocalizedDateTime(java.time.format.FormatStyle.MEDIUM,java.time.format.FormatStyle.SHORT).withLocale(compose.activity.resources.configuration.locales[0]).withZone(java.time.ZoneId.systemDefault()).format(time)
    private fun row(index:Int)=PhotoRow("content://synthetic/paged-$index",Instant.parse("2026-10-27T14:00:00Z").plusSeconds(index*60L),Coordinate(48.8584,2.2945),"photo_gps",Instant.EPOCH)
    private inner class Source:PagingSource<Int,PhotoRow>() {
        override val jumpingSupported=true
        override fun getRefreshKey(state:PagingState<Int,PhotoRow>)=state.anchorPosition
        override suspend fun load(params:LoadParams<Int>):LoadResult<Int,PhotoRow> {
            val start=(params.key ?: 0).coerceIn(0,1000)
            val end=(start+params.loadSize).coerceAtMost(1001)
            loads+=end-start
            return LoadResult.Page((start until end).map(::row),if(start==0) null else (start-params.loadSize).coerceAtLeast(0),if(end==1001) null else end,start,1001-end)
        }
    }
    @Before fun isolatedPackage() {check(InstrumentationRegistry.getInstrumentation().targetContext.packageName=="com.bontecou.whereday.mapqa")}
    @Test fun stripCanReachAndSelectPhotosBeyondThe500MapLimit() {
        val selected=mutableStateOf<String?>(null)
        compose.setContent {MaterialTheme {PagedMatchedCards({Source()},{selected.value=it}) {_,modifier->Box(modifier)}}}
        compose.waitUntil(5000) {compose.onAllNodesWithTag("matched-photo-card").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag("matched-photo-strip").performScrollToIndex(600)
        compose.waitUntil(5000) {compose.onAllNodesWithContentDescription("Photo taken at ${expectedPhotoTime(row(600).capturedAt)}").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("Photo taken at ${expectedPhotoTime(row(600).capturedAt)}").assertIsDisplayed().performTouchInput {click(center)}
        compose.runOnIdle {assertEquals(row(600).uri,selected.value)}
        compose.onNodeWithTag("matched-photo-strip").performScrollToIndex(1000)
        compose.waitUntil(5000) {compose.onAllNodesWithContentDescription("Photo taken at ${expectedPhotoTime(row(1000).capturedAt)}").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithContentDescription("Photo taken at ${expectedPhotoTime(row(1000).capturedAt)}").assertIsDisplayed()
        assertTrue(loads.all {it<=50})
    }
    @Test fun viewerStartsAtSelectedGlobalRankCrossesPagesAndDismissesOnInvalidation() {
        val sources=CopyOnWriteArrayList<Source>()
        val open=mutableStateOf(true)
        compose.setContent {MaterialTheme {if(open.value) PagedPhotoViewer(599,{Source().also {sources+=it}},row(599).uri,{open.value=false}) {photo,modifier->Box(modifier.testTag(photo.uri))}}}
        compose.waitUntil(5000) {compose.onAllNodesWithText("Photo 600 of 1001").fetchSemanticsNodes().isNotEmpty()}
        compose.onNodeWithTag(row(599).uri).assertExists()
        repeat(50) {compose.onNodeWithText("Next photo").performTouchInput {click(center)}}
        compose.onNodeWithText("Photo 650 of 1001").assertExists()
        compose.onNodeWithTag(row(649).uri).assertExists()
        compose.onNodeWithText("Previous photo").performTouchInput {click(center)}
        compose.onNodeWithText("Photo 649 of 1001").assertExists()
        compose.runOnIdle {sources.first().invalidate()}
        compose.waitUntil(5000) {!open.value}
        compose.onNodeWithText("Close photo").assertDoesNotExist()
        assertTrue(loads.all {it<=50})
    }
    @Test fun realRoomSourceOpensAnUnsampledPhotoAndRevocationDismissesItsViewer() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val database=com.bontecou.whereday.data.HistoryDatabase.create(context,null)
        try {
            val repository=com.bontecou.whereday.data.PhotoRepository(database)
            val range=com.bontecou.whereday.domain.HistoryRange(row(0).capturedAt,row(1000).capturedAt.plusMillis(1))
            kotlinx.coroutines.runBlocking {
                database.writeTransaction {
                    repeat(1001) {index->val photo=row(index)
                        database.history().put(com.bontecou.whereday.data.PhotoAssetRow(photo.uri,photo.capturedAt,photo.coordinate,true,true,Instant.EPOCH))
                        database.history().put(photo)
                    }
                }
                assertEquals(500,repository.window(range).photos.size)
                assertEquals(600,repository.rank(range,row(600).uri))
            }
            val open=mutableStateOf(true)
            compose.setContent {MaterialTheme {if(open.value) PagedPhotoViewer(600,{repository.pagingSource(range)},row(600).uri,{open.value=false}) {photo,modifier->Box(modifier.testTag(photo.uri))}}}
            compose.waitUntil(5000) {compose.onAllNodesWithText("Photo 601 of 1001").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithTag(row(600).uri).assertExists()
            kotlinx.coroutines.runBlocking {repository.unavailable(row(600).uri)}
            compose.waitUntil(5000) {!open.value}
            compose.onNodeWithText("Close photo").assertDoesNotExist()
        } finally {database.close()}
    }

    @Test fun deletedSelectionBeforeFirstLoadDismissesInsteadOfLeavingAStuckLoader() {
        val open=mutableStateOf(true)
        compose.setContent {MaterialTheme {if(open.value) PagedPhotoViewer(1200,{Source()},"content://synthetic/deleted",{open.value=false}) {_,modifier->Box(modifier)}}}
        compose.waitUntil(5000) {!open.value}
        compose.onNodeWithText("Close photo").assertDoesNotExist()
    }

    @Test fun settingsViewerCrossesLibraryPagesAndRemovesTheCurrentlyDisplayedReference() {
        val database=com.bontecou.whereday.data.HistoryDatabase.create(InstrumentationRegistry.getInstrumentation().targetContext,null)
        try {
            val repository=com.bontecou.whereday.data.PhotoRepository(database)
            val entries=(0 until 125).map {index->
                com.bontecou.whereday.data.PhotoLibraryEntry(com.bontecou.whereday.data.PhotoAssetRow("content://synthetic/library-%03d".format(index),if(index<65) null else Instant.EPOCH,null,true,index!=99,Instant.EPOCH),null)
            }
            kotlinx.coroutines.runBlocking {database.writeTransaction {entries.forEach {database.history().put(it.asset)}}}
            val open=mutableStateOf(true)
            val removed=mutableStateOf<String?>(null)
            compose.setContent {MaterialTheme {if(open.value) PagedLibraryPhotoViewer(49,{repository.libraryPagingSource()},entries[49].asset.uri,{open.value=false},{removed.value=it}) {entry,modifier->Box(modifier.testTag(entry.asset.uri))}}}
            compose.waitUntil(5000) {compose.onAllNodesWithText("Photo 50 of 125").fetchSemanticsNodes().isNotEmpty()}
            compose.onNodeWithText("Capture time unavailable").assertExists()
            repeat(50) {compose.onNodeWithText("Next photo").performTouchInput {click(center)}}
            compose.onNodeWithText("Photo 100 of 125").assertExists()
            compose.onNodeWithTag(entries[99].asset.uri).assertExists()
            compose.onNodeWithText("Unavailable · reselect to restore access").assertExists()
            compose.onNodeWithText("Remove photo from Whereday").performScrollTo().performTouchInput {click(center)}
            compose.runOnIdle {assertEquals(entries[99].asset.uri,removed.value)}
            kotlinx.coroutines.runBlocking {repository.remove(removed.value!!)}
            compose.waitUntil(5000) {!open.value}
            compose.onNodeWithText("Close photo").assertDoesNotExist()
            kotlinx.coroutines.runBlocking {assertEquals(124L,database.history().photoAssetCount());assertNotNull(database.history().photoAsset(entries[49].asset.uri))}
        } finally {database.close()}
    }

}
