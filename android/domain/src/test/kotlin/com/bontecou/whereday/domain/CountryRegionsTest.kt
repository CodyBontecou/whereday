package com.bontecou.whereday.domain

import com.google.gson.JsonParser
import org.junit.Test
import org.junit.Assert.*
import java.time.*
import java.util.Locale

class CountryRegionsTest {
    private val fixture by lazy {JsonParser.parseReader(javaClass.getResourceAsStream("/ios-country-regions.json")!!.reader()).asJsonObject}
    private val now=Instant.parse("2026-10-29T12:00:00Z")
    @Test fun membershipPickerAndFallbackNamesMatchTheActualFoundationSnapshot() {
        assertEquals(fixture["all"].asJsonArray.map {it.asString}.toSet(),CountryRegions.all)
        assertEquals(fixture["picker"].asJsonArray.map {it.asString}.toSet(),CountryRegions.picker)
        for(language in listOf("en","es")) for(code in CountryRegions.picker) {
            assertEquals("$language/$code",fixture["names"].asJsonObject[language].asJsonObject[code].asString,CountryRegions.fallbackName(code,language))
        }
        assertTrue(CountryRegions.picker.containsAll(listOf("XK","CQ","AC","EU","UN")))
        for(code in CountryRegions.all) assertEquals(code,CountryLocation(code,"UTC").countryCode)
        assertTrue("001" in CountryRegions.all);assertFalse("001" in CountryRegions.picker)
    }
    @Test fun everyActualSwiftCalculatorCaseMatchesRecordedAndManualPresenceCsv() {
        for(case in fixture["cases"].asJsonArray) {
            val row=case.asJsonObject;val raw=row["raw"].asString
            fun accumulator()=CountryDayAccumulator(now).also {
                it.add(TrackPoint(coordinate=Coordinate(0.0,0.0),time=now,accuracy=5.0,countryCode=raw,timeZone="UTC"))
                it.add(Visit(coordinate=Coordinate(0.0,0.0),arrivedAt=now,countryCode=raw,timeZone="UTC"))
            }
            assertEquals("recorded/$raw",row["recordedCsv"].asString,countryDaysCsv(accumulator().days()))
            val combined=accumulator()
            CountryRegions.normalize(raw)?.let {combined.add(CountryStay(countryCode=it,firstDay=LocalDate.parse("2026-10-29"),lastDay=LocalDate.parse("2026-10-29")))}
            assertEquals("combined/$raw",row["combinedCsv"].asString,countryDaysCsv(combined.days()))
        }
    }
    @Test fun unknownRegionsAndTimeZonesRemainUnresolved() {
        for(code in listOf("ZZ","UK"," US","us ","USA","")) assertNull(CountryRegions.normalize(code))
        assertNull(CountryRegions.normalize(null))
        try {CountryLocation("ZZ","UTC");fail("Unknown region accepted")} catch(_:IllegalArgumentException) {}
        try {CountryLocation("XK","not-a-zone");fail("Unknown zone accepted")} catch(_:IllegalArgumentException) {}
        val a=CountryDayAccumulator(now)
        a.add(TrackPoint(coordinate=Coordinate(0.0,0.0),time=now,accuracy=5.0,countryCode="XK",timeZone=null))
        assertTrue(a.days().isEmpty());assertEquals(1L,a.unresolvedPoints)
    }
    @Test fun normalizationIsIndependentOfDefaultLocaleAndDoesNotTrim() {
        val before=Locale.getDefault()
        try {Locale.setDefault(Locale.forLanguageTag("tr-TR"));assertEquals("IO",CountryRegions.normalize("io"));assertEquals("XK",CountryRegions.normalize("xk"));assertNull(CountryRegions.normalize("xk "))}
        finally {Locale.setDefault(before)}
    }
    @Test fun namesForNewRegionsRemainAvailableOnOlderIcuAndSetsCannotBeMutated() {
        for(language in listOf("en","es")) assertEquals(fixture["names"].asJsonObject[language].asJsonObject["CQ"].asString,CountryRegions.displayName("CQ",Locale.forLanguageTag(language)))
        for(set in listOf(CountryRegions.all,CountryRegions.picker)) {
            try {(set as MutableSet<String>).remove("US");fail("Frozen membership changed")} catch(_:UnsupportedOperationException) {}
        }
        assertTrue("US" in CountryRegions.all);assertTrue("US" in CountryRegions.picker)
    }
}
