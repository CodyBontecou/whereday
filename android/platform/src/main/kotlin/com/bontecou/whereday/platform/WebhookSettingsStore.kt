package com.bontecou.whereday.platform

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import com.bontecou.whereday.export.*
import com.bontecou.whereday.data.WebhookQueueTransition
import com.google.gson.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.*
import java.security.KeyStore
import java.util.UUID
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

class WebhookSettingsUnavailable:IllegalStateException("Webhook settings could not be opened. Delivery remains stopped; reset the settings to configure it again.")
data class WebhookConfiguration(val revision:String="",val settings:WebhookSettings=WebhookSettings(),val credentials:WebhookCredentials=WebhookCredentials()) {
    init {require(revision.isEmpty() || UUID.fromString(revision).toString()==revision);require(revision.isNotEmpty() || !settings.enabled)}
    override fun toString()="WebhookConfiguration(revision=$revision,settings=[redacted],credentials=[redacted])"
}

internal data class PreparedWebhookSettings(val previousRevision:String,val configuration:WebhookConfiguration,val transition:WebhookQueueTransition,val includeOutliers:Boolean)
internal data class WebhookSettingsDocument(val active:WebhookConfiguration=WebhookConfiguration(),val pending:PreparedWebhookSettings?=null)

/** Endpoint URLs may also contain secrets, so the complete document is encrypted and authenticated. */
internal class WebhookSettingsStore internal constructor(context:Context,private val directory:File,private val alias:String) {
    constructor(context:Context):this(context.applicationContext,File(context.filesDir,"webhooks"),"whereday_webhook_settings_aes_v1")
    private val file=AtomicFile(File(directory,"settings.bin"))
    private val aad="${context.packageName}|webhook-settings|1|${file.baseFile.absolutePath}".toByteArray(Charsets.UTF_8)
    suspend fun load():WebhookConfiguration=withContext(Dispatchers.IO) {synchronized(lock) {val doc=readLocked();check(doc.pending==null) {"Finish the pending webhook settings change first."};doc.active}}
    internal suspend fun document():WebhookSettingsDocument=withContext(Dispatchers.IO) {synchronized(lock) {readLocked()}}
    private fun readLocked():WebhookSettingsDocument {
        if(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists()) return WebhookSettingsDocument()
        try {
            val (iv,encrypted)=DataInputStream(file.openRead()).use {input->
                check(input.readInt()==MAGIC && input.readInt()==1)
                val iv=ByteArray(12);input.readFully(iv)
                val size=input.readInt();check(size in 16..MAX_ENCRYPTED)
                val bytes=ByteArray(size);input.readFully(bytes);check(input.read()==-1)
                iv to bytes
            }
            val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.DECRYPT_MODE,key(false),GCMParameterSpec(128,iv));updateAAD(aad)}
            return decodeDocument(cipher.doFinal(encrypted).toString(Charsets.UTF_8))
        } catch(_:Exception) {throw WebhookSettingsUnavailable()}
    }
    suspend fun replace(settings:WebhookSettings,credentials:WebhookCredentials):WebhookConfiguration=withContext(Dispatchers.IO) {synchronized(lock) {
        try {
            check(readLocked().pending==null)
            val value=candidate(settings,credentials)
            writeLocked(WebhookSettingsDocument(value));value
        } catch(_:Exception) {throw IllegalStateException("Webhook settings could not be saved.")}
    }}
    internal suspend fun prepare(expectedRevision:String,settings:WebhookSettings,credentials:WebhookCredentials,transition:WebhookQueueTransition,includeOutliers:Boolean):PreparedWebhookSettings=withContext(Dispatchers.IO) {synchronized(lock) {
        val doc=readLocked();check(doc.pending==null && doc.active.revision==expectedRevision) {"Webhook settings changed. Reload and try again."}
        val pending=PreparedWebhookSettings(expectedRevision,candidate(settings,credentials),transition,includeOutliers)
        writeLocked(doc.copy(pending=pending));pending
    }}
    internal suspend fun commit(revision:String):WebhookConfiguration=withContext(Dispatchers.IO) {synchronized(lock) {
        val doc=readLocked();val pending=checkNotNull(doc.pending);check(pending.configuration.revision==revision)
        writeLocked(WebhookSettingsDocument(pending.configuration));pending.configuration
    }}
    internal suspend fun abort(revision:String)=withContext(Dispatchers.IO) {synchronized(lock) {
        val doc=readLocked();check(doc.pending?.configuration?.revision==revision)
        writeLocked(doc.copy(pending=null))
    }}
    private fun candidate(settings:WebhookSettings,credentials:WebhookCredentials):WebhookConfiguration {
        if(settings.enabled) WebhookProtocol.request(settings,credentials,"configuration-validation")
        val value=WebhookConfiguration(UUID.randomUUID().toString(),settings,credentials)
        check(encode(value).toString().toByteArray(Charsets.UTF_8).size<=65536)
        return value
    }
    private fun writeLocked(doc:WebhookSettingsDocument) {
        val json=JsonObject().apply {
            addProperty("version",2);add("active",encode(doc.active))
            doc.pending?.let {p->add("pending",JsonObject().apply {addProperty("previousRevision",p.previousRevision);add("configuration",encode(p.configuration));addProperty("transition",p.transition.name);addProperty("includeOutliers",p.includeOutliers)})}
        }
        val plain=json.toString().toByteArray(Charsets.UTF_8);check(plain.size<=MAX_ENCRYPTED-16)
        val cipher=Cipher.getInstance("AES/GCM/NoPadding").apply {init(Cipher.ENCRYPT_MODE,key(true));updateAAD(aad)}
        val encrypted=cipher.doFinal(plain);check(cipher.iv.size==12)
        check(directory.mkdirs() || directory.isDirectory)
        val stream=file.startWrite()
        try {
            val writer=DataOutputStream(stream)
            writer.writeInt(MAGIC);writer.writeInt(1);writer.write(cipher.iv);writer.writeInt(encrypted.size);writer.write(encrypted);writer.flush()
            file.finishWrite(stream)
        } catch(e:Exception) {file.failWrite(stream);throw e}
    }
    suspend fun clear()=withContext(Dispatchers.IO) {synchronized(lock) {
        KeyStore.getInstance("AndroidKeyStore").apply {load(null);if(containsAlias(alias)) deleteEntry(alias)}
        file.delete();check(!file.baseFile.exists() && !File(file.baseFile.path+".bak").exists() && !File(file.baseFile.path+".new").exists()) {"Webhook settings could not be cleared."}
    }}
    private fun key(create:Boolean):SecretKey {
        val store=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}
        if(!store.containsAlias(alias)) {
            check(create)
            KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES,"AndroidKeyStore").apply {
                init(KeyGenParameterSpec.Builder(alias,KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setKeySize(256).setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .setRandomizedEncryptionRequired(true).build())
            }.generateKey()
        }
        return store.getKey(alias,null) as SecretKey
    }
    private fun encode(value:WebhookConfiguration)=JsonObject().apply {
        addProperty("version",1);addProperty("revision",value.revision)
        with(value.settings) {addProperty("enabled",enabled);addProperty("endpoint",endpoint);addProperty("format",format.name);addProperty("auth",auth.name);addProperty("mode",mode.name);addProperty("batchCount",batchCount);addProperty("batchMinutes",batchMinutes);addProperty("allowInsecureHttp",allowInsecureHttp)}
        with(value.credentials) {addProperty("key",key);addProperty("value",this.value);addProperty("username",username)}
    }
    private fun decodeDocument(text:String):WebhookSettingsDocument {
        val json=JsonParser.parseString(text).asJsonObject
        val version=json.getAsJsonPrimitive("version").also {check(it.isNumber)}.asBigDecimal.intValueExact()
        if(version==1) return WebhookSettingsDocument(decode(json))
        check(version==2)
        val active=decode(json.getAsJsonObject("active"))
        val pending=json.getAsJsonObject("pending")?.let {p->
            val previous=p.getAsJsonPrimitive("previousRevision").also {check(it.isString)}.asString
            check(previous==active.revision)
            val configuration=decode(p.getAsJsonObject("configuration"))
            check(configuration.revision.isNotEmpty() && configuration.revision!=previous)
            PreparedWebhookSettings(previous,configuration,WebhookQueueTransition.valueOf(p.getAsJsonPrimitive("transition").also {check(it.isString)}.asString),p.getAsJsonPrimitive("includeOutliers").also {check(it.isBoolean)}.asBoolean)
        }
        return WebhookSettingsDocument(active,pending)
    }
    private fun decode(json:JsonObject):WebhookConfiguration {
        fun string(name:String)=json.getAsJsonPrimitive(name).also {check(it.isString)}.asString
        fun bool(name:String)=json.getAsJsonPrimitive(name).also {check(it.isBoolean)}.asBoolean
        fun number(name:String)=json.getAsJsonPrimitive(name).also {check(it.isNumber)}.asBigDecimal.intValueExact()
        check(number("version")==1)
        val revision=string("revision");check(revision.isEmpty() || UUID.fromString(revision).toString()==revision)
        val settings=WebhookSettings(bool("enabled"),string("endpoint"),ExportFormat.valueOf(string("format")),WebhookAuth.valueOf(string("auth")),WebhookMode.valueOf(string("mode")),number("batchCount"),number("batchMinutes"),bool("allowInsecureHttp"))
        val credentials=WebhookCredentials(string("key"),string("value"),string("username"))
        if(settings.enabled) WebhookProtocol.request(settings,credentials,"configuration-validation")
        return WebhookConfiguration(revision,settings,credentials)
    }
    companion object {private val lock=Any();private const val MAGIC=0x57485331;private const val MAX_ENCRYPTED=262160}
}
