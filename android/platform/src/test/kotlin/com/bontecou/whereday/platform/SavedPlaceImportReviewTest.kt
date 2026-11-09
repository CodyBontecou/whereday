package com.bontecou.whereday.platform

import com.bontecou.whereday.export.SavedPlaceCsvImporter
import org.junit.Assert.*
import org.junit.Test

class SavedPlaceImportReviewTest {
    private fun parsed()=SavedPlaceCsvImporter.parse("name,latitude,longitude\nQA,0,180\n".toByteArray())

    @Test fun discardedOrClearedReviewCannotBeRevivedByALateRead() {
        val owner=SavedPlaceImportReview()
        val request=owner.begin()
        owner.discard()
        assertFalse(owner.publish(request,parsed()))
        assertFalse(owner.isCurrent(request))
        assertNull(owner.preview.value)
    }

    @Test fun oldPickerCancellationAndReadCannotReplaceANewerReview() {
        val owner=SavedPlaceImportReview()
        val old=owner.begin()
        val fresh=owner.begin()
        val review=parsed()
        assertTrue(owner.publish(fresh,review))
        owner.discard(old)
        assertFalse(owner.publish(old,parsed()))
        assertTrue(owner.isCurrent(fresh))
        assertSame(review,owner.preview.value)
    }
}
