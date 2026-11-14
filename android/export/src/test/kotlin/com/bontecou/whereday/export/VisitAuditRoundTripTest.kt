package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.*
import java.util.Locale

class VisitAuditRoundTripTest {
    private val time=Instant.parse("2026-10-28T12:00:00.123Z")
    private val original=Visit(coordinate=Coordinate(18.0,-66.0),arrivedAt=time,departedAt=time.plusSeconds(60),name="Café | 北",address="Current",notes="One\nTwo",
        source=VisitSource.manual,confirmation=Confirmation.corrected,confirmedAt=time.plusSeconds(1),updatedAt=time.plusSeconds(2),
        originalCoordinate=Coordinate(18.1,-66.1),originalName="Original <br> | \\ 北",originalAddress="Old\nRoad",
        detectedCoordinate=Coordinate(.0000001,-.00001),detectedName="",detectedAddress="-",placeSource="userEntered",placeDistanceMeters=42.25)
    private fun csv(metadata:String)= "arrived_at,departed_at,latitude,longitude,location_name,notes,source,confirmation_status,original_location_name,visit_metadata\n"+
        "${time},${time.plusSeconds(60)},18.0,-66.0,Current,Kept,automatic,unconfirmed,Legacy,\"${metadata.replace("\"","\"\"")}\"\n"
    @Test fun allRepresentableAuditFieldsRoundTripThroughJsonCsvAndMarkdown() {
        for(format in listOf(ExportFormat.json,ExportFormat.csv,ExportFormat.markdown)) {
            val text=HistoryExporter.render(format,listOf(original),emptyList(),ExportOptions(kind=DataKind.visits))
            val result=HistoryImporter.parse(text,format)
            assertTrue(result.errors.toString(),result.errors.isEmpty())
            val represented=if(format==ExportFormat.markdown) original.copy(arrivedAt=original.arrivedAt.truncatedTo(java.time.temporal.ChronoUnit.MINUTES),departedAt=original.departedAt?.truncatedTo(java.time.temporal.ChronoUnit.MINUTES)) else original
            assertEquals("$format",represented,result.visits.single().copy(id=original.id))
        }
    }
    @Test fun metadataCannotReplaceIdentityCurrentCoordinatesArrivalOrUserNotes() {
        val before=HistoryImporter.parse(csv("-"),ExportFormat.csv).visits.single()
        val metadata="""{"source":"manual","confirmationStatus":"corrected","id":"00000000-0000-0000-0000-000000000099","latitude":"0","longitude":"0","arrivedAt":"2000-01-01T00:00:00.000Z","notes":"Replaced","unknown":"future"}"""
        val after=HistoryImporter.parse(csv(metadata),ExportFormat.csv).visits.single()
        assertEquals(before.id,after.id);assertEquals(before.coordinate,after.coordinate);assertEquals(before.arrivedAt,after.arrivedAt)
        assertEquals("Kept",after.notes);assertEquals(VisitSource.manual,after.source);assertEquals(Confirmation.corrected,after.confirmation)
    }
    @Test fun presentMetadataOverridesLegacyAuditColumnsAndInvalidOptionalValuesAreAbsent() {
        val empty=HistoryImporter.parse(csv("{}"),ExportFormat.csv).visits.single()
        assertNull(empty.originalName);assertEquals(VisitSource.automatic,empty.source)
        val metadata="""{"confirmedAt":"invalid","updatedAt":"2026-10-28T12:00:00Z","originalLatitude":"invalid","originalLongitude":"1","placeDistanceMeters":"invalid","originalLocationName":""}"""
        val value=HistoryImporter.parse(csv(metadata),ExportFormat.csv).visits.single()
        assertNull(value.confirmedAt);assertNull(value.updatedAt);assertNull(value.originalCoordinate);assertNull(value.placeDistanceMeters);assertEquals("",value.originalName)
    }
    @Test fun malformedNonStringDuplicateAndTrailingMetadataRejectTheRow() {
        for(metadata in listOf("{","[]","null","{\"source\":null}","{\"source\":true}","{\"future\":{}}","{\"source\":\"manual\",\"source\":\"automatic\"}","{} trailing", """{"originalLocationName":"\ud800"}""", """{"future":"\udc00"}""")) {
            try {HistoryImporter.parse(csv(metadata),ExportFormat.csv);fail("Accepted malformed metadata")} catch(_:IllegalArgumentException) {}
        }
    }
    @Test fun metadataSelectionDoesNotLeakDisabledCoordinateNameOrAddressFields() {
        val options=ExportOptions(kind=DataKind.visits,visitCoordinates=false,visitName=false,visitAddress=false)
        val objectValue=JsonParser.parseString(VisitAudit.json(original,options)).asJsonObject
        assertEquals(setOf("source","confirmationStatus","confirmedAt","updatedAt","placeSource","placeDistanceMeters"),objectValue.keySet())
        assertEquals("manual",objectValue["source"].asString)
    }
    @Test fun actualSwiftMarkdownImportsRetainTheSelectedAuditPayload() {
        val cases=requireNotNull(javaClass.getResourceAsStream("/ios-history-tables.json")).bufferedReader().use {JsonParser.parseReader(it).asJsonObject["cases"].asJsonArray}
        var checked=0
        for(entry in cases) {
            val c=entry.asJsonObject
            if(c["format"].asString!="markdown" || c["kind"].asString=="points" || c["visits"].asJsonArray.size()==0 || !c["options"].asJsonObject["visitCoordinates"].asBoolean) continue
            val input=c["visits"].asJsonArray[0].asJsonObject;val options=c["options"].asJsonObject
            val result=HistoryImporter.parse(c["text"].asString,ExportFormat.markdown,Locale.forLanguageTag((c.get("localeTag") ?: c["locale"]).asString.replace('_','-')),ZoneId.of(c["zone"].asString))
            assertTrue(c["name"].asString+result.errors,result.errors.isEmpty())
            val visit=result.visits.first();assertEquals(VisitSource.manual,visit.source);assertEquals(Confirmation.corrected,visit.confirmation)
            assertEquals(Coordinate(input["originalLatitude"].asDouble,input["originalLongitude"].asDouble),visit.originalCoordinate)
            assertEquals(Coordinate(input["detectedLatitude"].asDouble,input["detectedLongitude"].asDouble),visit.detectedCoordinate)
            assertEquals(input["originalName"].asString.takeIf {options["visitName"].asBoolean},visit.originalName)
            assertEquals(input["detectedAddress"].asString.takeIf {options["visitAddress"].asBoolean},visit.detectedAddress)
            assertEquals(Instant.parse(input["confirmedAt"].asString),visit.confirmedAt);assertEquals(42.25,visit.placeDistanceMeters!!,0.0);checked++
        }
        assertEquals(96,checked)
    }
}
