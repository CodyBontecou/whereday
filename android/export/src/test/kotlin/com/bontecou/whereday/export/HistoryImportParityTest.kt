package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import com.google.gson.*
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.time.*
import java.util.Locale

/** Expectations are produced by compiling the complete repository ImportService.swift. */
@RunWith(Parameterized::class)
class HistoryImportParityTest(private val name:String,private val fixture:JsonObject) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}") fun cases():List<Array<Any>> =
            requireNotNull(HistoryImportParityTest::class.java.getResourceAsStream("/ios-history-import.json"))
                .bufferedReader().use {reader->JsonParser.parseReader(reader).asJsonObject["cases"].asJsonArray.map {
                    arrayOf(it.asJsonObject["name"].asString,it.asJsonObject)
                }}
        private val gson=GsonBuilder().serializeNulls().create()
    }
    @Test fun importsMatchSwiftAndSurviveAndroidJsonConversion() {
        val text=fixture["text"].asString
        val format=ExportFormat.valueOf(fixture["format"].asString)
        assertEquals(name,format,HistoryImporter.detect(text))
        if(fixture.has("expectedError")) {
            assertThrows(name,IllegalArgumentException::class.java) {HistoryImporter.parse(text,format,Locale.US,ZoneId.of("America/Puerto_Rico"))}
            return
        }
        val parsed=HistoryImporter.parse(text,format,Locale.US,ZoneId.of("America/Puerto_Rico"))
        val actual=if(fixture["subject"].asString=="visits") parsed.copy(points=emptyList()) else parsed.copy(visits=emptyList())
        assertTrue("$name: ${actual.errors}",actual.errors.isEmpty())
        val expectedVisits=fixture.getAsJsonArray("expectedVisits") ?: JsonArray()
        val expectedPoints=fixture.getAsJsonArray("expectedPoints") ?: JsonArray()
        for(element in expectedVisits) element.asJsonObject.apply {
            remove("customName") // iOS exposes this separately; no fixture sets it.
            if(get("source").isJsonNull) addProperty("source","imported")
            if(get("confirmationStatus").isJsonNull) addProperty("confirmationStatus","unconfirmed")
            normalizeTimes(this,listOf("arrivedAt","departedAt","confirmedAt","updatedAt"))
        }
        for(element in expectedPoints) normalizeTimes(element.asJsonObject,listOf("timestamp"))
        assertEquals(name,expectedVisits,gson.toJsonTree(actual.visits.map(::visit)))
        assertEquals(name,expectedPoints,gson.toJsonTree(actual.points.map(::point)))
        assertEquals(name,parsed,HistoryImporter.parse(text,format,Locale.US,ZoneId.of("America/Puerto_Rico")))
        val converted=HistoryExporter.render(ExportFormat.json,actual.visits,actual.points,ExportOptions(from=Instant.MIN))
        assertEquals(name,actual,HistoryImporter.parse(converted,ExportFormat.json))
    }
    private fun normalizeTimes(row:JsonObject,keys:List<String>) {
        for(key in keys) row.get(key)?.takeUnless {it.isJsonNull}?.let {row.addProperty(key,Instant.parse(it.asString).toString())}
    }
    private fun visit(v:Visit)=mapOf("latitude" to v.coordinate.latitude,"longitude" to v.coordinate.longitude,
        "arrivedAt" to v.arrivedAt.toString(),"departedAt" to v.departedAt?.toString(),"locationName" to v.name,"address" to v.address,"notes" to v.notes,
        "source" to v.source.name,"confirmationStatus" to v.confirmation.name,"confirmedAt" to v.confirmedAt?.toString(),"updatedAt" to v.updatedAt?.toString(),
        "originalLatitude" to v.originalCoordinate?.latitude,"originalLongitude" to v.originalCoordinate?.longitude,"originalLocationName" to v.originalName,"originalAddress" to v.originalAddress,
        "detectedLatitude" to v.detectedCoordinate?.latitude,"detectedLongitude" to v.detectedCoordinate?.longitude,"detectedLocationName" to v.detectedName,"detectedAddress" to v.detectedAddress,
        "placeSource" to v.placeSource,"placeDistanceMeters" to v.placeDistanceMeters)
    private fun point(p:TrackPoint)=mapOf("latitude" to p.coordinate.latitude,"longitude" to p.coordinate.longitude,"timestamp" to p.time.toString(),
        "horizontalAccuracy" to p.accuracy,"altitude" to p.altitude,"speed" to p.speed,"isOutlier" to p.outlier)
}
