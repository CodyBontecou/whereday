package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import com.google.gson.GsonBuilder
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant

/** Real Android files for the SwiftData commit acceptance harness; no journal/device is used. */
class PlaceProvenanceInteropTest {
    @Test fun exportsNearbySelectionsThroughBothRenderersAndAllBackupImportFormats()=runBlocking {
        val at=Instant.parse("2026-10-29T13:00:00Z")
        val origin=Coordinate(18.0,-66.0)
        val suggestion=NearbyPlaceSuggestion("synthetic-business","Café QA | 北","Synthetic street",Coordinate(18.0001,-66.0),11.068)
        val cases=mutableListOf<Map<String,Any?>>()
        for(corrected in listOf(false,true)) {
            val original=Visit("original",origin,at,at.plusSeconds(60),"Detected QA","Original street",
                detectedCoordinate=origin,detectedName="Detected QA",detectedAddress="Original street",countryCode="PR")
            val draft=VisitDraft("original","Typed QA","Typed street","One\nTwo | <note>",origin,at,at.plusSeconds(60)).selectNearby(suggestion,"geoapify")
            val visit=draft.build(original.takeIf {corrected},at.plusSeconds(120))
            assertEquals("geoapify",visit.placeSource);assertEquals(suggestion.distanceMeters,visit.placeDistanceMeters!!,0.0)
            val options=ExportOptions(kind=DataKind.visits)
            val source=object:ExportRecordSource {
                override suspend fun visits(consume:suspend (Visit)->Unit) {consume(visit)}
                override suspend fun points(consume:suspend (TrackPoint)->Unit) {error("Visit-only export must not read points")}
            }
            for(format in listOf(ExportFormat.json,ExportFormat.csv,ExportFormat.markdown)) for(renderer in listOf("list","streaming")) {
                val text=if(renderer=="list") HistoryExporter.render(format,listOf(visit),emptyList(),options,at)
                    else StringWriter().also {StreamingHistoryExporter.write(it,format,source,options,at)}.toString()
                val parsed=HistoryImporter.parse(text,format)
                assertTrue(parsed.errors.toString(),parsed.errors.isEmpty());assertTrue(parsed.points.isEmpty())
                val imported=parsed.visits.single()
                assertEquals(visit.copy(countryCode=null),imported.copy(id=visit.id))
                cases+=mapOf("name" to "${if(corrected) "corrected" else "manual"}-$renderer-${format.name}","format" to format.name,"text" to text,
                    "expected" to mapOf("latitude" to visit.coordinate.latitude,"longitude" to visit.coordinate.longitude,
                        "arrivedAt" to visit.arrivedAt.toString(),"departedAt" to visit.departedAt?.toString(),"name" to visit.name,"address" to visit.address,"notes" to visit.notes,
                        "source" to visit.source.name,"confirmation" to visit.confirmation.name,"confirmedAt" to visit.confirmedAt?.toString(),"updatedAt" to visit.updatedAt?.toString(),
                        "originalLatitude" to visit.originalCoordinate?.latitude,"originalLongitude" to visit.originalCoordinate?.longitude,"originalName" to visit.originalName,"originalAddress" to visit.originalAddress,
                        "detectedLatitude" to visit.detectedCoordinate?.latitude,"detectedLongitude" to visit.detectedCoordinate?.longitude,"detectedName" to visit.detectedName,"detectedAddress" to visit.detectedAddress,
                        "placeSource" to visit.placeSource,"placeDistanceMeters" to visit.placeDistanceMeters))
            }
        }
        assertEquals(12,cases.size)
        val output=Path.of("build/place-provenance/android-exports.json")
        Files.createDirectories(output.parent)
        Files.writeString(output,GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create().toJson(cases)+"\n")
        Unit
    }
}
