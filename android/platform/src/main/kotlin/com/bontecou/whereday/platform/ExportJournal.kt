package com.bontecou.whereday.platform

import android.content.Context
import android.util.AtomicFile
import com.bontecou.whereday.export.*
import com.google.gson.*
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileNotFoundException
import java.util.UUID

enum class ExportPickerPhase { awaitingDestination, writing, failed, completed, canceled }
data class ExportPickerState(val id:String,val displayName:String,val mime:String,val phase:ExportPickerPhase,
    val destination:String?=null,val persistedGrant:Boolean=false,val error:String?=null,val notice:ExportDeliveryNotice?=null) {
    val active get()=phase!=ExportPickerPhase.completed && phase!=ExportPickerPhase.canceled
}

/** Atomic, app-private receipts. Payloads remain outside the journal in the narrow share cache. */
internal class ExportJournal(context:Context,private val cache:File) {
    private val root=File(context.filesDir,"export-journal")
    private val current=AtomicFile(File(root,"current.json"))
    private fun id(value:String)=value.also {require(UUID.fromString(it).toString()==it)}
    private fun manifest(value:String)=AtomicFile(File(root,"bundles/${id(value)}.json"))
    private fun receipt(value:String)=AtomicFile(File(root,"pickers/${id(value)}.json"))
    fun save(prepared:PreparedExport) {
        val json=JsonObject().apply {
            addProperty("version",1);addProperty("id",prepared.id)
            add("files",JsonArray().apply {prepared.files.forEach {file ->add(JsonObject().apply {
                addProperty("path",file.relativePath);addProperty("format",file.format.name);addProperty("visits",file.counts.visits);addProperty("points",file.counts.points)
                addProperty("bytes",file.bytes);addProperty("sha256",file.sha256)
            })}})
        }
        write(manifest(prepared.id),json)
    }
    fun prepared(value:String):PreparedExport {
        val json=read(manifest(value)) ?: error("Prepared export is unavailable. Prepare it again.")
        require(json.long("version")==1L && json.text("id")==value)
        val directory=File(cache,id(value)).canonicalFile
        val files=json["files"].asJsonArray.map {entry ->
            val row=entry.asJsonObject;val path=row.text("path")
            require(path.isNotEmpty() && path.split('/').all {it.isNotBlank() && it!="." && it!=".." && '\\' !in it} && !path.startsWith('/'))
            val file=File(directory,path).canonicalFile
            require(file.path.startsWith(directory.path+File.separator))
            val format=ExportFormat.valueOf(row.text("format"));require(path.endsWith(".${format.extension}",true))
            val visits=row.long("visits");val points=row.long("points");val bytes=row.long("bytes");val hash=row.text("sha256")
            require(visits>=0 && points>=0 && bytes>=0 && hash.matches(Regex("[0-9a-f]{64}")))
            PreparedExportFile(file,path,format,ExportCounts(visits,points),bytes,hash)
        }
        require(files.map {it.relativePath}.distinct().size==files.size)
        return PreparedExport(value,files)
    }
    fun current():ExportPickerState?=read(current)?.let {picker(it.text("id")) ?: error("Saved export request is unavailable")}
    fun picker(value:String):ExportPickerState? {
        val json=read(receipt(value)) ?: return null
        require(json.long("version")==1L && json.text("id")==value)
        val destination=json["destination"]?.let {json.text("destination").also {require(android.net.Uri.parse(it).scheme=="content")}}
        return ExportPickerState(value,json.text("displayName"),json.text("mime"),ExportPickerPhase.valueOf(json.text("phase")),
            destination,json.boolean("persistedGrant"),json["error"]?.let {json.text("error")},json["notice"]?.let {field->ExportDeliveryNotice.entries.firstOrNull {it.isFailure && it.name==field.asString}})
    }
    fun begin(state:ExportPickerState) {
        put(state)
        write(current,JsonObject().apply {addProperty("id",state.id)})
    }
    fun put(state:ExportPickerState) {
        write(receipt(state.id),JsonObject().apply {
            addProperty("version",1);addProperty("id",state.id);addProperty("displayName",state.displayName);addProperty("mime",state.mime);addProperty("phase",state.phase.name)
            state.destination?.let {addProperty("destination",it)};addProperty("persistedGrant",state.persistedGrant);state.error?.let {addProperty("error",it)}
            state.notice?.let {addProperty("notice",it.name)}
        })
    }
    fun removeBundle(value:String) {manifest(value).delete()}
    fun cancel(state:ExportPickerState) {put(state.copy(phase=ExportPickerPhase.canceled,persistedGrant=false,error=null,notice=null))}
    fun receipts():List<ExportPickerState> =File(root,"pickers").listFiles().orEmpty().filter {it.extension=="json"}.mapNotNull {runCatching {picker(it.nameWithoutExtension)}.getOrNull()}
    fun clear() {root.deleteRecursively()}
    private fun write(file:AtomicFile,value:JsonObject) {
        val bytes=value.toString().toByteArray(Charsets.UTF_8)
        require(bytes.size<=8*1024*1024) {"Too many export files. Narrow the split date range."}
        check(file.baseFile.parentFile!!.mkdirs() || file.baseFile.parentFile!!.isDirectory)
        val output=file.startWrite()
        try {output.write(bytes);file.finishWrite(output)} catch(e:Throwable) {file.failWrite(output);throw e}
    }
    private fun read(file:AtomicFile):JsonObject? {
        val input=try {file.openRead()} catch(_:FileNotFoundException) {return null}
        val bytes=input.use {source ->
            val output=ByteArrayOutputStream();val buffer=ByteArray(8192)
            while(true) {val size=source.read(buffer);if(size<0) break;require(output.size()+size<=8*1024*1024);output.write(buffer,0,size)}
            output.toByteArray()
        }
        return JsonParser.parseString(bytes.toString(Charsets.UTF_8)).asJsonObject
    }
    private fun JsonObject.text(key:String):String {val value=get(key);require(value?.isJsonPrimitive==true && value.asJsonPrimitive.isString);return value.asString}
    private fun JsonObject.long(key:String):Long {val value=get(key);require(value?.isJsonPrimitive==true && value.asJsonPrimitive.isNumber);return requireNotNull(value.asString.toLongOrNull())}
    private fun JsonObject.boolean(key:String):Boolean {val value=get(key);require(value?.isJsonPrimitive==true && value.asJsonPrimitive.isBoolean);return value.asBoolean}
}
