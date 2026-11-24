package com.bontecou.whereday.platform

import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZonedDateTime

/** The evening onboarding turned recaps on is stored as a local day and read back for the foreground exemption. */
@RunWith(RobolectricTestRunner::class)
@Config(sdk=[35],manifest=Config.NONE)
class RecapOnboardingEveningTest {
    @Test fun storesTheLocalDayOnboardingTurnedRecapsOn() {
        val context=RuntimeEnvironment.getApplication()
        assertNull(EveningRecap.onboardingEvening(context))
        // 23:30 in Los Angeles is already the next day in UTC; the local day is what counts.
        EveningRecap.markOnboardingEvening(context,ZonedDateTime.of(2026,11,23,23,30,0,0,ZoneId.of("America/Los_Angeles")))
        assertEquals(LocalDate.of(2026,11,23),EveningRecap.onboardingEvening(context))
        EveningRecap.markOnboardingEvening(context,ZonedDateTime.of(2026,11,25,19,0,0,0,ZoneId.of("America/Los_Angeles")))
        assertEquals(LocalDate.of(2026,11,25),EveningRecap.onboardingEvening(context))
    }
}
