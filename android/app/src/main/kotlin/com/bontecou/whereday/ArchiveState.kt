package com.bontecou.whereday

import com.bontecou.whereday.billing.AccessState
import com.bontecou.whereday.billing.BillingState

/** What Archive says about stored history. [loading] covers the first database read, so re-entry never flashes "Nothing recorded yet". */
internal enum class ArchiveHistory {loading,empty,stored}

internal fun archiveHistory(count:Long?)=when {
    count==null->ArchiveHistory.loading
    count<=0L->ArchiveHistory.empty
    else->ArchiveHistory.stored
}

/**
 * Whether Share / Save should read as locked. Only states that cannot succeed without a purchase lock the buttons;
 * [AccessState.checking] and [AccessState.verificationRequired] still try, because delivery refreshes access first.
 * A build without Play billing never locks them: every capability is free there.
 */
internal fun exportDeliveryLocked(purchase:BillingState)=purchase.enforced && exportDeliveryLocked(purchase.access)
internal fun exportDeliveryLocked(access:AccessState)=access==AccessState.free || access==AccessState.pending || access==AccessState.unavailable
