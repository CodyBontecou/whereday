package com.bontecou.whereday.platform

import com.bontecou.whereday.data.AppSettings
import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext

/** Each visible activity owns a session; policy/clear and provider pacing remain application-owned. */
data class RoadDisplayControllerState(val configured:Boolean=false,val enabled:Boolean=false,val networkAllowed:Boolean=false,
    val loading:Boolean=true,val foregroundSessions:Int=0,val clearing:Boolean=false,val policyUnavailable:Boolean=false,
    val policyRevision:String="",val journalGeneration:String?=null,val journalReady:Boolean=false)
class RoadDisplayController(private val provider:RoadDirections?,settings:Flow<AppSettings>,
    private val readSettings:suspend()->AppSettings,private val resetConsent:suspend()->Unit,
    private val journal:StateFlow<JournalClearState>,
    private val elapsedMillis:()->Long={System.nanoTime()/1_000_000},
    private val minimumRequestIntervalMillis:Long=250,
    dispatcher:CoroutineDispatcher=Dispatchers.IO) {
    init {require(minimumRequestIntervalMillis>=0)}
    private val scope=CoroutineScope(SupervisorJob()+dispatcher)
    private val monitor=Any()
    private val sessions=mutableSetOf<Session>()
    private var policy:AppSettings?=null
    private var pendingRevision:String?=null
    private var unavailable=false
    private var maintenanceFailed=false
    private var clearing=0
    private val providerGate=Mutex()
    private var lastRequest:Long?=null
    private val mutable=MutableStateFlow(RoadDisplayControllerState(configured=provider!=null))
    val state=mutable.asStateFlow()
    private class Stamp(val session:Session,val epoch:Any,val revision:String,val generation:String?):AbstractCoroutineContextElement(Key) {
        companion object Key:CoroutineContext.Key<Stamp>
    }
    init {
        scope.launch {
            try {
                combine(settings,journal) {value,_->value}.collect {value->synchronized(monitor) {
                    policy=value;unavailable=false
                    if(pendingRevision==value.roadAccessRevision) pendingRevision=null
                    refreshLocked()
                }}
            } catch(canceled:CancellationException) {throw canceled}
            catch(_:Exception) {synchronized(monitor) {policy=null;unavailable=true;refreshLocked()}}
        }
    }
    private fun refreshLocked() {
        val value=policy;val clear=journal.value
        val ready=clearing==0 && !unavailable && !maintenanceFailed && pendingRevision==null && value!=null && !clear.blocked
        sessions.forEach {session->session.service.updateAccess(RoadDisplayAccess(provider!=null,
            ready && value?.roadDisplayEnabled==true,value?.networkGeocoding==true,
            session.foreground && !session.closed,ready,clear.generation,value?.roadAccessRevision.orEmpty()))}
        mutable.value=RoadDisplayControllerState(provider!=null,value?.roadDisplayEnabled==true,value?.networkGeocoding==true,
            value==null,sessions.count {it.foreground && !it.closed},clearing>0,unavailable || maintenanceFailed || pendingRevision!=null,
            value?.roadAccessRevision.orEmpty(),clear.generation,!clear.blocked)
    }
    /** Called before a DataStore policy write commits, including rapid off/on transitions. */
    fun invalidatePolicy(revision:String)=synchronized(monitor) {pendingRevision=revision;refreshLocked()}
    fun createSession():Session=synchronized(monitor) {
        check(scope.isActive)
        Session().also {sessions+=it;refreshLocked()}
    }
    inner class Session internal constructor() {
        internal var foreground=false
        internal var closed=false
        internal var epoch=Any()
        internal val service=RoadDisplayService(RoadDirections {a,b,mode->dispatch(this,a,b,mode)},elapsedMillis=elapsedMillis,minimumRequestIntervalMillis=0)
        fun foreground()=synchronized(monitor) {
            if(closed) return
            if(!foreground) {foreground=true;epoch=Any()}
            refreshLocked()
        }
        fun background()=synchronized(monitor) {
            if(foreground) {foreground=false;epoch=Any()}
            refreshLocked()
        }
        fun close() {
            synchronized(monitor) {
                if(closed) return
                closed=true;foreground=false;epoch=Any();refreshLocked()
            }
            // Keep closed sessions registered until their workers actually terminate.
            scope.launch {
                try {service.clearAndJoin()}
                finally {synchronized(monitor) {sessions.remove(this@Session);refreshLocked()}}
            }
        }
        suspend fun prepare(points:List<TrackPoint>,refresh:Boolean=false):RoadDisplayPlan {
            val (stamp,permit)=synchronized(monitor) {
                refreshLocked()
                if(closed || !foreground) throw RoadDisplayUnavailable()
                val permit=service.capturePermit() ?: throw RoadDisplayUnavailable()
                Stamp(this,epoch,checkNotNull(policy).roadAccessRevision,journal.value.generation) to permit
            }
            return withContext(stamp) {service.prepare(points,refresh,permit)}.also {
                if(!isCurrent(it,it.route.fingerprint)) throw CancellationException("Road display session expired")
            }
        }
        fun isCurrent(plan:RoadDisplayPlan,fingerprint:String)=synchronized(monitor) {
            refreshLocked()
            !closed && foreground && clearing==0 && pendingRevision==null && !journal.value.blocked &&
                service.isCurrent(plan,fingerprint)
        }
    }
    private suspend fun dispatch(session:Session,start:Coordinate,end:Coordinate,mode:RoadTransport):RoadDirectionsResult? {
        val stamp=currentCoroutineContext()[Stamp] ?: throw CancellationException("Road display request is unowned")
        check(stamp.session===session)
        return providerGate.withLock {
            lastRequest?.let {last->
                val elapsed=elapsedMillis()-last
                val wait=if(elapsed<0) minimumRequestIntervalMillis else (minimumRequestIntervalMillis-elapsed).coerceAtLeast(0)
                if(wait>0) delay(wait)
            }
            requireCurrent(stamp)
            lastRequest=elapsedMillis()
            checkNotNull(provider).route(start,end,mode).also {requireCurrent(stamp)}
        }
    }
    private suspend fun requireCurrent(stamp:Stamp) {
        val current=readSettings()
        currentCoroutineContext().ensureActive()
        synchronized(monitor) {
            val clear=journal.value;val session=stamp.session
            if(session.closed || !session.foreground || session.epoch!==stamp.epoch || clearing>0 || maintenanceFailed || pendingRevision!=null ||
                clear.blocked || clear.generation!=stamp.generation || !current.roadDisplayEnabled || !current.networkGeocoding ||
                current.roadAccessRevision!=stamp.revision) throw CancellationException("Road display policy changed")
        }
        currentCoroutineContext().ensureActive()
    }
    suspend fun clearAndJoin() {
        check(currentCoroutineContext()[Stamp]==null) {"Road maintenance cannot run inside a provider request"}
        val owned=synchronized(monitor) {clearing++;sessions.forEach {it.epoch=Any()};refreshLocked();sessions.toList()}
        var loaded:AppSettings?=null
        try {withContext(NonCancellable) {
            coroutineScope {owned.map {session->async {session.service.clearAndJoin()}}.awaitAll()}
            resetConsent();loaded=readSettings()
        }} finally {synchronized(monitor) {
            clearing--;policy=loaded
            if(loaded!=null && pendingRevision==loaded?.roadAccessRevision) pendingRevision=null
            maintenanceFailed=loaded==null;unavailable=loaded==null;refreshLocked()
        }}
        currentCoroutineContext().ensureActive()
    }
    internal suspend fun close() {
        clearAndJoin()
        scope.coroutineContext.job.cancelAndJoin()
    }
}
