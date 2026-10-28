package com.bontecou.whereday.platform

import kotlinx.coroutines.*
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.MockResponse
import okhttp3.mockwebserver.MockWebServer
import okhttp3.mockwebserver.SocketPolicy
import org.junit.*
import org.junit.Assert.*
import java.util.UUID
import java.util.concurrent.TimeUnit

class UsageAnalyticsHttpTransportTest {
    private lateinit var server:MockWebServer
    @Before fun start() {server=MockWebServer();server.start()}
    @After fun stop() {server.shutdown()}
    private fun packet()=UsagePacket(UUID.randomUUID(),UsagePending(UUID.randomUUID(),UsageEvent(UsageEventName.stepViewed,UsageStep.features),UsageMetadata("0.1.0","1")))
    private fun transport(token:String="")=UsageAnalyticsHttpTransport(server.url("/v1/events"),token,OkHttpClient())
    @Test fun acceptedReceiptAndExactRequestHaveNoExtraDeviceMetadata():Unit=runBlocking {
        server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody("{\"ok\":true,\"accepted\":1}"))
        val packet=packet();assertEquals(UsageSendResult.accepted,transport("test-only-key").send(packet))
        val request=requireNotNull(server.takeRequest(2,TimeUnit.SECONDS))
        assertEquals("POST",request.method);assertEquals("/v1/events",request.path)
        assertEquals(packet.encoded(),request.body.readUtf8());assertEquals("Bearer test-only-key",request.getHeader("Authorization"))
    }
    @Test fun redirectsAreNeverFollowed():Unit=runBlocking {
        server.enqueue(MockResponse().setResponseCode(307).setHeader("Location",server.url("/another-host-or-path")))
        assertEquals(UsageSendResult.rejected,transport().send(packet()));server.takeRequest()
        assertNull(server.takeRequest(100,TimeUnit.MILLISECONDS))
    }
    @Test fun throttlingRetriesButInvalidRequestsAreRejected():Unit=runBlocking {
        for(code in listOf(408,425,429,500,503)) {server.enqueue(MockResponse().setResponseCode(code));assertEquals(UsageSendResult.retry,transport().send(packet()))}
        for(code in listOf(400,401,403,404,422)) {server.enqueue(MockResponse().setResponseCode(code));assertEquals(UsageSendResult.rejected,transport().send(packet()))}
    }
    @Test fun malformedDuplicateOversizedOrWrongAcknowledgmentsCannotDiscardAnEvent():Unit=runBlocking {
        for(body in listOf("{}","{\"ok\":true,\"accepted\":0}","{\"ok\":true,\"ok\":true,\"accepted\":1}","x".repeat(5000))) {
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody(body))
            assertEquals(UsageSendResult.retry,transport().send(packet()))
        }
        server.enqueue(MockResponse().setHeader("Content-Type","text/html").setBody("{\"ok\":true,\"accepted\":1}"))
        assertEquals(UsageSendResult.retry,transport().send(packet()))
    }
    @Test fun cancellationClosesTheOwnedHttpCallPromptly():Unit=runBlocking {supervisorScope {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val send=async {transport().send(packet())}
        assertNotNull(withContext(Dispatchers.IO) {server.takeRequest(2,TimeUnit.SECONDS)})
        withTimeout(2_000) {send.cancelAndJoin()};assertTrue(send.isCancelled)
    }}
    @Test fun consentToHttpRetryKeepsExactReceiptAndRevocationPreventsFurtherRequests():Unit=runBlocking {
        val store=object:UsageAnalyticsStore {
            var value=UsageConsentState()
            override suspend fun read()=value
            override suspend fun write(value:UsageConsentState) {this.value=value}
        }
        var metadataReads=0
        val core=UsageAnalyticsCore(store,transport()) {metadataReads++;UsageMetadata("0.1.0","1")}
        val event=UsageEvent(UsageEventName.stepViewed,UsageStep.welcome)
        core.load()
        assertFalse(core.record(event,core.captureConsent()))
        assertNull(core.sendNext());assertEquals(0,metadataReads);assertEquals(0,server.requestCount)
        core.setConsent(true)
        assertTrue(core.record(event,core.captureConsent()))
        server.enqueue(MockResponse().setResponseCode(503))
        assertEquals(UsageSendResult.retry,core.sendNext())
        val first=server.takeRequest().body.readUtf8()
        server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody("{\"ok\":true,\"accepted\":1}"))
        assertEquals(UsageSendResult.accepted,core.sendNext())
        assertEquals(first,server.takeRequest().body.readUtf8())
        val oldPermit=core.captureConsent()
        core.setConsent(false)
        assertFalse(core.record(event,oldPermit));assertNull(core.sendNext())
        assertNull(store.value.consentId);assertTrue(store.value.pending.isEmpty())
        assertEquals(2,server.requestCount);assertEquals(1,metadataReads)
    }
    @Test fun unconfiguredBuildHasNoProductionTransport() {assertNull(UsageAnalyticsHttpTransport.configured())}
}
