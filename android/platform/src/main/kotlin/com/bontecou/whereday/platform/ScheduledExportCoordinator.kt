package com.bontecou.whereday.platform

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.util.AtomicFile
import com.bontecou.whereday.billing.Capability
import com.bontecou.whereday.domain.ExportSchedule
import com.bontecou.whereday.domain.InferenceRules
import com.bontecou.whereday.export.*
import com.google.gson.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.io.RandomAccessFile
import java.time.Instant
import java.time.ZoneId
import java.util.UUID
import java.util.concurrent.atomic.AtomicLong

data class AutomaticExportSettings(val enabled:Boolean=false,val schedule:ExportSchedule=ExportSchedule(),
    val format:ExportFormat=ExportFormat.json,val kind:DataKind=DataKind.all,val fileMode:ScheduledFileMode=ScheduledFileMode.rewrite,val outings:Boolean=false)
data class ScheduledExportInputs(val folder:String,val filenamePattern:String,val inference:InferenceRules?=InferenceRules())
data class PendingAutomaticExport(val id:String,val occurrence:Instant?,val plan:ScheduledExportPlan,val folder:String,val status:AutomaticExportNotice?=null) {val notice:String? get()=status?.diagnostic}
data class AutomaticExportState(val settings:AutomaticExportSettings=AutomaticExportSettings(),val lastSuccess:Instant?=null,
    val appendCursor:Instant?=null,val pending:PendingAutomaticExport?=null,val lastFailure:AutomaticExportFailure?=null,val lastStatus:AutomaticExportNotice?=null,val canceledOccurrence:Instant?=null) {
    val lastError:String? get()=lastFailure?.diagnostic
    val lastNotice:String? get()=lastStatus?.diagnostic
}
enum class AutomaticRunOutcome { exported, disabled, notDue, failed }
internal enum class AutomaticExportCheckpoint { captured, delivered, committed }

/** Owns the exact occurrence/window and atomically commits success plus cursor after verified delivery.
 * A writer receipt is authoritative after death between delivery and this journal's success commit.
 */
