package com.bontecou.whereday.data

import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.io.BufferedOutputStream
import java.io.DataOutputStream
import java.nio.channels.Channels
import java.time.Duration
import java.time.Instant
import java.nio.file.Files
import java.security.MessageDigest
import java.security.DigestOutputStream

/** Transient private cache, never a journal mutation or a retained export. One instance per process. */
class ReplayRepository(private val root:File,private val history:HistoryRepository) {
    private val initialization=Mutex()
    private var initialized=false
    private val ownership=Mutex()
    private val clearLock=Mutex()
    private val preparing=mutableSetOf<Job>()
    private val active=mutableSetOf<ReplayArchive>()
    private var clearing=false
    private var generation=0L
    /** Excludes new snapshots until the caller's history deletion has completed. */
    suspend fun clearCache(clearHistory:suspend ()->Unit={})=clearLock.withLock {
        val caller=currentCoroutineContext().job
        withContext(NonCancellable+Dispatchers.IO) {
            val (jobs,archives)=ownership.withLock {clearing=true;generation++;preparing.toList() to active.toList()}
            try {
                jobs.filter {it!==caller}.forEach {it.cancelAndJoin()}
                archives.forEach {it.close()}
                root.listFiles()?.forEach {check(it.deleteRecursively())}
                clearHistory()
            } finally {ownership.withLock {clearing=false}}
        }
    }
    suspend fun prepare(request:ReplayRequest,displayLimit:Int=2500,progress:suspend (Long)->Unit={}):ReplayArchive {
        var owned:ReplayArchive?=null
        val job=currentCoroutineContext().job
        val epoch=ownership.withLock {check(!clearing) {"Journal data is being cleared."};preparing.add(job);generation}
        try {return withContext(Dispatchers.IO) {
        require(displayLimit in 2..2500)
        initialization.withLock {
            if(!initialized) {
                check(root.mkdirs() || root.isDirectory)
                // The shared AppGraph constructs this once: prior-process files are no longer usable.
                root.listFiles()?.forEach {check(it.deleteRecursively())}
                initialized=true
            }
        }
        val directory=Files.createTempDirectory(root.toPath(),"replay-").toFile()
        var archive:ReplayArchive?=null
        try {
            history.database.readSnapshot {dao->

                val range=when(request) {
                    is ReplayRequest.Range->request.range
                    is ReplayRequest.Outing->{val session=dao.session(request.id)?.value ?: error("This outing was deleted.");HistoryRange(session.startedAt,(session.endedAt ?: Instant.now()).coerceAtLeast(session.startedAt).plusMillis(1))}
                    is ReplayRequest.Inferred->HistoryRange(request.header.startedAt,request.header.endedAt.plusMillis(1))
                }
                val sessions=if(request is ReplayRequest.Inferred) dao.rangeSessions(range.from,range.until).map {it.value} else emptyList()
                fun included(point:TrackPoint)=sessions.none {point.time>=it.startedAt && (it.endedAt==null || point.time<=it.endedAt)}
                var good=0L
                history.forEachPoint(range) {if(included(it) && !it.outlier) good++}
                // iOS outing distancePoints falls back to all points if every point is an outlier.
                val outliers=if(request is ReplayRequest.Range) request.includeOutliers else good==0L
                val data=RandomAccessFile(File(directory,"points"),"rw")
                val offsets=try {RandomAccessFile(File(directory,"offsets"),"rw")} catch(error:Throwable) {data.close();throw error}
                try {
                    var count=0L;var distance=0.0;var first:Instant?=null;var previous:TrackPoint?=null;var offset=0L
                    val endpoints=RouteEndpointBuilder()
                    val digest=MessageDigest.getInstance("SHA-256")
                    val pointWriter=DataOutputStream(BufferedOutputStream(DigestOutputStream(Channels.newOutputStream(data.channel),digest)))
                    val offsetWriter=DataOutputStream(BufferedOutputStream(Channels.newOutputStream(offsets.channel)))
                    history.forEachPoint(range) {point->
                        currentCoroutineContext().ensureActive()
                        val eligible=included(point)
                        if(eligible) endpoints.add(point)
                        if(eligible && (!point.outlier || outliers)) {
                            previous?.let {distance+=it.coordinate.distanceTo(point.coordinate)}
                            offsetWriter.writeLong(offset)
                            pointWriter.writeDouble(distance)
                            val id=point.id.toByteArray(Charsets.UTF_8);pointWriter.writeInt(id.size);pointWriter.write(id)
                            pointWriter.writeLong(point.time.toEpochMilli());pointWriter.writeDouble(point.coordinate.latitude);pointWriter.writeDouble(point.coordinate.longitude)
                            pointWriter.writeDouble(point.accuracy);pointWriter.writeDouble(point.altitude ?: Double.NaN);pointWriter.writeDouble(point.speed ?: Double.NaN);pointWriter.writeBoolean(point.outlier)
                            offset+=61L+id.size
                            first=first ?: point.time;previous=point;count++
                            if(count%500==0L) progress(count)
                        }
                    }
                    pointWriter.flush();offsetWriter.flush()
                    require(count>=2) {"Replay needs at least two route points."}
                    currentCoroutineContext().ensureActive()
                    val fingerprint=digest.digest().joinToString("") {"%02x".format(java.util.Locale.ROOT,it.toInt() and 255)}
                    archive=ReplayArchive(directory,data,offsets,count,fingerprint,dao.rangeVisits(range.from,range.until).map {it.value},endpoints.build(),first!!,previous!!.time,distance,displayLimit) {closed->ownership.withLock {active.remove(closed)};Unit}
                } finally {if(archive==null) {data.close();offsets.close()}}
            }
            owned=archive
            ownership.withLock {check(!clearing && epoch==generation) {"Journal data was cleared."};active.add(archive!!)}
            archive!!
        } catch(error:Throwable) {withContext(NonCancellable) {archive?.close()};directory.deleteRecursively();throw error}
        }} catch(error:Throwable) {withContext(NonCancellable) {owned?.close()};throw error}
        finally {withContext(NonCancellable) {ownership.withLock {preparing.remove(job)}}}
    }
}

