package com.bontecou.whereday

import com.bontecou.whereday.domain.HistoryRange
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PhotoBrowserDraftsTest {
    private val snapshot="a".repeat(64)
    @Test fun openingRoundTripsAllPlaceAreaAndAllScopes() {
        for(value in listOf(PhotoBrowserOpening("all",snapshot),PhotoBrowserOpening("areas",snapshot,setOf("area:2:3","area:1:0"),"area"),PhotoBrowserOpening("place",snapshot,setOf("place:51"),"map",51,epoch="generation"))) {
            value.browser.select(51);value.browser.directoryPage=PhotoPageCursor(1,50)
            value.browser.photo=PhotoViewerDraft.open("$snapshot:51","content://photos/first").apply {current="content://photos/current"}
            val restored=checkNotNull(PhotoBrowserOpening.restored(PhotoBrowserOpening.saved(value)))
            assertEquals(value,restored);assertEquals(value.browser.directoryPage,restored.browser.directoryPage)
            assertEquals(value.browser.photo!!.current,restored.browser.photo!!.current)
        }
    }
    @Test fun malformedAndOversizedOpeningsCannotRestoreReusedIds() {
        val good=PhotoBrowserOpening.saved(PhotoBrowserOpening("place",snapshot))
        for(values in listOf(emptyList(),good.toMutableList().apply {this[0]=2},good.toMutableList().apply {this[2]="old-row-identity"},good.toMutableList().apply {this[4]=-1L},listOf(1,"id",snapshot,"area",0L,true,"epoch",501)+List(501) {"area:$it"}+PhotoDirectoryDraft.saved(PhotoDirectoryDraft(snapshot,null)),listOf(1,"id",snapshot,"area",0L,true,"epoch",2,"same","same")+PhotoDirectoryDraft.saved(PhotoDirectoryDraft(snapshot,null)))) assertNull(PhotoBrowserOpening.restored(values))
    }
    @Test fun nestedPhotoAndBoundedCursorPairsRoundTripIndependently() {
        val draft=PhotoDirectoryDraft(snapshot,51).apply {
            directoryPage=PhotoPageCursor(1,50);collectionPage=PhotoPageCursor(10000,500000)
            photo=PhotoViewerDraft.open("$snapshot:51","content://photos/opening").apply {current="content://photos/current"}
        }
        val saved=PhotoDirectoryDraft.saved(draft);assertEquals(13,saved.size)
        val restored=checkNotNull(PhotoDirectoryDraft.restored(saved))
        assertEquals(draft.snapshot,restored.snapshot);assertEquals(draft.place,restored.place)
        assertEquals(draft.directoryPage,restored.directoryPage);assertEquals(draft.collectionPage,restored.collectionPage)
        assertEquals(draft.photo!!.id,restored.photo!!.id);assertEquals(draft.photo!!.current,restored.photo!!.current)
    }
    @Test fun mismatchedNestedPhotoScopeAndCorruptCursorsAreRejected() {
        val saved=PhotoDirectoryDraft.saved(PhotoDirectoryDraft(snapshot,1))
        for(values in listOf(saved.toMutableList().apply {this[0]=2},saved.toMutableList().apply {this[3]=1},saved.toMutableList().apply {this[4]=1L})) assertNull(PhotoDirectoryDraft.restored(values))
        val wrong=PhotoDirectoryDraft(snapshot,1).apply {photo=PhotoViewerDraft.open("different-directory","content://photos/wrong")}
        assertNull(PhotoDirectoryDraft.restored(PhotoDirectoryDraft.saved(wrong)))
    }
    @Test fun repeatedOldNextBoundaryCannotAdvanceThePageAgain() {
        val first=PhotoPageCursor().next(50);assertEquals(PhotoPageCursor(1,50),first)
        assertEquals(first,first.next(50));assertEquals(first,first.next(49));assertEquals(PhotoPageCursor(2,100),first.next(100))
        for(pair in listOf(-1 to 0L,0 to 1L,1 to 0L,Int.MAX_VALUE to 1L)) assertTrue(runCatching {PhotoPageCursor(pair.first,pair.second)}.isFailure)
    }
    @Test fun heldOpeningRetainsOwnerAndExactRangeAcrossClockChanges() {
        val from=Instant.parse("2026-11-02T12:00:00.123456789Z")
        val hold=PhotoBrowserHold("selection",HistoryRange(from,from.plusSeconds(30)))
        assertEquals(hold,PhotoBrowserHold.restored(PhotoBrowserHold.saved(hold)))
        assertNull(PhotoBrowserHold.restored(listOf(2,"selection",from.toString(),from.plusSeconds(30).toString())))
    }
}
