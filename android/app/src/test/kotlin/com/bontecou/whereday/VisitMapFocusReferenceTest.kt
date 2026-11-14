package com.bontecou.whereday

import com.bontecou.whereday.domain.*
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.*

class VisitMapFocusReferenceTest {
    @Test fun focusedDayAndRegionMatchEightExecutedSwiftReferences() {
        val root=javaClass.getResourceAsStream("/ios-visit-map-focus.json")!!.bufferedReader().use {JsonParser.parseReader(it).asJsonObject}
        assertEquals(8,root["cases"].asJsonArray.size())
        for(value in root["cases"].asJsonArray) {
            val input=value.asJsonObject["input"].asJsonObject;val expected=value.asJsonObject["expected"].asJsonObject
            val coordinate=input["coordinate"].asJsonArray.let {Coordinate(it[0].asDouble,it[1].asDouble)}
            val visit=Visit(id=input["id"].asString,coordinate=coordinate,arrivedAt=Instant.parse(input["arrival"].asString),timeZone="Asia/Tokyo")
            val selection=visitMapSelection(visit,ZoneId.of(input["zone"].asString));val range=selection.range(Instant.EPOCH,ZoneOffset.UTC)
            assertEquals(visit.id,Instant.ofEpochMilli(expected["from"].asLong),range.from)
            assertEquals(visit.id,Instant.ofEpochMilli(expected["through"].asLong).plusMillis(1),range.until)
            assertFalse(selection.tracksNow);assertEquals("Visit",selection.preset)
            val focus=selection.visitFocus!!;assertEquals(visit.id,focus.id);assertEquals(coordinate,focus.coordinate)
            val bounds=focus.bounds();assertEquals(0.008,bounds.east-bounds.west,1e-8);assertEquals(0.008,bounds.north-bounds.south,1e-8)
            assertEquals(coordinate.latitude,(bounds.south+bounds.north)/2,1e-8)
            assertEquals(coordinate.longitude,(bounds.west+bounds.east)/2,1e-8)
            assertEquals(range,selection.copy(visitFocus=null).range(Instant.EPOCH,ZoneOffset.UTC))
        }
    }
    @Test fun explicitRepeatTargetsCanRefocusWithoutChangingTheDataWindow() {
        val visit=Visit(coordinate=Coordinate(18.0,-66.0),arrivedAt=Instant.EPOCH)
        val first=visitMapSelection(visit,ZoneOffset.UTC);val again=visitMapSelection(visit,ZoneOffset.UTC)
        assertNotEquals(first.visitFocus!!.requestId,again.visitFocus!!.requestId)
        assertEquals(first.copy(visitFocus=null),again.copy(visitFocus=null))
        assertNull(MapSelection("Today").visitFocus)
    }
    @Test fun unqueryableDayBoundsAreRejectedAndPolarCameraTargetsRemainProjectable() {
        for(value in listOf(Long.MIN_VALUE,Long.MAX_VALUE)) {
            val visit=Visit(coordinate=Coordinate(0.0,0.0),arrivedAt=Instant.ofEpochMilli(value))
            assertTrue(runCatching {visitMapSelection(visit,ZoneOffset.UTC)}.exceptionOrNull() is IllegalArgumentException)
        }
        for(latitude in listOf(-90.0,90.0)) {
            val bounds=VisitMapFocus("polar",Coordinate(latitude,179.999)).bounds()
            assertTrue(bounds.south>=-MapCameraBounds.MAX_LATITUDE);assertTrue(bounds.north<=MapCameraBounds.MAX_LATITUDE)
            assertTrue(bounds.east-bounds.west<0.009)
        }
    }
}
