package com.bontecou.whereday.platform

import android.content.Context
import android.content.pm.PackageManager
import android.util.AtomicFile
import com.google.gson.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File
import java.security.MessageDigest
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong

enum class AutomationScope { summary, visits, outings, renameVisits, renameOutings, recording, exports, places, manualVisits }

/** Approval binds the installed app, not a caller-supplied package extra or referrer. */
data class AutomationIdentity internal constructor(val packageName:String,val uid:Int,val firstInstalled:Long,val signers:Set<String>) {
    /** Own the signer collection before a review outlives its source snapshot. */
    fun snapshot()=AutomationIdentity(packageName,uid,firstInstalled,signers.toSet())
    init {
        require(packageName.length<=255 && packageName.matches(Regex("[A-Za-z][A-Za-z0-9_]*(\\.[A-Za-z][A-Za-z0-9_]*)+")))
        require(uid>=0 && firstInstalled>=0 && signers.isNotEmpty() && signers.size<=16)
        require(signers.all {it.matches(Regex("[0-9a-f]{64}"))})
    }
}
internal fun installedAutomationIdentity(context:Context,packageName:String):AutomationIdentity? = try {
    @Suppress("DEPRECATION")
    val info=context.packageManager.getPackageInfo(packageName,PackageManager.GET_SIGNING_CERTIFICATES)
    val signers=info.signingInfo?.apkContentsSigners?.map {signature ->
        MessageDigest.getInstance("SHA-256").digest(signature.toByteArray()).joinToString("") {"%02x".format(it.toInt() and 255)}
    }?.toSet().orEmpty()
    AutomationIdentity(packageName,requireNotNull(info.applicationInfo).uid,info.firstInstallTime,signers)
} catch(_:Exception) {null}

data class AutomationApproval internal constructor(val revision:String,val identity:AutomationIdentity,val scopes:Set<AutomationScope>)
data class AutomationPermit internal constructor(val revision:String,val identity:AutomationIdentity,val scope:AutomationScope)
class AutomationNotAuthorized:SecurityException("Automation access is disabled or this app or action has not been approved.")
class AutomationSettingsUnavailable:IllegalStateException("Automation settings could not be opened. Access remains disabled until they are reset.")
class AutomationAccessChanged:CancellationException("Automation access changed.")

/** One process owns automation. All instances for the same private file share cancellation/fences.
 * An Activity must supply Android's getCallingPackage(), never an Intent extra, to authorize().
 */
class AutomationAccess internal constructor(private val directory:File,private val resolve:(String)->AutomationIdentity?) {
    constructor(context:Context):this(File(context.filesDir,"automation"),{installedAutomationIdentity(context.applicationContext,it)})
    private val state=owners.getOrPut(directory.canonicalPath) {Owner()}
    private val file=AtomicFile(File(directory,"approval.json"))
    private class Owner {
        val mutex=Mutex();val epoch=AtomicLong();val monitor=Any();var active:Job?=null
        @Volatile var inhibited=false
    }
    fun installed(packageName:String):AutomationIdentity?=resolve(packageName)
    suspend fun approval():AutomationApproval?=withContext(Dispatchers.IO) {state.mutex.withLock {if(state.inhibited) throw AutomationSettingsUnavailable();read()}}

    suspend fun approve(reviewed:AutomationIdentity,scopes:Set<AutomationScope>) {
        require(scopes.isNotEmpty())
        val approvedIdentity=reviewed.copy(signers=reviewed.signers.toSet())
        val approvedScopes=scopes.toSet()
        change {
            if(resolve(approvedIdentity.packageName)!=approvedIdentity) throw AutomationNotAuthorized()
            write(AutomationApproval(UUID.randomUUID().toString(),approvedIdentity,approvedScopes))
        }
    }
    suspend fun revoke() {change {write(null)}}

