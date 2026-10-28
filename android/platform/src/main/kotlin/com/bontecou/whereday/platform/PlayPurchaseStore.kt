package com.bontecou.whereday.platform

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.*
import com.bontecou.whereday.billing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class PlayPurchaseStore(context:Context,private val productId:String,private val events:(Int)->Unit):PurchaseStore {
    private val connectionMutex=Mutex()
    @Volatile private var connection:CompletableDeferred<Unit>?=null
    @Volatile private var selected:Pair<ProductDetails,ProductDetails.OneTimePurchaseOfferDetails>?=null
    private val client=BillingClient.newBuilder(context.applicationContext)
        .setListener { result,_ -> events(result.responseCode) }
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
    override suspend fun snapshot():StoreSnapshot {
        connect()
        val purchases=withTimeout(30_000) { suspendCancellableCoroutine<List<Purchase>> { continuation ->
            client.queryPurchasesAsync(QueryPurchasesParams.newBuilder().setProductType(BillingClient.ProductType.INAPP).build()) { result,values ->
                if(continuation.isActive) {
                    if(result.responseCode==BillingClient.BillingResponseCode.OK) continuation.resume(values)
                    else continuation.resumeWithException(IllegalStateException("Google Play purchases could not be read"))
                }
            }
        } }
        // Catalog failure must not prevent restoring an already-owned product.
        val catalog=withTimeoutOrNull(15_000) { suspendCancellableCoroutine<List<ProductDetails>> { continuation ->
            val params=QueryProductDetailsParams.newBuilder().setProductList(listOf(QueryProductDetailsParams.Product.newBuilder().setProductId(productId).setProductType(BillingClient.ProductType.INAPP).build())).build()
            client.queryProductDetailsAsync(params) { result,details -> if(continuation.isActive) continuation.resume(if(result.responseCode==BillingClient.BillingResponseCode.OK) details.productDetailsList else emptyList()) }
        } }.orEmpty()
        selected=catalog.firstOrNull { it.productId==productId }?.let { details ->
            details.oneTimePurchaseOfferDetailsList?.filter { it.rentalDetails==null && it.preorderDetails==null && it.offerToken!=null }?.minByOrNull { it.priceAmountMicros }?.let { details to it }
        }
        return StoreSnapshot(selected?.let { LifetimeProduct(productId,it.second.formattedPrice) },purchases.flatMap { purchase ->
            val state=when(purchase.purchaseState) { Purchase.PurchaseState.PURCHASED -> StorePurchaseState.purchased; Purchase.PurchaseState.PENDING -> StorePurchaseState.pending; else -> null }
            if(state==null) emptyList() else purchase.products.filter { it==productId }.map { StorePurchase(purchase.purchaseToken,it,state) }
        })
    }
    internal fun close() {client.endConnection()}
    suspend fun launch(activity:Activity):Int=withContext(Dispatchers.Main.immediate) {
        val selection=selected ?: error("Lifetime purchase is not available")
        val params=BillingFlowParams.ProductDetailsParams.newBuilder().setProductDetails(selection.first).setOfferToken(requireNotNull(selection.second.offerToken)).build()
        client.launchBillingFlow(activity,BillingFlowParams.newBuilder().setProductDetailsParamsList(listOf(params)).build()).responseCode
    }
}
