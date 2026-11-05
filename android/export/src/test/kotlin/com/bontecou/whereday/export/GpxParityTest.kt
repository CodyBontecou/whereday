package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import java.io.StringWriter
import java.time.Instant
import java.util.Locale

@RunWith(Parameterized::class)
class GpxParityTest(private val name:String,private val fixture:JsonObject) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}") fun cases():Collection<Array<Any>> =
            requireNotNull(GpxParityTest::class.java.getResourceAsStream("/ios-gpx.json")).bufferedReader().use {reader->
                JsonParser.parseReader(reader).asJsonObject["cases"].asJsonArray.map {arrayOf(it.asJsonObject["name"].asString,it.asJsonObject)}
            }
    }
    @Test fun listAndOrderedStreamingGpxMatchActualSwiftBytesAcrossLocales()=runBlocking {
        val flags=fixture["options"].asJsonObject
        fun flag(key:String)=flags[key].asBoolean
        fun JsonObject.text(key:String)=get(key)?.takeUnless {it.isJsonNull}?.asString
        fun JsonObject.number(key:String)=get(key)?.takeUnless {it.isJsonNull}?.asDouble
        val visits=fixture["visits"].asJsonArray.map {entry->val v=entry.asJsonObject
            Visit(coordinate=Coordinate(v["latitude"].asDouble,v["longitude"].asDouble),arrivedAt=Instant.parse(v["arrivedAt"].asString),departedAt=v.text("departedAt")?.let(Instant::parse),name=v.text("name"),address=v.text("address"),notes=v.text("notes"),
                source=VisitSource.valueOf(v.text("source") ?: "automatic"),confirmation=Confirmation.valueOf(v.text("confirmation") ?: "unconfirmed"),
                confirmedAt=v.text("confirmedAt")?.let(Instant::parse),updatedAt=v.text("updatedAt")?.let(Instant::parse),
                originalCoordinate=v.number("originalLatitude")?.let {Coordinate(it,v.number("originalLongitude")!!)},originalName=v.text("originalName"),originalAddress=v.text("originalAddress"),
                detectedCoordinate=v.number("detectedLatitude")?.let {Coordinate(it,v.number("detectedLongitude")!!)},detectedName=v.text("detectedName"),detectedAddress=v.text("detectedAddress"),
                placeSource=v.text("placeSource"),placeDistanceMeters=v.number("placeDistanceMeters"))}
        val points=fixture["points"].asJsonArray.map {entry->val p=entry.asJsonObject
            TrackPoint(coordinate=Coordinate(p["latitude"].asDouble,p["longitude"].asDouble),time=Instant.parse(p["time"].asString),accuracy=p["accuracy"].asDouble,altitude=p.number("altitude"),speed=p.number("speed"),outlier=p["outlier"].asBoolean)}
        val options=ExportOptions(kind=DataKind.valueOf(fixture["kind"].asString),visitCoordinates=flag("visitCoordinates"),visitDuration=flag("visitDuration"),visitName=flag("visitName"),visitAddress=flag("visitAddress"),visitNotes=flag("visitNotes"),pointAltitude=flag("pointAltitude"),pointSpeed=flag("pointSpeed"),pointAccuracy=flag("pointAccuracy"),pointOutlier=flag("pointOutlier"))
        val source=object:ExportRecordSource {
            override suspend fun visits(consume:suspend (Visit)->Unit) {visits.forEach {consume(it)}}
            override suspend fun points(consume:suspend (TrackPoint)->Unit) {points.forEach {consume(it)}}
        }
        val generatedAt=Instant.parse("2026-10-28T12:00:00Z")
        val expected=fixture["text"].asString
        val previousLocale=Locale.getDefault()
        try {
            for(locale in listOf(Locale.US,Locale.GERMANY,Locale.forLanguageTag("ar-EG"))) {
                Locale.setDefault(locale)
                assertEquals("$name/$locale/list",expected,HistoryExporter.render(ExportFormat.gpx,visits,points,options,generatedAt))
                val output=StringWriter();val counts=StreamingHistoryExporter.write(output,ExportFormat.gpx,source,options,generatedAt)
                assertEquals("$name/$locale/streaming",expected,output.toString())
                assertEquals(if(options.visitCoordinates) visits.size.toLong() else 0,counts.visits)
                assertEquals(points.size.toLong(),counts.points)
            }
        } finally {Locale.setDefault(previousLocale)}
    }
}
