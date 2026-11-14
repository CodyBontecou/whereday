package com.bontecou.whereday

import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
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

@Composable internal fun LifetimeExportAccess(purchase:BillingState,checkout:Boolean,buy:()->Unit,restore:()->Unit) {
    Card(Modifier.fillMaxWidth().testTag("export-access")) {
        Column(Modifier.padding(16.dp),verticalArrangement=Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.export_access_title),style=MaterialTheme.typography.titleMedium)
            Text(stringResource(R.string.export_access_body))
            purchase.notice?.let {Text(billingNoticeText(it),Modifier.testTag("export-access-notice"),style=MaterialTheme.typography.bodySmall)}
            if(purchase.access==AccessState.active) Text(stringResource(R.string.export_access_verified))
            else purchase.product?.let {product->
                Button(enabled=!purchase.busy && !checkout && purchase.access!=AccessState.pending,onClick=buy,modifier=Modifier.testTag("export-access-buy")) {
                    Text(stringResource(R.string.export_access_buy,product.formattedPrice))
                }
            }
            OutlinedButton(enabled=!purchase.busy && !checkout,onClick=restore,modifier=Modifier.testTag("export-access-restore")) {Text(stringResource(R.string.export_access_restore))}
            if(purchase.busy) {LinearProgressIndicator(Modifier.fillMaxWidth());Text(stringResource(R.string.export_access_checking),style=MaterialTheme.typography.bodySmall)}
        }
    }
}