/** Every original fix is indexed on disk. Only the current sampled prefix reaches the renderer. */
class ReplayArchive internal constructor(private val directory:File,private val data:RandomAccessFile,private val offsets:RandomAccessFile,
    val count:Long,val fingerprint:String,val visits:List<Visit>,val endpoints:RouteEndpoints,private val first:Instant,private val last:Instant,private val totalDistance:Double,private val displayLimit:Int,private val onClosed:suspend (ReplayArchive)->Unit) {
    private val lock=Mutex()
    private var closed=false
    private fun read(index:Long):Pair<TrackPoint,Double> {
        offsets.seek(index*8);data.seek(offsets.readLong())
        val distance=data.readDouble();val idSize=data.readInt();require(idSize>=0 && idSize.toLong()<=data.length()-data.filePointer-49) {"Replay cache is incomplete."}
        val idBytes=ByteArray(idSize);data.readFully(idBytes)
        val time=Instant.ofEpochMilli(data.readLong());val coordinate=Coordinate(data.readDouble(),data.readDouble())
        val accuracy=data.readDouble();val altitude=data.readDouble().takeUnless {it.isNaN()};val speed=data.readDouble().takeUnless {it.isNaN()}
        return TrackPoint(id=String(idBytes,Charsets.UTF_8),coordinate=coordinate,time=time,accuracy=accuracy,altitude=altitude,speed=speed,outlier=data.readBoolean()) to distance
    }
    suspend fun frame(index:Long):ReplayFrame=withContext(Dispatchers.IO) {lock.withLock {
        check(!closed) {"Replay was closed."}
        val cursor=index.coerceIn(0,count-1)
        val visible=ArrayList<TrackPoint>(minOf(cursor+1,displayLimit.toLong()).toInt())
        val size=minOf(cursor+1,displayLimit.toLong()).toInt()
        repeat(size) {i->
            currentCoroutineContext().ensureActive()
            val ordinal=if(size==1) 0 else (cursor.toDouble()*i/(size-1)).toLong()
            visible.add(read(ordinal).first)
        }
        val (current,distance)=read(cursor)
        ReplayFrame(cursor,count,current,visible,Duration.between(first,current.time),Duration.between(first,last),distance,totalDistance)
    }}
    private data class MarkerCache(val viewport:MapPointViewport,val selected:String?,val sampler:MapPointMarkerSampler,var index:Long = -1)
    private var markerCache:MarkerCache?=null
    /** Reuse the bounded grid for forward playback; a seek backwards rebuilds its exact prefix. */
    suspend fun pointMarkers(viewport:MapPointViewport,index:Long,selectedPointId:String?=null):MapPointMarkers = withContext(Dispatchers.IO) {lock.withLock {
        check(!closed) {"Replay was closed."}
        val cursor=index.coerceIn(0,count-1)
        val cache=markerCache?.takeIf {it.viewport==viewport && it.selected==selectedPointId && it.index<=cursor}
            ?: MarkerCache(viewport,selectedPointId,MapPointMarkerSampler(viewport,selectedPointId)).also {markerCache=it}
        while(cache.index<cursor) {
            currentCoroutineContext().ensureActive()
            val next=cache.index+1;cache.sampler.add(read(next).first);cache.index=next
        }
        currentCoroutineContext().ensureActive();cache.sampler.snapshot()
    }}
    suspend fun close()=withContext(Dispatchers.IO) {lock.withLock {
        if(!closed) {closed=true;markerCache=null;try {data.close()} finally {try {offsets.close()} finally {directory.deleteRecursively();onClosed(this@ReplayArchive)}}}
    }}
}
