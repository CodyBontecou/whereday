package com.bontecou.whereday.platform

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class GeocoderRequestsTest {
    private fun fixture(block:suspend CoroutineScope.(GeocoderRequests)->Unit)=runBlocking {
        val executor=GeocoderRequests.newLegacyExecutor()
        try {withTimeout(5000) {block(GeocoderRequests(executor))}}
        finally {executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS))}
    }
    @Test fun canceledQueuedLegacyLookupNeverReachesTheProvider()=fixture {requests->
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val calls=AtomicInteger()
        try {
            val running=launch(start=CoroutineStart.UNDISPATCHED) {requests.blocking {calls.incrementAndGet();entered.countDown();release.await();"first"}}
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            val abandoned=launch(start=CoroutineStart.UNDISPATCHED) {requests.blocking {calls.incrementAndGet();"abandoned"}}
            abandoned.cancelAndJoin();running.cancelAndJoin()
            val next=async(start=CoroutineStart.UNDISPATCHED) {requests.blocking {calls.incrementAndGet();"next"}}
            release.countDown();assertEquals("next",next.await());assertEquals(2,calls.get())
        } finally {release.countDown()}
    }
    @Test fun stalledLegacyProviderRejectsExcessWorkAndRecoversWhenTheQueueIsCanceled()=fixture {requests->
        val entered=CountDownLatch(1);val release=CountDownLatch(1);val calls=AtomicInteger()
        try {
            val running=launch(start=CoroutineStart.UNDISPATCHED) {requests.blocking {calls.incrementAndGet();entered.countDown();release.await();"first"}}
            assertTrue(entered.await(2,TimeUnit.SECONDS))
            val queued=launch(start=CoroutineStart.UNDISPATCHED) {requests.blocking {calls.incrementAndGet();"queued"}}
            val extra=async(start=CoroutineStart.UNDISPATCHED) {try {requests.blocking {calls.incrementAndGet();"excess"};null} catch(error:IOException) {error}}
            assertNotNull(withTimeout(500) {extra.await()});assertEquals(1,calls.get())
            queued.cancelAndJoin();running.cancelAndJoin()
            val next=async(start=CoroutineStart.UNDISPATCHED) {requests.blocking {calls.incrementAndGet();"next"}}
            release.countDown();assertEquals("next",next.await());assertEquals(2,calls.get())
        } finally {release.countDown()}
    }
    @Test fun canceledModernRequestsRetainTheirNativeSlotsUntilTheProviderReplies()=fixture {requests->
        val completions=mutableListOf<(String)->Unit>()
        val pending=(0 until 4).map {launch(start=CoroutineStart.UNDISPATCHED) {requests.callback<String> {complete,_->completions+=complete}}}
        pending.forEach {it.cancelAndJoin()}
        var invoked=false
        val error=try {requests.callback<String> {complete,_->invoked=true;complete("unexpected")};null} catch(error:IOException) {error}
        assertNotNull(error);assertFalse(invoked)
        completions.forEach {it("late result")}
        assertEquals("recovered",requests.callback<String> {complete,_->complete("recovered")})
    }
    @Test fun duplicateLateCompletionCannotReleaseAnotherRequestsSlot()=fixture {requests->
        val completions=mutableListOf<Pair<(String)->Unit,()->Unit>>()
        val pending=(0 until 4).map {launch(start=CoroutineStart.UNDISPATCHED) {requests.callback<String> {complete,fail->completions+=complete to fail}}}
        pending.forEach {it.cancelAndJoin()}
        completions[0].first("late");completions[0].second();completions[0].first("duplicate")
        val fifth=launch(start=CoroutineStart.UNDISPATCHED) {requests.callback<String> {complete,fail->completions+=complete to fail}}
        var invoked=false
        val error=try {requests.callback<String> {complete,_->invoked=true;complete("excess")};null} catch(error:IOException) {error}
        assertNotNull(error);assertFalse(invoked)
        fifth.cancelAndJoin();completions.drop(1).forEach {it.first("late")}
        assertEquals("recovered",requests.callback<String> {complete,_->complete("recovered")})
    }
    @Test fun synchronousProviderFailureDoesNotLeakModernAdmission()=fixture {requests->
        repeat(8) {
            val error=try {requests.callback<String> {_,_->throw IllegalStateException("Private provider detail")};null} catch(error:IOException) {error}
            assertNotNull(error)
            assertTrue(generateSequence<Throwable>(requireNotNull(error)) {it.cause}.take(8).all {it is IOException && it.message=="Geocoder unavailable"})
        }
        assertEquals("available",requests.callback<String> {complete,_->complete("available")})
    }
    @Test fun legacyFailureIsSanitizedAndTheWorkerCanServeAnotherRequest()=fixture {requests->
        val error=try {requests.blocking<String> {throw IllegalStateException("Private provider detail")};null} catch(error:IOException) {error}
        assertNotNull(error)
            assertTrue(generateSequence<Throwable>(requireNotNull(error)) {it.cause}.take(8).all {it is IOException && it.message=="Geocoder unavailable"})
        assertEquals("available",requests.blocking {"available"})
    }

}
