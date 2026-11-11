package com.bontecou.whereday.platform

import android.app.Activity
import android.content.Context
import com.android.billingclient.api.BillingClient
import com.bontecou.whereday.billing.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Clock

/** Application-owned billing connection. Activities are used only while opening Play's sheet. */
class PurchaseController(private val context: Context,private val usage:()->UsageFunnel?={null}) {
    private val scope=CoroutineScope(SupervisorJob()+Dispatchers.IO)
    private val configuration=PurchaseConfiguration()
    private val identity=PurchaseIdentity()
    private val refreshes=Channel<Unit>(Channel.CONFLATED)
    private val checkout=MutableStateFlow(false)
    val checkoutInFlight=checkout.asStateFlow()
    private val purchaseUsage=java.util.concurrent.atomic.AtomicReference<UsageFunnel.PurchaseAttempt?>()
    private val restoring=java.util.concurrent.atomic.AtomicBoolean()
    /** The plan whose Play sheet is open, so a completed free-week checkout can schedule its reminder. */
    private val checkoutPlan=java.util.concurrent.atomic.AtomicReference<StoreProduct?>()
    private val planMemory=PlanHistoryStore(context)
    private val history=MutableStateFlow(planMemory.load())
    /** What this installation remembers about its plan: whether it ever had a subscription, and the base plan it bought. */
    val planHistory:StateFlow<PlanHistory> = history.asStateFlow()
    private val store=PlayPurchaseStore(context,configuration.productId,configuration.subscriptionId) { result ->
        val code=result.code
        val attempt=purchaseUsage.getAndSet(null)
        val plan=checkoutPlan.getAndSet(null)
        checkout.value=false
        if(code==BillingClient.BillingResponseCode.OK && plan!=null) remember {PlanRetention.checkoutCompleted(it,plan.plan,result.purchaseTokenHash)}
        if(code==BillingClient.BillingResponseCode.OK && plan!=null && plan.freeTrialDays>0 && result.purchasedAt!=null)
            TrialReminder.schedule(context,result.purchasedAt.plus(java.time.Duration.ofDays(plan.freeTrialDays.toLong())),plan.formattedPrice,plan.plan==StorePlan.yearly)
        if(code==BillingClient.BillingResponseCode.OK) scope.launch {refreshUsage(attempt)}
        else {
            usage()?.purchaseFinished(attempt,if(code==BillingClient.BillingResponseCode.USER_CANCELED) UsageOutcome.cancelled else UsageOutcome.failed,
                if(code==BillingClient.BillingResponseCode.USER_CANCELED) UsageError.userCancelled else UsageError.unknown)
            if(code==BillingClient.BillingResponseCode.USER_CANCELED) session.notice(BillingNotice.canceled) else refresh()
        }
    }
    private val session:BillingSession=BillingSession(configuration.productIds,configuration.configured,store,
        RemotePurchaseVerifier(context,configuration,identity),
        { LeaseVerifier(configuration.publicKey,context.packageName,configuration.productIds.toSet(),identity.hash()) },
        PurchaseGrantCache(context),Clock.systemUTC())
    val state=session.state

    init {
        scope.launch { for(signal in refreshes) {session.refresh();reconcileReminder()} }
        // A lapse is only recognizable against a subscription this installation has verified before.
        scope.launch { session.state.collect { current -> remember {PlanRetention.observe(it,current,configuration.subscriptionId)} } }
    }
    @Synchronized private fun remember(change:(PlanHistory)->PlanHistory) {
        val before=history.value
        val after=change(before)
        if(after!=before) {planMemory.save(after);history.value=after}
    }
    /** A canceled or replaced trial keeps no reminder; Play itself reports cancellation as auto-renew off. */
    private fun reconcileReminder() {
        val current=state.value
        if(current.access==AccessState.active && (current.activeProductId!=configuration.subscriptionId || !current.autoRenewing)) TrialReminder.cancel(context)
        else if(current.access==AccessState.free) TrialReminder.cancel(context)
    }
    val subscriptionId get()=configuration.subscriptionId
    /** Play's own subscription page; cancellation and plan changes happen there. */
    fun manageUri()="https://play.google.com/store/account/subscriptions?sku=${configuration.subscriptionId}&package=${context.packageName}"

    fun refresh() { refreshes.trySend(Unit) }
    // Resuming under Play's sheet must not admit another checkout before its callback.
    fun resumed() {refresh()}

    /** Called directly at the click boundary, before dispatching Play UI work. */
    fun purchase(activity:Activity,plan:StorePlan,context:UsagePaywall=UsagePaywall.export):Job? {
        val current=state.value
        val product=current.product(plan)
        if(!configuration.configured || current.busy || product==null || restoring.get() ||
            current.access in setOf(AccessState.active,AccessState.pending) || !checkout.compareAndSet(false,true)) return null
        val attempt=usage()?.purchase(context,product.id)
        purchaseUsage.set(attempt);checkoutPlan.set(product)
        return scope.launch(Dispatchers.Main.immediate) {
            try {
                when(store.launch(activity,plan)) {
                    BillingClient.BillingResponseCode.OK -> Unit // Listener completes this exact captured attempt.
                    BillingClient.BillingResponseCode.ITEM_ALREADY_OWNED -> {
                        purchaseUsage.compareAndSet(attempt,null);checkout.value=false;checkoutPlan.set(null)
                        withContext(Dispatchers.IO) {refreshUsage(attempt)}
                    }
                    BillingClient.BillingResponseCode.USER_CANCELED -> {
                        purchaseUsage.compareAndSet(attempt,null);checkout.value=false;checkoutPlan.set(null)
                        usage()?.purchaseFinished(attempt,UsageOutcome.cancelled,UsageError.userCancelled);session.notice(BillingNotice.canceled)
                    }
                    else -> {
                        purchaseUsage.compareAndSet(attempt,null);checkout.value=false;checkoutPlan.set(null)
                        usage()?.purchaseFinished(attempt,UsageOutcome.failed,UsageError.storeUnavailable)
                        session.notice(BillingNotice.storeUnavailable)
                    }
                }
            } catch(e:Exception) {
                purchaseUsage.compareAndSet(attempt,null);checkout.value=false;checkoutPlan.set(null)
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
