package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import com.google.gson.*
import org.junit.Assert.*
import org.junit.Test
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.io.StringWriter
import kotlinx.coroutines.runBlocking

/** Build-only synthetic files consumed by generate_history_import_fixtures.py --verify-android.
 * Expected values describe the fields each format can represent; UUIDs, session membership,
 * country resolution, and photo references are not part of the current iOS history contract.
 */
class HistoryExportInteropTest {
    @Test fun producesHistoryFilesForActualSwiftAcceptance()=runBlocking {
        val at=Instant.parse("2026-10-26T13:00:00.125Z")
        val visits=listOf(Visit(id="10000000-0000-0000-0000-000000000001",coordinate=Coordinate(18.123456,-66.123456),arrivedAt=at,departedAt=at.plusSeconds(3600),
            name="Café, \"北\" 🗺️",address="Synthetic address",notes="One\nTwo | \\ Three",source=VisitSource.automatic,confirmation=Confirmation.corrected,
            originalCoordinate=Coordinate(18.1,-66.1),originalName="Original",originalAddress="Original address",
            detectedCoordinate=Coordinate(18.11,-66.11),detectedName="Detected",detectedAddress="Detected address",
            confirmedAt=at.plusSeconds(1),updatedAt=at.plusSeconds(2),placeSource="appleMaps",placeDistanceMeters=42.25),
            Visit(id="10000000-0000-0000-0000-000000000002",coordinate=Coordinate(18.2,-66.2),arrivedAt=at.plusSeconds(7200),name="Ongoing",notes="null",source=VisitSource.manual))
        val points=listOf(TrackPoint(id="20000000-0000-0000-0000-000000000001",coordinate=Coordinate(18.123456,-66.123456),time=at,accuracy=5.25,altitude=-1.5,speed=1.75,outlier=true),
            TrackPoint(id="20000000-0000-0000-0000-000000000002",coordinate=Coordinate(18.2,-66.2),time=at.plusSeconds(1),accuracy=0.0))
        val cases=mutableListOf<Map<String,Any>>()
        val visitChoices=listOf("default" to ExportOptions(kind=DataKind.visits),
            "without-name" to ExportOptions(kind=DataKind.visits,visitName=false),
            "without-address" to ExportOptions(kind=DataKind.visits,visitAddress=false),
            "without-notes" to ExportOptions(kind=DataKind.visits,visitNotes=false),
            "without-duration" to ExportOptions(kind=DataKind.visits,visitDuration=false),
            "without-optional-fields" to ExportOptions(kind=DataKind.visits,visitName=false,visitAddress=false,visitNotes=false,visitDuration=false))
        val pointChoices=(0..15).map {mask->"fields-${mask.toString().padStart(2,'0')}" to ExportOptions(kind=DataKind.points,
            pointSpeed=mask and 1!=0,pointAltitude=mask and 2!=0,pointAccuracy=mask and 4!=0,pointOutlier=mask and 8!=0)}
        for(format in listOf(ExportFormat.json,ExportFormat.csv,ExportFormat.markdown))
        for((variant,options) in visitChoices+pointChoices+listOf("default","only-visits","only-points").map {it to ExportOptions()}) for(renderer in listOf("list","streaming")) {
            val kind=options.kind
            val selectedVisits=if(variant=="only-points") emptyList() else visits
            val selectedPoints=if(variant=="only-visits") emptyList() else points
            val source=object:ExportRecordSource {
                override suspend fun visits(consume:suspend (Visit)->Unit) {selectedVisits.forEach {consume(it)}}
                override suspend fun points(consume:suspend (TrackPoint)->Unit) {selectedPoints.forEach {consume(it)}}
            }
            val label="android-$renderer-${kind.name}-$variant-${format.name}"
            val text=if(renderer=="list") HistoryExporter.render(format,selectedVisits,selectedPoints,options,generatedAt=at) else
                StringWriter().also {StreamingHistoryExporter.write(it,format,source,options,at)}.toString()
            val imported=HistoryImporter.parse(text,format)
            assertTrue("$label: ${imported.errors}",imported.errors.isEmpty())
            val representedVisits=selectedVisits.map {it.copy(
                arrivedAt=if(format==ExportFormat.markdown) it.arrivedAt.truncatedTo(java.time.temporal.ChronoUnit.MINUTES) else it.arrivedAt,
                departedAt=if(format==ExportFormat.markdown) it.departedAt?.truncatedTo(java.time.temporal.ChronoUnit.MINUTES) else it.departedAt,
                name=it.name.takeIf {options.visitName},address=it.address.takeIf {options.visitAddress},notes=it.notes.takeIf {options.visitNotes},
                originalCoordinate=it.originalCoordinate,
                originalName=it.originalName.takeIf {options.visitName},
                originalAddress=it.originalAddress.takeIf {options.visitAddress},
                detectedCoordinate=it.detectedCoordinate,
                detectedName=it.detectedName.takeIf {options.visitName},
                detectedAddress=it.detectedAddress.takeIf {options.visitAddress},
                confirmedAt=it.confirmedAt,updatedAt=it.updatedAt,
                placeSource=it.placeSource,placeDistanceMeters=it.placeDistanceMeters)}
            fun measurement(value:Double?)=if(format==ExportFormat.markdown) value?.let {XmlExportValues.number(it,1).toDouble()} else value
            val representedPoints=selectedPoints.map {it.copy(time=if(format==ExportFormat.markdown) it.time.truncatedTo(java.time.temporal.ChronoUnit.SECONDS) else it.time,
                speed=measurement(it.speed).takeIf {options.pointSpeed},altitude=measurement(it.altitude).takeIf {options.pointAltitude},
                accuracy=if(options.pointAccuracy) measurement(it.accuracy)!! else 0.0,outlier=options.pointOutlier && it.outlier)}
            if(kind!=DataKind.points) {
                assertEquals(label,representedVisits.size,imported.visits.size)
                assertEquals(label,representedVisits,imported.visits.zip(representedVisits).map {(actual,original)->actual.copy(id=original.id)})
            }
            if(kind!=DataKind.visits) {
                assertEquals(label,representedPoints.size,imported.points.size)
                assertEquals(label,representedPoints,imported.points.zip(representedPoints).map {(actual,original)->actual.copy(id=original.id)})
            }
            val expectedVisits=imported.visits.map {v->mapOf(
                "latitude" to v.coordinate.latitude,"longitude" to v.coordinate.longitude,"arrivedAt" to v.arrivedAt.toString(),"departedAt" to v.departedAt?.toString(),
                "customName" to null,"locationName" to v.name,"address" to v.address,"notes" to v.notes,"source" to v.source.name,"confirmationStatus" to v.confirmation.name,
                "confirmedAt" to v.confirmedAt?.toString(),"updatedAt" to v.updatedAt?.toString(),"originalLatitude" to v.originalCoordinate?.latitude,"originalLongitude" to v.originalCoordinate?.longitude,
                "originalLocationName" to v.originalName,"originalAddress" to v.originalAddress,"detectedLatitude" to v.detectedCoordinate?.latitude,"detectedLongitude" to v.detectedCoordinate?.longitude,
                "detectedLocationName" to v.detectedName,"detectedAddress" to v.detectedAddress,"placeSource" to v.placeSource,"placeDistanceMeters" to v.placeDistanceMeters)
            }
            val expectedPoints=imported.points.map {p->mapOf("latitude" to p.coordinate.latitude,"longitude" to p.coordinate.longitude,"timestamp" to p.time.toString(),
                "horizontalAccuracy" to p.accuracy,"altitude" to p.altitude,"speed" to p.speed,"isOutlier" to p.outlier)}
            val expected:Any=when(kind) {DataKind.visits->expectedVisits;DataKind.points->expectedPoints;DataKind.all->mapOf("visits" to expectedVisits,"points" to expectedPoints)}
            cases+=mapOf("name" to label,"entryPoint" to "unified","format" to format.name,"subject" to kind.name,"text" to text,"androidExpected" to expected)
        }
        val output=Path.of("build/history-interop/android-exports.json")
        Files.createDirectories(output.parent)
        Files.writeString(output,GsonBuilder().serializeNulls().setPrettyPrinting().disableHtmlEscaping().create().toJson(cases)+"\n")
        assertEquals(150,cases.size)
    }
}
