package com.bontecou.whereday.domain

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PhotoClustersTest {
    private fun photo(id:String,time:Long,x:Double)=PhotoMoment(id,Instant.ofEpochSecond(time),Coordinate(0.0,x),PhotoCoordinateSource.photoGPS)
    @Test fun extractedSwiftFixturesMatchGroupingOrderAndArithmeticCentroids() {
        val cases=JsonParser.parseReader(javaClass.getResourceAsStream("/ios-photo-clusters.json")!!.reader()).asJsonObject.getAsJsonArray("cases")
        for(value in cases) {
            val c=value.asJsonObject
            val photos=c.getAsJsonArray("photos").map {it.asJsonObject.let {p->PhotoMoment(p["uri"].asString,Instant.ofEpochSecond(p["time"].asLong),Coordinate(p["lat"].asDouble,p["lon"].asDouble),PhotoCoordinateSource.photoGPS)}}
            val result=PhotoClusters.build(photos,c["threshold"].asDouble)
            val expected=c.getAsJsonArray("clusters")
            assertEquals(c["name"].asString,expected.size(),result.size)
            expected.forEachIndexed {index,item->val e=item.asJsonObject;val r=result[index]
                assertEquals(e.getAsJsonArray("photos").map {it.asString},r.photos.map {it.uri})
                assertEquals(e["lat"].asDouble,r.coordinate.latitude,1e-9);assertEquals(e["lon"].asDouble,r.coordinate.longitude,1e-9)
            }
        }
    }
    @Test fun inclusiveThresholdAndEqualDistanceUseFirstCreatedCluster() {
        val photos=listOf(photo("a",0,0.0),photo("b",1,1.0),photo("c",2,0.5))
        val result=PhotoClusters.build(photos,35.0,distance={a,b->kotlin.math.abs(a.longitude-b.longitude)*70})
        assertEquals(listOf(listOf("a","c"),listOf("b")),result.map {it.photos.map {p->p.uri}})
        assertEquals(0.25,result.first().coordinate.longitude,0.0)
        assertEquals("c",result.first().representative.uri)
    }
    @Test fun stableUriTieOrderAndCancellationCheckpoint() {
        val photos=listOf(photo("b",0,0.0),photo("a",0,0.0))
        assertEquals(listOf("a","b"),PhotoClusters.build(photos).single().photos.map {it.uri})
        var checked=0
        try {PhotoClusters.build((0..499).map {photo("photo-$it",it.toLong(),it*0.01)},checkpoint={if(++checked==20) throw InterruptedException()});fail("Canceled grouping must stop")}
        catch(_:InterruptedException) {assertEquals(20,checked)}
    }
}
