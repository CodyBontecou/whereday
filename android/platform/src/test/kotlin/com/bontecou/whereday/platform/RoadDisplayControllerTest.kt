package com.bontecou.whereday.platform

import com.bontecou.whereday.data.AppSettings
import com.bontecou.whereday.domain.*
import java.time.Instant
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import org.junit.Assert.*
import org.junit.Test

class RoadDisplayControllerTest {
    private fun points(offset:Double=0.0)=listOf(
        TrackPoint("a",Coordinate(0.0,offset),Instant.EPOCH,5.0),
        TrackPoint("b",Coordinate(0.0,offset+.01),Instant.EPOCH.plusSeconds(600),5.0))
    private fun result(a:Coordinate,b:Coordinate)=RoadDirectionsResult(a.distanceTo(b)*1.3,listOf(a,b))
    private class Fixture(provider:RoadDirections?,initial:AppSettings=AppSettings(roadDisplayEnabled=true,roadAccessRevision="a")) {
        val settings=MutableStateFlow(initial)
        @Volatile var persisted=initial
        val journal=MutableStateFlow(JournalClearState(generation="journal-a"))
        var failReset=false
        val controller=RoadDisplayController(provider,settings,{persisted},{
            if(failReset) error("disk write failed")
            persisted=persisted.copy(roadDisplayEnabled=false,roadAccessRevision="reset")
            settings.value=persisted
        },journal,minimumRequestIntervalMillis=0)
        suspend fun ready() {withTimeout(2000) {controller.state.first {!it.loading}}}
        fun publish(value:AppSettings) {persisted=value;settings.value=value}
    }
    private suspend fun unavailable(session:RoadDisplayController.Session) {
        try {session.prepare(points());fail("Request admitted")} catch(_:RoadDisplayUnavailable) {}
    }
    @Test(timeout=5000) fun missingProviderAndDefaultConsentCannotDispatch()=runBlocking {
        for(configured in listOf(false,true)) {
            var calls=0
            val fixture=Fixture(if(configured) RoadDirections {a,b,_->calls++;result(a,b)} else null,AppSettings())
            try {fixture.ready();val session=fixture.controller.createSession();session.foreground();unavailable(session);assertEquals(0,calls)}
            finally {fixture.controller.close()}
        }
    }
    @Test(timeout=5000) fun activitiesOwnIndependentForegroundAndCacheLifetimes()=runBlocking {
        val calls=AtomicInteger();val fixture=Fixture(RoadDirections {a,b,_->calls.incrementAndGet();result(a,b)})
        try {
            fixture.ready();val old=fixture.controller.createSession();val fresh=fixture.controller.createSession()
            old.foreground();fresh.foreground();val plan=fresh.prepare(points())
            old.background();old.close()
            assertEquals(1,fixture.controller.state.value.foregroundSessions)
            assertTrue(fresh.isCurrent(plan,plan.route.fingerprint));assertTrue(fresh.prepare(points()).fromCache)
            fresh.background();assertFalse(fresh.isCurrent(plan,plan.route.fingerprint));unavailable(fresh)
            fresh.foreground();assertFalse(fresh.prepare(points()).fromCache);assertEquals(2,calls.get())
        } finally {fixture.controller.close()}
    }
    @Test(timeout=5000) fun precommitRevisionBlocksOldFlowAndCancelsNonCooperativeResult()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val calls=AtomicInteger()
        val fixture=Fixture(RoadDirections {a,b,_->if(calls.incrementAndGet()==1) withContext(NonCancellable) {entered.complete(Unit);release.await()};result(a,b)})
        try {
            fixture.ready();val session=fixture.controller.createSession();session.foreground()
            val old=async {session.prepare(points())};entered.await()
            fixture.controller.invalidatePolicy("c")
            unavailable(session)
            // The collector may conflate the off transition. The precommit fence must still reject the old result.
            fixture.publish(fixture.persisted.copy(roadAccessRevision="c"))
            withTimeout(2000) {fixture.controller.state.first {!it.policyUnavailable}}
            release.complete(Unit);old.join();assertTrue(old.isCancelled)
            assertFalse(session.prepare(points()).fromCache);assertEquals(2,calls.get())
        } finally {release.complete(Unit);fixture.controller.close()}
    }
    @Test(timeout=5000) fun queuedDispatchRereadsPersistedPolicyEvenBeforeCollectorObservesIt()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val calls=AtomicInteger()
        val fixture=Fixture(RoadDirections {a,b,_->calls.incrementAndGet();withContext(NonCancellable) {entered.complete(Unit);release.await()};result(a,b)})
        try {
            fixture.ready();val first=fixture.controller.createSession();val second=fixture.controller.createSession()
            first.foreground();second.foreground()
            val active=async {first.prepare(points())};entered.await()
            val queued=async(start=CoroutineStart.UNDISPATCHED) {second.prepare(points(.1))}
            fixture.persisted=fixture.persisted.copy(networkGeocoding=false,roadAccessRevision="b")
            release.complete(Unit);active.join();queued.join();assertTrue(active.isCancelled);assertTrue(queued.isCancelled);assertEquals(1,calls.get())
        } finally {release.complete(Unit);fixture.controller.close()}
    }
    @Test(timeout=5000) fun clearJoinsClosedSessionWorkerBeforeResettingConsent()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val fixture=Fixture(RoadDirections {a,b,_->withContext(NonCancellable) {entered.complete(Unit);release.await()};result(a,b)})
        try {
            fixture.ready();val session=fixture.controller.createSession();session.foreground()
            val pending=async {session.prepare(points())};entered.await();session.close()
            val clear=async(start=CoroutineStart.UNDISPATCHED) {fixture.controller.clearAndJoin()}
            assertFalse(clear.isCompleted);assertTrue(fixture.persisted.roadDisplayEnabled)
            val new=fixture.controller.createSession();new.foreground();unavailable(new)
            release.complete(Unit);clear.await();pending.join();assertTrue(pending.isCancelled)
            assertFalse(fixture.persisted.roadDisplayEnabled);unavailable(new)
        } finally {release.complete(Unit);fixture.controller.close()}
    }
    @Test(timeout=5000) fun journalGenerationChangeImmediatelyInvalidatesCachedPlan()=runBlocking {
        val fixture=Fixture(RoadDirections {a,b,_->result(a,b)})
        try {
            fixture.ready();val session=fixture.controller.createSession();session.foreground();val plan=session.prepare(points())
            fixture.journal.value=JournalClearState(generation="journal-b")
            assertFalse(session.isCurrent(plan,plan.route.fingerprint))
            fixture.journal.value=JournalClearState(JournalClearPhase.clearing,generation="journal-b")
            unavailable(session)
        } finally {fixture.controller.close()}
    }
    @Test(timeout=5000) fun failedConsentResetLeavesExistingAndNewSessionsBlocked()=runBlocking {
        val fixture=Fixture(RoadDirections {a,b,_->result(a,b)})
        try {
            fixture.ready();val session=fixture.controller.createSession();session.foreground();session.prepare(points())
            fixture.failReset=true
            try {fixture.controller.clearAndJoin();fail("Reset failure swallowed")} catch(_:IllegalStateException) {}
            unavailable(session);val fresh=fixture.controller.createSession();fresh.foreground();unavailable(fresh)
            fixture.publish(fixture.persisted.copy(metric=false))
            fixture.journal.value=JournalClearState(generation="unrelated-generation")
            yield();unavailable(session);unavailable(fresh)
        } finally {fixture.failReset=false;fixture.controller.close()}
    }
    @Test(timeout=5000) fun backgroundingAndReturningCannotAdoptAnOldProviderResult()=runBlocking {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val calls=AtomicInteger()
        val fixture=Fixture(RoadDirections {a,b,_->if(calls.incrementAndGet()==1) withContext(NonCancellable) {entered.complete(Unit);release.await()};result(a,b)})
        try {
            fixture.ready();val session=fixture.controller.createSession();session.foreground()
            val old=async {session.prepare(points())};entered.await();session.background();session.foreground()
            release.complete(Unit);old.join();assertTrue(old.isCancelled)
            assertFalse(session.prepare(points()).fromCache);assertEquals(2,calls.get())
        } finally {release.complete(Unit);fixture.controller.close()}
    }
    @Test(timeout=5000) fun providerPacingIsSharedAcrossActivities()=runBlocking {
        val starts=java.util.Collections.synchronizedList(mutableListOf<Long>())
        val settings=MutableStateFlow(AppSettings(roadDisplayEnabled=true))
        val controller=RoadDisplayController(RoadDirections {a,b,_->starts+=System.nanoTime();result(a,b)},settings,{settings.value},
            {settings.value=settings.value.copy(roadDisplayEnabled=false)},MutableStateFlow(JournalClearState()),minimumRequestIntervalMillis=120)
        try {
            withTimeout(2000) {controller.state.first {!it.loading}}
            val first=controller.createSession();val second=controller.createSession();first.foreground();second.foreground()
            awaitAll(async {first.prepare(points())},async {second.prepare(points(.1))})
            assertEquals(2,starts.size);assertTrue((starts[1]-starts[0])/1_000_000>=100)
        } finally {controller.close()}
    }

}
