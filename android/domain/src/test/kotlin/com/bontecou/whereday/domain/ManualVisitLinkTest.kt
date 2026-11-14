package com.bontecou.whereday.domain

import com.google.gson.JsonParser
import org.junit.Test
import org.junit.Assert.*
import java.time.Instant

class ManualVisitLinkTest {
    private val now=Instant.parse("2026-11-01T00:00:00Z")
    @Test fun matchesSeventySixExecutedCurrentSwiftParserCases() {
        val root=javaClass.getResourceAsStream("/ios-visit-links.json")!!.bufferedReader().use {JsonParser.parseReader(it).asJsonObject}
        val cases=root.getAsJsonArray("cases");assertEquals(76,cases.size())
        val failures=mutableListOf<String>()
        for(element in cases) {
            val c=element.asJsonObject;val url=c["url"].asString;val actual=runCatching {ManualVisitLink.parse(url,now)}
            if(!c["valid"].asBoolean) {if(actual.isSuccess) failures+="Accepted invalid $url";continue}
            if(actual.isFailure) {failures+="Rejected valid $url";continue}
            fun text(key:String)=c[key]?.takeUnless {it.isJsonNull}?.asString
            fun instant(key:String)=c[key]?.takeUnless {it.isJsonNull}?.asLong?.let(Instant::ofEpochMilli)
            val expected=if(text("kind")=="visit") ManualVisitLink.Existing(text("id")!!) else ManualVisitLink.Form(
                if(text("kind")=="checkIn") ManualVisitMode.current else ManualVisitMode.past,text("name"),
                c["latitude"]?.takeUnless {it.isJsonNull}?.asDouble?.let {Coordinate(it,c["longitude"].asDouble)},instant("arrival"),instant("departure"))
            if(expected!=actual.getOrNull()) failures+="Mismatch $url: expected $expected, got ${actual.getOrNull()}"
        }
        assertTrue(failures.joinToString("\n"),failures.isEmpty())
    }
    @Test fun defaultsAreCapturedAtPresentationWithoutCreatingAnyVisit() {
        val current=ManualVisitLink.parse("isome://check-in?name=Cafe",now) as ManualVisitLink.Form
        assertEquals(VisitLinkSeed(ManualVisitMode.current,"Cafe",null,now,now),current.seed(now))
        val past=ManualVisitLink.parse("whereday://log-visit",now) as ManualVisitLink.Form
        assertEquals(VisitLinkSeed(ManualVisitMode.past,"",null,now.minusSeconds(3600),now),past.seed(now))
    }
    @Test fun oversizedUrlsAndAmbiguousStructureNeverBecomeCommands() {
        for(value in listOf("isome://check-in?name="+"x".repeat(ManualVisitLink.MAX_URL_LENGTH),"isome://check-in?latitude=1&longitude=0&allowOverlap=true","isome://check-in?save=true","isome://visit/1-1-1-1-1"))
            assertThrows(InvalidVisitLink::class.java) {ManualVisitLink.parse(value,now)}
    }
}
