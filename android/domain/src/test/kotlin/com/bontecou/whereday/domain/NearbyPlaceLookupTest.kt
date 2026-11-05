package com.bontecou.whereday.domain

import com.google.gson.JsonParser
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.text.Normalizer

class NearbyPlaceLookupTest {
    private val origin=Coordinate(0.0,0.0)
    private fun record(name:String="Cafe",lat:Double=.001,lon:Double=0.0)=PlaceLookupRecord(name,"Street",lat,lon)
    private class Provider:PlaceLookupProvider {
        data class Call(val coordinate:Coordinate,val radius:Double,val language:String,val query:NearbyPlaceQuery)
        val calls=mutableListOf<Call>()
        var forwardCalls=0;var reverseCalls=0
        var nearby:suspend (Call)->List<PlaceLookupRecord> = {listOf(PlaceLookupRecord("Cafe","Street",.001,0.0))}
        override suspend fun search(query:String,language:String):List<PlaceLookupRecord> {forwardCalls++;return listOf(PlaceLookupRecord("Search",null,0.0,0.0))}
        override suspend fun reverse(coordinate:Coordinate,language:String):List<PlaceLookupRecord> {reverseCalls++;return listOf(PlaceLookupRecord("Reverse",null,0.0,0.0))}
        override suspend fun nearby(coordinate:Coordinate,radiusMeters:Double,language:String,query:NearbyPlaceQuery):List<PlaceLookupRecord> {
            val call=Call(coordinate,radiusMeters,language,query);calls.add(call);return nearby(call)
        }
    }
    private fun service(provider:Provider,allowed:MutableStateFlow<Boolean> = MutableStateFlow(true),language:()->String={"en"},timeout:Long=2000)=PlaceLookupService(provider,allowed,language,timeoutMillis=timeout,minimumRequestIntervalMillis=0)

