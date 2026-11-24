package com.bontecou.whereday

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.platform.LocalContext
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.bontecou.whereday.billing.*
import kotlinx.coroutines.flow.MutableStateFlow

/** Compiled only into the disposable mapQa application; nothing here grants real access. */
internal object SubscriptionPreview {
    data class Value(val billing:BillingState,val history:PlanHistory)
    val override=MutableStateFlow<Value?>(null)
    private const val qaPackage="com.bontecou.whereday.mapqa"

    fun allowed(packageName:String,variant:String)=packageName==qaPackage && variant=="mapQa"
    fun requireShell(packageName:String,variant:String,uid:Int) {
        if(!allowed(packageName,variant) || uid!=2000) throw SecurityException("Disposable mapQa shell only")
    }

    /** Fixed synthetic render states, not arbitrary serialized BillingState or purchase data. */
    fun fixture(mode:String):Value? {
        val subscription="whereday_individual"
        val bought=PlanHistory(true,StorePlan.yearly,"synthetic-presentation")
        fun active(product:String=subscription,renewing:Boolean=false,alsoRenewing:Boolean=false,known:Boolean=true)=
            BillingState(AccessState.active,activeProductId=product,autoRenewing=renewing,activePurchaseHash="synthetic-presentation",
                subscriptionRenewing=alsoRenewing,renewalKnown=known,notice=if(known) null else BillingNotice.cached)
        return when(mode) {
            "renewing-yearly"->Value(active(renewing=true),bought)
            "renewing-weekly"->Value(active(renewing=true),bought.copy(checkoutPlan=StorePlan.weekly))
            "renewing-unknown"->Value(active(renewing=true),PlanHistory(true))
            "ending-yearly"->Value(active(),bought)
            "lifetime"->Value(active("whereday_export_lifetime"),PlanHistory())
            "lifetime-renewing"->Value(active("whereday_export_lifetime",alsoRenewing=true),bought)
            "offline-subscription"->Value(active(known=false),bought)
            "offline-lifetime"->Value(active("whereday_export_lifetime",known=false),bought)
            "lapsed"->Value(BillingState(AccessState.free),bought)
            "none"->Value(BillingState(AccessState.free),PlanHistory())
            "clear"->null
            else->throw IllegalArgumentException("Unknown presentation state")
        }
    }

    /** Authorized malformed requests reset the fixture; unauthorized callers cannot modify even an existing fixture. */
    @Synchronized
    fun configure(packageName:String,variant:String,uid:Int,method:String,arg:String?,hasExtras:Boolean):String {
        requireShell(packageName,variant,uid)
        try {
            require(!hasExtras) {"No extras accepted"}
            val mode=when(method) {
                "set"->requireNotNull(arg) {"State required"}
                "clear"->{require(arg==null) {"Clear accepts no argument"};"clear"}
                else->throw IllegalArgumentException("Unknown presentation method")
            }
            override.value=fixture(mode)
            return mode
        } catch(e:IllegalArgumentException) {override.value=null;throw e}
    }

    /** Serializes the dispatch boundary with shell selection; it does not cancel actions already dispatched. */
    @Synchronized
    fun dispatchStoreAction(action:()->Unit) {
        if(allowed(BuildConfig.APPLICATION_ID,BuildConfig.BUILD_TYPE) && override.value==null) action()
    }

    fun presentation(packageName:String,variant:String,live:BillingState,history:PlanHistory,value:Value?=override.value):SubscriptionPresentation =
        if(allowed(packageName,variant) && value!=null) SubscriptionPresentation(value.billing,value.history,storeActionsAllowed=false)
        else SubscriptionPresentation(live,history)
}

internal fun dispatchSubscriptionStoreAction(action:()->Unit)=SubscriptionPreview.dispatchStoreAction(action)

@Suppress("UNUSED_PARAMETER")
@Composable internal fun rememberSubscriptionPresentation(live:BillingState,history:PlanHistory,
    owner:SubscriptionPresentationOwner=SubscriptionPresentationOwner.settings):SubscriptionPresentation {
    val fixture by SubscriptionPreview.override.collectAsStateWithLifecycle()
    return SubscriptionPreview.presentation(LocalContext.current.packageName,BuildConfig.BUILD_TYPE,live,history,fixture)
}
