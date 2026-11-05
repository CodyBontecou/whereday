package com.bontecou.whereday.domain
import com.google.gson.JsonParser
import org.junit.Test
import org.junit.Assert.*
import java.time.Instant
class TimelineImportMatchTest {
    @Test fun currentSwiftDuplicateBoundaryReferences() {
        val root=javaClass.getResourceAsStream("/ios-timeline-duplicates.json")!!.reader().use {JsonParser.parseReader(it).asJsonObject}
        val cases=root.getAsJsonArray("cases");assertEquals(50,cases.size())
        for((index,element) in cases.withIndex()) {
            val input=element.asJsonObject.getAsJsonObject("input");val expected=element.asJsonObject.getAsJsonObject("expected")
            fun instant(key:String):Instant?=input[key].takeUnless {it.isJsonNull}?.asBigDecimal?.let {seconds->val whole=seconds.setScale(0,java.math.RoundingMode.FLOOR).longValueExact();Instant.ofEpochSecond(whole,seconds.subtract(java.math.BigDecimal.valueOf(whole)).movePointRight(9).longValueExact())}
            fun match(suffix:String)=TimelineImportMatch(input["kind"].asString=="visit",Coordinate(input["lat$suffix"].asDouble,input["lon$suffix"].asDouble),instant("time$suffix")!!,instant("end$suffix"))
            val a=match("A");val b=match("B");val label="Swift duplicate reference $index"
            assertEquals(label,expected["bucketA"].asLong,a.bucket);assertEquals(label,expected["bucketB"].asLong,b.bucket);assertEquals(label,expected["matches"].asBoolean,a.matches(b))
        }
    }
}
