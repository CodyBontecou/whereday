package com.bontecou.whereday

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.bontecou.whereday.platform.*

@Composable internal fun AutomationScope.title():String=stringResource(when(this) {
    AutomationScope.summary->R.string.automation_scope_summary
    AutomationScope.visits->R.string.automation_scope_visits
    AutomationScope.outings->R.string.automation_scope_outings
    AutomationScope.renameVisits->R.string.automation_scope_rename_visits
    AutomationScope.renameOutings->R.string.automation_scope_rename_outings
    AutomationScope.recording->R.string.automation_scope_recording
    AutomationScope.places->R.string.automation_scope_places
    AutomationScope.manualVisits->R.string.automation_scope_manual_visits
    AutomationScope.exports->R.string.automation_scope_exports
})
@Composable internal fun AutomationScope.detail():String=stringResource(when(this) {
    AutomationScope.summary->R.string.automation_detail_summary
    AutomationScope.visits->R.string.automation_detail_visits
    AutomationScope.outings->R.string.automation_detail_outings
    AutomationScope.renameVisits->R.string.automation_detail_rename_visits
    AutomationScope.renameOutings->R.string.automation_detail_rename_outings
    AutomationScope.recording->R.string.automation_detail_recording
    AutomationScope.places->R.string.automation_detail_places
    AutomationScope.manualVisits->R.string.automation_detail_manual_visits
    AutomationScope.exports->R.string.automation_detail_exports_plan
})
@Composable internal fun automationFailureText(failure:AutomationFailure)=stringResource(when(failure) {
    AutomationFailure.save->R.string.automation_failure_save
    AutomationFailure.disable->R.string.automation_failure_disable
    AutomationFailure.load->R.string.automation_failure_load
    AutomationFailure.install->R.string.automation_failure_install
})
@Composable internal fun automationNoticeText(notice:AutomationNotice)=stringResource(when(notice) {
    AutomationNotice.saved->R.string.automation_notice_saved
    AutomationNotice.disabled->R.string.automation_notice_disabled
})