    @Test fun actualSwiftNormalizationRankingAndDistanceFixtures() {
        val data=javaClass.getResourceAsStream("/ios-nearby-places.json")!!.reader().use {JsonParser.parseReader(it).asJsonObject}
        assertEquals(2000.0,data.get("maximumRadius").asDouble,0.0)
        val cases=data.getAsJsonArray("cases");assertEquals(13,cases.size())
        fun String.nfc()=Normalizer.normalize(this,Normalizer.Form.NFC)
        for(value in cases) {
            val c=value.asJsonObject;val name=c.get("name").asString
            val rows=c.getAsJsonArray("records").map {r->val o=r.asJsonObject
                PlaceLookupRecord(o.get("name").takeUnless {it.isJsonNull}?.asString,o.get("address").takeUnless {it.isJsonNull}?.asString,o.get("latitude").asDouble,o.get("longitude").asDouble)}
            val center=c.getAsJsonArray("origin")
            val origin=Coordinate(center[0].asDouble,center[1].asDouble)
            val referenceDistances=c.getAsJsonArray("records").associate {r->val o=r.asJsonObject
                Coordinate(o.get("latitude").asDouble,o.get("longitude").asDouble) to o.get("distance").asDouble}
            // Separate algorithm parity from Core Location's floating-point distance quirks.
            val actual=nearbyPlaceSuggestions(rows,origin,c.get("radius").asDouble,50,"en-US") {_,point->referenceDistances.getValue(point)}
            val expected=c.getAsJsonArray("results");assertEquals(name,expected.size(),actual.size)
            expected.zip(actual).forEach {(e,a)->val x=e.asJsonObject
                assertEquals(name,x.get("id").asString.nfc(),a.id)
                assertEquals(name,x.get("name").asString.nfc(),a.name.nfc())
                assertEquals(name,x.get("address").takeUnless {it.isJsonNull}?.asString,a.address)
                assertEquals(name,Coordinate(x.get("latitude").asDouble,x.get("longitude").asDouble),a.coordinate)
                assertEquals(name,x.get("distance").asDouble,a.distanceMeters,.002)
                assertEquals(name,x.get("distance").asDouble,origin.distanceTo(a.coordinate),.002)
            }
            val production=nearbyPlaceSuggestions(rows,origin,c.get("radius").asDouble,50,"en-US")
            assertEquals(name,actual.map {it.id}.toSet(),production.map {it.id}.toSet())
            if(name=="id-away-from-zero-rounding") {
                // Real Core Location reports a ~2 nm difference between symmetric coordinates.
                // Android's shared metric reports a tie; its name tiebreak remains deterministic.
                assertTrue(referenceDistances.values.max()-referenceDistances.values.min()<.000001)
                assertEquals(listOf("Negative","Positive"),production.map {it.name})
            } else assertEquals(name,actual.map {it.id},production.map {it.id})
        }
    }
    @Test fun invalidProviderValuesAndPlaceholderNamesNeverBecomeSuggestions() {
        val bad=listOf(record("none"),record("UNKNOWN"),record("Current Location"),record(" "),record(lat=Double.NaN),record(lon=181.0),PlaceLookupRecord(null,"Address alone is not a business",0.0,0.0),PlaceLookupRecord("Missing",null,null,0.0))
        assertTrue(nearbyPlaceSuggestions(bad,origin).isEmpty())
        assertEquals(listOf("Cafe"),nearbyPlaceSuggestions(bad+record(),origin).map {it.name})
        assertEquals(4096,nearbyPlaceSuggestions(listOf(record("x".repeat(5000))),origin).single().name.length)
    }
    @Test fun cacheSeparatesSearchReverseRadiusLanguageAndExactCoordinateAndKeepsTheFullPage()=runBlocking {
        val provider=Provider();provider.nearby={listOf(record("Near"),record("Far",.002))};var language="en"
        val service=service(provider,language={language})
        assertEquals(1,service.nearby(origin,limit=1).size)
        assertEquals(2,service.nearby(origin,limit=8).size);assertEquals(1,provider.calls.size)
        service.reverse(origin);service.search("Cafe")
        assertEquals(1,provider.reverseCalls);assertEquals(1,provider.forwardCalls);assertEquals(1,provider.calls.size)
        service.nearby(origin,500.0);service.nearby(Coordinate(0.0,.000001));language="fr";service.nearby(origin)
        assertEquals(4,provider.calls.size)
    }
    @Test fun radiusClampsToTheActualMapKitMaximumAndInvalidArgumentsDoNotRequest()=runBlocking {
        val provider=Provider();val service=service(provider)
        service.nearby(origin,10_000.0);assertEquals(2000.0,provider.calls.single().radius,0.0)
        service.nearby(origin,2000.0);assertEquals(1,provider.calls.size)
        for(radius in listOf(0.0,-1.0,Double.NaN,Double.POSITIVE_INFINITY)) {
            try {service.nearby(origin,radius);fail()} catch(_:IllegalArgumentException) {}
        }
        for(limit in listOf(0,51)) try {service.nearby(origin,limit=limit);fail()} catch(_:IllegalArgumentException) {}
        assertEquals(1,provider.calls.size)
    }
    @Test fun fallbackUsesAllPlacesThenFiveQueriesOnlyWhenNormalizedResultsAreEmpty()=runBlocking {
        val provider=Provider();provider.nearby={when(val q=it.query) {
            NearbyPlaceQuery.Businesses->listOf(record("unknown"))
            NearbyPlaceQuery.AllPlaces->listOf(record("Too far",1.0))
            is NearbyPlaceQuery.Text->listOf(record(q.value,if(q.value=="coffee") .0005 else .001))
        }}
        val result=service(provider).nearby(origin)
        assertEquals(listOf(NearbyPlaceQuery.Businesses,NearbyPlaceQuery.AllPlaces)+listOf("restaurant","coffee","bar","hotel","shop").map {NearbyPlaceQuery.Text(it)},provider.calls.map {it.query})
        assertEquals("coffee",result.first().name);assertEquals(5,result.size)
    }
    @Test fun usableBusinessOrAllPlacesResultStopsFallback()=runBlocking {
        val provider=Provider();val service=service(provider)
        assertEquals(1,service.nearby(origin).size);assertEquals(listOf(NearbyPlaceQuery.Businesses),provider.calls.map {it.query})
        service.clearCache();provider.calls.clear();provider.nearby={if(it.query==NearbyPlaceQuery.AllPlaces) listOf(record()) else emptyList()}
        assertEquals(1,service.nearby(origin).size);assertEquals(listOf(NearbyPlaceQuery.Businesses,NearbyPlaceQuery.AllPlaces),provider.calls.map {it.query})
    }
    @Test fun optOutBlocksBothFreshAndCachedResultsWithoutAnyProviderCall()=runBlocking {
        val provider=Provider();val allowed=MutableStateFlow(false);val service=service(provider,allowed)
        try {service.nearby(origin);fail()} catch(_:PlaceLookupDisabled) {}
        assertTrue(provider.calls.isEmpty());allowed.value=true;service.nearby(origin);allowed.value=false
        try {service.nearby(origin);fail()} catch(_:PlaceLookupDisabled) {}
        assertEquals(1,provider.calls.size)
    }
    @Test fun revokingDuringFallbackCancelsAndLateResponseIsNotCached()=runBlocking {
        val provider=Provider();val allowed=MutableStateFlow(true);val started=CompletableDeferred<Unit>();val canceled=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        provider.nearby={if(it.query==NearbyPlaceQuery.Businesses) emptyList() else {started.complete(Unit);try {awaitCancellation()} finally {canceled.complete(Unit);withContext(NonCancellable) {release.await()}}}}
        val service=service(provider,allowed);val request=async {runCatching {service.nearby(origin)}}
        withTimeout(1000) {started.await()};allowed.value=false;withTimeout(1000) {canceled.await()};release.complete(Unit)
        assertTrue(withTimeout(1000) {request.await()}.exceptionOrNull() is PlaceLookupDisabled)
        assertEquals(2,provider.calls.size);provider.nearby={listOf(record("Fresh"))};allowed.value=true
        assertEquals("Fresh",service.nearby(origin).single().name);assertEquals(3,provider.calls.size)
    }
    @Test fun clearCancelsOwnedRequestAndSubsequentRequestLoadsFreshData()=runBlocking {
        val provider=Provider();val started=CompletableDeferred<Unit>()
        provider.nearby={started.complete(Unit);awaitCancellation()}
        val service=service(provider);val request=async {runCatching {service.nearby(origin)}}
        withTimeout(1000) {started.await()};withTimeout(1000) {service.clearCache()}
        assertTrue(request.await().exceptionOrNull() is CancellationException)
        provider.nearby={listOf(record("After clear"))}
        assertEquals("After clear",service.nearby(origin).single().name);assertEquals(2,provider.calls.size)
    }
    @Test fun timeoutAndProviderFailureAreNotCachedAndConcurrentCallersReuseCompletion()=runBlocking {
        val provider=Provider();val service=service(provider,timeout=100)
        provider.nearby={awaitCancellation()};try {service.nearby(origin);fail()} catch(_:TimeoutCancellationException) {}
        provider.nearby={throw java.io.IOException("Synthetic failure")};try {service.nearby(origin);fail()} catch(_:java.io.IOException) {}
        val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        provider.nearby={started.complete(Unit);release.await();listOf(record())}
        val one=async {service.nearby(origin)};started.await();val two=async {service.nearby(origin)};yield();release.complete(Unit)
        assertEquals(one.await(),two.await());assertEquals(3,provider.calls.size)
    }
    @Test fun unconfiguredNativeAddressProviderDoesNotPretendToProvideBusinesses()=runBlocking {
        val provider=object:PlaceLookupProvider {
            override suspend fun search(query:String,language:String)=emptyList<PlaceLookupRecord>()
            override suspend fun reverse(coordinate:Coordinate,language:String)=emptyList<PlaceLookupRecord>()
        }
        val service=PlaceLookupService(provider,MutableStateFlow(true),{"en"})
        try {service.nearby(origin);fail()} catch(_:NearbyPlaceLookupUnavailable) {}
    }
}
