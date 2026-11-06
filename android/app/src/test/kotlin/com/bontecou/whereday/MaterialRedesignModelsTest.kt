package com.bontecou.whereday

import com.bontecou.whereday.domain.Coordinate
import com.bontecou.whereday.domain.CountryPresence
import com.bontecou.whereday.domain.Visit
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

class MaterialRedesignModelsTest {
    private fun presence(code:String,day:String,recorded:Boolean=true,manual:Boolean=false)=CountryPresence(code,LocalDate.parse(day),recorded,manual)

    @Test fun atlasOrdersCountriesByDistinctDaysAndCountsGapsOnlyAfterTheJournalStarts() {
        val days=listOf(presence("US","2026-01-03"),presence("US","2026-01-04"),presence("MX","2026-01-04"),presence("MX","2026-01-06"),
            presence("MX","2026-01-06"),presence("JP","2025-12-31"))
        val atlas=AtlasYear.of(2026,days,LocalDate.parse("2026-01-07"))
        assertEquals(listOf(AtlasCountry("MX",2),AtlasCountry("US",2)),atlas.countries)
        // Jan 1–2 precede the first record; Jan 5 and Jan 7 are gaps.
        assertEquals(2,atlas.gaps)
        assertEquals(2,atlas.byDay.getValue(LocalDate.parse("2026-01-04")).size)
        assertNull(atlas.byDay[LocalDate.parse("2025-12-31")])
    }

    @Test fun atlasFutureYearHasNoGapsAndTotalsSpanYears() {
        assertEquals(0,AtlasYear.of(2027,emptyList(),LocalDate.parse("2026-11-03")).gaps)
        val totals=AtlasYear.totals(listOf(presence("PT","2025-08-05"),presence("PT","2026-08-05"),presence("CA","2026-06-24")))
        assertEquals(listOf(AtlasCountry("PT",2),AtlasCountry("CA",1)),totals)
    }

    @Test fun widgetSnapshotInksPlacesAndFindsTheOpenStay() {
        val zone=ZoneOffset.UTC
        val day=LocalDate.parse("2026-11-01")
        val at={time:String->Instant.parse("2026-11-01T${time}:00Z")}
        val here=Coordinate(37.0,-122.0)
        val visits=listOf(
            Visit("a",here,at("00:00"),at("08:12"),name="Home"),
            Visit("b",here,at("09:50"),at("12:30"),name="Office"),
            Visit("c",here,at("13:50"),null,name="Office"),
        )
        val snapshot=WidgetSnapshot.of(day,zone,visits,6400.0,true,true,at("07:42"),at("14:20"),dark=false)
        assertEquals("Office",snapshot.here);assertEquals(at("13:50"),snapshot.hereSince)
        assertEquals(2,snapshot.places)
        assertEquals(WidgetSnapshot.inksLight[0],snapshot.stays[0].ink)
        assertEquals(snapshot.stays[1].ink,snapshot.stays[2].ink)
        assertEquals((14*60+20)/1440f,snapshot.now,1e-4f)
        assertEquals(snapshot.now,snapshot.stays[2].end,1e-4f)
    }

    @Test fun todayDistanceUsesOneDecimalInTheUnitOfChoice() {
        assertEquals("6.4 km",todayDistance(6400.0,true,java.util.Locale.US))
        assertEquals("4.0 mi",todayDistance(6437.0,false,java.util.Locale.US))
        assertEquals("6,4 km",todayDistance(6400.0,true,java.util.Locale.forLanguageTag("es")))
    }

    @Test fun recipeFormatNamesReadAsProductNames() {
        assertEquals("GPX",recipeFormatName(com.bontecou.whereday.export.ExportFormat.gpx))
        assertEquals("GeoJSON",recipeFormatName(com.bontecou.whereday.export.ExportFormat.geojson))
        assertEquals("OwnTracks",recipeFormatName(com.bontecou.whereday.export.ExportFormat.owntracks))
    }

