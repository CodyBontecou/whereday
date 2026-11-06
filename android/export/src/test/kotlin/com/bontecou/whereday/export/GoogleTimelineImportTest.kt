package com.bontecou.whereday.export

import com.google.gson.*
import com.bontecou.whereday.domain.*
import org.junit.Test
import org.junit.Assert.*
import java.io.File
import java.time.Instant
import java.util.concurrent.CancellationException

class GoogleTimelineImportTest {
    @Test fun currentSwiftGoogleReferencesMatchSelectedRecordsAndCounts() {
        val fixture=JsonParser.parseReader(javaClass.getResourceAsStream("/ios-google-timeline.json")!!.reader()).asJsonObject
        for(case in fixture.getAsJsonArray("cases")) {
            val input=case.asJsonObject.getAsJsonObject("input");val expected=case.asJsonObject.getAsJsonObject("expected")
            val text=input["json"].asString;val label=input["id"].asString
            val actual=runCatching {HistoryImporter.parse(text,ExportFormat.json)}
            if(expected.has("error")) {assertTrue(label,actual.isFailure);continue}
            val result=actual.getOrElse {throw AssertionError(label,it)}
            assertEquals(label,expected["source"].asString,result.source.name)
            assertEquals(label,expected["invalid"].asInt,result.rejected)
            assertEquals(label,expected["unsupported"].asInt,result.unsupported)
            val visits=JsonArray();result.visits.forEach {v->visits.add(JsonObject().apply {
                addProperty("latitude",v.coordinate.latitude);addProperty("longitude",v.coordinate.longitude)
                addProperty("arrival",millis(v.arrivedAt));add("departure",v.departedAt?.let {JsonPrimitive(millis(it))} ?: JsonNull.INSTANCE)
                add("name",v.name?.let(::JsonPrimitive) ?: JsonNull.INSTANCE);add("address",v.address?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
                addProperty("source",v.source.name);addProperty("placeSource",v.placeSource)
            })}
            val points=JsonArray();result.points.forEach {p->points.add(JsonObject().apply {
                addProperty("latitude",p.coordinate.latitude);addProperty("longitude",p.coordinate.longitude);addProperty("time",millis(p.time));addProperty("accuracy",p.accuracy)
                add("altitude",p.altitude?.let(::JsonPrimitive) ?: JsonNull.INSTANCE);add("speed",p.speed?.let(::JsonPrimitive) ?: JsonNull.INSTANCE)
            })}
            assertEquals(label,expected["visits"],visits);assertEquals(label,expected["points"],points)
        }
    }
    private fun millis(time:Instant)=java.math.BigDecimal.valueOf(time.epochSecond).movePointRight(3).add(java.math.BigDecimal.valueOf(time.nano.toLong(),6)).setScale(0,java.math.RoundingMode.HALF_UP).longValueExact()
    @Test fun detectRecognizesDeviceArraysAndBothBomAwarePublicEntriesAgree() {
        val text=" \uFEFF  [{\"startTime\":\"2020-01-01T00:00:00Z\",\"endTime\":\"2020-01-01T01:00:00Z\",\"activity\":{\"start\":\"geo:18,-66\"}}]"
        assertEquals(ExportFormat.json,HistoryImporter.detect(text));assertEquals(ExportFormat.json,HistoryImporter.detect(text.reader()))
        assertEquals(1,HistoryImporter.parse(text,ExportFormat.json).points.size)
    }
    @Test fun segmentPathsAreStreamedAndScratchIsRemovedAfterSinkFailureOrCancellation() {
        val text="""{"semanticSegments":[{"timelinePath":[{"point":"geo:18,-66","durationMinutesOffsetFromStartTime":1}],"startTime":"2020-01-01T00:00:00Z","endTime":"2020-01-01T01:00:00Z"}]}"""
        val root=kotlin.io.path.createTempDirectory("google-import-test-").toFile()
        try {
            val sink=IllegalStateException("sink stopped")
            val failure=runCatching {HistoryImporter.scanSource({text.reader()},ExportFormat.json,onPoint={throw sink},scratch={File.createTempFile("row-",".json",root)})}.exceptionOrNull()
            assertSame(sink,failure);assertTrue(root.listFiles()!!.isEmpty())
            val canceled=runCatching {HistoryImporter.scanSource({text.reader()},ExportFormat.json,checkpoint={if(root.listFiles()!!.any {it.length()>0}) throw CancellationException()},scratch={File.createTempFile("row-",".json",root)})}.exceptionOrNull()
            assertTrue(canceled is CancellationException);assertTrue(root.listFiles()!!.isEmpty())
        } finally {root.deleteRecursively()}
    }
    @Test fun unknownFieldsCannotAllocateUnboundedMetadataAndKnownFieldsRemainBounded() {
        val point="\"latitudeE7\":0,\"longitudeE7\":0,\"timestampMs\":0"
        val unknown="x".repeat(ImportRecordLimits.textCharacters+1)
        val accepted=HistoryImporter.parse("{\"locations\":[{$point,\"unused\":\"$unknown\"}]}",ExportFormat.json)
        assertEquals(1,accepted.points.size)
        assertTrue(runCatching {HistoryImporter.parse("{\"locations\":[{$point,\"name\":\"$unknown\"}]}",ExportFormat.json)}.exceptionOrNull() is ImportRecordTooLarge)
    }
}
