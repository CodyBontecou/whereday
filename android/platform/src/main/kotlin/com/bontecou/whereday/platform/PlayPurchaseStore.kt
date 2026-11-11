package com.bontecou.whereday.platform

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import com.bontecou.whereday.billing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.time.Instant
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A completed Play checkout, reported before verification. [purchaseTime] starts a free trial's clock. */
data class CheckoutResult(val code:Int,val purchasedAt:Instant?,val purchaseTokenHash:String?=null)

class PlayPurchaseStore(context:Context,private val lifetimeId:String,private val subscriptionId:String,private val events:(CheckoutResult)->Unit):PurchaseStore {
    private val connectionMutex=Mutex()
    @Volatile private var connection:CompletableDeferred<Unit>?=null
    /** The offer token each plan launches with: a subscription base plan's eligible trial offer, or the Lifetime buy option. */
    @Volatile private var offers:Map<StorePlan,Pair<ProductDetails,String>> = emptyMap()
    private val client=BillingClient.newBuilder(context.applicationContext)
        .setListener { result,purchases ->
            val subscription=purchases?.firstOrNull { subscriptionId in it.products }
            events(CheckoutResult(result.responseCode,subscription?.let { Instant.ofEpochMilli(it.purchaseTime) },subscription?.let { sha256(it.purchaseToken.toByteArray()) }))
        }
        .enablePendingPurchases(PendingPurchasesParams.newBuilder().enableOneTimeProducts().build())
        .enableAutoServiceReconnection().build()

    private suspend fun connect()=connectionMutex.withLock {
        if(client.isReady) return@withLock
        val pending=connection ?: CompletableDeferred<Unit>().also { deferred ->
            connection=deferred
            client.startConnection(object:BillingClientStateListener {
                override fun onBillingSetupFinished(result:BillingResult) {
                    connection=null
                    if(result.responseCode==BillingClient.BillingResponseCode.OK) deferred.complete(Unit)
                    else deferred.completeExceptionally(IllegalStateException("Google Play billing is unavailable"))
                }
                override fun onBillingServiceDisconnected() { }
            })
        }
        withTimeout(30_000) { pending.await() }
    }
    private suspend fun owned(type:String)=withTimeout(30_000) { suspendCancellableCoroutine<List<Purchase>> { continuation ->
        client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(type).build()) { result,values ->
            if(continuation.isActive) {
                if(result.responseCode==BillingClient.BillingResponseCode.OK) continuation.resume(values)
                else continuation.resumeWithException(IllegalStateException("Google Play purchases could not be read"))
            }
        }
    } }
    private suspend fun details(id:String,type:String)=withTimeoutOrNull(15_000) { suspendCancellableCoroutine<List<ProductDetails>> { continuation ->
        val params=QueryProductDetailsParams.newBuilder().setProductList(listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(id).setProductType(type).build())).build()
        client.queryProductDetailsAsync(params) { result,details -> if(continuation.isActive) continuation.resume(if(result.responseCode==BillingClient.BillingResponseCode.OK) details.productDetailsList else emptyList()) }
    } }.orEmpty().firstOrNull { it.productId==id }

    override suspend fun snapshot():StoreSnapshot {
        connect()
        val purchases=owned(BillingClient.ProductType.INAPP)+owned(BillingClient.ProductType.SUBS)
        // Catalog failure must not prevent restoring an already-owned product.
        val found=mutableMapOf<StorePlan,Pair<ProductDetails,String>>()
        val products=mutableListOf<StoreProduct>()
        details(subscriptionId,BillingClient.ProductType.SUBS)?.let { details ->
            details.subscriptionOfferDetails.orEmpty().groupBy { it.basePlanId }.values.forEach { plans ->
                // Play lists only the offers this account is eligible for, so a free first phase means the trial is available.
                val chosen=plans.firstOrNull { offer -> offer.offerId!=null && offer.pricingPhases.pricingPhaseList.firstOrNull()?.priceAmountMicros==0L }
                    ?: plans.firstOrNull { it.offerId==null } ?: return@forEach
                val phases=chosen.pricingPhases.pricingPhaseList
                val recurring=phases.lastOrNull { it.recurrenceMode==ProductDetails.RecurrenceMode.INFINITE_RECURRING } ?: return@forEach
                val plan=when(recurring.billingPeriod) { "P1W","P7D" -> StorePlan.weekly; "P1Y","P12M" -> StorePlan.yearly; else -> return@forEach }
                val trial=phases.firstOrNull()?.takeIf { it.priceAmountMicros==0L && it!==recurring }?.let { trialDays(it.billingPeriod) } ?: 0
                found[plan]=details to chosen.offerToken
                products+=StoreProduct(subscriptionId,plan,recurring.formattedPrice,recurring.priceAmountMicros,recurring.priceCurrencyCode,trial)
            }
        }
        details(lifetimeId,BillingClient.ProductType.INAPP)?.let { details ->
            details.oneTimePurchaseOfferDetailsList?.filter { it.rentalDetails==null && it.preorderDetails==null && it.offerToken!=null }?.minByOrNull { it.priceAmountMicros }?.let { offer ->
                found[StorePlan.lifetime]=details to requireNotNull(offer.offerToken)
                products+=StoreProduct(lifetimeId,StorePlan.lifetime,offer.formattedPrice,offer.priceAmountMicros,offer.priceCurrencyCode)
            }
        }
        offers=found
        return StoreSnapshot(products.sortedBy { it.plan.ordinal },purchases.flatMap { purchase ->
            val state=when(purchase.purchaseState) { Purchase.PurchaseState.PURCHASED -> StorePurchaseState.purchased; Purchase.PurchaseState.PENDING -> StorePurchaseState.pending; else -> null }
            if(state==null) emptyList() else purchase.products.filter { it==lifetimeId || it==subscriptionId }.map { StorePurchase(purchase.purchaseToken,it,state,purchase.isAutoRenewing) }
        })
    }
    internal fun close() {client.endConnection()}
    suspend fun launch(activity:Activity,plan:StorePlan):Int=withContext(Dispatchers.Main.immediate) {
        val (details,token)=offers[plan] ?: error("This plan is not available")
        val params=BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(details).setOfferToken(token).build()
        client.launchBillingFlow(activity,BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params)).build()).responseCode
    }
    companion object {
        /** ISO 8601 periods as Play reports them for trials, such as P3D, P1W or P1M. */
        internal fun trialDays(period:String):Int {
            val match=Regex("P(\\d+)([DWM])").matchEntire(period) ?: return 0
            val count=match.groupValues[1].toInt()
            return when(match.groupValues[2]) { "D" -> count; "W" -> count*7; else -> count*30 }
        }
    }
}
