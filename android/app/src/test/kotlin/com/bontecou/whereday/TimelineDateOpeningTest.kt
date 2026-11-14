package com.bontecou.whereday

import org.junit.Assert.*
import org.junit.Test
import java.time.*

class TimelineDateOpeningTest {
    @Test fun roundTripRetainsCivilDayZoneAndIndependentOpeningIdentity() {
        for(day in listOf(LocalDate.of(1850,1,2),LocalDate.of(0,2,29),LocalDate.of(2200,12,31))) {
            val opening=TimelineDateOpening(day,ZoneId.of("Pacific/Apia"))
            assertEquals(opening,TimelineDateOpening.restored(TimelineDateOpening.saved(opening)))
            assertNotEquals(opening.id,TimelineDateOpening(day,opening.zone).id)
        }
    }
    @Test fun invalidOrFutureEnvelopesCannotReopenAnUnrelatedPicker() {
        val values=TimelineDateOpening.saved(TimelineDateOpening(LocalDate.of(2026,11,2),ZoneOffset.UTC))
        for(raw in listOf(emptyList(),values+"extra",values.toMutableList().apply {this[0]=2},
            values.toMutableList().apply {this[1]="bad"},values.toMutableList().apply {this[2]="2026-02-30"},
            values.toMutableList().apply {this[2]="+999999999-01-01"},values.toMutableList().apply {this[3]="Not/AZone"},
            values.toMutableList().apply {this[2]="2011-12-30";this[3]="Pacific/Apia"})) assertNull(TimelineDateOpening.restored(raw))
    }
}
