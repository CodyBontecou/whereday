package com.bontecou.whereday.domain

import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class RoadDisplayServiceTest {
    private val allowed=RoadDisplayAccess(true,true,true,true,true,"journal-a")
    private fun source(offset:Double=0.0)=listOf(
        TrackPoint("a-$offset",Coordinate(0.0,offset),Instant.EPOCH,5.0),
        TrackPoint("b-$offset",Coordinate(0.0,offset+.01),Instant.EPOCH.plusSeconds(600),5.0))
    private fun result(a:Coordinate,b:Coordinate)=RoadDirectionsResult(a.distanceTo(b)*1.3,listOf(a,b))
    private fun service(provider:RoadDirections)=RoadDisplayService(provider,minimumRequestIntervalMillis=0).also {it.updateAccess(allowed)}
    private suspend fun denied(service:RoadDisplayService) {
        try {service.prepare(source());fail("Admission was required")} catch(_:RoadDisplayUnavailable) {}
    }
    @Test fun capturedPermitCannotCrossPolicyRevisionOrServiceBoundary()=runBlocking {
        var calls=0
        val service=service(RoadDirections {a,b,_->calls++;result(a,b)})
        val permit=requireNotNull(service.capturePermit())
        service.updateAccess(allowed.copy(policyRevision="new"))
        try {service.prepare(source(),permit=permit);fail("Old permit accepted")} catch(_:CancellationException) {}
        val other=service(RoadDirections {a,b,_->calls++;result(a,b)})
        try {other.prepare(source(),permit=service.capturePermit());fail("Foreign permit accepted")} catch(_:CancellationException) {}
        assertEquals(0,calls)
        assertTrue(service.prepare(source(),permit=service.capturePermit()).route.hasSnappedSegments)
        service.clearAndJoin();assertNull(service.capturePermit())
    }
    @Test fun defaultsAndEveryAdmissionGateDenyWithoutProviderCalls()=runBlocking {
        var calls=0;val service=RoadDisplayService(RoadDirections {a,b,_->calls++;result(a,b)},minimumRequestIntervalMillis=0)
        denied(service)
        for(access in listOf(allowed.copy(configured=false),allowed.copy(enabled=false),allowed.copy(networkAllowed=false),allowed.copy(foreground=false),allowed.copy(journalReady=false))) {
            service.updateAccess(access);denied(service)
        }
        assertEquals(0,calls);service.updateAccess(allowed);assertTrue(service.prepare(source()).route.hasSnappedSegments);assertEquals(1,calls)
    }
    @Test fun idempotentAccessKeepsCacheAndPolicyChangesInvalidateCompletedPlans()=runBlocking {
        var calls=0;val service=service(RoadDirections {a,b,_->calls++;result(a,b)})
        val first=service.prepare(source());val fingerprint=RoadSnapping.fingerprint(source())
        assertFalse(first.fromCache);assertTrue(service.isCurrent(first,fingerprint))
        service.updateAccess(allowed);val second=service.prepare(source());assertTrue(second.fromCache);assertEquals(1,calls)
        assertFalse(service.isCurrent(second,RoadSnapping.fingerprint(source(.1))))
        service.updateAccess(allowed.copy(foreground=false));service.updateAccess(allowed)
        assertFalse(service.isCurrent(first,fingerprint));assertFalse(service.prepare(source()).fromCache);assertEquals(2,calls)
        val other=service(RoadDirections {a,b,_->result(a,b)})
        assertFalse(other.isCurrent(second,fingerprint))
    }
    @Test fun cacheSeparatesAllConsumedSourceFieldsAndProtectsItsOwnGeometry()=runBlocking {
        var calls=0;val service=service(RoadDirections {a,b,_->calls++;result(a,b)})
        val source=source();val first=service.prepare(source)
        (first.route.segments.single().coordinates as MutableList<Coordinate>).clear()
        val second=service.prepare(source);assertTrue(second.fromCache);assertEquals(2,second.route.segments.single().coordinates.size)
        for(point in listOf(source[0].copy(id="changed"),source[0].copy(accuracy=6.0),source[0].copy(time=Instant.EPOCH.plusNanos(1)),source[0].copy(outlier=true),source[0].copy(coordinate=Coordinate(.000001,0.0)))) {
            assertFalse(service.prepare(listOf(point,source[1])).fromCache)
        }
        assertEquals(6,calls)
    }
    @Test fun cacheExpiryClockRollbackAndExplicitRefreshRequestAgain()=runBlocking {
        val clock=AtomicLong(1000);var calls=0
        val service=RoadDisplayService(RoadDirections {a,b,_->calls++;result(a,b)},elapsedMillis=clock::get,cacheLifetimeMillis=100,minimumRequestIntervalMillis=0).also {it.updateAccess(allowed)}
        service.prepare(source());clock.set(1099);assertTrue(service.prepare(source()).fromCache)
        clock.set(1100);assertFalse(service.prepare(source()).fromCache)
        clock.set(100);assertFalse(service.prepare(source()).fromCache)
        assertFalse(service.prepare(source(),refresh=true).fromCache);assertEquals(4,calls)
    }
    @Test fun retainedCoordinateBudgetEvictsLeastRecentlyUsedRoutes()=runBlocking {
        var calls=0
        val service=RoadDisplayService(RoadDirections {a,b,_->calls++;result(a,b)},maximumCacheEntries=8,maximumCacheCoordinates=4,minimumRequestIntervalMillis=0).also {it.updateAccess(allowed)}
        service.prepare(source());service.prepare(source(.1));assertTrue(service.prepare(source()).fromCache)
        service.prepare(source(.2));assertTrue(service.prepare(source()).fromCache)
        assertFalse(service.prepare(source(.1)).fromCache);assertEquals(4,calls)
    }
    @Test fun entryBudgetAndDisabledCacheAreIndependentOfCoordinateBudget()=runBlocking {
        for(entries in listOf(0,1)) {
            var calls=0
            val service=RoadDisplayService(RoadDirections {a,b,_->calls++;result(a,b)},maximumCacheEntries=entries,maximumCacheCoordinates=100,minimumRequestIntervalMillis=0).also {it.updateAccess(allowed)}
            service.prepare(source());service.prepare(source(.1));assertFalse(service.prepare(source()).fromCache);assertEquals(3,calls)
        }
    }
    @Test(timeout=5000) fun rapidOptOutAndReenableRejectLateNonCooperativeResultAndKeepCallerAlive()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val calls=AtomicInteger()
        val service=service(RoadDirections {a,b,_->if(calls.incrementAndGet()==1) withContext(NonCancellable) {entered.complete(Unit);release.await()};result(a,b)})
        val old=async {service.prepare(source())}
        try {
            entered.await();service.updateAccess(allowed.copy(networkAllowed=false));service.updateAccess(allowed)
            val fresh=async(start=CoroutineStart.UNDISPATCHED) {service.prepare(source())}
            assertEquals(1,calls.get());release.complete(Unit);old.join();assertTrue(old.isCancelled)
            assertFalse(fresh.await().fromCache);assertEquals(2,calls.get());assertTrue(currentCoroutineContext().isActive)
        } finally {release.complete(Unit);service.clearAndJoin()}
    }
    @Test(timeout=5000) fun journalGenerationInvalidatesQueuedRequestsBeforeTheirProviderDispatch()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val calls=AtomicInteger()
        val starts=java.util.Collections.synchronizedList(mutableListOf<Coordinate>())
        val service=service(RoadDirections {a,b,_->starts+=a;if(calls.incrementAndGet()==1) withContext(NonCancellable) {entered.complete(Unit);release.await()};result(a,b)})
        val old=async {service.prepare(source())}
        try {
            entered.await();val queued=async(start=CoroutineStart.UNDISPATCHED) {service.prepare(source(.1))}
            service.updateAccess(allowed.copy(journalGeneration="journal-b"))
            val fresh=async(start=CoroutineStart.UNDISPATCHED) {service.prepare(source(.2))}
            release.complete(Unit);old.join();queued.join();assertTrue(old.isCancelled);assertTrue(queued.isCancelled)
            val plan=fresh.await();assertEquals(2,calls.get());assertEquals(listOf(Coordinate(0.0,0.0),Coordinate(0.0,.2)),starts.toList())
            assertTrue(service.isCurrent(plan,RoadSnapping.fingerprint(source(.2))))
        } finally {release.complete(Unit);service.clearAndJoin()}
    }
    @Test(timeout=5000) fun clearFencesBeforeJoiningAndRequiresFreshAdmission()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val calls=AtomicInteger()
        val service=service(RoadDirections {a,b,_->if(calls.incrementAndGet()==1) withContext(NonCancellable) {entered.complete(Unit);release.await()};result(a,b)})
        val pending=async {service.prepare(source())}
        try {
            entered.await();val clear=async(start=CoroutineStart.UNDISPATCHED) {service.clearAndJoin()}
            assertFalse(clear.isCompleted);denied(service);assertEquals(1,calls.get())
            release.complete(Unit);clear.await();pending.join();assertTrue(pending.isCancelled);denied(service)
            service.updateAccess(allowed.copy(journalGeneration="journal-b"));assertFalse(service.prepare(source()).fromCache);assertEquals(2,calls.get())
        } finally {release.complete(Unit);service.clearAndJoin()}
    }
    @Test(timeout=5000) fun cancelingOneCallerDoesNotCancelAnotherQueuedCaller()=runBlocking {
        val entered=CompletableDeferred<Unit>();val calls=AtomicInteger()
        val service=service(RoadDirections {a,b,_->if(calls.incrementAndGet()==1) {entered.complete(Unit);awaitCancellation()};result(a,b)})
        val first=async {service.prepare(source())};entered.await()
        val second=async(start=CoroutineStart.UNDISPATCHED) {service.prepare(source(.1))}
        first.cancelAndJoin();assertTrue(second.await().route.hasSnappedSegments);assertEquals(2,calls.get());assertTrue(currentCoroutineContext().isActive)
    }
    @Test(timeout=5000) fun attemptTimeoutUsesAlternateTransportButTotalTimeoutCannotBecomeFallback()=runBlocking {
        var calls=0
        val alternate=RoadDisplayService(RoadDirections {a,b,_->calls++;if(calls==1) awaitCancellation();result(a,b)},attemptTimeoutMillis=40,totalTimeoutMillis=2000,minimumRequestIntervalMillis=0).also {it.updateAccess(allowed)}
        val plan=alternate.prepare(source());assertTrue(plan.route.hasSnappedSegments);assertEquals(2,calls)
        assertEquals(setOf(RoadDisplayWarning.attemptTimedOut),plan.warnings)
        calls=0
        val total=RoadDisplayService(RoadDirections {a,b,_->calls++;if(calls==1) awaitCancellation();result(a,b)},attemptTimeoutMillis=2000,totalTimeoutMillis=60,minimumRequestIntervalMillis=0).also {it.updateAccess(allowed)}
        try {total.prepare(source());fail("Total deadline was required")} catch(_:TimeoutCancellationException) {}
        assertEquals(1,calls);assertTrue(currentCoroutineContext().isActive)
        assertTrue(total.prepare(source()).route.hasSnappedSegments);assertEquals(2,calls)
    }
    @Test fun oversizedOrExhaustedGeometryStopsFurtherRequestsAndPreservesRawFallback()=runBlocking {
        var calls=0
        val oversized=RoadDisplayService(RoadDirections {a,b,_->calls++;RoadDirectionsResult(1500.0,listOf(a,a,b))},maximumResponseCoordinates=2,maximumPreparedCoordinates=4,minimumRequestIntervalMillis=0).also {it.updateAccess(allowed)}
        val a=oversized.prepare(source());assertFalse(a.route.hasSnappedSegments);assertEquals(1,calls);assertEquals(setOf(RoadDisplayWarning.geometryLimit),a.warnings)
        assertEquals(source().map {it.coordinate},a.route.segments.single().coordinates)
        calls=0
        val exhausted=RoadDisplayService(RoadDirections {start,end,_->calls++;result(start,end)},maximumResponseCoordinates=2,maximumPreparedCoordinates=4,maximumCacheCoordinates=4,minimumRequestIntervalMillis=0).also {it.updateAccess(allowed)}
        val points=(0..3).map {TrackPoint("p$it",Coordinate(0.0,it*.01),Instant.EPOCH.plusSeconds(it*600L),5.0)}
        val b=exhausted.prepare(points);assertEquals(2,calls);assertEquals(3,b.route.segments.size);assertFalse(b.route.segments.last().snapped)
        assertEquals(setOf(RoadDisplayWarning.geometryLimit),b.warnings)
        assertFalse(exhausted.prepare(points).fromCache);assertEquals(4,calls)
    }
    @Test fun unusableGeometryAndProviderErrorsBecomeTypedWarningsWithoutExceptionText()=runBlocking {
        val bad=listOf(RoadDirectionsResult(Double.NaN,source().map {it.coordinate}),RoadDirectionsResult(1500.0,emptyList()),RoadDirectionsResult(1_000_000.0,source().map {it.coordinate}))
        for(response in bad) {
            var calls=0;val service=service(RoadDirections {a,b,_->calls++;if(calls==1) response else result(a,b)})
            val plan=service.prepare(source());assertEquals(2,calls);assertTrue(plan.route.hasSnappedSegments);assertEquals(setOf(RoadDisplayWarning.unusableGeometry),plan.warnings)
        }
        val failed=service(RoadDirections {_,_,_->throw IllegalStateException("credential=sensitive example")}).prepare(source())
        assertFalse(failed.route.hasSnappedSegments);assertEquals(setOf(RoadDisplayWarning.providerUnavailable),failed.warnings)
    }
    @Test(timeout=5000) fun requestsAreSerialAndSpacingAppliesAcrossDifferentSources()=runBlocking {
        val timestamps=java.util.Collections.synchronizedList(mutableListOf<Long>())
        val service=RoadDisplayService(RoadDirections {a,b,_->timestamps+=System.nanoTime();result(a,b)},minimumRequestIntervalMillis=40).also {it.updateAccess(allowed)}
        val first=async {service.prepare(source())};val second=async {service.prepare(source(.1))}
        first.await();second.await();assertEquals(2,timestamps.size);assertTrue(timestamps[1]-timestamps[0]>=39_000_000)
    }
    @Test(timeout=5000) fun accessUpdatesDuringClearCannotReopenAdmission()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val calls=AtomicInteger()
        val service=service(RoadDirections {a,b,_->if(calls.incrementAndGet()==1) withContext(NonCancellable) {entered.complete(Unit);release.await()};result(a,b)})
        val old=async {service.prepare(source())}
        try {
            entered.await();val clear=async(start=CoroutineStart.UNDISPATCHED) {service.clearAndJoin()}
            service.updateAccess(allowed)
            val during=async(start=CoroutineStart.UNDISPATCHED) {
                try {service.prepare(source(.1));true} catch(_:RoadDisplayUnavailable) {false}
            }
            release.complete(Unit);clear.await();old.join()
            assertFalse("Access must stay fenced until clear completes",during.await())
            assertEquals(1,calls.get());denied(service)
        } finally {release.complete(Unit);service.clearAndJoin()}
    }
    @Test(timeout=5000) fun clearStillJoinsProviderAfterTheOriginalCallerWasCanceled()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val service=RoadDisplayService(RoadDirections {a,b,_->withContext(NonCancellable) {entered.complete(Unit);release.await()};result(a,b)},dispatcher=Dispatchers.Unconfined,minimumRequestIntervalMillis=0).also {it.updateAccess(allowed)}
        val caller=async(start=CoroutineStart.UNDISPATCHED) {service.prepare(source())}
        try {
            entered.await();caller.cancel();yield()
            val clear=async(start=CoroutineStart.UNDISPATCHED) {service.clearAndJoin()}
            val returnedWhileProviderWasStillRunning=clear.isCompleted
            release.complete(Unit);caller.join();clear.await()
            assertFalse("A canceled caller must not unregister its still-running provider",returnedWhileProviderWasStillRunning)
        } finally {release.complete(Unit);service.clearAndJoin()}
    }
    @Test(timeout=5000) fun overlappingClearsKeepTheirFenceWhenOneMaintenanceCallerIsCanceled()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val calls=AtomicInteger()
        val service=service(RoadDirections {a,b,_->if(calls.incrementAndGet()==1) withContext(NonCancellable) {entered.complete(Unit);release.await()};result(a,b)})
        val pending=async {service.prepare(source())}
        try {
            entered.await();val first=async(start=CoroutineStart.UNDISPATCHED) {service.clearAndJoin()}
            val second=async(start=CoroutineStart.UNDISPATCHED) {service.clearAndJoin()}
            first.cancel();assertFalse(first.isCompleted)
            service.updateAccess(allowed);denied(service)
            release.complete(Unit);first.join();second.await();pending.join()
            assertTrue(first.isCancelled);denied(service);assertEquals(1,calls.get())
            service.updateAccess(allowed.copy(journalGeneration="journal-b"));assertTrue(service.prepare(source()).route.hasSnappedSegments)
            assertEquals(2,calls.get())
        } finally {release.complete(Unit);service.clearAndJoin()}
    }
    @Test(timeout=5000) fun providerCannotDeadlockMaintenanceByJoiningItsOwnRequest()=runBlocking {
        lateinit var service:RoadDisplayService
        var rejected=false
        service=service(RoadDirections {a,b,_->
            withContext(Dispatchers.IO) {
                try {service.clearAndJoin();fail("Provider must not join its own request")}
                catch(_:IllegalStateException) {rejected=true}
            }
            result(a,b)
        })
        assertTrue(service.prepare(source()).route.hasSnappedSegments);assertTrue(rejected)
        service.clearAndJoin();denied(service)
    }
}
