package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import javax.xml.parsers.DocumentBuilderFactory
import org.xml.sax.InputSource
import java.io.StringReader

class ExportContractTest {
    private val start = Instant.parse("2026-10-26T12:00:00Z")
    private val visit = Visit(coordinate=Coordinate(18.0,-66.0),arrivedAt=start,departedAt=start.plusSeconds(3600),name="Cafe, \"North\"",notes="One\nTwo | Three",source=VisitSource.manual)
    private val points = listOf(TrackPoint(coordinate=Coordinate(18.0,-66.0),time=start,accuracy=5.0,speed=1.5,altitude=9.0),TrackPoint(coordinate=Coordinate(18.001,-66.0),time=start.plusSeconds(900),accuracy=8.0,outlier=true))
    @Test fun supportedImportsRoundTripCoordinatesTextAndTimes() {
        for(format in listOf(ExportFormat.json,ExportFormat.csv,ExportFormat.markdown)) {
            val imported=HistoryImporter.parse(HistoryExporter.render(format,listOf(visit),points,generatedAt=start),format)
            assertTrue("$format: ${imported.errors}",imported.errors.isEmpty())
            assertEquals(visit.coordinate,imported.visits.single().coordinate)
            assertEquals(visit.name,imported.visits.single().name)
            assertEquals(visit.notes,imported.visits.single().notes)
            assertEquals(visit.arrivedAt,imported.visits.single().arrivedAt)
            assertEquals(points.map { it.time },imported.points.map { it.time })
            assertEquals(points.map { it.outlier },imported.points.map { it.outlier })
        }
    }
    @Test fun correctionMetadataSurvivesJSON() {
        val corrected=visit.correct(Coordinate(19.0,-67.0),"Corrected", "Address",start.plusSeconds(1))
        val imported=HistoryImporter.parse(HistoryExporter.render(ExportFormat.json,listOf(corrected),emptyList()),ExportFormat.json).visits.single()
        assertEquals(visit.coordinate,imported.originalCoordinate)
        assertEquals(visit.name,imported.originalName)
        assertEquals(Confirmation.corrected,imported.confirmation)
    }
    @Test fun optionalFieldsAreOmittedAndPointsOnlyProtocolsDoNotEmitVisits() {
        val options=ExportOptions(visitCoordinates=false,visitName=false,pointAltitude=false)
        val json=JsonParser.parseString(HistoryExporter.render(ExportFormat.json,listOf(visit),points,options)).asJsonObject
        assertFalse(json["visits"].asJsonArray[0].asJsonObject.has("latitude"))
        assertFalse(json["points"].asJsonArray[0].asJsonObject.has("altitude"))
        val owntracks=JsonParser.parseString(HistoryExporter.render(ExportFormat.owntracks,listOf(visit),points)).asJsonArray
        assertEquals(2,owntracks.size())
        assertEquals(5,owntracks[0].asJsonObject["vel"].asInt)
        assertEquals("IM",owntracks[0].asJsonObject["tid"].asString)
    }
    @Test fun xmlFormatsAreWellFormedWithEscapedUserTextAndGPXGapSplit() {
        val factory=DocumentBuilderFactory.newInstance().apply { isNamespaceAware=true; setFeature("http://apache.org/xml/features/disallow-doctype-decl",true) }
        for(format in listOf(ExportFormat.gpx,ExportFormat.kml)) {
            val xml=HistoryExporter.render(format,listOf(visit.copy(name="A & B <C>")),points)
            val doc=factory.newDocumentBuilder().parse(InputSource(StringReader(xml)))
            if(format==ExportFormat.gpx) {
                assertEquals(2,doc.getElementsByTagNameNS("http://www.topografix.com/GPX/1/1","trkseg").length)
                assertEquals(2,doc.getElementsByTagNameNS("https://isome.isolated.tech/gpx/1.0","horizontalAccuracy").length)
                assertEquals(1,doc.getElementsByTagNameNS("https://isome.isolated.tech/gpx/1.0","departedAt").length)
            }
        }
    }
    @Test fun geoJSONUsesLongitudeBeforeLatitude() {
        val feature=JsonParser.parseString(HistoryExporter.render(ExportFormat.geojson,listOf(visit),emptyList())).asJsonObject["features"].asJsonArray[0].asJsonObject
        assertEquals(-66.0,feature["geometry"].asJsonObject["coordinates"].asJsonArray[0].asDouble,0.0)
    }
    @Test fun csvPreservesCRLFQuotesTrailingEmptiesAndBOM() {
        assertEquals(listOf(listOf("a","b",""),listOf("x\r\ny","say \"hello\"","")),CsvReader.read("\uFEFFa,b,\r\n\"x\r\ny\",\"say \"\"hello\"\"\",\r\n"))
        assertThrows(IllegalArgumentException::class.java) { CsvReader.read("a,\"unfinished") }
    }
    @Test fun wrappingTimeFilterAndQualityFiltersAreApplied() {
        val options=ExportOptions(startTime=LocalTime.of(22,0),endTime=LocalTime.of(6,0),zone=ZoneOffset.UTC,excludeOutliers=true)
        assertFalse(options.includes(start))
        assertTrue(options.includes(Instant.parse("2026-10-26T23:00:00Z")))
        assertTrue(options.points(points).isEmpty())
    }
    @Test fun filenamePlanningRejectsTraversalAndContainsUntrustedTokens() {
        val date=LocalDate.parse("2026-10-26")
        assertEquals("2026/2026-10/Daily Track - 2026-10-26.md",FilenamePlanner.path("{year}/{year}-{month}/Daily Track - {date}",ExportFormat.markdown,date))
        assertThrows(IllegalArgumentException::class.java) { FilenamePlanner.path("../secret",ExportFormat.json,date) }
        assertFalse(FilenamePlanner.path("{name}",ExportFormat.json,date,title="../../secret").contains('/'))
        assertTrue(FilenamePlanner.dayStable("{year}/History - {time}").contains("{date}"))
    }
    @Test fun malformedRowsAreReportedAndValidRowsRetained() {
        val result=HistoryImporter.parse("timestamp,latitude,longitude\n2026-10-26T12:00:00Z,18,-66\n2026-10-26T12:01:00Z,nonsense,-66",ExportFormat.csv)
        assertEquals(1,result.points.size)
        assertEquals(1,result.errors.size)
    }
    @Test fun longUnicodeNamesStayValidWithinFilesystemByteLimitsAndNumericTokensStayStable() {
        val date=LocalDate.parse("2026-10-27")
        val path=FilenamePlanner.path("{name}",ExportFormat.json,date,title="🗺️".repeat(200))
        assertTrue(Charsets.UTF_8.newEncoder().canEncode(path))
        assertTrue(path.toByteArray(Charsets.UTF_8).size<=185)
        val previous=java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.forLanguageTag("ar"))
            assertEquals("10-27.json",FilenamePlanner.path("{month}-{dayNumber}",ExportFormat.json,date))
        } finally {java.util.Locale.setDefault(previous)}
    }
    @Test fun repeatedLegacyImportsHaveStableIdentities() {
        val text=HistoryExporter.render(ExportFormat.json,listOf(visit),points)
        val first=HistoryImporter.parse(text,ExportFormat.json)
        val second=HistoryImporter.parse(text,ExportFormat.json)
        assertEquals(first.visits.map { it.id },second.visits.map { it.id })
        assertEquals(first.points.map { it.id },second.points.map { it.id })
        assertEquals(first.points.size,first.points.map { it.id }.distinct().size)
    }
}
