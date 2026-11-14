package com.bontecou.whereday.domain

import java.time.LocalDate
import org.junit.Assert.*
import org.junit.Test

class CountryAtlasTest {
    private fun p(code:String,day:String,recorded:Boolean=true)=CountryPresence(code,LocalDate.parse(day),recorded,!recorded)
    private val all=listOf(p("US","2025-12-30"),p("US","2025-12-31"),p("US","2026-01-01"),p("MX","2026-01-01"),p("MX","2026-01-02"),p("MX","2026-01-03"),p("CA","2026-01-05",false))

    @Test fun inksFollowAllTimeOrderAndHomeTakesTheFirst() {
        val year=CountryAtlas(all,2026)
        assertEquals(listOf("MX","CA","US"),year.summaries.map {it.countryCode})
        // All-time: US and MX tie on three days; the name order breaks it, so MX leads.
        assertEquals(0,year.ink("MX"));assertEquals(1,year.ink("US"));assertEquals(2,year.ink("CA"))
        val home=CountryAtlas(all,2026,homeCountryCode="CA")
        assertEquals(0,home.ink("CA"));assertEquals(1,home.ink("MX"))
        assertEquals(3,CountryAtlas(all).maximumDays)
    }

    @Test fun yearScopesDaysAndTravelDaysListBothCountries() {
        val atlas=CountryAtlas(all,2026)
        assertEquals(listOf("MX","US"),atlas.countriesByDay.getValue(LocalDate.parse("2026-01-01")).map {it.countryCode})
        assertFalse(LocalDate.parse("2025-12-31") in atlas.countriesByDay)
        assertTrue(LocalDate.parse("2025-12-31") in CountryAtlas(all).countriesByDay)
    }

    @Test fun gapsCountFromTheFirstRecordedDayThroughTodayWithinTheYear() {
        assertEquals(1,CountryAtlas(all,2026).gapCount(LocalDate.parse("2026-01-05")))
        assertEquals(3,CountryAtlas(all,2026).gapCount(LocalDate.parse("2026-01-07")))
        assertEquals(1,CountryAtlas(all).gapCount(LocalDate.parse("2026-01-05")))
        assertEquals(0,CountryAtlas(emptyList(),2026).gapCount(LocalDate.parse("2026-01-05")))
        assertEquals(listOf(2025,2026),CountryAtlas.years(all,LocalDate.parse("2026-03-01")))
        assertEquals(listOf(2026),CountryAtlas.years(emptyList(),LocalDate.parse("2026-03-01")))
    }
}
