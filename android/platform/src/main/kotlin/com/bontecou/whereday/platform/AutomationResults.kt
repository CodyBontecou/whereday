package com.bontecou.whereday.platform

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.os.SystemClock
import android.provider.Settings
import android.util.AtomicFile
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.*
import java.nio.file.Files
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class AutomationExportAccessRequired:SecurityException("Verified export access is required.")
class AutomationResultUnavailable:FileNotFoundException("This automation result is unavailable or expired.")
data class AutomationResult internal constructor(val id:String,val name:String,val mime:String,val bytes:Long,val expiresAt:Long)

/** Private, immutable result files. The URI provider must call open()/info(), not expose
 * this directory through FileProvider. Reauthorization, revision, boot and two clocks bound access.
 * Revocation cannot withdraw bytes already read or a descriptor already handed to a recipient.
 */
class AutomationResults internal constructor(private val directory:File,private val access:AutomationAccess,
    private val wallMillis:()->Long,private val elapsedMillis:()->Long,private val bootCount:()->Int,
    private val lifetimeMillis:Long=10*60_000L,
    private val grantResult:(String,String)->Unit={_,_->error("Result grants are not configured.")},
    private val revokeResult:(String)->Unit={},
    private val authorizeExport:suspend ()->Unit={throw AutomationExportAccessRequired()}) {
    constructor(context:Context,access:AutomationAccess,authorizeExport:suspend ()->Unit={throw AutomationExportAccessRequired()}):this(File(context.cacheDir,"automation-results"),access,
        System::currentTimeMillis,SystemClock::elapsedRealtime,{Settings.Global.getInt(context.contentResolver,Settings.Global.BOOT_COUNT,-1)},
        grantResult={recipient,id ->context.grantUriPermission(recipient,resultUri(context,id),Intent.FLAG_GRANT_READ_URI_PERMISSION)},
        revokeResult={id ->context.revokeUriPermission(resultUri(context,id),Intent.FLAG_GRANT_READ_URI_PERMISSION)},authorizeExport=authorizeExport)
    init {require(lifetimeMillis in 1L..60*60_000L)}
    private val mutex=locks.getOrPut(directory.canonicalPath) {Mutex()}
    private data class Record(val result:AutomationResult,val permit:AutomationPermit,val created:Long,val elapsed:Long,val boot:Int,val hash:String)
    suspend fun create(caller:String?,scope:AutomationScope,name:String,mime:String,write:suspend (OutputStream)->Unit):AutomationResult {
        require(name.isNotBlank() && name.length<=200 && name.none {it<' ' || it=='/' || it=='\\'})
        require(mime.matches(Regex("[a-z0-9.+-]+/[a-z0-9.+-]+")))
        val id=UUID.randomUUID().toString()
        try {return access.authorize(caller,scope) {permit ->mutex.withLock {
            if(scope==AutomationScope.exports) authorizeExport()
            check(directory.mkdirs() || directory.isDirectory)
            sweep()
            val boot=bootCount();check(boot>=0)
            val body=body(id);val temporary=File(directory,"$id.part")
            val hash=MessageDigest.getInstance("SHA-256")
            try {
                FileOutputStream(temporary).use {output ->
                    val digest=DigestOutputStream(output,hash)
                    write(digest);currentCoroutineContext().ensureActive()
                    if(scope==AutomationScope.exports) authorizeExport()
                    digest.flush();output.fd.sync()
                }
                check(temporary.renameTo(body))
                val created=wallMillis();val elapsed=elapsedMillis()
                check(created>=0 && elapsed>=0 && bootCount()==boot)
                val result=AutomationResult(id,name,mime,body.length(),Math.addExact(created,lifetimeMillis))
                save(Record(result,permit,created,elapsed,boot,hex(hash.digest())))
                currentCoroutineContext().ensureActive()
                result
            } finally {temporary.delete()}
        }}} catch(e:Throwable) {
            withContext(NonCancellable+Dispatchers.IO) {mutex.withLock {delete(id)}}
            throw e
        }
    }

    suspend fun info(caller:String?,id:String):AutomationResult=withRecord(caller,id) {it.result}
    /** Package grant survives the request Activity. Provider authorization still bounds every read.
     * Hold the same approval/result locks as clear so a late grant cannot follow its cleanup. */
    suspend fun grantRead(caller:String,id:String):AutomationResult=withRecord(caller,id) {
        grantResult(caller,id);it.result
    }
    suspend fun open(caller:String?,id:String):ParcelFileDescriptor {
        var descriptor:ParcelFileDescriptor?=null
        try {return withRecord(caller,id) {record ->
            val hash=MessageDigest.getInstance("SHA-256")
            FileInputStream(body(id)).use {input ->
                val buffer=ByteArray(64*1024)
                while(true) {currentCoroutineContext().ensureActive();val n=input.read(buffer);if(n<0) break;hash.update(buffer,0,n)}
            }
            if(hex(hash.digest())!=record.hash) throw AutomationResultUnavailable()
            ParcelFileDescriptor.open(body(id),ParcelFileDescriptor.MODE_READ_ONLY).also {descriptor=it}
        }} catch(e:Throwable) {descriptor?.close();throw e}
    }
    private suspend fun <T> withRecord(caller:String?,id:String,block:suspend (Record)->T):T=withContext(Dispatchers.IO) {
        val record=mutex.withLock {load(id)}
        access.authorize(caller,record.permit.scope) {permit ->mutex.withLock {
            if(permit!=record.permit || load(id)!=record) throw AutomationResultUnavailable()
            if(permit.scope==AutomationScope.exports) authorizeExport()
            val value=block(record)
            if(permit.scope==AutomationScope.exports) authorizeExport()
            value
        }}
    }
    suspend fun clear()=withContext(Dispatchers.IO) {mutex.withLock {
        if(directory.isDirectory) Files.newDirectoryStream(directory.toPath()).use {paths ->for(path in paths) {
            val file=path.toFile();if(owned(file.name)) delete(file.name.substringBefore('.'))
        }}
    }}
    private fun owned(name:String)=Regex("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}\\.(body|json|json\\.new|json\\.bak|part)").matches(name)
    private fun body(id:String):File {require(UUID.fromString(id).toString()==id);return File(directory,"$id.body")}
    private fun metadata(id:String)=AtomicFile(File(directory,"$id.json"))
    private fun delete(id:String) {
        body(id) // Validate before revoking any URI or touching files.
        revokeResult(id)
        for(file in listOf(body(id),metadata(id).baseFile,File(directory,"$id.json.new"),File(directory,"$id.json.bak"),File(directory,"$id.part")))
            check(file.delete() || !file.exists()) {"Automation results could not be cleared."}
    }
    private fun sweep() {
        Files.newDirectoryStream(directory.toPath()).use {paths ->for(path in paths) {
            val file=path.toFile();if(!owned(file.name)) continue
            val id=file.name.substringBefore('.')
            try {load(id)} catch(_:Exception) {delete(id)}
        }}
    }
    private fun load(id:String):Record {
        try {
            body(id) // validate before using the id as a path
            val json=metadata(id).openRead().use {input ->
                val size=input.channel.size();check(size in 1L..4096L)
                val bytes=ByteArray(size.toInt());DataInputStream(input).readFully(bytes);check(input.read()==-1)
                AutomationJson.objectValue(bytes.toString(Charsets.UTF_8))
            }
            check(json.keySet()==setOf("version","name","mime","bytes","expires","revision","package","uid","installed","signers","scope","created","elapsed","boot","sha256"))
            check(AutomationJson.integer(json,"version")==1L)
            fun text(key:String)=AutomationJson.text(json,key)
            fun long(key:String)=AutomationJson.integer(json,key)
            val uid=long("uid");check(uid in 0..Int.MAX_VALUE.toLong())
            val signers=json["signers"].asJsonArray.map {check(it.isJsonPrimitive && it.asJsonPrimitive.isString);it.asString}
            check(signers.distinct().size==signers.size)
            val identity=AutomationIdentity(text("package"),uid.toInt(),long("installed"),signers.toSet())
            val revision=text("revision");check(UUID.fromString(revision).toString()==revision)
            val boot=long("boot");check(boot in 0..Int.MAX_VALUE.toLong())
            val result=AutomationResult(id,text("name"),text("mime"),long("bytes"),long("expires"))
            val record=Record(result,AutomationPermit(revision,identity,AutomationScope.valueOf(text("scope"))),long("created"),long("elapsed"),boot.toInt(),text("sha256"))
            check(result.bytes>=0 && record.created>=0 && record.elapsed>=0 && result.expiresAt==Math.addExact(record.created,lifetimeMillis))
            check(record.hash.matches(Regex("[0-9a-f]{64}")))
            check(record.boot==bootCount() && wallMillis() in record.created until result.expiresAt)
            check(elapsedMillis() in record.elapsed until Math.addExact(record.elapsed,lifetimeMillis))
            check(body(id).isFile && body(id).length()==result.bytes)
            return record
        } catch(_:Exception) {throw AutomationResultUnavailable()}
    }
    private fun save(record:Record) {
        val json=JsonObject().apply {
            addProperty("version",1);addProperty("name",record.result.name);addProperty("mime",record.result.mime)
            addProperty("bytes",record.result.bytes);addProperty("expires",record.result.expiresAt)
            addProperty("revision",record.permit.revision);addProperty("package",record.permit.identity.packageName)
            addProperty("uid",record.permit.identity.uid);addProperty("installed",record.permit.identity.firstInstalled)
            add("signers",JsonArray().apply {record.permit.identity.signers.sorted().forEach {add(it)}})
            addProperty("scope",record.permit.scope.name);addProperty("created",record.created);addProperty("elapsed",record.elapsed)
            addProperty("boot",record.boot);addProperty("sha256",record.hash)
        }
        val file=metadata(record.result.id);val stream=file.startWrite()
        try {stream.write(json.toString().toByteArray(Charsets.UTF_8));file.finishWrite(stream)}
        catch(e:Exception) {file.failWrite(stream);throw e}
    }
    private fun hex(bytes:ByteArray)=bytes.joinToString("") {"%02x".format(it.toInt() and 255)}
    companion object {
        private val locks=ConcurrentHashMap<String,Mutex>()
        private fun resultUri(context:Context,id:String)=Uri.parse("content://${context.packageName}.automation-results/$id")
    }
}
