package com.bontecou.whereday.domain

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class TimelineStatisticsReferenceTest {
    private fun JsonObject.instant(key:String)=get(key).takeUnless {it.isJsonNull}?.asLong?.let(Instant::ofEpochMilli)
    @Test fun completeSwiftBuilderAndTimelineStatisticsMatchThirtyFourReferences() {
        val root=javaClass.getResourceAsStream("/ios-timeline-statistics.json")!!.bufferedReader().use {JsonParser.parseReader(it).asJsonObject}
        assertEquals(34,root["cases"].asJsonArray.size())
        for(raw in root["cases"].asJsonArray) {
            val input=raw.asJsonObject["input"].asJsonObject;val expected=raw.asJsonObject["expected"].asJsonObject
            val label=input["id"].asString
            val selected=input.instant("selected")!!.atZone(ZoneId.of(input["zone"].asString)).toLocalDate()
            val range=HistoryRange.day(selected,ZoneId.of(input["zone"].asString))
            assertEquals(label,expected.instant("from"),range.from);assertEquals(label,expected.instant("until"),range.until)
            val sessions=input["sessions"].asJsonArray.map {it.asJsonObject.let {s->RecordingSession(s["id"].asString.lowercase(),s.instant("start")!!,s.instant("end"))}}
            val rules=if(input["enabled"].asBoolean) InferenceRules(Duration.ofSeconds(input["gapSeconds"].asLong),Duration.ofSeconds(input["minimumSeconds"].asLong),input["minimumPoints"].asInt) else null
            val builder=TimelineOutingBuilder(sessions,range,rules,input.instant("now")!!,input.instant("activeStart"))
            input["points"].asJsonArray.map {it.asJsonObject}.sortedBy {it["time"].asLong}.forEach {p->
                builder.add(TrackPoint(p["id"].asString.lowercase(),Coordinate(p["lat"].asDouble,p["lon"].asDouble),p.instant("time")!!,5.0,outlier=p["outlier"].asBoolean))
            }
            val actual=builder.finish();val wanted=expected["selected"].asJsonArray
            assertEquals(label,wanted.size(),actual.outings.size)
            wanted.zip(actual.outings).forEach { (rawRow,a)->
                val e=rawRow.asJsonObject;val name="$label/${e["id"].asString}"
                val inferred=e["inferred"].asBoolean
                val id=if(inferred) "inferred:"+e["points"].asJsonArray.first().asString.lowercase() else e["storedID"].asString.lowercase()
                assertEquals(name,id,a.id);assertEquals(name,inferred,a.stored==null)
                assertEquals(name,e["sequence"].asInt,a.sequence)
                assertEquals(name,e.instant("start"),a.startedAt);assertEquals(name,e.instant("end"),a.endedAt)
                assertEquals(name,e.instant("effectiveEnd"),a.effectiveEnd);assertEquals(name,e["active"].asBoolean,a.active)
                assertEquals(name,e["count"].asLong,a.summary.count);assertEquals(name,e["outliers"].asLong,a.summary.outlierCount)
                assertEquals(name,e["distance"].asDouble,a.summary.distanceMeters,0.02)
                val metrics=OutingMetrics.capture(RecordingSession(a.id,a.startedAt,a.endedAt),a.summary,input.instant("now")!!)
                assertEquals(name,e.instant("effectiveEnd"),metrics.effectiveEnd)
                assertEquals(name,e["duration"].asDouble,metrics.durationSeconds,0.000001)
                val average=e["average"].takeUnless {it.isJsonNull}?.asDouble
                if(average==null) assertNull(name,metrics.averageSpeedMetersPerSecond)
                else assertEquals(name,average,checkNotNull(metrics.averageSpeedMetersPerSecond),0.0001)
                assertTrue(name,metrics.range.contains(metrics.effectiveEnd))
                assertFalse(name,metrics.range.contains(metrics.effectiveEnd.plusMillis(1)))
                val session=RecordingSession(a.id,a.startedAt,a.endedAt)
                val detail=OutingDetail(session,emptyList(),a.summary,emptyList(),metrics,inferred=inferred)
                val target=OutingMapTarget.capture(detail);val repeatedTarget=OutingMapTarget.capture(detail)
                val focus=e["focus"].asJsonObject
                assertEquals(name,session,target.session)
                assertEquals(name,focus.instant("from"),target.range.from)
                assertEquals(name,focus.instant("through")!!.plusMillis(1),target.range.until)
                assertEquals(name,focus.instant("pointFrom"),target.range.from)
                assertEquals(name,focus.instant("photoThrough")!!.plusMillis(1),target.range.until)
                assertNotEquals(name,target.requestId,repeatedTarget.requestId)
                assertTrue(focus["initialApplied"].asBoolean);assertFalse(focus["repeatedApplied"].asBoolean)
                assertTrue(focus["freshApplied"].asBoolean);assertTrue(focus["distinctRequest"].asBoolean)
                assertEquals(2,focus["fits"].asInt)
            }
            assertEquals(label,expected["distance"].asDouble,actual.distanceMeters,0.04)
        }
    }
    @Test fun smallOutingDistanceRetainsPrecisionAfterLargeUnrelatedHistory() {
        val start=Instant.parse("2026-11-01T00:00:00Z")
        val outingStart=start.plusSeconds(10_000)
        val session=RecordingSession("small",outingStart,outingStart.plusSeconds(10_000))
        val builder=TimelineOutingBuilder(listOf(session),HistoryRange.day(LocalDate.of(2026,11,1),ZoneOffset.UTC),InferenceRules(minimumPoints=10_000_000),start.plusSeconds(30_000))
        repeat(10_000) {i->builder.add(TrackPoint("unrelated-$i",Coordinate(0.0,if(i%2==0) 0.0 else 180.0),start.plusSeconds(i.toLong()),5.0))}
        val direct=OutingPointSummaryBuilder()
        repeat(10_001) {i->
            val point=TrackPoint("small-$i",Coordinate(18.0+i*0.0000000001,-66.0),outingStart.plusSeconds(i.toLong()),5.0)
            direct.add(point);builder.add(point)
        }
        assertTrue(direct.build().distanceMeters>0.1)
        assertEquals(direct.build().distanceMeters,builder.finish().distanceMeters,0.000001)
    }
    @Test(timeout=30_000) fun overlappingIntervalsDoNotRequirePerPointSessionScans() {
        val start=Instant.parse("2026-11-01T00:00:00Z")
        val sessions=(1..10_000).map {RecordingSession("session-$it",start,start.plusSeconds(100_000))}
        val builder=TimelineOutingBuilder(sessions,HistoryRange(start,start.plusSeconds(86400)),InferenceRules(),start.plusSeconds(200_000))
        val coordinate=Coordinate(18.0,-66.0)
        repeat(100_000) {builder.add(TrackPoint("point-$it",coordinate,start.plusSeconds(it.toLong()),5.0))}
        val result=builder.finish()
        assertEquals(10_000,result.outings.size)
        assertTrue(result.outings.all {it.summary.count==100_000L && it.summary.distanceMeters==0.0})
    }
}
