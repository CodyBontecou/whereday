package com.bontecou.whereday.domain

import com.google.gson.JsonParser
import org.junit.Assert.*
import org.junit.Test
import java.time.Instant

class PhotoClustersTest {
    private fun photo(id:String,time:Long,x:Double)=PhotoMoment(id,Instant.ofEpochSecond(time),Coordinate(0.0,x),PhotoCoordinateSource.photoGPS)
    @Test fun extractedSwiftFixturesMatchGroupingOrderAndSphericalCentroids() {
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
            val annotations=PhotoClusters.annotations(result,c["maximum"].asInt)
            val expectedAnnotations=c.getAsJsonArray("annotations")
            assertEquals(c["name"].asString,expectedAnnotations.size(),annotations.size)
            expectedAnnotations.forEachIndexed {index,item ->
                val e=item.asJsonObject;val r=annotations[index]
                assertEquals(c["name"].asString,e["isArea"].asBoolean,r.isArea)
                assertEquals(e.getAsJsonArray("photos").map {it.asString},r.photos.map {it.uri})
                assertEquals(e["lat"].asDouble,r.coordinate.latitude,1e-9)
                assertEquals(e["lon"].asDouble,r.coordinate.longitude,1e-9)
                assertEquals(e.getAsJsonArray("places").map {group->group.asJsonArray.map {it.asString}},r.places.map {group->group.photos.map {it.uri}})
            }
            assertEquals(result.flatMap {it.photos}.map {it.uri}.sorted(),annotations.flatMap {it.photos}.map {it.uri}.sorted())
        }
    }
    @Test fun indexAgreesWithExhaustiveSearchIncludingMovingCentroidsAndGlobalEdges() {
        val random=java.util.Random(714L)
        val photos=(0..<4000).map {i ->
            val center=when(i%4) {0->Coordinate(89.9999,179.9999);1->Coordinate(0.0,-179.9999);2->Coordinate(18.0,-66.0);else->Coordinate(-48.0,2.0)}
            PhotoMoment("random-$i",Instant.ofEpochSecond(i.toLong()),Coordinate((center.latitude+(random.nextDouble()-.5)*.01).coerceIn(-90.0,90.0),((center.longitude+(random.nextDouble()-.5)*.01+540)%360)-180),PhotoCoordinateSource.photoGPS)
        }
        assertEquals(PhotoClusters.build(photos,distance={a,b->a.distanceTo(b)}),PhotoClusters.build(photos))
    }
    @Test(timeout=30000) fun indexedDistinctPlacesRemainCompleteAtLargeLibraryScale() {
        val photos=(0..<20000).map {i -> PhotoMoment("place-$i",Instant.ofEpochSecond(i.toLong()),Coordinate(-70.0+(i/200)*1.4,-179.0+(i%200)*1.79),PhotoCoordinateSource.photoGPS)}
        val places=PhotoClusters.build(photos)
        assertEquals(photos.size,places.size)
        val markers=PhotoClusters.annotations(places)
        assertTrue(markers.size<=500)
        assertEquals(photos.map {it.uri}.toSet(),markers.flatMap {it.photos}.map {it.uri}.toSet())
        assertEquals(places.toSet(),markers.flatMap {it.places}.toSet())
        assertTrue(markers.all {it.coordinate==it.places.first().coordinate})
    }
    @Test fun invalidAnnotationLimitAndCanceledCoarseningStop() {
        try {PhotoClusters.annotations(emptyList(),0);fail("Zero markers cannot represent places")} catch(_:IllegalArgumentException) {}
        val places=PhotoClusters.build((0..100).map {photo("place-$it",it.toLong(),it*.01)})
        var checked=0
        try {PhotoClusters.annotations(places,1,checkpoint={if(++checked==20) throw InterruptedException()});fail("Canceled coarsening must stop")}
        catch(_:InterruptedException) {assertEquals(20,checked)}
    }
    @Test fun inclusiveThresholdAndEqualDistanceUseFirstCreatedCluster() {
        val photos=listOf(photo("a",0,0.0),photo("b",1,1.0),photo("c",2,0.5))
        val result=PhotoClusters.build(photos,35.0,distance={a,b->kotlin.math.abs(a.longitude-b.longitude)*70})
        assertEquals(listOf(listOf("a","c"),listOf("b")),result.map {it.photos.map {p->p.uri}})
        assertEquals(0.25,result.first().coordinate.longitude,1e-12)
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
