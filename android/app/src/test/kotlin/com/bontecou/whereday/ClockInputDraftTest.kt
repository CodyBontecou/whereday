package com.bontecou.whereday

import java.time.LocalTime
import org.junit.Assert.*
import org.junit.Test

class ClockInputDraftTest {
    @Test fun exportOpeningPreservesExactTimeAndDoesNotReuseAnEarlierApprovalIdentity() {
        val value=ExportClockDraft(LocalTime.of(7,25,36,123456789),"opening-one")
        assertEquals(value,ExportClockDraft.restored(value.saved()))
        assertNotEquals(value,value.copy(id="opening-two"))
    }
    @Test fun malformedExportOpeningIsNotRestored() {
        for(values in listOf(emptyList(),listOf("2","opening","07:25"),listOf("1","","07:25"),listOf("1","opening","25:00"))) {
            assertNull(ExportClockDraft.restored(values))
        }
    }
    @Test fun pendingFrameworkInputRetainsInvalidEmptyAndLocalizedTextVerbatim() {
        for(value in listOf(FrameworkClockInput(true,"99","71"),FrameworkClockInput(true,"",""),FrameworkClockInput(true,"٠٩","١٧"),FrameworkClockInput(false,"09","17"))) {
            assertEquals(value,FrameworkClockInput.restored(value.saved()))
        }
    }
    @Test fun malformedFrameworkInputCannotRestoreAClockDraft() {
        for(values in listOf(emptyList(),listOf("2","true","99","71"),listOf("1","yes","99","71"),listOf("1","true","99"))) {
            assertNull(FrameworkClockInput.restored(values))
        }
    }
}
