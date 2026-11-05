package com.bontecou.whereday.export

import org.junit.Test
import org.junit.Assert.*
import java.time.Instant
import java.util.Base64

class WebhookProtocolTest {
    private val settings=WebhookSettings(endpoint="https://example.invalid/receive?existing=a%2Bb")
    private val secret=WebhookCredentials("X-Api-Key","sensitive-value","alice")
    private fun plan(auth:WebhookAuth)=WebhookProtocol.request(settings.copy(auth=auth),secret,"delivery_123")
    private fun rejects(block:()->Unit) {try {block();fail("Expected validation failure")} catch(_:IllegalArgumentException) {}}
    @Test fun validationHasStableReasonsAndNeverCarriesRequestSecrets() {
        data class Case(val reason:WebhookValidationFailure,val options:WebhookSettings,val credentials:WebhookCredentials=secret)
        val cases=listOf(
            Case(WebhookValidationFailure.url,settings.copy(endpoint="https://example.invalid/private token")),
            Case(WebhookValidationFailure.https,settings.copy(endpoint="http://example.invalid/")),
            Case(WebhookValidationFailure.endpoint,settings.copy(endpoint="https://owner:private-token@example.invalid/")),
            Case(WebhookValidationFailure.authenticationValue,settings.copy(auth=WebhookAuth.bearer),secret.copy(value="private-token\n")),
            Case(WebhookValidationFailure.apiKey,settings.copy(auth=WebhookAuth.apiKeyQuery),secret.copy(key="")),
            Case(WebhookValidationFailure.basicValue,settings.copy(auth=WebhookAuth.basic),secret.copy(username="private:token")),
            Case(WebhookValidationFailure.basicRequired,settings.copy(auth=WebhookAuth.basic),WebhookCredentials()),
            Case(WebhookValidationFailure.headerName,settings.copy(auth=WebhookAuth.customHeader),secret.copy(key="Authorization\n")),
            Case(WebhookValidationFailure.urlLength,settings.copy(auth=WebhookAuth.apiKeyQuery),secret.copy(value="雪".repeat(4000)))
        )
        for(case in cases) {
            try {WebhookProtocol.request(case.options,case.credentials,"stable_id");fail("Accepted ${case.reason}")}
            catch(error:WebhookValidationException) {
                assertEquals(case.reason,error.reason);assertEquals(case.reason.diagnostic,error.message)
                assertFalse(error.toString().contains("private-token"));assertNull(error.cause)
            }
        }
    }
    @Test fun allAuthModesHaveTheExpectedWireRepresentation() {
        assertFalse(plan(WebhookAuth.none).headers.containsKey("Authorization"))
        assertEquals("Bearer sensitive-value",plan(WebhookAuth.bearer).headers["Authorization"])
        assertEquals("Basic "+Base64.getEncoder().encodeToString("alice:sensitive-value".toByteArray()),plan(WebhookAuth.basic).headers["Authorization"])
        assertEquals(secret.value,plan(WebhookAuth.customHeader).headers["X-Api-Key"])
        assertEquals(settings.endpoint+"&X-Api-Key=sensitive-value",plan(WebhookAuth.apiKeyQuery).url)
        assertEquals("delivery_123",plan(WebhookAuth.none).headers["Idempotency-Key"])
    }
    @Test fun queryCredentialsCannotIntroduceNewParametersOrFragments() {
        val creds=WebhookCredentials("a&key","x=y &/?#+雪\r\n")
        for(endpoint in listOf("https://example.invalid/path","https://example.invalid/path?","https://example.invalid/path?other=%26")) {
            val result=WebhookProtocol.request(settings.copy(endpoint=endpoint,auth=WebhookAuth.apiKeyQuery),creds,"id")
            assertTrue(result.url.endsWith("a%26key=x%3Dy%20%26%2F%3F%23%2B%E9%9B%AA%0D%0A"))
            assertEquals(if(endpoint.contains("other")) 1 else 0,result.url.count {it=='&'})
            assertFalse(result.url.contains('#'))
        }
    }
    @Test fun allEightFormatsKeepTheirSpecificContentTypes() {
        for(format in ExportFormat.entries) {
            val request=WebhookProtocol.request(settings.copy(format=format),secret,"id")
            assertEquals(format.mime,request.headers["Content-Type"])
        }
    }
    @Test fun endpointValidationNeverEchoesCredentialsAndHttpNeedsOptIn() {
        for(endpoint in listOf("file:///tmp/secret","ftp://example.invalid","https://user:password@example.invalid/","https://example.invalid/#token","https://example.invalid:0/","https://example.invalid:65536/","https://example.invalid/\r\nAuthorization:x","not a URL")) {
            try {WebhookProtocol.request(settings.copy(endpoint=endpoint),secret,"id");fail("Invalid endpoint accepted")}
            catch(e:IllegalArgumentException) {assertFalse(e.message.orEmpty().contains("password"));assertFalse(e.message.orEmpty().contains(secret.value))}
        }
        rejects {WebhookProtocol.request(settings.copy(endpoint="http://127.0.0.1/"),secret,"id")}
        assertEquals("http://127.0.0.1/",WebhookProtocol.request(settings.copy(endpoint="http://127.0.0.1/",allowInsecureHttp=true),secret,"id").url)
        assertTrue(WebhookProtocol.request(settings.copy(endpoint="https://[::1]:8443/"),secret,"id").url.contains("[::1]"))
    }
    @Test fun headerAndBasicCredentialValidationPreventsInjection() {
        for(auth in listOf(WebhookAuth.bearer,WebhookAuth.customHeader)) {
            rejects {WebhookProtocol.request(settings.copy(auth=auth),secret.copy(value="ok\r\nX-Leak: secret"),"id")}
            rejects {WebhookProtocol.request(settings.copy(auth=auth),secret.copy(value="雪"),"id")}
        }
        for(name in listOf("Host","Content-Length","Content-Type","Connection","Transfer-Encoding","Idempotency-Key","User-Agent","Proxy-Authorization","bad key","X\nKey"))
            rejects {WebhookProtocol.request(settings.copy(auth=WebhookAuth.customHeader),secret.copy(key=name),"id")}
        rejects {WebhookProtocol.request(settings.copy(auth=WebhookAuth.basic),secret.copy(username="a:b"),"id")}
        rejects {WebhookProtocol.request(settings.copy(auth=WebhookAuth.basic),secret.copy(value="secret\n"),"id")}
        assertEquals("session=synthetic",WebhookProtocol.request(settings.copy(auth=WebhookAuth.customHeader),secret.copy(key="Cookie",value="session=synthetic"),"id").headers["Cookie"])
        val unicode=WebhookProtocol.request(settings.copy(auth=WebhookAuth.basic),WebhookCredentials(value="密碼",username="user"),"id")
        assertEquals("user:密碼",Base64.getDecoder().decode(unicode.headers.getValue("Authorization").removePrefix("Basic ")).toString(Charsets.UTF_8))
    }
    @Test fun displayRepresentationsMaskEndpointHeadersAndCredentials() {
        val value=settings.copy(endpoint="https://example.invalid/sensitive-value?secret=sensitive-value",auth=WebhookAuth.bearer)
        for(text in listOf(value.toString(),secret.toString(),WebhookProtocol.request(value,secret,"id").toString(),WebhookHttpResult(429,"sensitive-value").toString())) assertFalse(text.contains(secret.value))
        rejects {WebhookProtocol.request(settings,secret,"bad\r\nkey")}
    }
    @Test fun retryPolicyMatchesThreeAttemptIosClassesAndStopsPermanentOrRedirectResponses() {
        val now=Instant.parse("2026-10-27T12:00:00Z")
        for(code in listOf(0,408,429,500,503,599,600,999)) {
            assertEquals(WebhookRetry.After(now.plusSeconds(1)),WebhookRetryPolicy.decide(1,WebhookHttpResult(code),now))
            assertEquals(WebhookRetry.After(now.plusSeconds(2)),WebhookRetryPolicy.decide(2,WebhookHttpResult(code),now))
            assertEquals(WebhookRetry.Stopped,WebhookRetryPolicy.decide(3,WebhookHttpResult(code),now))
        }
        for(code in listOf(301,302,307,308,400,401,403,404,422)) assertEquals(WebhookRetry.Stopped,WebhookRetryPolicy.decide(1,WebhookHttpResult(code),now))
        for(code in listOf(200,201,202,204,299)) assertEquals(WebhookRetry.Complete,WebhookRetryPolicy.decide(3,WebhookHttpResult(code),now))
    }
    @Test fun retryAfterAcceptsDeltaOrDateButHasAOneDayBoundAndMinimumBackoff() {
        val now=Instant.parse("2026-10-27T12:00:00Z")
        val cases=mapOf("30" to 30L,"Tue, 27 Oct 2026 12:01:00 GMT" to 60L,"0" to 1L,"-1" to 1L,"garbage" to 1L,"+30" to 1L,"Tue, 27 Oct 2026 11:00:00 GMT" to 1L,"9999999999999999999999999999" to 86400L,"Wed, 28 Oct 2026 13:00:00 GMT" to 86400L)
        for((header,seconds) in cases) assertEquals(header,WebhookRetry.After(now.plusSeconds(seconds)),WebhookRetryPolicy.decide(1,WebhookHttpResult(429,header),now))
    }
    @Test fun settingsPreserveIosBatchChoicesAndDefaultDisabledState() {
        assertFalse(WebhookSettings().enabled);assertEquals(WebhookMode.realtime,WebhookSettings().mode)
        for(count in listOf(5,10,25,50,100)) assertEquals(count,WebhookSettings(batchCount=count).batchCount)
        for(minutes in listOf(1,5,15,30,60)) assertEquals(minutes,WebhookSettings(batchMinutes=minutes).batchMinutes)
        rejects {WebhookSettings(batchCount=0)};rejects {WebhookSettings(batchMinutes=2)}
    }
}
