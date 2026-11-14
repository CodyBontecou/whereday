package com.bontecou.whereday

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import com.bontecou.whereday.platform.*
import java.text.NumberFormat

@Composable internal fun exportDeliveryText(notice:ExportDeliveryNotice,fileCount:Int?=null):String {
    if(notice==ExportDeliveryNotice.saved && fileCount!=null) return pluralStringResource(R.plurals.export_delivery_saved_files,fileCount,NumberFormat.getIntegerInstance(LocalConfiguration.current.locales[0]).format(fileCount))
    return stringResource(when(notice) {
        ExportDeliveryNotice.saved->R.string.export_delivery_saved
        ExportDeliveryNotice.canceled->R.string.export_delivery_canceled
        ExportDeliveryNotice.interrupted->R.string.export_delivery_interrupted
        ExportDeliveryNotice.failed->R.string.export_delivery_failed
        ExportDeliveryNotice.unavailable->R.string.export_delivery_unavailable
        ExportDeliveryNotice.accessRequired->R.string.export_delivery_access_required
        ExportDeliveryNotice.destinationChanged->R.string.export_delivery_changed
        ExportDeliveryNotice.destinationUnavailable->R.string.export_delivery_destination_unavailable
        ExportDeliveryNotice.preparedUnavailable->R.string.export_delivery_prepared_unavailable
        ExportDeliveryNotice.folderRequired->R.string.export_delivery_folder_required
        ExportDeliveryNotice.preparationFailed->R.string.export_delivery_preparation_failed
        ExportDeliveryNotice.multipleFiles->R.string.export_delivery_multiple
    })
}

@Composable internal fun exportSettingsText(failure:ExportSettingsFailure):String=stringResource(when(failure) {
    ExportSettingsFailure.restore->R.string.export_settings_restore_error
    ExportSettingsFailure.save->R.string.export_settings_save_error
})
