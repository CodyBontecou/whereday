package com.bontecou.whereday.platform

import android.content.Context
import android.util.AtomicFile
import com.bontecou.whereday.domain.ReviewMilestone
import com.bontecou.whereday.domain.ReviewSuccess
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

interface ReviewMilestoneStore {suspend fun read():ReviewMilestone;suspend fun write(value:ReviewMilestone)}
class AndroidReviewMilestoneStore internal constructor(path:File,private val clock:Clock=Clock.systemUTC()):ReviewMilestoneStore {
    constructor(context:Context):this(File(context.filesDir,"review-milestone.json"))
    private val file=AtomicFile(path)
    override suspend fun read():ReviewMilestone=withContext(Dispatchers.IO) {
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists()) return@withContext ReviewMilestone()
        val raw=file.openRead().use {it.readBytesBounded()}
        val json=JsonParser.parseString(raw).asJsonObject
        fun flag(name:String):Boolean {val value=json.getAsJsonPrimitive(name);require(value.isBoolean);return value.asBoolean}
        fun text(name:String):String? {val value=json.get(name);if(value==null || value.isJsonNull) return null;require(value.isJsonPrimitive && value.asJsonPrimitive.isString);return value.asString}
        fun number(name:String):Long? {val value=json.get(name);if(value==null || value.isJsonNull) return null;require(value.isJsonPrimitive && value.asJsonPrimitive.isNumber);return value.asLong}
        when(json.get("version").toString()) {
            "1"->{
                // Single-shot-ever state: keep the use days and start the cooldown now for someone already asked.
                val dates=json.getAsJsonArray("days");require(dates.size()<=2)
                ReviewMilestone.migrated(dates.map {LocalDate.parse(it.asString)}.toSet(),flag("requested"),clock.instant()).also {write(it)}
            }
            "2"->{
                val days=number("useDays");require(days!=null && days in 0..ReviewMilestone.maxUseDays.toLong())
                ReviewMilestone(days.toInt(),text("lastUseDay")?.let(LocalDate::parse),text("requestedVersion"),number("requestedAt")?.let(Instant::ofEpochMilli))
            }
            else->throw IllegalArgumentException("Unsupported review milestone version")
        }
    }
    private fun java.io.InputStream.readBytesBounded():String {
        val raw=ByteArray(4097);var size=0
        while(size<raw.size) {val count=read(raw,size,raw.size-size);if(count<0) break;size+=count}
        require(size<=4096)
        return Charsets.UTF_8.newDecoder().onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
            .decode(java.nio.ByteBuffer.wrap(raw,0,size)).toString()
    }
    override suspend fun write(value:ReviewMilestone)=withContext(Dispatchers.IO) {
        check(file.baseFile.parentFile!!.isDirectory || file.baseFile.parentFile!!.mkdirs())
        val json=com.google.gson.JsonObject().apply {
            addProperty("version",2);addProperty("useDays",value.useDays);addProperty("lastUseDay",value.lastUseDay?.toString())
            addProperty("requestedVersion",value.requestedVersion);addProperty("requestedAt",value.requestedAt?.toEpochMilli())
        }.let {com.google.gson.GsonBuilder().serializeNulls().create().toJson(it)}.toByteArray(Charsets.UTF_8)
        val output=file.startWrite()
        try {output.write(json);output.fd.sync();file.finishWrite(output)} catch(error:Exception) {file.failWrite(output);throw error}
    }
}

/** Host lifetime belongs to the Activity. The controller never retains an Activity or ReviewInfo. */
interface ReviewPromptHost {fun ready():Boolean;suspend fun prepare():ReviewPromptLaunch?}
fun interface ReviewPromptLaunch {suspend fun launch()}

/** A qualifying success that the foreground Activity may turn into a review request once its window is clear. */
data class ReviewSuccessMoment(val id:Long,val source:ReviewSuccess,val at:Instant)

