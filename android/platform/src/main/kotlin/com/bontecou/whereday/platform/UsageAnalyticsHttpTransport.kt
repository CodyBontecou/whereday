package com.bontecou.whereday.platform

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.nio.ByteBuffer
import java.nio.charset.CodingErrorAction
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume

/** Only the internal constructor accepts a test endpoint; shipping configuration pins one receiver. */
class UsageAnalyticsHttpTransport internal constructor(private val endpoint:HttpUrl,token:String,client:OkHttpClient):UsageAnalyticsTransport {
    private val token=token.trim().also {require(it.none {char->char.isISOControl()})}
    private val client=client.newBuilder().followRedirects(false).followSslRedirects(false).retryOnConnectionFailure(false)
        .callTimeout(10,TimeUnit.SECONDS).connectTimeout(10,TimeUnit.SECONDS).readTimeout(10,TimeUnit.SECONDS).build()
    override suspend fun send(packet:UsagePacket):UsageSendResult=suspendCancellableCoroutine {continuation ->
        val request=Request.Builder().url(endpoint).header("Accept","application/json")
            .post(packet.encoded().toRequestBody("application/json; charset=utf-8".toMediaType())).apply {
                if(token.isNotEmpty()) header("Authorization","Bearer $token")
            }.build()
        val call=client.newCall(request)
        continuation.invokeOnCancellation {call.cancel()}
        if(!continuation.isActive) return@suspendCancellableCoroutine
        call.enqueue(object:Callback {
            override fun onFailure(call:Call,error:IOException) {if(continuation.isActive) continuation.resume(UsageSendResult.retry)}
            override fun onResponse(call:Call,response:Response) {
                val result=try {response.use {reply ->
                    when {
                        reply.code in setOf(408,425,429) || reply.code>=500->UsageSendResult.retry
                        reply.code !in 200..299->UsageSendResult.rejected
                        else->{
                            val body=requireNotNull(reply.body);require(body.contentType()?.subtype=="json")
                            val bytes=ByteArrayOutputStream();val buffer=ByteArray(1024)
                            body.byteStream().use {stream ->
                                while(bytes.size()<=4096) {
                                    val count=stream.read(buffer,0,minOf(buffer.size,4097-bytes.size()))
                                    if(count<0) break
                                    bytes.write(buffer,0,count)
                                    if(bytes.size()>4096) {call.cancel();break}
                                }
                            }
                            if(bytes.size()>4096) {call.cancel();UsageSendResult.retry}
                            else {
                                val text=Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                                    .decode(ByteBuffer.wrap(bytes.toByteArray())).toString()
                                val ack=AutomationJson.objectValue(text,maxChars=4096)
                                val ok=ack["ok"]
                                if(ack.keySet()==setOf("ok","accepted") && ok?.isJsonPrimitive==true && ok.asJsonPrimitive.isBoolean && ok.asBoolean && AutomationJson.integer(ack,"accepted")==1L)
                                    UsageSendResult.accepted else UsageSendResult.retry
                            }
                        }
                    }
                }} catch(_:Exception) {UsageSendResult.retry}
                if(continuation.isActive) continuation.resume(result)
            }
        })
    }
    companion object {
        const val RECEIVER="https://iso-me-onboarding-analytics.costream.workers.dev/v1/events"
        fun configured():UsageAnalyticsHttpTransport?=if(!BuildConfig.USAGE_ANALYTICS_AVAILABLE) null else
            UsageAnalyticsHttpTransport(RECEIVER.toHttpUrl(),BuildConfig.USAGE_ANALYTICS_TOKEN,OkHttpClient())
    }
}
