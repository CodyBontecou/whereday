package com.bontecou.whereday.domain

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.time.Instant
import java.io.IOException
import kotlin.math.roundToLong
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RoadSnappingTest {
    private val base=Instant.parse("2026-10-27T10:00:00Z")
    private fun point(id:String="a",longitude:Double=0.0,seconds:Long=0,accuracy:Double=5.0)=TrackPoint(id,Coordinate(0.0,longitude),base.plusSeconds(seconds),accuracy)
    private fun fixtures()=JsonParser.parseReader(javaClass.getResourceAsStream("/ios-road-snapping.json")!!.reader()).asJsonObject.getAsJsonArray("cases")
    private fun points(row:JsonObject)=row.getAsJsonArray("points").map {value->value.asJsonObject.let {
        TrackPoint(it["id"].asString,Coordinate(it["latitude"].asDouble,it["longitude"].asDouble),base.plusNanos((it["seconds"].asDouble*1e9).roundToLong()),it["accuracy"].asDouble)
    }}
    private fun compareSegments(label:String,expected:JsonArray,actual:List<RoadSegment>) {
        assertEquals(label,expected.size(),actual.size)
        expected.zip(actual).forEach {(value,segment)->val row=value.asJsonObject
            assertEquals(label,row["start"].asInt,segment.startIndex);assertEquals(label,row["end"].asInt,segment.endIndex);assertEquals(label,row["snapped"].asBoolean,segment.snapped)
            val coords=row.getAsJsonArray("coordinates");assertEquals(label,coords.size(),segment.coordinates.size)
            coords.zip(segment.coordinates).forEach {(coordinate,found)->assertEquals(label,coordinate.asJsonArray[0].asDouble,found.latitude,1e-12);assertEquals(label,coordinate.asJsonArray[1].asDouble,found.longitude,1e-12)}
        }
    }
    private suspend fun compareFixture(row:JsonObject,swiftMetric:Boolean) {
        val label=row["name"].asString+" swiftMetric="+swiftMetric
        val source=points(row);val original=source.toList();val calls=mutableListOf<Pair<Int,RoadTransport>>()
        val distances=source.zipWithNext().mapIndexed {index,pair->(pair.first.coordinate to pair.second.coordinate) to row.getAsJsonArray("candidateDistances")[index].asDouble}.toMap()
        val metric:(Coordinate,Coordinate)->Double={a,b->if(swiftMetric) distances[a to b] ?: a.distanceTo(b) else a.distanceTo(b)}
        val result=RoadSnapping.build(source,RoadDirections {a,b,transport->
            calls+=source.indexOfFirst {it.coordinate==a} to transport
            if(row["mode"].asString=="missing") null else {
                val distance=if(row["mode"].asString=="badwalking" && transport==RoadTransport.walking) 1_000_000.0 else metric(a,b)*1.5
                RoadDirectionsResult(distance,listOf(a,Coordinate(a.latitude+.000001,a.longitude),Coordinate((a.latitude+b.latitude)/2+.00005,(a.longitude+b.longitude)/2),Coordinate(b.latitude-.000001,b.longitude),b))
            }
        },metric)
        val expectedCalls=row.getAsJsonArray("calls").map {it.asJsonObject.let {c->c["index"].asInt to RoadTransport.valueOf(c["transport"].asString)}}
        assertEquals(label,expectedCalls,calls);assertEquals(label,source.size,result.sourcePointCount)
        compareSegments(label,row.getAsJsonArray("segments"),result.segments)
        row.getAsJsonArray("replay").forEach {frame->compareSegments(label,frame.asJsonObject.getAsJsonArray("segments"),result.segmentsThrough(frame.asJsonObject["index"].asInt))}
        assertEquals(label,original,source);assertEquals(label,RoadSnapping.fingerprint(original),result.fingerprint)
        if(row["name"].asString=="forty-largest-missing") assertEquals(80,calls.size)
    }
    @Test fun actualSwiftSelectionStitchingCoalescingAndReplayReferences()=runBlocking {
        val rows=fixtures();assertEquals(17,rows.size());rows.forEach {compareFixture(it.asJsonObject,true)}
    }
    @Test fun androidSurfaceMetricMatchesTheReferenceDecisionsOnTheseNonBoundaryInputs()=runBlocking {
        fixtures().forEach {compareFixture(it.asJsonObject,false)}
    }
    @Test fun exactEligibilityBoundsAndTransportOrdering()=runBlocking {
        val start=point();val end=point("b",.01,100)
        suspend fun calls(distance:Double,seconds:Long=100,accuracy:Double=5.0):List<RoadTransport> {
            val calls=mutableListOf<RoadTransport>()
            RoadSnapping.build(listOf(start,end.copy(time=base.plusSeconds(seconds),accuracy=accuracy)),RoadDirections {_,_,mode->calls+=mode;null}) {_,_->distance}
            return calls
        }
        assertEquals(listOf(RoadTransport.walking,RoadTransport.automobile),calls(120.0))
        assertTrue(calls(119.999).isEmpty());assertTrue(calls(25_000.001,1000).isEmpty())
        assertEquals(listOf(RoadTransport.automobile,RoadTransport.walking),calls(25_000.0,1000))
        assertTrue(calls(5500.001).isEmpty());assertEquals(2,calls(5500.0).size)
        assertEquals(2,calls(120.0,7200).size);assertTrue(calls(120.0,7201).isEmpty())
        assertEquals(2,calls(120.0,100,120.0).size);assertTrue(calls(120.0,100,120.001).isEmpty())
        assertEquals(2,calls(1000.0,100,750.0).size);assertTrue(calls(1000.0,100,750.001).isEmpty())
        assertEquals(RoadTransport.walking,calls(320.0).first());assertEquals(RoadTransport.automobile,calls(320.001).first())
    }
    @Test fun implausibleAndInvalidDistancesFallBackWithoutModifyingSource()=runBlocking {
        val source=listOf(point(),point("b",.01,300))
        for(distance in listOf(-1.0,0.0,Double.NaN,Double.POSITIVE_INFINITY,1_000_000.0)) {
            val modes=mutableListOf<RoadTransport>();val result=RoadSnapping.build(source,RoadDirections {a,b,mode->modes+=mode;RoadDirectionsResult(distance,listOf(a,b))})
            assertEquals(2,modes.size);assertFalse(result.hasSnappedSegments);assertEquals(source.map {it.coordinate},result.segments.single().coordinates)
        }
    }
    @Test fun providerExceptionsTryAlternateThenCoalesceRaw()=runBlocking {
        val source=listOf(point(),point("b",.01,300),point("c",.01001,310));var calls=0
        val result=RoadSnapping.build(source,RoadDirections {_,_,_->calls++;throw IOException("synthetic failure")})
        assertEquals(2,calls);assertEquals(1,result.segments.size);assertEquals(2,result.segments.single().endIndex)
        assertEquals(source.map {it.coordinate},result.segments.single().coordinates)
    }
    @Test fun sourceIsFrozenBeforeSuspendingAndReturnedGeometryIsCopied()=runBlocking {
        val source=mutableListOf(point(),point("b",.01,300));val original=source.toList()
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val geometry=source.map {it.coordinate}.toMutableList()
        val job=async {RoadSnapping.build(source,RoadDirections {_,_,_->entered.complete(Unit);release.await();RoadDirectionsResult(1500.0,geometry)})}
        entered.await();source.clear();release.complete(Unit);val result=job.await();geometry.clear()
        assertEquals(2,result.sourcePointCount);assertEquals(RoadSnapping.fingerprint(original),result.fingerprint)
        assertEquals(original.map {it.coordinate},result.segments.single().coordinates)
    }
    @Test fun cancellationNeverBecomesRawFallbackOrAnotherProviderCall()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();var calls=0
        val job=async {RoadSnapping.build(listOf(point(),point("b",.01,300)),RoadDirections {a,b,_->calls++;withContext(NonCancellable) {entered.complete(Unit);release.await()};RoadDirectionsResult(1500.0,listOf(a,b))})}
        entered.await();job.cancel();release.complete(Unit);job.join();assertTrue(job.isCancelled);assertEquals(1,calls)
    }
    @Test fun lazyDisabledFingerprintAndEveryConsumedSourceFieldInvalidate() {
        assertNull(RoadSnapping.taskFingerprint(false) {error("Disabled snapping must not query history")})
        val a=point();val b=point("b",.01,300);val original=RoadSnapping.fingerprint(listOf(a,b))
        assertEquals(original,RoadSnapping.fingerprint(listOf(a,b)))
        for(changed in listOf(a.copy(id="other"),a.copy(coordinate=Coordinate(.00001,0.0)),a.copy(time=a.time.plusNanos(1)),a.copy(outlier=true),a.copy(accuracy=6.0))) assertNotEquals(original,RoadSnapping.fingerprint(listOf(changed,b)))
        assertNotEquals(original,RoadSnapping.fingerprint(listOf(b,a)))
    }
    @Test fun oversizedSourcesAreRejectedBeforeHashingOrProviderDispatch()=runBlocking {
        var calls=0
        try {
            RoadSnapping.build(List(2501) {point()},RoadDirections {_,_,_->calls++;null})
            fail("Full histories must pass through the bounded map query")
        } catch(_:IllegalArgumentException) {}
        assertEquals(0,calls)
        val bounded=RoadSnapping.build(List(2500) {point()},RoadDirections {_,_,_->calls++;null})
        assertEquals(2500,bounded.sourcePointCount);assertEquals(0,calls)
        assertEquals(2500,bounded.segments.single().coordinates.size)
    }
}
