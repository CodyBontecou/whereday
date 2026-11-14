package com.bontecou.whereday.platform

import com.bontecou.whereday.billing.Capability
import com.bontecou.whereday.export.*
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okio.BufferedSink
import java.io.*
import java.security.DigestOutputStream
import java.security.MessageDigest
import java.time.Instant
import java.util.UUID
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Immutable, private payload. A durable outbox can retain the ID and fingerprint across attempts. */
internal class WebhookPayload private constructor(val id:String,val file:File,val format:ExportFormat,val bytes:Long,val sha256:String,val counts:ExportCounts) {
    override fun toString()="WebhookPayload(id=$id,format=$format,bytes=$bytes)"
    suspend fun validate(current:()->Unit) {
        check(file.isFile && file.length()==bytes) {"Webhook payload is unavailable."}
        val digest=MessageDigest.getInstance("SHA-256")
        file.inputStream().use {input->val buffer=ByteArray(65536);while(true) {
            currentCoroutineContext().ensureActive();current()
            val n=input.read(buffer);if(n<0) break;digest.update(buffer,0,n)
        }}
        check(hex(digest.digest())==sha256) {"Webhook payload changed. Prepare it again."}
    }
    companion object {
        suspend fun restore(directory:File,id:String,format:ExportFormat,bytes:Long,sha256:String,counts:ExportCounts,current:()->Unit):WebhookPayload=withContext(Dispatchers.IO) {
            require(UUID.fromString(id).toString()==id && bytes>=0 && sha256.matches(Regex("[0-9a-f]{64}")) && counts.points>=0 && counts.visits>=0) {"Invalid saved webhook payload."}
            val file=File(directory,"$id.body")
            require(file.canonicalFile.parentFile==directory.canonicalFile) {"Invalid saved webhook payload path."}
            WebhookPayload(id,file,format,bytes,sha256,counts).also {it.validate(current)}
        }
        suspend fun create(directory:File,format:ExportFormat,source:ExportRecordSource,options:ExportOptions,generated:Instant,locale:java.util.Locale=java.util.Locale.getDefault()):WebhookPayload=withContext(Dispatchers.IO) {
            check(directory.mkdirs() || directory.isDirectory)
            val id=UUID.randomUUID().toString();val pending=File(directory,"$id.tmp");val ready=File(directory,"$id.body")
            try {
                val digest=MessageDigest.getInstance("SHA-256")
                val counts=FileOutputStream(pending).use {stream->
                    val writer=DigestOutputStream(stream,digest).bufferedWriter(Charsets.UTF_8)
                    val counts=StreamingHistoryExporter.write(writer,format,source,options,generated,scratchDirectory=directory,locale=locale)
                    writer.flush();stream.fd.sync();counts
                }
                currentCoroutineContext().ensureActive();check(pending.renameTo(ready))
                WebhookPayload(id,ready,format,ready.length(),hex(digest.digest()),counts)
            } catch(e:Exception) {pending.delete();ready.delete();throw e}
        }
        private fun hex(bytes:ByteArray)=bytes.joinToString("") {"%02x".format(it.toInt() and 255)}
    }
}

/** One logical HTTP attempt. Retry scheduling belongs to the durable outbox, never this client. */
internal class WebhookHttpTransport(base:OkHttpClient=OkHttpClient()) {
    private val client=base.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .cookieJar(CookieJar.NO_COOKIES).authenticator(Authenticator.NONE).proxyAuthenticator(Authenticator.NONE).cache(null)
        .connectTimeout(30,TimeUnit.SECONDS).readTimeout(30,TimeUnit.SECONDS).writeTimeout(30,TimeUnit.SECONDS).callTimeout(60,TimeUnit.SECONDS).build()
    suspend fun post(plan:WebhookRequest,payload:WebhookPayload,current:()->Unit):WebhookHttpResult {
        currentCoroutineContext().ensureActive();current()
        val body=object:RequestBody() {
            override fun contentType()=payload.format.mime.toMediaType()
            override fun contentLength()=payload.bytes
            override fun isOneShot()=true
            override fun writeTo(sink:BufferedSink) {
                fun checked() {try {current()} catch(_:CancellationException) {throw IOException("Webhook delivery was canceled.")}}
                checked()
                payload.file.inputStream().use {input->val buffer=ByteArray(65536);var total=0L
                    while(true) {
                        checked();val n=input.read(buffer);if(n<0) break
                        sink.write(buffer,0,n);total+=n
                    }
                    if(total!=payload.bytes) throw IOException("Webhook payload changed.")
                }
            }
        }
        val request=try {Request.Builder().url(plan.url).post(body).apply {plan.headers.forEach {header(name=it.key,value=it.value)}}.build()}
        catch(_:IllegalArgumentException) {throw IllegalStateException("Webhook request could not be prepared.")}
        val call=client.newCall(request)
        val finished=CompletableDeferred<Unit>()
        try {
            return suspendCancellableCoroutine {continuation->
                continuation.invokeOnCancellation {call.cancel()}
                call.enqueue(object:Callback {
                    override fun onFailure(call:Call,e:IOException) {
                        try {if(continuation.isActive) continuation.resume(WebhookHttpResult(0))} finally {finished.complete(Unit)}
                    }
                    override fun onResponse(call:Call,response:Response) {
                        try {
                            val result=try {response.use {WebhookHttpResult(it.code,it.header("Retry-After")?.take(256))}} catch(_:Exception) {WebhookHttpResult(0)}
                            if(continuation.isActive) continuation.resume(result)
                        } finally {finished.complete(Unit)}
                    }
                })
            }
        } finally {
            call.cancel()
            withContext(NonCancellable) {finished.await()}
        }
    }
}

/** All actual delivery rechecks lifetime access; callers also supply their queue-generation fence. */
internal class WebhookDelivery(private val authorize:suspend(Capability)->Unit,private val transport:WebhookHttpTransport=WebhookHttpTransport()) {
    suspend fun send(configuration:WebhookConfiguration,payload:WebhookPayload,current:()->Boolean):WebhookHttpResult=withContext(Dispatchers.IO) {
        val job=currentCoroutineContext()[Job]
        fun checkCurrent() {job?.ensureActive();if(!current()) throw CancellationException("Webhook delivery was canceled.")}
        checkCurrent();check(configuration.settings.enabled) {"Webhook delivery is disabled."}
        check(configuration.settings.format==payload.format) {"Webhook format changed. Prepare the payload again."}
        authorize(Capability.webhook)
        val request=WebhookProtocol.request(configuration.settings,configuration.credentials,payload.id)
        payload.validate(::checkCurrent)
        authorize(Capability.webhook);checkCurrent()
        transport.post(request,payload,::checkCurrent)
    }
}
