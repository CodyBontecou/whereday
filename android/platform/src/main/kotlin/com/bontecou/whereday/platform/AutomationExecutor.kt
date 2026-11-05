package com.bontecou.whereday.platform

import com.bontecou.whereday.data.JournalCommands
import com.bontecou.whereday.domain.*
import com.google.gson.stream.JsonWriter
import kotlinx.coroutines.*
import java.io.FilterOutputStream
import java.io.OutputStreamWriter
import java.time.Clock
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/** The Activity binding must report actual runtime state and enforce visible-start restrictions. */
interface AutomationRecording {
    suspend fun status():AutomationRecordingStatus
    suspend fun start()
    suspend fun stop()
}
data class AutomationRecordingStatus(val active:Boolean,val elapsedSeconds:Long?) {
    init {require(elapsedSeconds==null || elapsedSeconds>=0);require(active || elapsedSeconds==null || elapsedSeconds==0L)}
}

/** All results are files, keeping Binder replies bounded and approval checks in one owner. */
class AutomationExecutor(private val commands:JournalCommands,private val results:AutomationResults,
    private val recording:AutomationRecording,private val exports:ExportFiles,private val exportInference:suspend ()->InferenceRules?,private val clock:Clock=Clock.systemUTC(),
    private val zone:()->ZoneId={ZoneId.systemDefault()},private val locale:()->Locale={Locale.getDefault()},
    private val currentLocation:suspend ()->Coordinate={throw AutomationLocationUnavailable()}) {
    suspend fun execute(callingPackage:String?,request:AutomationRequest):AutomationResult {
        if(request.command==AutomationCommand.export) return export(callingPackage,request)
        return results.create(callingPackage,request.command.scope,"whereday-result.json","application/json") {output ->
            val writer=JsonWriter(OutputStreamWriter(output,Charsets.UTF_8)).apply {serializeNulls=true}
            writer.beginObject().name("version").value(1).name("command").value(request.command.name).name("data")
            when(request.command) {
                AutomationCommand.statistics -> {
                    val stats=commands.statistics(request.range(clock,zone()))
                    val runtime=recording.status()
                    writer.beginObject().name("pointCount").value(stats.points.count).name("outlierCount").value(stats.points.outlierCount)
                        .name("visitCount").value(stats.visitCount).name("distanceMeters").value(stats.points.distanceMeters)
                    writeRuntime(writer,runtime);writer.endObject()
                }
                AutomationCommand.visits -> {
                    writer.beginArray();commands.forEachVisit(request.range(clock,zone())) {visit(writer,it)};writer.endArray()
                }
                AutomationCommand.outings -> {
                    writer.beginArray();commands.forEachOuting(request.range(clock,zone())) {outing(writer,it)};writer.endArray()
                }
                AutomationCommand.savedPlaces -> {
                    writer.beginArray();commands.forEachSavedPlace {
                        writer.beginObject().name("id").value(it.id).name("name").value(it.name).endObject()
                    };writer.endArray()
                }
                AutomationCommand.openManualVisits -> {
                    writer.beginArray();commands.forEachOpenManualVisit {visit(writer,it)};writer.endArray()
                }
                AutomationCommand.checkIn -> {
                    val arrival=request.visitAt ?: clock.instant()
                    require(arrival<=clock.instant()) {"Choose an arrival time at or before now."}
                    val coordinate=request.coordinate ?: if(request.savedPlaceId==null) currentLocation() else null
                    currentCoroutineContext().ensureActive()
                    visit(writer,commands.checkIn(request.name,request.savedPlaceId,coordinate,arrival,request.allowOverlap))
                }
                AutomationCommand.checkOut ->visit(writer,commands.checkOut(request.itemId,request.visitAt))
                AutomationCommand.currentOuting ->outing(writer,commands.currentOuting())
                AutomationCommand.renameVisit ->visit(writer,commands.renameVisit(requireNotNull(request.itemId),requireNotNull(request.name)))
                AutomationCommand.renameOuting ->outing(writer,commands.renameOuting(requireNotNull(request.itemId),requireNotNull(request.name)))
                AutomationCommand.renameCurrentOuting ->outing(writer,commands.renameCurrentOuting(requireNotNull(request.name)))
                AutomationCommand.startRecording,AutomationCommand.stopRecording -> {
                    if(request.command==AutomationCommand.startRecording) recording.start() else recording.stop()
                    writer.beginObject().name("requestAccepted").value(true)
                    writeRuntime(writer,recording.status());writer.endObject()
                }
                AutomationCommand.export ->error("Export uses its file path")
            }
            writer.endObject();writer.flush() // The result store owns the stream and its fsync.
        }
    }
    private fun writeRuntime(writer:JsonWriter,status:AutomationRecordingStatus) {
        writer.name("recordingActive").value(status.active).name("currentRecordingSeconds").value(status.elapsedSeconds)
    }
    private fun visit(writer:JsonWriter,value:Visit) {
        writer.beginObject().name("id").value(value.id).name("name").value(value.name?.trim()?.takeIf {it.isNotEmpty()} ?: value.address?.trim()?.takeIf {it.isNotEmpty()} ?: "Visit")
            .name("address").value(value.address).name("arrivedAt").value(value.arrivedAt.toString()).name("departedAt").value(value.departedAt?.toString()).endObject()
    }
    private fun outing(writer:JsonWriter,value:RecordingSession) {
        val name=value.name?.trim()?.takeIf {it.isNotEmpty()} ?: DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale()).withZone(zone()).format(value.startedAt)
        writer.beginObject().name("id").value(value.id).name("name").value(name).name("startedAt").value(value.startedAt.toString()).name("endedAt").value(value.endedAt?.toString()).endObject()
    }
    private suspend fun export(caller:String?,request:AutomationRequest):AutomationResult =
        results.create(caller,AutomationScope.exports,"whereday-export.zip","application/zip") {output ->
            val selection=requireNotNull(request.selection)
            val prepared=exports.prepare(ExportFileRequest(setOf(selection.format)+selection.additionalFormats,selection.options,
                selection.filename,selection.splitDays,generatedAt=clock.instant(),outings=selection.outings,inference=exportInference()))
            try {
                // Closing the ZIP releases its native deflater, but must not close the store's stream.
                ZipOutputStream(object:FilterOutputStream(output) {
                    override fun write(bytes:ByteArray,offset:Int,length:Int) {output.write(bytes,offset,length)}
                    override fun close() {flush()}
                }).use {zip ->
                    val names=mutableSetOf<String>()
                    exports.readPrepared(prepared) {file,input ->
                        val name=file.relativePath
                        require(name.isNotBlank() && !name.startsWith('/') && name.split('/').all {it.isNotBlank() && it!="." && it!=".."} && '\\' !in name && names.add(name))
                        zip.putNextEntry(ZipEntry(name))
                        val buffer=ByteArray(64*1024)
                        while(true) {currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0) break;zip.write(buffer,0,n)}
                        zip.closeEntry()
                    }
                }
            } finally {withContext(NonCancellable+Dispatchers.IO) {runCatching {exports.discard(prepared)}}}
        }
}
