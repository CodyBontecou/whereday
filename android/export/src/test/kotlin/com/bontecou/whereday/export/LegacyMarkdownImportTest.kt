package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.Locale

class LegacyMarkdownImportTest {
    private fun parse(text:String,locale:Locale=Locale.US,zone:ZoneId=ZoneId.of("America/Puerto_Rico"))=HistoryImporter.parse(text.trimIndent(),ExportFormat.markdown,locale,zone)
    @Test fun legacyVisitSectionsKeepDateBoundariesLocalTimesAndNotes() {
        val result=parse("""
            # Whereday History
            ## Monday, October 26, 2026
            ### Coffee
            - **Arrived:** 9:30 AM
            - **Departed:** 10:15 AM
            - **Address:** Synthetic address
            - **Coordinates:** 18.0, -66.0
            > First line
            > Second line
            ## Tuesday, October 27, 2026
            ### Home
            - **Arrived:** 8:00 PM
            - **Departed:** Still here
            - **Coordinates:** 18.1,-66.1
        """)
        assertTrue(result.errors.toString(),result.errors.isEmpty());assertEquals(2,result.visits.size)
        val first=result.visits.first();assertEquals(Instant.parse("2026-10-26T13:30:00Z"),first.arrivedAt)
        assertEquals(Instant.parse("2026-10-26T14:15:00Z"),first.departedAt)
        assertEquals("First line\nSecond line",first.notes);assertEquals("Synthetic address",first.address)
        assertEquals(VisitSource.imported,first.source);assertEquals(Confirmation.unconfirmed,first.confirmation)
        assertEquals(Instant.parse("2026-10-28T00:00:00Z"),result.visits.last().arrivedAt);assertNull(result.visits.last().departedAt)
    }
    @Test fun historicalVisitAndPointTablesReadClockTimesUnitsAndMissingValues() {
        val result=parse("""
            ## Monday, October 26, 2026
            | Arrived | Departed | Lat | Lon | Location | Notes | Source | Status |
            | --- | --- | --- | --- | --- | --- | --- | --- |
            | 9:00 AM | - | 18 | -66 | Cafe | - | automatic | confirmed |
            ## Monday, October 26, 2026
            | Time | Latitude | Longitude | Speed | Altitude | Accuracy | Outlier |
            | --- | --- | --- | --- | --- | --- | --- |
            | 9:00:15 AM | 18 | -66 | - | - | 5 m | Yes |
        """)
        assertTrue(result.errors.toString(),result.errors.isEmpty())
        assertNull(result.visits.single().departedAt);assertNull(result.visits.single().notes)
        val point=result.points.single();assertEquals(Instant.parse("2026-10-26T13:00:15Z"),point.time)
        assertNull(point.speed);assertNull(point.altitude);assertEquals(5.0,point.accuracy,0.0);assertTrue(point.outlier)
    }
    @Test fun localeAndZoneAreExplicitRatherThanAssumingUsDatesOrUtc() {
        val result=parse("""
            ## lundi 26 octobre 2026
            | Time | Lat | Lon |
            | --- | --- | --- |
            | 21:15:30 | 48.85 | 2.35 |
        """,Locale.FRANCE,ZoneId.of("Europe/Paris"))
        assertTrue(result.errors.toString(),result.errors.isEmpty())
        assertEquals(Instant.parse("2026-10-26T20:15:30Z"),result.points.single().time)
    }
    @Test fun nonexistentDstClockIsRejectedAndAnOverlapUsesItsFirstOffset() {
        val result=parse("""
            ## Sunday, March 8, 2026
            | Time | Lat | Lon |
            | --- | --- | --- |
            | 2:30:00 AM | 40 | -74 |
            | 3:30:00 AM | 40 | -74 |
            ## Sunday, November 1, 2026
            | Time | Lat | Lon |
            | --- | --- | --- |
            | 1:30:00 AM | 40 | -74 |
        """,zone=ZoneId.of("America/New_York"))
        assertEquals(1,result.errors.size);assertTrue(result.errors.single().contains("does not exist"))
        assertEquals(listOf(Instant.parse("2026-03-08T07:30:00Z"),Instant.parse("2026-11-01T05:30:00Z")),result.points.map {it.time})
    }
    @Test fun modernEscapesAndOpenVisitsRoundTripWithoutDroppingBackslashesOrCells() {
        val visit=Visit(coordinate=Coordinate(18.0,-66.0),arrivedAt=Instant.parse("2026-10-26T12:00:00Z"),
            name="Folder \\ | Cafe",address="Ends in "+'\\',notes="Pipe |, slash \\\\ and\nNew line")
        val result=parse(HistoryExporter.render(ExportFormat.markdown,listOf(visit),emptyList()))
        assertTrue(result.errors.toString(),result.errors.isEmpty())
        assertEquals(visit.name,result.visits.single().name);assertEquals(visit.address,result.visits.single().address)
        assertEquals(visit.notes,result.visits.single().notes);assertNull(result.visits.single().departedAt)
    }
    @Test fun invalidRowsAndSectionFieldsCannotBecomePartialOpenVisitsOrReuseOldDates() {
        val result=parse("""
            ## Monday, October 26, 2026
            ### Bad departure
            - **Arrived:** 9:00 AM
            - **Departed:** invalid
            - **Coordinates:** 18, -66
            ## Invalid date
            ### Must not inherit yesterday
            - **Arrived:** 9:00 AM
            - **Coordinates:** 18, -66
            ## Duplicate columns
            | Arrived | Lat | Latitude | Lon |
            | 2026-10-26T12:00:00Z | 18 | 19 | -66 |
            ## Visits
            | Arrived | Departed | Lat | Lon |
            | --- | --- | --- | --- |
            | 2026-10-26T12:00:00Z | - | 18 |
            | 2026-10-26T13:00:00Z | - | 18 | -66 |
        """)
        assertEquals(1,result.visits.size);assertEquals(Instant.parse("2026-10-26T13:00:00Z"),result.visits.single().arrivedAt)
        assertTrue(result.errors.size>=3);assertTrue(result.errors.any {it.contains("Column count")});assertTrue(result.errors.any {it.contains("column aliases")})
    }
    @Test fun contentDetectionRecognizesLegacySectionsAndKeepsCsvNotesInCsv() {
        assertEquals(ExportFormat.markdown,HistoryImporter.detect("\uFEFF# Export\n### Home\n- **Arrived:** 9:00 AM"))
        assertEquals(ExportFormat.markdown,HistoryImporter.detect("|Time|Lat|Lon|\n|---|---|---|"))
        assertEquals(ExportFormat.json,HistoryImporter.detect(" \uFEFF {\"visits\":[]}"))
        val json="""{"visits":[{"latitude":18,"longitude":-66,"arrivedAt":"2026-10-26T12:00:00Z"}]}"""
        val prefixed=" \uFEFF "+json
        assertEquals(1,HistoryImporter.parse(prefixed,HistoryImporter.detect(prefixed)).visits.size)
        assertEquals(ExportFormat.csv,HistoryImporter.detect("arrived_at,notes\n2026-10-26T12:00:00Z,\"\n- **Arrived:** note\""))
    }
    @Test fun quotedCsvHeadersAreRecognizedBeforeMarkdownInsideNotes() {
        val csv=""""arrived_at","notes"
            | Time | Lat | Lon |
        """.trimIndent()
        assertEquals(ExportFormat.csv,HistoryImporter.detect(csv))
    }
    @Test fun historicalPositionalPointRowsDoNotRequireAHeader() {
        val text="## Monday, October 26, 2026\n| 9:00:00 AM | 18 | -66 | 1.5 m/s | 10 m | 5 m | Yes |"
        assertEquals(ExportFormat.markdown,HistoryImporter.detect(text))
        val point=parse(text).points.single()
        assertEquals(Instant.parse("2026-10-26T13:00:00Z"),point.time)
        assertEquals(1.5,point.speed!!,0.0);assertEquals(10.0,point.altitude!!,0.0);assertTrue(point.outlier)
    }
    @Test fun reimportAndConversionToJsonKeepStableLegacyIdentities() {
        val text="""
            ## Monday, October 26, 2026
            | Time | Lat | Lon |
            | --- | --- | --- |
            | 9:00:00 AM | 18 | -66 |
        """
        val first=parse(text);assertEquals(first,parse(text))
        val json=HistoryExporter.render(ExportFormat.json,first.visits,first.points)
        assertEquals(first.points,HistoryImporter.parse(json,ExportFormat.json).points)
    }
}