class ScheduledExportCoordinator internal constructor(
    context:Context,private val files:ExportFiles,private val inputs:suspend ()->ScheduledExportInputs,
    private val authorize:suspend (Capability)->Unit,private val clock:()->Instant={Instant.now()},
    private val zone:()->ZoneId={ZoneId.systemDefault()},private val checkpoint:(AutomaticExportCheckpoint)->Unit={},
) {
    private val root=File(context.filesDir,"automatic-exports")
    private val stateFile=AtomicFile(File(root,"state.json"))
    private val generation=AtomicLong()
    companion object {private val processLock=Mutex()}
    private suspend fun <T> locked(block:suspend ()->T):T=withContext(Dispatchers.IO) {
        processLock.withLock {
            check(root.mkdirs() || root.isDirectory)
            RandomAccessFile(File(root,"coordinator.lock"),"rw").use {file ->file.channel.lock().use {currentCoroutineContext().ensureActive();block()}}
        }
    }
    suspend fun snapshot():AutomaticExportState=locked {read()}
    suspend fun configure(settings:AutomaticExportSettings):AutomaticExportState {
        if(!settings.enabled) invalidate()
        if(settings.enabled) authorize(Capability.scheduledExport)
        return locked {applyConfiguration(read(),settings)}
    }
    /** Read, transform and persist under the process/file lock, including independent controller instances. */
    suspend fun updateSettings(change:(AutomaticExportSettings)->AutomaticExportSettings):AutomaticExportState=locked {
        val old=read();val settings=change(old.settings)
        if(!settings.enabled) invalidate()
        if(settings.enabled) authorize(Capability.scheduledExport)
        applyConfiguration(old,settings)
    }
    private fun applyConfiguration(old:AutomaticExportState,settings:AutomaticExportSettings):AutomaticExportState {
        if(old.pending!=null && old.settings.copy(enabled=settings.enabled)!=settings) throw AutomaticExportPendingChange()
        val resetCursor=old.settings.format!=settings.format || old.settings.kind!=settings.kind || old.settings.outings!=settings.outings || old.settings.fileMode!=settings.fileMode
        return old.copy(settings=settings,appendCursor=if(resetCursor) null else old.appendCursor,
            canceledOccurrence=if(resetCursor || old.settings.schedule!=settings.schedule) null else old.canceledOccurrence).also(::put)
    }
    suspend fun runIfDue():AutomaticRunOutcome=execute(force=false,retryOnly=false)
    suspend fun runNow():AutomaticRunOutcome=execute(force=true,retryOnly=false)
    suspend fun retryPending():AutomaticRunOutcome=execute(force=true,retryOnly=true)
    private suspend fun execute(force:Boolean,retryOnly:Boolean):AutomaticRunOutcome {
        val epoch=generation.get()
        return locked {
            var state=read()
            if(!force && !state.settings.enabled) return@locked AutomaticRunOutcome.disabled
            if(retryOnly && state.pending==null) return@locked AutomaticRunOutcome.notDue
            var completed=false
            try {
                current(epoch)
                if(state.pending==null) {
                    val now=clock();val occurrence=if(force) null else state.settings.schedule.due(now,zone(),state.lastSuccess)
                    if(!force && occurrence==null) return@locked AutomaticRunOutcome.notDue
                    if(!force && occurrence==state.canceledOccurrence) return@locked AutomaticRunOutcome.notDue
                    authorize(Capability.scheduledExport)
                    val input=inputs();val folder=Uri.parse(input.folder)
                    require(folder.scheme=="content" && DocumentsContract.isTreeUri(folder)) {"Choose an export folder in Settings first."}
                    val plan=ScheduledExportPlan.resolve(state.settings.format,ExportOptions(kind=state.settings.kind,zone=zone()),input.filenamePattern,
                        state.settings.schedule,state.settings.fileMode,state.appendCursor,now,state.settings.outings,input.inference)
                    state=state.copy(pending=PendingAutomaticExport(UUID.randomUUID().toString(),occurrence,plan,input.folder),lastFailure=null)
                    put(state);checkpoint(AutomaticExportCheckpoint.captured)
                }
                var pending=requireNotNull(state.pending)
                var receipt=files.scheduledDeliveryState(pending.id)
                if(receipt==null) {
                    val destination=files.resolveScheduledDestination(pending.plan,Uri.parse(pending.folder))
                    var prepared:PreparedExport?=null;var merged:PreparedScheduledUpdate?=null
                    try {
                        var actual=pending.plan
                        if(actual.usesDeltaWindow && destination.existing==null) {
                            actual=fullDay(actual)
                            pending=pending.copy(status=AutomaticExportNotice.missingDay)
                            state=state.copy(pending=pending);put(state)
                        }
                        prepared=files.prepareScheduled(actual)
                        if(actual.usesDeltaWindow && destination.existing!=null) {
                            try {merged=files.mergeScheduled(actual,prepared,destination.existing)}
                            catch(_:ScheduledMergeRequiresRewrite) {
                                files.discard(prepared);prepared=files.prepareScheduled(fullDay(actual))
                                pending=pending.copy(status=AutomaticExportNotice.jsonRewritten)
                                state=state.copy(pending=pending);put(state)
                            }
                        }
                        current(epoch)
                        receipt=if(merged!=null) files.startScheduledMergedDelivery(pending.id,merged,destination.parent)
                            else files.startScheduledDelivery(pending.id,prepared,destination.parent,destination.existing,destination.fingerprint)
                    } finally {
                        withContext(NonCancellable) {
                            prepared?.let {runCatching {files.discard(it)}}
                            merged?.prepared?.let {runCatching {files.discard(it)}}
                        }
                    }
                }
                current(epoch)
                check(requireNotNull(receipt).phase!=ScheduledDeliveryPhase.canceled) {"The saved delivery was canceled. Cancel this pending run before starting another."}
                val delivered=files.resumeScheduledDelivery(pending.id)
                check(delivered.phase==ScheduledDeliveryPhase.completed) {"Export delivery is not complete."}
                checkpoint(AutomaticExportCheckpoint.delivered);current(epoch)
                val success=pending.plan.filenameDate
                val cursor=if(pending.plan.shouldAdvanceAppendCursor) pending.plan.filenameDate else state.appendCursor
                state=state.copy(lastSuccess=success,appendCursor=cursor,pending=null,lastFailure=null,lastStatus=pending.status)
                put(state);completed=true;checkpoint(AutomaticExportCheckpoint.committed)
                AutomaticRunOutcome.exported
            } catch(e:Exception) {
                withContext(NonCancellable) {if(!completed) runCatching {put(read().copy(lastFailure=AutomaticExportFailure.execution))}}
                if(e is CancellationException) throw e
                AutomaticRunOutcome.failed
            }
        }
    }
    private fun fullDay(plan:ScheduledExportPlan)=plan.copy(options=plan.options.copy(from=plan.filenameDate.atZone(plan.options.zone).toLocalDate().atStartOfDay(plan.options.zone).toInstant()),usesDeltaWindow=false)
    suspend fun cancelPending():AutomaticExportState {
        invalidate()
        return locked {
            val state=read();state.pending?.let {files.cancelScheduledDelivery(it.id)}
            state.copy(pending=null,lastFailure=null,canceledOccurrence=state.pending?.occurrence ?: state.canceledOccurrence,
                lastStatus=AutomaticExportNotice.canceled).also(::put)
        }
    }
    fun invalidate() {generation.incrementAndGet();files.invalidateScheduledDelivery()}
    suspend fun clear() {
        invalidate()
        locked {
            files.clearScheduledDeliveries()
            stateFile.delete()
        }
    }
    private suspend fun current(epoch:Long) {currentCoroutineContext().ensureActive();if(epoch!=generation.get()) throw CancellationException("Automatic export configuration was cleared or disabled")}
    private fun put(state:AutomaticExportState) {
        val json=JsonObject().apply {
            addProperty("version",2)
            val c=state.settings
            add("settings",JsonObject().apply {
                addProperty("enabled",c.enabled);addProperty("hour",c.schedule.hour);addProperty("minute",c.schedule.minute);addProperty("interval",c.schedule.intervalHours)
                addProperty("format",c.format.name);addProperty("kind",c.kind.name);addProperty("fileMode",c.fileMode.name);addProperty("outings",c.outings)
            })
            state.lastSuccess?.let {addProperty("lastSuccess",it.toString())};state.appendCursor?.let {addProperty("appendCursor",it.toString())}
            state.canceledOccurrence?.let {addProperty("canceledOccurrence",it.toString())}
            state.lastFailure?.let {addProperty("lastError",it.diagnostic);addProperty("lastFailureCode",it.name)};state.lastStatus?.let {addProperty("lastNotice",it.diagnostic);addProperty("lastNoticeCode",it.name)}
            state.pending?.let {p ->add("pending",JsonObject().apply {
                addProperty("id",p.id);p.occurrence?.let {addProperty("occurrence",it.toString())};addProperty("folder",p.folder)
                addProperty("selection",ExportSelectionCodec.encode(ExportSelection(p.plan.format,options=p.plan.options,filename=p.plan.filenamePattern,outings=p.plan.outings)))
                addProperty("filenameDate",p.plan.filenameDate.toString());addProperty("mode",p.plan.effectiveFileMode.name);addProperty("delta",p.plan.usesDeltaWindow)
                addProperty("inferenceEnabled",p.plan.inference!=null)
                p.plan.inference?.let {rules ->addProperty("inferenceGap",rules.gap.toString());addProperty("inferenceDuration",rules.minimumDuration.toString());addProperty("inferencePoints",rules.minimumPoints)}
                p.status?.let {addProperty("notice",it.diagnostic);addProperty("noticeCode",it.name)}
            })}
        }
        val bytes=json.toString().toByteArray(Charsets.UTF_8);require(bytes.size<=128*1024)
        val output=stateFile.startWrite()
        try {output.write(bytes);stateFile.finishWrite(output)} catch(e:Throwable) {stateFile.failWrite(output);throw e}
    }
    private fun read():AutomaticExportState {
        val input=try {stateFile.openRead()} catch(_:java.io.FileNotFoundException) {return AutomaticExportState()}
        val json=input.use {source ->
            val output=java.io.ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) {val n=source.read(buffer);if(n<0) break;require(output.size()+n<=128*1024);output.write(buffer,0,n)}
            JsonParser.parseString(output.toString(Charsets.UTF_8.name())).asJsonObject
        }
        fun JsonObject.text(key:String):String {val v=get(key);require(v?.isJsonPrimitive==true && v.asJsonPrimitive.isString);return v.asString}
        fun JsonObject.bool(key:String):Boolean {val v=get(key);require(v?.isJsonPrimitive==true && v.asJsonPrimitive.isBoolean);return v.asBoolean}
        fun JsonObject.number(key:String):Int {val v=get(key);require(v?.isJsonPrimitive==true && v.asJsonPrimitive.isNumber);return requireNotNull(v.asString.toIntOrNull())}
        fun JsonObject.instant(key:String)=get(key)?.let {Instant.parse(text(key))}
        fun JsonObject.status(code:String,legacy:String):AutomaticExportNotice? {
            if(has(code)) return AutomaticExportNotice.entries.firstOrNull {it.name==runCatching {text(code)}.getOrNull()} ?: AutomaticExportNotice.previousStatus
            if(!has(legacy)) return null
            return AutomaticExportNotice.entries.firstOrNull {it.diagnostic==runCatching {text(legacy)}.getOrNull()} ?: AutomaticExportNotice.previousStatus
        }
        fun JsonObject.failure():AutomaticExportFailure? {
            if(has("lastFailureCode") || has("lastError")) return AutomaticExportFailure.execution
            return null
        }
        val version=json.number("version");require(version in 1..2)
        val c=json["settings"].asJsonObject
        require(version==2 || !c.has("outings"))
        val settings=AutomaticExportSettings(c.bool("enabled"),ExportSchedule(c.number("hour"),c.number("minute"),c.number("interval")),ExportFormat.valueOf(c.text("format")),DataKind.valueOf(c.text("kind")),ScheduledFileMode.valueOf(c.text("fileMode")),if(version==2) c.bool("outings") else false)
        val pending=json["pending"]?.asJsonObject?.let {p ->
            val id=p.text("id").also {require(UUID.fromString(it).toString()==it)}
            val folder=p.text("folder").also {require(Uri.parse(it).scheme=="content" && DocumentsContract.isTreeUri(Uri.parse(it)))}
            val selection=ExportSelectionCodec.decode(p.text("selection"));val date=Instant.parse(p.text("filenameDate"))
            require(selection.options.until==date && !selection.splitDays && selection.additionalFormats.isEmpty())
            val mode=ScheduledFileMode.valueOf(p.text("mode"));val delta=p.bool("delta")
            require(version==2 || !selection.outings)
            require(selection.outings==settings.outings)
            val inferenceEnabled=version==2 && p.bool("inferenceEnabled")
            if(!inferenceEnabled) require(listOf("inferenceGap","inferenceDuration","inferencePoints").none {p.has(it)})
            val inference=if(inferenceEnabled) InferenceRules(java.time.Duration.parse(p.text("inferenceGap")),java.time.Duration.parse(p.text("inferenceDuration")),p.number("inferencePoints")) else null
            require(selection.outings || inference==null)
            require(!delta || mode==ScheduledFileMode.append && selection.format !in setOf(ExportFormat.gpx,ExportFormat.kml))
            PendingAutomaticExport(id,p.instant("occurrence"),ScheduledExportPlan(selection.format,selection.options,selection.filename,date,mode,delta,selection.outings,inference),folder,p.status("noticeCode","notice"))
        }
        return AutomaticExportState(settings,json.instant("lastSuccess"),json.instant("appendCursor"),pending,json.failure(),json.status("lastNoticeCode","lastNotice"),json.instant("canceledOccurrence"))
    }
}
