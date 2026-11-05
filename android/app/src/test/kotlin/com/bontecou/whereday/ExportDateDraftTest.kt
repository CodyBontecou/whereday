package com.bontecou.whereday

import com.bontecou.whereday.export.ExportOptions
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ExportDateDraftTest {
    private val zone=ZoneId.of("America/New_York")
    private val options=ExportOptions(from=Instant.parse("2024-03-09T05:00:00.123456789Z"),until=Instant.parse("2024-03-11T03:59:59.999999999Z"),zone=zone)
    @Test fun openingAndRestorationRetainExactBoundsZoneAndIdentity() {
        val draft=ExportDateDraft.open(options).copy(id="opening")
        assertEquals(draft,ExportDateDraft.restored(draft.saved()))
        assertNotEquals(draft,draft.copy(id="new-opening"))
        val all=ExportDateDraft.open(ExportOptions(zone=zone),LocalDate.of(2026,11,2))
        assertEquals(LocalDate.of(2026,11,2),all.first);assertEquals(all.first,all.last)
    }
    @Test fun applyingCivilDatesPreservesLatestUnrelatedFiltersAndTheDstDay() {
        val draft=ExportDateDraft.open(options)
        val latest=options.copy(maximumAccuracy=8.5,completedOnly=true,pointAltitude=false)
        val day=LocalDate.of(2024,3,10)
        assertEquals(latest.copy(from=Instant.parse("2024-03-10T05:00:00Z"),until=Instant.parse("2024-03-11T03:59:59.999999999Z")),draft.applyTo(latest,day,day))
    }
    @Test fun changedBoundsZoneInvalidAndSkippedDatesCannotUseTheReview() {
        val draft=ExportDateDraft.open(options);val day=LocalDate.of(2024,3,10)
        for(changed in listOf(options.copy(from=options.from.plusNanos(1)),options.copy(until=options.until.minusNanos(1)),options.copy(zone=ZoneOffset.UTC))) {
            assertNull(draft.applyTo(changed,day,day))
        }
        assertNull(draft.applyTo(options,day,day.minusDays(1)))
        assertNull(draft.applyTo(options,LocalDate.MAX,LocalDate.MAX))
        val apia=ExportOptions(zone=ZoneId.of("Pacific/Apia"));val skipped=LocalDate.of(2011,12,30)
        assertNull(ExportDateDraft.open(apia).applyTo(apia,skipped,skipped))
    }
    @Test fun malformedOrReversedSavedBaselinesAreRejected() {
        val values=ExportDateDraft.open(options).saved()
        assertNull(ExportDateDraft.restored(emptyList()))
        for((index,value) in listOf(0 to "2",1 to "",2 to "invalid",3 to "2020-01-01T00:00:00Z",4 to "Unknown/Zone",5 to "2024-02-30",6 to "bad")) {
            assertNull(ExportDateDraft.restored(values.toMutableList().apply {this[index]=value}))
        }
    }
}
