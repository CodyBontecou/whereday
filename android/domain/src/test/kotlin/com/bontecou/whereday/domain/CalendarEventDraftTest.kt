package com.bontecou.whereday.domain

import com.google.gson.JsonParser
import java.time.Instant
import java.util.Locale
import org.junit.Assert.*
import org.junit.Test

class CalendarEventDraftTest {
    private val start=Instant.parse("2026-11-01T05:30:00.123Z")
    private val point=Coordinate(18.1234567,-66.9876543)
    @Test fun visitKeepsExactInstantsAndPrefersAddressWithoutSharingNotesOrAudit() {
        val visit=Visit(coordinate=point,arrivedAt=start,departedAt=start.plusSeconds(120),name="  Cafe 日本語  ",address="  Calle 1  ",notes="Private notes",originalName="Old")
        val draft=CalendarEventDraft.visit(visit,"Unknown",start.plusSeconds(900))
        assertEquals("Cafe 日本語",draft.title);assertEquals("Calle 1",draft.location)
        assertEquals(start,draft.startedAt);assertEquals(visit.departedAt,draft.endedAt)
        assertFalse(draft.toString().contains("Private notes"));assertEquals("Old",visit.originalName)
    }
    @Test fun openAndFutureVisitsUseOneCapturedNowAndNeverReverseTheirInterval() {
        val visit=Visit(coordinate=point,arrivedAt=start)
        assertEquals(start.plusSeconds(60),CalendarEventDraft.visit(visit,"Unknown",start.plusSeconds(60)).endedAt)
        assertEquals(start,CalendarEventDraft.visit(visit,"Unknown",start.minusSeconds(60)).endedAt)
    }
    @Test fun fallbackLocationIsPosixAndTitleSupportsAddressOrUnknown() {
        val old=Locale.getDefault()
        try {
            Locale.setDefault(Locale.GERMANY)
            val visit=Visit(coordinate=point,arrivedAt=start)
            assertEquals("18.123457, -66.987654",CalendarEventDraft.visit(visit,"Ubicación desconocida",start).location)
            assertEquals("Ubicación desconocida",CalendarEventDraft.visit(visit,"Ubicación desconocida",start).title)
            assertEquals("Address",CalendarEventDraft.visit(visit.copy(address="Address"),"Unknown",start).title)
            assertEquals("Cafe",CalendarEventDraft.visit(visit.copy(name="Cafe",address=" "),"Unknown",start).location)
        } finally {Locale.setDefault(old)}
    }
    @Test fun exactInferredAndActiveOutingsKeepBoundsAndOptionalStartPoint() {
        val session=RecordingSession(startedAt=start,endedAt=start.plusSeconds(120),name="  Walk  ",notes="private")
        val exact=CalendarEventDraft.outing(session,point,"Outing 2",start.plusSeconds(900))
        assertEquals("Walk",exact.title);assertEquals(session.endedAt,exact.endedAt);assertNotNull(exact.location)
        val inferred=CalendarEventDraft.outing(session.copy(name=null),null,"Salida 3",start)
        assertEquals("Salida 3",inferred.title);assertNull(inferred.location)
        assertEquals(start.plusSeconds(60),CalendarEventDraft.outing(session.copy(endedAt=null),null,"Outing",start.plusSeconds(60)).endedAt)
        assertEquals(start,CalendarEventDraft.outing(session.copy(endedAt=null),null,"Outing",start.minusSeconds(1)).endedAt)
    }
    @Test fun constructorsMatchNineExecutedCurrentSwiftReferenceCases() {
        val root=javaClass.getResourceAsStream("/ios-calendar-events.json")!!.bufferedReader().use {JsonParser.parseReader(it).asJsonObject}
        val cases=root.getAsJsonArray("cases");assertEquals(9,cases.size())
        for(case in cases) {
            val input=case.asJsonObject.getAsJsonObject("input");val expected=case.asJsonObject.getAsJsonObject("expected")
            fun text(key:String)=input.get(key)?.takeUnless {it.isJsonNull}?.asString
            fun instant(key:String)=input.get(key)?.takeUnless {it.isJsonNull}?.asLong?.let(Instant::ofEpochMilli)
            val point=input.getAsJsonArray("coordinate")?.let {Coordinate(it[0].asDouble,it[1].asDouble)}
            val start=instant("start")!!;val now=instant("now")!!;val fallback=text("fallback")!!
            val draft=if(text("kind")=="visit") CalendarEventDraft.visit(Visit(coordinate=point!!,arrivedAt=start,departedAt=instant("end"),name=text("name"),address=text("address")),fallback,now)
                else CalendarEventDraft.outing(RecordingSession(startedAt=start,endedAt=instant("end"),name=text("name")),point,fallback,now)
            val id=text("id")
            assertEquals(id,expected["title"].asString,draft.title)
            assertEquals(id,expected["start"].asLong,draft.startedAt.toEpochMilli())
            assertEquals(id,expected["end"].asLong,draft.endedAt.toEpochMilli())
            assertEquals(id,expected["location"].takeUnless {it.isJsonNull}?.asString,draft.location)
        }
    }

}
