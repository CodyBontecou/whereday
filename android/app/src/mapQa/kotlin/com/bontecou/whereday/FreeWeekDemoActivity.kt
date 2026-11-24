package com.bontecou.whereday

import android.Manifest
import android.os.Build
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.testTagsAsResourceId
import androidx.compose.ui.unit.dp
import androidx.core.app.NotificationManagerCompat
import androidx.lifecycle.lifecycleScope
import com.bontecou.whereday.billing.*
import com.bontecou.whereday.platform.UsageAuthorization
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch

/** QA-only (mapQa): onboarding's last step with a FAKE configured billing, so the free-week path can be driven on an emulator
 *  without Google Play, the purchase verifier or a real purchase. Nothing here touches the journal or Play; only the Evening
 *  recap choice is applied to this QA package's Settings › Reminders and scheduled, through the same function the app uses.
 *  `adb shell am start -n com.bontecou.whereday.mapqa/com.bontecou.whereday.FreeWeekDemoActivity`
 *  Extras: `trial` (boolean, default true: Play offers the free week), `catalog` (`ok` or `fail`: the first product query fails),
 *  `outcome` (`success`, `cancel` or `pending`), `intents` (comma-separated keys, default `days`), `location` (boolean, default true). */
class FreeWeekDemoActivity:androidx.activity.ComponentActivity() {
    private val notificationsAllowed=MutableStateFlow(false)
    private val notificationPermission=registerForActivityResult(ActivityResultContracts.RequestPermission()) {refreshNotifications()}
    private fun refreshNotifications() {notificationsAllowed.value=NotificationManagerCompat.from(this).areNotificationsEnabled()}
    override fun onResume() {super.onResume();refreshNotifications()}

    override fun onCreate(state:android.os.Bundle?) {
        super.onCreate(state)
        check(packageName.endsWith(".mapqa")) {"Fake billing is for the disposable QA package only"}
        val billing=FakeBilling(lifecycleScope,trial=intent.getBooleanExtra("trial",true),failFirst=intent.getStringExtra("catalog")=="fail",
            outcome=intent.getStringExtra("outcome") ?: "success")
        billing.load()
        val intents=intent.getStringExtra("intents")?.split(',')?.map {it.trim()}?.toSet()?.let {OnboardingIntent.decode(it)} ?: OnboardingIntent.DEFAULT
        val location=intent.getBooleanExtra("location",true)
        refreshNotifications()
        setContent {
            WheredayTheme(isSystemInDarkTheme()) {
                val purchase by billing.state.collectAsState()
                val checkout by billing.checkout.collectAsState()
                val notifications by notificationsAllowed.collectAsState()
                var result by remember {mutableStateOf<String?>(null)}
                val finished=result
                if(finished!=null) Surface(Modifier.fillMaxSize().semantics {testTagsAsResourceId=true}) {
                    Text(finished,Modifier.safeDrawingPadding().padding(24.dp).testTag("qa-onboarding-result"))
                } else Onboarding({},{if(Build.VERSION.SDK_INT>=33) notificationPermission.launch(Manifest.permission.POST_NOTIFICATIONS)},
                    authorization=if(location) UsageAuthorization.foreground else UsageAuthorization.unknown,
                    access=RecordingAccessState(if(location) LocationPrecision.precise else LocationPrecision.none,notifications),
                    initialIntents=intents,offer=OnboardingOffer(purchase,checkout,billing::buy,billing::restore,billing::load),initialStep=OnboardingStep.tonight) {decision->
                    val preferences=com.bontecou.whereday.platform.AppGraph.from(this@FreeWeekDemoActivity).preferences
                    preferences.update {it.copy(recapFrequency=OnboardingRecap.frequency(decision).key)}
                    scheduleRecap(this@FreeWeekDemoActivity,preferences,onboarding=decision.recap)
                    result="QA onboarding finished: start=${decision.start} recap=${decision.recap} trial=${decision.trial}"
                }
            }
        }
    }
}

/** Synthetic Play catalog and checkout. The prices are QA fixtures, never shown by real builds, which read Play's product details. */
private class FakeBilling(private val scope:CoroutineScope,trial:Boolean,private var failFirst:Boolean,private val outcome:String) {
    private val days=if(trial) 7 else 0
    private val products=listOf(
        StoreProduct("whereday_individual",StorePlan.weekly,"$0.99",990_000,"USD",days),
        StoreProduct("whereday_individual",StorePlan.yearly,"$29.99",29_990_000,"USD",days),
        StoreProduct("whereday_export_lifetime",StorePlan.lifetime,"$49.99",49_990_000,"USD"))
    val state=MutableStateFlow(BillingState(AccessState.checking,busy=true,enforced=true))
    val checkout=MutableStateFlow(false)
    fun load() {
        scope.launch {
            state.value=state.value.copy(busy=true,notice=null)
            delay(600)
            state.value=if(failFirst) {failFirst=false;BillingState(AccessState.free,enforced=true)} else BillingState(AccessState.free,products,enforced=true)
        }
    }
    fun buy(plan:StorePlan) {
        if(!checkout.compareAndSet(false,true)) return
        scope.launch {
            delay(800)
            checkout.value=false
            state.value=when(outcome) {
                "cancel"->state.value.copy(notice=BillingNotice.canceled)
                "pending"->state.value.copy(access=AccessState.pending,notice=BillingNotice.pendingPayment)
                else->state.value.copy(access=AccessState.active,notice=BillingNotice.verified,activeProductId=products.first {it.plan==plan}.id)
            }
        }
    }
    fun restore() {
        scope.launch {
            state.value=state.value.copy(busy=true,notice=null);delay(400)
            state.value=state.value.copy(busy=false,notice=BillingNotice.notFound)
        }
    }
}
