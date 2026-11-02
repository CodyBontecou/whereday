package com.bontecou.whereday.geography

import com.bontecou.whereday.domain.Coordinate
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Test
import java.io.*

class GeographicTimeZoneIndexTest {
    private fun polygon(vararg rings: List<Pair<Double,Double>>):ByteArray = ByteArrayOutputStream().also { bytes ->
        DataOutputStream(bytes).use { output -> output.writeInt(rings.size); rings.forEach { ring -> output.writeInt(ring.size); ring.forEach { (lon,lat) -> output.writeFloat(lon.toFloat()); output.writeFloat(lat.toFloat()) } } }
    }.toByteArray()
    private fun resources(polygons: List<Pair<String,ByteArray>>):Map<String,ByteArray> {
        val index=ByteArrayOutputStream().also { bytes -> DataOutputStream(bytes).use { out ->
            out.writeInt(0x57445A31);out.writeUTF(GeographicTimeZoneIndex.VERSION);out.writeUTF("a".repeat(64));out.writeInt(polygons.size)
            polygons.forEachIndexed { id,(zone,payload) -> out.writeInt(id);out.writeUTF(zone);out.writeLong(payload.size.toLong());out.writeDouble(-2.0);out.writeDouble(-2.0);out.writeDouble(2.0);out.writeDouble(2.0) }
        } }.toByteArray()
        return polygons.mapIndexed { id,p -> "p-$id.bin" to p.second }.toMap()+mapOf("index.bin" to index)
    }
    private val outer=listOf(-2.0 to -2.0,-2.0 to 2.0,2.0 to 2.0,2.0 to -2.0,-2.0 to -2.0)
    private val hole=listOf(-0.5 to -0.5,0.5 to -0.5,0.5 to 0.5,-0.5 to 0.5,-0.5 to -0.5)
    @Test fun holesAndBoundaryIntersectionsRemainGeometryDecisionsAndCacheIsBounded()=runBlocking {
        val data=resources(listOf("America/New_York" to polygon(outer,hole)))
        var polygonReads=0
        val index=GeographicTimeZoneIndex(open={ if(it.endsWith("0.bin")) polygonReads++; ByteArrayInputStream(data.getValue(it)) },maximumCachedBytes=0)
        assertNull(index.query(Coordinate(0.0,0.0)))
        assertEquals("America/New_York",index.query(Coordinate(1.0,1.0)))
        assertEquals("America/New_York",index.query(Coordinate(2.0,0.0)))
        assertEquals(0L,index.metrics.cachedGeometryBytes)
        val reads=polygonReads
        assertNull(index.query(Coordinate(10.0,10.0))); assertEquals(reads,polygonReads)
    }
    @Test fun overlappingZonesRemainExplicitAndAreNeverCollapsedToAnArbitraryZone()=runBlocking {
        val data=resources(listOf("America/New_York" to polygon(outer),"America/Chicago" to polygon(outer)))
        val index=GeographicTimeZoneIndex(open={ ByteArrayInputStream(data.getValue(it)) })
        assertEquals(setOf("America/New_York","America/Chicago"),index.queryAll(Coordinate(1.0,1.0)))
        assertNull(index.query(Coordinate(1.0,1.0)))
        assertTrue(index.metrics.cachedGeometryBytes>0)
        index.release(); assertEquals(0L,index.metrics.cachedGeometryBytes)
    }
    @Test fun truncatedPolygonIsRejectedInsteadOfInventingAnEmptyOrPartialZone()=runBlocking {
        val data=resources(listOf("America/New_York" to polygon(outer)))
        val broken=data+mapOf("p-0.bin" to byteArrayOf(0,0,0,1))
        val index=GeographicTimeZoneIndex(open={ ByteArrayInputStream(broken.getValue(it)) })
        try { index.query(Coordinate(1.0,1.0)); fail("Expected truncated boundary failure") } catch(expected:EOFException) { }
    }
}
