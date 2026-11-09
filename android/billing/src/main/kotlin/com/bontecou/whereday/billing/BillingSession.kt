package com.bontecou.whereday.billing

import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.*

enum class StorePlan { weekly, yearly, lifetime }
/** One purchasable Play offer: a subscription base plan (with its eligible free trial, if any) or the one-time Lifetime unlock. */
data class StoreProduct(val id: String,val plan: StorePlan,val formattedPrice: String,val priceMicros: Long=0,val currencyCode: String="",val freeTrialDays: Int=0) {
    val isSubscription get()=plan!=StorePlan.lifetime
}
enum class StorePurchaseState { purchased, pending }
data class StorePurchase(val token: String,val productId: String,val state: StorePurchaseState,val autoRenewing: Boolean=false) {
    override fun toString()="StorePurchase(productId=$productId, state=$state, token=<redacted>)"
}
data class StoreSnapshot(val products: List<StoreProduct>,val purchases: List<StorePurchase>)
interface PurchaseStore { suspend fun snapshot(): StoreSnapshot }
sealed interface PurchaseVerification {
    data class Active(val signedLease: String):PurchaseVerification
    data object Pending:PurchaseVerification
    data object Revoked:PurchaseVerification
}
interface PurchaseVerifier { suspend fun verify(purchase: StorePurchase): PurchaseVerification }
class VerifiedAccessRequired:IllegalStateException("Verified Whereday access is required.")
enum class BillingRefreshResult {verified,pending,notFound,unavailable,failed}
enum class AccessState { checking, free, pending, active, verificationRequired, unavailable }
enum class BillingNotice(val diagnostic:String) {
    unavailable("Purchases are not available in this build. Imports and export previews remain free."),
    pendingPayment("Your purchase is pending in Google Play. Access starts after payment is completed and verified."),
    verified("Whereday access is verified."),
    pendingVerification("Your purchase is pending verification."),
    notEligible("Google Play no longer reports an eligible purchase."),
    cached("Using previously verified access. Connect to refresh purchase status."),
    verificationFailed("Purchase status could not be verified. Check your connection and try Restore purchases."),
    canceled("Purchase canceled."),
    storeUnavailable("Google Play could not open the purchase. Try again."),
    notFound("No eligible Whereday subscription or Lifetime purchase was found for this Google Play account.")
}
data class BillingState(val access: AccessState=AccessState.checking,val products: List<StoreProduct> = emptyList(),val busy: Boolean=false,val notice:BillingNotice?=null,
    val activeProductId:String?=null,val autoRenewing:Boolean=false,val enforced:Boolean=true) {
    val message:String? get()=notice?.diagnostic
    fun product(plan:StorePlan)=products.firstOrNull { it.plan==plan }
    /** Builds without Play billing keep recording free; only configured builds put tracking behind access. */
    val trackingLocked get()=enforced && access!=AccessState.active
}

/** [productIds] lists every product that grants access, most durable first: an owned Lifetime is verified ahead of a subscription. */
class BillingSession(private val productIds: List<String>,private val configured: Boolean,private val store: PurchaseStore,private val verifier: PurchaseVerifier,private val leaseVerifier: (() -> LeaseVerifier),private val cache: GrantCache,private val clock: Clock=Clock.systemUTC()) {
    private val mutex=Mutex()
    private val renewal=Mutex()
    private var grant: VerifiedGrant?=null
    private var signed: String?=null
    private var lastSeen=Instant.EPOCH
    private val mutable=MutableStateFlow(BillingState(enforced=configured))
    val state=mutable.asStateFlow()

