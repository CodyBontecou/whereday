package com.bontecou.whereday.data

import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.runBlocking
import org.junit.Test
import org.junit.Assert.*
import java.time.*

class CountryRegionsPersistenceTest {
    private val now=Instant.parse("2026-10-29T12:00:00Z")
    private val day=LocalDate.parse("2026-10-29")
    private val coordinate=Coordinate(0.0,0.0)
    private suspend fun fixture(block:suspend (HistoryDatabase,HistoryRepository)->Unit) {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        check(context.packageName=="com.bontecou.whereday.data.test")
        val db=HistoryDatabase.create(context,null)
        try {block(db,HistoryRepository(db,Clock.fixed(now,ZoneOffset.UTC),{ZoneOffset.UTC}))} finally {db.close()}
    }
    @Test fun allFrozenRegionsRoundTripAndMergeManualWithLowercaseRecordedPresence()=runBlocking {fixture {db,repo->
        for(code in CountryRegions.all) {
            repo.saveCountryStay(CountryStay("manual-$code",code,day,day))
            db.history().put(PointRow(TrackPoint(id="point-$code",coordinate=coordinate,time=now,accuracy=5.0,countryCode=code.lowercase(java.util.Locale.ROOT),timeZone="UTC"),null))
        }
        val report=repo.readCountryDays(CountryDayBounds())
        assertEquals(CountryRegions.all,report.days.map {it.countryCode}.toSet())
        assertEquals(CountryRegions.all.size,report.stays.size);assertEquals(CountryRegions.all.size,report.days.size)
        assertTrue(report.days.all {it.recorded && it.manual && it.day==day})
        assertEquals(0L,report.unresolvedPoints)
        assertEquals("xk",db.history().point("point-XK")!!.value.countryCode)
        val csv=countryDaysCsv(report.days)
        for(code in listOf("CQ","XK","AC","001")) assertTrue(csv.contains("$code,2026-10-29,recorded+manual\r\n"))
    }}
    @Test fun cachedKosovoResolutionIsAcceptedWithoutAnyProviderOrAssetRequest()=runBlocking {fixture {db,repo->
        db.history().put(PointRow(TrackPoint(id="cached",coordinate=coordinate,time=now,accuracy=5.0),null))
        db.history().put(CountryLookupRow(coordinate,"XK","Europe/Belgrade","synthetic-regions"))
        val lookup=object:CountryLookup {
            override suspend fun prepare()=error("No asset preparation expected")
            override suspend fun lookup(coordinate:Coordinate):CountryLocation?=error("No provider request expected")
        }
        val state=CountryAnalysis(db,lookup,"synthetic-regions",Clock.fixed(now,ZoneOffset.UTC)).run({true},{})
        assertEquals(1L,state.resolved)
        assertEquals("XK",db.history().point("cached")!!.value.countryCode)
        assertEquals(listOf(CountryPresence("XK",day,true,false)),repo.readCountryDays(CountryDayBounds()).days)
    }}
    @Test fun unknownCountryStillStaysUnresolvedAndCannotBecomeAManualStay()=runBlocking {fixture {db,repo->
        try {repo.saveCountryStay(CountryStay("unknown","ZZ",day,day));fail("Unknown region accepted")} catch(_:IllegalArgumentException) {}
        db.history().put(PointRow(TrackPoint(id="unknown",coordinate=coordinate,time=now,accuracy=5.0,countryCode="ZZ",timeZone="UTC"),null))
        val report=repo.readCountryDays(CountryDayBounds())
        assertTrue(report.days.isEmpty());assertTrue(report.stays.isEmpty());assertEquals(1L,report.unresolvedPoints)
    }}
}
