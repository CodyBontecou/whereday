package com.bontecou.whereday.platform

import com.bontecou.whereday.domain.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import okhttp3.OkHttpClient
import okhttp3.mockwebserver.*
import org.junit.*
import org.junit.Assert.*
import java.util.concurrent.TimeUnit

class GeoapifyPlaceLookupTest {
    private lateinit var server:MockWebServer
    private val origin=Coordinate(48.8584,2.2945)
    private val key="synthetic-only-key"
    private val addresses=object:PlaceLookupProvider {
        override suspend fun search(query:String,language:String)=listOf(PlaceLookupRecord(query,language,1.0,2.0))
        override suspend fun reverse(coordinate:Coordinate,language:String)=listOf(PlaceLookupRecord("system",language,coordinate.latitude,coordinate.longitude))
    }
    @Before fun start() {server=MockWebServer();server.start()}
    @After fun stop() {server.shutdown()}
    private fun places()=GeoapifyNearbyPlaces(server.url("/v2/places"),key,OkHttpClient())
    private fun provider()=GeoapifyPlaceLookup(addresses,places())
    private fun feature(name:String="Cafe QA",lat:String="48.8584",lon:String="2.2945")="""{"type":"Feature","properties":{"name":"$name","formatted":"QA address","lat":$lat,"lon":$lon}}"""
    private fun reply(features:String=feature())=MockResponse().setHeader("Content-Type","application/geo+json; charset=utf-8").setBody("""{"type":"FeatureCollection","features":[$features]}""")
    @Test fun requestHasOnlyRequiredSearchContextAndMapsNamedGeoJson():Unit=runBlocking {
        server.enqueue(reply())
        assertEquals(listOf(PlaceLookupRecord("Cafe QA","QA address",origin.latitude,origin.longitude)),places().nearby(origin,1000.0,"es-PR",NearbyPlaceQuery.Businesses))
        val request=server.takeRequest();val url=request.requestUrl!!
        assertEquals("GET",request.method);assertEquals("/v2/places",url.encodedPath)
        assertEquals(setOf("apiKey","categories","filter","bias","limit","lang"),url.queryParameterNames)
        assertEquals(key,url.queryParameter("apiKey"));assertEquals("circle:2.2945,48.8584,1000.0",url.queryParameter("filter"))
        assertEquals("proximity:2.2945,48.8584",url.queryParameter("bias"));assertEquals("50",url.queryParameter("limit"));assertEquals("es",url.queryParameter("lang"))
        assertEquals(0L,request.bodySize)
    }
    @Test fun fallbackTermsUseCategoriesAndCustomNamesAreEncoded():Unit=runBlocking {
        val expected=mapOf("restaurant" to "catering.restaurant","coffee" to "catering.cafe","bar" to "catering.bar","hotel" to "accommodation.hotel","shop" to "commercial")
        for((term,category) in expected) {
            server.enqueue(reply(""));places().nearby(origin,500.0,"en",NearbyPlaceQuery.Text(term))
            val url=server.takeRequest().requestUrl!!;assertEquals(category,url.queryParameter("categories"));assertNull(url.queryParameter("name"))
        }
        server.enqueue(reply(""));places().nearby(origin,500.0,"zh-Hant-TW",NearbyPlaceQuery.Text("Café & Tea"))
        val url=server.takeRequest().requestUrl!!;assertEquals("Café & Tea",url.queryParameter("name"));assertEquals("zh",url.queryParameter("lang"))
        assertTrue(url.queryParameter("categories")!!.contains("public_transport"))
    }
    @Test fun systemSearchAndReverseDoNotUseTheBusinessEndpoint():Unit=runBlocking {
        val provider=provider()
        assertEquals("Address",provider.search("Address","en").single().name)
        assertEquals(origin.latitude,provider.reverse(origin,"es").single().latitude)
        assertEquals(0,server.requestCount)
        assertSame(addresses,GeoapifyPlaceLookup.configured(addresses))
    }
    @Test fun malformedUnnamedAndInvalidCoordinatesAreNeverSelectable():Unit=runBlocking {
        server.enqueue(reply(listOf(feature(),feature("",lat="90.1"),feature("Bad number",lat="\"48.8584\""),"{}","{\"type\":\"Feature\",\"properties\":{\"formatted\":\"No business name\",\"lat\":0,\"lon\":0}}",feature("Overflow",lat="1e999")).joinToString(",")))
        assertEquals(listOf("Cafe QA"),places().nearby(origin,1000.0,"en",NearbyPlaceQuery.Businesses).map {it.name})
    }
    @Test fun statusAndRedirectFailuresAreRedactedAndNotRetried():Unit=runBlocking {
        for(code in listOf(301,307,400,401,403,429,500,503)) {
            server.enqueue(MockResponse().setResponseCode(code).setHeader("Location",server.url("/leak")).setBody("$key ${origin.latitude} private error"))
            val before=server.requestCount
            try {places().nearby(origin,1000.0,"en",NearbyPlaceQuery.Businesses);fail()} catch(error:java.io.IOException) {
                generateSequence<Throwable>(error) {it.cause}.take(8).forEach {assertEquals("Nearby business search is unavailable. Try again later.",it.message)}
            }
            server.takeRequest();assertEquals(before+1,server.requestCount)
            assertNull(server.takeRequest(50,TimeUnit.MILLISECONDS))
        }
    }
    @Test fun retryAfterZeroCannotTriggerAnAutomaticServiceUnavailableFollowup():Unit=runBlocking {
        server.enqueue(MockResponse().setResponseCode(503).setHeader("Retry-After","0"))
        server.enqueue(reply())
        try {places().nearby(origin,1000.0,"en",NearbyPlaceQuery.Businesses);fail("503 must not be retried")} catch(_:java.io.IOException) {}
        assertEquals(1,server.requestCount)
    }
    @Test fun strictBoundedResponseRejectsDuplicatesMalformedAndExcessFeatures():Unit=runBlocking {
        val bodies=listOf("{}","{\"type\":\"FeatureCollection\",\"features\":[],\"features\":[]}","{bad}","""{"type":"FeatureCollection","features":[${feature().replace("\"name\":", "\"name\":\"duplicate\",\"name\":")}]}""","""{"type":"FeatureCollection","features":[${List(51) {feature()}.joinToString(",")}]}""","x".repeat(1_048_577))
        for(body in bodies) {
            server.enqueue(MockResponse().setHeader("Content-Type","application/json").setBody(body))
            try {places().nearby(origin,1000.0,"en",NearbyPlaceQuery.Businesses);fail()} catch(_:java.io.IOException) {}
        }
        server.enqueue(MockResponse().setHeader("Content-Type","text/html").setBody("{}"))
        try {places().nearby(origin,1000.0,"en",NearbyPlaceQuery.Businesses);fail()} catch(_:java.io.IOException) {}
    }
    @Test fun chunkedOversizeAndMalformedUtf8AreRejected():Unit=runBlocking {
        for(response in listOf(
            MockResponse().setHeader("Content-Type","application/json").setChunkedBody("x".repeat(1_048_577),8192),
            MockResponse().setHeader("Content-Type","application/json").setBody(okio.Buffer().write(byteArrayOf(0xC3.toByte(),0x28))),
        )) {
            server.enqueue(response)
            try {places().nearby(origin,1000.0,"en",NearbyPlaceQuery.Businesses);fail()} catch(_:java.io.IOException) {}
        }
    }
    @Test fun invalidInputsNeverSendAndUnsupportedLanguageUsesEnglish():Unit=runBlocking {
        for(radius in listOf(0.0,-1.0,Double.NaN,2001.0)) {
            try {places().nearby(origin,radius,"en",NearbyPlaceQuery.Businesses);fail()} catch(_:IllegalArgumentException) {}
        }
        for(query in listOf("", "x".repeat(257))) {
            try {places().nearby(origin,1000.0,"en",NearbyPlaceQuery.Text(query));fail()} catch(_:IllegalArgumentException) {}
        }
        assertEquals(0,server.requestCount)
        server.enqueue(reply(""));places().nearby(origin,1000.0,"zz-ZZ",NearbyPlaceQuery.Businesses)
        assertEquals("en",server.takeRequest().requestUrl!!.queryParameter("lang"))
    }
    @Test fun cancellationClosesTheOwnedCall():Unit=runBlocking {supervisorScope {
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job=async {places().nearby(origin,1000.0,"en",NearbyPlaceQuery.Businesses)}
        assertNotNull(withContext(Dispatchers.IO) {server.takeRequest(2,TimeUnit.SECONDS)})
        withTimeout(2000) {job.cancelAndJoin()};assertTrue(job.isCancelled)
    }}
    @Test fun optOutMakesNoRequestAndRevokesAnInflightLookup():Unit=runBlocking {supervisorScope {
        val allowed=MutableStateFlow(false)
        val service=PlaceLookupService(provider(),allowed,{"en"},minimumRequestIntervalMillis=0)
        try {service.nearby(origin);fail()} catch(_:PlaceLookupDisabled) {}
        assertEquals(0,server.requestCount)
        allowed.value=true;server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job=async {service.nearby(origin)}
        assertNotNull(withContext(Dispatchers.IO) {server.takeRequest(2,TimeUnit.SECONDS)})
        allowed.value=false
        try {withTimeout(2000) {job.await()};fail()} catch(_:PlaceLookupDisabled) {}
        assertEquals(1,server.requestCount)
    }}
    @Test fun normalizedCachedResultsRetainLimitAndCannotBypassOptOut():Unit=runBlocking {
        val allowed=MutableStateFlow(true);val service=PlaceLookupService(provider(),allowed,{"en"},minimumRequestIntervalMillis=0)
        server.enqueue(reply(listOf(feature("First"),feature("First"),feature("Second",lat="48.8585"),feature("Outside",lat="49.0")).joinToString(",")))
        assertEquals(1,service.nearby(origin,limit=1).size)
        assertEquals(listOf("First","Second"),service.nearby(origin,limit=8).map {it.name})
        assertEquals(1,server.requestCount)
        allowed.value=false
        try {service.nearby(origin);fail()} catch(_:PlaceLookupDisabled) {}
        assertEquals(1,server.requestCount)
    }
    @Test fun emptyBusinessAndBroadResultsUseTheFiveCategoryFallbacks():Unit=runBlocking {
        repeat(7) {server.enqueue(reply(""))}
        val service=PlaceLookupService(provider(),MutableStateFlow(true),{"en"},minimumRequestIntervalMillis=0)
        assertTrue(service.nearby(origin).isEmpty());assertEquals(7,server.requestCount)
        val urls=(0..6).map {server.takeRequest().requestUrl!!}
        assertFalse(urls[0].queryParameter("categories")!!.contains("public_transport"))
        assertTrue(urls[1].queryParameter("categories")!!.contains("public_transport"))
        assertEquals(listOf("catering.restaurant","catering.cafe","catering.bar","accommodation.hotel","commercial"),urls.drop(2).map {it.queryParameter("categories")})
    }
}