    suspend fun refresh(userRestore:Boolean=false):BillingRefreshResult=mutex.withLock {
        mutable.value=mutable.value.copy(busy=true,notice=null)
        try {
            loadCache()
            if(!configured) { mutable.value=BillingState(AccessState.unavailable,notice=BillingNotice.unavailable,enforced=false); return@withLock BillingRefreshResult.unavailable }
            val snapshot=store.snapshot()
            mutable.value=mutable.value.copy(products=snapshot.products)
            val owned=snapshot.purchases.filter { it.productId in productIds }.sortedBy { productIds.indexOf(it.productId) }
            val purchased=owned.firstOrNull { it.state==StorePurchaseState.purchased }
            if(purchased==null) {
                clearGrant()
                mutable.value=mutable.value.copy(access=if(owned.any { it.state==StorePurchaseState.pending }) AccessState.pending else AccessState.free,
                    notice=if(owned.isNotEmpty()) BillingNotice.pendingPayment else if(userRestore) BillingNotice.notFound else null)
                return@withLock if(owned.isNotEmpty()) BillingRefreshResult.pending else BillingRefreshResult.notFound
            }
            if(grant!=null && (grant?.purchaseTokenHash!=sha256(purchased.token.toByteArray()) || grant?.product!=purchased.productId)) clearGrant()
            when(val result=verifier.verify(purchased)) {
                is PurchaseVerification.Active -> {
                    val checked=leaseVerifier().verify(result.signedLease)
                    val now=clock.instant()
                    require(Duration.between(checked.issuedAt,now).abs()<=Duration.ofMinutes(5)) { "Check device date and time" }
                    require(checked.purchaseTokenHash==sha256(purchased.token.toByteArray()) && checked.product==purchased.productId)
                    require(checked.allows(now,now))
                    cache.save(CachedGrant(result.signedLease,now))
                    grant=checked;signed=result.signedLease;lastSeen=now
                    mutable.value=mutable.value.copy(access=AccessState.active,notice=BillingNotice.verified,activeProductId=checked.product,autoRenewing=purchased.autoRenewing)
                    BillingRefreshResult.verified
                }
                PurchaseVerification.Pending -> { clearGrant(); mutable.value=mutable.value.copy(access=AccessState.pending,notice=BillingNotice.pendingVerification);BillingRefreshResult.pending }
                PurchaseVerification.Revoked -> { clearGrant(); mutable.value=mutable.value.copy(access=AccessState.free,notice=BillingNotice.notEligible);BillingRefreshResult.notFound }
            }
        } catch(e:Exception) {
            if(e is CancellationException) throw e
            val active=grant?.allows(clock.instant(),lastSeen)==true
            mutable.value=mutable.value.copy(access=if(active) AccessState.active else AccessState.verificationRequired,
                notice=if(active) BillingNotice.cached else BillingNotice.verificationFailed,activeProductId=if(active) grant?.product else null)
            BillingRefreshResult.failed
        } finally { mutable.value=mutable.value.copy(busy=false) }
    }
    private suspend fun loadCache() {
        if(grant!=null) return
        val cached=cache.load() ?: return
        try { grant=leaseVerifier().verify(cached.signedLease);signed=cached.signedLease;lastSeen=cached.lastSeen }
        catch(e:Exception) { if(e is CancellationException) throw e; clearGrant() }
    }
    private suspend fun clearGrant() { grant=null;signed=null;lastSeen=Instant.EPOCH;cache.clear();mutable.value=mutable.value.copy(activeProductId=null,autoRenewing=false) }
    private fun free(capability:Capability)=capability in FREE_CAPABILITIES || (!configured && capability==Capability.recording)
    suspend fun requireAccess(capability: Capability) {
        if(free(capability)) return
        mutex.withLock {
        loadCache()
        val now=clock.instant()
        if(!configured || grant?.allows(now,lastSeen)!=true) {
            mutable.value=mutable.value.copy(access=if(configured) AccessState.verificationRequired else AccessState.unavailable,notice=if(configured) BillingNotice.verificationFailed else BillingNotice.unavailable)
            throw VerifiedAccessRequired()
        }
        lastSeen=maxOf(lastSeen,now)
        cache.save(CachedGrant(requireNotNull(signed),lastSeen))
        }
    }
    /** Cold workers can renew expired grants; valid offline leases and free features need no network. */
    suspend fun requireAccessWithRefresh(capability:Capability) {
        try {requireAccess(capability);return}
        catch(e:Exception) {if(e is CancellationException || !configured || free(capability)) throw e}
        renewal.withLock {
            try {requireAccess(capability);return@withLock}
            catch(e:Exception) {if(e is CancellationException) throw e}
            refresh()
            requireAccess(capability)
        }
    }
    fun notice(value:BillingNotice) { mutable.value=mutable.value.copy(notice=value) }
    // Recording is paid once Play billing is configured; history, imports and previews never are.
    companion object { private val FREE_CAPABILITIES=setOf(Capability.historyImport,Capability.preview) }
}
