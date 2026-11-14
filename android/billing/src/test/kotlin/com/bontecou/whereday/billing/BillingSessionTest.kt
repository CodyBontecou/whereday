package com.bontecou.whereday.billing

import com.google.gson.JsonObject
import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException
import java.security.*
import java.time.*
import java.util.Base64

class BillingSessionTest {
    private val key=KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
    private val clock=TestClock(Instant.parse("2026-10-27T12:00:00Z"))
    private val purchase=StorePurchase("synthetic-token","lifetime",StorePurchaseState.purchased)
    private fun lease(device:String="device-a",token:String=purchase.token,issued:Instant=clock.now,duration:Duration=Duration.ofDays(7),packageName:String="example.app"):String {
        val claims=JsonObject().apply {
            addProperty("aud",packageName);addProperty("sub",device);addProperty("product_id","lifetime");addProperty("jti","fixture-grant")
            addProperty("purchase_token_hash",sha256(token.toByteArray()));addProperty("iat",issued.epochSecond)
            addProperty("refresh_after",issued.plusSeconds(86400).epochSecond);addProperty("exp",issued.plus(duration).epochSecond)
        }
        fun encode(value:ByteArray)=Base64.getUrlEncoder().withoutPadding().encodeToString(value)
        val message=encode("{\"alg\":\"RS256\",\"typ\":\"JWT\"}".toByteArray())+"."+encode(claims.toString().toByteArray())
        val signature=Signature.getInstance("SHA256withRSA").apply { initSign(key.private);update(message.toByteArray()) }.sign()
        return "$message.${encode(signature)}"
    }
    private fun checker()=LeaseVerifier(Base64.getEncoder().encodeToString(key.public.encoded),"example.app","lifetime","device-a")
    private class Cache:GrantCache {
        var value:CachedGrant?=null
        override suspend fun load()=value
        override suspend fun save(value:CachedGrant) { this.value=value }
        override suspend fun clear() { value=null }
    }
    private fun session(cache:Cache,store:suspend ()->StoreSnapshot,verify:suspend (StorePurchase)->PurchaseVerification,configured:Boolean=true)=BillingSession("lifetime",configured,object:PurchaseStore { override suspend fun snapshot()=store() },object:PurchaseVerifier { override suspend fun verify(purchase:StorePurchase)=verify(purchase) },::checker,cache,clock)
    private fun owned(purchase:StorePurchase=this.purchase)=StoreSnapshot(LifetimeProduct("lifetime","localized fixture price"),listOf(purchase))
    private suspend fun denied(session:BillingSession) {
        try { session.requireAccess(Capability.fileExport);fail("Expected locked paid feature") } catch(expected:IllegalStateException) { }
    }

