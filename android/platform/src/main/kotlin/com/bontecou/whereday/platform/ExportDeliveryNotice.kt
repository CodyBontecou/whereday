package com.bontecou.whereday.platform

import com.bontecou.whereday.billing.VerifiedAccessRequired
import kotlinx.coroutines.CancellationException

/** Stable presentation identities. Provider/exception text never becomes a delivery notice. */
enum class ExportDeliveryNotice(val diagnostic:String) {
    saved("Export saved and verified."),
    canceled("Export canceled. Files already written remain in the destination."),
    interrupted("Saving was interrupted. Resume or cancel the saved request."),
    failed("Saving could not finish. Resume or choose another destination; check the prior destination for an incomplete file."),
    unavailable("Saved export could not be restored. Discard the interrupted request or try again."),
    accessRequired("Verified lifetime export access is required. Recording, imports and previews remain free."),
    destinationChanged("The destination changed. Choose another destination to preserve it."),
    destinationUnavailable("Destination access was lost. Choose another destination."),
    preparedUnavailable("Prepared export changed or expired. Prepare it again."),
    folderRequired("Choose an export folder in Settings first."),
    preparationFailed("Export could not be prepared. Check storage and export settings, then try again."),
    multipleFiles("Use Share or the export folder for multiple files.");

    val isFailure get()=this!=saved && this!=canceled
}

class ExportDeliveryException(val notice:ExportDeliveryNotice):IllegalStateException(notice.diagnostic)

fun exportDeliveryNotice(error:Throwable,fallback:ExportDeliveryNotice=ExportDeliveryNotice.preparationFailed):ExportDeliveryNotice=when(error) {
    is CancellationException->ExportDeliveryNotice.interrupted
    is VerifiedAccessRequired->ExportDeliveryNotice.accessRequired
    is ExportDeliveryException->error.notice
    else->fallback
}
