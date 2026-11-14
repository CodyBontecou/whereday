package com.bontecou.whereday.platform

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.bontecou.whereday.billing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock

/** Application-owned billing connection. Activities are used only while opening Play's sheet. */
class PurchaseController(context: Context,private val usage:()->UsageFunnel?={null}) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val configuration=PurchaseConfiguration()
    private val identity=PurchaseIdentity()
    private val refreshes=Channel<Unit>(Channel.CONFLATED)
    private val checkout=MutableStateFlow(false)
    val checkoutInFlight=checkout.asStateFlow()
    private val purchaseUsage=java.util.concurrent.atomic.AtomicReference<UsageFunnel.PurchaseAttempt?>()
    private val restoring=java.util.concurrent.atomic.AtomicBoolean()
    private val store=PlayPurchaseStore(context,configuration.productId) { code ->
        val attempt=purchaseUsage.getAndSet(null)
        checkout.value=false
        if(code==BillingClient.BillingResponseCode.OK) scope.launch {refreshUsage(attempt)}
        else {
            usage()?.purchaseFinished(attempt,if(code==BillingClient.BillingResponseCode.USER_CANCELED) UsageOutcome.cancelled else UsageOutcome.failed,
                if(code==BillingClient.BillingResponseCode.USER_CANCELED) UsageError.userCancelled else UsageError.unknown)
            if(code==BillingClient.BillingResponseCode.USER_CANCELED) session.notice(BillingNotice.canceled) else refresh()
        }
    }
    private val session:BillingSession=BillingSession(configuration.productId,configuration.configured,store,
        RemotePurchaseVerifier(context,configuration,identity),
        { LeaseVerifier(configuration.publicKey,context.packageName,configuration.productId,identity.hash()) },
        PurchaseGrantCache(context),Clock.systemUTC())
    val state=session.state

    init { scope.launch { for(signal in refreshes) session.refresh() } }

    fun refresh() { refreshes.trySend(Unit) }
    // Resuming under Play's sheet must not admit another checkout before its callback.
    fun resumed() {refresh()}

    /** Called directly at the click boundary, before dispatching Play UI work. */
    fun purchase(activity:Activity,context:UsagePaywall=UsagePaywall.export):Job? {
        val current=state.value
        if(!configuration.configured || current.busy || current.product==null || restoring.get() ||
            current.access in setOf(AccessState.active,AccessState.pending) || !checkout.compareAndSet(false,true)) return null
        val attempt=usage()?.purchase(context,configuration.productId)
        purchaseUsage.set(attempt)
        return scope.launch(Dispatchers.Main.immediate) {
            try {
                when(store.launch(activity)) {
                    BillingClient.BillingResponseCode.OK -> Unit // Listener completes this exact captured attempt.
                    BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                        purchaseUsage.compareAndSet(attempt,null);checkout.value=false
                        withContext(Dispatchers.IO) {refreshUsage(attempt)}
                    }
                    BillingClient.BillingResponseCode.USER_CANCELED -> {
                        purchaseUsage.compareAndSet(attempt,null);checkout.value=false
                        usage()?.purchaseFinished(attempt,UsageOutcome.cancelled,UsageError.userCancelled);session.notice(BillingNotice.canceled)
                    }
                    else -> {
                        purchaseUsage.compareAndSet(attempt,null);checkout.value=false
                        usage()?.purchaseFinished(attempt,UsageOutcome.failed,UsageError.storeUnavailable)
                        session.notice(BillingNotice.storeUnavailable)
                    }
                }
            } catch(e:Exception) {
                purchaseUsage.compareAndSet(attempt,null);checkout.value=false
                if(e is CancellationException) throw e
                usage()?.purchaseFinished(attempt,UsageOutcome.failed,UsageError.unknown)
                session.notice(BillingNotice.storeUnavailable)
            }
        }
    }
    /** Automatic refreshes never become user restore events. Capture consent before scheduling. */
    fun restore(context:UsagePaywall):Job? {
        if(state.value.busy || checkout.value || !restoring.compareAndSet(false,true)) return null
        val attempt=usage()?.purchase(context,configuration.productId,restore=true)
        return scope.launch {
            try {refreshUsage(attempt,userRestore=true)}
            finally {restoring.set(false)}
        }
    }

    private suspend fun refreshUsage(attempt:UsageFunnel.PurchaseAttempt?,userRestore:Boolean=false) {
        val result=session.refresh(userRestore)
        usage()?.refreshFinished(attempt,result)
    }

    internal fun close() {scope.cancel();refreshes.close();store.close()}

    suspend fun requireAccess(capability:Capability)=withContext(Dispatchers.IO) { session.requireAccessWithRefresh(capability) }
}
