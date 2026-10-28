package com.bontecou.whereday.platform

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

interface UsageAnalyticsStore {
    suspend fun read():UsageConsentState
    suspend fun write(value:UsageConsentState)
}
enum class UsageSendResult {accepted,retry,rejected}
fun interface UsageAnalyticsTransport {suspend fun send(packet:UsagePacket):UsageSendResult}
enum class UsageAnalyticsError {storage,delivery}
data class UsageAnalyticsStatus(val loaded:Boolean=false,val available:Boolean=false,val enabled:Boolean=false,val pending:Int=0,val error:UsageAnalyticsError?=null)
class UsageConsentPermit internal constructor(internal val owner:Any,internal val epoch:Long)
class UsageAnalyticsUnavailable:IllegalStateException("Usage analytics is unavailable.")
class UsageAnalyticsSettingsUnavailable:IllegalStateException("Usage analytics settings could not be saved or opened. Reset sharing before enabling it.")

/** One application/process owner. Transport cancellation must close its active HTTP request.
 * No pre-consent queue exists. Metadata and identifiers are created only inside an accepted epoch.
 */
class UsageAnalyticsCore(private val store:UsageAnalyticsStore,private val transport:UsageAnalyticsTransport?,private val metadata:()->UsageMetadata) {
    private val serial=Mutex();private val changes=Mutex();private val sends=Mutex();private val monitor=Any()
    private val epoch=AtomicLong()
    private val eventOwner=Any()
    @Volatile private var accepting=false
    private var current=UsageConsentState()
    private var activeSend:Job?=null
    private val mutable=MutableStateFlow(UsageAnalyticsStatus(available=transport!=null))
    val state=mutable.asStateFlow()
    suspend fun load() {
        val ticket=epoch.get()
        serial.withLock {
            if(mutable.value.loaded || ticket!=epoch.get()) return
            try {current=freeze(store.read());synchronized(monitor) {if(ticket==epoch.get()) accepting=current.consentId!=null && transport!=null};publish()}
            catch(e:CancellationException) {throw e}
            catch(_:Exception) {accepting=false;current=UsageConsentState();publish(UsageAnalyticsError.storage)}
        }
    }
    private fun beginChange():Long=synchronized(monitor) {accepting=false;activeSend?.cancel();epoch.incrementAndGet()}
    internal fun inhibit() {beginChange()}
    suspend fun setConsent(enabled:Boolean) {
        val ticket=beginChange()
        changes.withLock {
            if(ticket!=epoch.get()) return
            val previous=synchronized(monitor) {activeSend}
            // Persist an opt-out before waiting for a transport to finish cancelling. A crash
            // while draining must not revive the old consent or queue in the next process.
            if(!enabled) {
                serial.withLock {
                    if(ticket!=epoch.get()) return
                    try {val next=UsageConsentState();store.write(next);current=next;publish()}
                    catch(e:CancellationException) {throw e}
                    catch(_:Exception) {publish(UsageAnalyticsError.storage);throw UsageAnalyticsSettingsUnavailable()}
                }
                previous?.cancelAndJoin()
                return
            }
            previous?.cancelAndJoin()
            serial.withLock {
                if(ticket!=epoch.get()) return
                if(transport==null) throw UsageAnalyticsUnavailable()
                if(mutable.value.error==UsageAnalyticsError.storage) throw UsageAnalyticsSettingsUnavailable()
                val next=current.takeIf {it.consentId!=null} ?: UsageConsentState(UUID.randomUUID())
                try {
                    store.write(next);current=freeze(next)
                    synchronized(monitor) {if(ticket==epoch.get()) accepting=enabled}
                    publish()
                } catch(e:CancellationException) {throw e}
                catch(_:Exception) {accepting=false;publish(UsageAnalyticsError.storage);throw UsageAnalyticsSettingsUnavailable()}
            }
        }
    }
    /** Capture at the UI event boundary, before launching any asynchronous collector work. */
    fun captureConsent():UsageConsentPermit?=synchronized(monitor) {
        if(accepting) UsageConsentPermit(eventOwner,epoch.get()) else null
    }
    internal fun permits(permit:UsageConsentPermit?):Boolean=synchronized(monitor) {
        permit!=null && permit.owner===eventOwner && permit.epoch==epoch.get() && accepting
    }
    suspend fun record(event:UsageEvent,permit:UsageConsentPermit?):Boolean {
        val ticket=permit?.epoch ?: return false
        if(permit.owner!==eventOwner || !accepting) return false
        return serial.withLock {
            if(!accepting || ticket!=epoch.get()) return@withLock false
            try {
                val entry=UsagePending(UUID.randomUUID(),event,metadata())
                val next=current.copy(pending=current.pending.takeLast(49)+entry)
                store.write(next);current=freeze(next);publish()
                accepting && ticket==epoch.get()
            } catch(e:CancellationException) {throw e}
            catch(_:Exception) {accepting=false;publish(UsageAnalyticsError.storage);false}
        }
    }
    /** At-least-once receipt: retries reuse the exact consent/event UUID and saved metadata. */
    suspend fun sendNext():UsageSendResult?=sends.withLock {
        val ticket=epoch.get()
        if(!accepting) return@withLock null
        val packet=serial.withLock {
            if(!accepting || ticket!=epoch.get()) null else current.pending.firstOrNull()?.let {UsagePacket(requireNotNull(current.consentId),it)}
        } ?: return@withLock null
        supervisorScope {
            val job=async(start=CoroutineStart.LAZY) {requireNotNull(transport).send(packet)}
            val registered=synchronized(monitor) {
                if(!accepting || ticket!=epoch.get()) false else {activeSend=job;true}
            }
            if(!registered) {job.cancel();return@supervisorScope null}
            try {
                job.start()
                val result=try {job.await()} catch(e:CancellationException) {throw e} catch(_:Exception) {UsageSendResult.retry}
                serial.withLock {
                    if(!accepting || ticket!=epoch.get()) return@withLock null
                    try {
                        if(result!=UsageSendResult.retry) {
                            val next=current.copy(pending=current.pending.filterNot {it.id==packet.event.id})
                            store.write(next);current=freeze(next)
                        }
                        publish(if(result==UsageSendResult.accepted) null else UsageAnalyticsError.delivery)
                    } catch(e:CancellationException) {throw e}
                    catch(_:Exception) {accepting=false;publish(UsageAnalyticsError.storage)}
                }
                result
            } finally {synchronized(monitor) {if(activeSend===job) activeSend=null}}
        }
    }
    private fun freeze(value:UsageConsentState)=value.copy(pending=java.util.Collections.unmodifiableList(value.pending.toList()))
    private fun publish(error:UsageAnalyticsError?=null) {mutable.value=UsageAnalyticsStatus(true,transport!=null,current.consentId!=null,current.pending.size,error)}
}
