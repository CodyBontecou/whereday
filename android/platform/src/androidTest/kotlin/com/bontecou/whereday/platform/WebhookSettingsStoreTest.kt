package com.bontecou.whereday.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.export.*
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.File
import java.security.KeyStore
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class WebhookSettingsStoreTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root:File
    private lateinit var alias:String
    private lateinit var store:WebhookSettingsStore
    private val settings=WebhookSettings(enabled=true,endpoint="https://example.invalid/private-endpoint-token?existing=url-secret-value",auth=WebhookAuth.bearer,format=ExportFormat.overland,mode=WebhookMode.batchCount,batchCount=25)
    private val credentials=WebhookCredentials("X-Secret-Key","unique-credential-value-qa","alice-private-name")
    @Before fun setup() {
        check(context.packageName=="com.bontecou.whereday.platform.test")
        root=File(context.cacheDir,"webhook-settings-test-${UUID.randomUUID()}")
        alias="whereday_webhook_test_${UUID.randomUUID()}"
        store=WebhookSettingsStore(context,File(root,"one"),alias)
    }
    @After fun cleanup() {
        root.deleteRecursively()
        KeyStore.getInstance("AndroidKeyStore").apply {load(null);if(containsAlias(alias)) deleteEntry(alias)}
    }
    private fun encrypted()=File(root,"one/settings.bin")

    @Test fun missingSettingsAreDisabledWithoutCreatingAFileOrKey():Unit=runBlocking {
        val loaded=store.load();assertFalse(loaded.settings.enabled);assertEquals("",loaded.settings.endpoint)
        assertFalse(File(root,"one").exists())
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.containsAlias(alias))
    }
    @Test fun realKeystoreEncryptionSurvivesRecreationAndContainsNoPlaintextCredentialsOrUrl():Unit=runBlocking {
        val saved=store.replace(settings,credentials)
        val first=encrypted().readBytes()
        val text=first.toString(Charsets.ISO_8859_1)
        for(secret in listOf(credentials.value,credentials.username,credentials.key,"private-endpoint-token","url-secret-value")) assertFalse(text.contains(secret))
        val loaded=WebhookSettingsStore(context,File(root,"one"),alias).load()
        assertEquals(saved,loaded);assertEquals(settings,loaded.settings);assertEquals(credentials,loaded.credentials)
        assertFalse(saved.toString().contains(credentials.value));assertFalse(saved.toString().contains("url-secret-value"))
        val again=store.replace(settings,credentials)
        assertNotEquals(saved.revision,again.revision);val second=encrypted().readBytes();assertFalse(first.contentEquals(second))
        assertFalse(first.copyOfRange(8,20).contentEquals(second.copyOfRange(8,20)))
        assertNull(KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.getKey(alias,null).encoded)
        assertEquals(again,store.load())
    }
    @Test fun corruptCiphertextIvFramingAndOversizedLengthsCannotLoadOrBeSilentlyOverwritten():Unit=runBlocking {
        store.replace(settings,credentials)
        val original=encrypted().readBytes()
        val variants=listOf(original.copyOf(7),original+byteArrayOf(1),original.copyOf().apply {this[lastIndex]=(this[lastIndex].toInt() xor 1).toByte()},original.copyOf().apply {this[8]=(this[8].toInt() xor 1).toByte()},original.copyOf().apply {for(i in 20..23) this[i]=127})
        for(value in variants) {
            encrypted().writeBytes(value)
            try {store.load();fail()} catch(e:WebhookSettingsUnavailable) {assertFalse(e.toString().contains(credentials.value))}
            try {store.replace(settings.copy(enabled=false),credentials);fail()} catch(_:IllegalStateException) {}
            assertArrayEquals(value,encrypted().readBytes())
        }
        store.clear();assertFalse(store.load().settings.enabled)
        assertFalse(KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.containsAlias(alias))
        assertTrue(store.replace(settings,credentials).settings.enabled)
    }
    @Test fun lostKeyAndRelocatedCiphertextFailClosedWithoutGeneratingAReplacementKey():Unit=runBlocking {
        store.replace(settings,credentials)
        val other=File(root,"two").apply {mkdirs()}
        encrypted().copyTo(File(other,"settings.bin"))
        try {WebhookSettingsStore(context,other,alias).load();fail()} catch(_:WebhookSettingsUnavailable) {}
        val original=encrypted().readBytes()
        val keys=KeyStore.getInstance("AndroidKeyStore").apply {load(null)};keys.deleteEntry(alias)
        try {store.load();fail()} catch(_:WebhookSettingsUnavailable) {}
        assertFalse(keys.containsAlias(alias));assertArrayEquals(original,encrypted().readBytes())
    }
    @Test fun preparedTransactionSurvivesRecreationAndMustCommitOrAbortBeforeOrdinaryLoads():Unit=runBlocking {
        val initial=store.prepare("",settings,credentials,com.bontecou.whereday.data.WebhookQueueTransition.requireEmpty,true)
        val reopened=WebhookSettingsStore(context,File(root,"one"),alias)
        assertEquals(initial,reopened.document().pending);assertEquals(WebhookConfiguration(),reopened.document().active)
        try {reopened.load();fail()} catch(_:IllegalStateException) {}
        try {reopened.replace(settings,credentials);fail()} catch(_:IllegalStateException) {}
        assertEquals(initial,reopened.document().pending)
        assertEquals(initial.configuration,reopened.commit(initial.configuration.revision));assertNull(store.document().pending)
        val next=store.prepare(initial.configuration.revision,settings.copy(enabled=false),credentials,com.bontecou.whereday.data.WebhookQueueTransition.discard,false)
        reopened.abort(next.configuration.revision);assertEquals(initial.configuration,reopened.load())
        try {store.prepare("",settings,credentials,com.bontecou.whereday.data.WebhookQueueTransition.retain,false);fail()} catch(_:IllegalStateException) {}
        assertEquals(initial.configuration,store.load());assertNull(store.document().pending)
    }
    @Test fun invalidPreparedCandidatePreservesActiveDocumentAndContainsNoPlaintextSecrets():Unit=runBlocking {
        val saved=store.replace(settings,credentials);val before=encrypted().readBytes()
        try {store.prepare(saved.revision,settings.copy(endpoint="invalid"),credentials,com.bontecou.whereday.data.WebhookQueueTransition.retain,false);fail()} catch(_:IllegalArgumentException) {}
        assertArrayEquals(before,encrypted().readBytes());assertEquals(saved,store.load())
        store.prepare(saved.revision,settings,credentials.copy(value="pending-private-token"),com.bontecou.whereday.data.WebhookQueueTransition.retain,false)
        val encryptedText=encrypted().readBytes().toString(Charsets.ISO_8859_1)
        assertFalse(encryptedText.contains("pending-private-token"));assertFalse(encryptedText.contains(credentials.value))
    }
    @Test fun legacyEncryptedDocumentLoadsButFractionalEnvelopeVersionsFailClosed():Unit=runBlocking {
        val saved=store.replace(settings,credentials)
        val key=KeyStore.getInstance("AndroidKeyStore").apply {load(null)}.getKey(alias,null)
        val aad="${context.packageName}|webhook-settings|1|${encrypted().absolutePath}".toByteArray(Charsets.UTF_8)
        val bytes=encrypted().readBytes()
        val cipher=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {init(javax.crypto.Cipher.DECRYPT_MODE,key,javax.crypto.spec.GCMParameterSpec(128,bytes.copyOfRange(8,20)));updateAAD(aad)}
        val envelope=com.google.gson.JsonParser.parseString(cipher.doFinal(bytes.copyOfRange(24,bytes.size)).toString(Charsets.UTF_8)).asJsonObject
        fun rewrite(json:com.google.gson.JsonObject) {
            val encrypt=javax.crypto.Cipher.getInstance("AES/GCM/NoPadding").apply {init(javax.crypto.Cipher.ENCRYPT_MODE,key);updateAAD(aad)}
            val body=encrypt.doFinal(json.toString().toByteArray(Charsets.UTF_8))
            java.io.DataOutputStream(encrypted().outputStream()).use {it.writeInt(0x57485331);it.writeInt(1);it.write(encrypt.iv);it.writeInt(body.size);it.write(body)}
        }
        rewrite(envelope.getAsJsonObject("active"));assertEquals(saved,store.load())
        envelope.addProperty("version",2.5);rewrite(envelope)
        try {store.load();fail()} catch(_:WebhookSettingsUnavailable) {}
    }
    @Test fun rejectedEnabledConfigurationPreservesTheLastCommittedSettingsAndClearErasesIt():Unit=runBlocking {
        val saved=store.replace(settings,credentials)
        try {store.replace(settings.copy(endpoint="https://user:password@example.invalid/"),credentials);fail()} catch(e:IllegalStateException) {assertFalse(e.toString().contains("password"))}
        assertEquals(saved,store.load())
        store.clear();assertFalse(encrypted().exists());assertEquals(WebhookConfiguration(),store.load())
    }
}
