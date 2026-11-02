package com.bontecou.whereday.domain

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import org.junit.Assert.*
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class PlaceLookupTest {
    private val origin=Coordinate(0.0,0.0)
    private fun row(name:String="Place",lat:Double=1.0,lon:Double=2.0)=PlaceLookupRecord(name,"Address",lat,lon)
    private class Provider:PlaceLookupProvider {
        var calls=0
        var forward:suspend (String,String)->List<PlaceLookupRecord> = {_,_->listOf(PlaceLookupRecord("Name","Address",1.0,2.0))}
        var backward:suspend (Coordinate,String)->List<PlaceLookupRecord> = {_,_->listOf(PlaceLookupRecord("Reverse","Street",8.0,9.0))}
        override suspend fun search(query:String,language:String):List<PlaceLookupRecord> {calls++;return forward(query,language)}
        override suspend fun reverse(coordinate:Coordinate,language:String):List<PlaceLookupRecord> {calls++;return backward(coordinate,language)}
    }
    @Test fun rejectsUnusableRecordsAndDeduplicatesWithSwiftRoundingAndNameFallback() {
        val rows=listOf(row("  Cafe  ",-.000005,.000005),row("cafe",-.000005,.000005),row("cafe",-.000015,.000015),
            PlaceLookupRecord(" ","  Fallback  ",0.0,0.0),PlaceLookupRecord(null,null,0.0,0.0),row(lat=Double.NaN),row(lon=181.0),PlaceLookupRecord("Missing",null,null,0.0))
        val result=placeLookupResults(rows)
        assertEquals(3,result.size);assertEquals("cafe--1-1",result[0].id);assertEquals("cafe--2-2",result[1].id)
        assertEquals("Fallback",result[2].name);assertEquals("Fallback",result[2].address)
        assertEquals(2,placeLookupResults(rows,2).size)
        assertEquals(4096,placeLookupResults(listOf(row("x".repeat(5000)))).single().name.length)
    }
    @Test fun shortQueriesDoNotCallProviderAndOptOutBlocksCachedAndNewRequests()=runBlocking {
        val allowed=MutableStateFlow(true);val provider=Provider();val service=PlaceLookupService(provider,allowed,{"en"},minimumRequestIntervalMillis=0)
        assertTrue(service.search(" a ").isEmpty());assertTrue(service.search("😀").isEmpty());assertEquals(0,provider.calls)
        service.search(" cafe ");service.search("CAFE");assertEquals(1,provider.calls)
        allowed.value=false
        for(query in listOf("cafe","new")) try {service.search(query);fail()} catch(_:PlaceLookupDisabled) {}
        try {service.reverse(origin);fail()} catch(_:PlaceLookupDisabled) {}
        assertEquals(1,provider.calls)
        try {service.search("x".repeat(257));fail()} catch(_:IllegalArgumentException) {}
    }
    @Test fun reverseMetadataNeverMovesSelectedPinAndCacheIsLanguageAndCoordinateSpecific()=runBlocking {
        val provider=Provider();var language="en";val service=PlaceLookupService(provider,MutableStateFlow(true),{language},minimumRequestIntervalMillis=0)
        val result=service.reverse(origin)!!;assertEquals(origin,result.coordinate);assertEquals("Street",result.address)
        assertEquals(result,service.reverse(origin));assertEquals(1,provider.calls)
        service.reverse(Coordinate(0.0,.000001));assertEquals(2,provider.calls)
        language="fr";service.reverse(origin);assertEquals(3,provider.calls)
    }
    @Test fun cacheExpiresEvictsLeastRecentAndClearRemovesResults()=runBlocking {
        val provider=Provider();var now=0L
        val service=PlaceLookupService(provider,MutableStateFlow(true),{"en"},{now},maximumCacheEntries=2,cacheLifetimeMillis=100,minimumRequestIntervalMillis=0)
        service.search("one");service.search("two");service.search("ONE");service.search("three");service.search("two")
        assertEquals(4,provider.calls)
        now=100;service.search("two");assertEquals(5,provider.calls)
        now=0;service.search("two");assertEquals(6,provider.calls)
        service.clearCache();service.search("two");assertEquals(7,provider.calls)
    }
    @Test fun optOutCancelsInFlightLookupAndDiscardsLateProviderResult()=runBlocking {
        val allowed=MutableStateFlow(true);val provider=Provider();val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val canceled=CompletableDeferred<Unit>()
        provider.forward={_,_->started.complete(Unit)
            try {awaitCancellation()} finally {canceled.complete(Unit);withContext(NonCancellable) {release.await()}}
            @Suppress("UNREACHABLE_CODE") listOf(row())
        }
        val service=PlaceLookupService(provider,allowed,{"en"},timeoutMillis=2000,minimumRequestIntervalMillis=0)
        val lookup=async {runCatching {service.search("first")}}
        withTimeout(1000) {started.await()};allowed.value=false;withTimeout(1000) {canceled.await()};release.complete(Unit)
        assertTrue(withTimeout(1000) {lookup.await()}.exceptionOrNull() is PlaceLookupDisabled)
        provider.forward={_,_->listOf(row("Fresh"))};allowed.value=true
        assertEquals("Fresh",service.search("first").single().name);assertEquals(2,provider.calls)
    }
    @Test fun canceledOrFailedLookupIsNotCachedAndTimeoutAllowsRetry()=runBlocking {
        val provider=Provider();val started=CompletableDeferred<Unit>();val cancellations=AtomicInteger()
        provider.forward={_,_->started.complete(Unit);try {awaitCancellation()} finally {cancellations.incrementAndGet()}}
        val service=PlaceLookupService(provider,MutableStateFlow(true),{"en"},timeoutMillis=100,minimumRequestIntervalMillis=0)
        try {service.search("slow");fail()} catch(_:TimeoutCancellationException) {}
        assertEquals(1,cancellations.get())
        provider.forward={_,_->throw java.io.IOException("failed")}
        try {service.search("slow");fail()} catch(_:java.io.IOException) {}
        provider.forward={_,_->listOf(row("Recovered"))}
        assertEquals("Recovered",service.search("slow").single().name);assertEquals(3,provider.calls)
    }
    @Test fun simultaneousIdenticalRequestsShareCompletedCacheRatherThanDuplicateProviderCalls()=runBlocking {
        val provider=Provider();val started=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        provider.forward={_,_->started.complete(Unit);release.await();listOf(row())}
        val service=PlaceLookupService(provider,MutableStateFlow(true),{"en"},minimumRequestIntervalMillis=0)
        val first=async {service.search("same")};started.await();val second=async {service.search("SAME")};yield();release.complete(Unit)
        assertEquals(first.await(),second.await());assertEquals(1,provider.calls)
    }
    @Test fun clearingOwnedDataCancelsLookupBeforeClearingItsCache()=runBlocking {
        val provider=Provider();val started=CompletableDeferred<Unit>();val canceled=CompletableDeferred<Unit>()
        provider.forward={_,_->started.complete(Unit);try {awaitCancellation()} finally {canceled.complete(Unit)}}
        val service=PlaceLookupService(provider,MutableStateFlow(true),{"en"},minimumRequestIntervalMillis=0)
        val pending=async {runCatching {service.search("pending")}}
        started.await();withTimeout(1000) {service.clearCache();canceled.await()}
        assertTrue(pending.await().exceptionOrNull() is CancellationException)
        provider.forward={_,_->listOf(row("After clear"))}
        assertEquals("After clear",service.search("pending").single().name);assertEquals(2,provider.calls)
    }

    @Test fun optOutDuringThrottleStopsTheNextNativeRequest()=runBlocking {
        val provider=Provider();val allowed=MutableStateFlow(true)
        val service=PlaceLookupService(provider,allowed,{"en"},{0L},minimumRequestIntervalMillis=500)
        service.search("first")
        val next=async {runCatching {service.search("second")}}
        delay(20);assertTrue(next.isActive);assertEquals(1,provider.calls)
        allowed.value=false
        assertTrue(withTimeout(1000) {next.await()}.exceptionOrNull() is PlaceLookupDisabled)
        assertEquals(1,provider.calls)
    }

    @Test fun canonicallyEquivalentUnicodeMatchesSwiftStringEqualityForIdsAndCacheKeys()=runBlocking {
        val rows=placeLookupResults(listOf(row("Café"),row("Cafe\u0301")))
        assertEquals(1,rows.size);assertEquals("Café",rows.single().name)
        val provider=Provider();val service=PlaceLookupService(provider,MutableStateFlow(true),{"en"},minimumRequestIntervalMillis=0)
        service.search("Café");service.search("CAFE\u0301")
        assertEquals(1,provider.calls)
    }

}
