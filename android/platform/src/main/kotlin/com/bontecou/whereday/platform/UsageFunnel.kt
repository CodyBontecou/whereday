package com.bontecou.whereday.platform

import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Job
import com.bontecou.whereday.billing.BillingRefreshResult

/** Typed action lifetime: the completion uses the permission captured before the action began. */
class UsageFunnel(private val analytics:UsageAnalyticsController) {
    class PermissionAttempt internal constructor(internal val permit:UsageActionPermit,internal val request:UsageRequestKind,internal val started:Job?) {
        internal val completed=AtomicBoolean()
    }
    class PurchaseAttempt internal constructor(internal val permit:UsageActionPermit,internal val context:UsagePaywall,
        internal val product:UsageProduct?,internal val restore:Boolean,internal val started:Job?) {
        internal val completed=AtomicBoolean()
    }
    fun permission(request:UsageRequestKind,status:UsageAuthorization):PermissionAttempt? {
        val permit=analytics.capture() ?: return null
        val started=analytics.track(permit) {UsageEvent(UsageEventName.permissionRequested,step=step(request),request=request,authorization=status)}
        return PermissionAttempt(permit,request,started)
    }
    fun permissionFinished(attempt:PermissionAttempt?,status:UsageAuthorization) {
        if(attempt==null || !attempt.completed.compareAndSet(false,true)) return
        analytics.track(attempt.permit,after=attempt.started) {UsageEvent(UsageEventName.permissionCompleted,step=step(attempt.request),request=attempt.request,authorization=status)}
    }
    fun paywall(context:UsagePaywall) {analytics.track {UsageEvent(UsageEventName.paywallShown,paywall=context)}}
    fun purchase(context:UsagePaywall,productId:String,restore:Boolean=false):PurchaseAttempt? {
        val permit=analytics.capture() ?: return null
        // Configured product strings never enter telemetry unless they match the reviewed allowlist.
        val product=UsageProduct.entries.singleOrNull {it.wire==productId}
        val started=analytics.track(permit) {UsageEvent(if(restore) UsageEventName.restoreStarted else UsageEventName.purchaseStarted,
            paywall=context,product=product,outcome=UsageOutcome.started)}
        return PurchaseAttempt(permit,context,product,restore,started)
    }
    fun purchaseFinished(attempt:PurchaseAttempt?,outcome:UsageOutcome,error:UsageError?=null) {
        if(attempt==null || !attempt.completed.compareAndSet(false,true)) return
        analytics.track(attempt.permit,after=attempt.started) {UsageEvent(if(attempt.restore) UsageEventName.restoreFinished else UsageEventName.purchaseFinished,
            paywall=attempt.context,product=attempt.product,outcome=outcome,error=error)}
    }
    fun refreshFinished(attempt:PurchaseAttempt?,result:BillingRefreshResult) {
        when(result) {
            BillingRefreshResult.verified->purchaseFinished(attempt,if(attempt?.restore==true) UsageOutcome.restored else UsageOutcome.succeeded)
            BillingRefreshResult.pending->purchaseFinished(attempt,UsageOutcome.pending,UsageError.paymentPending)
            BillingRefreshResult.notFound->purchaseFinished(attempt,if(attempt?.restore==true) UsageOutcome.notFound else UsageOutcome.failed,UsageError.notUnlocked)
            BillingRefreshResult.unavailable->purchaseFinished(attempt,UsageOutcome.failed,UsageError.storeUnavailable)
            BillingRefreshResult.failed->purchaseFinished(attempt,UsageOutcome.failed,UsageError.unknown)
        }
    }
    private fun step(request:UsageRequestKind)=if(request==UsageRequestKind.notifications) UsageStep.notifications else UsageStep.permissions
}
