package com.bontecou.whereday

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.SideEffect
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalView
import androidx.core.view.WindowInsetsControllerCompat
import com.bontecou.whereday.billing.*

/** Disposable mapQa presentation fixture. It never changes the billing session or grants access. */
class SubscriptionPreviewActivity:ComponentActivity() {
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        check(packageName=="com.bontecou.whereday.mapqa")
        enableEdgeToEdge()
        val mode=intent.data?.getQueryParameter("state") ?: "offline-subscription"
        val lifetime=mode in setOf("lifetime","lifetime-renewing","offline-lifetime")
        val summary=if(mode=="lapsed") SubscriptionSummary(SubscriptionStatus.lapsed) else PlanRetention.summary(
            BillingState(AccessState.active,activeProductId=if(lifetime) "lifetime" else "individual",autoRenewing=mode=="renewing",
                activePurchaseHash="preview",subscriptionRenewing=mode=="lifetime-renewing",renewalKnown=!mode.startsWith("offline-")),
            PlanHistory(true,StorePlan.yearly,"preview"),"individual")
        val dark=intent.data?.getQueryParameter("dark")=="true"
        setContent {
            val view=LocalView.current
            SideEffect {WindowInsetsControllerCompat(window,view).apply {
                isAppearanceLightStatusBars=!dark;isAppearanceLightNavigationBars=!dark
            }}
            WheredayTheme(dark=dark) {
                Scaffold(containerColor=wd.surface,contentWindowInsets=WindowInsets.statusBars) {padding->
                    Box(Modifier.fillMaxSize().padding(padding).consumeWindowInsets(padding)) {
                        SubscriptionScreen(summary,false,"https://play.google.com/store/account/subscriptions",::finish,::finish)
                    }
                }
            }
        }
    }
}
