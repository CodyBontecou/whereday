package com.bontecou.whereday

import com.bontecou.whereday.domain.Coordinate
import org.junit.Assert.*
import org.junit.Test

class JournalMapCameraTest {
    private val pose=JournalMapCamera(Coordinate(18.2,-66.2),11.0,25.0,30.0,true,null)
    @Test fun preservesPoseAndTheExactConsumedFocusRequest() {
        val focused=pose.copy(appliedFocus=VisitMapFocus("visit",Coordinate(18.0,-66.0),"consumed-request"))
        assertEquals(focused,JournalMapCamera.restored(focused.saved()))
        assertNotEquals(focused.appliedFocus,focused.appliedFocus!!.copy(requestId="new-request"))
    }
    @Test fun preservesUnfittedEmptyMapForLaterInitialDataFit() {
        val unfitted=pose.copy(fitted=false)
        assertEquals(unfitted,JournalMapCamera.restored(unfitted.saved()))
        assertEquals(pose,JournalMapCamera.restored(pose.saved()))
    }
    @Test fun rejectsMalformedOrUnknownSavedState() {
        assertNull(JournalMapCamera.restored(emptyList()))
        for((index,value) in listOf(0 to 2,1 to "18",3 to 11,6 to "true")) {
            assertNull(JournalMapCamera.restored(pose.saved().toMutableList().apply {this[index]=value}))
        }
        assertNull(JournalMapCamera.restored(pose.saved()+"truncated focus"))
    }
    @Test fun rejectsNonfiniteAndInvalidCameraAndFocusCoordinates() {
        for((index,value) in listOf(1 to 91.0,2 to -181.0,3 to -1.0,4 to Double.NaN,5 to Double.POSITIVE_INFINITY,5 to -1.0)) {
            assertNull(JournalMapCamera.restored(pose.saved().toMutableList().apply {this[index]=value}))
        }
        val focused=pose.copy(appliedFocus=VisitMapFocus("visit",Coordinate(18.0,-66.0),"request"))
        assertNull(JournalMapCamera.restored(focused.saved().toMutableList().apply {this[8]=Double.NaN}))
    }
}