    @Test fun monthRecapCountsPlacesDaysNewPlacesAndTheBiggestDay() {
        val zone=ZoneOffset.UTC
        val month=java.time.YearMonth.of(2026,10)
        val here=Coordinate(37.0,-122.0)
        val at={day:Int,time:String->Instant.parse("2026-10-%02dT%s:00Z".format(day,time))}
        val visits=listOf(
            Visit("h1",here,at(1,"00:00"),at(1,"08:00"),name="Home"),
            Visit("o1",here,at(1,"09:00"),at(1,"17:00"),name="Office"),
            Visit("h2",here,at(2,"18:00"),at(2,"23:00"),name="Home"),
            Visit("c1",here,at(17,"12:00"),at(17,"13:00"),name="Cafe"),
        )
        val distances=List(31) {if(it==16) 31_000.0 else if(it==0) 5_000.0 else 0.0}
        val recap=MonthRecap.of(month,zone,visits,distances,setOf(0,16,20),setOf("Home"),Instant.parse("2026-11-03T00:00:00Z"))
        assertEquals(4,recap.visitCount);assertEquals(3,recap.placeCount)
        assertEquals(2,recap.newPlaces)
        assertEquals(4,recap.recordedDays) // visits on 1, 2, 17 plus points on 21
        assertEquals(LocalDate.parse("2026-10-17"),recap.biggestDay);assertEquals(31_000.0,recap.biggestDistance,0.1)
        assertEquals(36_000.0,recap.totalDistance,0.1)
        // Office (8h) out-stays Home (8h+5h=13h)? Home 13h leads, so Home takes the first ink.
        assertEquals(0,recap.arcs[0].first {it.start==0f}.ink)
        assertEquals(31,recap.arcs.size)
    }

    @Test fun traceMomentSaysStayingMovingOrLater() {
        val here=Coordinate(37.0,-122.0)
        val at={time:String->Instant.parse("2026-11-01T${time}:00Z")}
        val visits=listOf(Visit("a",here,at("09:00"),at("10:00"),name="Office"),Visit("b",here,at("13:50"),null,name=" "))
        assertEquals(TraceMoment.Staying("Office"),TraceMoment.at(at("09:30"),at("14:20"),visits))
        assertEquals(TraceMoment.Moving,TraceMoment.at(at("11:00"),at("14:20"),visits))
        assertEquals(TraceMoment.Staying(null),TraceMoment.at(at("14:00"),at("14:20"),visits))
        assertEquals(TraceMoment.Later,TraceMoment.at(at("15:00"),at("14:20"),visits))
    }

    @Test fun placeRhythmMatchesTheIosCountsMedianYearAndPeak() {
        val zone=ZoneOffset.UTC
        val here=Coordinate(37.0,-122.0)
        // Three weekday lunches (Mon–Wed 2026-10-26..28, 12:00–13:00) and one long Saturday morning.
        val visits=listOf(26,27,28).map {Visit("l$it",here,Instant.parse("2026-10-${it}T12:00:00Z"),Instant.parse("2026-10-${it}T13:00:00Z"))}+
            Visit("sat",here,Instant.parse("2026-10-31T09:00:00Z"),Instant.parse("2026-10-31T09:30:00Z"))
        val rhythm=PlaceRhythm(visits,Instant.parse("2026-11-03T00:00:00Z"),zone)
        assertEquals(4,rhythm.visitCount)
        assertEquals(java.time.Duration.ofMinutes(60),rhythm.typicalStay)
        assertEquals(java.time.Duration.ofMinutes(210),rhythm.timeThisYear)
        assertEquals(3,rhythm.level(1,12));assertEquals(0,rhythm.level(1,8));assertEquals(2,rhythm.level(6,9))
        assertEquals(PlaceRhythm.Leaning.weekday to PlaceRhythm.Part.midday,rhythm.summary)
        assertEquals("1h",PlaceRhythm.duration(java.time.Duration.ofMinutes(60)));assertEquals("1h 11m",PlaceRhythm.duration(java.time.Duration.ofMinutes(71)))
        assertEquals("2d 1h",PlaceRhythm.duration(java.time.Duration.ofHours(49)))
        val place=com.bontecou.whereday.domain.SavedPlace("p","Cafe",here,radius=60.0)
        assertEquals(4,PlaceRhythm.visitsFor(place,visits+Visit("far",Coordinate(37.01,-122.0),Instant.parse("2026-10-30T10:00:00Z"))).size)
    }
}
