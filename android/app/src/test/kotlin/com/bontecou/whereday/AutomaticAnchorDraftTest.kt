package com.bontecou.whereday

import com.bontecou.whereday.domain.ExportSchedule
import com.bontecou.whereday.export.*
import com.bontecou.whereday.platform.AutomaticExportSettings
import org.junit.Assert.*
import org.junit.Test

class AutomaticAnchorDraftTest {
    private val settings=AutomaticExportSettings(true,ExportSchedule(9,17,23),ExportFormat.csv,DataKind.visits,ScheduledFileMode.append,true)
    @Test fun preservesTheWholeReviewBaselineAndOpeningIdentity() {
        val request=AutomaticAnchorDraft(settings,"opening-one")
        assertEquals(request,AutomaticAnchorDraft.restored(request.saved()))
        assertNotEquals(request,AutomaticAnchorDraft(settings,"opening-two"))
        assertNotEquals(request,request.copy(expected=settings.copy(format=ExportFormat.json)))
    }
    @Test fun emptyOrMalformedSavedReviewsCannotBecomeAnApproval() {
        assertNull(AutomaticAnchorDraft.restored(emptyList()))
        val saved=AutomaticAnchorDraft(settings,"opening").saved()
        for((index,value) in listOf(0 to "2",1 to "",2 to "yes",3 to "24",4 to "60",5 to "-1",6 to "unknown",7 to "unknown",8 to "unknown",9 to "yes")) {
            assertNull(AutomaticAnchorDraft.restored(saved.toMutableList().apply {this[index]=value}))
        }
        assertNull(AutomaticAnchorDraft.restored(saved.dropLast(1)))
    }
    @Test fun defaultDisabledSettingsRemainDisabledAfterRestoration() {
        val request=AutomaticAnchorDraft(AutomaticExportSettings(),"disabled")
        val restored=checkNotNull(AutomaticAnchorDraft.restored(request.saved()))
        assertEquals(request,restored);assertFalse(restored.expected.enabled)
    }
}
