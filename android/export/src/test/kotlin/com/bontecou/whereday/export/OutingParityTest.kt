package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.time.Instant

class OutingParityTest {
    @Test fun summaryJsonAndCsvMatchActualSwiftRendererFixtures()=runBlocking {
        val cases=requireNotNull(javaClass.getResourceAsStream("/ios-outings.json")).bufferedReader().use {JsonParser.parseReader(it).asJsonObject["cases"].asJsonArray}
        assertEquals(10,cases.size())
        fun compareJson(label:String,expected:com.google.gson.JsonElement,actual:com.google.gson.JsonObject) {
            val reference=expected.deepCopy().asJsonObject
            for((e,a) in reference["outings"].asJsonArray.zip(actual["outings"].asJsonArray)) {
                for((field,tolerance) in listOf("distanceMeters" to .005,"averageSpeedMetersPerSecond" to .0005)) {
                    val number=e.asJsonObject.remove(field);val observed=a.asJsonObject.remove(field)
                    if(number!=null) {assertNotNull(label,observed);assertEquals(label+"/"+field,number.asDouble,observed!!.asDouble,tolerance)} else assertNull(label,observed)
                }
            }
            assertEquals(label,reference,actual)
        }
        for(element in cases) {
            val c=element.asJsonObject
            fun optional(key:String)=c[key]?.takeUnless {it.isJsonNull}?.asString
            val start=Instant.parse(c["start"].asString);val end=optional("end")?.let(Instant::parse);val now=Instant.parse(c["now"].asString)
            val inferred=c["inferred"].asBoolean
            val session=RecordingSession(c["id"].asString,start,end,if(inferred) null else optional("title"),if(inferred) null else optional("notes"))
            val points=c["points"].asJsonArray.mapIndexed {i,p ->p.asJsonObject.let {TrackPoint("p$i",Coordinate(it["lat"].asDouble,it["lon"].asDouble),Instant.parse(it["time"].asString),5.0,outlier=it["outlier"].asBoolean)}}
            val builder=OutingExportSummaryBuilder();points.forEach(builder::add)
            val preliminary=builder.build(session,c["sequence"].asInt,inferred,now,0)
            val visits=c["visits"].asJsonArray.map {Visit(coordinate=Coordinate(0.0,0.0),arrivedAt=Instant.parse(it.asString))}.filter {it.arrivedAt in start..preliminary.effectiveEndAt}
            val summary=preliminary.copy(visitCount=visits.size.toLong())
            val source=object:ExportRecordSource {
                override suspend fun points(consume:suspend (TrackPoint)->Unit) {points.forEach {consume(it)}}
                override suspend fun visits(consume:suspend (Visit)->Unit) {visits.forEach {consume(it)}}
            }
            val csv=StringWriter();StreamingOutingExporter.write(csv,summary,source,ExportFormat.csv,generatedAt=now)
            assertEquals(c["name"].asString,c["expectedCSV"].asString,csv.toString())
            val json=StringWriter();StreamingOutingExporter.write(json,summary,source,ExportFormat.json,generatedAt=now)
            val actual=JsonParser.parseString(json.toString()).asJsonObject.apply {remove("exportDate")}
            compareJson(c["name"].asString,c["expectedJSON"],actual)
            val earlier=OutingExportSummaryBuilder().build(RecordingSession("earlier",start.minusSeconds(3600),start.minusSeconds(3500)),1,true,now,0)
            val pairCsv=StringWriter();StreamingOutingExporter.write(pairCsv,listOf(summary,earlier),source,ExportFormat.csv,generatedAt=now)
            assertEquals(c["name"].asString,c["expectedPairCSV"].asString,pairCsv.toString())
            val pairJson=StringWriter();StreamingOutingExporter.write(pairJson,listOf(summary,earlier),source,ExportFormat.json,generatedAt=now)
            compareJson(c["name"].asString,c["expectedPairJSON"],JsonParser.parseString(pairJson.toString()).asJsonObject.apply {remove("exportDate")})
        }
    }
}
