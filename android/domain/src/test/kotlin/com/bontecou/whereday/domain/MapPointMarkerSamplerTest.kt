package com.bontecou.whereday.domain

import com.google.gson.*
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class MapPointMarkerSamplerTest {
    private fun JsonElement.number():Double = if(asJsonPrimitive.isNumber) asDouble else when(asString) {"nan"->Double.NaN;"inf"->Double.POSITIVE_INFINITY;else->Double.NEGATIVE_INFINITY}
    @Test fun executedCurrentSwiftViewportCasesMatch() {
        val document=javaClass.getResourceAsStream("/ios-point-markers.json")!!.reader().use {JsonParser.parseReader(it).asJsonObject}
        val cases=document.getAsJsonArray("cases");val expected=document.getAsJsonArray("expected")
        assertEquals(27,cases.size());assertEquals(cases.size(),expected.size())
        for((index,value) in cases.withIndex()) {
            val case=value.asJsonObject;val raw=case.getAsJsonArray("points")
            val points=raw.mapIndexedNotNull {i,row->
                val values=row.asJsonArray;val lat=values[1].number();val lon=values[2].number()
                if(!lat.isFinite() || !lon.isFinite() || lat !in -90.0..90.0 || lon !in -180.0..180.0) null
                else TrackPoint(values[0].asString,Coordinate(lat,lon),Instant.EPOCH.plusSeconds(i.toLong()),1.0)
            }
            val view=case.getAsJsonArray("viewport")
            val viewport=case.get("rect").takeUnless {it.isJsonNull}?.asJsonArray?.let {MapPointViewport(it[0].number(),it[1].number(),it[2].number(),it[3].number(),view[0].number(),view[1].number())}
            val selected=case.get("selected").takeUnless {it.isJsonNull}?.asString
            val result=MapPointMarkerSampler.sample(points,viewport,selected)
            val ids=expected[index].asJsonObject.getAsJsonArray("indices").map {raw[it.asInt].asJsonArray[0].asString}
            assertEquals(case.get("name").asString,ids,result.map {it.id})
            assertTrue(result.size<=160)
        }
    }
    @Test fun millionPointStreamRetainsBoundedCellsAndSelectedIdentityWithoutChangingRouteOrder() {
        val sampler=MapPointMarkerSampler(MapPointViewport(0.0,0.0,1.0,1.0,1800.0,1200.0),"999999")
        repeat(1_000_000) {i->sampler.add(TrackPoint(i.toString(),Coordinate(-75+(i%30)*5.0,-175+(i%40)*9.0),Instant.EPOCH.plusSeconds(i.toLong()),1.0))}
        val result=sampler.points()
        assertTrue(result.size in 1..160);assertTrue(result.any {it.id=="999999"})
        assertEquals(result.map {it.time}.sorted(),result.map {it.time})
        assertEquals(result.map {it.id}.distinct(),result.map {it.id})
    }
    @Test fun selectedOffscreenPointDoesNotDisplaceAVisiblePointAndMissingViewportDrawsNothing() {
        val first=TrackPoint("first",Coordinate(0.0,0.0),Instant.EPOCH,1.0)
        val outside=first.copy(id="outside",coordinate=Coordinate(80.0,170.0))
        val viewport=MapPointViewport(.4,.4,.2,.2,48.0,48.0)
        assertEquals(listOf(first),MapPointMarkerSampler.sample(listOf(first,outside),viewport,"outside"))
        assertEquals(emptyList<TrackPoint>(),MapPointMarkerSampler.sample(listOf(first),null,"first"))
    }
}
