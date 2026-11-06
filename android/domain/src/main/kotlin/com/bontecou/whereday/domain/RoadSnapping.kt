package com.bontecou.whereday.domain

import java.nio.ByteBuffer
import java.security.MessageDigest
import java.time.Duration
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive

/** Display-only directions; callers must supply an explicitly authorized provider. */
enum class RoadTransport { walking, automobile }
enum class RoadAttributionKind {provider,data}
data class RoadAttribution(val name:String,val url:String,val kind:RoadAttributionKind=RoadAttributionKind.provider) {
    init {
        require(name.isNotBlank() && name.length<=80 && name.none(Char::isISOControl))
        val link=runCatching {java.net.URI(url)}.getOrNull()
        require(url.length<=2048 && link?.scheme=="https" && !link.host.isNullOrBlank() && link.userInfo==null)
    }
}
data class RoadDirectionsResult(val distanceMeters:Double,val coordinates:List<Coordinate>,val attributions:List<RoadAttribution> = emptyList()) {
    init {require(attributions.size<=8)}
}
fun interface RoadDirections {
    suspend fun route(start:Coordinate,end:Coordinate,transport:RoadTransport):RoadDirectionsResult?
}

data class RoadSegment(val startIndex:Int,val endIndex:Int,val coordinates:List<Coordinate>,val snapped:Boolean) {
    init {require(startIndex>=0 && endIndex>startIndex);require(coordinates.size>=2)}
    fun clipped(index:Int):RoadSegment? {
        if(snapped) return null
        val end=index.coerceIn(startIndex,endIndex)
        if(end==startIndex || coordinates.size<end-startIndex+1) return null
        return copy(endIndex=end,coordinates=coordinates.take(end-startIndex+1))
    }
}

data class RoadRoute(val fingerprint:String,val sourcePointCount:Int,val segments:List<RoadSegment>,val attributions:List<RoadAttribution> = emptyList()) {
    init {require(attributions.size<=8)}
    val hasSnappedSegments get()=segments.any {it.snapped}
    fun segmentsThrough(index:Int):List<RoadSegment> {
        if(index<=0) return emptyList()
        val visible=mutableListOf<RoadSegment>()
        for(segment in segments) {
            if(segment.endIndex<=index) {visible+=segment;continue}
            if(segment.startIndex<index) segment.clipped(index)?.let(visible::add)
            break
        }
        return visible
    }
}

/** iOS gap-selection/stitching rules over the already filtered map input.
 * Default distances use Android's shared surface metric, not MapKit's metric.
 * An injected metric permits rule comparisons against actual Swift measurements.
 */
object RoadSnapping {
    const val maximumSourcePoints=2500
    private data class Candidate(val index:Int,val start:TrackPoint,val end:TrackPoint,val distance:Double,val elapsed:Double)
    private data class Working(val start:Int,var end:Int,val coordinates:MutableList<Coordinate>,val snapped:Boolean)
    fun taskFingerprint(enabled:Boolean,points:()->List<TrackPoint>):String?=if(enabled) fingerprint(points()) else null
    fun fingerprint(points:List<TrackPoint>,checkpoint:()->Unit={}):String {
        val digest=MessageDigest.getInstance("SHA-256")
        fun number(value:Long) {digest.update(ByteBuffer.allocate(8).putLong(value).array())}
        number(points.size.toLong())
        for(point in points) {
            checkpoint()
            val id=point.id.toByteArray(Charsets.UTF_8);number(id.size.toLong());digest.update(id)
            number(point.coordinate.latitude.toBits());number(point.coordinate.longitude.toBits())
            number(point.time.epochSecond);number(point.time.nano.toLong());number(if(point.outlier) 1 else 0)
            // Accuracy participates in eligibility and therefore invalidates a prepared route.
            number(point.accuracy.toBits())
        }
        return digest.digest().joinToString("") {"%02x".format(java.util.Locale.ROOT,it.toInt() and 255)}
    }
    suspend fun build(points:List<TrackPoint>,directions:RoadDirections,distance:(Coordinate,Coordinate)->Double={a,b->a.distanceTo(b)}):RoadRoute {
        val context=currentCoroutineContext();context.ensureActive()
        require(points.size<=maximumSourcePoints) {"Road display requires a bounded map source"}
        // Freeze the source before suspending; provider output never alters recorded points.
        val source=points.toList();val fingerprint=fingerprint(source) {context.ensureActive()}
        val candidates=source.zipWithNext().mapIndexed {index,(start,end)->
            context.ensureActive()
            Candidate(index,start,end,distance(start.coordinate,end.coordinate),Duration.between(start.time,end.time).let {it.seconds+it.nano/1_000_000_000.0})
        }
        val selected=candidates.filter {candidate->
            candidate.distance.isFinite() && candidate.distance in 120.0..25_000.0 && candidate.elapsed>0 && candidate.elapsed<=7200 &&
                candidate.distance/candidate.elapsed<=55 && maxOf(candidate.start.accuracy,candidate.end.accuracy)<=maxOf(120.0,candidate.distance*.75)
        }.sortedWith(compareByDescending<Candidate> {it.distance}.thenBy {it.index}).take(40).map {it.index}.toSet()
        val segments=mutableListOf<Working>()
        val attributions=linkedSetOf<RoadAttribution>()
        for(candidate in candidates) {
            currentCoroutineContext().ensureActive()
            var snapped:List<Coordinate>?=null
            if(candidate.index in selected) {
                val modes=if(candidate.distance/maxOf(candidate.elapsed,1.0)<=3.2) listOf(RoadTransport.walking,RoadTransport.automobile) else listOf(RoadTransport.automobile,RoadTransport.walking)
                for(mode in modes) {
                    currentCoroutineContext().ensureActive()
                    val response=try {directions.route(candidate.start.coordinate,candidate.end.coordinate,mode)}
                    catch(cancelled:CancellationException) {throw cancelled}
                    catch(_:Exception) {null}
                    currentCoroutineContext().ensureActive()
                    if(response!=null && response.distanceMeters.isFinite() && response.distanceMeters>0 && response.distanceMeters<=maxOf(candidate.distance+1000,candidate.distance*4) &&
                        (attributions+response.attributions).size<=8) {
                        snapped=stitch(response.coordinates,candidate.start.coordinate,candidate.end.coordinate,distance) {context.ensureActive()}
                        if(snapped.size>=2) {attributions+=response.attributions;break}
                        snapped=null
                    }
                }
            }
            val coordinates=snapped ?: listOf(candidate.start.coordinate,candidate.end.coordinate)
            val previous=segments.lastOrNull()
            if(snapped==null && previous!=null && !previous.snapped && previous.end==candidate.index) {
                previous.coordinates.add(coordinates.last());previous.end=candidate.index+1
            } else segments+=Working(candidate.index,candidate.index+1,coordinates.toMutableList(),snapped!=null)
        }
        currentCoroutineContext().ensureActive()
        return RoadRoute(fingerprint,source.size,segments.map {RoadSegment(it.start,it.end,it.coordinates.toList(),it.snapped)},attributions.toList())
    }
    private fun stitch(route:List<Coordinate>,start:Coordinate,end:Coordinate,distance:(Coordinate,Coordinate)->Double,checkpoint:()->Unit):List<Coordinate> {
        val stitched=mutableListOf(start)
        for(coordinate in route) {checkpoint();if(distance(stitched.last(),coordinate)>2) stitched+=coordinate}
        if(distance(stitched.last(),end)<=2) stitched[stitched.lastIndex]=end else stitched+=end
        return stitched
    }
}
