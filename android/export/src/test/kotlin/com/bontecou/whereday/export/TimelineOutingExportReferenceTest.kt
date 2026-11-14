package com.bontecou.whereday.export

import com.bontecou.whereday.domain.*
import com.google.gson.*
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.StringWriter
import java.time.*

/** Current Swift global builder through JSON/CSV, without injecting summary IDs. */
class TimelineOutingExportReferenceTest {
    private fun JsonObject.instant(key:String)=get(key)?.takeUnless {it.isJsonNull}?.asLong?.let(Instant::ofEpochMilli)
    @Test fun builderThroughJsonAndCsvMatchesThirtyFourSwiftHistories()=runBlocking {
        val cases=javaClass.getResourceAsStream("/ios-timeline-statistics.json")!!.bufferedReader().use {JsonParser.parseReader(it).asJsonObject["cases"].asJsonArray}
        assertEquals(34,cases.size())
        for(raw in cases) {
            val input=raw.asJsonObject["input"].asJsonObject;val expected=raw.asJsonObject["expected"].asJsonObject
            val label=input["id"].asString;val now=input.instant("now")!!
            val sessions=input["sessions"].asJsonArray.map {it.asJsonObject.let {s->RecordingSession(s["id"].asString.lowercase(),s.instant("start")!!,s.instant("end"))}}
            val points=input["points"].asJsonArray.map {it.asJsonObject.let {p->TrackPoint(p["id"].asString.lowercase(),Coordinate(p["lat"].asDouble,p["lon"].asDouble),p.instant("time")!!,5.0,outlier=p["outlier"].asBoolean)}}.sortedBy {it.time}
            val rules=if(input["enabled"].asBoolean) InferenceRules(Duration.ofSeconds(input["gapSeconds"].asLong),Duration.ofSeconds(input["minimumSeconds"].asLong),input["minimumPoints"].asInt) else null
            val index=TimelineOutingBuilder(sessions,HistoryRange.all,rules,now,input.instant("activeStart")).apply {points.forEach(::add)}.finish()
            val visits=input["visits"]?.asJsonArray?.map {it.asJsonObject.let {v->Visit(coordinate=Coordinate(0.0,0.0),arrivedAt=v.instant("start")!!,departedAt=v.instant("end"))}}.orEmpty()
            val outings=index.outings.map {a->
                val session=a.stored ?: RecordingSession(a.id,a.startedAt,a.endedAt)
                val through=a.endedAt ?: if(a.stored==null) a.effectiveEnd else now
                val selected=points.filter {p->p.time>=a.startedAt && p.time<=through && (a.stored!=null || sessions.none {p.time>=it.startedAt && p.time<=(it.endedAt ?: now)})}
                val builder=OutingExportSummaryBuilder().apply {selected.forEach(::add)}
                builder.buildForExport(session,a.sequence,a.stored==null,now,visits.count {it.arrivedAt>=a.startedAt && it.arrivedAt<=a.effectiveEnd}.toLong(),a.active)
            }
            val source=object:ExportRecordSource {
                override suspend fun points(consume:suspend (TrackPoint)->Unit) {points.forEach {consume(it)}}
                override suspend fun visits(consume:suspend (Visit)->Unit) {visits.forEach {consume(it)}}
            }
            val reference=expected["allExport"].asJsonObject
            val json=StringWriter();StreamingOutingExporter.write(json,outings,source,ExportFormat.json,generatedAt=now)
            val actual=JsonParser.parseString(json.toString()).asJsonObject.apply {remove("exportDate")}
            val wanted=reference["json"].deepCopy().asJsonObject
            assertEquals(label,wanted["outings"].asJsonArray.size(),actual["outings"].asJsonArray.size())
            wanted["outings"].asJsonArray.zip(actual["outings"].asJsonArray).forEach {(e,a)->
                for((key,tolerance) in listOf("distanceMeters" to .02,"averageSpeedMetersPerSecond" to .001)) {
                    val x=e.asJsonObject.remove(key);val y=a.asJsonObject.remove(key)
                    if(x==null) assertNull(label,y) else {assertNotNull(label,y);assertEquals("$label/$key",x.asDouble,y!!.asDouble,tolerance)}
                }
            }
            assertEquals(label,wanted,actual)
            val csv=StringWriter();StreamingOutingExporter.write(csv,outings,source,ExportFormat.csv,generatedAt=now)
            val expectedRows=CsvReader.read(reference["csv"].asString);val actualRows=CsvReader.read(csv.toString())
            assertEquals(label,expectedRows.size,actualRows.size)
            expectedRows.zip(actualRows).forEachIndexed {row,(e,a)->
                assertEquals(label,e.size,a.size)
                e.zip(a).forEachIndexed {column,(x,y)->
                    if(row>0 && column in listOf(7,8) && x.isNotEmpty()) assertEquals("$label/$row/$column",x.toDouble(),y.toDouble(),if(column==7) .03 else .002)
                    else assertEquals("$label/$row/$column",x,y)
                }
            }
        }
    }
}
