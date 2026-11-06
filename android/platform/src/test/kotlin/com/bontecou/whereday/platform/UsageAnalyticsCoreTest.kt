package com.bontecou.whereday.platform

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test

class UsageAnalyticsCoreTest {
    private class Store:UsageAnalyticsStore {
        var value=UsageConsentState();var writes=0;var failRead=false;var failWrite=false
        override suspend fun read():UsageConsentState {check(!failRead);return value}
        override suspend fun write(value:UsageConsentState) {check(!failWrite);writes++;this.value=value}
    }
    private val event=UsageEvent(UsageEventName.stepViewed,UsageStep.features)
    private val metadata=UsageMetadata("0.1.0","1")
    @Test fun defaultOffDoesNotCreateIdentifiersMetadataOrQueuedEvents():Unit=runBlocking {
        val store=Store();var metadataReads=0;var sends=0
        val core=UsageAnalyticsCore(store,{sends++;UsageSendResult.accepted}) {metadataReads++;metadata}
        assertFalse(core.record(event,core.captureConsent()));core.load()
        repeat(20) {assertFalse(core.record(event,core.captureConsent()))}
        assertNull(core.sendNext());assertEquals(0,store.writes);assertEquals(0,metadataReads);assertEquals(0,sends)
        assertNull(store.value.consentId);assertTrue(store.value.pending.isEmpty())
    }
    @Test fun asynchronousCollectorsCannotReplayPreConsentOldConsentOrForeignOwnerEvents():Unit=runBlocking {
        val store=Store();var metadataReads=0
        val core=UsageAnalyticsCore(store,{UsageSendResult.accepted}) {metadataReads++;metadata};core.load()
        val before=core.captureConsent();assertNull(before)
        core.setConsent(true);assertFalse(core.record(event,before))
        val old=core.captureConsent();assertNotNull(old)
        core.setConsent(false);core.setConsent(true);assertFalse(core.record(event,old))
        val other=UsageAnalyticsCore(Store(),{UsageSendResult.accepted}) {metadata};other.load();other.setConsent(true)
        assertFalse(core.record(event,other.captureConsent()))
        assertEquals(0,metadataReads);assertTrue(store.value.pending.isEmpty())
        assertTrue(core.record(event,core.captureConsent()));assertEquals(1,metadataReads)
    }
    @Test fun consentQueuesOnlyNewEventsAndRetriesKeepExactBytesAcrossRestart():Unit=runBlocking {
        val store=Store();val sent=mutableListOf<String>()
        val first=UsageAnalyticsCore(store,{sent+=it.encoded();UsageSendResult.retry}) {metadata}
        first.load();assertFalse(first.record(event,first.captureConsent()));first.setConsent(true)
        val identity=store.value.consentId;assertNotNull(identity);assertTrue(store.value.pending.isEmpty())
        assertTrue(first.record(event,first.captureConsent()));assertEquals(UsageSendResult.retry,first.sendNext())
        store.value=UsageAnalyticsCodec.decode(UsageAnalyticsCodec.encode(store.value))
        val second=UsageAnalyticsCore(store,{sent+=it.encoded();UsageSendResult.accepted}) {error("Stored retries must not re-collect metadata")}
        second.load();assertEquals(UsageSendResult.accepted,second.sendNext())
        assertEquals(sent[0],sent[1]);assertEquals(identity,store.value.consentId);assertTrue(store.value.pending.isEmpty())
    }
    @Test fun boundedQueueAndIdempotentApprovalPreserveTheCurrentConsent():Unit=runBlocking {
        val store=Store();val core=UsageAnalyticsCore(store,{UsageSendResult.accepted}) {metadata}
        core.load();core.setConsent(true);val identity=store.value.consentId
        repeat(75) {assertTrue(core.record(event,core.captureConsent()))}
        val ids=store.value.pending.map {it.id};assertEquals(50,ids.size);assertEquals(50,ids.toSet().size)
        core.setConsent(true);assertEquals(identity,store.value.consentId);assertEquals(ids,store.value.pending.map {it.id})
        core.setConsent(false);assertNull(store.value.consentId);assertTrue(store.value.pending.isEmpty())
        core.setConsent(true);assertNotEquals(identity,store.value.consentId);assertTrue(store.value.pending.isEmpty())
    }
    @Test fun optOutCancelsAndJoinsTheOwnedRequestWithoutReplayingItsQueue():Unit=runBlocking {supervisorScope {
        val store=Store();val entered=CompletableDeferred<Unit>();val cancelled=CompletableDeferred<Unit>()
        val core=UsageAnalyticsCore(store,{entered.complete(Unit);try {awaitCancellation()} finally {cancelled.complete(Unit)}}) {metadata}
        core.load();core.setConsent(true);core.record(event,core.captureConsent())
        val send=async {runCatching {core.sendNext()}};entered.await()
        core.setConsent(false)
        assertTrue(cancelled.isCompleted);assertTrue(send.await().exceptionOrNull() is CancellationException)
        assertNull(store.value.consentId);assertTrue(store.value.pending.isEmpty());assertFalse(core.record(event,core.captureConsent()))
    }}
    @Test fun optOutIsDurableBeforeWaitingForUncooperativeTransportCancellation():Unit=runBlocking {supervisorScope {
        val store=Store();val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>()
        val core=UsageAnalyticsCore(store,{entered.complete(Unit);withContext(NonCancellable) {release.await()};UsageSendResult.accepted}) {metadata}
        core.load();core.setConsent(true);core.record(event,core.captureConsent())
        val send=async {runCatching {core.sendNext()}};entered.await()
        val disable=async {core.setConsent(false)}
        withTimeout(2_000) {while(store.value.consentId!=null) delay(5)}
        assertFalse(disable.isCompleted)
        val restarted=UsageAnalyticsCore(store,{fail("Restored opt-out sent data");UsageSendResult.accepted}) {error("Metadata collected after opt-out")}
        restarted.load();assertFalse(restarted.record(event,restarted.captureConsent()));assertNull(restarted.sendNext())
        release.complete(Unit);disable.await();send.await();assertTrue(store.value.pending.isEmpty())
    }}
    @Test fun laterOptOutFencesAnEarlierEnableWaitingForStorage():Unit=runBlocking {supervisorScope {
        val entered=CompletableDeferred<Unit>();val release=CompletableDeferred<Unit>();val backing=Store()
        val store=object:UsageAnalyticsStore {
            override suspend fun read():UsageConsentState {entered.complete(Unit);release.await();return backing.value}
            override suspend fun write(value:UsageConsentState)=backing.write(value)
        }
        val core=UsageAnalyticsCore(store,{UsageSendResult.accepted}) {error("No new consent was committed")}
        val load=async {core.load()};entered.await()
        val enable=async(start=CoroutineStart.UNDISPATCHED) {core.setConsent(true)}
        val disable=async(start=CoroutineStart.UNDISPATCHED) {core.setConsent(false)}
        release.complete(Unit);load.await();enable.await();disable.await()
        assertNull(backing.value.consentId);assertEquals(1,backing.writes);assertFalse(core.record(event,core.captureConsent()))
    }}
    @Test fun lateRestoredConsentCannotReenableCollectionDuringAnOptOutWrite():Unit=runBlocking {supervisorScope {
        val backing=Store();val old=UsageAnalyticsCore(backing,{UsageSendResult.accepted}) {metadata};old.load();old.setConsent(true)
        val reading=CompletableDeferred<Unit>();val releaseRead=CompletableDeferred<Unit>()
        val writing=CompletableDeferred<Unit>();val releaseWrite=CompletableDeferred<Unit>()
        val store=object:UsageAnalyticsStore {
            override suspend fun read():UsageConsentState {val snapshot=backing.value;reading.complete(Unit);releaseRead.await();return snapshot}
            override suspend fun write(value:UsageConsentState) {writing.complete(Unit);releaseWrite.await();backing.write(value)}
        }
        val core=UsageAnalyticsCore(store,{fail("Sent after opt-out");UsageSendResult.accepted}) {error("Collected after opt-out")}
        val load=async {core.load()};reading.await()
        val off=async(start=CoroutineStart.UNDISPATCHED) {core.setConsent(false)}
        try {
            releaseRead.complete(Unit);load.await();writing.await()
            assertFalse(withTimeout(2_000) {core.record(event,core.captureConsent())})
            assertNull(withTimeout(2_000) {core.sendNext()})
        } finally {releaseWrite.complete(Unit);off.await()}
        assertNull(backing.value.consentId)
    }}
    @Test fun storageFailureInhibitsCollectionUntilAnExplicitReset():Unit=runBlocking {
        val store=Store();val core=UsageAnalyticsCore(store,{UsageSendResult.accepted}) {metadata};core.load()
        store.failWrite=true
        try {core.setConsent(true);fail("Failed consent persisted")} catch(_:UsageAnalyticsSettingsUnavailable) {}
        assertFalse(core.record(event,core.captureConsent()));assertEquals(UsageAnalyticsError.storage,core.state.value.error)
        store.failWrite=false
        try {core.setConsent(true);fail("Reset was bypassed")} catch(_:UsageAnalyticsSettingsUnavailable) {}
        core.setConsent(false);core.setConsent(true);assertTrue(core.record(event,core.captureConsent()))
    }
    @Test fun corruptReadAndMissingTransportNeverEnableImplicitly():Unit=runBlocking {
        val store=Store();store.failRead=true
        val core=UsageAnalyticsCore(store,null) {error("No configured transport")};core.load()
        assertEquals(UsageAnalyticsError.storage,core.state.value.error);assertFalse(core.record(event,core.captureConsent()))
        core.setConsent(false);assertNull(core.state.value.error);assertFalse(core.state.value.available)
        try {core.setConsent(true);fail("Missing transport approved")} catch(_:UsageAnalyticsUnavailable) {}
        assertNull(store.value.consentId);assertEquals(1,store.writes)
    }
    @Test fun rejectedEventsAreRemovedButTransportFailuresRetainTheSameReceipt():Unit=runBlocking {
        val store=Store();var reject=false
        val core=UsageAnalyticsCore(store,{if(reject) UsageSendResult.rejected else throw java.io.IOException("Synthetic network fault")}) {metadata}
        core.load();core.setConsent(true);core.record(event,core.captureConsent());val id=store.value.pending.single().id
        assertEquals(UsageSendResult.retry,core.sendNext());assertEquals(id,store.value.pending.single().id)
        reject=true;assertEquals(UsageSendResult.rejected,core.sendNext());assertTrue(store.value.pending.isEmpty())
        assertEquals(UsageAnalyticsError.delivery,core.state.value.error)
    }
}
