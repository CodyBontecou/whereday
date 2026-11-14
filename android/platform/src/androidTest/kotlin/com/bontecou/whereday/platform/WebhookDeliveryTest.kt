package com.bontecou.whereday.platform

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bontecou.whereday.billing.Capability
import com.bontecou.whereday.domain.*
import com.bontecou.whereday.export.*
import com.google.gson.JsonParser
import kotlinx.coroutines.*
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith
import java.io.*
import java.net.*
import java.security.MessageDigest
import java.time.Instant
import java.util.Locale
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import kotlin.concurrent.thread

@RunWith(AndroidJUnit4::class)
class WebhookDeliveryTest {
    private val context=InstrumentationRegistry.getInstrumentation().targetContext
    private lateinit var root:File
    private val now=Instant.parse("2026-10-27T12:00:00Z")
    private val point=TrackPoint(id="synthetic-point",coordinate=Coordinate(0.0,0.0),time=now,accuracy=5.0)
    @Before fun setup() {check(context.packageName=="com.bontecou.whereday.platform.test");root=File(context.cacheDir,"webhook-delivery-test-${UUID.randomUUID()}")}
    @After fun cleanup() {root.deleteRecursively()}
    private fun source(count:Int=1)=object:ExportRecordSource {
        override suspend fun visits(consume:suspend(Visit)->Unit) {consume(Visit(id="synthetic-visit",coordinate=point.coordinate,arrivedAt=now.minusSeconds(60),departedAt=now,name="Synthetic visit"))}
        override suspend fun points(consume:suspend(TrackPoint)->Unit) {repeat(count) {consume(point.copy(id="synthetic-$it",time=now.plusSeconds(it.toLong())))}}
    }
    private suspend fun payload(format:ExportFormat=ExportFormat.json,count:Int=1)=WebhookPayload.create(root,format,source(count),ExportOptions(kind=DataKind.all),now)
    private fun configuration(server:Receiver,format:ExportFormat=ExportFormat.json,auth:WebhookAuth=WebhookAuth.none)=WebhookConfiguration(UUID.randomUUID().toString(),WebhookSettings(enabled=true,endpoint=server.url+"?existing=a%2Bb",format=format,auth=auth,allowInsecureHttp=true),WebhookCredentials("X-Key","synthetic-secret","synthetic-user"))
    private suspend fun WebhookDelivery.deliver(config:WebhookConfiguration,payload:WebhookPayload,current:()->Boolean={true})=send(config,payload,current)
    @Test fun allFormatsArePostedFromTheirCompleteStreamedFilesWithCorrectMimeAndIdentity():Unit=runBlocking {
        for(format in ExportFormat.entries) Receiver().use {server->
            val payload=payload(format)
            val calls=AtomicInteger()
            val delivery=WebhookDelivery({assertEquals(Capability.webhook,it);calls.incrementAndGet()})
            val result=withTimeout(10000) {delivery.deliver(configuration(server,format),payload)}
            assertEquals(204,result.status);assertEquals(2,calls.get())
            val request=server.requests.single()
            assertEquals("POST",request.method);assertEquals(payload.bytes,request.bytes);assertEquals(payload.sha256,request.sha256)
            assertEquals(format.mime,request.headers["content-type"]);assertEquals(payload.id,request.headers["idempotency-key"])
            assertEquals("no-store",request.headers["cache-control"]);assertEquals(1L,payload.counts.points)
            assertEquals(if(format in setOf(ExportFormat.owntracks,ExportFormat.overland)) 0L else 1L,payload.counts.visits)
            if(format==ExportFormat.owntracks) assertEquals("location",JsonParser.parseString(request.prefix).asJsonArray[0].asJsonObject["_type"].asString)
            if(format==ExportFormat.overland) assertEquals(1,JsonParser.parseString(request.prefix).asJsonObject["locations"].asJsonArray.size())
        }
    }
    @Test fun allAuthenticationModesReachOnlyTheExplicitLoopbackReceiver():Unit=runBlocking {
        val payload=payload()
        for(auth in WebhookAuth.entries) Receiver().use {server->
            assertTrue(WebhookDelivery({}).deliver(configuration(server,auth=auth),payload).successful)
            val received=server.requests.single()
            when(auth) {
                WebhookAuth.none->assertNull(received.headers["authorization"])
                WebhookAuth.apiKeyQuery->assertTrue(received.path.endsWith("&X-Key=synthetic-secret"))
                WebhookAuth.bearer->assertEquals("Bearer synthetic-secret",received.headers["authorization"])
                WebhookAuth.basic->assertEquals("Basic "+java.util.Base64.getEncoder().encodeToString("synthetic-user:synthetic-secret".toByteArray()),received.headers["authorization"])
                WebhookAuth.customHeader->assertEquals("synthetic-secret",received.headers["x-key"])
            }
        }
    }
    @Test fun retriesUseTheSameImmutableBodyAndIdempotencyKeyWithoutClientSideAutomaticRetry():Unit=runBlocking {
        Receiver(listOf(503,204),retryAfter="0").use {server->
            val payload=payload();val config=configuration(server)
            val sender=WebhookDelivery({})
            val first=sender.deliver(config,payload)
            assertEquals(503,first.status);assertEquals(1,server.requests.size)
            assertEquals(WebhookRetry.After(now.plusSeconds(1)),WebhookRetryPolicy.decide(1,first,now))
            val restored=WebhookPayload.restore(root,payload.id,payload.format,payload.bytes,payload.sha256,payload.counts) {}
            assertEquals(payload.id,restored.id);assertEquals(payload.sha256,restored.sha256)
            assertEquals(204,sender.deliver(config,restored).status)
            val requests=server.requests
            assertEquals(2,requests.size);assertNull(requests[1].headers["cookie"]);assertEquals(requests[0].sha256,requests[1].sha256)
            assertEquals(payload.id,requests[0].headers["idempotency-key"]);assertEquals(requests[0].headers["idempotency-key"],requests[1].headers["idempotency-key"])
        }
    }
    @Test fun redirectsCannotForwardTheBodyOrCredentialsToAnotherReceiver():Unit=runBlocking {
        Receiver().use {destination->Receiver(listOf(307),location=destination.url).use {origin->
            val result=WebhookDelivery({}).deliver(configuration(origin,auth=WebhookAuth.bearer),payload())
            assertEquals(307,result.status);assertEquals(1,origin.requests.size);assertEquals(0,destination.requests.size)
        }}
    }
    @Test fun deniedOrExpiredPurchaseDisabledSettingsAndOldGenerationsNeverOpenARequest():Unit=runBlocking {
        Receiver().use {server->
            val payload=payload();val config=configuration(server)
            for(failAt in 1..2) {
                val calls=AtomicInteger()
                try {WebhookDelivery({if(calls.incrementAndGet()==failAt) error("Synthetic purchase denial")}).deliver(config,payload);fail()} catch(_:IllegalStateException) {}
                assertEquals(failAt,calls.get());assertTrue(server.requests.isEmpty())
            }
            try {WebhookDelivery({fail("Disabled delivery must not authorize")}).deliver(config.copy(settings=config.settings.copy(enabled=false)),payload);fail()} catch(_:IllegalStateException) {}
            try {WebhookDelivery({fail("Stale delivery must not authorize")}).deliver(config,payload) {false};fail()} catch(_:CancellationException) {}
            assertTrue(server.requests.isEmpty())
        }
    }
    @Test fun responseFailuresExposeOnlyStatusAndOfflineEndpointsBecomeRetryableNetworkResults():Unit=runBlocking {
        val payload=payload()
        for(code in listOf(401,408,429,500)) Receiver(listOf(code),retryAfter="5").use {server->
            val result=WebhookDelivery({}).deliver(configuration(server),payload)
            assertEquals(code,result.status);assertEquals("5",result.retryAfter);assertEquals(1,server.requests.size)
            assertFalse(result.toString().contains("synthetic-secret"))
        }
        val offline=Receiver();val config=configuration(offline);offline.close()
        assertEquals(0,withTimeout(5000) {WebhookDelivery({}).deliver(config,payload)}.status)
    }
    @Test fun changedPayloadIsRejectedBeforeOpeningTheNetwork():Unit=runBlocking {
        Receiver().use {server->
            val payload=payload();val bytes=payload.file.readBytes();bytes[bytes.lastIndex]=(bytes.last().toInt() xor 1).toByte();payload.file.writeBytes(bytes)
            try {WebhookDelivery({}).deliver(configuration(server),payload);fail()} catch(_:IllegalStateException) {}
            assertTrue(server.requests.isEmpty())
            try {WebhookPayload.restore(root,"../outside",payload.format,payload.bytes,payload.sha256,payload.counts) {};fail()} catch(_:IllegalArgumentException) {}
            try {WebhookPayload.restore(root,payload.id,payload.format,payload.bytes,payload.sha256,payload.counts) {};fail()} catch(_:IllegalStateException) {}
        }
    }
    @Test fun cancellationClosesAnInFlightCallAndJoinsItsCallback():Unit=runBlocking {
        Receiver(stall=true).use {server->
            val payload=payload();val sending=async {WebhookDelivery({}).deliver(configuration(server),payload)}
            withTimeout(5000) {server.received.await()}
            withTimeout(5000) {sending.cancelAndJoin();server.finished.await()}
            assertTrue(sending.isCancelled);assertEquals(1,server.requests.size)
        }
    }
    @Test fun largeBodiesStreamCompletelyAndCanceledPreparationRemovesItsStaging():Unit=runBlocking {
        Receiver().use {server->
            val payload=payload(ExportFormat.json,20000)
            assertTrue(payload.bytes>1_000_000)
            assertEquals(20000L,payload.counts.points)
            assertTrue(WebhookDelivery({}).deliver(configuration(server),payload).successful)
            assertEquals(payload.sha256,server.requests.single().sha256)
        }
        val directory=File(root,"canceled");val entered=CompletableDeferred<Unit>()
        val pending=async {WebhookPayload.create(directory,ExportFormat.json,object:ExportRecordSource {
            override suspend fun visits(consume:suspend(Visit)->Unit) {}
            override suspend fun points(consume:suspend(TrackPoint)->Unit) {consume(point);entered.complete(Unit);awaitCancellation()}
        },ExportOptions(kind=DataKind.points),now)}
        withTimeout(5000) {entered.await();pending.cancelAndJoin()}
        assertTrue(directory.listFiles().orEmpty().isEmpty())
    }
    internal data class Captured(val method:String,val path:String,val headers:Map<String,String>,val bytes:Long,val sha256:String,val prefix:String)
    internal class Receiver(private val codes:List<Int> =listOf(204),private val retryAfter:String?=null,private val location:String?=null,private val stall:Boolean=false):Closeable {
        private val socket=ServerSocket(0,4,InetAddress.getByName("127.0.0.1"))
        val url="http://127.0.0.1:${socket.localPort}/receive"
        val requests=CopyOnWriteArrayList<Captured>()
        val received=CompletableDeferred<Unit>();val finished=CompletableDeferred<Unit>()
        @Volatile private var active:Socket?=null
        private val worker=thread(name="webhook-loopback",isDaemon=true) {
            try {for(code in codes) socket.accept().use {client->
                active=client;client.soTimeout=10000
                val input=BufferedInputStream(client.getInputStream())
                fun line():String {val out=ByteArrayOutputStream();while(true) {val byte=input.read();check(byte>=0);if(byte==10) return out.toString("ISO-8859-1").trimEnd('\r');out.write(byte);check(out.size()<65536)}}
                val start=line().split(' ');val headers=linkedMapOf<String,String>()
                while(true) {val line=line();if(line.isEmpty()) break;headers[line.substringBefore(':').lowercase(Locale.ROOT)]=line.substringAfter(':').trim()}
                val length=headers.getValue("content-length").toLong();check(length in 0..32_000_000)
                val digest=MessageDigest.getInstance("SHA-256");val prefix=ByteArrayOutputStream();var remaining=length;val buffer=ByteArray(65536)
                while(remaining>0) {val n=input.read(buffer,0,minOf(remaining,buffer.size.toLong()).toInt());check(n>0);digest.update(buffer,0,n);val keep=minOf(n,8192-prefix.size());if(keep>0) prefix.write(buffer,0,keep);remaining-=n}
                requests+=Captured(start[0],start[1],headers,length,digest.digest().joinToString("") {"%02x".format(it.toInt() and 255)},prefix.toString("UTF-8"));received.complete(Unit)
                if(stall) {while(input.read()!=-1) {} }
                else client.getOutputStream().apply {write(("HTTP/1.1 $code Result\r\nContent-Length: 0\r\nConnection: close\r\nSet-Cookie: qa-cookie=synthetic-secret; Path=/\r\n"+(retryAfter?.let {"Retry-After: $it\r\n"} ?: "")+(location?.let {"Location: $it\r\n"} ?: "")+"\r\n").toByteArray(Charsets.ISO_8859_1));flush()}
                active=null
            }} catch(e:Exception) {if(!socket.isClosed && !stall) {received.completeExceptionally(e)}} finally {finished.complete(Unit)}
        }
        override fun close() {socket.close();active?.close();worker.join(2000);check(!worker.isAlive)}
    }
}
