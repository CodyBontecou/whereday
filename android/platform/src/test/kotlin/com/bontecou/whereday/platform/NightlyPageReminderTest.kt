package com.bontecou.whereday.platform

import java.time.*
import org.junit.Assert.*
import org.junit.Test

class NightlyPageReminderTest {
    private val zone=ZoneId.of("America/Los_Angeles")
    private fun at(date:String,time:String)=ZonedDateTime.of(LocalDate.parse(date),LocalTime.parse(time),zone)

    @Test fun beforeTheCutoffFiresTonightAtNine() {
        assertEquals(at("2026-11-23","21:00"),NightlyPageReminder.nextFire(at("2026-11-23","00:00")))
        assertEquals(at("2026-11-23","21:00"),NightlyPageReminder.nextFire(at("2026-11-23","20:29:59")))
        assertFalse(NightlyPageReminder.tomorrow(at("2026-11-23","08:15")))
    }
    @Test fun fromHalfPastEightItMovesToTomorrow() {
        assertEquals(at("2026-11-24","21:00"),NightlyPageReminder.nextFire(at("2026-11-23","20:30")))
        assertEquals(at("2026-11-24","21:00"),NightlyPageReminder.nextFire(at("2026-11-23","21:00")))
        assertEquals(at("2026-11-24","21:00"),NightlyPageReminder.nextFire(at("2026-11-23","23:59")))
        assertTrue(NightlyPageReminder.tomorrow(at("2026-11-23","20:45")))
    }
    @Test fun crossesMonthAndYearBoundaries() {
        assertEquals(at("2027-01-01","21:00"),NightlyPageReminder.nextFire(at("2026-12-31","22:10")))
    }
    @Test fun keepsLocalNineAcrossDaylightSavingChanges() {
        // US daylight saving time ends on 2026-11-01: the 21:00 wall time is kept, not a fixed 24-hour offset.
        val fire=NightlyPageReminder.nextFire(at("2026-10-31","23:00"))
        assertEquals(LocalTime.of(21,0),fire.toLocalTime());assertEquals(LocalDate.parse("2026-11-01"),fire.toLocalDate())
        assertEquals(ZoneOffset.ofHours(-8),fire.offset)
    }
}
