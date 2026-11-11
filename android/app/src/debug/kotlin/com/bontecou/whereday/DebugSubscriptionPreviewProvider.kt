package com.bontecou.whereday

import android.content.ContentProvider
import android.content.ContentValues
import android.net.Uri
import android.os.Binder
import android.os.Bundle
import com.bontecou.whereday.billing.*

/**
 * Debug-only billing stand-in for UI checks on an emulator without Play billing:
 * `adb shell content call --uri content://com.bontecou.whereday.subscriptionpreview --method set --arg lapsed`.
 * It changes only what Settings shows. It never grants access: recording and exports still ask the real BillingSession.
 */
class DebugSubscriptionPreviewProvider : ContentProvider() {
    override fun onCreate() = true
    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        check(Binder.getCallingUid() == 2000) { "Subscription preview is available only to the ADB shell" }
        require(method == "set") { "Use --method set" }
        val subscription = "whereday_individual"
        val trial = listOf(StoreProduct(subscription, StorePlan.yearly, "$29.99", 29_990_000, "USD", freeTrialDays = 7))
        fun active(product: String, renewing: Boolean, alsoRenewing: Boolean = false) =
            BillingState(AccessState.active, activeProductId = product, autoRenewing = renewing, activePurchaseHash = "preview", subscriptionRenewing = alsoRenewing)
        val bought = PlanHistory(true, StorePlan.yearly, "preview")
        SubscriptionPreview.override.value = when (arg) {
            "renewing-yearly" -> SubscriptionPreview.Value(active(subscription, true), bought)
            "renewing-weekly" -> SubscriptionPreview.Value(active(subscription, true), bought.copy(checkoutPlan = StorePlan.weekly))
            "renewing-unknown" -> SubscriptionPreview.Value(active(subscription, true), PlanHistory(true))
            "ending-yearly" -> SubscriptionPreview.Value(active(subscription, false), bought)
            "lifetime" -> SubscriptionPreview.Value(active("whereday_export_lifetime", false), PlanHistory())
            "lifetime-renewing" -> SubscriptionPreview.Value(active("whereday_export_lifetime", false, alsoRenewing = true), bought)
            "lapsed" -> SubscriptionPreview.Value(BillingState(AccessState.free), bought)
            "lapsed-trial" -> SubscriptionPreview.Value(BillingState(AccessState.free, products = trial), bought)
            "none" -> SubscriptionPreview.Value(BillingState(AccessState.free), PlanHistory())
            "clear", null -> null
            else -> throw IllegalArgumentException("Unknown preview: $arg")
        }
        return Bundle().apply { putString("preview", arg ?: "clear") }
    }
    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?) = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = throw UnsupportedOperationException()
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = throw UnsupportedOperationException()
}
