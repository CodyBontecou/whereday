package com.bontecou.whereday.platform

import com.bontecou.whereday.data.AppSettings
import java.net.URI
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

/** Supply reviewed provider identity/privacy information together with the configured routing adapter. */
data class RoadProviderDisclosure(val name:String,val privacyUrl:String) {
    init {
        require(name.isNotBlank() && name.length<=80 && name.none {it.isISOControl()})
        val uri=URI(privacyUrl)
        require(uri.scheme=="https" && !uri.host.isNullOrBlank() && uri.userInfo==null && privacyUrl.length<=2048)
    }
}
class RoadConsentReview internal constructor(val id:Long,internal val epoch:Any,internal val generation:String?,internal val revision:String)
enum class RoadConsentFailure {stale,storage}
data class RoadConsentState(val review:RoadConsentReview?=null,val busy:Boolean=false,val failure:RoadConsentFailure?=null,val saved:Boolean=false,val savedEnabled:Boolean?=null)

/** A settings-screen lifetime; acceptance is single-use and rechecked inside the atomic preference edit. */
class RoadConsentSession(val provider:RoadProviderDisclosure?,private val configured:()->Boolean,
    private val journal:StateFlow<JournalClearState>,private val updatePreferences:suspend((AppSettings)->AppSettings)->Unit,
    dispatcher:CoroutineDispatcher=Dispatchers.IO,private val beforeDisable:()->Unit={}) {
    private val scope=CoroutineScope(SupervisorJob()+dispatcher)
    private val monitor=Any()
    private var foreground=false
    private var closed=false
    private var epoch=Any()
    private var sequence=0L
    private var active:Job?=null
    private var activeEnabling=false
    private val mutable=MutableStateFlow(RoadConsentState())
    val state=mutable.asStateFlow()
    fun foreground()=synchronized(monitor) {if(!closed && !foreground) {foreground=true;epoch=Any()}}
    fun background()=synchronized(monitor) {
        foreground=false;epoch=Any();if(activeEnabling) active?.cancel();mutable.value=mutable.value.copy(review=null)
    }
    fun review(settings:AppSettings)=synchronized(monitor) {
        if(closed || !foreground || mutable.value.busy || provider==null || !configured() || journal.value.blocked ||
            !settings.networkGeocoding || settings.roadDisplayEnabled) return
        mutable.value=RoadConsentState(review=RoadConsentReview(++sequence,epoch,journal.value.generation,settings.roadAccessRevision))
    }
    fun dismiss(id:Long)=synchronized(monitor) {if(mutable.value.review?.id==id) mutable.value=mutable.value.copy(review=null)}
    fun isCurrent(review:RoadConsentReview)=synchronized(monitor) {
        !closed && foreground && review===mutable.value.review && epoch===review.epoch && !journal.value.blocked && journal.value.generation==review.generation
    }
    fun confirm(id:Long):Job?=synchronized(monitor) {
        val ticket=mutable.value.review?.takeIf {it.id==id} ?: return null
        mutable.value=mutable.value.copy(review=null)
        submit(true,ticket)
    }
    fun disable():Job?=synchronized(monitor) {
        if(closed || !foreground || mutable.value.busy || journal.value.blocked) return null
        try {beforeDisable()} catch(_:Exception) {mutable.value=RoadConsentState(failure=RoadConsentFailure.storage);return null}
        submit(false,RoadConsentReview(++sequence,epoch,journal.value.generation,""))
    }
    private class Expired:IllegalStateException()
    private fun submit(enabled:Boolean,ticket:RoadConsentReview):Job? {
        if(closed || !foreground || mutable.value.busy) return null
        activeEnabling=enabled
        mutable.value=RoadConsentState(busy=true)
        return scope.launch(start=CoroutineStart.LAZY) {
            val operation=currentCoroutineContext().job
            try {
                updatePreferences {latest->synchronized(monitor) {
                    if(!operation.isActive || (enabled && (closed || !foreground || epoch!==ticket.epoch || journal.value.blocked || journal.value.generation!=ticket.generation ||
                        provider==null || !configured() || !latest.networkGeocoding || latest.roadAccessRevision!=ticket.revision))) throw Expired()
                    latest.copy(roadDisplayEnabled=enabled)
                }}
                mutable.value=RoadConsentState(saved=true,savedEnabled=enabled)
            } catch(canceled:CancellationException) {throw canceled}
            catch(_:Expired) {mutable.value=RoadConsentState(failure=RoadConsentFailure.stale)}
            catch(_:Exception) {mutable.value=RoadConsentState(failure=RoadConsentFailure.storage)}
            finally {mutable.update {it.copy(busy=false)}}
        }.also {active=it;it.start()}
    }
    fun close()=synchronized(monitor) {
        closed=true;foreground=false;epoch=Any();mutable.value=RoadConsentState()
        // A user's revocation survives leaving Settings; only queued approvals expire with the screen.
        val pending=active
        if(pending?.isActive==true && !activeEnabling) pending.invokeOnCompletion {scope.cancel()} else scope.cancel()
    }
}