    @Test fun signedDeviceProductAndPackageBindingsRejectTamperingAndOverlongLeases() {
        assertEquals("fixture-grant",checker().verify(lease()).id)
        val valid=lease();val pieces=valid.split('.')
        val bad=listOf(lease(device="other-device"),lease(packageName="other.app"),lease(duration=Duration.ofDays(8)),pieces[0]+"."+Base64.getUrlEncoder().withoutPadding().encodeToString("{}".toByteArray())+"."+pieces[2])
        bad.forEach { value -> try { checker().verify(value);fail("Expected grant rejection") } catch(expected:Exception) { } }
    }
    @Test fun pendingAndUnconfiguredBuildsNeverUnlockOrAcknowledgePurchases()=runBlocking {
        val cache=Cache();var calls=0
        val pending=session(cache,{ owned(purchase.copy(state=StorePurchaseState.pending)) },{ calls++;PurchaseVerification.Active(lease()) })
        assertEquals(BillingRefreshResult.pending,pending.refresh());assertEquals(AccessState.pending,pending.state.value.access);assertEquals(BillingNotice.pendingPayment,pending.state.value.notice);assertEquals(0,calls);denied(pending)
        val disabled=session(cache,{ fail("Must not query Play before configuration");owned() },{ fail("Must not verify");PurchaseVerification.Revoked },false)
        assertEquals(BillingRefreshResult.unavailable,disabled.refresh());assertEquals(AccessState.unavailable,disabled.state.value.access);assertEquals(BillingNotice.unavailable,disabled.state.value.notice);denied(disabled);assertEquals(AccessState.unavailable,disabled.state.value.access);assertEquals(BillingNotice.unavailable,disabled.state.value.notice)
        listOf(Capability.recording,Capability.historyImport,Capability.preview).forEach { disabled.requireAccess(it) }
    }
    @Test fun storePurchasedStateRequiresServerSignatureBeforeGrantingAccess()=runBlocking {
        val cache=Cache()
        val unverified=session(cache,{ owned() },{ throw IOException("fixture offline") })
        assertEquals(BillingRefreshResult.failed,unverified.refresh());assertEquals(BillingNotice.verificationFailed,unverified.state.value.notice);denied(unverified);assertNull(cache.value)
        val verified=session(cache,{ owned() },{ PurchaseVerification.Active(lease()) })
        assertEquals(BillingRefreshResult.verified,verified.refresh());assertEquals(BillingNotice.verified,verified.state.value.notice);verified.requireAccess(Capability.fileExport);assertNotNull(cache.value)
    }
    @Test fun transientFailurePreservesVerifiedAccessUntilExpiryAndClockRollbackIsRejected()=runBlocking {
        val cache=Cache();cache.value=CachedGrant(lease(),clock.now)
        val offline=session(cache,{ throw IOException("fixture offline") },{ PurchaseVerification.Revoked })
        clock.now=clock.now.plusSeconds(2*86400)
        assertEquals(BillingRefreshResult.failed,offline.refresh());offline.requireAccess(Capability.webhook);assertEquals(AccessState.active,offline.state.value.access)
        clock.now=clock.now.minusSeconds(600);denied(offline)
        assertEquals(BillingNotice.verificationFailed,offline.state.value.notice)
        clock.now=clock.now.plusSeconds(8*86400);denied(offline)
    }
    @Test fun refundOrFreshEmptyPlayOwnershipClearsDurableCache()=runBlocking {
        val cache=Cache();cache.value=CachedGrant(lease(),clock.now)
        val revoked=session(cache,{ owned() },{ PurchaseVerification.Revoked })
        assertEquals(BillingRefreshResult.notFound,revoked.refresh());assertEquals(BillingNotice.notEligible,revoked.state.value.notice);assertNull(cache.value);denied(revoked)
        cache.value=CachedGrant(lease(),clock.now)
        val empty=session(cache,{ StoreSnapshot(null,emptyList()) },{ fail("Must not verify missing purchase");PurchaseVerification.Revoked })
        assertEquals(BillingRefreshResult.notFound,empty.refresh());assertNull(cache.value);denied(empty)
    }
    @Test fun accountOrPurchaseChangeCannotReuseUnrelatedCachedGrant()=runBlocking {
        val cache=Cache();cache.value=CachedGrant(lease(),clock.now)
        val changed=session(cache,{ owned(purchase.copy(token="other-token")) },{ throw IOException("fixture offline") })
        changed.refresh();assertNull(cache.value);denied(changed)
    }
    @Test fun freeFeaturesDoNotWaitForPurchaseVerificationNetworkWork()=runBlocking {
        val started=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        val session=session(Cache(),{ owned() },{ started.complete(Unit);finish.await();PurchaseVerification.Active(lease()) })
        val refresh=launch { session.refresh() };started.await()
        withTimeout(1000) { session.requireAccess(Capability.recording);session.requireAccess(Capability.preview) }
        finish.complete(Unit);refresh.join()
    }
    @Test fun verificationProtocolIsUnambiguousAndPurchaseTokensAreRedacted() {
        val text=verificationMessage("challenge","nonce","example.app","lifetime",sha256(purchase.token.toByteArray()),"key-hash").toString(Charsets.UTF_8)
        assertEquals(7,text.split('\n').size);assertFalse(text.contains(purchase.token));assertFalse(purchase.toString().contains(purchase.token))
        try { verificationMessage("bad\nchallenge","nonce","app","sku","hash","key");fail("Expected separator rejection") } catch(expected:IllegalArgumentException) { }
    }
    @Test fun coldScheduledAccessUsesValidCacheAndRenewsExpiredLeaseOnce()=runBlocking {
        val cache=Cache();cache.value=CachedGrant(lease(),clock.now);var queries=0;var checks=0
        val session=session(cache,{queries++;owned()},{checks++;PurchaseVerification.Active(lease())})
        session.requireAccessWithRefresh(Capability.scheduledExport);assertEquals(0,queries)
        clock.now=clock.now.plusSeconds(8*86400)
        awaitAll(async {session.requireAccessWithRefresh(Capability.scheduledExport)},async {session.requireAccessWithRefresh(Capability.fileExport)})
        assertEquals(1,queries);assertEquals(1,checks);assertEquals(AccessState.active,session.state.value.access)
        assertEquals(clock.now,cache.value!!.lastSeen)
    }
    @Test fun failedRenewalCannotExtendExpiredAccessAndFreeFeaturesRemainAvailable()=runBlocking {
        val cache=Cache();cache.value=CachedGrant(lease(),clock.now);clock.now=clock.now.plusSeconds(8*86400)
        val started=CompletableDeferred<Unit>();val finish=CompletableDeferred<Unit>()
        val session=session(cache,{started.complete(Unit);finish.await();throw IOException("offline fixture")},{PurchaseVerification.Revoked})
        val refresh=async {try {session.requireAccessWithRefresh(Capability.scheduledExport);false} catch(_:IllegalStateException) {true}}
        started.await()
        withTimeout(1000) {session.requireAccessWithRefresh(Capability.recording);session.requireAccessWithRefresh(Capability.preview)}
        finish.complete(Unit);assertTrue(refresh.await());denied(session)
    }
    @Test fun backgroundRenewalHonorsRevocationAndUnconfiguredBuildsNeverQueryStore()=runBlocking {
        val cache=Cache();cache.value=CachedGrant(lease(),clock.now);clock.now=clock.now.plusSeconds(8*86400)
        val revoked=session(cache,{owned()},{PurchaseVerification.Revoked})
        try {revoked.requireAccessWithRefresh(Capability.scheduledExport);fail()} catch(_:IllegalStateException) {}
        assertNull(cache.value)
        val disabled=session(Cache(),{fail("Unconfigured builds must not query Play");owned()},{PurchaseVerification.Revoked},false)
        try {disabled.requireAccessWithRefresh(Capability.scheduledExport);fail()} catch(_:IllegalStateException) {}
    }
    @Test fun explicitEmptyRestoreHasFeedbackWithoutChangingTheFreePolicy()=runBlocking {
        val current=session(Cache(),{StoreSnapshot(null,emptyList())},{fail("No purchase to verify");PurchaseVerification.Revoked})
        assertEquals(BillingRefreshResult.notFound,current.refresh());assertNull(current.state.value.notice)
        assertEquals(BillingRefreshResult.notFound,current.refresh(userRestore=true));assertEquals(BillingNotice.notFound,current.state.value.notice)
        assertEquals(AccessState.free,current.state.value.access)
        current.requireAccess(Capability.recording);current.requireAccess(Capability.preview);denied(current)
    }
    @Test fun presentationNoticesCannotGrantOrRevokeVerifiedAccess()=runBlocking {
        val current=session(Cache(),{owned()},{PurchaseVerification.Active(lease())})
        current.notice(BillingNotice.verified);denied(current)
        assertEquals(BillingRefreshResult.verified,current.refresh())
        current.notice(BillingNotice.canceled);current.requireAccess(Capability.fileExport)
        assertEquals(AccessState.active,current.state.value.access);assertEquals("Purchase canceled.",current.state.value.message)
    }
    private class TestClock(var now:Instant):Clock() { override fun instant()=now;override fun getZone():ZoneId=ZoneOffset.UTC;override fun withZone(zone:ZoneId):Clock=this }
}
