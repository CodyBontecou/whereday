package com.bontecou.whereday

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.bontecou.whereday.billing.*

/** Disposable mapQa presentation fixture. It never changes the billing session or grants access. */
class SubscriptionPreviewActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        check(packageName=="com.bontecou.whereday.mapqa")
        val mode=intent.data?.getQueryParameter("state") ?: "offline-subscription"
        val lifetime=mode in setOf("lifetime","lifetime-renewing","offline-lifetime")
        val summary=if(mode=="lapsed") SubscriptionSummary(SubscriptionStatus.lapsed) else PlanRetention.summary(
            BillingState(AccessState.active,activeProductId=if(lifetime) "lifetime" else "individual",autoRenewing=mode=="renewing",
                activePurchaseHash="preview",subscriptionRenewing=mode=="lifetime-renewing",renewalKnown=!mode.startsWith("offline-")),
            PlanHistory(true,StorePlan.yearly,"preview"),"individual")
        setContent {WheredayTheme(dark=intent.data?.getQueryParameter("dark")=="true") {
            SubscriptionScreen(summary,false,"https://play.google.com/store/account/subscriptions",::finish,::finish)
        }}
    }
}
