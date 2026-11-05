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
import java.time.ZoneId
import java.util.Locale

@RunWith(Parameterized::class)
class OutingMarkdownParityTest(private val name:String,private val c:JsonObject) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}") fun cases():Collection<Array<Any>> =
            requireNotNull(OutingMarkdownParityTest::class.java.getResourceAsStream("/ios-outing-markdown.json")).bufferedReader().use {reader->JsonParser.parseReader(reader).asJsonObject["cases"].asJsonArray.map {arrayOf(it.asJsonObject["name"].asString,it.asJsonObject)}}
    }
    @Test fun completePageAndBulkTableMatchActualSwift()=runBlocking {
        fun JsonObject.text(key:String)=get(key)?.takeUnless {it.isJsonNull}?.asString
        fun JsonObject.number(key:String)=get(key)?.takeUnless {it.isJsonNull}?.asDouble
        val start=Instant.parse(c["start"].asString);val end=c.text("end")?.let(Instant::parse);val now=Instant.parse(c["now"].asString);val inferred=c["inferred"].asBoolean
        val points=c["points"].asJsonArray.mapIndexed {i,entry->val p=entry.asJsonObject
            TrackPoint("p$i",Coordinate(p["lat"].asDouble,p["lon"].asDouble),Instant.parse(p["time"].asString),p.number("accuracy") ?: 5.0,p.number("altitude"),p.number("speed"),p["outlier"].asBoolean)}
        val preliminary=OutingExportSummaryBuilder().apply {points.forEach(::add)}.build(RecordingSession(c["id"].asString,start,end,if(inferred) null else c.text("title"),if(inferred) null else c.text("notes")),c["sequence"].asInt,inferred,now,0)
        val visits=c["visits"].asJsonArray.map {entry->if(entry.isJsonPrimitive) Visit(coordinate=Coordinate(0.0,0.0),arrivedAt=Instant.parse(entry.asString)) else entry.asJsonObject.let {v->
            Visit(coordinate=Coordinate(v.number("latitude") ?: 0.0,v.number("longitude") ?: 0.0),arrivedAt=Instant.parse(v["arrivedAt"].asString),departedAt=v.text("departedAt")?.let(Instant::parse),name=v.text("name"),address=v.text("address"),notes=v.text("notes"),source=VisitSource.valueOf(v.text("source") ?: "automatic"),confirmation=Confirmation.valueOf(v.text("confirmation") ?: "unconfirmed"),
                originalCoordinate=v.number("originalLatitude")?.let {Coordinate(it,v.number("originalLongitude")!!)},originalName=v.text("originalName"),originalAddress=v.text("originalAddress"),detectedCoordinate=v.number("detectedLatitude")?.let {Coordinate(it,v.number("detectedLongitude")!!)},detectedName=v.text("detectedName"),detectedAddress=v.text("detectedAddress"),confirmedAt=v.text("confirmedAt")?.let(Instant::parse),updatedAt=v.text("updatedAt")?.let(Instant::parse),placeSource=v.text("placeSource"),placeDistanceMeters=v.number("placeDistanceMeters"))
        }}.filter {it.arrivedAt in start..preliminary.effectiveEndAt}.sortedBy {it.arrivedAt}
        val summary=preliminary.copy(visitCount=visits.size.toLong())
        val earlier=OutingExportSummaryBuilder().build(RecordingSession("earlier",start.minusSeconds(3600),start.minusSeconds(3500)),1,true,now,0)
        val source=object:ExportRecordSource {
            override suspend fun visits(consume:suspend(Visit)->Unit) {visits.forEach {consume(it)}}
            override suspend fun points(consume:suspend(TrackPoint)->Unit) {points.sortedBy {it.time}.forEach {consume(it)}}
        }
        val flags=c.getAsJsonObject("options")
        fun flag(key:String)=flags?.get(key)?.asBoolean ?: true
        val options=ExportOptions(zone=ZoneId.of(c["zone"].asString),visitCoordinates=flag("visitCoordinates"),visitDuration=flag("visitDuration"),visitName=flag("visitName"),visitAddress=flag("visitAddress"),visitNotes=flag("visitNotes"),pointAltitude=flag("pointAltitude"),pointSpeed=flag("pointSpeed"),pointAccuracy=flag("pointAccuracy"),pointOutlier=flag("pointOutlier"))
        val outings=when(c["shape"].asString) {"empty"->emptyList();"pair"->listOf(summary,earlier);else->listOf(summary)}
        val locale=Locale.forLanguageTag((c.get("localeTag") ?: c["locale"]).asString.replace('_','-'))
        val out=StringWriter();val counts=StreamingOutingExporter.write(out,outings,source,ExportFormat.markdown,options,now,locale=locale)
        val text=out.toString().replace(Regex("(?m)^\\*\\*Export Date:\\*\\* .*$"),"**Export Date:** <GENERATED>")
        assertEquals(name,c["expected"].asString,text)
        assertEquals(outings.sumOf {it.visitCount},counts.visits);assertEquals(outings.sumOf {it.pointCount},counts.points)
    }
}
