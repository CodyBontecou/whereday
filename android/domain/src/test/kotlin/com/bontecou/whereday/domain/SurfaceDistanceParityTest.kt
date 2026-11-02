package com.bontecou.whereday.domain

import com.google.gson.JsonObject
import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.Parameterized
import kotlin.math.*

@RunWith(Parameterized::class)
class SurfaceDistanceParityTest(private val name:String,private val c:JsonObject) {
    companion object {
        @JvmStatic @Parameterized.Parameters(name="{0}") fun cases():Collection<Array<Any>> =
            requireNotNull(SurfaceDistanceParityTest::class.java.getResourceAsStream("/ios-distances.json")).bufferedReader().use {JsonParser.parseReader(it).asJsonObject["cases"].asJsonArray.filter {it.asJsonObject["comparison"].asString=="parity"}.map {arrayOf(it.asJsonObject["name"].asString,it.asJsonObject)}}
    }
    @Test fun conditionedPairsAndOrderedRouteTotalsMatchCoreLocationPrecision() {
        val points=c["points"].asJsonArray.map {Coordinate(it.asJsonArray[0].asDouble,it.asJsonArray[1].asDouble)}
        val expected=c["expected"].asDouble
        val actual=points.zipWithNext {a,b->a.distanceTo(b)}.sum()
        // Core Location's approximate long-arc series differs slightly by platform and call context.
        val tolerance=(if(points.size>2) .25 else .01)+expected*1e-9
        assertEquals(name,expected,actual,tolerance)
        assertEquals(name,actual,points.asReversed().zipWithNext {a,b->a.distanceTo(b)}.sum(),1e-6+actual*1e-12)
        assertTrue(actual.isFinite() && actual>=0)
    }
}

class SurfaceDistanceBoundsTest {
    @Test fun singularReferencesStayFiniteWithoutClaimingCoreLocationParity() {
        val cases=requireNotNull(javaClass.getResourceAsStream("/ios-distances.json")).bufferedReader().use {JsonParser.parseReader(it).asJsonObject["cases"].asJsonArray}
        var checked=0
        for(entry in cases) {
            val c=entry.asJsonObject;if(c["comparison"].asString!="singular") continue
            val a=c["points"].asJsonArray[0].asJsonArray;val b=c["points"].asJsonArray[1].asJsonArray
            val distance=Coordinate(a[0].asDouble,a[1].asDouble).distanceTo(Coordinate(b[0].asDouble,b[1].asDouble))
            assertTrue(c["name"].asString,distance.isFinite() && distance in 0.0..20_100_000.0);checked++
        }
        assertEquals(7,checked)
    }
    @Test fun localMeridianAndParallelUseTheirDistinctEllipsoidCurvatures() {
        val origin=Coordinate(0.0,0.0)
        assertEquals(110.57427590630364,origin.distanceTo(Coordinate(.001,0.0)),.000001)
        assertEquals(111.31949079327357,origin.distanceTo(Coordinate(0.0,.001)),.000001)
        assertTrue(Coordinate(48.0,2.0).distanceTo(Coordinate(48.0,Math.nextDown(2.0)))>0)
        assertEquals(0.0,Coordinate(0.0,180.0).distanceTo(Coordinate(0.0,-180.0)),0.0)
    }
}