/** Requests come only from success moments (export, import, purchase), never from launch or resume. */
class ReviewMilestoneController(private val store:ReviewMilestoneStore,private val appVersion:String,private val clock:Clock=Clock.systemUTC(),
    private val zone:()->ZoneId={ZoneId.systemDefault()},private val unavailable:()->Unit={},
    /** A success that can't be shown in this window (another dialog stays up) is dropped; the next success can ask. */
    private val successWindow:Duration=Duration.ofSeconds(30)):AutoCloseable {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val serial=Mutex();private val presenting=Mutex();private val generation=AtomicLong();private val moments=AtomicLong()
    private val mutable=MutableStateFlow(ReviewMilestone())
    val state=mutable.asStateFlow()
    private val pending=MutableStateFlow<ReviewSuccessMoment?>(null)
    val pendingSuccess=pending.asStateFlow()
    @Volatile private var loaded=false
    @Volatile private var failed=false
    init {scope.launch {serial.withLock {load()}}}
    private suspend fun load():Boolean {
        if(failed) return false
        if(!loaded) try {mutable.value=store.read();loaded=true} catch(canceled:CancellationException) {throw canceled} catch(_:Exception) {failed=true;unavailable()}
        return loaded && !failed
    }
    private fun record(success:ReviewSuccess?) {
        val ticket=generation.get();val day=clock.instant().atZone(zone()).toLocalDate()
        scope.launch {serial.withLock {
            if(ticket!=generation.get() || !load()) return@withLock
            try {
                val next=mutable.value.used(day)
                if(next!=mutable.value) {store.write(next);mutable.value=next}
            } catch(canceled:CancellationException) {throw canceled} catch(_:Exception) {failed=true;unavailable();return@withLock}
            if(success!=null) pending.value=ReviewSuccessMoment(moments.incrementAndGet(),success,clock.instant())
        }}
    }
    /** Counts a day of use only. */
    fun appUsed()=record(null)
    /** Call where the success happens. Non-qualifying sources are ignored entirely. */
    fun succeeded(source:ReviewSuccess) {if(source.qualifies) record(source)}
    suspend fun maybeRequest(host:ReviewPromptHost) {
        if(!presenting.tryLock()) return
        val ticket=generation.get()
        try {
            val moment=pending.value ?: return
            if(clock.instant().isAfter(moment.at.plus(successWindow))) {pending.compareAndSet(moment,null);return}
            if(!host.ready()) return
            if(!serial.withLock {load() && mutable.value.eligible(appVersion,clock.instant())}) {pending.compareAndSet(moment,null);return}
            val request=host.prepare() ?: return
            if(!host.ready() || ticket!=generation.get()) return
            val claimed=serial.withLock {
                val now=clock.instant()
                if(ticket!=generation.get() || !load() || !mutable.value.eligible(appVersion,now) || !host.ready()) false
                else {val next=mutable.value.claim(appVersion,now);try {store.write(next)} catch(error:Exception) {if(error !is CancellationException) failed=true;throw error};mutable.value=next;true}
            }
            // Record the attempt as the request is made, before entering the SDK (Play may suppress the card).
            // If focus is lost during persistence, don't launch over another foreground window.
            if(claimed) {pending.compareAndSet(moment,null);if(host.ready() && ticket==generation.get()) request.launch()}
        } catch(canceled:CancellationException) {throw canceled}
        catch(_:Exception) {unavailable()}
        finally {presenting.unlock()}
    }
    suspend fun clearJournalMilestones() {
        generation.incrementAndGet();pending.value=null
        serial.withLock {
            // Unreadable state fails closed: treat it as just asked, so the cooldown applies.
            val before=if(load()) mutable.value else ReviewMilestone(requestedAt=clock.instant())
            val next=before.cleared();store.write(next);mutable.value=next;loaded=true;failed=false
        }
    }
    override fun close() {scope.cancel()}
}
