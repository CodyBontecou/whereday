package com.bontecou.whereday

import org.junit.Assert.*
import org.junit.Test

class PhotoViewerDraftTest {
    @Test fun earlierLibraryUriStateMigratesWithoutTrustingAnOldPageNumber() {
        val restored=checkNotNull(PhotoViewerDraft.restored("content://photos/old-library-opening"))
        assertEquals("library",restored.owner);assertEquals(restored.opening,restored.current)
        assertNull(PhotoViewerDraft.restored(41));assertNull(PhotoViewerDraft.restored("https://photos/old"))
    }
    @Test fun currentAssetAndOpeningIdentityRoundTripWithoutSavingAPageNumber() {
        val draft=PhotoViewerDraft.open("library","content://photos/first")
        draft.current="content://photos/last"
        val restored=checkNotNull(PhotoViewerDraft.restored(PhotoViewerDraft.saved(draft)))
        assertEquals(draft.owner,restored.owner);assertEquals(draft.id,restored.id)
        assertEquals(draft.opening,restored.opening);assertEquals(draft.current,restored.current)
    }
    @Test fun independentOpeningsDoNotShareTheirIdentity() {
        val first=PhotoViewerDraft.open("library","content://photos/one")
        val next=PhotoViewerDraft.open("matched:range","content://photos/one")
        assertNotEquals(first.id,next.id);assertNotEquals(first.owner,next.owner)
    }
    @Test fun corruptAndFutureSavedDraftsAreRejected() {
        for(values in listOf(emptyList(),listOf(2,"library","id","content://photos/a","content://photos/b"),listOf(1,"","id","content://photos/a","content://photos/b"),listOf(1,"library","","content://photos/a","content://photos/b"),listOf(1,"library","id","https://photos/a","content://photos/b"))) assertNull(PhotoViewerDraft.restored(values))
    }
}
