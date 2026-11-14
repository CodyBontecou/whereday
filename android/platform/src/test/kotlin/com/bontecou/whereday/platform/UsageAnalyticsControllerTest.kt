package com.bontecou.whereday.platform

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UsageAnalyticsControllerTest {
    private class Store:UsageAnalyticsStore {
        var value=UsageConsentState()
        override suspend fun read()=value
        override suspend fun write(value:UsageConsentState) {this.value=value}
    }
    private class Jobs:UsageAnalyticsJobs {
        var cancelled=0
        override suspend fun reconcile() {}
        override suspend fun cancelAll() {cancelled++}
    }
    private val event=UsageEvent(UsageEventName.stepViewed,UsageStep.features)
    private val metadata=UsageMetadata("0.1.0","1")
    private suspend fun ready(controller:UsageAnalyticsController) {withTimeout(2_000) {while(!controller.state.value.loaded) delay(5)}}
    @Test fun factoryIsNeverEvaluatedBeforeConsentAndDisableBlocksItImmediately():Unit=runBlocking {
        val store=Store();var built=0;var sent=0
        val core=UsageAnalyticsCore(store,{sent++;UsageSendResult.accepted}) {metadata}
        val controller=UsageAnalyticsController(core,Jobs())
        try {
            ready(controller);assertNull(controller.track {built++;event});assertEquals(0,built)
            controller.setEnabled(true)!!.join()
            controller.track {built++;event}!!.join();assertEquals(1,built);assertEquals(1,store.value.pending.size)
            assertEquals(UsageDrainResult.idle,controller.drain());assertEquals(1,sent)
            val off=controller.setEnabled(false)!!
            assertNull(controller.track {built++;event});off.join()
            assertNull(store.value.consentId);assertEquals(1,built)
        } finally {controller.close()}
    }
    @Test fun absentAndForeignActionPermitsCannotEvaluateACompletionFactory():Unit=runBlocking {
        fun controller()=UsageAnalyticsController(UsageAnalyticsCore(Store(),{UsageSendResult.accepted}) {metadata},Jobs())
        val first=controller();val second=controller()
        try {
            ready(first);ready(second)
            val before=first.capture();assertNull(before)
            first.setEnabled(true)!!.join();second.setEnabled(true)!!.join()
            assertNull(first.track(before) {error("Pre-consent callback ran")})
            assertNull(second.track(first.capture()) {error("Foreign callback ran")})
            assertEquals(0,first.state.value.pending);assertEquals(0,second.state.value.pending)
        } finally {first.close();second.close()}
    }
    @Test fun clearCancelsAndJoinsAnOlderApprovalBeforeItCanReviveSharing():Unit=runBlocking {supervisorScope {
        val backing=Store();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val store=object:UsageAnalyticsStore {
            override suspend fun read()=backing.value
            override suspend fun write(value:UsageConsentState) {
                if(value.consentId!=null) {entered.complete(Unit);withContext(NonCancellable) {release.await()}}
                backing.write(value)
            }
        }
        val core=UsageAnalyticsCore(store,{UsageSendResult.accepted}) {metadata};val jobs=Jobs();val controller=UsageAnalyticsController(core,jobs)
        try {
            ready(controller);val grant=controller.setEnabled(true)!!;entered.await()
            val clear=async(start=CoroutineStart.UNDISPATCHED) {controller.clearAll()}
            assertNull(controller.track {error("Factory ran during clear")})
            release.complete(Unit);clear.await();grant.join()
            assertFalse(controller.state.value.enabled);assertNull(backing.value.consentId);assertEquals(1,jobs.cancelled)
        } finally {release.complete(Unit);controller.close()}
    }}
    @Test fun optedOutDrainAndUnavailableApprovalNeverReachTheTransport():Unit=runBlocking {
        val controller=UsageAnalyticsController(UsageAnalyticsCore(Store(),null) {error("Unconfigured metadata collection")},Jobs())
        try {
            ready(controller);assertEquals(UsageDrainResult.disabled,controller.drain())
            controller.setEnabled(true)!!.join();assertEquals(UsageControlError.unavailable,controller.control.value.error)
            assertFalse(controller.state.value.enabled);assertNull(controller.track {error("Unconfigured factory")})
        } finally {controller.close()}
    }
    @Test fun workerDrainIsBoundedToTenReceiptsAndPreservesTheRemainder():Unit=runBlocking {
        var sent=0;val store=Store();val core=UsageAnalyticsCore(store,{sent++;UsageSendResult.accepted}) {metadata}
        val controller=UsageAnalyticsController(core,Jobs())
        try {
            ready(controller);controller.setEnabled(true)!!.join()
            repeat(11) {controller.track {event}!!.join()}
            assertEquals(UsageDrainResult.retry,controller.drain());assertEquals(10,sent);assertEquals(1,core.state.value.pending)
            assertEquals(UsageDrainResult.idle,controller.drain());assertEquals(11,sent)
        } finally {controller.close()}
    }
    @Test fun optOutCancelsAnActiveDrainAndClearsTheCommittedQueue():Unit=runBlocking {supervisorScope {
        val store=Store();val entered=CompletableDeferred<Unit>()
        val core=UsageAnalyticsCore(store,{entered.complete(Unit);awaitCancellation()}) {metadata}
        val controller=UsageAnalyticsController(core,Jobs())
        try {
            ready(controller);controller.setEnabled(true)!!.join();controller.track {event}!!.join()
            val drain=async {runCatching {controller.drain()}};entered.await()
            controller.setEnabled(false)!!.join()
            assertTrue(drain.await().exceptionOrNull() is CancellationException)
            assertNull(store.value.consentId);assertTrue(store.value.pending.isEmpty())
        } finally {controller.close()}
    }}
}
