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
class HistoryTableParityTest(private val name:String,private val fixture:JsonObject) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}") fun cases():Collection<Array<Any>> =
            requireNotNull(HistoryTableParityTest::class.java.getResourceAsStream("/ios-history-tables.json")).bufferedReader().use {reader->
                JsonParser.parseReader(reader).asJsonObject["cases"].asJsonArray.map {arrayOf(it.asJsonObject["name"].asString,it.asJsonObject)}
            }
    }
    @Test fun historyRenderersMatchActualSwift()=runBlocking {
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
        val options=ExportOptions(zone=java.time.ZoneId.of(fixture["zone"].asString),kind=DataKind.valueOf(fixture["kind"].asString),visitCoordinates=flag("visitCoordinates"),visitDuration=flag("visitDuration"),visitName=flag("visitName"),visitAddress=flag("visitAddress"),visitNotes=flag("visitNotes"),pointAltitude=flag("pointAltitude"),pointSpeed=flag("pointSpeed"),pointAccuracy=flag("pointAccuracy"),pointOutlier=flag("pointOutlier"))
        val source=object:ExportRecordSource {
            override suspend fun visits(consume:suspend (Visit)->Unit) {visits.sortedBy {it.arrivedAt}.forEach {consume(it)}}
            override suspend fun points(consume:suspend (TrackPoint)->Unit) {points.sortedBy {it.time}.forEach {consume(it)}}
        }
        val generatedAt=Instant.parse("2026-10-28T12:00:00Z")
        val format=ExportFormat.valueOf(fixture["format"].asString)
        val expected=if(format==ExportFormat.json) fixture["expected"] else fixture["text"]
        fun actual(text:String)=if(format==ExportFormat.json) JsonParser.parseString(text) else com.google.gson.JsonPrimitive(if(format==ExportFormat.markdown) text.replace(Regex("(?m)^\\*\\*Export Date:\\*\\* .*$"),"**Export Date:** <GENERATED>") else text)
        val previousLocale=Locale.getDefault()
        try {
            for(locale in if(format==ExportFormat.markdown) listOf(Locale.forLanguageTag((fixture.get("localeTag") ?: fixture["locale"]).asString.replace('_','-'))) else listOf(Locale.US,Locale.GERMANY,Locale.forLanguageTag("ar-EG"))) {
                Locale.setDefault(locale)
                assertEquals("$name/$locale/list",expected,HistoryExporter.render(format,visits,points,options,generatedAt).let(::actual))
                val output=StringWriter();val counts=StreamingHistoryExporter.write(output,format,source,options,generatedAt)
                assertEquals("$name/$locale/streaming",expected,actual(output.toString()))
                assertEquals(if(options.kind==DataKind.points) 0L else visits.size.toLong(),counts.visits)
                assertEquals(if(options.kind==DataKind.visits) 0L else points.size.toLong(),counts.points)
            }
        } finally {Locale.setDefault(previousLocale)}
    }
}