    private suspend fun change(mutation:()->Unit) {
        val epoch=synchronized(state.monitor) {
            state.inhibited=true
            val next=state.epoch.incrementAndGet();state.active?.cancel(AutomationAccessChanged());next
        }
        withContext(Dispatchers.IO) {state.mutex.withLock {
            if(epoch!=state.epoch.get()) throw AutomationAccessChanged()
            // If storage fails, inhibit this process and surface the failure; do not claim persistence.
            mutation();state.inhibited=false
        }}
    }

    suspend fun <T> authorize(callingPackage:String?,scope:AutomationScope,block:suspend (AutomationPermit)->T):T {
        val epoch=state.epoch.get()
        return withContext(Dispatchers.IO) {state.mutex.withLock {
            if(epoch!=state.epoch.get() || state.inhibited) throw AutomationNotAuthorized()
            val approval=read() ?: throw AutomationNotAuthorized()
            if(callingPackage!=approval.identity.packageName || scope !in approval.scopes || resolve(callingPackage)!=approval.identity) throw AutomationNotAuthorized()
            coroutineScope {
                val operation=async(start=CoroutineStart.LAZY) {
                    block(AutomationPermit(approval.revision,approval.identity,scope))
                }
                synchronized(state.monitor) {
                    if(epoch!=state.epoch.get() || state.inhibited) throw AutomationNotAuthorized()
                    state.active=operation
                }
                try {
                    val result=operation.await()
                    currentCoroutineContext().ensureActive()
                    if(epoch!=state.epoch.get() || state.inhibited) throw AutomationAccessChanged()
                    result
                } finally {synchronized(state.monitor) {if(state.active===operation) state.active=null}}
            }
        }}
    }

    private fun read():AutomationApproval? {
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists()) return null
        try {
            val raw=file.openRead().use {input ->
                val size=input.channel.size();check(size in 0L..16_384L)
                ByteArray(size.toInt()).also {java.io.DataInputStream(input).readFully(it);check(input.read()==-1)}
            }
            val json=AutomationJson.objectValue(raw.toString(Charsets.UTF_8))
            check(json.keySet()==setOf("version","approval"));check(AutomationJson.integer(json,"version")==1L)
            if(json["approval"].isJsonNull) return null
            val a=json["approval"].asJsonObject
            check(a.keySet()==setOf("revision","package","uid","installed","signers","scopes"))
            val revision=AutomationJson.text(a,"revision");check(UUID.fromString(revision).toString()==revision)
            val uid=AutomationJson.integer(a,"uid");check(uid in 0..Int.MAX_VALUE.toLong())
            fun strings(key:String)=a[key].asJsonArray.map {check(it.isJsonPrimitive && it.asJsonPrimitive.isString);it.asString}.also {check(it.distinct().size==it.size)}
            val identity=AutomationIdentity(AutomationJson.text(a,"package"),uid.toInt(),AutomationJson.integer(a,"installed"),strings("signers").toSet())
            val scopes=strings("scopes").map {AutomationScope.valueOf(it)}.toSet();check(scopes.isNotEmpty())
            return AutomationApproval(revision,identity,scopes)
        } catch(_:Exception) {throw AutomationSettingsUnavailable()}
    }
    private fun write(approval:AutomationApproval?) {
        check(directory.mkdirs() || directory.isDirectory)
        val json=JsonObject().apply {
            addProperty("version",1)
            add("approval",approval?.let {a ->JsonObject().apply {
                addProperty("revision",a.revision);addProperty("package",a.identity.packageName)
                addProperty("uid",a.identity.uid);addProperty("installed",a.identity.firstInstalled)
                add("signers",JsonArray().apply {a.identity.signers.sorted().forEach {add(it)}})
                add("scopes",JsonArray().apply {a.scopes.sortedBy {it.ordinal}.forEach {add(it.name)}})
            }} ?: JsonNull.INSTANCE)
        }
        val stream=file.startWrite()
        try {stream.write(json.toString().toByteArray(Charsets.UTF_8));file.finishWrite(stream)}
        catch(e:Exception) {file.failWrite(stream);throw e}
    }
    companion object {private val owners=ConcurrentHashMap<String,Owner>()}
}
