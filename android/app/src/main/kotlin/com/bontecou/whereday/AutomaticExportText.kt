package com.bontecou.whereday

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.bontecou.whereday.platform.*

@Composable internal fun automaticExportFailureText(value:AutomaticExportFailure):String=stringResource(when(value) {
    AutomaticExportFailure.execution->R.string.automatic_export_failure_execution
    AutomaticExportFailure.interrupted->R.string.automatic_export_failure_interrupted
    AutomaticExportFailure.settingsUnavailable->R.string.automatic_export_failure_settings
    AutomaticExportFailure.purchase->R.string.automatic_export_failure_plan
    AutomaticExportFailure.pendingChange->R.string.automatic_export_failure_pending
})
@Composable internal fun automaticExportNoticeText(value:AutomaticExportNotice):String=stringResource(when(value) {
    AutomaticExportNotice.missingDay->R.string.automatic_export_notice_missing
    AutomaticExportNotice.jsonRewritten->R.string.automatic_export_notice_json
    AutomaticExportNotice.canceled->R.string.automatic_export_notice_canceled
    AutomaticExportNotice.previousStatus->R.string.automatic_export_notice_previous
})
@Composable internal fun automaticExportActionText(value:AutomaticExportActionNotice):String=stringResource(when(value) {
    AutomaticExportActionNotice.settingsSaved->R.string.automatic_export_action_saved
    AutomaticExportActionNotice.verified->R.string.automatic_export_action_verified
    AutomaticExportActionNotice.canceled->R.string.automatic_export_action_canceled
    AutomaticExportActionNotice.reset->R.string.automatic_export_action_reset
})
