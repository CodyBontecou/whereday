package com.bontecou.whereday.export

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class ExportSelectionTest {
    @Test fun everyFilterFieldAndFileChoiceSurvivesRoundTrip() {
        val selection=ExportSelection(ExportFormat.kml,setOf(ExportFormat.csv,ExportFormat.geojson),
            ExportOptions(kind=DataKind.points,from=Instant.parse("2026-01-01T00:00:00Z"),until=Instant.parse("2026-10-27T00:00:00Z"),zone=ZoneId.of("America/New_York"),
                startTime=LocalTime.of(22,35),endTime=LocalTime.of(6,15),completedOnly=true,minimumVisitMinutes=15,excludeOutliers=true,maximumAccuracy=25.0,
                visitCoordinates=false,visitName=false,visitAddress=false,visitNotes=false,visitDuration=false,pointAltitude=false,pointSpeed=false,pointAccuracy=false,pointOutlier=false),
            "{year}/🗺️ - {date} - {format}",true,true)
        assertEquals(selection,ExportSelectionCodec.decode(ExportSelectionCodec.encode(selection)))
    }
    @Test fun allDatesIncludesPre1970ButLegacySavedBoundsAreNotSilentlyBroadened() {
        val earlier=Instant.parse("1969-12-31T23:59:59.125Z")
        assertTrue(ExportOptions().includes(earlier))
        val saved=ExportSelection(options=ExportOptions(from=Instant.EPOCH))
        val decoded=ExportSelectionCodec.decode(ExportSelectionCodec.encode(saved))
        assertEquals(Instant.EPOCH,decoded.options.from);assertFalse(decoded.options.includes(earlier))
        val reset=decoded.copy(options=decoded.options.copy(from=ExportOptions.allDatesStart))
        assertTrue(reset.options.includes(earlier))
        assertEquals(ExportSelection(),ExportSelectionCodec.decode(ExportSelectionCodec.encode(ExportSelection())))
    }
    @Test fun invalidSavedFiltersAndFutureVersionsRequireExplicitReview() {
        val original=ExportSelectionCodec.encode(ExportSelection())
        val corrupt=listOf("{",original.replace("\"version\":2","\"version\":3"),original.replace("\"kind\":\"all\"","\"kind\":\"unknown\""),
            original.replace("\"excludeOutliers\":false","\"excludeOutliers\":\"false\""),
            JsonParser.parseString(original).asJsonObject.apply {remove("until")}.toString(),
            JsonParser.parseString(original).asJsonObject.apply {addProperty("maximumAccuracy",-1)}.toString())
        corrupt.forEach {saved -> try {ExportSelectionCodec.decode(saved);fail("Invalid settings must not silently select all history")} catch(_:Exception) {}}
    }
    @Test fun newInstallDefaultsAndUnknownOptionalFieldsAreSupported() {
        assertEquals(ExportSelection(),ExportSelectionCodec.decode(null))
        val selection=ExportSelection()
        val json=JsonParser.parseString(ExportSelectionCodec.encode(selection)).asJsonObject.apply {addProperty("futureOptionalLabel","ignored")}
        assertEquals(selection,ExportSelectionCodec.decode(json.toString()))
    }
    @Test fun versionOnePreservesEveryHistoryChoiceAndVersionTwoRequiresExplicitOutingSubject() {
        val original=ExportSelection(format=ExportFormat.markdown,options=ExportOptions(kind=DataKind.visits,from=Instant.parse("2020-01-01T00:00:00Z")),splitDays=true)
        val legacy=JsonParser.parseString(ExportSelectionCodec.encode(original)).asJsonObject.apply {addProperty("version",1);remove("outings")}
        assertEquals(original,ExportSelectionCodec.decode(legacy.toString()))
        val invalid=JsonParser.parseString(ExportSelectionCodec.encode(original)).asJsonObject.apply {remove("outings")}
        try {ExportSelectionCodec.decode(invalid.toString());fail()} catch(_:IllegalArgumentException) {}
    }
}
