package com.bontecou.whereday

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import com.bontecou.whereday.billing.*

@Composable internal fun billingNoticeText(notice:BillingNotice):String=stringResource(when(notice) {
    BillingNotice.unavailable->R.string.billing_unavailable
    BillingNotice.pendingPayment->R.string.billing_pending_payment
    BillingNotice.verified->R.string.billing_verified
    BillingNotice.pendingVerification->R.string.billing_pending_verification
    BillingNotice.notEligible->R.string.billing_not_eligible
    BillingNotice.cached->R.string.billing_cached
    BillingNotice.verificationFailed->R.string.billing_verification_failed
    BillingNotice.canceled->R.string.billing_canceled
    BillingNotice.storeUnavailable->R.string.billing_store_unavailable
    BillingNotice.notFound->R.string.billing_not_found
})

/** Export access on the Export tab: every subscription plan and Lifetime include exports. [manage] opens Play's subscription page. */
@Composable internal fun ExportAccessCard(purchase:BillingState,checkout:Boolean,buy:(StorePlan)->Unit,restore:()->Unit,manage:(()->Unit)?=null) {
    var paywall by rememberSaveable {mutableStateOf(false)}
    var leaving by rememberSaveable {mutableStateOf(false)}
    // Match Settings: only a freshly confirmed renewing subscription gets the factual cancellation summary.
    // Cached access is not proof of renewal (or cancellation); keep its management link direct and neutral.
    if(leaving && manage!=null) BeforeYouGoSheet({leaving=false}) {leaving=false;manage()}
    if(paywall) PaywallDialog(purchase,checkout,buy,restore,dismiss={paywall=false},entry=PaywallEntry.export)
    WdCard(Modifier.testTag("export-access")) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
            Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                Text(stringResource(R.string.export_access_kicker).uppercase(),style=WdType.eyebrow,color=wd.textMuted,modifier=Modifier.weight(1f))
                if(purchase.access==AccessState.active) Icon(Icons.Outlined.Verified,null,Modifier.size(18.dp),tint=wd.success)
            }
            Text(stringResource(R.string.export_access_title),style=WdType.title2,color=wd.textPrimary,modifier=Modifier.semantics {heading()})
            Text(stringResource(R.string.export_access_body),style=MaterialTheme.typography.bodyMedium,color=wd.textMuted)
            purchase.notice?.let {notice->
                val warning=notice in setOf(BillingNotice.unavailable,BillingNotice.verificationFailed,BillingNotice.storeUnavailable,BillingNotice.notFound,BillingNotice.notEligible)
                val ink=when {warning->wd.inkRust;notice==BillingNotice.verified->wd.success;else->wd.accent}
                val shape=RoundedCornerShape(12.dp)
                Row(Modifier.fillMaxWidth().clip(shape).background(ink.copy(alpha=if(wd.dark) 0.12f else 0.06f)).border(1.dp,ink.copy(alpha=0.35f),shape).padding(12.dp),
                    horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                    Icon(when {warning->Icons.Outlined.ErrorOutline;notice==BillingNotice.verified->Icons.Outlined.CheckCircle;notice==BillingNotice.pendingPayment || notice==BillingNotice.pendingVerification->Icons.Outlined.Schedule;else->Icons.Outlined.Info},
                        null,Modifier.size(18.dp),tint=ink)
                    Text(billingNoticeText(notice),Modifier.weight(1f).testTag("export-access-notice"),style=WdType.caption,color=wd.textPrimary)
                }
            }
            if(purchase.access==AccessState.active) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(8.dp)) {
                WdLiveDot(Modifier.size(14.dp),color=wd.success)
                Text(stringResource(R.string.export_access_verified),style=WdType.caption.copy(fontWeight=FontWeight.SemiBold),color=wd.success)
            }
            else {
                // Buying goes through the plan carousel; its Buy and Restore call the same callbacks as this card.
                if(purchase.products.isNotEmpty()) WdButton(stringResource(R.string.paywall_preview),{paywall=true},Modifier.testTag("export-access-buy"),emphasis=WdEmphasis.primary,
                    enabled=!purchase.busy && !checkout && purchase.access!=AccessState.pending,icon=Icons.Outlined.LockOpen)
                // Without store products there is nothing to buy, but the carousel still explains the plans and shows Play as unavailable.
                else if(purchase.access!=AccessState.checking) WdButton(stringResource(R.string.paywall_preview),{paywall=true},Modifier.testTag("export-access-paywall"),
                    icon=Icons.Outlined.Info)
            }
            if(manage!=null) WdButton(stringResource(R.string.paywall_manage),{
                if(purchase.renewalKnown && purchase.autoRenewing) leaving=true else manage()
            },Modifier.testTag("export-access-manage"),icon=Icons.Outlined.OpenInNew)
            WdButton(stringResource(R.string.export_access_restore),restore,Modifier.testTag("export-access-restore"),enabled=!purchase.busy && !checkout,icon=Icons.Outlined.Restore)
            if(purchase.busy) Row(verticalAlignment=Alignment.CenterVertically,horizontalArrangement=Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(16.dp),color=wd.accent,trackColor=wd.dialTrack,strokeWidth=2.dp)
                Text(stringResource(R.string.export_access_checking),style=WdType.caption,color=wd.textMuted)
            }
        }
    }
}
