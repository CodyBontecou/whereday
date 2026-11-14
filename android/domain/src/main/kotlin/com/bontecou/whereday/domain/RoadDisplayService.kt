package com.bontecou.whereday.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

private class RoadDisplayWork:AbstractCoroutineContextElement(Key) {
    companion object Key:CoroutineContext.Key<RoadDisplayWork>
}

/** Supplied by visible-map lifecycle, explicit consent and the application's journal barrier. */
data class RoadDisplayAccess(val configured:Boolean=false,val enabled:Boolean=false,val networkAllowed:Boolean=false,
    val foreground:Boolean=false,val journalReady:Boolean=false,val journalGeneration:String?=null,val policyRevision:String="") {
    internal val admitted get()=configured && enabled && networkAllowed && foreground && journalReady
}
enum class RoadDisplayWarning { providerUnavailable, attemptTimedOut, unusableGeometry, geometryLimit }
class RoadDisplayUnavailable:IllegalStateException("Road display access is unavailable")
class RoadDisplayPlan internal constructor(val route:RoadRoute,val warnings:Set<RoadDisplayWarning>,val fromCache:Boolean,internal val owner:Any)
class RoadDisplayPermit internal constructor(internal val owner:Any)

/** Ephemeral, serial, explicitly admitted display requests. Owns no journal records or HTTP client. */
class RoadDisplayService(private val provider:RoadDirections,
    private val dispatcher:CoroutineDispatcher=Dispatchers.Default,
    private val elapsedMillis:()->Long={System.nanoTime()/1_000_000},
    private val totalTimeoutMillis:Long=60_000,private val attemptTimeoutMillis:Long=10_000,
    private val minimumRequestIntervalMillis:Long=250,
    private val maximumCacheEntries:Int=4,private val maximumCacheCoordinates:Int=50_000,
    private val cacheLifetimeMillis:Long=600_000,
    private val maximumResponseCoordinates:Int=20_000,private val maximumPreparedCoordinates:Int=50_000
) {
    init {
        require(totalTimeoutMillis>0 && attemptTimeoutMillis>0 && minimumRequestIntervalMillis>=0)
        require(maximumCacheEntries in 0..16 && maximumCacheCoordinates in 2..200_000 && cacheLifetimeMillis>0)
        require(maximumResponseCoordinates in 2..100_000 && maximumPreparedCoordinates in maximumResponseCoordinates..500_000)
    }
    private val lock=Any()
    private val serial=Mutex()
    private var access=RoadDisplayAccess()
    private var owner=Any()
    private var clearing=0
    private val requests=mutableSetOf<Deferred<RoadDisplayPlan>>()
    private data class Cached(val created:Long,val route:RoadRoute,val warnings:Set<RoadDisplayWarning>,val count:Int)
    private val cache=LinkedHashMap<String,Cached>(4,.75f,true)
    private var cachedCoordinates=0
    // Contains no coordinates; preserve spacing across policy changes and different route requests.
    private var lastRequestMillis:Long?=null
    private data class Attempt(val response:RoadDirectionsResult?)

    fun updateAccess(value:RoadDisplayAccess) {
        val canceled=synchronized(lock) {
            if(clearing>0 || value==access) return
            access=value;owner=Any();forgetCache();requests.toList()
        }
        canceled.forEach {it.cancel(CancellationException("Road display access changed"))}
    }
    fun isCurrent(plan:RoadDisplayPlan,sourceFingerprint:String):Boolean=synchronized(lock) {
        clearing==0 && access.admitted && plan.owner===owner && plan.route.fingerprint==sourceFingerprint
    }
    fun capturePermit():RoadDisplayPermit?=synchronized(lock) {
        if(clearing==0 && access.admitted) RoadDisplayPermit(owner) else null
    }
    private fun requireOwner(ticket:Any)=synchronized(lock) {
        if(clearing>0 || !access.admitted || ticket!==owner) throw CancellationException("Road display request expired")
    }
    private fun forgetCache() {cache.clear();cachedCoordinates=0}
    private fun copyRoute(route:RoadRoute)=route.copy(segments=route.segments.map {it.copy(coordinates=it.coordinates.toList())},attributions=route.attributions.toList())

    suspend fun prepare(points:List<TrackPoint>,refresh:Boolean=false,permit:RoadDisplayPermit?=null):RoadDisplayPlan=coroutineScope {
        currentCoroutineContext().ensureActive()
        val ticket=synchronized(lock) {
            if(clearing>0 || !access.admitted) throw RoadDisplayUnavailable()
            if(permit!=null && permit.owner!==owner) throw CancellationException("Road display permit expired")
            owner
        }
        require(points.size<=RoadSnapping.maximumSourcePoints) {"Road display requires a bounded map source"}
        val source=points.toList()
        val request=async(dispatcher+RoadDisplayWork(),start=CoroutineStart.LAZY) {withTimeout(totalTimeoutMillis) {serial.withLock {
            requireOwner(ticket)
            val context=currentCoroutineContext()
            val fingerprint=RoadSnapping.fingerprint(source) {context.ensureActive()}
            val hit=synchronized(lock) {
                requireOwner(ticket)
                val entry=cache[fingerprint]
                val now=elapsedMillis()
                if(entry!=null && !refresh && now>=entry.created && now-entry.created<cacheLifetimeMillis) entry else {
                    if(entry!=null) {cache.remove(fingerprint);cachedCoordinates-=entry.count};null
                }
            }
            if(hit!=null) return@withLock RoadDisplayPlan(copyRoute(hit.route),hit.warnings.toSet(),true,ticket)
            val warnings=mutableSetOf<RoadDisplayWarning>()
            var retained=0
            var limited=false
            val admittedProvider=RoadDirections {start,end,transport->
                context.ensureActive();requireOwner(ticket)
                if(limited || maximumPreparedCoordinates-retained<2) {
                    warnings+=RoadDisplayWarning.geometryLimit;null
                } else {
                    lastRequestMillis?.let {last->
                        val delta=elapsedMillis()-last
                        val wait=if(delta<0) minimumRequestIntervalMillis else (minimumRequestIntervalMillis-delta).coerceAtLeast(0)
                        if(wait>0) delay(wait)
                    }
                    context.ensureActive();requireOwner(ticket)
                    lastRequestMillis=elapsedMillis()
                    val response=try {
                        val attempt=withTimeoutOrNull(attemptTimeoutMillis) {Attempt(provider.route(start,end,transport))}
                        if(attempt==null) warnings+=RoadDisplayWarning.attemptTimedOut
                        else if(attempt.response==null) warnings+=RoadDisplayWarning.providerUnavailable
                        attempt?.response
                    } catch(canceled:CancellationException) {throw canceled}
                    catch(_:Exception) {warnings+=RoadDisplayWarning.providerUnavailable;null}
                    context.ensureActive();requireOwner(ticket)
                    if(response==null) null
                    else if(response.coordinates.size>maximumResponseCoordinates || response.coordinates.size>maximumPreparedCoordinates-retained) {
                        limited=true;warnings+=RoadDisplayWarning.geometryLimit;null
                    } else if(response.coordinates.size<2 || !response.distanceMeters.isFinite() || response.distanceMeters<=0 ||
                        response.distanceMeters>maxOf(start.distanceTo(end)+1000,start.distanceTo(end)*4)) {
                        warnings+=RoadDisplayWarning.unusableGeometry;null
                    } else {retained+=response.coordinates.size;response}
                }
            }
            val route=RoadSnapping.build(source,admittedProvider)
            context.ensureActive();requireOwner(ticket)
            val count=route.segments.sumOf {it.coordinates.size}
            synchronized(lock) {
                requireOwner(ticket)
                if(maximumCacheEntries>0 && count<=maximumCacheCoordinates) {
                    cache.put(fingerprint,Cached(elapsedMillis(),copyRoute(route),warnings.toSet(),count))?.let {cachedCoordinates-=it.count}
                    cachedCoordinates+=count
                    while(cache.size>maximumCacheEntries || cachedCoordinates>maximumCacheCoordinates) {
                        val first=cache.entries.first();cachedCoordinates-=first.value.count;cache.remove(first.key)
                    }
                }
            }
            RoadDisplayPlan(route,warnings.toSet(),false,ticket)
        }}}
        val registered=synchronized(lock) {
            if(clearing==0 && access.admitted && owner===ticket) {requests+=request;true} else false
        }
        if(!registered) {request.cancel();throw CancellationException("Road display request expired")}
        // Await can be canceled before a non-cooperative provider finishes. Keep the worker
        // registered until its actual completion so journal maintenance can still join it.
        request.invokeOnCompletion {synchronized(lock) {requests.remove(request)}}
        try {request.start();request.await().also {requireOwner(ticket)}} finally {if(!request.isCompleted) request.cancel()}
    }

    /** Fence new requests before waiting for owned work, then require fresh explicit admission. */
    suspend fun clearAndJoin() {
        check(currentCoroutineContext()[RoadDisplayWork]==null) {"Road display maintenance cannot run inside a provider request"}
        val jobs=synchronized(lock) {clearing++;access=RoadDisplayAccess();owner=Any();forgetCache();requests.toList()}
        try {
            jobs.forEach {it.cancel(CancellationException("Road display cleared"))}
            withContext(NonCancellable) {jobs.joinAll()}
        } finally {
            synchronized(lock) {requests.removeAll(jobs.toSet());clearing--;access=RoadDisplayAccess();owner=Any();forgetCache()}
        }
        currentCoroutineContext().ensureActive()
    }
}
