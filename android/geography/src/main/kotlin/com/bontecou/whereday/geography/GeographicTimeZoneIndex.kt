package com.bontecou.whereday.geography

import com.bontecou.whereday.domain.Coordinate
import com.esri.core.geometry.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.time.ZoneId
import java.util.LinkedHashMap

data class BoundaryQueryMetrics(val candidates: Int=0,val loadedPolygonBytes: Long=0,val cachedGeometryBytes: Long=0)

/** Worldwide coordinates are retained exactly; only candidate polygons enter geometry memory. */
class GeographicTimeZoneIndex(private val open: (String)->InputStream,private val maximumCachedBytes: Long=4L*1024*1024) {
    private data class Entry(val id: Int,val zone: String,val bytes: Long,val minLat: Double,val minLon: Double,val maxLat: Double,val maxLon: Double) {
        // Broad-phase padding only; the original polygon is always the final authority.
        fun near(p: Coordinate)=p.latitude>=minLat-0.00001 && p.latitude<=maxLat+0.00001 && p.longitude>=minLon-0.00001 && p.longitude<=maxLon+0.00001
    }
    private data class Cached(val polygon: Polygon,val bytes: Long)
    private val mutex=Mutex()
    private var entries: List<Entry>?=null
    private val cache=LinkedHashMap<Int,Cached>(16,0.75f,true)
    private var cachedBytes=0L
    @Volatile var metrics=BoundaryQueryMetrics(); private set
    init { require(maximumCachedBytes>=0) }

    suspend fun prepare()=withContext(Dispatchers.Default) { mutex.withLock { loadIndex() } }
    private suspend fun loadIndex() {
        if(entries!=null) return
        val zoneNames=mutableMapOf<String,String>()
        val parsed=DataInputStream(BufferedInputStream(open("index.bin"))).use { input ->
            require(input.readInt()==MAGIC && input.readUTF()==VERSION) { "Unsupported geographic boundary data" }
            require(input.readUTF().matches(Regex("[0-9a-f]{64}"))) { "Missing boundary provenance" }
            val count=input.readInt(); require(count in 1..100_000)
            val result=ArrayList<Entry>(count)
            repeat(count) { id ->
                if(id%256==0) currentCoroutineContext().ensureActive()
                require(input.readInt()==id)
                val name=input.readUTF()
                val zone=zoneNames.getOrPut(name) { name }
                val bytes=input.readLong()
                val minLat=input.readDouble(); val minLon=input.readDouble(); val maxLat=input.readDouble(); val maxLon=input.readDouble()
                require(bytes in 1..256L*1024*1024 && minLat.isFinite() && minLon.isFinite() && maxLat.isFinite() && maxLon.isFinite())
                require(minLat in -90.0..90.0 && maxLat in minLat..90.0 && minLon in -180.0..180.0 && maxLon in minLon..180.0)
                result.add(Entry(id,zone,bytes,minLat,minLon,maxLat,maxLon))
            }
            require(input.read()==-1)
            result
        }
        entries=parsed
    }
    suspend fun query(coordinate: Coordinate): String?=queryAll(coordinate).singleOrNull()
    suspend fun queryAll(coordinate: Coordinate): Set<String> = withContext(Dispatchers.Default) { mutex.withLock {
        loadIndex()
        val point=Point(coordinate.longitude,coordinate.latitude)
        val candidates=entries!!.filter { it.near(coordinate) }
        val matches=linkedSetOf<String>()
        var loaded=0L
        for(entry in candidates) {
            currentCoroutineContext().ensureActive()
            if(entry.zone !in SUPPORTED_ZONES) continue
            val polygon=cache[entry.id]?.polygon ?: run {
                // Evict before allocation, including all cached geometry for a large polygon.
                evictTo((maximumCachedBytes-entry.bytes*8).coerceAtLeast(0))
                val value=readPolygon(entry)
                loaded+=entry.bytes
                // Esri's estimateMemorySize() uses desktop sun.misc.Unsafe fields absent on Android.
                // Account conservatively for grown double buffers, path arrays and object overhead.
                val size=value.pointCount*64L+value.pathCount*128L+4096L
                if(size<=maximumCachedBytes) {
                    evictTo(maximumCachedBytes-size)
                    cache[entry.id]=Cached(value,size); cachedBytes+=size
                }
                value
            }
            if(OperatorIntersects.local().execute(polygon,point,SPATIAL_REFERENCE,null)) matches.add(entry.zone)
        }
        metrics=BoundaryQueryMetrics(candidates.size,loaded,cachedBytes)
        matches
    } }
    private suspend fun readPolygon(entry: Entry): Polygon {
        val polygon=Polygon()
        DataInputStream(BufferedInputStream(open("p-${entry.id}.bin"))).use { input ->
            val rings=input.readInt(); require(rings in 1..1_000_000)
            var bytes=4L
            repeat(rings) {
                val count=input.readInt(); bytes+=4; require(count>=3 && count<=entry.bytes/8)
                repeat(count) { i ->
                    if(i%4096==0) currentCoroutineContext().ensureActive()
                    val lon=input.readFloat().toDouble(); val lat=input.readFloat().toDouble(); bytes+=8
                    require(lon.isFinite() && lat.isFinite() && lon in -180.0..180.0 && lat in -90.0..90.0 && bytes<=entry.bytes)
                    if(i==0) polygon.startPath(lon,lat) else polygon.lineTo(lon,lat)
                }
            }
            require(bytes==entry.bytes && input.read()==-1) { "Truncated geographic polygon" }
        }
        return polygon
    }
    private fun evictTo(limit: Long) {
        val iterator=cache.entries.iterator()
        while(cachedBytes>limit && iterator.hasNext()) { cachedBytes-=iterator.next().value.bytes; iterator.remove() }
    }
    suspend fun release() { mutex.withLock { cache.clear(); cachedBytes=0; entries=null; metrics=BoundaryQueryMetrics() } }
    companion object {
        const val VERSION="timeshape-2026b.29-f32-polygons-v1"
        private const val MAGIC=0x57445A31
        private val SUPPORTED_ZONES=ZoneId.getAvailableZoneIds()
        private val SPATIAL_REFERENCE=SpatialReference.create(4326)
    }
}
