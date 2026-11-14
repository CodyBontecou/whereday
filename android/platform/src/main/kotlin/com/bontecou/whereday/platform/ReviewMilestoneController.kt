package com.bontecou.whereday.platform

import android.content.Context
import android.util.AtomicFile
import com.bontecou.whereday.domain.ReviewMilestone
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.time.Clock
import java.time.LocalDate
import java.time.ZoneId
import java.util.concurrent.atomic.AtomicLong

interface ReviewMilestoneStore {suspend fun read():ReviewMilestone;suspend fun write(value:ReviewMilestone)}
class AndroidReviewMilestoneStore internal constructor(path:File):ReviewMilestoneStore {
    constructor(context:Context):this(File(context.filesDir,"review-milestone.json"))
    private val file=AtomicFile(path)
    override suspend fun read():ReviewMilestone=withContext(Dispatchers.IO) {
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists()) return@withContext ReviewMilestone()
        val raw=file.openRead().use {it.readBytesBounded()}
        val json=JsonParser.parseString(raw).asJsonObject
        require(json.get("version").toString()=="1")
        val dates=json.getAsJsonArray("days");require(dates.size()<=2)
        fun flag(name:String):Boolean {val value=json.getAsJsonPrimitive(name);require(value.isBoolean);return value.asBoolean}
        ReviewMilestone(dates.map {LocalDate.parse(it.asString)}.toSet(),flag("exported"),flag("requested"))
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
            addProperty("version",1);add("days",com.google.gson.JsonArray().apply {value.days.sorted().forEach {add(it.toString())}})
            addProperty("exported",value.exported);addProperty("requested",value.requested)
        }.toString().toByteArray(Charsets.UTF_8)
        val output=file.startWrite()
        try {output.write(json);output.fd.sync();file.finishWrite(output)} catch(error:Exception) {file.failWrite(output);throw error}
    }
}

/** Host lifetime belongs to the Activity. The controller never retains an Activity or ReviewInfo. */
interface ReviewPromptHost {fun ready():Boolean;suspend fun prepare():ReviewPromptLaunch?}
fun interface ReviewPromptLaunch {suspend fun launch()}

class ReviewMilestoneController(private val store:ReviewMilestoneStore,private val clock:Clock=Clock.systemUTC(),private val zone:()->ZoneId={ZoneId.systemDefault()},
    private val unavailable:()->Unit={}):AutoCloseable {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val serial=Mutex();private val presenting=Mutex();private val generation=AtomicLong()
    private val mutable=MutableStateFlow(ReviewMilestone())
    val state=mutable.asStateFlow()
    @Volatile private var loaded=false
    @Volatile private var failed=false
    init {scope.launch {serial.withLock {load()}}}
    private suspend fun load():Boolean {
        if(failed) return false
        if(!loaded) try {mutable.value=store.read();loaded=true} catch(canceled:CancellationException) {throw canceled} catch(_:Exception) {failed=true;unavailable()}
        return loaded && !failed
    }
    private fun record(export:Boolean) {
        val ticket=generation.get();val day=clock.instant().atZone(zone()).toLocalDate()
        scope.launch {serial.withLock {
            if(ticket!=generation.get() || !load()) return@withLock
            try {
                val next=if(export) mutable.value.fileExported(day) else mutable.value.used(day)
                if(next!=mutable.value) {store.write(next);mutable.value=next}
            } catch(canceled:CancellationException) {throw canceled} catch(_:Exception) {failed=true;unavailable()}
        }}
    }
    fun appUsed()=record(false)
    fun fileExported()=record(true)
    suspend fun maybeRequest(host:ReviewPromptHost) {
        if(!presenting.tryLock()) return
        val ticket=generation.get()
        try {
            if(!host.ready() || !serial.withLock {load() && mutable.value.eligible}) return
            val request=host.prepare() ?: return
            if(!host.ready() || ticket!=generation.get()) return
            val claimed=serial.withLock {
                if(ticket!=generation.get() || !load() || !mutable.value.eligible || !host.ready()) false
                else {val next=mutable.value.claim();try {store.write(next)} catch(error:Exception) {if(error !is CancellationException) failed=true;throw error};mutable.value=next;true}
            }
            // Consume the single attempt before entering the SDK, including quota-suppressed cards.
            // If focus is lost during persistence, don't launch over another foreground window.
            if(claimed && host.ready() && ticket==generation.get()) request.launch()
        } catch(canceled:CancellationException) {throw canceled}
        catch(_:Exception) {unavailable()}
        finally {presenting.unlock()}
    }
    suspend fun clearJournalMilestones() {
        generation.incrementAndGet()
        serial.withLock {
            val before=if(load()) mutable.value else ReviewMilestone(requested=true)
            val next=before.cleared();store.write(next);mutable.value=next;loaded=true;failed=false
        }
    }
    override fun close() {scope.cancel()}
}
