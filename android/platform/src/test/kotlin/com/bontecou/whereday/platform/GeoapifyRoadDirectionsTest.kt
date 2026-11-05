package com.bontecou.whereday.platform

import com.bontecou.whereday.data.AppSettings
import com.bontecou.whereday.domain.*
import java.io.IOException
import java.time.Instant
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import okhttp3.*
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.mockwebserver.*
import okio.Buffer
import org.junit.*
import org.junit.Assert.*

class GeoapifyRoadDirectionsTest {
    private lateinit var server:MockWebServer
    private val start=Coordinate(48.8584,2.2945)
    private val end=Coordinate(48.8584,2.3045)
    private val key="synthetic-routing-key"
    @Before fun setup() {server=MockWebServer();server.start()}
    @After fun cleanup() {server.shutdown()}
    private fun adapter(client:OkHttpClient=OkHttpClient(),timeout:Long=2000)=GeoapifyRoadDirections(server.url("/v1/routing"),key,client,timeout)
    private fun feature(distance:String="900.0",units:String="Meters",coordinates:String="[[[2.2945,48.8584],[2.2995,48.86],[2.3045,48.8584]]]",mode:String="walk")=
        """{"type":"Feature","properties":{"mode":"$mode","distance":$distance,"distance_units":"$units","legs":[{"steps":[{"instruction":{"text":"not retained"}}]}]},"geometry":{"type":"MultiLineString","coordinates":$coordinates}}"""
    private fun document(features:String=feature())="""{"type":"FeatureCollection","properties":{"unknown":{"nested":[true,null,123,"ignored"]}},"features":[$features]}"""
    private fun response(raw:String=document())=MockResponse().setHeader("Content-Type","application/geo+json; charset=utf-8").setBody(raw)
    private suspend fun rejected(reply:MockResponse,transport:GeoapifyRoadDirections=adapter()) {
        server.enqueue(reply)
        try {transport.route(start,end,RoadTransport.walking);fail("Unsafe response accepted")}
        catch(error:IOException) {
            assertEquals("Road routing is unavailable. Try again later.",error.message)
            // Coroutine stack recovery may wrap a sanitized IOException in another copy.
            val causes=generateSequence(error as Throwable) {it.cause}.take(8).toList()
            assertNull(causes.last().cause)
            for(cause in causes) assertEquals("Road routing is unavailable. Try again later.",cause.message)
            assertFalse(error.toString().contains(key));assertFalse(error.toString().contains("48.8584"))
        }
    }
    @Test(timeout=10000) fun sendsOnlyTwoLatLonEndpointsAndRequestedTravelMode():Unit=runBlocking {
        for((mode,wire) in listOf(RoadTransport.walking to "walk",RoadTransport.automobile to "drive")) {
            server.enqueue(response(document(feature(mode=wire))))
            val result=checkNotNull(adapter().route(start,end,mode))
            assertEquals(900.0,result.distanceMeters,0.0)
            assertEquals(listOf(start,Coordinate(48.86,2.2995),end),result.coordinates)
            val request=server.takeRequest();val url=checkNotNull(request.requestUrl)
            assertEquals("GET",request.method);assertEquals("/v1/routing",url.encodedPath);assertEquals(0L,request.bodySize)
            assertEquals(setOf("waypoints","mode","units","format","apiKey"),url.queryParameterNames)
            assertEquals("48.8584,2.2945|48.8584,2.3045",url.queryParameter("waypoints"))
            assertEquals(wire,url.queryParameter("mode"));assertEquals("metric",url.queryParameter("units"));assertEquals("geojson",url.queryParameter("format"))
            assertEquals(key,url.queryParameter("apiKey"));assertEquals("no-store",request.getHeader("Cache-Control"))
            assertNull(request.getHeader("Authorization"));assertNull(request.getHeader("Cookie"))
        }
    }
    @Test fun emptyFeatureCollectionHasNoRoute():Unit=runBlocking {
        server.enqueue(response(document("")));assertNull(adapter().route(start,end,RoadTransport.walking))
    }
    @Test fun serverMetadataCannotReplaceTheDeclaredProviderAndDataCredits():Unit=runBlocking {
        val forged=feature().replace("\"mode\":\"walk\"","\"mode\":\"walk\",\"attribution\":{\"name\":\"Injected\",\"url\":\"https://example.test\"}")
        server.enqueue(response(document(forged)))
        assertEquals(listOf(RoadAttribution("Geoapify","https://www.geoapify.com/"),RoadAttribution("OpenStreetMap","https://www.openstreetmap.org/copyright",RoadAttributionKind.data)),
            adapter().route(start,end,RoadTransport.walking)!!.attributions)
    }
    @Test fun acceptsOptionalFiniteAltitudeWithoutChangingTheTwoDimensionalRoute():Unit=runBlocking {
        server.enqueue(response(document(feature(coordinates="[[[2.2945,48.8584,12.0],[2.3045,48.8584,15.0]]]"))))
        assertEquals(listOf(start,end),adapter().route(start,end,RoadTransport.walking)!!.coordinates)
    }
    @Test(timeout=15000) fun rejectsMalformedGeometryUnitsCountsAndUnrequestedAlternatives():Unit=runBlocking {
        val good=feature()
        val bad=listOf(
            feature(distance="0"),feature(distance="-1"),feature(distance="\"900\""),feature(distance="1e999"),feature(units="Miles"),feature(mode="drive"),
            good.replace("\"distance_units\":\"Meters\",",""),good.replace("\"distance_units\":\"Meters\"", "\"unused\":true"),
            feature(coordinates="[]"),feature(coordinates="[[]]"),feature(coordinates="[[[2.2945,48.8584]]]"),
            feature(coordinates="[[[2.0,91.0],[2.0,48.0]]]"),feature(coordinates="[[[181.0,48.0],[2.0,48.0]]]"),
            feature(coordinates="[[[\"2.0\",48.0],[2.0,48.0]]]"),feature(coordinates="[[[2.0],[2.0,48.0]]]"),
            feature(coordinates="[[[2.0,48.0,0,0],[2.0,48.0]]]"),feature(coordinates="[[[2.0,48.0],[2.1,48.0]],[[3.0,48.0],[3.1,48.0]]]"),
            good.replace("MultiLineString","LineString"),good.replace("\"geometry\":{", "\"geometry\":null,\"other\":{")
        )
        for(raw in bad) rejected(response(document(raw)))
        rejected(response(document("$good,$good")))
        rejected(response(document().replace("FeatureCollection","Collection")))
    }
    @Test(timeout=10000) fun strictReaderRejectsDuplicateKeysSyntaxTrailingDataAndExcessiveNesting():Unit=runBlocking {
        for(raw in listOf(
            document().replace("\"distance\":900.0","\"distance\":900.0,\"distance\":1"),
            document().replace("\"unknown\":{","\"unknown\":{\"x\":1,\"x\":2,"),
            document()+" {}",document().replace("900.0","NaN"),document().replace("900.0","+900"),
            document().replace("\"unknown\":{", "\"unknown\":{\"deep\":"+"[".repeat(20)+"0"+"]".repeat(20)+","),
            document().replace("\"ignored\"","\""+"x".repeat(16_385)+"\""),
            document().replace("\"unknown\":{", "\"unknown\":{\"many\":["+List(20_001) {"null"}.joinToString(",")+"],")
        )) rejected(response(raw))
    }
    @Test(timeout=15000) fun coordinateLimitAcceptsTwentyThousandAndRejectsOneMore():Unit=runBlocking {
        for(count in listOf(20_000,20_001)) {
            val raw=document(feature(coordinates="[["+List(count) {"[2.2945,48.8584]"}.joinToString(",")+"]]"))
            if(count==20_000) {server.enqueue(response(raw));assertEquals(count,adapter().route(start,end,RoadTransport.walking)!!.coordinates.size)}
            else rejected(response(raw))
        }
    }
    @Test(timeout=10000) fun unknownMetadataCannotBypassObjectOrTotalWorkBudgets():Unit=runBlocking {
        val wide=(0..256).joinToString(",") {"\"field$it\":null"}
        rejected(response(document().replace("\"ignored\"","{$wide}")))
        val many="["+List(1000) {"["+List(200) {"null"}.joinToString(",")+"]"}.joinToString(",")+"]"
        rejected(response(document().replace("\"ignored\"",many)))
    }
    @Test(timeout=10000) fun boundsBothKnownAndChunkedBodiesAndRejectsMalformedUtf8():Unit=runBlocking {
        val oversized=" ".repeat(2_097_153)
        rejected(response(oversized));rejected(MockResponse().setHeader("Content-Type","application/json").setChunkedBody(oversized,8192))
        rejected(MockResponse().setHeader("Content-Type","application/json").setBody(Buffer().write(byteArrayOf(0xc3.toByte(),0x28))))
        rejected(response().setHeader("Content-Type","text/html"))
        rejected(response().setHeader("Content-Type","application/json; charset=iso-8859-1"))
    }
    @Test(timeout=10000) fun doesNotRedirectAuthenticateOrRetryHttpFailures():Unit=runBlocking {
        for(code in listOf(301,302,307,308,401,403,408,429,500,503)) {
            val before=server.requestCount
            rejected(response("not route data").setResponseCode(code).setHeader("Location",server.url("/must-not-follow"))
                .setHeader("Retry-After","0").setHeader("WWW-Authenticate","Basic realm=QA"))
            assertEquals(before+1,server.requestCount)
            assertEquals("/v1/routing",server.takeRequest().requestUrl!!.encodedPath)
            assertNull(server.takeRequest(40,TimeUnit.MILLISECONDS))
        }
    }
    @Test(timeout=10000) fun inheritedCookiesAuthenticationAndDiskCacheCannotRetainOrReplayARoute():Unit=runBlocking {
        val directory=kotlin.io.path.createTempDirectory("whereday-routing-cache-qa").toFile()
        val cache=Cache(directory,2_097_152)
        val cookieAccess=java.util.concurrent.atomic.AtomicInteger();val authentication=java.util.concurrent.atomic.AtomicInteger()
        val base=OkHttpClient.Builder().cache(cache).cookieJar(object:CookieJar {
            override fun loadForRequest(url:HttpUrl):List<Cookie> {cookieAccess.incrementAndGet();return listOf(Cookie.Builder().name("private").value("synthetic").domain(url.host).build())}
            override fun saveFromResponse(url:HttpUrl,cookies:List<Cookie>) {cookieAccess.incrementAndGet()}
        }).authenticator {_,reply->authentication.incrementAndGet();reply.request.newBuilder().header("Authorization","synthetic-auth").build()}.build()
        try {
            val transport=adapter(base)
            for(distance in listOf(900,950)) {
                server.enqueue(response(document(feature(distance=distance.toString()))).setHeader("Cache-Control","public, max-age=3600").setHeader("Set-Cookie","private=synthetic; Path=/"))
                assertEquals(distance.toDouble(),transport.route(start,end,RoadTransport.walking)!!.distanceMeters,0.0)
            }
            rejected(response().setResponseCode(401).setHeader("WWW-Authenticate","Basic realm=QA"),transport)
            assertEquals(3,server.requestCount);assertEquals(0,cookieAccess.get());assertEquals(0,authentication.get())
            repeat(3) {val request=server.takeRequest();assertNull(request.getHeader("Cookie"));assertNull(request.getHeader("Authorization"))}
        } finally {cache.close();directory.deleteRecursively()}
    }
    @Test(timeout=10000) fun rateLimitCooldownSuppressesOtherGapsUntilItsMonotonicDeadline():Unit=runBlocking {
        var clock=0L
        val transport=GeoapifyRoadDirections(server.url("/v1/routing"),key,OkHttpClient(),2000,{clock},{0})
        rejected(response().setResponseCode(429).setHeader("Retry-After","120"),transport)
        try {transport.route(start,end,RoadTransport.automobile);fail()} catch(_:IOException) {}
        assertEquals(1,server.requestCount)
        clock=119_999_999_999
        try {transport.route(start,end,RoadTransport.walking);fail()} catch(_:IOException) {}
        assertEquals(1,server.requestCount)
        clock=120_000_000_000;server.enqueue(response())
        assertNotNull(transport.route(start,end,RoadTransport.walking));assertEquals(2,server.requestCount)
    }
    @Test(timeout=10000) fun busyCooldownHonorsHttpDateAndBoundsInvalidOrExcessiveDelay():Unit=runBlocking {
        for((header,seconds) in listOf("Thu, 01 Jan 1970 00:02:00 GMT" to 120L,"invalid" to 60L,"0" to 60L,"999999999999999" to 3600L)) {
            var clock=0L;val transport=GeoapifyRoadDirections(server.url("/v1/routing"),key,OkHttpClient(),2000,{clock},{0})
            rejected(response().setResponseCode(503).setHeader("Retry-After",header),transport)
            val before=server.requestCount
            clock=(seconds-1)*1_000_000_000
            try {transport.route(start,end,RoadTransport.walking);fail()} catch(_:IOException) {}
            assertEquals(before,server.requestCount)
            clock=seconds*1_000_000_000;server.enqueue(response());assertNotNull(transport.route(start,end,RoadTransport.walking))
        }
    }
    @Test(timeout=10000) fun cancellationCancelsTheActualSocketCall():Unit=runBlocking {supervisorScope {
        val failed=CompletableDeferred<Unit>()
        val client=OkHttpClient.Builder().eventListener(object:EventListener() {override fun callFailed(call:Call,ioe:IOException) {failed.complete(Unit)}}).build()
        server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
        val job=async {adapter(client).route(start,end,RoadTransport.walking)}
        assertNotNull(withContext(Dispatchers.IO) {server.takeRequest(2,TimeUnit.SECONDS)})
        withTimeout(2000) {job.cancelAndJoin();failed.await()};assertTrue(job.isCancelled)
    }}
    @Test(timeout=5000) fun callDeadlineFailsWithASanitizedError():Unit=runBlocking {
        rejected(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE),adapter(timeout=150))
        assertEquals(1,server.requestCount)
    }
    @Test fun configurationIsIndependentAndUnavailableBuildsHaveNoProvider() {
        assertNull(GeoapifyRoadDirections.configured(false,key))
        for(invalid in listOf(""," ","a\nb","x".repeat(513))) assertNull(GeoapifyRoadDirections.configured(true,invalid))
        val configured=checkNotNull(GeoapifyRoadDirections.configured(true,key))
        assertEquals("Geoapify Routing",configured.disclosure.name)
        assertEquals("https://www.geoapify.com/privacy-policy/",configured.disclosure.privacyUrl)
        assertEquals(0,server.requestCount)
    }
    @Test fun unsafeEndpointOverridesAreRejectedBeforeAnyRequest() {
        for(url in listOf("http://example.test/v1/routing","https://user:synthetic@example.test/v1/routing","https://example.test/v1/routing?secret=synthetic","https://example.test/v1/routing#fragment","https://example.test/wrong")) {
            try {GeoapifyRoadDirections(url.toHttpUrl(),key,OkHttpClient());fail("Unsafe endpoint accepted")}
            catch(_:IllegalArgumentException) {}
        }
        assertEquals(0,server.requestCount)
    }
    @Test(timeout=10000) fun invalidHttpGeometryFallsBackToOriginalPointsWithoutPublishingAnEstimate():Unit=runBlocking {
        val settings=MutableStateFlow(AppSettings(roadDisplayEnabled=true,networkGeocoding=true))
        val controller=RoadDisplayController(adapter(),settings,{settings.value},{},MutableStateFlow(JournalClearState()),minimumRequestIntervalMillis=0)
        val session=controller.createSession()
        val points=listOf(TrackPoint("a",start,Instant.EPOCH,5.0),TrackPoint("b",end,Instant.EPOCH.plusSeconds(600),5.0))
        try {
            withTimeout(2000) {controller.state.first {!it.loading}};session.foreground()
            repeat(2) {server.enqueue(response(document(feature(coordinates="[[[2.0,91.0],[2.0,48.0]]]"))))}
            val plan=session.prepare(points)
            assertFalse(plan.route.hasSnappedSegments);assertEquals(setOf(RoadDisplayWarning.providerUnavailable),plan.warnings)
            assertEquals(listOf(start,end),plan.route.segments.single().coordinates);assertEquals(RoadSnapping.fingerprint(points),plan.route.fingerprint)
            assertEquals(2,server.requestCount)
        } finally {session.close();controller.close()}
    }
    @Test(timeout=10000) fun realControllerRequiresOptInCachesOnlyAdmittedResultsAndCancelsHttpOnRevocation():Unit=runBlocking {supervisorScope {
        val failed=CompletableDeferred<Unit>()
        val client=OkHttpClient.Builder().eventListener(object:EventListener() {override fun callFailed(call:Call,ioe:IOException) {failed.complete(Unit)}}).build()
        val settings=MutableStateFlow(AppSettings(roadDisplayEnabled=false,networkGeocoding=true,roadAccessRevision="a"))
        val controller=RoadDisplayController(adapter(client),settings,{settings.value},{settings.value=settings.value.copy(roadDisplayEnabled=false)},
            MutableStateFlow(JournalClearState(generation="qa")),minimumRequestIntervalMillis=0)
        val points=listOf(TrackPoint("private-a",start,Instant.EPOCH,5.0),TrackPoint("private-b",end,Instant.EPOCH.plusSeconds(600),5.0))
        val original=points.toList();val session=controller.createSession()
        try {
            withTimeout(2000) {controller.state.first {!it.loading}};session.foreground()
            try {session.prepare(points);fail("Unapproved routing sent")} catch(_:RoadDisplayUnavailable) {}
            assertEquals(0,server.requestCount)
            settings.value=settings.value.copy(roadDisplayEnabled=true,roadAccessRevision="b")
            withTimeout(2000) {controller.state.first {it.enabled && it.policyRevision=="b"}}
            server.enqueue(response());val plan=session.prepare(points)
            assertTrue(plan.route.hasSnappedSegments);assertEquals(RoadSnapping.fingerprint(points),plan.route.fingerprint)
            assertTrue(session.prepare(points).fromCache);assertEquals(1,server.requestCount)
            assertEquals(original,points)
            server.takeRequest()
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending=async {session.prepare(points,true)}
            assertNotNull(withContext(Dispatchers.IO) {server.takeRequest(2,TimeUnit.SECONDS)})
            settings.value=settings.value.copy(networkGeocoding=false,roadAccessRevision="c")
            withTimeout(2000) {pending.join();failed.await()};assertTrue(pending.isCancelled)
            assertFalse(session.isCurrent(plan,plan.route.fingerprint))
            try {session.prepare(points);fail()} catch(_:RoadDisplayUnavailable) {}
            assertEquals(2,server.requestCount);assertEquals(original,points)
        } finally {session.close();controller.close()}
    }}
    @Test(timeout=10000) fun clearJoinsTheHttpRequestAndResetsConsent():Unit=runBlocking {supervisorScope {
        val failed=CompletableDeferred<Unit>()
        val client=OkHttpClient.Builder().eventListener(object:EventListener() {override fun callFailed(call:Call,ioe:IOException) {failed.complete(Unit)}}).build()
        val settings=MutableStateFlow(AppSettings(roadDisplayEnabled=true,networkGeocoding=true))
        val controller=RoadDisplayController(adapter(client),settings,{settings.value},{settings.value=settings.value.copy(roadDisplayEnabled=false)},
            MutableStateFlow(JournalClearState()),minimumRequestIntervalMillis=0)
        val session=controller.createSession()
        try {
            withTimeout(2000) {controller.state.first {!it.loading}};session.foreground()
            val points=listOf(TrackPoint("a",start,Instant.EPOCH,5.0),TrackPoint("b",end,Instant.EPOCH.plusSeconds(600),5.0))
            server.enqueue(MockResponse().setSocketPolicy(SocketPolicy.NO_RESPONSE))
            val pending=async {session.prepare(points)}
            assertNotNull(withContext(Dispatchers.IO) {server.takeRequest(2,TimeUnit.SECONDS)})
            withTimeout(2000) {controller.clearAndJoin();pending.join();failed.await()}
            assertFalse(settings.value.roadDisplayEnabled);assertTrue(pending.isCancelled);assertEquals(1,server.requestCount)
        } finally {session.close();controller.close()}
    }}
}
