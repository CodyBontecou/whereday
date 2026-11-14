package com.bontecou.whereday

import com.bontecou.whereday.domain.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class TimelineNavigationReferenceTest {
    @Test fun navigationAndChronologyMatchThirtyExecutedSwiftReferences() {
        val root=javaClass.getResourceAsStream("/ios-timeline-navigation.json")!!.bufferedReader().use {JsonParser.parseReader(it).asJsonObject}
        assertEquals(30,root["cases"].asJsonArray.size())
        for(raw in root["cases"].asJsonArray) {
            val row=raw.asJsonObject;val input=row["input"].asJsonObject;val expected=row["expected"].asJsonObject
            val today=LocalDate.parse(expected["today"].asString);val selected=LocalDate.parse(expected["selected"].asString)
            val zone=ZoneId.of(row["zone"].asString)
            val latest=expected["latestMillis"].takeUnless {it.isJsonNull}?.asLong?.let {Instant.ofEpochMilli(it).atZone(zone).toLocalDate()}
            val hasEvents=listOf("displayedVisits","displayedSessions").any {input[it]?.asJsonArray?.isEmpty==false}
            val handled=input["handled"]?.asBoolean ?: false
            assertEquals(input["id"].asString,LocalDate.parse(expected["initial"].asString),if(handled) selected else initialTimelineDay(selected,today,hasEvents,latest))
            assertEquals(input["id"].asString,expected["emptyTarget"].takeUnless {it.isJsonNull}?.asString?.let(LocalDate::parse),emptyTimelineTarget(selected,today,latest))
            fun instant(day:Int)=today.plusDays(day.toLong()).atTime(9,0).atZone(zone).toInstant()
            val visits=input["displayedVisits"]?.asJsonArray?.map {v->v.asJsonObject.let {Visit(it["id"].asString,Coordinate(0.0,0.0),instant(it["day"].asInt))}}.orEmpty()
            val sessions=input["displayedSessions"]?.asJsonArray?.map {v->v.asJsonObject.let {RecordingSession(it["id"].asString,instant(it["day"].asInt))}}.orEmpty()
            val window=HistoryWindow(HistoryRange.all,emptyList(),PointSummaryBuilder().build(),visits,sessions)
            assertEquals(input["id"].asString,expected["order"].asJsonArray.map {it.asString},timelineEntries(window).map {it.id})
        }
    }
    @Test fun movementTiesRetainGlobalBuilderOrderAndVisitsKeepPriority() {
        val at=Instant.parse("2026-11-01T00:00:00Z")
        val stored=RecordingSession("00000000-0000-0000-0000-000000000010",at.plusSeconds(500))
        val point=TrackPoint("00000000-0000-0000-0000-000000000001",Coordinate(18.0,-66.0),stored.startedAt,5.0)
        val range=HistoryRange.day(LocalDate.of(2026,11,1),ZoneOffset.UTC)
        val builder=TimelineOutingBuilder(listOf(stored),range,InferenceRules(),at.plusSeconds(300))
        builder.add(point);val index=builder.finish()
        // The matching executed Swift reference is future-open-at-unconsumed-fix.
        assertEquals(listOf("inferred:${point.id}",stored.id),index.outings.map {it.id})
        val header=InferredOutingHeader("inferred:${point.id}",point.time,point.time,PointSummary(count=1))
        val visit=Visit("visit",point.coordinate,point.time)
        val window=HistoryWindow(range,emptyList(),PointSummary(),listOf(visit),listOf(stored),listOf(header),timeline=index)
        assertEquals(listOf(visit.id,header.id,stored.id),timelineEntries(window).map {it.id})
    }
    @Test fun cameraRequestsDoNotChangeMapContentReplayOrPhotoOwnerIdentity() {
        val at=Instant.parse("2026-11-01T00:00:00Z")
        val range=HistoryRange(at,at.plusSeconds(100))
        val first=MapSelection("Outing",outingRange=range,outingFocusRequest="123e4567-e89b-12d3-a456-426614174010")
        val second=first.copy(outingFocusRequest="123e4567-e89b-12d3-a456-426614174011")
        assertNotEquals(first,second);assertEquals(first.contentScope(),second.contentScope())
        assertEquals(range,second.range(at.plusSeconds(10000),ZoneOffset.UTC))
        assertFalse(second.tracksNow)
    }
    @Test fun adjacentDaysSkipUnrepresentableCivilDaysAndRejectStorageOverflow() {
        val zone=ZoneId.of("Pacific/Apia")
        assertEquals(LocalDate.of(2011,12,31),adjacentTimelineDay(LocalDate.of(2011,12,29),1,zone))
        assertEquals(LocalDate.of(2011,12,29),adjacentTimelineDay(LocalDate.of(2011,12,31),-1,zone))
        assertNull(adjacentTimelineDay(LocalDate.MAX,1,ZoneOffset.UTC))
        assertNull(adjacentTimelineDay(LocalDate.MIN,-1,ZoneOffset.UTC))
    }
}
